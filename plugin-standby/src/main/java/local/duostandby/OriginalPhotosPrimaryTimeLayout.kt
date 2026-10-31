package local.duostandby

/** iOS 27.1 / 24A94425 PhotosUIPrivate StandBy time-layout adapter.
 *
 * A user position (1/2/11) is not an asset-resolved layout. In particular, asking
 * the native provider for spec(1) or spec(2) traps. Never substitute the selector's
 * demonstration layouts 7/8/18 for the real asset result. All units here are
 * original points; the Canvas owner is responsible for its point/pixel mapping.
 *
 * Source: provider 0x1e33201bc, sublayouts 0x1e3320264, initial fallback
 * 0x1e3320334 + live init 0x1e33ada4c, scale 0x1e331ec24.
 */
object OriginalPhotosPrimaryTimeLayout {
    enum class Category(val raw: Int, val baselineHeight: Double) {
        WIDE_SCREEN(0, 402.0), HOME_BUTTON(1, 375.0), STANDARD(2, 466.0)
    }
    enum class LabelLayout(val raw: Int) {
        CENTERED(0), ALIGNED_LEFT(1), ALIGNED_RIGHT(2), ALIGNED_TO_EDGES(3),
        STACKED_ALIGNED_LEFT(4), STACKED_ALIGNED_RIGHT(5)
    }
    enum class HorizontalAlignment { LEADING, TRAILING }
    enum class Phase { ORIGINAL_INITIAL, ASSET_RESOLVED, ANDROID_NO_SEGMENTATION }
    /** One drawn label of a PosterLabelLayout. Native EDGES emits three labels:
     * hours / separator / minutes (builder 0x1e3446370: kind 1, kind 3, kind 2
     * in that order; payload hours@0x00, separator@0x40, Optional.none@0x30,
     * Optional.none@0x50, minutes@0x60). CENTERED / LEFT / RIGHT emit one full
     * label (0x1e3445ca0 / 0x1e34460fc). */
    enum class LabelSlot { FULL, HOURS, SEPARATOR, MINUTES }
    enum class LabelAnchor { LEADING, CENTER, TRAILING }
    data class LabelFrame(val slot: LabelSlot, val anchor: LabelAnchor, val text: String)
    data class FontSpec(val size: Double, val width: Double?, val weight: Double?,
                        val nativeName: String = ".SFSoftNumeric-Regular",
                        val centeredColons: Boolean = true) {
        fun scaled(factor: Double) = copy(size = size * factor)
    }
    data class Margins(val top: Double, val bottom: Double, val hoursTop: Double,
                       val minutesTop: Double, val leading: Double, val trailing: Double) {
        fun scaled(f: Double) = Margins(top*f,bottom*f,hoursTop*f,minutesTop*f,leading*f,trailing*f)
    }
    /** This is the native overlap-analysis rectangle, NOT a measured glyph frame. */
    data class AnalysisRect(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun scaled(f: Double) = AnalysisRect(x*f,y*f,width*f,height*f)
    }
    data class Spec(val actualRaw: Int, val category: Category, val font: FontSpec,
                    val margins: Margins, val labelLayout: LabelLayout,
                    val supportsForceCenteredLayout: Boolean, val isVertical: Boolean,
                    val anchoredAtBottom: Boolean,
                    val containerHorizontalAlignment: HorizontalAlignment?,
                    val usesDeviceScaledFonts: Boolean, val baselineHeight: Double,
                    val analysisRect: AnalysisRect) {
        fun effectiveLabelLayout(forceCentered: Boolean) =
            if (forceCentered && supportsForceCenteredLayout) LabelLayout.CENTERED else labelLayout

        /** Native scales font SIZE, margins and analysis rect, not width or weight. */
        fun scaledForScreenHeight(height: Double): Spec {
            require(height.isFinite() && height > 0.0)
            if (!usesDeviceScaledFonts || baselineHeight <= 0.0) return this
            val f = height / baselineHeight
            return copy(font = font.scaled(f), margins = margins.scaled(f),
                        analysisRect = analysisRect.scaled(f))
        }
    }
    /** The three keys prevent a late result for another asset/position/category
     * from moving the current clock. Asset identity is the existing stable key. */
    data class AssetResult(val assetIdentity: String, val requestedRaw: Int,
                           val category: Category, val actualRaw: Int)
    sealed interface Resolution {
        data object Hidden : Resolution
        data class Ready(val phase: Phase, val spec: Spec) : Resolution
        data class Pending(val reason: String, val candidateRaws: List<Int>) : Resolution
    }

