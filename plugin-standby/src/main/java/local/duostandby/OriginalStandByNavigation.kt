package local.duostandby

import kotlin.math.*

/** Hierarchy, not Face.ordinal. AmbientUI 24A94425: provider categories are
 * horizontal (ba9c), configurations inside category vertical (2a224), both
 * noncircular by default (1a05c/1a068). Actual available order below follows
 * iPhone session01, not a claim that Simulator configuration order is identical.
 * CALENDAR remains a direct-launch research face plus a supported widget entry;
 * no full-screen calendar category was established in the physical recording. */
object OriginalStandByNavigation {
    enum class Mode { WIDGETS, PHOTOS, CLOCKS }
    enum class Axis { NONE, HORIZONTAL, VERTICAL }
    val clockFaces = listOf(Face.SOLAR, Face.FLOAT, Face.WORLD, Face.ANALOG, Face.DIGITAL, Face.WEATHER)
    fun mode(face: Face) = when (face) {
        Face.WIDGETS, Face.CALENDAR -> Mode.WIDGETS
        Face.PHOTOS -> Mode.PHOTOS
        else -> Mode.CLOCKS
    }
    fun clockIndex(face: Face) = clockFaces.indexOf(face).coerceAtLeast(0)
    fun face(mode: Mode, clock: Face) = when (mode) {
        Mode.WIDGETS -> Face.WIDGETS
        Mode.PHOTOS -> Face.PHOTOS
        Mode.CLOCKS -> clock.takeIf { it in clockFaces } ?: Face.ANALOG
    }
    /** Android touch slop adapter. Once chosen, a diagonal/reverse drag cannot
     * switch owner; the independent child stack and root use the same rule. */
    fun lock(previous: Axis, dx: Double, dy: Double, slop: Double): Axis {
        if (previous != Axis.NONE) return previous
        if (max(abs(dx), abs(dy)) <= slop) return Axis.NONE
        return if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
    }
    fun progress(index: Int, page: Double) = 1.0 - abs(index - page).coerceIn(0.0, 1.0)

    /** Retargetable analytic critical spring adapter for the recovered poster
     * presentation target. Source response=.35, damping=1.0, tension=(2π/r)^2.
     * UIKit's interactive animation retarget implementation is not reproduced
     * bit-for-bit. Do not confuse this with the separate scroll-offset kernel. */
    class Presentation(initial: Double) {
        var value = initial; private set
        private var velocity = 0.0
        private var target = initial
        private var time: Long? = null
        var running = false; private set
        fun sample(next: Double, nanos: Long, animated: Boolean): Double {
            if (!animated) { value=next; target=next;velocity=0.0;time=nanos;running=false;return value }
            val old=time
            if (old!=null) {
                val dt=(nanos-old).coerceAtLeast(0L)/1e9
                val w=2.0*PI/.35;val y=value-target;val a=velocity+w*y;val e=exp(-w*dt)
                value=target+(y+a*dt)*e;velocity=(velocity-w*a*dt)*e
            }
            time=nanos;target=next
            // Adapter-only termination tolerance, not an asserted UIKit stop rule.
            running=abs(value-target)>1e-5 || abs(velocity)>1e-4
            if (!running) {value=target;velocity=0.0}
            return value.coerceIn(0.0,1.0)
        }
    }
    fun scale(axis: Axis, progress: Double): Double {
        val target=if(axis==Axis.HORIZONTAL).61 else Double.fromBits(0x3fec4ec4e0000000L)
        return target+(1.0-target)*progress
    }
}
