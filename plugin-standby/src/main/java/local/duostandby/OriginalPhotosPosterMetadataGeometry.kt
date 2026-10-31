package local.duostandby

/** PhotosClockPoster branch, iOS 27.1 / 24A94425. All coordinates are original logical points.
 * Host routing is deliberately outside this model: a Duo gallery-owned tap must not be forwarded.
 * Main time-label geometry is NOT reconstructed here. No ordinary 32/18 or 2/3-width fallback.
 */
internal object OriginalPhotosPosterMetadataGeometry {
    enum class Category(val raw: Int) { WIDE_SCREEN(0), HOME_BUTTON(1), STANDARD(2) }
    enum class HorizontalAlignment { LEADING, TRAILING }
    enum class TapRegion { CENTER, LEADING, TRAILING, TOP, BOTTOM }
    data class Point(val x: Double, val y: Double)
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val center: Point get() = Point((left + right) / 2.0, (top + bottom) / 2.0)
        // Half-open boundaries are the Android adapter convention, not recovered SwiftUI hit-test ABI.
        fun contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom
    }
    data class Style(
        val alignment: HorizontalAlignment,
        val titleSize: Double, val subtitleSize: Double,
        val fontWeight: Int, val usesRoundedFont: Boolean,
        val elementSpacing: Double,
        val top: Double, val leading: Double, val bottom: Double, val trailing: Double,
        val actionsSide: Double
    )
    data class MetadataLayout(
        val style: Style,
        // Includes the flexible first Spacer; scale belongs to this entire frame, before padding.
        val contentFrame: Rect,
        // Intrinsic title+subtitle block is measured by the host with the source fonts/spacing.
        val textBlockFrame: Rect,
        val textAnchorX: Double,
        val scalePivot: Point
    )
    data class ActionsLayout(val contentFrame: Rect, val scalePivot: Point, val bottomRowPadding: Double)
    data class TapRegions(val poster: Rect, val leading: Rect, val trailing: Rect, val top: Rect, val bottom: Rect) {
        fun regionAt(x: Double, y: Double): TapRegion? {
            if (!poster.contains(x, y)) return null
            return when {
                leading.contains(x, y) -> TapRegion.LEADING
                trailing.contains(x, y) -> TapRegion.TRAILING
                top.contains(x, y) -> TapRegion.TOP
                bottom.contains(x, y) -> TapRegion.BOTTOM
                else -> TapRegion.CENTER // Clear full-poster onTap layer behind the edge scaffold.
            }
        }
    }

    // 0x1e32ddad4 layout init, 0x1e35b7490..74d0 constants; actions table 0x1e35d3e68.
    fun style(category: Category): Style {
        val standard = category == Category.STANDARD
        return Style(
            if (standard) HorizontalAlignment.LEADING else HorizontalAlignment.TRAILING,
            33.0, 24.0, 600, false, if (standard) 1.0 else 5.0,
            60.0, if (standard) 45.0 else 60.0, if (standard) 40.0 else 60.0,
            if (standard) 48.0 else 63.0,
            when (category) { Category.WIDE_SCREEN -> 49.0; Category.HOME_BUTTON -> 32.0; Category.STANDARD -> 30.0 }
        )
    }

    /** 0x1e32ddb60: VStack(Spacer(), title, subtitle), frame(maxWidth: infinity), then blur/scale.
     * 0x1e33ae858: outer padding; 0x1e33b1a10: outer ZStack(center).
     * Null means the proposed size compresses the source stack; that case has not been recovered.
     * textBlockHeight includes actual line metrics and the single title/subtitle elementSpacing;
     * it is not titleSize+subtitleSize and contains no invented baseline offset.
     */
    fun metadata(category: Category, width: Double, height: Double, textBlockHeight: Double, rtl: Boolean): MetadataLayout? {
        val s = style(category)
        if (!width.isFinite() || !height.isFinite() || !textBlockHeight.isFinite() || textBlockHeight < 0.0 ||
            width < s.leading + s.trailing || height < s.top + s.bottom + textBlockHeight) return null
        val left = if (rtl) s.trailing else s.leading
        val right = width - if (rtl) s.leading else s.trailing
        val frame = Rect(left, s.top, right, height - s.bottom)
        val alignLeft = (s.alignment == HorizontalAlignment.LEADING) != rtl
        return MetadataLayout(s, frame, Rect(left, frame.bottom - textBlockHeight, right, frame.bottom),
            if (alignLeft) frame.left else frame.right, frame.center)
    }

    /** Actions ZStack is a separate sibling, not a container for metadata. Its padding precedes
     * opacity/blur/center-scale, so the scale pivot is the full padded poster, unlike metadata.
     * The bottom action VStack separately has .padding(.bottom, 32) at 0x1e33b12dc.
     */
    fun actions(category: Category, width: Double, height: Double): ActionsLayout? {
        val side = style(category).actionsSide
        if (!width.isFinite() || !height.isFinite() || width < side * 2 || height < 40.0) return null
        return ActionsLayout(Rect(side, 40.0, width - side, height), Point(width / 2.0, height / 2.0), 32.0)
    }

    /** Native HStack: 100-wide leading edge, VStack(top 100, Spacer, bottom 100), trailing 100.
     * Both stack spacings are nil in source; the resolved HStack gap must come from the host,
     * never an invented 0/8-point default. VStack spacing only consumes the flexible center.
     * Native compressed proposals and outer Duo event ownership are intentionally not guessed.
     */
    fun tapRegions(width: Double, height: Double, resolvedHorizontalGap: Double, rtl: Boolean): TapRegions? {
        if (!width.isFinite() || !height.isFinite() || !resolvedHorizontalGap.isFinite() || resolvedHorizontalGap < 0.0 ||
            width <= 200.0 + 2.0 * resolvedHorizontalGap || height <= 200.0) return null
        val left = Rect(0.0, 0.0, 100.0, height)
        val right = Rect(width - 100.0, 0.0, width, height)
        val middleLeft = 100.0 + resolvedHorizontalGap
        val middleRight = width - middleLeft
        return TapRegions(Rect(0.0, 0.0, width, height), if (rtl) right else left, if (rtl) left else right,
            Rect(middleLeft, 0.0, middleRight, 100.0), Rect(middleLeft, height - 100.0, middleRight, height))
    }
}
