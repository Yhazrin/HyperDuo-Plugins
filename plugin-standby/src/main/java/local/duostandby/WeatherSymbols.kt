package local.duostandby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Original SF Symbols for the weather conditions and sun events, extracted from
 * CoreGlyphs.bundle/Assets.car (24A94425) at the largest raster variant and
 * tinted at draw time. The vector BVG source is not parsed yet, so icons at very
 * large sizes can be slightly soft; the canvas icons remain the fallback only if
 * an asset is missing.
 */
object WeatherSymbols {
    private val names = listOf("sun.max.fill","cloud.sun.fill","cloud.fill","cloud.fog.fill",
        "cloud.drizzle.fill","cloud.rain.fill","cloud.snow.fill","cloud.heavyrain.fill",
        "cloud.bolt.rain.fill","sunrise.fill","sunset.fill")
    private val cache = mutableMapOf<String, Bitmap?>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        names.forEach { name ->
            cache[name] = runCatching { app.assets.open("weather-symbols/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }

    fun conditionName(code: Int): String = when (code) {
        0 -> "sun.max.fill"
        1, 2 -> "cloud.sun.fill"
        3 -> "cloud.fill"
        45, 48 -> "cloud.fog.fill"
        in 51..57 -> "cloud.drizzle.fill"
        in 61..67 -> "cloud.rain.fill"
        in 71..77 -> "cloud.snow.fill"
        in 80..82 -> "cloud.heavyrain.fill"
        in 85..86 -> "cloud.snow.fill"
        in 95..99 -> "cloud.bolt.rain.fill"
        else -> "cloud.fill"
    }

    fun drawCondition(c: Canvas, paint: Paint, code: Int, left: Float, centerY: Float, size: Float, color: Int): Boolean {
        val bitmap = cache[conditionName(code)] ?: return false
        return draw(c, paint, bitmap, left, centerY, size, color)
    }

    fun drawSunEvent(c: Canvas, paint: Paint, sunrise: Boolean, left: Float, centerY: Float, size: Float, color: Int): Boolean {
        val bitmap = cache[if (sunrise) "sunrise.fill" else "sunset.fill"] ?: return false
        return draw(c, paint, bitmap, left, centerY, size, color)
    }

    private fun draw(c: Canvas, paint: Paint, bitmap: Bitmap, left: Float, centerY: Float, size: Float, color: Int): Boolean {
        paint.reset(); paint.isAntiAlias = true
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val h = size
        val w = h * bitmap.width / bitmap.height
        c.drawBitmap(bitmap, null, RectF(left, centerY - h / 2f, left + w, centerY + h / 2f), paint)
        paint.colorFilter = null
        return true
    }
}
