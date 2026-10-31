package local.duostandby

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** State 6 闹钟宿主。
 *
 * 外屏是 ClockAngel.AlarmAlertFullScreen：左标题（localizedUppercase）、右 190pt 时间、
 * 底部 LargeActionButton 胶囊。内屏是整面 #FF9500，帐篷姿态里朝桌面的那一面。
 * 内屏窗口亮度由 Presentation 设为 1.0，这里不再乘任何低于 1 的 screenBrightness。
 */
class GlowView(
    context: Context,
    innerHint: Boolean,
    private val alarmLabel: String,
    private val listener: Listener,
    private val forceOuter: Boolean = false,
) : View(context) {
    interface Listener { fun onAlarmStop(); fun onSnooze() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.elapsedRealtime()
    /** 柔光实现：MATERIAL = 原版 timerYellow 材质（缩放+模糊+颜色矩阵）；EMISSION = 证据色线性发光。 */
    enum class GlowMode { MATERIAL, EMISSION }
    var glowMode: GlowMode = GlowMode.MATERIAL
    private var materialOrNull: GlowBackdropMaterial? = null
    private val material: GlowBackdropMaterial
        get() = materialOrNull ?: GlowBackdropMaterial(context).also { materialOrNull = it }
    private var loggedBackdrop = ""
    private var innerDisplay = !forceOuter && innerHint
    private var glowOnly = false
    /** setDualInnerMode / setDualOuterMode 之后不再让 onSizeChanged 改角色。 */
    private var roleLocked = false
    private var shader: RadialGradient? = null
    private val stopRect = RectF()
    private val snoozeRect = RectF()
    private var pressed: Int = 0
    private val density = resources.displayMetrics.density
    private fun pt(n: Float) = n * density

    init {
        text.textAlign = Paint.Align.CENTER
        text.typeface = OriginalFonts(context).ui
    }

    /** State 6：display 0 是外屏闹钟，display 1 是内屏柔光。 */
    fun setDualInnerMode(enabled: Boolean) {
        innerDisplay = enabled
        glowOnly = enabled
        roleLocked = true
        invalidate()
    }

    fun setDualOuterMode() {
        innerDisplay = false
        glowOnly = false
        roleLocked = true
        invalidate()
    }

    fun setSingleMode(innerHint: Boolean) {
        innerDisplay = innerHint
        glowOnly = false
        roleLocked = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        materialOrNull?.release()
        materialOrNull = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 已确认：外屏约 1712×1168，内屏约 1672×2364。旧判断把外屏尺寸当成内屏，
        // 会在 setDualOuterMode 之后又把闹钟宿主画成整面材质。双屏角色一旦指定就锁住。
        if (forceOuter || roleLocked) return
        val innerBySize = (w == 1672 && h == 2364) || (w == 2364 && h == 1672)
        innerDisplay = (display?.displayId == 1) || innerBySize
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = ((SystemClock.elapsedRealtime() - start) / 1000.0).coerceAtLeast(0.0)
        val ramp = (elapsed / 2.2).coerceIn(0.0, 1.0).toFloat()
        if (innerDisplay) {
            // 帐篷姿态里朝向桌面的那一面是整面闹钟暖光，不是壁纸材质。
            // 颜色是 ClockAngel 的 #FF9500（_colorLiteral @ 0x1000096ac）。
            canvas.drawColor(Color.rgb(255, 149, 0))
        } else {
            drawAlarmAlertFullScreen(canvas)
        }
        if (!glowOnly) drawButtons(canvas, ramp)
        invalidate()
    }

    /**
     * AlarmAlertFullScreen 的布局调用在 0x10001a510（它取了该类型的 accessor 0x100024ae4）。
     *
     * 标题：frame(width: nil, height: 96, alignment: .top)，水平 leading。0x10001abf4 的高度立即数是 96。
     * 时间：flexFrame(maxWidth: .infinity, alignment: .bottomTrailing)。0x10001b068，maxWidth 是 +inf。
     * 纵向偏移：x = 0，y = 50（0x10001b220，标志为 0 时用 50，否则用 0）。
     * 时间格式是 "h:mm"（0x10001aee0）。颜色是 Color.white。
     * VStack 把这一行放在上面，Spacer 把按钮推到底，底边距 30（0x10001d6f4）。
     * 190 只是 CTFontGetDescentForSystemFontOfSize 的入参，不是这一行的 frame。
     */
    private fun drawAlarmAlertFullScreen(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val fonts = OriginalFonts(context)
        val rowH = pt(96f)
        val rowTop = pt(50f)
        text.typeface = fonts.semibold
        text.color = Color.WHITE
        text.textSize = rowH
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm"))
        val timeBox = (-text.ascent() + text.descent()).coerceAtLeast(1f)
        text.textSize = text.textSize * (rowH / timeBox)
        val timeBaseline = rowTop + rowH - text.descent()
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(time, width.toFloat(), timeBaseline, text)

        text.textAlign = Paint.Align.LEFT
        text.color = Color.rgb(255, 149, 0)
        val title = alarmLabel.uppercase()
        val lines = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).ifEmpty { listOf(title) }
        text.textSize = rowH / (lines.size * 1.4f)
        var y = rowTop - text.ascent()
        val step = text.textSize * 1.15f
        for (line in lines) {
            canvas.drawText(line, 0f, y, text)
            y += step
        }
        text.textAlign = Paint.Align.CENTER
    }

    /**
     * LargeActionButton.body（0x10007df10）：Capsule 填色，上面盖 Text，再套 Glass 和 BorderedProminentButtonStyle。
     * 左 stop、右 snooze。stop 这几个字母在字符串表里。snooze 的底色用 #FF9500。
     * 胶囊高度不是这段里的立即数。
     */
    private fun drawButtons(canvas: Canvas, ramp: Float) {
        val h = pt(64f)
        val gap = pt(8f)
        val bottom = pt(30f)
        val y = height - bottom - h / 2f
        text.textAlign = Paint.Align.CENTER
        text.typeface = OriginalFonts(context).semibold
        text.textSize = pt(22f)
        text.color = Color.WHITE
        val labelPad = pt(36f)
        val w = maxOf(text.measureText("stop"), text.measureText("snooze")) + labelPad * 2f
        val pair = w * 2f + gap
        val left = (width - pair) / 2f
        val right = left + w + gap
        stopRect.set(left, y - h / 2f, left + w, y + h / 2f)
        snoozeRect.set(right, y - h / 2f, right + w, y + h / 2f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((210 * ramp).toInt(), 58, 58, 62)
        canvas.drawRoundRect(stopRect, h / 2f, h / 2f, paint)
        paint.color = Color.argb((255 * ramp).toInt(), 255, 149, 0)
        canvas.drawRoundRect(snoozeRect, h / 2f, h / 2f, paint)
        val textY = stopRect.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.drawText("stop", stopRect.centerX(), textY, text)
        canvas.drawText("snooze", snoozeRect.centerX(), textY, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = 0; return true }
            MotionEvent.ACTION_UP -> {
                if (glowOnly) {
                    // 内屏只有柔光、没有按钮：点一下在两种实现间切换，便于实机 A/B。
                    glowMode = if (glowMode == GlowMode.MATERIAL) GlowMode.EMISSION else GlowMode.MATERIAL
                    loggedBackdrop = ""
                    android.util.Log.i("DuoGlowMaterial", "mode=$glowMode")
                    invalidate()
                } else if (snoozeRect.contains(event.x, event.y)) listener.onSnooze()
                else if (stopRect.contains(event.x, event.y)) listener.onAlarmStop()
                return true
            }
        }
        return true
    }
}
