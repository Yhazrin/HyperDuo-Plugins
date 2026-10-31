package local.duostandby

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalTime

/**
 * Weather conditions and sun-event slots for the digital face.
 *
 * Original components: WeatherWidget.appex (TemperatureComplicationWidget /
 * SunEventsContentView) hosted by SimpleDigitalPosterExtension as
 * primaryWidget/secondaryWidget. Icons there are SF Symbols; Android has no SF
 * Symbols, so these canvas icons are an explicit visual adapter. Values come from
 * DigitalWeatherRepository (real Open-Meteo data) and stay hidden when absent.
 */
object DigitalFaceWidgets {
    fun draw(canvas: Canvas, width: Float, baseline: Float, dateRight: Float,
             fontPx: Float, color: Int, typeface: Typeface?,
             weather: DigitalWeatherRepository.Snapshot?,
             sun: Pair<String, String>?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.textSize = fontPx
        paint.color = color
        paint.textAlign = Paint.Align.LEFT
        val icon = fontPx * 1.05f
        // Weather conditions: cloud/rain/sun icon + temperature, right after the date.
        if (weather?.tempC != null) {
            var x = dateRight + fontPx * 0.55f
            drawConditionIcon(canvas, paint, x, baseline - fontPx * 0.35f, icon, weather.code ?: 0, color)
            x += icon + fontPx * 0.18f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("${weather.tempC}°", x, baseline, paint)
        }
        // Sun event: trailing edge, icon + time (next sunrise or sunset).
        if (sun != null) {
            val time = sun.second.take(5)
            paint.textAlign = Paint.Align.RIGHT
            val textWidth = paint.measureText(time)
            val textRight = width - fontPx * 1.4f
            canvas.drawText(time, textRight, baseline, paint)
            val iconX = textRight - textWidth - icon - fontPx * 0.35f
            drawSunEventIcon(canvas, paint, iconX, baseline - fontPx * 0.35f, icon, sun.first == "sunrise", color)
        }
    }

    fun drawConditionIcon(canvas: Canvas, paint: Paint, left: Float, centerY: Float, size: Float, code: Int, color: Int) {
        if (WeatherSymbols.drawCondition(canvas, paint, code, left, centerY, size, color)) return
        paint.style = Paint.Style.FILL
        paint.color = color
        val l = left; val t = centerY - size * 0.28f; val r = left + size; val b = centerY + size * 0.24f
        if (code == 0 || code == 1) {
            // Clear / mainly clear: small sun with rays (SF Symbol shape is unavailable).
            val cx = left + size * 0.5f
            canvas.drawCircle(cx, centerY, size * 0.20f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.08f
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r1 = size * 0.30f; val r2 = size * 0.44f
                canvas.drawLine(cx + (Math.cos(a) * r1).toFloat(), centerY + (Math.sin(a) * r1).toFloat(),
                    cx + (Math.cos(a) * r2).toFloat(), centerY + (Math.sin(a) * r2).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            return
        }
        // Cloud body: three circles + a rounded base.
        canvas.drawCircle(l + size * 0.32f, t + size * 0.22f, size * 0.22f, paint)
        canvas.drawCircle(l + size * 0.56f, t + size * 0.14f, size * 0.28f, paint)
        canvas.drawCircle(l + size * 0.78f, t + size * 0.24f, size * 0.20f, paint)
        canvas.drawRoundRect(RectF(l + size * 0.18f, t + size * 0.22f, r - size * 0.06f, b), size * 0.16f, size * 0.16f, paint)
        if (code in 51..67 || code in 80..82) {
            // Rain: two short strokes below the cloud.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.09f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(l + size * 0.38f, b + size * 0.06f, l + size * 0.32f, b + size * 0.24f, paint)
            canvas.drawLine(l + size * 0.62f, b + size * 0.06f, l + size * 0.56f, b + size * 0.24f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    fun drawSunEventIcon(canvas: Canvas, paint: Paint, left: Float, centerY: Float, size: Float, sunrise: Boolean, color: Int) {
        if (WeatherSymbols.drawSunEvent(canvas, paint, sunrise, left, centerY, size, color)) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        val cx = left + size * 0.5f
        val horizon = centerY + size * 0.18f
        // Half sun above a horizon line.
        canvas.drawArc(RectF(cx - size * 0.24f, horizon - size * 0.34f, cx + size * 0.24f, horizon + size * 0.14f), 180f, 180f, false, paint)
        canvas.drawLine(left + size * 0.06f, horizon, left + size * 0.94f, horizon, paint)
        // Arrow: up for sunrise, down for sunset.
        val ax = cx
        val ay = if (sunrise) horizon - size * 0.62f else horizon + size * 0.38f
        val dy = if (sunrise) size * 0.22f else -size * 0.22f
        canvas.drawLine(ax, ay, ax, ay + dy, paint)
        canvas.drawLine(ax, ay, ax - size * 0.12f, ay + dy * 0.55f, paint)
        canvas.drawLine(ax, ay, ax + size * 0.12f, ay + dy * 0.55f, paint)
        paint.style = Paint.Style.FILL
    }
}
