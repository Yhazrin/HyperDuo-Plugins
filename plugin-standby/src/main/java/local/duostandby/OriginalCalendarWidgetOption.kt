package local.duostandby

import java.util.Locale

/** Android storage adapter for CalendarPoster._header: StringWidgetItem.
 * This is NOT the private PosterKit wire format. Missing old data stays explicit;
 * showTime is not a header selection and may never migrate to an empty widget.
 */
sealed interface CalendarHeaderSelection {
    data object LegacyDefault : CalendarHeaderSelection
    data class DateFormat(val value:CalendarHeaderDateFormat) : CalendarHeaderSelection
    data class PreservedUnknown(val encoded:String) : CalendarHeaderSelection
}

/** Original StringWidgetDateFormat factories 0x2162e8b88..0x2162e91c0. */
enum class CalendarHeaderDateFormat(val pattern:String,val titleKey:String,val chineseTitle:String) {
    DAY_TIME("E h:mm","Day and Time","日期和时间"),
    TIME_DAY("h:mm E","Time and Day","时间和日期"),
    DAY("EEEE","Day","日期"),
    DAY_MONTH("E MMM","Day and Month","日期和月份"),
    MONTH_DAY("MMM E","Month and Day","月份和日期"),
    MONTH("LLLL","Month","月份");
    fun title(locale:Locale=Locale.getDefault())=if(locale.language=="zh")chineseTitle else titleKey
}

object OriginalCalendarWidgetOption {
    private const val PREFIX="date-format:"
    // 0x2162fc9dc..0x2162fca60: date(raw0) has header; month(raw1) editingUnavailable.
    fun canEdit(style:Int)=style==0
    const val MONTH_UNAVAILABLE="此样式无法自定义小组件。"
    const val WEATHER_UNAVAILABLE="天气状况不可用：原天气 provider 尚未接入。"
    // 0x2162e9518..0x2162e9530: prefix E, allowsRemoval=false. No empty choice.
    const val DATE_PREFIX="E"
    const val ALLOWS_REMOVAL=false

    fun dateFormats(dayDoesNotComeFirst:Boolean):List<CalendarHeaderDateFormat> =
        if(dayDoesNotComeFirst)listOf(CalendarHeaderDateFormat.TIME_DAY,CalendarHeaderDateFormat.DAY,
            CalendarHeaderDateFormat.MONTH_DAY,CalendarHeaderDateFormat.MONTH)
        else listOf(CalendarHeaderDateFormat.DAY_TIME,CalendarHeaderDateFormat.DAY,
            CalendarHeaderDateFormat.DAY_MONTH,CalendarHeaderDateFormat.MONTH)

    fun encode(selection:CalendarHeaderSelection):String?=when(selection){
        CalendarHeaderSelection.LegacyDefault -> null
        is CalendarHeaderSelection.DateFormat -> PREFIX+selection.value.pattern
        is CalendarHeaderSelection.PreservedUnknown -> selection.encoded
    }
    fun decode(encoded:String?):CalendarHeaderSelection {
        if(encoded==null)return CalendarHeaderSelection.LegacyDefault
        val format=if(encoded.startsWith(PREFIX))CalendarHeaderDateFormat.entries.firstOrNull{
            it.pattern==encoded.removePrefix(PREFIX)
        }else null
        return if(format!=null)CalendarHeaderSelection.DateFormat(format)
               else CalendarHeaderSelection.PreservedUnknown(encoded)
    }
    fun selectionTitle(selection:CalendarHeaderSelection)=when(selection){
        CalendarHeaderSelection.LegacyDefault -> "原有默认显示（未选自定义标题）"
        is CalendarHeaderSelection.DateFormat -> selection.value.title()
        is CalendarHeaderSelection.PreservedUnknown -> "未支持的标题配置（已保留）"
    }
}
