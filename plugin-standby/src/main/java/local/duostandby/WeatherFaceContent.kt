package local.duostandby

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WEATHER face (WeatherFace.appex: CLKUITimeLabel + complication picker,
 * auto-cycling twelveHourForecast / conditionDetails / fourDayForecast /
 * nextHourPrecipitation).
 *
 * Recovered: posterData {"style":"Complication","complication":"automatic"} and
 * the four complication identities. The SwiftUI geometry/background are not yet
 * recovered, so the layout and background gradient are explicit Android adapters;
 * values come from DigitalWeatherRepository (real Open-Meteo data), never faked.
 */
object WeatherFaceContent {
    fun draw(c: Canvas, w: Float, h: Float, time: LocalDateTime, is24: Boolean,
             data: DigitalWeatherRepository.Snapshot?, fonts: OriginalFonts, night: Boolean,
             context: android.content.Context, phaseHint: Int = 0) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val originalBackground = WeatherFaceAssets.background(context, data?.code)
        if (originalBackground != null) {
            WeatherFaceAssets.drawCover(c, originalBackground, w, h, paint)
        } else {
            // Non-clear/mostly-clear conditions have no recovered WeatherFace asset; the original
            // uses WeatherKit sky rendering. Use a condition-tinted adapter instead of the old
            // generic blue so rain/overcast no longer end in the same deep-blue band.
            val day = time.hour in 6..18
            val code = data?.code ?: -1
            val top: Int
            val bottom: Int
            when {
                code == 3 -> { top = if (day) Color.rgb(122, 138, 154) else Color.rgb(30, 38, 50); bottom = if (day) Color.rgb(72, 86, 102) else Color.rgb(14, 20, 28) }
                code in 45..48 -> { top = if (day) Color.rgb(142, 150, 158) else Color.rgb(34, 40, 48); bottom = if (day) Color.rgb(98, 106, 114) else Color.rgb(16, 22, 28) }
                code in 51..67 -> { top = if (day) Color.rgb(100, 116, 132) else Color.rgb(26, 34, 46); bottom = if (day) Color.rgb(58, 72, 88) else Color.rgb(12, 18, 26) }
                code in 71..77 || code in 85..86 -> { top = if (day) Color.rgb(170, 182, 194) else Color.rgb(36, 44, 54); bottom = if (day) Color.rgb(122, 136, 150) else Color.rgb(18, 24, 32) }
                code in 80..82 -> { top = if (day) Color.rgb(80, 96, 112) else Color.rgb(22, 28, 38); bottom = if (day) Color.rgb(46, 58, 72) else Color.rgb(10, 14, 20) }
                code in 95..99 -> { top = if (day) Color.rgb(74, 78, 96) else Color.rgb(24, 26, 36); bottom = if (day) Color.rgb(40, 42, 56) else Color.rgb(8, 10, 16) }
                else -> { top = if (day) Color.rgb(91, 143, 196) else Color.rgb(18, 28, 46); bottom = if (day) Color.rgb(38, 64, 96) else Color.rgb(6, 10, 18) }
            }
            paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // CLKUITimeLabel: large soft-numeric time at the top.
        val text = time.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm", Locale.US))
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.softCompressedSemibold
        paint.textSize = h * 0.30f
        paint.color = if (night) VisualTokens.NIGHT_RED else Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        c.drawText(text, w / 2f, h * 0.34f, paint)

        if (data?.tempC == null) return
        // Auto cycle over the recovered complications; nextHour only when minutely data exists.
        val phases = if (data.nextHourMm.isNotEmpty()) 4L else 3L
        // Original evidence (standby-1.mp4) shows the user swiping between complication pages;
        // the previous 8s auto-cycle had no original evidence. Phase now comes from the face state.
        val phase = phaseHint.coerceIn(0, phases.toInt() - 1)
        val panelTop = h * 0.50f
        // standby-1.mp4 frame_0005: the weather face shows one dot per complication page
        // (4 dots, bottom-center, current filled), not only the complication itself.
        val pageDots = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotCount = phases.toInt().coerceAtLeast(1)
        val dotGap = h * 0.018f
        val dotRadius = h * 0.006f
        val dotCx = w / 2f
        val dotCy = h - h * 0.035f
        for (i in 0 until dotCount) {
            pageDots.color = Color.argb(if (i == phase) 235 else 80, 255, 255, 255)
            c.drawCircle(dotCx + (i - (dotCount - 1) / 2f) * dotGap, dotCy, dotRadius, pageDots)
        }
        when (phase) {
            0 -> conditions(c, w, h, panelTop, data, fonts, paint, context)
            1 -> hours(c, w, h, panelTop, data, fonts, paint)
            2 -> days(c, w, h, panelTop, data, fonts, paint)
            else -> nextHour(c, w, h, panelTop, data, fonts, paint)
        }
    }

