package local.duostandby

import android.graphics.Color

/**
 * Positions are in the 960 x 600 analysis plane, NOT an iPhone pixel resolution.
 * Source: Apple official standby-1.mp4 frame 180, manually rectified.
 * All colors and system-font substitutions remain estimated, not Apple assets.
 */
object VisualTokens {
    const val WIDTH=960f
    const val HEIGHT=600f
    const val LARGE_DATE_X=393f
    const val LARGE_DATE_Y=145f
    const val LARGE_DATE_WIDTH=196f
    const val LARGE_DATE_HEIGHT=387f
    const val COLUMN_X=130f
    const val COLUMN_STEP=116.7f
    const val FIRST_ROW_CENTER=205f
    const val ROW_STEP=69f
    const val GRID_SIZE=44f
    const val TITLE_SIZE=43f
    const val SELECTED_RADIUS=38f
    val BACKGROUND=Color.rgb(250,248,244)
    val RED=Color.rgb(224,43,47)
    val BLUE=Color.rgb(0,88,147)
    val MUTED=Color.rgb(174,194,208)
    val SELECTED=Color.rgb(0,25,50)
    val GREEN=Color.rgb(0,149,88)
    val INK=Color.rgb(0,21,20)
    val YELLOW=Color.rgb(248,192,66)
    val NIGHT_RED=Color.rgb(174,18,22)
}
