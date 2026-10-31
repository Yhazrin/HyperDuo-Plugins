package local.duostandby

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * StandBy 短横幅（short-look banner）。
 *
 * 几何来自 BannerKit / UserNotificationsUIKit 24A94425 的反汇编，见
 * `docs/research/evidence/banner-port-20261025.md`：
 *  - `_layoutInfoV2`（`-[BNBannerLayoutManager init]` 0x1cd10dff4）：contentInsets 0/8/0/8、
 *    maximumContentSize 556 × ∞、interBannerSpacing 8、alignment 3（水平居中）；
 *  - 短横幅内边距 `-[NCNotificationContentView _contentInsetsForShortLook]` 0x222875608：0/2/5/5；
 *  - 连续圆角 23.5（`_configurePlatterViewIfNeccesary` 0x22288235c → `_setContinuousCornerRadius:`）；
 *  - 背景 `NCPlatterView initWithRecipe:1`（0x222882324）、clipsToBounds YES；
 *  - 过渡时长 0.375s（`-[BNBannerAnimator transitionDuration:]` 0x1cd12a078，
 *    `-[BNBannerMorphTransitionAnimator transitionDuration:]` 0x1cd123aa4）。
 *
 * 仍是 Android 适配（不冒充原版）：MTMaterial recipe 1 的模糊/材质没有公开等价物，
 * 这里用半透明深色填充；短横幅的出现/消失曲线原版由 SpringBoard 的 presentable 动画设置给，
 * 这两个镜像里没有，暂用 0.375s 的位移+淡入；自动消失 5.2s 来自用户对真机的描述。
 */
