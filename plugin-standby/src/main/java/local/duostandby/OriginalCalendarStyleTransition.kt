package local.duostandby

import kotlin.math.*

/** Reconstructed CalendarPoster 24A94425 StyleTransition (0x2162b8d28).
 * Three channels, NOT a shared duration. Native parameters; Android sampling adapter.
 * SwiftUI interruption/transaction parity is not established by this adapter.
 */
class OriginalCalendarStyleTransition(initialActive: Boolean, private val hiddenBlurRadius:Double=10.0) {
    init { require(hiddenBlurRadius.isFinite() && hiddenBlurRadius>=0.0) }
    data class Sample(val opacity:Float, val blur:Float, val scale:Float, val running:Boolean)
    private class Channel(var value:Double) {
        var velocity=0.0
        var from=value; var target=value; var started=0L; var last=0L
        var delay=0.0; var damping:Double?=null
        fun retarget(to:Double, now:Long, springDamping:Double?=null, wait:Double=0.0) {
            step(now);from=value;target=to;started=now;last=now;delay=wait;damping=springDamping
            if(springDamping==null)velocity=0.0
        }
        fun step(now:Long) {
            if(started==0L)return
            val d=damping
            if(d==null) {
                val t=((now-started)/1e9-delay)/.15
                value=from+(target-from)*curve(t.coerceIn(0.0,1.0))
                if(t>=1.0){value=target;started=0L}
            }else {
                val dt=((now-last)/1e9).coerceAtLeast(0.0);last=now
                // Exact m=2,k=300 solution: c=30 underdamped; c=50 OVERDAMPED.
                val a=d/4.0;val y=value-target;val v=velocity
                if(a*a<150.0){
                    val w=sqrt(150.0-a*a);val e=exp(-a*dt);val co=cos(w*dt);val si=sin(w*dt)
                    value=target+e*(y*co+(v+a*y)/w*si)
                    velocity=e*(v*co-(a*v+150.0*y)/w*si)
                }else{
                    val h=sqrt(a*a-150.0);val r1=-a+h;val r2=-a-h
                    val c1=(v-r2*y)/(r1-r2);val c2=y-c1
                    val e1=exp(r1*dt);val e2=exp(r2*dt)
                    value=target+c1*e1+c2*e2;velocity=r1*c1*e1+r2*c2*e2
                }
                // Adapter stopping tolerance, NOT an extracted Apple parameter.
                if(abs(value-target)<.00001&&abs(velocity)<.0001){value=target;velocity=0.0;started=0L}
            }
        }
        val running get()=started!=0L
    }
    private var active=initialActive
    private val opacity=Channel(if(active)1.0 else 0.0)
    private val blur=Channel(if(active)0.0 else hiddenBlurRadius)
    private val scale=Channel(if(active)1.0 else .95)
    fun sample(isActive:Boolean,nowNanos:Long):Sample {
        if(active!=isActive) {
            opacity.retarget(if(isActive)1.0 else 0.0,nowNanos,wait=if(isActive).1 else 0.0)
            blur.retarget(if(isActive)0.0 else hiddenBlurRadius,nowNanos,if(isActive)50.0 else null)
            scale.retarget(if(isActive)1.0 else .95,nowNanos,if(isActive)30.0 else 50.0)
            active=isActive
        }
        opacity.step(nowNanos);blur.step(nowNanos);scale.step(nowNanos)
        return Sample(opacity.value.toFloat(),blur.value.toFloat(),scale.value.toFloat(),opacity.running||blur.running||scale.running)
    }
    companion object {
        fun settled(active:Boolean)=Sample(if(active)1f else 0f,if(active)0f else 10f,if(active)1f else .95f,false)
        private fun curve(x:Double):Double {
            if(x<=0.0)return 0.0
            if(x>=1.0)return 1.0
            // Invert cubic x(t), with native control points (.25,0),(.475,1).
            var lo=0.0;var hi=1.0
            repeat(30){val t=(lo+hi)/2;val u=1-t;val bx=3*u*u*t*.25+3*u*t*t*.475+t*t*t;if(bx<x)lo=t else hi=t}
            val t=(lo+hi)/2;return 3*(1-t)*t*t+t*t*t
        }
    }
}
