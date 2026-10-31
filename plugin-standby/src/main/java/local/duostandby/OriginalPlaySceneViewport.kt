package local.duostandby

/** Explicit, externally measured scene mapping; no native viewport is guessed.
 * Android density is NOT the iOS UIScreen scale. Uniform pixel/point conversion
 * preserves source geometry; glyphs are deliberately not fitted to the viewport.
 * This is an Android host adapter, not a recovered Apple scaling modifier. */
data class OriginalPlaySceneViewport(
    val leftPixels:Double,
    val topPixels:Double,
    val widthPixels:Double,
    val heightPixels:Double,
    val pixelsPerPoint:Double,
    val sourceWindowHeightPoints:Double
) {
    init {
        require(listOf(leftPixels,topPixels,widthPixels,heightPixels,pixelsPerPoint,
            sourceWindowHeightPoints).all(Double::isFinite))
        require(widthPixels>0 && heightPixels>0 && pixelsPerPoint>0 && sourceWindowHeightPoints>0)
    }
    val widthPoints:Double get()=widthPixels/pixelsPerPoint
    val heightPoints:Double get()=heightPixels/pixelsPerPoint
}
