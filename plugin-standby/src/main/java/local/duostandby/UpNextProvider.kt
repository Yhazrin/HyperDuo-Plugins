package local.duostandby

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Up Next widget data: upcoming CalendarProvider events, read through root
 * (content query, the existing root bridge). Original analog is the Calendar
 * "Up Next" widget; Android has no WidgetKit timeline, so this is a platform
 * adapter. Empty when there are no events; nothing is fabricated.
 */
object UpNextProvider {
    data class Event(val title: String, val startMs: Long)
    @Volatile var events: List<Event> = emptyList()
        private set
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L

    fun refresh(context: Context) {
        // Integration boundary: opening a clock must not request unrelated private-data Root access.
        if (!context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_calendar_opt_in", false)) { events = emptyList(); return }
        if (System.currentTimeMillis() - lastAttempt < 5 * 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        Thread {
            try {
                val now = System.currentTimeMillis()
                val where = "dtstart>$now AND deleted=0"
                val process = ProcessBuilder("su", "-c",
                    "/system/bin/content query --uri content://com.android.calendar/events" +
                        " --projection title:dtstart --where \"$where\" --sort \"dtstart ASC\"").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(6, TimeUnit.SECONDS)
                events = parse(output).take(3)
            } catch (_: Throwable) {
                // keep previous
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun parse(output: String): List<Event> =
        output.lineSequence().mapNotNull { line ->
            val title = Regex("(?:^|[ ,])title=([^,]*)").find(line)?.groupValues?.get(1)?.trim()
            val start = Regex("(?:^|[ ,])dtstart=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (title.isNullOrBlank() || start == null) null else Event(title, start)
        }.toList()
}
