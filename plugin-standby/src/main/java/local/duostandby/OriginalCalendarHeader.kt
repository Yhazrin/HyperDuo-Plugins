package local.duostandby

import android.icu.text.DateTimePatternGenerator
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/** Source: CalendarPoster cabfc/c1e18 and CalendarWidget LocaleConventions.
 * ICU is an Android locale adapter, not proof of Apple locale-data/font parity.
 * Date-format selection follows cabfc; weather/provider rendering is unavailable.
 * Formatter locale/timezone and Canvas layout are explicit Android adapters.
 */
object OriginalCalendarHeader {
    data class Parts(val weekday:String,val time:String,val timeFirst:Boolean)
    data class Run(val text:String,val secondary:Boolean=false)
    private val dateFormatters=mutableMapOf<String,SimpleDateFormat>()
    private var cachedLocale:Locale?=null
    private lateinit var clock:SimpleDateFormat
    private lateinit var weekday:SimpleDateFormat
    private var cachedTimeFirst=false
    private fun ensure(locale:Locale){
        if(cachedLocale==locale)return
        val generator=DateTimePatternGenerator.getInstance(locale)
        // ICU Jm pads en-US to hh:mm, unlike Foundation defaultDigits (h:mm).
        // Preserve jm's locale hour width and omit only unquoted day-period fields.
        clock=SimpleDateFormat(withoutDayPeriod(generator.getBestPattern("jm")),locale)
        weekday=SimpleDateFormat("E",locale)
        // Inputs are wall-clock LocalDateTime, so UTC preserves those fields.
        clock.timeZone=TimeZone.getTimeZone("UTC");weekday.timeZone=TimeZone.getTimeZone("UTC")
        cachedTimeFirst=dayDoesNotComeFirst(locale)
        dateFormatters.clear()
        cachedLocale=locale
    }
    /** CalendarWidget 0x2163f1634: ordered searches, not first occurrence of any
     * matching character; ja/zh/ko applies ONLY to the missing-pattern/token branch.
     * Android ICU generates the probe template; Apple locale data parity is not assumed.
     */
    fun dayDoesNotComeFirst(locale:Locale=Locale.getDefault()):Boolean {
        val pattern:String?=DateTimePatternGenerator.getInstance(locale).getBestPattern("yMMMEd")
        val day=listOf('E','e','c').map{pattern?.indexOf(it)?:-1}.firstOrNull{it>=0}
        val month=listOf('M','L').map{pattern?.indexOf(it)?:-1}.firstOrNull{it>=0}
        return if(day!=null && month!=null)month<day else locale.language in setOf("ja","zh","ko")
    }
    private fun format(pattern:String,time:LocalDateTime,locale:Locale):String {
        ensure(locale)
        val formatter=dateFormatters.getOrPut(pattern){
            SimpleDateFormat(pattern,locale).apply{timeZone=TimeZone.getTimeZone("UTC")}
        }
        // UTC is a wall-clock LocalDateTime adapter, not a recovered native timezone override.
        return formatter.format(Date.from(time.toInstant(ZoneOffset.UTC)))
    }
    /** For the six recovered payloads, cabfc/cadd0 splits E from a remainder.
     * Remainder h:mm uses locale-aware c1e18 time; MMM is directly formatted (cb1e8).
     * EEEE / LLLL have one c0124/secondary Text (cb11c, cb354). No header is derived from showTime.
     */
    fun runs(time:LocalDateTime,selection:CalendarHeaderSelection,locale:Locale=Locale.getDefault()):List<Run> {
        if(selection is CalendarHeaderSelection.PreservedUnknown)
            return listOf(Run("标题配置不可用")) // Explicit adapter state; no fabricated provider content.
        val choice=(selection as? CalendarHeaderSelection.DateFormat)?.value
        if(choice!=null && (choice==CalendarHeaderDateFormat.DAY || choice==CalendarHeaderDateFormat.MONTH))
            return listOf(Run(format(choice.pattern,time,locale),true))
        val parts=parts(time,locale)
        val remainder=if(choice==CalendarHeaderDateFormat.DAY_MONTH || choice==CalendarHeaderDateFormat.MONTH_DAY)
            format("MMM",time,locale) else parts.time
        // Original render order uses environment locale, not the saved payload's literal order.
        return if(parts.timeFirst)listOf(Run(remainder+" ",true),Run(parts.weekday))
               else listOf(Run(parts.weekday+" "),Run(remainder,true))
    }
    private fun withoutDayPeriod(pattern:String):String {
        val out=StringBuilder();var quoted=false;var i=0
        fun space(c:Char)=c.isWhitespace()||Character.isSpaceChar(c)
        while(i<pattern.length){
            val c=pattern[i]
            if(c=='\''){
                out.append(c)
                if(i+1<pattern.length && pattern[i+1]=='\''){out.append(c);i+=2;continue}
                quoted=!quoted;i++;continue
            }
            if(!quoted && c in "abB"){
                while(out.isNotEmpty()&&space(out.last()))out.setLength(out.length-1)
                while(i<pattern.length&&pattern[i]==c)i++
                while(i<pattern.length&&space(pattern[i]))i++
            }else{out.append(c);i++}
        }
        return out.toString().trim(::space)
    }
    fun time(time:LocalDateTime,locale:Locale=Locale.getDefault()):String {
        ensure(locale);return clock.format(Date.from(time.toInstant(ZoneOffset.UTC)))
    }
    fun parts(time:LocalDateTime,locale:Locale=Locale.getDefault()):Parts {
        ensure(locale);val date=Date.from(time.toInstant(ZoneOffset.UTC))
        return Parts(weekday.format(date),clock.format(date),cachedTimeFirst)
    }
    fun fontSize(aspectClass:Int)=if(aspectClass==1||aspectClass==2)50f else 60f
    fun offsetY(aspectClass:Int)=if(aspectClass==1)-168f else -173.5f
    fun frameHeight(aspectClass:Int)=if(aspectClass==2)50f else 98f
    const val DATE_FORMAT_FRAME_WIDTH=484f
}