    private fun conditions(c: Canvas, w: Float, h: Float, top: Float,
                           data: DigitalWeatherRepository.Snapshot, fonts: OriginalFonts, paint: Paint,
                           context: android.content.Context) {
        WeatherSymbols.init(context)
        paint.reset(); paint.isAntiAlias = true
        DigitalFaceWidgets.drawConditionIcon(c, paint, w * 0.38f, top + h * 0.06f, h * 0.16f, data.code ?: 0, Color.WHITE)
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.softCompressedSemibold
        paint.textSize = h * 0.13f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.LEFT
        c.drawText("${data.tempC}°", w * 0.50f, top + h * 0.13f, paint)
        paint.typeface = fonts.ui; paint.textSize = h * 0.035f; paint.textAlign = Paint.Align.CENTER
        c.drawText("Conditions", w / 2f, top + h * 0.20f, paint)
    }

    private fun hours(c: Canvas, w: Float, h: Float, top: Float,
                      data: DigitalWeatherRepository.Snapshot, fonts: OriginalFonts, paint: Paint) {
        val list = data.hours.take(12)
        if (list.isEmpty()) return
        val step = w / (list.size + 1)
        list.forEachIndexed { i, hour ->
            val x = step * (i + 1)
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.ui; paint.textSize = h * 0.030f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            c.drawText("${hour.hour}", x, top + h * 0.03f, paint)
            paint.reset(); paint.isAntiAlias = true
            DigitalFaceWidgets.drawConditionIcon(c, paint, x - h * 0.03f, top + h * 0.09f, h * 0.06f, hour.code, Color.WHITE)
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.softCompressedSemibold; paint.textSize = h * 0.04f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            c.drawText("${hour.tempC}°", x, top + h * 0.16f, paint)
        }
    }

    private fun nextHour(c: Canvas, w: Float, h: Float, top: Float,
                         data: DigitalWeatherRepository.Snapshot, fonts: OriginalFonts, paint: Paint) {
        val values = data.nextHourMm
        if (values.isEmpty()) return
        val total = values.sum()
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.ui; paint.textSize = h * 0.035f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        c.drawText("Next hour", w / 2f, top + h * 0.02f, paint)
        paint.typeface = fonts.softCompressedSemibold; paint.textSize = h * 0.06f
        c.drawText(String.format(java.util.Locale.US, "%.1f mm", total), w / 2f, top + h * 0.10f, paint)
        val max = values.maxOrNull()?.coerceAtLeast(0.1) ?: 0.1
        val barW = w * 0.04f
        val gap = w * 0.015f
        val baseY = top + h * 0.22f
        val maxH = h * 0.10f
        values.forEachIndexed { i, v ->
            val x = w / 2f + (i - (values.size - 1) / 2f) * (barW + gap)
            val barH = maxH * (v / max).toFloat()
            paint.color = Color.WHITE
            c.drawRoundRect(android.graphics.RectF(x - barW / 2, baseY - barH, x + barW / 2, baseY), barW / 2, barW / 2, paint)
        }
    }

    private fun days(c: Canvas, w: Float, h: Float, top: Float,
                     data: DigitalWeatherRepository.Snapshot, fonts: OriginalFonts, paint: Paint) {
        val list = data.days.take(4)
        if (list.isEmpty()) return
        val rowH = h * 0.07f
        list.forEachIndexed { i, day ->
            val y = top + h * 0.03f + i * rowH
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.ui; paint.textSize = h * 0.035f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.LEFT
            c.drawText(day.date.takeLast(5), w * 0.30f, y, paint)
            paint.reset(); paint.isAntiAlias = true
            DigitalFaceWidgets.drawConditionIcon(c, paint, w * 0.46f, y - h * 0.012f, h * 0.05f, day.code, Color.WHITE)
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.softCompressedSemibold; paint.textSize = h * 0.04f; paint.color = Color.WHITE; paint.textAlign = Paint.Align.RIGHT
            c.drawText("${day.maxC}°  ${day.minC}°", w * 0.66f, y, paint)
        }
    }
}