    /** Exact original order. numberSystem is not read by this provider witness. */
    fun candidates(requestedRaw: Int, category: Category): List<Int> = when (requestedRaw) {
        1 -> if (category == Category.STANDARD) listOf(14,7,15,6,16) else listOf(14,7,12,13,19)
        2 -> listOf(9,8)
        11 -> if (category == Category.STANDARD) listOf(18,16,17) else listOf(18,16,17,19)
        else -> listOf(requestedRaw)
    }

    /** Original initialization, not an assertion that an analyzed photo uses it. */
    fun initialRaw(requestedRaw: Int): Int = when (requestedRaw) { 1 -> 5; 2 -> 8; else -> requestedRaw }

    fun resolve(requestedRaw: Int, category: Category, screenHeight: Double,
                assetIdentity: String?, assetResult: AssetResult?,
                useOriginalInitialState: Boolean = false): Resolution {
        require(requestedRaw in setOf(0,1,2,11))
        require(screenHeight.isFinite() && screenHeight > 0.0)
        if (requestedRaw == 0) return Resolution.Hidden
        val candidates = candidates(requestedRaw, category)
        val resultMatches = assetResult != null && assetIdentity != null &&
            assetResult.assetIdentity == assetIdentity && assetResult.requestedRaw == requestedRaw &&
            assetResult.category == category && assetResult.actualRaw in candidates
        val phase: Phase
        val raw: Int
        if (resultMatches) {
            phase = Phase.ASSET_RESOLVED
            raw = assetResult!!.actualRaw
        } else if (useOriginalInitialState) {
            phase = Phase.ORIGINAL_INITIAL
            raw = initialRaw(requestedRaw)
        } else return Resolution.Pending("ASSET_LAYOUT_REQUIRED", candidates)
        val spec = spec(raw, category)
            ?: return Resolution.Pending("SPEC_NOT_YET_PORTED:$raw:${category.raw}", candidates)
        return Resolution.Ready(phase, spec.scaledForScreenHeight(screenHeight))
    }

    /** Only decoded native branches are exposed. Unsupported is not a fabricated
     * equivalent, and standard values must not silently become wide/Duo values.
     * Jump table 0x1e35bbfaa: raw 5/11 0x1e3321320; 7 0x1e3321424; 6 0x1e3321514;
     * 14 0x1e33215d0; 15 0x1e3321690; 16 0x1e332194c; 8 0x1e33217b0; 9 0x1e33218b0;
     * 18 0x1e3321844; 19 aliases 18 in this STANDARD provider.
     * HorizontalDynamic candidates 14/7/15/6/16 are authored against spec+0x100=402
     * and scale only when spec+0x140 usesDeviceScaledFonts is set.
     */
    fun spec(actualRaw: Int, category: Category): Spec? {
        if (category != Category.STANDARD) return null
        val small = FontSpec(126.0,42.0,640.0)
        val vertical = FontSpec(225.0,47.0,640.0)
        val large = FontSpec(472.0,37.0,522.0)
        val medium = FontSpec(300.0,49.0,750.0)
        return when (actualRaw) {
            5,11,18,19 -> Spec(actualRaw,category,small,
                Margins(0.0,0.0,0.0,0.0,30.0,0.0), LabelLayout.ALIGNED_LEFT,
                false,false,true,null,true,466.0,AnalysisRect(30.0,348.0,180.0,89.0))
            8 -> Spec(actualRaw,category,vertical,
                Margins(0.0,0.0,12.0,185.0,0.0,60.0), LabelLayout.STACKED_ALIGNED_RIGHT,
                false,true,false,HorizontalAlignment.TRAILING,true,466.0,
                AnalysisRect(456.0,66.0,135.0,335.0))
            9 -> Spec(actualRaw,category,vertical,
                Margins(0.0,0.0,12.0,185.0,60.0,0.0), LabelLayout.STACKED_ALIGNED_LEFT,
                false,true,false,HorizontalAlignment.LEADING,true,466.0,
                AnalysisRect(66.0,66.0,156.0,335.0))
            14 -> Spec(14,category,large,
                Margins(-49.0,0.0,0.0,0.0,58.0,58.0), LabelLayout.ALIGNED_TO_EDGES,
                false,false,false,null,true,402.0,AnalysisRect(65.0,65.0,557.0,336.0))
            7 -> Spec(7,category,large,
                Margins(-49.0,0.0,0.0,0.0,0.0,0.0), LabelLayout.CENTERED,
                true,false,false,null,true,402.0,AnalysisRect(89.0,65.0,500.0,336.0))
            15 -> Spec(15,category,medium,
                Margins(-6.0,0.0,0.0,0.0,58.0,58.0), LabelLayout.ALIGNED_TO_EDGES,
                false,false,false,null,true,402.0,AnalysisRect(66.0,66.0,547.0,215.0))
            6 -> Spec(6,category,medium,
                Margins(-6.0,0.0,0.0,0.0,0.0,0.0), LabelLayout.CENTERED,
                false,false,false,null,true,402.0,AnalysisRect(100.0,66.0,480.0,215.0))
            16 -> Spec(16,category,small,
                Margins(-1.0,0.0,0.0,0.0,30.0,0.0), LabelLayout.ALIGNED_LEFT,
                false,false,false,null,false,402.0,AnalysisRect(30.0,30.0,180.0,89.0))
            else -> null
        }
    }

