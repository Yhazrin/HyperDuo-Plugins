package local.duostandby

import kotlin.math.min

/** PosterKit FaceEditorView 24A94425 geometry proposal, not measured Duo pixels.
 * W/H are the host GeometryProxy. Android supplies its View in logical points.
 * The injected host scale is not recovered: absent/invalid uses the original .5
 * fallback. P/Q include 12 points TOTAL; never enlarge the underlying scene.
 * Evidence: supplement-20261023-v40/INNER-PARENT-CHROME.md, a820..f628.
 */
object OriginalFaceEditorLayout {
    data class Frame(val x:Double,val y:Double,val width:Double,val height:Double)
    data class Proposal(val scale:Double,val preview:Frame,val optionsRegion:Frame,val optionsMaxHeight:Double)
    fun resolve(width:Double,height:Double,configuredScale:Double?,trailingInset:Double,rightToLeft:Boolean):Proposal {
        require(width>=0&&height>=0&&width.isFinite()&&height.isFinite())
        val scale=configuredScale?.takeIf{it>0&&it.isFinite()}?:.5
        val sw=scale*width;val sh=scale*height
        val p=sw+12.0
        val leading=(width+p)/2.0
        val trailing=trailingInset.takeIf{it.isFinite()}?.coerceAtLeast(0.0)?:0.0
        // SwiftUI wrappers: frame(maxWidth:R,maxHeight:sh+50,center), scale(center),
        // padding(trailing:T), padding(leading:L). Proposals flow in reverse order.
        val available=(width-leading-trailing).coerceAtLeast(0.0)
        return Proposal(scale,Frame((width-sw)/2,(height-sh)/2,sw,sh),
            Frame(if(rightToLeft)trailing else leading,0.0,available,height),
            min(height,(sh+50.0).coerceAtLeast(0.0)))
    }
}
