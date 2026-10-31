package local.duostandby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max

/**
 * Original WeatherFace.appex background assets (StandBy-Clear-iOS /
 * StandBy-MostlyClear-iOS), copied byte-for-byte from the 24A94425 IPSW.
 * Other conditions keep the gradient adapter until their WeatherKit/Weather.app
 * assets are recovered.
 */
object WeatherFaceAssets {
    private var clear: Bitmap? = null
    private var mostly: Bitmap? = null
    private var partlyCloudy: Bitmap? = null
    private var loaded = false

    fun partlyCloudy(context: Context): Bitmap? {
        load(context)
        return partlyCloudy
    }

    fun background(context: Context, code: Int?): Bitmap? {
        load(context)
        return when (code) {
            0 -> clear
            1, 2 -> mostly
            else -> null
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        clear = decode(context, "weather-face/StandBy-Clear-iOS.png")
        mostly = decode(context, "weather-face/StandBy-MostlyClear-iOS.png")
        partlyCloudy = decode(context, "weather-face/cloud.sun.moon.fill@3x.png")
    }

    private fun decode(context: Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    fun drawCover(c: Canvas, bitmap: Bitmap, w: Float, h: Float, paint: Paint) {
        paint.reset(); paint.isAntiAlias = true
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(w / bitmap.width, h / bitmap.height)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - bitmap.width * scale) / 2f, (h - bitmap.height * scale) / 2f)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = Color.TRANSPARENT
    }
}
