package local.duostandby

/** Android frame-transaction adapter. Geometry and appearance of every navigation
 * layer are sampled once at the same Choreographer timestamp, including children
 * hidden by their parent. Drawing reads a completed snapshot, never advances it.
 * Keeps the existing source-derived .35/1 presentation parameters. This is NOT
 * the complete AnimationKit Composer/targetVelocity or UIKit input-state port. */
class OriginalNavigationFrame(initialOuter: Double, initialClock: Double) {
    data class Layer(
        val axis: OriginalStandByNavigation.Axis,
        val index: Int,
        val offsetPages: Double,
        val targetProgress: Double,
        val presentationProgress: Double,
        val scale: Double,
        val running: Boolean
    ) {
        val visible: Boolean get() = targetProgress > 0.0 || presentationProgress > 1e-5 || running
    }
    data class Snapshot(
        val frameTimeNanos: Long,
        val outer: List<Layer>,
        val clocks: List<Layer>
    ) {
        val running: Boolean get() = outer.any { it.running } || clocks.any { it.running }
        fun clockVisible(index: Int): Boolean =
            outer[OriginalStandByNavigation.Mode.CLOCKS.ordinal].visible && clocks[index].visible
    }
    private val outerSprings = OriginalStandByNavigation.Mode.entries.map {
        OriginalStandByNavigation.Presentation(OriginalStandByNavigation.progress(it.ordinal, initialOuter))
    }
    private val clockSprings = OriginalStandByNavigation.clockFaces.indices.map {
        OriginalStandByNavigation.Presentation(OriginalStandByNavigation.progress(it, initialClock))
    }
    private var lastFrame: Long? = null
    var snapshot = Snapshot(0L,
        initialLayers(OriginalStandByNavigation.Axis.HORIZONTAL, initialOuter, outerSprings),
        initialLayers(OriginalStandByNavigation.Axis.VERTICAL, initialClock, clockSprings))
        private set

    private fun initialLayers(axis: OriginalStandByNavigation.Axis, page: Double,
                              springs: List<OriginalStandByNavigation.Presentation>) =
        springs.mapIndexed { index, spring ->
            Layer(axis, index, index-page, OriginalStandByNavigation.progress(index, page),
                spring.value, OriginalStandByNavigation.scale(axis, spring.value), false)
        }

    fun advance(nanos: Long, outerPage: Double, clockPage: Double, animated: Boolean): Snapshot {
        // A repeated draw/update of the same frame must not consume time twice.
        // Events arriving after this commit are sampled in the next frame.
        if (lastFrame?.let { nanos <= it } == true) return snapshot
        fun sample(axis: OriginalStandByNavigation.Axis, page: Double,
                   springs: List<OriginalStandByNavigation.Presentation>) =
            springs.mapIndexed { index, spring ->
                val target = OriginalStandByNavigation.progress(index, page)
                val value = spring.sample(target, nanos, animated)
                Layer(axis, index, index-page, target, value,
                    OriginalStandByNavigation.scale(axis, value), spring.running)
            }
        val next = Snapshot(nanos,
            sample(OriginalStandByNavigation.Axis.HORIZONTAL, outerPage, outerSprings),
            sample(OriginalStandByNavigation.Axis.VERTICAL, clockPage, clockSprings))
        lastFrame = nanos
        snapshot = next
        return next
    }
}
