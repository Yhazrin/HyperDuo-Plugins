package local.duostandby

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** PhotosClockPoster's metadata state and three animation channels, not AmbientPhotoFrame.
 * 24A94425 PhotosUIPrivate: 0x1e3340074 (deadline), 0x1e32de6e4 (onChange),
 * 0x1e34fdb80/db9c/dbc8 (springs), 0x1e32de800/de864/de8f0 (targets).
 * Android evaluates the recovered physical parameters analytically. This is not
 * a claim of sampled SwiftUI rendering parity. The owner must supply the native
 * show/hide event; changing an asset must NOT invent an automatic show event.
 */
class OriginalPhotosPosterMetadataMotion {
    data class Sample(val opacity: Double, val scale: Double, val blurPoints: Double)
    internal data class Spring(val stiffness: Double, val damping: Double, val delay: Double = 0.0)
    internal data class Value(val position: Double, val velocity: Double)
    internal class Channel(private var initial: Double) {
        private var target = initial
        private var velocity = 0.0
        private var start = 0L
        private var spring: Spring? = null

        fun sample(now: Long): Value {
            val s = spring ?: return Value(target, 0.0)
            val t = (now - start).coerceAtLeast(0L) / 1e9 - s.delay
            if (t <= 0.0) return Value(initial, velocity)
            val a = s.damping / (2.0 * MASS)
            val w2 = s.stiffness / MASS
            val delta = a * a - w2
            val x = initial - target
            val y: Double
            val v: Double
            // SpringAnimation.animate (24A94425, 0x1c7629ab8/ae0)
            // selects the critical solution for every damping ratio >= 1.
            // It does not use the two-root overdamped physical solution.
            if (delta >= 0.0) {
                val w = sqrt(w2)
                val b = velocity + w * x
                val decay = exp(-w * t)
                y = (x + b * t) * decay
                v = (b - w * (x + b * t)) * decay
            } else {
                val w = sqrt(-delta)
                val b = (velocity + a * x) / w
                val decay = exp(-a * t)
                val wave = x * cos(w * t) + b * sin(w * t)
                y = decay * wave
                v = decay * (-a * wave - x * w * sin(w * t) + b * w * cos(w * t))
            }
            return Value(target + y, v)
        }

        fun move(to: Double, at: Long, curve: Spring, animated: Boolean) {
            val current = sample(at)
            initial = if (animated) current.position else to
            // The native constructor explicitly passes initialVelocity=0. A
            // SwiftUI interruption may transfer velocity outside this constructor;
            // that runtime behavior is not established by this source slice.
            velocity = 0.0
            target = to
            start = at
            spring = if (animated) curve else null
        }

        fun logicallyComplete(now: Long): Boolean {
            val s = spring ?: return true
            // DelayAnimation passes max(0, elapsed-delay) to the base spring.
            val localTime = ((now - start).coerceAtLeast(0L) / 1e9 - s.delay).coerceAtLeast(0.0)
            // 0x1c7629c3c..64: threshold uses stiffness alone, not stiffness/mass.
            // b.hi skips the write only while threshold > time, so equality completes.
            return localTime >= 2.0 * PI / sqrt(s.stiffness)
        }

        fun active(now: Long): Boolean {
            if (spring == null) return false
            val current = sample(now)
            // Scheduling tolerance only, not a claimed native settling duration.
            return abs(current.position - target) > 1e-5 || abs(current.velocity) > 1e-5
        }
    }

    private val opacity = Channel(0.0)
    private val scale = Channel(HIDDEN_SCALE)
    private val blur = Channel(HIDDEN_BLUR_POINTS)
    private var deadline: Long? = null
    var isShowing = false
        private set

    /** nowNanos is the animation commit/start tick, not an earlier pointer-down tick. */
    fun setShowing(showing: Boolean, nowNanos: Long, animated: Boolean = true) {
        if (showing == isShowing) return
        isShowing = showing
        opacity.move(if (showing) 1.0 else 0.0, nowNanos, if (showing) SHOW_CONTENT else HIDE, animated)
        blur.move(if (showing) 0.0 else HIDDEN_BLUR_POINTS, nowNanos, if (showing) SHOW_CONTENT else HIDE, animated)
        scale.move(if (showing) 1.0 else HIDDEN_SCALE, nowNanos, if (showing) SHOW_SCALE else HIDE, animated)
        if (!showing) deadline = null
    }

    /** Third show transaction's logical completion, independent of visible spring settling.
     * The owner fires its completion once: scheduleAutoHide(currentTick), then clears
     * isTogglingMetadata. Sampling this predicate never schedules or hides anything.
     * Only the content transaction participates; the separate scale transaction does not.
     */
    fun showLogicallyComplete(nowNanos: Long): Boolean =
        isShowing && opacity.logicallyComplete(nowNanos) && blur.logicallyComplete(nowNanos)

    /** Native scheduleAutoHideMetadata captures the exact DispatchTime deadline. */
    fun scheduleAutoHide(nowNanos: Long): Long {
        val token = nowNanos + AUTO_HIDE_NANOS
        deadline = token
        return token
    }

    /** An old scheduled callback cannot hide a subsequently rescheduled episode. */
    fun fireAutoHide(token: Long, nowNanos: Long, animated: Boolean = true): Boolean {
        if (deadline != token || nowNanos < token) return false
        deadline = null
        setShowing(false, nowNanos, animated)
        return true
    }

    fun sample(nowNanos: Long): Sample = Sample(
        opacity.sample(nowNanos).position.coerceIn(0.0, 1.0),
        scale.sample(nowNanos).position,
        blur.sample(nowNanos).position.coerceAtLeast(0.0),
    )

    fun needsFrame(nowNanos: Long): Boolean = opacity.active(nowNanos) || scale.active(nowNanos) || blur.active(nowNanos)

    companion object {
        const val AUTO_HIDE_NANOS = 7_000_000_000L
        const val HIDDEN_SCALE = 1.03
        const val HIDDEN_BLUR_POINTS = 6.0
        private const val MASS = 2.0
        internal val SHOW_SCALE = Spring(200.0, 20.9439, 0.165)
        internal val SHOW_CONTENT = Spring(54.8311, 21.4147, 0.165)
        internal val HIDE = Spring(877.298, 85.5397)
    }
}
