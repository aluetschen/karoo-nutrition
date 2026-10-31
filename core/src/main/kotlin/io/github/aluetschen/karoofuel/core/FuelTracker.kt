package io.github.aluetschen.karoofuel.core

import kotlin.math.max

/** Everything the app tracks during a ride. Stored so a ride survives an app restart. */
data class FuelState(
    /** Recorded ride time (without pauses), in seconds. */
    val rideSeconds: Double = 0.0,
    val kcalTotal: Double = 0.0,
    val carbsBurnedG: Double = 0.0,
    val carbsEatenG: Double = 0.0,
    /** Carbohydrates "owed" since the last gel. A gel is due once this reaches the gel size. */
    val fuelDueG: Double = 0.0,
    val gelsTaken: Int = 0,
    /** Ride time of the last gel alert (null = no alert yet). */
    val lastGelAtSeconds: Double? = null,
    /** The low-glycogen warning was already shown in this ride. */
    val glycogenWarningShown: Boolean = false,
    /** Smoothed current values for display (about 30 s). */
    val kcalPerHour: Double? = null,
    val carbsBurnedPerHour: Double? = null,
    /** Averaged over about 10 min, used for forecasts. */
    val avgTargetCarbsPerHour: Double? = null,
    val avgCarbsBurnedPerHour: Double? = null,
    val source: EnergySource? = null,
)

/** What happened in a time step. */
data class TickResult(
    /** A gel is due now. The caller shows the alert and then calls [FuelTracker.registerGel]. */
    val gelDue: Boolean = false,
    /** Glycogen just fell below the warning level (only once per ride). */
    val glycogenLow: Boolean = false,
)

/**
 * Integrates energy consumption over time and decides when the next gel is due.
 * Called once per second during a recorded ride.
 */
class FuelTracker(state: FuelState = FuelState()) {
    var state: FuelState = state
        private set

    fun tick(dtSeconds: Double, powerW: Double?, hr: Double?, profile: RiderProfile, settings: FuelSettings): TickResult {
        if (dtSeconds <= 0) return TickResult()
        val s = state
        val rideSeconds = s.rideSeconds + dtSeconds
        val estimate = EnergyModel.estimateEnergy(powerW, hr, profile, settings)

        state = if (estimate == null) {
            // No sensor data: keep the time running, add no energy.
            s.copy(rideSeconds = rideSeconds, kcalPerHour = null, carbsBurnedPerHour = null, source = null)
        } else {
            val dtHours = dtSeconds / 3600.0
            val carbFraction = EnergyModel.carbFraction(EnergyModel.intensity(powerW, hr, profile))
            val carbsPerHour = estimate.kcalPerHour * carbFraction / EnergyModel.KCAL_PER_G_CARB
            val target = EnergyModel.targetCarbsPerHour(carbsPerHour, settings)
            val updated = s.copy(
                rideSeconds = rideSeconds,
                kcalTotal = s.kcalTotal + estimate.kcalPerHour * dtHours,
                carbsBurnedG = s.carbsBurnedG + carbsPerHour * dtHours,
                kcalPerHour = ema(s.kcalPerHour, estimate.kcalPerHour, dtSeconds, 30.0),
                carbsBurnedPerHour = ema(s.carbsBurnedPerHour, carbsPerHour, dtSeconds, 30.0),
                avgTargetCarbsPerHour = ema(s.avgTargetCarbsPerHour, target, dtSeconds, 600.0),
                avgCarbsBurnedPerHour = ema(s.avgCarbsBurnedPerHour, carbsPerHour, dtSeconds, 600.0),
                source = estimate.source,
            )
            if (isFuelingActive(updated, profile, settings)) {
                updated.copy(fuelDueG = updated.fuelDueG + target * dtHours)
            } else {
                updated
            }
        }

        var glycogenLow = false
        if (!state.glycogenWarningShown && glycogenFraction(profile, settings) < settings.glycogenWarningLevel) {
            glycogenLow = true
            state = state.copy(glycogenWarningShown = true)
        }
        return TickResult(gelDue = isGelDue(settings), glycogenLow = glycogenLow)
    }

