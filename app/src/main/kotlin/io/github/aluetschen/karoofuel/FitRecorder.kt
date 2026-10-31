package io.github.aluetschen.karoofuel

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Writes the app's values into the ride's FIT file as "developer fields".
 *
 * - Every second (record): kcal/h, carbs g/h, glycogen %, number of gels
 * - Summary (session): kcal, carbs burned / eaten, gels, final glycogen %
 *
 * Evaluation tools like intervals.icu or Golden Cheetah can display these fields.
 */
class FitRecorder(private val engine: FuelEngine) {
    private fun field(number: Int, name: String, units: String) = DeveloperField(
        fieldDefinitionNumber = number.toShort(),
        fitBaseTypeId = FLOAT32,
        fieldName = name,
        units = units,
    )

    // Field numbers must start at 0 and increase by 1 and must never change between versions.
    private val kcalRate = field(0, "Fuel kcal rate", "kcal/h")
    private val carbRate = field(1, "Fuel carb rate", "g/h")
    private val glycogen = field(2, "Fuel glycogen", "%")
    private val gels = field(3, "Fuel gels", "gels")
    private val kcalTotal = field(4, "Fuel kcal total", "kcal")
    private val carbsBurned = field(5, "Fuel carbs burned", "g")
    private val carbsEaten = field(6, "Fuel carbs eaten", "g")

    fun start(emitter: Emitter<FitEffect>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            var secondsSinceSession = 0
            var wasPaused = false
            while (isActive) {
                delay(1000)
                val s = engine.snapshot.value
                when {
                    s.recording -> {
                        wasPaused = false
                        emitter.onNext(WriteToRecordMesg(recordValues(s)))
                        // Summary every 5 min. The last value written is saved at the end of the ride.
                        if (++secondsSinceSession >= 300) {
                            emitter.onNext(WriteToSessionMesg(sessionValues(s)))
                            secondsSinceSession = 0
                        }
                    }
                    s.paused && !wasPaused -> {
                        // On pause (also right before the ride is saved): write the summary
                        emitter.onNext(WriteToSessionMesg(sessionValues(s)))
                        wasPaused = true
                        secondsSinceSession = 0
                    }
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun recordValues(s: FuelSnapshot): List<FieldValue> = buildList {
        s.kcalPerHour?.let { add(FieldValue(kcalRate, it)) }
        s.carbsBurnedPerHour?.let { add(FieldValue(carbRate, it)) }
        add(FieldValue(glycogen, s.glycogenPercent))
        add(FieldValue(gels, s.gelsTaken.toDouble()))
    }

    private fun sessionValues(s: FuelSnapshot): List<FieldValue> = listOf(
        FieldValue(kcalTotal, s.kcalTotal),
        FieldValue(carbsBurned, s.carbsBurnedG),
        FieldValue(carbsEaten, s.carbsEatenG),
        FieldValue(gels, s.gelsTaken.toDouble()),
        FieldValue(glycogen, s.glycogenPercent),
    )

    private companion object {
        /** FIT base type float32 (Garmin FIT SDK). */
        const val FLOAT32: Short = 136
    }
}
