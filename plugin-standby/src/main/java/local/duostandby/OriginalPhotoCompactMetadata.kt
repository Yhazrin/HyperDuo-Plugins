package local.duostandby

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Ordinary AmbientPhotoFrame compact metadata, not PhotosClockPoster SwiftUI.
 * Native 27.1: 0x1e33970e0 show .3s, completion +6s, 0x1e33977f4 hide .3s.
 * Retained identities replace delayed callbacks: an expired asset cannot hide a new one.
 * No click/gesture triggers are invented. Only accepted asset or source text changes show.
 */
class OriginalPhotoCompactMetadata {
    data class SafeInsets(val left: Float = 0f, val right: Float = 0f)
    data class Content(val identity: String, val location: String?, val captured: LocalDateTime?)
    private data class Episode(val content: Content, val started: Long, val initialAlpha: Float)
    private val episodes = linkedMapOf<String, Episode>()

    fun clear() = episodes.clear()

    fun update(contents: List<Content>, nowNanos: Long) {
        val visible = contents.associateBy { it.identity }
        episodes.keys.retainAll(visible.keys)
        visible.forEach { (identity, content) ->
            if (content.location.isNullOrBlank() && content.captured == null) {
                episodes.remove(identity)
            } else if (episodes[identity]?.content != content) {
                val previous = alpha(identity, nowNanos)
                episodes[identity] = Episode(content, nowNanos, previous)
            }
        }
    }

    fun alpha(identity: String, nowNanos: Long): Float {
        val episode = episodes[identity] ?: return 0f
        val elapsed = ((nowNanos - episode.started).coerceAtLeast(0L) / 1_000_000.0)
        return when {
            elapsed < FADE_MILLIS -> episode.initialAlpha +
                (1f - episode.initialAlpha) * defaultUIViewEase(elapsed / FADE_MILLIS)
            elapsed < HIDE_START_MILLIS -> 1f
            elapsed < END_MILLIS -> 1f - defaultUIViewEase((elapsed - HIDE_START_MILLIS) / FADE_MILLIS)
            else -> 0f
        }
    }

    /** Retain frame scheduling through the dwell so the eventual fade-out is reached. */
    fun needsFrame(nowNanos: Long): Boolean = episodes.values.any {
        nowNanos - it.started < (END_MILLIS * 1_000_000).toLong()
    }

    companion object {
        const val TITLE_MAX_POINTS = 32f
        const val TITLE_MIN_POINTS = 24f
        const val DATE_POINTS = 18f
        const val BASELINE_GAP_POINTS = 28f
        const val WIDTH_FRACTION = 2f / 3f
        const val FADE_MILLIS = 300.0
        const val HIDE_START_MILLIS = 6300.0
        const val END_MILLIS = 6600.0

        // NSDateFormatter medium date/no time + autoupdatingCurrentLocale is the
        // native contract. Java localized MEDIUM is the Android locale adapter.
        fun dateText(captured: LocalDateTime?, locale: Locale = Locale.getDefault()): String? =
            captured?.toLocalDate()?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

        // Native options 0x7000000 have the default UIView curve bits. Reuse the
        // same-build default cubic already recovered by OriginalConfigurationPortalMotion;
        // this is not a separately sampled Photos alpha presentation curve.
        private fun defaultUIViewEase(t: Double): Float {
            if (t <= 0.0) return 0f
            if (t >= 1.0) return 1f
            var lo = 0.0; var hi = 1.0
            repeat(24) {
                val u = (lo + hi) * .5; val v = 1.0 - u
                val x = 3.0 * v * v * u * .42 + 3.0 * v * u * u * .58 + u * u * u
                if (x < t) lo = u else hi = u
            }
            val u = (lo + hi) * .5
            return (3.0 * (1.0 - u) * u * u + u * u * u).toFloat()
        }
    }
}
