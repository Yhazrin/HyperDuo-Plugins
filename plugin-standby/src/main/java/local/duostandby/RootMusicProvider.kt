package local.duostandby

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Now-playing adapter for the MUSIC widget, reading the system media session via
 * root dumpsys (the app already uses the root bridge for battery/photos). The
 * original component is MusicWidgets.appex; Android has no WidgetKit session feed,
 * so this is an explicit platform adapter. Empty when no session/title exists.
 */
object RootMusicProvider {
    data class NowPlaying(val title: String, val artist: String?, val playing: Boolean)
    @Volatile var current: NowPlaying? = null
        private set
    private val fetching = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L

    fun refresh(context: Context) {
        // Integration boundary: opening a clock must not request unrelated private-data Root access.
        if (!context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_music_opt_in", false)) { current = null; return }
        if (System.currentTimeMillis() - lastAttempt < 20_000L) return
        if (!fetching.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        Thread {
            try {
                val process = ProcessBuilder("su", "-c", "dumpsys media_session").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(5, TimeUnit.SECONDS)
                current = parse(output)
            } catch (_: Throwable) {
                // Keep the previous value; no fabricated track.
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun parse(output: String): NowPlaying? {
        var title: String? = null
        var artist: String? = null
        var playing = false
        for (line in output.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("state=PlaybackState")) {
                val state = Regex("state=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                if (state != null) playing = state == 3
            }
            // Android 13+ prints metadata as "size=N, description=<title>, <artist>, <album>".
            if (t.startsWith("metadata:") && t.contains("description=")) {
                val desc = t.substringAfter("description=", "")
                val parts = desc.split(", ")
                if (parts.isNotEmpty()) title = parts[0].trim()
                if (parts.size > 1) artist = parts[1].trim()
            }
            if (title == null) {
                Regex("(?:^|[ ,])title=([^,}]+)").find(t)?.groupValues?.get(1)?.let { title = it.trim() }
            }
            if (artist == null) {
                Regex("(?:^|[ ,])artist=([^,}]+)").find(t)?.groupValues?.get(1)?.let { artist = it.trim() }
            }
        }
        val t = title?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return NowPlaying(t, artist?.takeIf { it != "null" }, playing)
    }
}