    /** RETRACTED 2026-10-24: the earlier raw-7 (tall/CENTERED) choice was an
     * Android adaptation, not a recovered Duo photo-face layout. The user's
     * reference shows a small corner time plus bottom location/date metadata,
     * and the native no-matte selection (0x1ea860838) is still unresolved.
     * Do not substitute any raw here until the Photos face request value
     * (1 / 2 / 11) and the corner layout (16/17/18/19) are closed from the
     * package. Returning null keeps the evidence-backed ORIGINAL_INITIAL (raw 5).
     */
    fun androidNoMatteRaw(requestedRaw: Int, category: Category): Int? = null

    /** Resolution used by the photo canvas: keeps the native initial state for
     * shapes without a decoded adaptation, otherwise applies the no-matte one. */
    fun resolveForPhoto(requestedRaw: Int, category: Category, screenHeight: Double): Resolution {
        val adapted = androidNoMatteRaw(requestedRaw, category)
            ?: return resolve(requestedRaw, category, screenHeight, null, null,
                useOriginalInitialState = true)
        val spec = spec(adapted, category)
            ?: return Resolution.Pending("SPEC_NOT_YET_PORTED:$adapted:${category.raw}",
                candidates(requestedRaw, category))
        return Resolution.Ready(Phase.ANDROID_NO_SEGMENTATION,
            spec.scaledForScreenHeight(screenHeight))
    }

    /** Native label composition for the effective PosterLabelLayout. */
    fun labelFrames(spec: Spec, forceCentered: Boolean): List<LabelFrame> {
        val layout = spec.effectiveLabelLayout(forceCentered)
        val full = "" // text is filled by the caller; slots identify the pieces
        return when (layout) {
            LabelLayout.CENTERED -> listOf(LabelFrame(LabelSlot.FULL, LabelAnchor.CENTER, full))
            LabelLayout.ALIGNED_LEFT -> listOf(LabelFrame(LabelSlot.FULL, LabelAnchor.LEADING, full))
            LabelLayout.ALIGNED_RIGHT -> listOf(LabelFrame(LabelSlot.FULL, LabelAnchor.TRAILING, full))
            LabelLayout.ALIGNED_TO_EDGES -> listOf(
                LabelFrame(LabelSlot.HOURS, LabelAnchor.LEADING, full),
                LabelFrame(LabelSlot.SEPARATOR, LabelAnchor.CENTER, full),
                LabelFrame(LabelSlot.MINUTES, LabelAnchor.TRAILING, full))
            LabelLayout.STACKED_ALIGNED_LEFT -> listOf(
                LabelFrame(LabelSlot.HOURS, LabelAnchor.LEADING, full),
                LabelFrame(LabelSlot.MINUTES, LabelAnchor.LEADING, full))
            LabelLayout.STACKED_ALIGNED_RIGHT -> listOf(
                LabelFrame(LabelSlot.HOURS, LabelAnchor.TRAILING, full),
                LabelFrame(LabelSlot.MINUTES, LabelAnchor.TRAILING, full))
        }
    }

    /** Structural plan from PhotosClockPosterTimeContentView 0x1e33853dc,
     * 0x1e338649c. Nil stack spacing is preserved as null, not guessed as 0/8.
     * Values are instructions to a layout adapter, not final glyph coordinates.
     */
    data class ContainerPlan(val leadingPadding: Double, val trailingPadding: Double,
                             val verticalPadding: Double?, val bottomPadding: Boolean,
                             val spacerBefore: Boolean, val spacerAfter: Boolean,
                             val horizontalSpacerBefore: Boolean,
                             val horizontalSpacerAfter: Boolean,
                             val nativeStackSpacing: Double? = null)
    fun containerPlan(spec: Spec) = ContainerPlan(
        spec.margins.leading, spec.margins.trailing,
        if (spec.isVertical) null else if (spec.anchoredAtBottom) spec.margins.bottom else spec.margins.top,
        spec.anchoredAtBottom, spec.anchoredAtBottom && !spec.isVertical, !spec.anchoredAtBottom,
        spec.isVertical && spec.containerHorizontalAlignment == HorizontalAlignment.TRAILING,
        spec.isVertical && spec.containerHorizontalAlignment == HorizontalAlignment.LEADING)
}
