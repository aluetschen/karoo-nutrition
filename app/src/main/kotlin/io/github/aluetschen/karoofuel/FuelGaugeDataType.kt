package io.github.aluetschen.karoofuel

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import io.github.aluetschen.karoofuel.core.EnergySource
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Graphical data field "Fuel gauge":
 * - big: minutes until the next gel
 * - bar: progress towards the next gel (green -> orange -> red)
 * - top right: energy source (W / ♥) and number of gels
 * - bottom line (if there is room): kcal/h and glycogen
 */
class FuelGaugeDataType(
    private val engine: FuelEngine,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    /** Only the values that are drawn, rounded so the view is redrawn only on visible changes. */
    private data class GaugeState(
        val minutes: Int?,
        val progress: Int,
        val gels: Int,
        val kcalPerHour: Int?,
        val glycogen: Int,
        val fuelingActive: Boolean,
        /** Where the energy currently comes from (null = no sensor data). */
        val source: EnergySource?,
    )

    // The numeric value behind the graphic (e.g. for the field preview): minutes until the next gel
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            engine.snapshot.map { it.minutesToNextGel }.distinctUntilChanged().collect { value ->
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

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true))
        val job = CoroutineScope(Dispatchers.Default).launch {
            if (config.preview) {
                emitter.updateView(render(context, config, GaugeState(12, 52, 2, 650, 71, true, EnergySource.POWER)))
                return@launch
            }
            engine.snapshot
                .map {
                    GaugeState(
                        minutes = it.minutesToNextGel?.roundToInt(),
                        progress = (it.gelProgress * 100).roundToInt(),
                        gels = it.gelsTaken,
                        kcalPerHour = it.kcalPerHour?.let { k -> (k / 10).roundToInt() * 10 },
                        glycogen = it.glycogenPercent.roundToInt(),
                        fuelingActive = it.fuelingActive,
                        source = it.source,
                    )
                }
                .distinctUntilChanged()
                .collect { emitter.updateView(render(context, config, it)) }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun render(context: Context, config: ViewConfig, g: GaugeState): RemoteViews {
        val (viewW, viewH) = config.viewSize
        // Limit the image size (Android limits how much data a view update may contain)
        val scale = minOf(1.0, sqrt(MAX_PIXELS.toDouble() / (viewW.coerceAtLeast(1) * viewH.coerceAtLeast(1)))).toFloat()
        val w = (viewW * scale).roundToInt().coerceAtLeast(1)
        val h = (viewH * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) Color.WHITE else Color.BLACK
        val mutedColor = if (night) Color.rgb(170, 170, 170) else Color.rgb(90, 90, 90)
        val trackColor = if (night) Color.rgb(60, 60, 60) else Color.rgb(215, 215, 215)

        val density = context.resources.displayMetrics.scaledDensity * scale
        val pad = 4f * density
        val big = (config.textSize * density).coerceAtMost(h * 0.5f)
        val small = (big * 0.38f).coerceAtLeast(10f * density)
        val showBottom = h > big + small * 2.6f + pad * 4

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1) Minutes until the next gel
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = big
        paint.color = textColor
        val minutesText = g.minutes?.toString() ?: "--"
        val baseline = pad + big * 0.85f
        canvas.drawText(minutesText, pad, baseline, paint)
        val minutesWidth = paint.measureText(minutesText)

        paint.typeface = Typeface.DEFAULT
        paint.textSize = small
        paint.color = mutedColor
        canvas.drawText(context.getString(R.string.gauge_minutes), pad * 2 + minutesWidth, baseline, paint)
        // Top right: number of gels, and before it the source of the energy value
        // ("W" = power meter, orange "♥" = heart rate fallback, "--" = no data)
        val gelsText = context.getString(R.string.gauge_gels, g.gels)
        val gelsX = w - pad - paint.measureText(gelsText)
        canvas.drawText(gelsText, gelsX, baseline, paint)
        val sourceText = when (g.source) {
            EnergySource.POWER -> context.getString(R.string.gauge_source_power)
            EnergySource.HEART_RATE -> context.getString(R.string.gauge_source_hr)
            null -> "--"
        }
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = if (g.source == EnergySource.HEART_RATE) Color.rgb(245, 124, 0) else mutedColor
        canvas.drawText(sourceText, gelsX - pad * 2 - paint.measureText(sourceText), baseline, paint)
        paint.typeface = Typeface.DEFAULT
        paint.color = mutedColor

        // 2) Bar: progress towards the next gel
        val barTop = baseline + pad * 1.5f
        val barHeight = (h * 0.16f).coerceIn(6f * density, 18f * density)
        val bar = RectF(pad, barTop, w - pad, barTop + barHeight)
        val radius = barHeight / 2
        paint.color = trackColor
        canvas.drawRoundRect(bar, radius, radius, paint)
        if (g.progress > 0) {
            paint.color = when {
                g.progress >= 90 -> Color.rgb(229, 57, 53) // red: gel almost due
                g.progress >= 60 -> Color.rgb(245, 124, 0) // orange
                else -> Color.rgb(67, 160, 71) // green
            }
            val fill = RectF(bar.left, bar.top, bar.left + bar.width() * g.progress / 100f, bar.bottom)
            canvas.drawRoundRect(fill, radius, radius, paint)
        }

        // 3) Bottom line: kcal/h and glycogen (or the start phase)
        if (showBottom) {
            paint.textSize = small
            paint.color = mutedColor
            val y = bar.bottom + pad + small
            val kcal = g.kcalPerHour?.let { context.getString(R.string.gauge_kcal, it) } ?: context.getString(R.string.gauge_kcal_none)
            canvas.drawText(kcal, pad, y, paint)
            val right = if (g.fuelingActive) {
                context.getString(R.string.gauge_glycogen, g.glycogen)
            } else {
                context.getString(R.string.gauge_start_phase, g.glycogen)
            }
            canvas.drawText(right, w - pad - paint.measureText(right), y, paint)
        }

        return RemoteViews(context.packageName, R.layout.view_gauge).apply {
            setImageViewBitmap(R.id.gauge_image, bitmap)
        }
    }

    companion object {
        const val TYPE_ID = "fuel-gauge"
        private const val MAX_PIXELS = 120_000
    }
}