class NotificationBannerView(context: Context) : View(context) {
    companion object {
        /** BNBannerLayoutInfoV2.maximumContentSize.width */
        const val MAX_WIDTH_PT = 556f
        /** BNBannerLayoutInfoV2.contentInsets.left / right */
        const val EDGE_INSET_PT = 8f
        /** NCNotificationContentView short-look content insets */
        const val CONTENT_TOP_PT = 0f
        const val CONTENT_LEFT_PT = 2f
        const val CONTENT_BOTTOM_PT = 5f
        const val CONTENT_RIGHT_PT = 5f
        /** _setContinuousCornerRadius:23.5 */
        const val CORNER_PT = 23.5f
        /** BNBannerAnimator transitionDuration 回退值 */
        const val TRANSITION_MS = 375L
        /** 用户对真机短横幅自动消失的描述（非原版证据） */
        const val AUTO_DISMISS_MS = 5200L
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 同 build UIKit 实测的 cubic(.42,0,.58,1)（OriginalConfigurationPortalMotion 里的同一个解）。
     *  原版短横幅自己的 presentable 动画设置不在 BannerKit/UNUIKit 里，这里是明确的适配曲线。 */
    private val uiKitEase = object : android.animation.TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            var lo = 0.0; var hi = 1.0
            repeat(20) {
                val u = (lo + hi) * .5; val v = 1.0 - u
                val x = 3.0 * v * v * u * .42 + 3.0 * v * u * u * .58 + u * u * u
                if (x < t) lo = u else hi = u
            }
            val u = (lo + hi) * .5
            return (3.0 * (1.0 - u) * u * u + u * u * u).toFloat()
        }
    }

    private var item: NotificationRelay.Item? = null
    /** 0 = 收起在顶部上方，1 = 完全就位 */
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val card = RectF()
    private var bodyLayout: android.text.StaticLayout? = null
    private val hideRunnable = Runnable { animateOut() }
    private var dragOffset = 0f
    private var dragging = false
    private var downY = 0f

    init {
        visibility = GONE
        bodyPaint.color = Color.WHITE
    }

    /** 顶部可用区内边距：原版用 screen 的 portraitPeripheryInsets + 8，这里用刘海/状态栏高度兜底。 */
    private fun topInsetPx(): Float {
        val cutout = rootWindowInsets?.displayCutout
        val status = rootWindowInsets?.systemWindowInsetTop ?: 0
        val cut = cutout?.safeInsetTop ?: 0
        return maxOf(cut, status).toFloat().coerceAtLeast(0f) + dp(EDGE_INSET_PT)
    }

    fun show(value: NotificationRelay.Item) {
        item = value
        dragOffset = 0f
        dragging = false
        layoutCard()
        visibility = VISIBLE
        removeCallbacks(hideRunnable)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress.coerceAtMost(0.25f), 1f).apply {
            duration = TRANSITION_MS
            interpolator = uiKitEase
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(hideRunnable, AUTO_DISMISS_MS)
    }

    fun dismissImmediately() {
        removeCallbacks(hideRunnable)
        animator?.cancel()
        progress = 0f
        dragOffset = 0f
        visibility = GONE
        invalidate()
    }

    private fun animateOut() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = TRANSITION_MS
            interpolator = uiKitEase
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { visibility = GONE }
            })
            start()
        }
    }

    private fun layoutCard() {
        if (width <= 0 || height <= 0) return
        // 宽度：min(556, 容器宽 − left/right contentInsets)，水平居中（alignment 3）。
        val w = min(dp(MAX_WIDTH_PT), width - dp(EDGE_INSET_PT) * 2f).coerceAtLeast(1f)
        val padL = dp(CONTENT_LEFT_PT) + dp(14f)
        val padR = dp(CONTENT_RIGHT_PT) + dp(14f)
        val bodyW = (w - padL - padR).toInt().coerceAtLeast(1)
        bodyPaint.textSize = dp(14f)
        bodyPaint.color = Color.WHITE
        val text = item?.text.orEmpty()
        bodyLayout = if (text.isEmpty()) null else android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, bodyW)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val titleH = if (item?.title.isNullOrEmpty()) 0f else dp(22f)
        val bodyH = bodyLayout?.height?.toFloat() ?: 0f
        val h = dp(CONTENT_TOP_PT) + dp(CONTENT_BOTTOM_PT) + dp(20f) * 2f + dp(44f) + dp(12f) +
            titleH + (if (bodyH > 0f) dp(6f) + bodyH else 0f)
        val left = (width - w) / 2f
        // presentationEdge 默认 1：沿 useable container 的 maxY 方向堆叠；单条横幅即贴顶部可用区。
        val top = topInsetPx()
        card.set(left, top, left + w, top + h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (card.contains(event.x, event.y)) {
                    dragging = true
                    downY = event.y
                    removeCallbacks(hideRunnable)
                    animator?.cancel()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                dragOffset = (event.y - downY).coerceAtMost(0f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (dragOffset < -dp(50f)) swipeOut() else springBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun swipeOut() {
        ValueAnimator.ofFloat(dragOffset, -(card.bottom + dp(24f))).apply {
            duration = TRANSITION_MS
            addUpdateListener {
                dragOffset = it.animatedValue as Float
                progress = (1f + dragOffset / (card.bottom + dp(24f))).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    progress = 0f; dragOffset = 0f; visibility = GONE
                }
            })
            start()
        }
    }

    private fun springBack() {
        ValueAnimator.ofFloat(dragOffset, 0f).apply {
            duration = TRANSITION_MS
            addUpdateListener { dragOffset = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(hideRunnable, AUTO_DISMISS_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (item != null) layoutCard()
    }

    override fun onDraw(c: Canvas) {
        val value = item ?: return
        val a = progress.coerceIn(0f, 1f)
        if (a <= 0.001f) return
        c.save()
        // 从顶部上方滑入：位移量 = 卡片底部 + 顶部内边距，progress 1 时归零。
        val slide = (1f - a) * -(card.bottom + dp(EDGE_INSET_PT))
        c.translate(0f, slide + dragOffset)
        cardPaint.style = Paint.Style.FILL
        // NCPlatterView recipe 1 的 Android 适配：没有公开模糊材质，用深色半透明 + 轻描边。
        cardPaint.color = Color.argb((236f * a).roundToInt(), 24, 24, 26)
        c.drawRoundRect(card, dp(CORNER_PT), dp(CORNER_PT), cardPaint)
        cardPaint.style = Paint.Style.STROKE
        cardPaint.strokeWidth = dp(1f)
        cardPaint.color = Color.argb((26f * a).roundToInt(), 255, 255, 255)
        c.drawRoundRect(card, dp(CORNER_PT), dp(CORNER_PT), cardPaint)
        cardPaint.style = Paint.Style.FILL
        // 短横幅内容内边距：top 0 / left 2 / bottom 5 / right 5（+ 内部留白 14/20）。
        val contentLeft = card.left + dp(CONTENT_LEFT_PT) + dp(14f)
        val contentRight = card.right - dp(CONTENT_RIGHT_PT) - dp(14f)
        val contentTop = card.top + dp(CONTENT_TOP_PT) + dp(20f)
        value.icon?.let { bmp ->
            iconPaint.alpha = (255f * a).roundToInt()
            c.drawBitmap(bmp, null, RectF(contentLeft, contentTop, contentLeft + dp(44f), contentTop + dp(44f)), iconPaint)
        }
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = dp(13f)
        textPaint.color = Color.argb((190f * a).roundToInt(), 255, 255, 255)
        val headerY = contentTop + dp(44f) * 0.62f
        textPaint.textAlign = Paint.Align.LEFT
        c.drawText(value.appName, contentLeft + dp(56f), headerY, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val whenTime = if (value.time > 0L) value.time else System.currentTimeMillis()
        c.drawText(timeFormat.format(Date(whenTime)), contentRight, headerY, textPaint)
        var y = contentTop + dp(44f) + dp(12f)
        if (value.title.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = dp(16f)
            textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textPaint.color = Color.argb((245f * a).roundToInt(), 255, 255, 255)
            val title = TextUtils.ellipsize(value.title, textPaint, contentRight - contentLeft, TextUtils.TruncateAt.END)
            c.drawText(title, 0, title.length, contentLeft, y + dp(16f), textPaint)
            y += dp(22f)
        }
        bodyLayout?.let { layout ->
            c.save()
            c.translate(contentLeft, y + dp(6f))
            layout.paint.alpha = (210f * a).roundToInt()
            layout.draw(c)
            c.restore()
        }
        c.restore()
    }
}
