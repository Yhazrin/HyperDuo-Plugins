package local.duostandby

import android.content.Context
import android.graphics.*
import android.os.Build
import org.json.JSONObject
import java.util.Locale

/** Source-backed SKY drawing: source background + CoreText-shaped live time outlines
 * + normal vibrantMatrix backdrop. Location phase and original glyph transitions remain external.
 * Font: ClockPoster 0x6a688..0x6a6bc, 210pt .SFSoftNumeric-SemiExpandedMedium.
 */
class OriginalSolarClockRenderer(context: Context) {
    private val background = OriginalSolarRenderer(context)
    private val font = JSONObject(context.assets.open("solar/solar-numerals.json").bufferedReader().use { it.readText() })
    private val glyphs = HashMap<Int, Path>()
    private val shapedLines = HashMap<String, JSONObject>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backing: RenderNode? = null
    private var vibrant: RenderNode? = null
    init {
        require(font.getString("postscript_name") == ".SFSoftNumeric-SemiExpandedMedium")
        val lines = font.getJSONArray("reference_lines")
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i); shapedLines[line.getString("text")] = line
        }
        val rows = font.getJSONArray("glyphs")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val path = Path(); val elements = row.getJSONArray("elements")
            for (j in 0 until elements.length()) {
                val el = elements.getJSONObject(j); val points = el.getJSONArray("points")
                fun x(n: Int) = points.getJSONArray(n).getDouble(0).toFloat()
                fun y(n: Int) = points.getJSONArray(n).getDouble(1).toFloat()
                when (el.getInt("type")) {
                    0 -> path.moveTo(x(0),y(0)); 1 -> path.lineTo(x(0),y(0))
                    2 -> path.quadTo(x(0),y(0),x(1),y(1))
                    3 -> path.cubicTo(x(0),y(0),x(1),y(1),x(2),y(2)); 4 -> path.close()
                }
            }
            glyphs[row.getInt("glyph_id")] = path
        }
    }
    /** Explicit live clock API. hour24/minute come from host time, not a captured fixture.
     * null solarPositionPhase preserves original pre-location state; never derives phase from clock time.
     * 12-hour formatting changes hour numerals only; original AM/PM designator is not yet ported.
     * Source font shaping is exact for H:mm/HH:mm; host baseline remains an explicit Android adapter.
     */
    fun drawLiveFace(canvas: Canvas, widthPx: Int, heightPx: Int, pixelsPerPoint: Float,
                     hour24: Int, minute: Int, use24Hour: Boolean,
                     solarPositionPhase: Float?, redMode: Boolean = false,
                     measuredBaselineYPoints: Float? = null): Boolean {
        require(hour24 in 0..23 && minute in 0..59)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val text = String.format(Locale.ROOT, if (use24Hour) "%02d:%02d" else "%d:%02d", hour, minute)
        return drawShapedFace(canvas,widthPx,heightPx,pixelsPerPoint,solarPositionPhase,
            text,redMode,measuredBaselineYPoints)
    }

    /** Reference input remains explicit and separate from the live-time entry point. */
    fun drawReferenceFace(canvas: Canvas, widthPx: Int, heightPx: Int, pixelsPerPoint: Float,
                          referenceSolarPhase: Float, fixtureText: String = "9:41",
                          redMode: Boolean = false, measuredBaselineYPoints: Float? = null): Boolean =
        drawShapedFace(canvas,widthPx,heightPx,pixelsPerPoint,referenceSolarPhase,
            fixtureText,redMode,measuredBaselineYPoints)

    private fun drawShapedFace(canvas: Canvas, widthPx: Int, heightPx: Int, pixelsPerPoint: Float,
                              solarPositionPhase: Float?, text: String, redMode: Boolean,
                              measuredBaselineYPoints: Float?): Boolean {
        require(widthPx > 0 && heightPx > 0 && pixelsPerPoint > 0f && pixelsPerPoint.isFinite())
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return false
        val line = shapedLines[text] ?: error("No source-font shaped clock line: $text")
        val bg = backing ?: RenderNode("OriginalSolarSkyReference").also { backing = it }
        bg.setPosition(0,0,widthPx,heightPx)
        val recording = bg.beginRecording(widthPx,heightPx)
        val result = background.drawBackground(recording,widthPx,heightPx,pixelsPerPoint,
            solarPositionPhase,if (redMode) 1f else 0f)
        bg.endRecording()
        if (result.backend != OriginalSolarRenderer.Backend.ANDROID_RENDER_EFFECT) return false
        canvas.drawRenderNode(bg)
        val factor = 210f / font.getDouble("font_point_size").toFloat() * pixelsPerPoint
        val originX = (widthPx - line.getDouble("width").toFloat() * factor) * .5f
        val baseline = measuredBaselineYPoints?.times(pixelsPerPoint) ?: (heightPx * .5f +
            (line.getDouble("ascent") - line.getDouble("descent")).toFloat() * factor * .5f)
        val mask = Path(); val positions = line.getJSONArray("positions")
        for (i in 0 until positions.length()) {
            val item = positions.getJSONObject(i); val transform = Matrix()
            transform.setValues(floatArrayOf(factor,0f,originX + item.getDouble("x").toFloat() * factor,
                0f,-factor,baseline - item.getDouble("y").toFloat() * factor,0f,0f,1f))
            mask.addPath(glyphs.getValue(item.getInt("glyph_id")),transform)
        }
        if (redMode) {
            paint.color = Color.rgb(.8f,0f,0f) // 0x6a544..0x6a568; normal is NOT white text.
            canvas.drawPath(mask,paint)
            return true
        }
        val layer = vibrant ?: RenderNode("OriginalSolarVibrantMatrixText").also { vibrant = it }
        layer.setPosition(0,0,widthPx,heightPx)
        val contents = layer.beginRecording(widthPx,heightPx); contents.drawRenderNode(bg); layer.endRecording()
        // Source backdrop saturation 1.2, blur10 at 0xba190; matrix bytes0xba1a0.
        // Android saturation coefficients / blur kernel / clipping are explicit backend adapters.
        val saturation = ColorMatrix().apply { setSaturation(1.2f) }
        val saturated = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturation))
        val blurred = RenderEffect.createBlurEffect(10f*pixelsPerPoint,10f*pixelsPerPoint,saturated,Shader.TileMode.CLAMP)
        val matrix = floatArrayOf(
            1.718f,-1.107f,-.112f,0f,.5f*255,
            -.329f,.941f,-.112f,0f,.5f*255,
            -.329f,-1.107f,1.935f,0f,.5f*255,
            0f,0f,0f,1f,0f)
        layer.setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix),blurred))
        val save = canvas.save(); canvas.clipPath(mask); canvas.drawRenderNode(layer); canvas.restoreToCount(save)
        return true
    }
    fun release() {
        background.release()
        if (Build.VERSION.SDK_INT >= 29) { backing?.discardDisplayList(); vibrant?.discardDisplayList() }
        backing = null; vibrant = null
    }
}
