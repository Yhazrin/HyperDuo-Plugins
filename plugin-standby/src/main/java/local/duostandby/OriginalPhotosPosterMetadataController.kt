package local.duostandby

/** State/transaction adapter for PhotosClockPosterView, not a clock-layout reconstruction.
 * Source: 24A94425 PhotosUIPrivate show 0x1e33b77ec, hide 0x1e33b454c,
 * center 0x1e33b04ec, horizontal edge 0x1e33b90c8, vertical edge 0x1e33b93d8.
 * Call on the UI thread. All times are monotonic animation commit/frame ticks.
 */
class OriginalPhotosPosterMetadataController {
    data class Gate(
        val assetAvailable: Boolean = false,
        val renderingForIdle: Boolean = false,
        val renderingForPreview: Boolean = false,
        val renderingForSnapshot: Boolean = false,
        val renderingForEditorPreview: Boolean = false,
        val displayStyleRaw: Int = 0,
        val isAnimatingTimeLabel: Boolean = false,
        val showingDoNotFeatureAlert: Boolean = false,
    )
    enum class TapRegion { CENTER, LEADING, TRAILING, TOP, BOTTOM }
    data class TapResult(val consumed: Boolean, val photoStep: Int = 0, val stateChanged: Boolean = false)
    data class Frame(
        val clockOpacity: Double,
        val clockMetadataScale: Double,
        val actionsOpacity: Double,
        val actionsScale: Double,
        val actionsBlurPoints: Double,
        val gradientOpacity: Double,
        val metadata: OriginalPhotosPosterMetadataMotion.Sample,
        val isShowing: Boolean,
        val isToggling: Boolean,
    )
    private enum class Completion { SHOW, HIDE }
    private val metadataMotion = OriginalPhotosPosterMetadataMotion()
    private val clockOpacity = OriginalPhotosPosterMetadataMotion.Channel(1.0)
    private val clockScale = OriginalPhotosPosterMetadataMotion.Channel(1.0)
    private val actionsOpacity = OriginalPhotosPosterMetadataMotion.Channel(0.0)
    private val actionsScale = OriginalPhotosPosterMetadataMotion.Channel(1.03)
    private val actionsBlur = OriginalPhotosPosterMetadataMotion.Channel(6.0)
    private val gradient = OriginalPhotosPosterMetadataMotion.Channel(0.0)
    private var completion: Completion? = null
    private var deadlineDispatched = false
    var isShowingMetadata = false
        private set
    var isTogglingMetadata = false
        private set
    private var deadlineToken: Long? = null
    /** A host wake-up is needed only until this dispatch has been delivered. */
    val nextAutoHideDeadlineNanos: Long?
        get() = if (deadlineDispatched) null else deadlineToken

    private fun nonInteractive(gate: Gate): Boolean = gate.renderingForIdle ||
        gate.renderingForPreview || gate.renderingForSnapshot || gate.renderingForEditorPreview

    /** Host resolves geometry into the original center / 100-point edge regions.
     * consumed is the Android adapter's routing result, not an original Swift return value.
     * A displayed metadata edge never also requests another photo, even if hide is gated.
     */
    fun tap(region: TapRegion, nowNanos: Long, gate: Gate): TapResult {
        if (nonInteractive(gate)) return TapResult(false)
        if (region == TapRegion.CENTER && (gate.displayStyleRaw == 1 ||
                isTogglingMetadata || gate.isAnimatingTimeLabel)) return TapResult(true)
        if (isShowingMetadata) return TapResult(true, stateChanged = hide(nowNanos, gate))
        return when (region) {
            TapRegion.LEADING -> TapResult(true, photoStep = -1)
            TapRegion.TRAILING -> TapResult(true, photoStep = 1)
            else -> TapResult(true, stateChanged = show(nowNanos, gate))
        }
    }

    fun show(nowNanos: Long, gate: Gate): Boolean {
        if (nonInteractive(gate) || gate.isAnimatingTimeLabel || isShowingMetadata || !gate.assetAvailable) return false
        isTogglingMetadata = true
        isShowingMetadata = true
        // Model onChange drives its own metadata text channels, separate from actions.
        metadataMotion.setShowing(true, nowNanos)
        // Transaction 1: clock label disappears using the fast, undelayed spring.
        clockOpacity.move(0.0, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, true)
        clockScale.move(0.96, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, true)
        // Transaction 2: only actions scale, delayed and underdamped.
        actionsScale.move(1.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_SCALE, true)
        // Transaction 3: actions/gradient content; only this owns show completion.
        actionsOpacity.move(1.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_CONTENT, true)
        actionsBlur.move(0.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_CONTENT, true)
        gradient.move(1.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_CONTENT, true)
        completion = Completion.SHOW
        return true
    }

