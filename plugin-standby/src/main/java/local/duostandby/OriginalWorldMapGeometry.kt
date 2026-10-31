package local.duostandby

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** ClockPoster24A94425 WorldMap fit0xb7c6c; initializer0x20198 supplies margins.
 * No freely chosen card, padding, uniform image-fit or inferred screen aspect.
 * Caller must identify its input coordinate space. Android window is an adapter,
 * not evidence of original Duo UIScreen bounds or parent scene geometry.
 */
object OriginalWorldMapGeometry {
    data class Frame(val x:Double,val y:Double,val width:Double,val height:Double,val mode:Int)
    fun fit(width:Double,height:Double,displayScale:Double):Frame {
        require(width.isFinite()&&height.isFinite()&&displayScale>0&&displayScale.isFinite())
        val top=40.666;val left=95.0;val bottom=44.667;val right=41.0
        val landTop=-0.333;val landLeft=9.333;val landBottom=61.666;val landRight=11.666
        val availableWidth=width-left-right;val availableHeight=height-top-bottom
        val landWidth=737.0-(landLeft+landRight);val landHeight=369.0-(landTop+landBottom)
        val ratio=min(availableWidth/landWidth,availableHeight/landHeight)
        var x=left;var y=top;var w=737.0;var h=369.0
        val resize=abs(ratio-1.0)>0.05
        if(resize){w*=ratio;h*=ratio;x+=(availableWidth-landWidth*ratio)*0.5;y+=(availableHeight-landHeight*ratio)*0.5}
        // Final source subtraction is UNscaled land inset, including resize branch.
        x-=landLeft;y-=landTop
        // UIKit0x1c43e80e0: epsilon prevents outward expansion for sub-1e-4
        // floating noise near integral physical edges. Scale is explicit, never0.
        fun minimum(v:Double):Double {val z=v*displayScale;val c=ceil(z);return (if(c-z<0.0001)c else floor(z))/displayScale}
        fun maximum(v:Double):Double {val z=v*displayScale;val f=floor(z);return (if(z-f<0.0001)f else ceil(z))/displayScale}
        val x0=minimum(x);val y0=minimum(y);val x1=maximum(x+w);val y1=maximum(y+h)
        return Frame(x0,y0,x1-x0,y1-y0,if(resize)1 else 4)
    }
}
