package local.duostandby

/** SBHWidgetStackViewController normal UIPageControl, iOS 24A94425.
 * Model alpha is the UIView.alpha property used by _insetWidgetsForTrackingAppearance;
 * presentation alpha is separate. No fractional pill is invented for integer setCurrentPage.
 * Appearance rendering reuses SBH's response .35 / damping 1 analytic Android adapter.
 */
class OriginalWidgetPageControl {
    companion object {
        const val HIDE_DELAY_MILLIS = 800L
        const val INACTIVE_ALPHA = 0.45 // UIColor _pageControlIndicatorColor raw constant.
        // Physical session01 at 73.35 s, 2622x1206. Measured NORMAL profile,
        // not the editor profile and not the simulator's host bounds.
        const val MEASURED_TRAILING_OFFSET = 607.0f // content center -> control center
        const val MEASURED_PITCH = 34.5f
        const val MEASURED_RADIUS = 7.5f
    }
    var currentPage = 0; private set
    var numberOfPages = 0; private set
    var modelAlpha = 0.0; private set
    var presentationAlpha = 0.0; private set
    private var alpha = OriginalWidgetStackPaging.Scale(0.0)
    val running get() = alpha.running
    fun configure(count: Int, selected: Int) {
        numberOfPages = count.coerceAtLeast(0)
        currentPage = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
    }
    fun setCurrentPage(selected: Int) {
        currentPage = selected.coerceIn(0, (numberOfPages - 1).coerceAtLeast(0))
    }
    fun setHidden(hidden: Boolean, nowNanos: Long, animated: Boolean) {
        modelAlpha = if (hidden) 0.0 else 1.0
        sample(nowNanos, animated)
    }
    fun sample(nowNanos: Long, animated: Boolean): Double {
        presentationAlpha = alpha.sample(modelAlpha, nowNanos, animated).coerceIn(0.0, 1.0)
        return presentationAlpha
    }
    fun trackingAppearance(isTracking: Boolean) = numberOfPages > 1 && isTracking && modelAlpha > 0.0
    fun reset() {
        modelAlpha = 0.0; presentationAlpha = 0.0
        alpha = OriginalWidgetStackPaging.Scale(0.0)
    }
}
