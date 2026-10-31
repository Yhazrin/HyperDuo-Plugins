package local.duostandby

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** Up Next events widget (Calendar Up Next).
 *  文案来自原版 CalendarWidget.framework `Localizable.loctable`（zh_CN）：
 *  `Up Next`=临近日程、`No Upcoming Events`=无临近日程、`x_more_events`="其他%d个日程"。
 *  数据仍是本机 CalendarProvider（root 只读）；行布局是 Android 适配（原版 widget body 未解出）。
 */
class UpNextWidgetContent(private val context: android.content.Context) {
    private val zh = java.util.Locale.getDefault().language == "zh"
    private val fonts = OriginalFonts(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun draw(c: Canvas, bounds: RectF, night: Boolean) {
        val events = UpNextProvider.events
        val size = min(bounds.width(), bounds.height())
        val color = if (night) VisualTokens.NIGHT_RED else Color.WHITE
        if (events.isEmpty()) {
            // 原版空态文案（CalendarWidget loctable）：无临近日程 / No Upcoming Events
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.ui
            paint.textSize = size * 0.12f
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            c.drawText(if (zh) "无临近日程" else "No Upcoming Events", bounds.centerX(), bounds.centerY(), paint)
            return
        }
        var y = bounds.top + size * 0.24f
        // 超出可见行数时用原版 "其他%d个日程" 收尾，而不是无声截断。
        val maxRows = 3
        val shown = events.take(maxRows)
        shown.forEachIndexed { index, event ->
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.semibold
            paint.textSize = size * (if (index == 0) 0.14f else 0.11f)
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            val time = Instant.ofEpochMilli(event.startMs).atZone(ZoneId.systemDefault()).format(timeFormat)
            c.drawText(time, bounds.centerX(), y, paint)
            paint.typeface = fonts.ui
            paint.textSize = size * (if (index == 0) 0.11f else 0.09f)
            c.drawText(event.title.take(18), bounds.centerX(), y + size * 0.12f, paint)
            y += size * 0.26f
        }
        val more = events.size - shown.size
        if (more > 0) {
            paint.reset(); paint.isAntiAlias = true
            paint.typeface = fonts.ui
            paint.textSize = size * 0.09f
            paint.color = color
            paint.textAlign = Paint.Align.CENTER
            c.drawText(if (zh) "其他${more}个日程" else "${more} more events", bounds.centerX(), y, paint)
        }
    }
}
