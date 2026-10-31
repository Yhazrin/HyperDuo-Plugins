package local.duostandby

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Independent translation of the verified CalendarPoster locale rules.
 * Evidence: ORIGINAL_CALENDAR_LOCALE.md, 0x2162ecf78 (weekday rotation),
 * 0x2162ecbe0 (base locale), and ORIGINAL_CALENDAR_LAYOUT.md (localized yMMMM).
 *
 * This experiment still generates ISO/Gregorian LocalDate cells. It does not
 * implement Apple's other Calendar identifiers or their month/day arithmetic.
 * FaceOptions.numerals currently selects a font style, NOT ClockFaceNumerals;
 * therefore this helper deliberately does not reinterpret that preference as nu.
 * No Android dependency: the renderer injects ICU's pattern and weekday symbols.
 */
object OriginalCalendarLocale {
    const val CALENDAR_IMPLEMENTATION = "ISO_GREGORIAN_ONLY"

    data class Snapshot(
        val locale: Locale,
        val firstDayOfWeek: DayOfWeek,
        val weekdayLabels: List<String>,
        val requestedCalendarType: String,
        val localizedMonthYearPattern: String
    ) {
        val usesGregorianFallback: Boolean
            get() = requestedCalendarType != "gregorian" && requestedCalendarType != "gregory" && requestedCalendarType != "iso8601"

        private val monthYearFormatter = DateTimeFormatter
            .ofPattern(localizedMonthYearPattern, locale)
            .withDecimalStyle(DecimalStyle.of(locale))

        fun monthTitle(date: LocalDate): String = date.format(monthYearFormatter)

        fun monthName(date: LocalDate): String =
            date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)

        fun gridStart(date: LocalDate): LocalDate {
            val first = date.withDayOfMonth(1)
            val precedingDays = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
            return first.minusDays(precedingDays.toLong())
        }
    }

    /** firstWeekday follows Calendar/ICU's 1=Sunday ... 7=Saturday convention. */
    fun create(
        locale: Locale,
        firstWeekday: Int,
        sundayFirstSymbols: List<String>,
        localizedMonthYearPattern: String,
        requestedCalendarType: String = "gregorian"
    ): Snapshot {
        require(firstWeekday in 1..7)
        require(sundayFirstSymbols.size == 7)
        val offset = firstWeekday - 1
        return Snapshot(
            locale = locale,
            firstDayOfWeek = DayOfWeek.of(if (firstWeekday == 1) 7 else firstWeekday - 1),
            weekdayLabels = List(7) { sundayFirstSymbols[(it + offset) % 7] },
            requestedCalendarType = requestedCalendarType,
            localizedMonthYearPattern = localizedMonthYearPattern
        )
    }

    /** JVM-only adapter; Android supplies ICU's locale-specific yMMMM pattern. */
    fun forJavaLocale(locale: Locale, localizedMonthYearPattern: String): Snapshot {
        val first = WeekFields.of(locale).firstDayOfWeek
        val symbols = List(7) { index ->
            DayOfWeek.of(if (index == 0) 7 else index)
                .getDisplayName(TextStyle.NARROW_STANDALONE, locale)
        }
        return create(
            locale,
            if (first == DayOfWeek.SUNDAY) 1 else first.value + 1,
            symbols,
            localizedMonthYearPattern
        )
    }
}
