package local.duostandby

import kotlin.math.min

/** Opt-in iPhone 27.2 Float scene adapter, calibrated from retained video at100.5s.
 * Mean scale from independently matched first/last 2 outlines, not assumed3x.
 * This is not a verified Duo host or Apple's private layout algorithm.
 * Point scale fits the measured SCENE, never the digits; no time-varying shrink.
 * The known source glyph/ink layout stays at400pt and overlap30pt. */
object OriginalPlayVideoReference {
    const val FRAME_WIDTH_PIXELS = 2622.0
    const val FRAME_HEIGHT_PIXELS = 1206.0
    const val MEASURED_PIXELS_PER_POINT = 2.9928236141311984
    val widthPoints:Double get()=FRAME_WIDTH_PIXELS/MEASURED_PIXELS_PER_POINT
    val heightPoints:Double get()=FRAME_HEIGHT_PIXELS/MEASURED_PIXELS_PER_POINT

    fun viewportIn(leftPixels:Double,topPixels:Double,widthPixels:Double,heightPixels:Double):OriginalPlaySceneViewport {
        require(listOf(leftPixels,topPixels,widthPixels,heightPixels).all(Double::isFinite))
        require(widthPixels>0 && heightPixels>0)
        val scale=min(widthPixels/widthPoints,heightPixels/heightPoints)
        val w=widthPoints*scale
        val h=heightPoints*scale
        return OriginalPlaySceneViewport(leftPixels+(widthPixels-w)/2,topPixels+(heightPixels-h)/2,
            w,h,scale,heightPoints)
    }
}
