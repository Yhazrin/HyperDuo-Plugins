package local.duostandby

import kotlin.math.abs
import kotlin.math.exp

/** c8d1c..c8d48: interpolatingSpring(mass:2, stiffness:300, damping:50,
 * initialVelocity:0), keyed by style. Exact overdamped solution, roots -10/-15.
 * Continuity on interruption and termination tolerance are Android adapter
 * policies; native SwiftUI transaction equivalence is not yet demonstrated.
 */
class OriginalCalendarPositionSpring(initialPosition:Double) {
    data class Sample(val position:Double,val velocity:Double,val target:Double,val running:Boolean)
    private var position=initialPosition
    private var velocity=0.0
    private var target=initialPosition
    private var lastTime:Long?=null
    init { require(initialPosition.isFinite()) }
    fun sample(targetPosition:Double,nowNanos:Long):Sample {
        require(targetPosition.isFinite())
        val previous=lastTime
        require(previous==null || nowNanos>=previous){"Monotonic frame time required"}
        if(previous!=null){
            val dt=(nowNanos-previous)/1e9
            val y=position-target
            val c1=(velocity+15.0*y)/5.0;val c2=y-c1
            val e1=exp(-10.0*dt);val e2=exp(-15.0*dt)
            position=target+c1*e1+c2*e2
            velocity=-10.0*c1*e1-15.0*c2*e2
        }
        lastTime=nowNanos
        // Retarget only after advancing the old trajectory. Never reset velocity.
        target=targetPosition
        val running=abs(position-target)>=.00001 || abs(velocity)>=.0001
        if(!running){position=target;velocity=0.0}
        return Sample(position,velocity,target,running)
    }
    /** A non-animated host/layout reset is explicit, never inferred by fitting. */
    fun snapTo(position:Double,nowNanos:Long){
        require(position.isFinite());this.position=position;target=position;velocity=0.0;lastTime=nowNanos
    }
}
