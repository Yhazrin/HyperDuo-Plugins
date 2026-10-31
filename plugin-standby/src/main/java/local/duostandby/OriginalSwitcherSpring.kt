package local.duostandby

/**
 * Critically-damped spring matching AMUIPosterSwitcherSettings defaults:
 * horizontalScaleResponse / verticalScaleResponse = 0.35, damping ratio = 1.0.
 * A damping ratio of 1.0 has no overshoot, so the analytic solution is
 *   x(t) = 1 - (1 + omega*t) * exp(-omega*t),  omega = 2*pi / response.
 * iOS "response" is the natural period; the 1% settle is about 1.05 * response.
 */
object OriginalSwitcherSpring {
    const val RESPONSE = 0.35f
    const val DAMPING = 1.0f
    const val VERTICAL_EXITING_SCALE = 0.885f
    const val HORIZONTAL_EXITING_SCALE = 0.618f
    const val EXITING_OPACITY = 0f
    const val FORWARD_FADEOUT = 0.3f
    const val REVERSE_FADEOUT = 0.05f

    fun durationMs(): Long = (1100f * RESPONSE).toLong()

    /** t in 0..1 over [durationMs]; returns the 0..1 progress of the critically damped spring. */
    fun value(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val omega = (2.0 * Math.PI / RESPONSE).toFloat()
        val seconds = t * durationMs() / 1000f
        return 1f - (1f + omega * seconds) * Math.exp((-omega * seconds).toDouble()).toFloat()
    }
}
