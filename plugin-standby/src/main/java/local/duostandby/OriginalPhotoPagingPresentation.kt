package local.duostandby

import kotlin.math.abs

/** Same-build AmbientUI 24A94425 vertical profile, NOT measured Duo runtime values.
 * 0x2a244/0x2a510: scaleDownOnly=true, q=p; outer wrapper scale, child poster alpha.
 * Reuses the project's .35/1 retargetable presentation adapter, separate from the
 * original scroll-offset kernel. Stable configuration+asset IDs survive commit;
 * assets in one configuration must not share a presentation spring.
 */
class OriginalPhotoPagingPresentation {
    data class Sample(val progress: Double, val scale: Float, val alpha: Int,
                      val state: Int, val running: Boolean) {
        // AMUIPosterCategorySwitcherItem._updateClipping 0x2b56c.
        val clips get() = (state and 2.inv()) == 1
    }
    private class Entry(initial: Double) {
        val spring = OriginalStandByNavigation.Presentation(initial)
        var target = initial
        var state = if (initial == 1.0) 2 else 0
        var frame: Long? = null
        var sample = Sample(initial, scale(initial), (initial * 255.0).toInt(), state, false)
    }
    private val entries = linkedMapOf<String, Entry>()
    private val sampled = mutableSetOf<String>()
    val running get() = sampled.any { entries[it]?.sample?.running == true }

    fun clear() { entries.clear(); sampled.clear() }
    fun beginFrame(visiblePageId: String, retainedPageIds: Set<String>) {
        sampled.clear()
        entries.keys.retainAll(retainedPageIds + visiblePageId)
        // The origin was already visible before a gesture; adjacent items start at 0.
        entries.getOrPut(visiblePageId) { Entry(1.0) }
    }
    fun sample(pageId: String, offsetPages: Double, nanos: Long, animated: Boolean): Sample {
        val target = 1.0 - abs(offsetPages).coerceIn(0.0, 1.0)
        val entry = entries.getOrPut(pageId) { Entry(0.0) }
        sampled.add(pageId)
        if (entry.frame?.let { nanos <= it } == true) return entry.sample
        if (target > entry.target) entry.state = 1 // willAppear
        else if (target < entry.target) entry.state = 3 // willDisappear
        val progress = entry.spring.sample(target, nanos, animated)
        if (!entry.spring.running) {
            if (target == 1.0) entry.state = 2 // didAppear
            else if (target == 0.0) entry.state = 0 // didDisappear
        }
        entry.target = target; entry.frame = nanos
        entry.sample = Sample(progress, scale(progress),
            (progress * 255.0 + .5).toInt().coerceIn(0, 255), entry.state, entry.spring.running)
        return entry.sample
    }
    companion object {
        // Vertical default bits at 0x1a01c..1a028; never use horizontal .61 or editor .69.
        val TARGET_SCALE = Double.fromBits(0x3fec4ec4e0000000L)
        private fun scale(p: Double) = (TARGET_SCALE + (1.0 - TARGET_SCALE) * p).toFloat()
    }
}
