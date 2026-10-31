package local.duostandby

import android.content.Context
import android.graphics.*
import android.os.Build
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Source-derived Solar SKY background only. RollingTime / location phase / Spectrum / Custom
 * are intentionally not replaced with invented equivalents. Android blur is a backend adapter,
 * not a claim of pixel equivalence with SwiftUI's compositor.
 */
class OriginalSolarRenderer(context: Context) {
    private val interpolation = OriginalSolarColorInterpolation.load(
        context.assets.open(OriginalSolarColorInterpolation.SRGB_TABLE_ASSET),
        context.assets.open(OriginalSolarColorInterpolation.LINEAR_TABLE_ASSET))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var node: RenderNode? = null
    enum class Backend { ANDROID_RENDER_EFFECT, UNSUPPORTED }
    data class Result(val backend: Backend, val sourceFrame: OriginalSolarParameters.Frame)

    /** pixelsPerPoint is an explicit host adapter, not the iPhone's discovered screen scale.
     * Phase MUST be measured/recovered SolarPosition.percentComplete, not local hour / 24.
     * null is the original model's pre-location state. Caller schedules at source minimum 1/30s.
     * No device settings, continuous ticker, clock text, or app state are changed here.
     */
    fun drawBackground(canvas: Canvas, widthPx: Int, heightPx: Int, pixelsPerPoint: Float,
                       solarPositionPhase: Float?, redProgress: Float = 0f): Result {
        val frame = OriginalSolarParameters.frame(widthPx / pixelsPerPoint, heightPx / pixelsPerPoint,
            pixelsPerPoint, solarPositionPhase, redProgress, interpolation)
        // Source .background(Color) surrounds the blurred Circle, not a radial gradient.
        canvas.drawColor(argb(frame.background))
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated)
            return Result(Backend.UNSUPPORTED, frame) // Never silently substitute a fake blur.
        val circle = node ?: RenderNode("OriginalSolarSkyCircle").also { node = it }
        val diameter = OriginalSolarParameters.CIRCLE_DIAMETER_POINTS * pixelsPerPoint
        val radius = OriginalSolarParameters.BLUR_RADIUS_POINTS * pixelsPerPoint
        val extent = ceil(diameter).toInt()
        circle.setPosition(0, 0, extent, extent)
        circle.setClipToBounds(false)
        circle.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
        val recording = circle.beginRecording(extent, extent)
        paint.color = argb(frame.circle)
        recording.drawCircle(diameter * .5f, diameter * .5f, diameter * .5f, paint)
        circle.endRecording()
        val checkpoint = canvas.save()
        canvas.translate(frame.center.x * pixelsPerPoint - diameter * .5f,
                         frame.center.y * pixelsPerPoint - diameter * .5f)
        canvas.drawRenderNode(circle)
        canvas.restoreToCount(checkpoint)
        return Result(Backend.ANDROID_RENDER_EFFECT, frame)
    }
    fun release() { if (Build.VERSION.SDK_INT >= 29) node?.discardDisplayList(); node = null }
    private fun argb(c: OriginalSolarParameters.Rgba): Int = Color.argb(
        (c.a * 255f).roundToInt().coerceIn(0,255), (c.r * 255f).roundToInt().coerceIn(0,255),
        (c.g * 255f).roundToInt().coerceIn(0,255), (c.b * 255f).roundToInt().coerceIn(0,255))
}
