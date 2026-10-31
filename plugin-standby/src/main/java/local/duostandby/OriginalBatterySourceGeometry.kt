package local.duostandby

import android.graphics.RectF
import kotlin.math.min
import kotlin.math.round

/** Literal 24A94425 BatteryCenterUI UIKit branch. Deliberately separate from
 * the 27.2 recorded SwiftUI widget and its measured presentation profile. */
object OriginalBatterySourceGeometry {
    const val OUTER_INSET = 12f // 0x1fe237f38..f50
    const val GAP = 11f // 0x1fe237f74, 0x1fe23822c
    const val STROKE_WIDTH = 6f // 0x1fe2382e0
    const val MAX_DEVICES = 4 // 0x1fe238284
    const val GLYPH_POINT_SIZE = 24f // 0x1fe23c5f8, multiplied by ringScale and scaleFactor
    const val BOLT_SIZE = 15f // 0x1fe23c46c, multiplied by ringScale and scaleFactor
    fun includesEmptyDevices(deviceCount: Int) = deviceCount > 1 // 0x1fe2382a8
    fun radius(diameter: Float, strokeWidth: Float, displayScale: Float): Float {
        require(displayScale > 0f)
        return round((diameter-strokeWidth)*.5f*displayScale)/displayScale
    }
    /** Frames of BCUIRingItemView, not necessarily the charge ring's final frame.
     * Single-device branch uses style raw2 and an additional internal half-width
     * ring + charge label layout; do not render that branch as a giant full ring. */
    fun itemFrames(bounds: RectF, deviceCount: Int, rtl: Boolean = false): List<RectF> {
        if(deviceCount <= 0) return emptyList()
        val b=RectF(bounds);b.inset(OUTER_INSET,OUTER_INSET)
        if(deviceCount == 1) return listOf(b)
        val side=min((b.width()-GAP)*.5f,(b.height()-GAP)*.5f).coerceAtLeast(0f)
        return List(MAX_DEVICES) { i ->
            val col=i%2
            val x=if(rtl) b.right-side-col*(side+GAP) else b.left+col*(side+GAP)
            val y=b.top+(i/2)*(side+GAP)
            RectF(x,y,x+side,y+side)
        }
    }
}
