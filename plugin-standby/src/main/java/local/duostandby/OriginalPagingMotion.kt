package local.duostandby

import kotlin.math.*

/** UIKit 24A94425 ordinary, non-nudging paging branch (both axes share coefficients).
 * init1c4e44338 / prepare1c4e5ad74 / numerical engine1c4e589d0.
 * NOT the poster presentation spring or the circular0.25s transition.
 * Android touch velocity/bounds/density are explicit input adapters. Interrupted
 * extra-page preparation and original drag rubber-banding remain separate.
 */
object OriginalPagingMotion {
    data class Coefficients(val pull:Double,val friction:Double)
    fun coefficients(boundsWidth:Double):Coefficients {
        val w=min(boundsWidth,1024.0)
        // Separate multiply/add, matching source (not fused).
        return Coefficients(w*Double.fromBits(0xbe88c8d68a811ef1uL.toLong())+Double.fromBits(0x3f37888bb5f95f35L),
            w*Double.fromBits(0x3ee4da3480d67ab4L)+Double.fromBits(0x3feef02caf02caf2L))
    }
    /** Content-offset velocity in points/ms, NOT finger pixels/sec. */
    fun target(position:Double,velocity:Double,pageWidth:Double,lastIndex:Int):Int {
        require(pageWidth>0.0)
        val p=position/pageWidth
        val selected=when {velocity>.3->ceil(p);velocity<-.3->floor(p);else->floor(p+.5)}
        return selected.toInt().coerceIn(0,lastIndex)
    }
    fun programmaticProgress(fraction:Float):Float {
        // UIAnimation.progressForFraction curve0,1c3c88cdc. Separate from drag release.
        val s=sin(fraction.toDouble()*Double.fromBits(0x3ff921fb54442d18L)).toFloat();return s*s
    }
    /** UIScrollView 24A94425 __UIScrollViewRubberBandOffsetWithoutDecorationForOffset
     * 1c4e60d50..dd8; default coefficient branch1c4e60f08..38=.55.
     * Zero insets/no decorations. Original per-input-type/runtime overrides and
     * reversal acceleration are NOT established by this default profile. */
    fun rubberBand(offset:Double,minOffset:Double,maxOffset:Double,range:Double,coefficient:Double=.55):Double {
        if(abs(range)<Double.fromBits(0x3cb0000000000000L))return offset
        val hi=max(minOffset,maxOffset)
        return when {
            offset>hi -> hi+range*(1.0-1.0/(1.0+coefficient*((offset-hi)/range)))
            offset<minOffset -> minOffset-range*(1.0-1.0/(1.0+((minOffset-offset)/range)*coefficient))
            else -> offset
        }
    }
    /** Algebraic inverse for continuous Android re-grab of an overscrolled page;
     * not a claim to port UIKit's velocity-aware rubberBandingReversalOffset. */
    fun unRubberBand(offset:Double,minOffset:Double,maxOffset:Double,range:Double,coefficient:Double=.55):Double {
        val edge=when{offset<minOffset->minOffset;offset>maxOffset->maxOffset;else->return offset}
        val distance=abs(offset-edge).coerceAtMost(range*(1.0-1e-9))
        return edge+sign(offset-edge)*range*(1.0/(1.0-distance/range)-1.0)/coefficient
    }
    private fun roundAway(x:Double)=if(x>=0.0)floor(x+.5)else ceil(x-.5)
    fun displayRound(x:Double,scale:Double):Double {
        if(scale==1.0)return roundAway(x)
        val whole=floor(x);return whole+roundAway((x-whole)*scale)/scale
    }
    class Release(boundsWidth:Double,initialPosition:Double,initialVelocity:Double,
                  val destination:Double,private val displayScale:Double) {
        private val coefficients=coefficients(boundsWidth)
        private val direction=initialVelocity.compareTo(0.0)
        var position=initialPosition;private set
        var velocity=initialVelocity;private set
        var running=true;private set
        fun advance(milliseconds:Double):Double {
            val steps=floor(milliseconds).toInt()
            if(!running||steps<1)return position
            val old=position;var lastDistance=0.0
            repeat(steps){
                lastDistance=destination-position
                velocity=(velocity+lastDistance*coefficients.pull)*coefficients.friction
                position+=velocity
            }
            var rendered=displayRound(position,displayScale)
            // Source anti-stall branch58abc..b24 deliberately uses signed v<.5.
            if(rendered!=destination&&rendered==old&&velocity<.5){
                rendered=displayRound(rendered+(if(lastDistance<0.0)-1.0 else 1.0)/displayScale,displayScale)
            }
            val close=abs(velocity)<.01&&abs(lastDistance)<=1.0&&abs(rendered-destination)<5.0
            val crossed=when {direction>0->old<=destination&&rendered>destination
                direction<0->old>=destination&&rendered<destination;else->false}
            if(close||(crossed&&abs(velocity)<.1)){position=destination;velocity=0.0;running=false}
            else position=rendered
            return position
        }
    }
}
