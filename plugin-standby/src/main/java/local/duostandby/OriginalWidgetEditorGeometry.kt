package local.duostandby

import kotlin.math.min

/** Recovered SBH square/small-stack aperture constraints (24A94425).
 * Values in reference points come from AMUIInfographIconListLayout on the
 * 402 x 874 iPhone 17 Pro Simulator, not Android dp or video pixel guesses.
 * This is an explicit source-device profile, not a claim of Duo native sizing.
 */
object OriginalWidgetEditorGeometry {
    const val REFERENCE_WIDTH = 874f
    const val REFERENCE_HEIGHT = 402f
    const val ICON_DIMENSION = 362f
    const val ICON_CORNER_RADIUS = 61.54f
    // AMUIInfographViewController ...iconContentScaleForGridSizeClass: @1f228.
    const val EDITOR_CONTENT_SCALE = 0.5f
    // SBH _setupApertureViewInContainer @1f847228c; raw @1f87179c0.
    const val APERTURE_FRACTION = 0.744f
    fun referencePointScale(width:Float,height:Float)=
        min(width/REFERENCE_WIDTH,height/REFERENCE_HEIGHT)
    fun apertureSide(width:Float,height:Float)=min(width,height)*APERTURE_FRACTION
    fun cardSide(width:Float,height:Float)=
        ICON_DIMENSION*EDITOR_CONTENT_SCALE*referencePointScale(width,height)
    // Small-stack expanded branch: SBH ApertureView.layoutSubviews @1f850e69c.
    fun expandedCornerRadius(width:Float,height:Float)=
        (ICON_CORNER_RADIUS/ICON_DIMENSION)*min(width,height)
    // Collapsed endpoint uses concentric corners: @1f850e674..680.
    fun concentricCornerRadius(apertureWidth:Float,iconWidth:Float,iconRadius:Float,contentScale:Float)=
        iconRadius+(apertureWidth-iconWidth*contentScale)*0.5f
}
