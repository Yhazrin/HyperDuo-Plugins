package local.duostandby

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Photo Shuffle sheet. Structure and strings recovered from PhotosPosterUI 24A94425:
 *  - controller: PUWallpaperShuffleConfigurationViewController (configureSheet:
 *    sheetPresentationController + setDetents + setPrefersGrabberVisible)
 *  - title: PHOTOS_WALLPAPER_SHUFFLE_CONFIGURATION_TITLE ("Photo Shuffle" / "照片随机显示")
 *  - sources: _FEATURED_PHOTOS_BUTTON_TITLE, _USE_ALBUM_TITLE + _ALBUM_BUTTON_TITLE,
 *    _SMART_ALBUM_SHUFFLE_CONFIGURATION_TITLE, _SELECT_PHOTOS_BUTTON_TITLE
 *  - frequency: _FREQUENCY_TITLE + HOURLY / DAILY / LOCK / TAP
 * This is the Android rendering of that sheet (a bottom card, not the UIKit detent sheet).
 */
class PhotoShuffleSheet(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val zh = java.util.Locale.getDefault().language == "zh"

    var onFeatured: (() -> Unit)? = null
    var onAlbum: (() -> Unit)? = null
    var onCategories: (() -> Unit)? = null
    var onManual: (() -> Unit)? = null
    var onFrequency: ((Int) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    private var selectedFrequency = 0
    private val frequencyRows = mutableListOf<Pair<Int, TextView>>()

    init {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        setOnClickListener { close() }
    }

    fun show(currentFrequency: Int) {
        selectedFrequency = currentFrequency.coerceIn(0, 3)
        build()
    }

    private fun close() {
        onDismiss?.invoke()
    }

    private fun build() {
        removeAllViews()
        frequencyRows.clear()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(10f).toInt(), dp(20f).toInt(), dp(18f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 30))
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
            }
            setOnClickListener { }
        }
        card.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(90, 255, 255, 255)); cornerRadius = dp(3f)
            }
        }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(5f).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f).toInt()
        })
        val screenH = context.resources.displayMetrics.heightPixels
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(text(if (zh) "照片随机显示" else "Photo Shuffle", 20f, Color.WHITE, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f).toInt() })
        // 原版还有 PHOTOS_WALLPAPER_SHUFFLE_CONFIGURATION_DESCRIPTION_DEVICE
        // （iphone/ipad 两套文案："使用iPhone时，全天随机显示的一组动态照片。"）。
        // 本机不是 iPhone/iPad，不做设备名替换就上屏会误导，故这一行暂不显示，等确定文案策略。
        // 原版 configureSheet 的动作顺序：使用精选照片 → 选取…（人物/宠物）→ 使用相簿 → 手动选择照片。
        // 人物/宠物需要照片分类数据，本机没有，故这一行不出现（不放假按钮）。
        sourceRow(content, if (zh) "使用精选照片" else "Use Featured Photos") { onFeatured?.invoke(); close() }
        sourceRow(content, if (zh) "使用相簿" else "Use Album") { onAlbum?.invoke(); close() }
        sourceRow(content, if (zh) "手动选择照片" else "Select Photos Manually") { onManual?.invoke(); close() }
        content.addView(View(context).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) },
            LinearLayout.LayoutParams(-1, dp(1f).toInt()).apply { topMargin = dp(10f).toInt(); bottomMargin = dp(10f).toInt() })
        content.addView(text(if (zh) "随机显示频率" else "Shuffle Frequency", 15f, Color.argb(160, 255, 255, 255), false))
        val labels = if (zh) arrayOf("每小时", "每天", "锁定时", "轻点时")
        else arrayOf("Hourly", "Daily", "On Lock", "On Tap")
        labels.forEachIndexed { index, label ->
            val row = text(label, 16f, Color.WHITE, false).apply {
                setPadding(0, dp(12f).toInt(), 0, dp(12f).toInt())
                setOnClickListener { selectFrequency(index) }
            }
            frequencyRows.add(index to row)
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        refreshFrequency()
        val scroll = android.widget.ScrollView(context).apply { isFillViewport = true }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // 原版用 UISheetPresentationController 的 detent + grabber；Android 取 medium detent 的近似高度。
        val cardHeight = (screenH * 0.52f).toInt()
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, cardHeight, Gravity.BOTTOM))
        card.setOnClickListener { }
        card.isClickable = true
        card.isFocusable = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = true
        requestFocus()
        selectFrequency(selectedFrequency, notify = false)
    }

    private fun sourceRow(parent: LinearLayout, label: String, action: () -> Unit) {
        val row = text(label, 16f, Color.WHITE, false).apply {
            setPadding(0, dp(14f).toInt(), 0, dp(14f).toInt())
            setOnClickListener { action() }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun selectFrequency(index: Int, notify: Boolean = true) {
        selectedFrequency = index.coerceIn(0, 3)
        refreshFrequency()
        if (notify) onFrequency?.invoke(selectedFrequency)
    }

    private fun refreshFrequency() {
        frequencyRows.forEach { (index, row) ->
            row.setTextColor(if (index == selectedFrequency) Color.WHITE else Color.argb(140, 255, 255, 255))
            row.setTypeface(Typeface.create("sans-serif", if (index == selectedFrequency) Typeface.BOLD else Typeface.NORMAL))
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
