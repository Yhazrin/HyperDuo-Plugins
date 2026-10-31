package local.duostandby

import kotlin.math.*

/** SBHWidgetStackViewController 24A94425, not AMUIPosterSwitcher presentation.
 * Host ownership: AmbientUI24644..246a8. Exact formulas/ranges in
 * references/gesture-port-v21-widget-source. Rendering inputs are logical points. */
object OriginalWidgetStackPaging {
    fun rest(height:Double)=height
    fun contentHeight(height:Double)=3.0*height
    fun center(relativeIndex:Int,height:Double)=height+height*(relativeIndex+.5)
    private fun roundAway(v:Double)=if(v>=0.0)floor(v+.5)else ceil(v-.5)
    fun newActive(active:Int,offset:Double,height:Double,count:Int):Int {
        if(count<=0)return 0
        val candidate=active+roundAway((offset-height)/height).toInt()
        // Literal source branch wraps overflow to first / underflow to last;
        // it is not modulo arbitrary multi-page jumps. Recenter bounds the range.
        return when {candidate>=count->0;candidate<0->count-1;else->candidate}
    }
    fun recenteredOffset(offset:Double,height:Double):Double {
        val remainder=offset%height // C fmod signed remainder, not positive modulo.
        return height+if(remainder>height*.5)remainder-height else remainder
    }
    fun scale(width:Double,height:Double,centerY:Double,offset:Double,trackingAppearance:Boolean):Double {
        val minimum=1.0-46.0/width
        val distance=abs(centerY-height*.5-offset)
        val base=(1.0-(46.0/width)*(distance/height)).coerceIn(minimum,1.0)
        return if(trackingAppearance)base*(1.0-6.0/width)else base
    }
    /** SBFFluidBehaviorSettings damping1 / response.35 are source values.
     * Analytic retarget/termination is an Android adapter, not UIKit CA identity. */
    class Scale(initial:Double=1.0){
        private var value=initial;private var velocity=0.0;private var target=initial;private var time:Long?=null
        var running=false;private set
        fun sample(next:Double,nanos:Long,animated:Boolean):Double{
            if(!animated){value=next;target=next;velocity=0.0;time=nanos;running=false;return value}
            time?.let{val dt=(nanos-it).coerceAtLeast(0L)/1e9;val w=2.0*PI/.35;val y=value-target;val a=velocity+w*y;val e=exp(-w*dt);value=target+(y+a*dt)*e;velocity=(velocity-w*a*dt)*e}
            time=nanos;target=next;running=abs(value-target)>1e-5||abs(velocity)>1e-4
            if(!running){value=target;velocity=0.0};return value
        }
    }
}
