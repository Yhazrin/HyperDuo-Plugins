package local.duostandby

import android.content.Context

/** The recovered interaction kernels run on Choreographer/Canvas, not Android
 * ValueAnimator. The user's explicit original-motion preference must not be
 * silently replaced by the device's animator-duration scale (observed 0.0).
 * No system setting is read or written. Reduce motion remains an app opt-in. */
object OriginalStandByMotionPolicy {
    private const val PREFERENCES = "original_motion_v22"
    private const val REDUCE_MOTION = "reduce_motion"
    fun animationsEnabled(context: Context): Boolean =
        !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(REDUCE_MOTION, false)
    fun setReduceMotion(context: Context, reduce: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(REDUCE_MOTION, reduce).apply()
    }
}
