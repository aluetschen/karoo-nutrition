package io.github.aluetschen.karoofuel

import android.content.Context
import android.os.SystemClock
import io.github.aluetschen.karoofuel.core.EnergySource
import io.github.aluetschen.karoofuel.core.FuelTracker
import io.github.aluetschen.karoofuel.core.RiderProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the data fields show. */
data class FuelSnapshot(
    val recording: Boolean = false,
    val kcalPerHour: Double? = null,
    val kcalTotal: Double = 0.0,
    val carbsBurnedPerHour: Double? = null,
    val minutesToNextGel: Double? = null,
    val gelsTaken: Int = 0,
    val source: EnergySource? = null,
)

/**
 * Heart of the app: reads power, heart rate, profile and ride state from the Karoo,
 * calculates once per second and triggers the gel alert.
 */
class FuelEngine(private val context: Context, private val karoo: KarooSystemService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = FuelPrefs(context)
    private val tracker = FuelTracker()
    private val lock = Any()

    private val _snapshot = MutableStateFlow(FuelSnapshot())
    val snapshot: StateFlow<FuelSnapshot> = _snapshot

    private class Sample(val value: Double, val atMs: Long)

    @Volatile private var power: Sample? = null
    @Volatile private var heartRate: Sample? = null
    @Volatile private var profile: UserProfile? = null
    @Volatile private var rideState: RideState? = null
    private var started = false
    private var lastSaveMs = 0L

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            karoo.streamDataFlow(DataType.Type.POWER).collect { power = it.toSample() }
        }
        scope.launch {
            karoo.streamDataFlow(DataType.Type.HEART_RATE).collect { heartRate = it.toSample() }
        }
        scope.launch {
            karoo.consumerFlow<UserProfile>().collect { profile = it }
        }
        scope.launch {
            karoo.consumerFlow<RideState>().collect { onRideState(it) }
        }
        scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                // cap at 5 s in case the device was asleep
                val dt = ((now - last) / 1000.0).coerceAtMost(5.0)
                last = now
                tick(dt, now)
            }
        }
    }

    fun stop() {
        synchronized(lock) { prefs.saveState(tracker.state) }
        scope.cancel()
    }

    private fun onRideState(state: RideState) {
        synchronized(lock) {
            val previous = rideState
            rideState = state
            when {
                // App (re)started in the middle of a ride: continue with the saved values
                previous == null && state !is RideState.Idle -> prefs.loadState()?.let { tracker.restoreState(it) }
                // Ride finished or discarded: start fresh next time
                state is RideState.Idle -> {
                    tracker.reset()
                    prefs.clearState()
                }
                // Pause: save
                state is RideState.Paused -> prefs.saveState(tracker.state)
            }
            publish()
        }
    }

    private fun tick(dt: Double, nowMs: Long) {
        val recording = rideState is RideState.Recording
        var gelDue = false
        synchronized(lock) {
            if (recording) {
                val settings = prefs.settings()
                gelDue = tracker.tick(dt, power.fresh(nowMs), heartRate.fresh(nowMs), riderProfile(), settings)
                if (gelDue) tracker.registerGel(settings)
                if (nowMs - lastSaveMs > 30_000) {
                    prefs.saveState(tracker.state)
                    lastSaveMs = nowMs
                }
            }
            publish()
        }
        if (gelDue) showGelAlert()
    }

    /** Bonus action: an extra gel was eaten. */
    fun gelTaken() {
        synchronized(lock) {
            tracker.registerGel(prefs.settings())
            publish()
        }
    }

    /** Bonus action: the last gel was not taken -> remind again later. */
    fun gelSkipped() {
        synchronized(lock) {
            tracker.undoLastGel(prefs.settings())
            publish()
        }
    }

    private fun publish() {
        val s = tracker.state
        _snapshot.value = FuelSnapshot(
            recording = rideState is RideState.Recording,
            kcalPerHour = s.kcalPerHour,
            kcalTotal = s.kcalTotal,
            carbsBurnedPerHour = s.carbsBurnedPerHour,
            minutesToNextGel = tracker.minutesToNextGel(prefs.settings()),
            gelsTaken = s.gelsTaken,
            source = s.source,
        )
    }

    private fun showGelAlert() {
        val settings = prefs.settings()
        val gels = tracker.state.gelsTaken
        karoo.dispatch(
            InRideAlert(
                id = "gel-due",
                icon = R.drawable.ic_gel,
                title = context.getString(R.string.alert_title),
                detail = context.getString(R.string.alert_detail, settings.gelCarbsG.roundToInt(), gels),
                autoDismissMs = 15_000,
                backgroundColor = R.color.alert_background,
                textColor = R.color.alert_text,
            ),
        )
        karoo.dispatch(
            PlayBeepPattern(
                listOf(
                    PlayBeepPattern.Tone(frequency = 3000, durationMs = 200),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 100),
                    PlayBeepPattern.Tone(frequency = 3000, durationMs = 200),
                ),
            ),
        )
    }

    private fun riderProfile(): RiderProfile {
        val p = profile
        val weight = p?.weight?.toDouble()?.takeIf { it > 20 } ?: 75.0
        return RiderProfile(
            weightKg = weight,
            ftpWatts = p?.ftp ?: 0,
            maxHr = p?.maxHr ?: 0,
            restingHr = p?.restingHr ?: 0,
            age = prefs.age(),
            sex = prefs.sex(),
        )
    }

    private fun StreamState.toSample(): Sample? =
        (this as? StreamState.Streaming)?.dataPoint?.singleValue?.let { Sample(it, SystemClock.elapsedRealtime()) }

    /** Values older than 5 s count as missing (sensor lost). */
    private fun Sample?.fresh(nowMs: Long): Double? = this?.takeIf { nowMs - it.atMs <= 5_000 }?.value
}
