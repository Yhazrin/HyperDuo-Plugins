package local.duostandby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** Live digital widget for kind com.apple.mobiletimer.datetime ("Date Time").
 *
 * Recovered from the real WorldClockWidget executable (24A94425, device IPSW
 * iPhone19,4): the time font is `.SFSoftNumeric-CompressedSemibold` created with
 * CTFontCreateWithNameAndOptions (0x100029d54) at sizes 96 and 80 (lazy wrappers
 * 0x100029d18 / 0x100029c8c); the small dimensions provider caps the content at
 * 136 points (SmallSizeDimentions, maxWidth 0x4061 = 136 at 0x10002186c).
 * The font family, the two point sizes and the 136-point design extent are source
 * values. Date placement and the optional AM/PM line remain Android adapters: the
 * DateTimeWidgetView body positions were not recovered, so this file must not be
 * described as the full native body.
 */
class DigitalWidgetContent(private val context:Context) {
    companion object {
        const val SOURCE_BUNDLE="com.apple.mobiletimer.WorldClockWidget"
        const val SOURCE_EXECUTABLE="WorldClockWidget"
        const val SOURCE_KIND="com.apple.mobiletimer.datetime"
        const val SOURCE_FONT=".SFSoftNumeric-CompressedSemibold"
        const val SOURCE_TIME_FONT_PT=96f
        const val SOURCE_TIME_FONT_PT_MEDIUM=80f
        const val SOURCE_DESIGN_EXTENT=136f
        const val PORT_STATUS="SOURCE_FONT_AND_EXTENT_BODY_LAYOUT_ADAPTER"
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val fonts=OriginalFonts(context)
    /** Compatibility overload; new renderer passes its frame Instant and Entry. */
    fun draw(canvas:Canvas,bounds:RectF,night:Boolean)=draw(canvas,bounds,Instant.now(),OriginalWidgetStackHost.Entry("DIGITAL","数字时钟"),night)
    fun draw(canvas:Canvas,bounds:RectF,instant:Instant,entry:OriginalWidgetStackHost.Entry,night:Boolean){
        if(bounds.isEmpty)return
        val zone=WidgetClockContent.zone(entry.zoneId)
        val time=instant.atZone(zone)
        val use24=entry.clock24Hour ?: android.text.format.DateFormat.is24HourFormat(context)
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        val scale=min(bounds.width(),bounds.height())/SOURCE_DESIGN_EXTENT
        val color=if(night)VisualTokens.NIGHT_RED else Color.WHITE
        val save=canvas.save()
        canvas.translate(bounds.centerX()-SOURCE_DESIGN_EXTENT*scale/2f,
            bounds.centerY()-SOURCE_DESIGN_EXTENT*scale/2f)
        canvas.scale(scale,scale)
        // Date: SwiftUI semantic font in the native view; this Android size is an adapter.
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textAlign=Paint.Align.CENTER
        paint.textSize=13f;paint.color=color
        canvas.drawText(time.format(DateTimeFormatter.ofPattern("EEE, MMM d",locale)),68f,15f,paint)
        // Time: recovered family + point size. The compressed width is what lets
        // four/five glyphs fit the 136-point small widget.
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.softCompressedSemibold
        paint.textAlign=Paint.Align.CENTER;paint.textSize=SOURCE_TIME_FONT_PT;paint.color=color
        val text=time.format(DateTimeFormatter.ofPattern(if(use24)"HH:mm" else "h:mm",locale))
        val baseline=78f-(paint.ascent()+paint.descent())/2f
        val width=paint.measureText(text)
        if(width>SOURCE_DESIGN_EXTENT-4f)paint.textSize*= (SOURCE_DESIGN_EXTENT-4f)/width
        canvas.drawText(text,68f,baseline,paint)
        if(!use24){
            paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textAlign=Paint.Align.CENTER
            paint.textSize=11f;paint.color=color;paint.alpha=200
            canvas.drawText(time.format(DateTimeFormatter.ofPattern("a",locale)),68f,130f,paint)
        }
        canvas.restoreToCount(save)
    }
}