    /** Estimated remaining glycogen as a share of the full stores (0..1). Eaten carbs spare glycogen. */
    fun glycogenFraction(profile: RiderProfile, settings: FuelSettings): Double {
        val store = EnergyModel.glycogenStoreG(profile, settings)
        if (store <= 0) return 1.0
        return ((store - state.carbsBurnedG + state.carbsEatenG) / store).coerceIn(0.0, 1.0)
    }

    /** Has the fueling phase started (time or glycogen rule)? */
    fun isFuelingActive(profile: RiderProfile, settings: FuelSettings): Boolean = isFuelingActive(state, profile, settings)

    private fun isFuelingActive(s: FuelState, profile: RiderProfile, settings: FuelSettings): Boolean = when (settings.startMode) {
        StartMode.TIME -> s.rideSeconds >= settings.startAfterMinutes * 60.0
        StartMode.GLYCOGEN -> s.carbsBurnedG - s.carbsEatenG >= depletionThresholdG(profile, settings)
    }

    private fun depletionThresholdG(profile: RiderProfile, settings: FuelSettings): Double =
        EnergyModel.glycogenStoreG(profile, settings) * settings.glycogenStartDepletion

    private fun isGelDue(settings: FuelSettings): Boolean {
        val s = state
        if (s.fuelDueG < settings.gelCarbsG) return false
        val last = s.lastGelAtSeconds ?: return true
        return s.rideSeconds - last >= settings.minMinutesBetweenGels * 60.0
    }

    /** Progress towards the next gel (0..1), for the graphical field. */
    fun gelProgress(settings: FuelSettings): Double =
        if (settings.gelCarbsG <= 0) 0.0 else (state.fuelDueG / settings.gelCarbsG).coerceIn(0.0, 1.0)

    /** A gel was taken (after an alert, or as an extra gel via a button). */
    fun registerGel(settings: FuelSettings) {
        val s = state
        state = s.copy(
            gelsTaken = s.gelsTaken + 1,
            carbsEatenG = s.carbsEatenG + settings.gelCarbsG,
            fuelDueG = s.fuelDueG - settings.gelCarbsG,
            lastGelAtSeconds = s.rideSeconds,
        )
    }

    /**
     * The last gel was NOT taken: undo it. The reminder comes back after
     * [FuelSettings.minMinutesBetweenGels] at the earliest.
     */
    fun undoLastGel(settings: FuelSettings) {
        val s = state
        if (s.gelsTaken == 0) return
        state = s.copy(
            gelsTaken = s.gelsTaken - 1,
            carbsEatenG = max(0.0, s.carbsEatenG - settings.gelCarbsG),
            fuelDueG = s.fuelDueG + settings.gelCarbsG,
            lastGelAtSeconds = s.rideSeconds,
        )
    }

    /** Forecast: minutes until the next gel, or null if no estimate is possible yet. */
    fun minutesToNextGel(profile: RiderProfile, settings: FuelSettings): Double? {
        val s = state
        val rate = s.avgTargetCarbsPerHour ?: return null
        if (rate < 1.0) return null
        val waitForStart = if (isFuelingActive(profile, settings)) {
            0.0
        } else {
            when (settings.startMode) {
                StartMode.TIME -> max(0.0, settings.startAfterMinutes * 60.0 - s.rideSeconds) / 60.0
                StartMode.GLYCOGEN -> {
                    val burnRate = s.avgCarbsBurnedPerHour ?: return null
                    if (burnRate < 1.0) return null
                    val missing = depletionThresholdG(profile, settings) - (s.carbsBurnedG - s.carbsEatenG)
                    max(0.0, missing) / burnRate * 60.0
                }
            }
        }
        val byCarbs = max(0.0, settings.gelCarbsG - s.fuelDueG) / rate * 60.0
        val bySpacing = s.lastGelAtSeconds?.let { max(0.0, settings.minMinutesBetweenGels - (s.rideSeconds - it) / 60.0) } ?: 0.0
        return max(waitForStart + byCarbs, bySpacing)
    }

    /** Continue a saved ride (e.g. after an app restart). */
    fun restoreState(saved: FuelState) {
        state = saved
    }

    fun reset() {
        state = FuelState()
    }

    /** Exponential moving average with time constant [tauSeconds]. */
    private fun ema(previous: Double?, value: Double, dt: Double, tauSeconds: Double): Double {
        if (previous == null) return value
        val alpha = dt / (tauSeconds + dt)
        return previous + alpha * (value - previous)
    }
}
