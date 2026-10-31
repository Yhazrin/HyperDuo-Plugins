package local.duostandby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** CalendarWidget body adapter. Never invokes CalendarPoster/StandByRenderer.
 * Recovered CUIK styling values are kept separate from the explicitly provisional
 * Android envelope fit and font rasterization. No event/calendar permission and
 * no fabricated event, alternate-calendar text, or out-of-month date is added.
 * Date arithmetic is ISO/Gregorian LocalDate, with ICU locale/week-start labels;
 * it does not claim support for Apple's non-Gregorian calendar arithmetic.
 */
class CalendarWidgetContent(private val context: Context) {
    companion object {
        const val SOURCE_DATE_BODY = "CalendarWidget.CalendarDateWidget -> CalendarUIKit.CUIKDateView"
        const val SOURCE_MONTH_BODY = "CalendarWidget.CalendarMonthWidget -> CalendarUIKit.CUIKMonthView"
        const val PORT_STATUS = "INDEPENDENT_BODY_ANDROID_PREVIEW"
        // Month widget function's height/136 branch exists; its StandBy host is
        // not closed. This is an Android normalization, not a measured slot size.
        const val ANDROID_PREVIEW_EXTENT = 136f
        // CalendarWidget 0x216356da4..df8: positive-height month profile
        // uses height/136 and an independent 11pt bold dateLabelFont.
        // This closes the header style, not StandBy family/host selection.
        const val MONTH_HEADER_FONT_SIZE = 11f
        // 0x216356f5c..84 selects MMMM when overlayCalendarID is nil.
        // This adapter has no overlay calendar; do not silently use LLLL.
        const val MONTH_NAME_FORMAT = "MMMM"
        const val GRID_FONT_SIZE = 10f
        const val ROW_HEIGHT = 14f
        const val TODAY_CIRCLE_SIZE = 18f
        const val DATE_LABEL_PADDING = 5f
        const val DIVIDER_PADDING = 4f
        const val DIVIDER_HEIGHT = 10f
        const val ALT_CAL_UNDERLINE_WIDTH = 14f
    }
    private val fonts = OriginalFonts(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normal = fonts.ui
    // CUIKWeekLabelRow 0x204973730 and ordinary CUIKWeekRow
    // 0x204974404 use semibold, not medium/regular (24A94425).
    private val monthGridFont = fonts.semibold
    // SwiftUI .bold mapped to Android weight 700, not a shaping parity claim.
    private val monthHeaderFont = Typeface.create(fonts.ui,700,false)
    // Day tuple +9 is isToday; 0x2049743e0..3f8 selects bold only then.
    // Android 600/700 maps the original weight choice, not rasterization parity.
    private val monthTodayFont = Typeface.create(fonts.ui,700,false)
    private val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun draw(canvas: Canvas, bounds: RectF, date: LocalDate, style: OriginalWidgetStackHost.CalendarStyle, night: Boolean) {
        if (bounds.isEmpty) return
        val scale=min(bounds.width(),bounds.height())/ANDROID_PREVIEW_EXTENT
        val save=canvas.save()
        canvas.translate(bounds.centerX()-ANDROID_PREVIEW_EXTENT*scale/2f,bounds.centerY()-ANDROID_PREVIEW_EXTENT*scale/2f)
        canvas.scale(scale,scale)
        // Ambient host owns background removal. The body is transparent.
        when(style){
            OriginalWidgetStackHost.CalendarStyle.DATE -> drawDate(canvas,date,night)
            OriginalWidgetStackHost.CalendarStyle.MONTH -> drawMonth(canvas,date,night)
        }
        canvas.restoreToCount(save)
    }

    private fun drawDate(canvas: Canvas, date: LocalDate, night: Boolean) {
        // Date styling supplied by the source-bound parameter adapter below.
        val p=CalendarDateWidgetParameters
        val color=if(night)VisualTokens.NIGHT_RED else Color.WHITE
        val accent=if(night)VisualTokens.NIGHT_RED else Color.rgb(255,59,48)
        val weekday=date.format(DateTimeFormatter.ofPattern(p.weekdayFormat,locale)).uppercase(locale)
        // Full weekday length varies. Android preview fit avoids clipping;
        // this is not CUIK's recovered line/fit policy.
        val labelSave=canvas.save()
        paint.reset();paint.typeface=fonts.semibold;paint.textSize=p.weekdayFontSize
        val labelWidth=paint.measureText(weekday)+(weekday.codePointCount(0,weekday.length)-1).coerceAtLeast(0)*p.weekdayTracking
        val fit=min(1f,(ANDROID_PREVIEW_EXTENT-2f*p.firstLineX)/labelWidth.coerceAtLeast(1f))
        canvas.scale(fit,1f,p.firstLineX,p.firstLineY)
        text(canvas,weekday,p.firstLineX,p.firstLineY,p.weekdayFontSize,accent,fonts.semibold,Paint.Align.LEFT,p.weekdayTracking)
        canvas.restoreToCount(labelSave)
        text(canvas,number(date.dayOfMonth),p.dayNumberX,p.dayNumberY,p.dayNumberFontSize,color,normal,Paint.Align.LEFT)
    }

    private fun drawMonth(canvas: Canvas, date: LocalDate, night: Boolean) {
        val l=locale
        val calendar=Calendar.getInstance(l)
        val firstDay=calendar.firstDayOfWeek
        val firstIso=if(firstDay==Calendar.SUNDAY)7 else firstDay-1
        val first=date.withDayOfMonth(1)
        val before=Math.floorMod(first.dayOfWeek.value-firstIso,7)
        val rowCount=(before+date.lengthOfMonth()+6)/7
        val start=first.minusDays(before.toLong())
        val white=if(night)VisualTokens.NIGHT_RED else Color.WHITE
        val accent=if(night)VisualTokens.NIGHT_RED else Color.rgb(255,59,48)
        // Header size/weight/format are source-bound; positions and the seven-
        // column envelope remain Android preview placement. Native baselines,
        // font leading and the complete layout proposal are still pending.
        val inset=DATE_LABEL_PADDING
        val col=(ANDROID_PREVIEW_EXTENT-2f*inset)/7f
        val gridHeight=(rowCount+1)*ROW_HEIGHT
        val top=(ANDROID_PREVIEW_EXTENT-gridHeight)/2f
        // DateFormatter "MMMM" keeps the locale month name as-is; uppercase was an adapter-only change.
        text(canvas,date.format(DateTimeFormatter.ofPattern(MONTH_NAME_FORMAT,l)),inset,top-DATE_LABEL_PADDING,MONTH_HEADER_FONT_SIZE,accent,monthHeaderFont,Paint.Align.LEFT)
        val symbols=DateFormatSymbols(l).getWeekdays(DateFormatSymbols.STANDALONE,DateFormatSymbols.NARROW)
        for(i in 0..6){
            val weekday=((firstDay-1+i)%7)+1
            text(canvas,symbols[weekday],inset+(i+.5f)*col,top+ROW_HEIGHT*.5f,GRID_FONT_SIZE,white,monthGridFont,Paint.Align.CENTER)
        }
        for(i in 0 until rowCount*7){
            val cell=start.plusDays(i.toLong())
            // Source showOutOfMonthDays=false / NoDimming: omit, don't fade.
            if(cell.month!=date.month || cell.year!=date.year)continue
            val x=inset+(i%7+.5f)*col
            val y=top+(i/7+1.5f)*ROW_HEIGHT
            val today=cell==date
            if(today){
                paint.reset();paint.isAntiAlias=true;paint.color=accent
                canvas.drawCircle(x,y,TODAY_CIRCLE_SIZE/2f,paint)
            }
            text(canvas,number(cell.dayOfMonth),x,y,GRID_FONT_SIZE,if(today)Color.BLACK else white,if(today)monthTodayFont else monthGridFont,Paint.Align.CENTER)
        }
    }
    private fun number(value:Int)=NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed=false }.format(value)
    private fun text(canvas:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,font:Typeface,align:Paint.Align,tracking:Float=0f){
        paint.reset();paint.isAntiAlias=true;paint.typeface=font;paint.textSize=size;paint.color=color;paint.textAlign=align
        val baseline=y-(paint.ascent()+paint.descent())/2f
        if(tracking==0f){canvas.drawText(value,x,baseline,paint);return}
        // Android has no Canvas letterSpacing; preserve source point tracking
        // for the recovered weekday string with Unicode code-point boundaries.
        val glyphs=value.codePoints().toArray().map { String(Character.toChars(it)) }
        var cursor=x
        for(glyph in glyphs){canvas.drawText(glyph,cursor,baseline,paint);cursor+=paint.measureText(glyph)+tracking}
    }
}

/** The full-day label and 91/19/4.5 styling literals occur together at
 * CalendarWidget 0x2163b3320..3374. Host-family selection is not closed;
 * choosing this profile and all positions remain an Android preview adapter. */
private object CalendarDateWidgetParameters {
    const val weekdayFormat="EEEE"
    const val weekdayFontSize=19f
    const val weekdayTracking=4.5f
    const val dayNumberFontSize=91f
    const val firstLineX=5f
    const val firstLineY=22f
    const val dayNumberX=5f
    const val dayNumberY=83f
}
