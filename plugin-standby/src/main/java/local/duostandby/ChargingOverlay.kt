package local.duostandby

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Partial, parameter-driven experiment, NOT a complete Apple charging animation.
 * Source: charging-layout-followup/original-charging-layout.json, 24A94425 AmbientUI.
 * Draw-space maps one UIKit logical point to one Android dp; true Duo host frame/scale unknown.
 * Caller owns timing, invalidation, system animation-scale policy and lifecycle. No receivers.
 *
 * Explicit experiment condition: known DarkerSystemColors charging RGB (0,220,100).
 * This neither changes accessibility settings nor claims the ordinary systemGreenColor value.
 * Label uses Android sans-serif bold metrics, not Apple's unavailable system font renderer.
 */
class ChargingOverlay {
    var cameraAnchor:CutoutAnchor.Anchor?=null
    var labelTypeface:Typeface?=null
    var cameraStrokeWidthPx=4.0
    var powerColor: Int = Color.rgb(0, 220, 100)
    /** Original track derives from _UILegibilitySettings.primaryColor, not powerColor.
     * Null deliberately omits it until the caller supplies a measured/resolved color. */
    var resolvedLegibilityColor: Int? = null

    val unsupportedRendering: List<String> = listOf(
        "bolt.fill system symbol: omitted; no invented lightning path",
        "PLPlatterView recipes 6 and 19: omitted (including grabber)",
        "CAFilter gaussianBlur/brightness/saturation/plusL: not emulated",
        "shadownOpacity: retained as literal track; no invented shadow",
        "unresolved _UILegibilitySettings.primaryColor: track omitted unless explicitly supplied",
        "Apple UILabel/UIFont metrics and contentsMultiplyColor compositing: not reproduced",
        "CoreAnimation runtime spring sampling/composition: analytical experiment, not compared",
        "host handoff/automatic charging presentation/interrupt dismissal: caller-owned"
    )

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val circle = RectF(-31.5f, -31.5f, 31.5f, 31.5f)
    private val formatter = NumberFormat.getIntegerInstance()
    private var lastPercent = -1
    private var percentLabel = ""

    /** width/height in pixels, density in pixels/dp, elapsed in monotonic seconds.
     * Returns whether the ORIGINAL presentation timeline has time remaining (9.8 s),
     * not whether all currently omitted layers are visible. 6.2 s is notification only.
     * Negative/invalid time is inactive. End does not leave a frozen ring on screen.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, density: Float,
             elapsedSeconds: Double, batteryPercent: Int, rtl: Boolean): Boolean {
        if (!width.isFinite() || !height.isFinite() || !density.isFinite() ||
            width <= 0f || height <= 0f || density <= 0f ||
            !OriginalChargingTimeline.isPlaying(elapsedSeconds)) return false

        val percent = batteryPercent.coerceIn(0, 100)
        val w = width / density
        // Original roundForScale applied to the ring origin, not to its center.
        val originX = ((if (rtl) 25f else w - 70f - 25f) * density).roundToInt() / density
        val originY = (15f * density).roundToInt() / density
        val centerX = cameraAnchor?.let{(it.x/density).toFloat()} ?: (originX + 35f)
        val centerY = cameraAnchor?.let{(it.y/density).toFloat()} ?: (originY + 35f)
        val input = OriginalChargingTimeline.Inputs(percent.toDouble(), centerY.toDouble(),
            (centerY + 4f).toDouble())
        fun value(layer: String, key: String, model: Double): Float =
            OriginalChargingTimeline.value(layer, key, elapsedSeconds, input, model).toFloat()

        val magScale = value("magSafeLayer", "transform.scale.xy", 1.0)
        val magAlpha = value("magSafeLayer", "opacity", 0.0).coerceIn(0f, 1f)
        val magY = value("magSafeLayer", "position.y", centerY.toDouble())
        val containerScale = value("ringContainerLayer", "transform.scale.xy", 1.0)
        val ringScale = value("ringLayer", "transform.scale.xy", 1.0)
        val level = value("batteryLevelRing", "strokeEnd", percent / 100.0).coerceIn(0f, 1f)
        val labelAlpha = value("chargePercentLabel.layer", "opacity", 0.0).coerceIn(0f, 1f)
        val labelScale = value("chargePercentLabel.layer", "transform.scale.xy", 1.0)

        cameraAnchor?.let {
            drawCameraRing(canvas,it,level,magAlpha,cameraStrokeWidthPx)
            return true
        }
        val save = canvas.save()
        try {
            canvas.scale(density, density)
            // Equivalent centers for the original nonzero-origin frame AND bounds hierarchy.
            canvas.translate(centerX, if(cameraAnchor!=null)centerY else magY)
            canvas.scale(magScale, magScale)
            val ringSave = canvas.save()
            try {
                // chargeRing -> ringContainer -> ringLayer -> track/level.
                canvas.scale(containerScale, containerScale)
                canvas.scale(ringScale, ringScale)
                stroke.strokeWidth = 7f
                resolvedLegibilityColor?.let { color ->
                    stroke.color = color
                    stroke.alpha = alpha(color, magAlpha * 0.10000000149011612f)
                    canvas.drawOval(circle, stroke)
                }
                stroke.color = powerColor
                stroke.alpha = alpha(powerColor, magAlpha)
                if (level > 0f) {
                    // CGPath arc -pi/2 .. 3pi/2 with positive sweep maps to Canvas +360.
                    canvas.drawArc(circle, -90f, 360f * level, false, stroke)
                }
            } finally {
                canvas.restoreToCount(ringSave)
            }
            // The label is under chargeRing, NOT scaled by ringContainer/ringLayer.
            if (labelAlpha > 0f && labelScale > 0f) {
                val textSave = canvas.save()
                try {
                    canvas.scale(labelScale, labelScale)
                    labelTypeface?.let{text.typeface=it}
                    text.textSize = 26.3333f
                    text.color = powerColor
                    text.alpha = alpha(powerColor, magAlpha * labelAlpha)
                    if (lastPercent != percent) {
                        lastPercent = percent
                        // Evidence is localizedStringFromNumber(numberStyle:0), not an added % glyph.
                        percentLabel = formatter.format(percent)
                    }
                    val metrics = text.fontMetrics
                    canvas.drawText(percentLabel, 0f, -(metrics.ascent + metrics.descent) * 0.5f, text)
                } finally {
                    canvas.restoreToCount(textSave)
                }
            }
        } finally {
            canvas.restoreToCount(save)
        }
        return true
    }

    /** A camera hole is stationary. Only stroke progress/opacity animate;
     * inherited Apple layer scale/translation must NOT change physical fit. */
    fun drawCameraRing(canvas:Canvas,a:CutoutAnchor.Anchor,level:Float=1f,opacity:Float=1f,widthPx:Double=cameraStrokeWidthPx){
        val g=OuterCameraGeometry.outline(a.radius,widthPx)
        stroke.style=Paint.Style.STROKE;stroke.strokeWidth=g.strokeWidth.toFloat()
        stroke.color=powerColor;stroke.alpha=(opacity.coerceIn(0f,1f)*255).roundToInt()
        val rad=g.pathRadius.toFloat()
        val box=RectF(a.x.toFloat()-rad,a.y.toFloat()-rad,a.x.toFloat()+rad,a.y.toFloat()+rad)
        if(level>0)canvas.drawArc(box,-90f,360f*level.coerceIn(0f,1f),false,stroke)
    }

    private fun alpha(color: Int, fraction: Float): Int =
        (Color.alpha(color) * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
}