    /** The source hide is TWO transactions, not show's three transactions in reverse. */
    fun hide(nowNanos: Long, gate: Gate, animated: Boolean = true): Boolean {
        if (isTogglingMetadata || gate.showingDoNotFeatureAlert || !isShowingMetadata) return false
        isShowingMetadata = false
        metadataMotion.setShowing(false, nowNanos, animated)
        // First: all four actions/gradient fields use the fast, undelayed spring.
        actionsOpacity.move(0.0, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, animated)
        actionsScale.move(1.03, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, animated)
        actionsBlur.move(6.0, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, animated)
        gradient.move(0.0, nowNanos, OriginalPhotosPosterMetadataMotion.HIDE, animated)
        // Second: restore clock opacity/metadataScale with delayed slow content spring.
        clockOpacity.move(1.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_CONTENT, animated)
        clockScale.move(1.0, nowNanos, OriginalPhotosPosterMetadataMotion.SHOW_CONTENT, animated)
        isTogglingMetadata = animated
        completion = if (animated) Completion.HIDE else null
        return true
    }

    /** Explicitly equivalent to scheduleAutoHideMetadata. Never called directly by tap. */
    fun scheduleAutoHide(nowNanos: Long): Long {
        val token = metadataMotion.scheduleAutoHide(nowNanos)
        deadlineToken = token
        deadlineDispatched = false
        return token
    }

    /** Old deadlines cannot hide a rescheduled episode; one dispatch is attempted once.
     * Source hide can refuse while toggling/an alert is active; no invented per-frame retry.
     */
    fun fireAutoHide(token: Long, nowNanos: Long, gate: Gate): Boolean {
        if (token != deadlineToken || nowNanos < token || deadlineDispatched) return false
        deadlineDispatched = true
        return hide(nowNanos, gate)
    }

    fun advance(nowNanos: Long, gate: Gate): Boolean {
        var changed = false
        when (completion) {
            Completion.SHOW -> if (actionsOpacity.logicallyComplete(nowNanos) &&
                    actionsBlur.logicallyComplete(nowNanos) && gradient.logicallyComplete(nowNanos)) {
                // Exact completion ordering: schedule first, then unlock. Not visual settling.
                scheduleAutoHide(nowNanos)
                isTogglingMetadata = false
                completion = null
                changed = true
            }
            Completion.HIDE -> if (clockOpacity.logicallyComplete(nowNanos) && clockScale.logicallyComplete(nowNanos)) {
                // 0x1e33b7e2c clears the guard only: hide never schedules a new deadline.
                isTogglingMetadata = false
                completion = null
                changed = true
            }
            null -> Unit
        }
        nextAutoHideDeadlineNanos?.let { token ->
            if (!deadlineDispatched && nowNanos >= token) changed = fireAutoHide(token, nowNanos, gate) || changed
        }
        return changed || needsFrame(nowNanos)
    }

    fun sample(nowNanos: Long): Frame = Frame(
        clockOpacity.sample(nowNanos).position.coerceIn(0.0, 1.0),
        clockScale.sample(nowNanos).position,
        actionsOpacity.sample(nowNanos).position.coerceIn(0.0, 1.0),
        actionsScale.sample(nowNanos).position,
        actionsBlur.sample(nowNanos).position.coerceAtLeast(0.0),
        gradient.sample(nowNanos).position.coerceIn(0.0, 1.0),
        metadataMotion.sample(nowNanos), isShowingMetadata, isTogglingMetadata,
    )

    fun needsFrame(nowNanos: Long): Boolean = completion != null || metadataMotion.needsFrame(nowNanos) ||
        clockOpacity.active(nowNanos) || clockScale.active(nowNanos) || actionsOpacity.active(nowNanos) ||
        actionsScale.active(nowNanos) || actionsBlur.active(nowNanos) || gradient.active(nowNanos)
}
