package io.github.aluetschen.karoofuel

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Entry point for the Karoo: registers the data fields and the bonus actions.
 */
class KarooFuelExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val engine by lazy { FuelEngine(this, karooSystem) }

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            FuelDataType(engine, extension, "kcal-rate") { it.kcalPerHour },
            FuelDataType(engine, extension, "kcal-total") { it.kcalTotal },
            FuelDataType(engine, extension, "carbs-rate") { it.carbsBurnedPerHour },
            FuelDataType(engine, extension, "next-gel") { it.minutesToNextGel },
            FuelDataType(engine, extension, "gels") { it.gelsTaken.toDouble() },
            FuelDataType(engine, extension, "glycogen") { it.glycogenPercent },
            FuelGaugeDataType(engine, extension),
        )
    }

    private val fitRecorder by lazy { FitRecorder(engine) }

    override fun startFit(emitter: Emitter<FitEffect>) {
        fitRecorder.start(emitter)
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            if (connected) engine.start()
        }
    }

    override fun onBonusAction(actionId: String) {
        when (actionId) {
            ACTION_GEL_TAKEN -> engine.gelTaken()
            ACTION_GEL_SKIPPED -> engine.gelSkipped()
        }
    }

    override fun onDestroy() {
        engine.stop()
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo-fuel"
        const val ACTION_GEL_TAKEN = "gel-taken"
        const val ACTION_GEL_SKIPPED = "gel-skipped"
    }
}

/** A numeric data field that shows one value from [FuelSnapshot]. */
class FuelDataType(
    private val engine: FuelEngine,
    extension: String,
    typeId: String,
    private val selector: (FuelSnapshot) -> Double?,
) : DataTypeImpl(extension, typeId) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            engine.snapshot
                .map { selector(it) }
                .distinctUntilChanged()
                .collect { value ->
                    emitter.onNext(
                        if (value == null) {
                            StreamState.NotAvailable
                        } else {
                            StreamState.Streaming(DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to value)))
                        },
                    )
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}
