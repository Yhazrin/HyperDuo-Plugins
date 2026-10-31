package local.duostandby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** Independent widget reconstruction, NOT WorldClockWidget's recovered SwiftUI.
 * Host kind/bundle identities are source-bound; geometry and Android font/layout
 * below remain explicit preview adapters. Real Instant+ZoneId drives every hand.
 * No ClockPoster, cached screenshot, fabricated location or weather is involved.
 */
class WidgetAnalogContent(context: Context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    /** Original WorldClockWidget bundle assets (858x858 templates):
     * square = 12 ticks + 12/3/6/9; circle = 12 ticks + 1..12. */
    private val squareFace=android.graphics.BitmapFactory.decodeStream(
        context.assets.open("widget/square-face.png"))
    private val circleFace=android.graphics.BitmapFactory.decodeStream(
        context.assets.open("widget/circle-face.png"))
    fun draw(canvas:Canvas,bounds:RectF,instant:Instant,entry:OriginalWidgetStackHost.Entry,night:Boolean,circle:Boolean=false){
        if(bounds.isEmpty)return
        val t=instant.atZone(WidgetClockContent.zone(entry.zoneId))
        val ink=if(night)VisualTokens.NIGHT_RED else Color.WHITE
        val accent=if(night)VisualTokens.NIGHT_RED else Color.rgb(255,159,10)
        val extent=min(bounds.width(),bounds.height())
        val scale=extent/136f
        val save=canvas.save()
        canvas.translate(bounds.centerX()-extent/2f,bounds.centerY()-extent/2f)
        canvas.scale(scale,scale)
        paint.reset();paint.isAntiAlias=true
        paint.colorFilter=android.graphics.PorterDuffColorFilter(ink,android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(if(circle)circleFace else squareFace,null,RectF(0f,0f,136f,136f),paint)
        // ClockHandShape.path(in:) @0x1000431c4（WorldClockWidget.appex，符号已被 redacted）：
        //   0x100043228 fmov d0,#0.5 → half = length * 0.5
        //   0x100043230 mov x8,#0x3333333333333333 / movk #0xbfc3,lsl48 → double -0.15
        //   0x10004323c fsub d1,d11,d0  → 秒针尾端 = length*(1+0.15)，即反向 0.15*length
        //   0x100043240 tip = half * d8（d8 = 第 6 个 double 入参 unitLength，运行期传入）
        // 已确认：0.5 与 0.15 是原版常量；unitLength 的具体取值（本文件 0.80/0.90）
        // 仍是 Android 适配值，未从调用点解出。
        val half=68f
        val sec=t.second+t.nano/1e9
        val minutes=t.minute+sec/60.0
        val hours=t.hour%12+minutes/60.0
        fun hand(angleDeg:Float,tip:Float,tail:Float,width:Float,color:Int){
            val h=canvas.save();canvas.translate(68f,68f);canvas.rotate(angleDeg)
            paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE
            paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=width;paint.color=color
            canvas.drawLine(0f,half*tail,0f,-half*tip,paint)
            canvas.restoreToCount(h)
        }
        hand((hours*30).toFloat(),.50f,0f,5f,ink)
        hand((minutes*6).toFloat(),.80f,0f,5f,ink)
        hand((sec*6).toFloat(),.90f,.15f,1f,accent)
        // handConnectorCircleDiameter = 8 (SmallSizeDimentions).
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL;paint.color=ink
        canvas.drawCircle(68f,68f,4f,paint)
        canvas.restoreToCount(save)
    }
}

class WidgetWorldClockContent(private val context: Context) {
    private val analog=WidgetAnalogContent(context)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val font=OriginalFonts(context).medium
    fun draw(canvas:Canvas,bounds:RectF,instant:Instant,entry:OriginalWidgetStackHost.Entry,night:Boolean){
        if(bounds.isEmpty)return
        val extent=min(bounds.width(),bounds.height())
        val dial=RectF(bounds.centerX()-extent*.4f,bounds.top+extent*.02f,bounds.centerX()+extent*.4f,bounds.top+extent*.82f)
        analog.draw(canvas,dial,instant,entry,night,circle=true)
        val zone=WidgetClockContent.zone(entry.zoneId)
        val t=instant.atZone(zone)
        // Only identify the configured IANA zone. Do not invent a localized city.
        val use24=entry.clock24Hour ?: android.text.format.DateFormat.is24HourFormat(context)
        val lines=listOf(zone.id.replace('_',' '),t.format(DateTimeFormatter.ofPattern(if(use24)"HH:mm · XXX" else "h:mm a · XXX",Locale.getDefault())))
        paint.typeface=font;paint.color=if(night)VisualTokens.NIGHT_RED else Color.WHITE;paint.textAlign=Paint.Align.CENTER
        lines.forEachIndexed { index,line ->
            paint.textSize=extent*(if(index==0).065f else .045f)
            val measured=paint.measureText(line)
            if(measured>bounds.width()*.94f)paint.textSize*=bounds.width()*.94f/measured
            canvas.drawText(line,bounds.centerX(),bounds.top+extent*(.90f+index*.075f)-(paint.ascent()+paint.descent())/2f,paint)
        }
    }
}

object WidgetClockContent {
    const val SOURCE_BUNDLE="com.apple.mobiletimer.WorldClockWidget"
    const val SOURCE_DATETIME_KIND="com.apple.mobiletimer.datetime"
    const val SOURCE_SQUARE_KIND="com.apple.mobiletimer.square"
    const val PORT_STATUS="INDEPENDENT_CLOCK_BODY_ANDROID_RECONSTRUCTION"
    fun zone(id:String?):ZoneId=if(id==null)ZoneId.systemDefault() else runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
}
