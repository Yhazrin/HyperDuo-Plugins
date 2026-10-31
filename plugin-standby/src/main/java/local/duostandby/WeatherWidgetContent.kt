package local.duostandby

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/**
 * Weather conditions widget for the StandBy widget stack.
 * Original: Weather.app/PlugIns/WeatherWidget.appex (TemperatureComplicationWidget /
 * SunEventsContentView). Values come from DigitalWeatherRepository (real Open-Meteo);
 * with no data the widget draws nothing rather than a fabricated temperature.
 */
class WeatherWidgetContent(private val context: android.content.Context) {
    private val fonts = OriginalFonts(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, bounds: RectF, night: Boolean) {
        val data = DigitalWeatherRepository.current ?: return
        if (data.tempC == null) return
        val size = min(bounds.width(), bounds.height())
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val color = if (night) VisualTokens.NIGHT_RED else Color.WHITE
        paint.reset(); paint.isAntiAlias = true
        DigitalFaceWidgets.drawConditionIcon(c, paint, cx - size * 0.17f, cy - size * 0.24f, size * 0.34f, data.code ?: 0, color)
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.softCompressedSemibold
        paint.textSize = size * 0.26f
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        c.drawText("${data.tempC}°", cx, cy + size * 0.12f, paint)
        val sun = DigitalWeatherRepository.nextSunEvent(java.time.LocalTime.now()) ?: return
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.ui
        paint.textSize = size * 0.12f
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        c.drawText(sun.second.take(5), cx, cy + size * 0.36f, paint)
    }
}
