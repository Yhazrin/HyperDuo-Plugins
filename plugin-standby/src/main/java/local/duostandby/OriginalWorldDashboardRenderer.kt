package local.duostandby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.icu.text.DateTimePatternGenerator
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import java.time.Instant
import java.util.Date
import java.util.Locale

/** Original local-offset/selected-zone Dashboard branches; no fictional city/pin/alarm.
 * Original CLKTimeFormatter78b20 supplies locale/hour-cycle timeText. Android
 * ICU/system hour preference is an explicit data adapter, not Apple locale data.
 * Source named font/size/color/offset are retained; SwiftUI text layout parity
 * (including nil/default VStack spacing) requires original-host comparison.
 */
class OriginalWorldDashboardRenderer(private val context:Context) {
    private val density=context.resources.displayMetrics.density
    private val fonts=OriginalFonts(context)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var cacheKey=""
    private lateinit var formatter:SimpleDateFormat
    private fun clockText(instant:Instant,zoneId:java.time.ZoneId):String {
        val locale=Locale.getDefault()
        val hour24=android.text.format.DateFormat.is24HourFormat(context)
        val zone=zoneId.id
        val includeDesignator=zoneId.rules.getOffset(instant)!=java.time.ZoneId.systemDefault().rules.getOffset(instant)
        val key="$locale/$hour24/$zone/$includeDesignator"
        if(cacheKey!=key){
            // CLKTimeFormatter.timeText excludes the designator in local-offset
            // branch. ICU skeleton Hm/hm preserves localized punctuation/digits.
            val pattern=DateTimePatternGenerator.getInstance(locale).getBestPattern(if(hour24)"Hm" else "hm")
            formatter=SimpleDateFormat(if(includeDesignator)pattern else withoutDayPeriod(pattern),locale)
            formatter.timeZone=TimeZone.getTimeZone(zone);cacheKey=key
        }
        return formatter.format(Date.from(instant))
    }
    private fun withoutDayPeriod(pattern:String):String {
        val out=StringBuilder();var quoted=false;var i=0
        fun space(c:Char)=c.isWhitespace()||Character.isSpaceChar(c)
        while(i<pattern.length){
            val c=pattern[i]
            if(c=='\''){
                out.append(c)
                if(i+1<pattern.length&&pattern[i+1]=='\''){out.append(c);i+=2;continue}
                quoted=!quoted;i++;continue
            }
            if(!quoted&&c in "abB"){
                while(out.isNotEmpty()&&space(out.last()))out.setLength(out.length-1)
                while(i<pattern.length&&pattern[i]==c)i++
                while(i<pattern.length&&space(pattern[i]))i++
            }else{out.append(c);i++}
        }
        return out.toString().trim(::space)
    }
    fun draw(canvas:Canvas,width:Int,height:Int,instant:Instant,redMode:Boolean,city:OriginalWorldPins.City?=null,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()) {
        val text=clockText(instant,zone)
        val rtl=context.resources.configuration.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.worldTime
        paint.textSize=OriginalWorldDashboard.TIME_SIZE.toFloat()
        val timeMetrics=paint.fontMetrics
        val timeHeight=(timeMetrics.descent-timeMetrics.ascent).toDouble()
        paint.typeface=fonts.semibold;paint.textSize=OriginalWorldDashboard.NAME_SIZE.toFloat()
        val nameMetrics=paint.fontMetrics
        // Nil/default SwiftUI stack spacing remains a host adapter, not a
        // source literal; do not confuse this8 with the recovered49pt margins.
        val layout=OriginalWorldDashboard.layout(width/density.toDouble(),height/density.toDouble(),
            timeHeight,(nameMetrics.descent-nameMetrics.ascent).toDouble(),8.0,!city?.name.isNullOrEmpty(),rtl)
        val save=canvas.save();canvas.scale(density,density)
        paint.typeface=fonts.worldTime;paint.textSize=OriginalWorldDashboard.TIME_SIZE.toFloat()
        paint.color=if(redMode)Color.RED else Color.WHITE
        paint.textAlign=if(rtl)Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(text,layout.leading.toFloat(),(layout.timeTop-timeMetrics.ascent).toFloat(),paint)
        if(!city?.name.isNullOrEmpty()){
            paint.typeface=fonts.semibold;paint.textSize=OriginalWorldDashboard.NAME_SIZE.toFloat()
            paint.color=if(redMode)Color.rgb(153,0,0)else Color.rgb(141,141,146)
            canvas.drawText(city!!.name,layout.leading.toFloat(),(layout.nameTop-nameMetrics.ascent).toFloat(),paint)
        }
        canvas.restoreToCount(save)
    }
}
