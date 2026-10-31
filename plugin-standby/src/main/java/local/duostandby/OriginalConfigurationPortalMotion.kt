package local.duostandby


/** Ambient editing-switcher portal, NOT the normal poster paging spring.
 * Simulator 24A94425 PosterBoard: enter1bd950 / exit1bdee4 duration300ms;
 * keyTimes: transform/corner0...85, enter portal fade.8..1, exit fade.2...4;
 * controls0..200, enter footer100..200, exit footer0..200.
 * Same-build UIKit probe captured global cubic(.42,0,.58,1), linear keyTimes;
 * apply easing to the 300ms transaction BEFORE keyframe-window interpolation.
 * This is an isolated UIKit reproduction, not a captured PosterBoard/Duo entry.
 * Exit inherited context and cancellation remain explicit Android adapters.
 * Retargeting carries every currently displayed channel instead of resetting.
 */
class OriginalConfigurationPortalMotion {
    enum class Phase { IDLE, ENTERING, SELECTING, EXITING }
    data class Sample(val phase:Phase,val geometry:Float,val portalAlpha:Float,
                      val controlsAlpha:Float,val footerAlpha:Float,val elapsedMs:Double,
                      val running:Boolean)
    private var started=0L
    private var lastSample=0L
    private var initialPortal=1f
    private var initialControls=0f
    private var initialFooter=0f
    var sample=Sample(Phase.IDLE,0f,0f,0f,0f,0.0,false);private set
    val running get()=sample.running
    fun begin(entering:Boolean,nanos:Long,portalAlpha:Float=sample.portalAlpha,
              controlsAlpha:Float=sample.controlsAlpha,footerAlpha:Float=sample.footerAlpha){
        initialPortal=portalAlpha;initialControls=controlsAlpha;initialFooter=footerAlpha
        started=nanos;lastSample=nanos
        sample=Sample(if(entering)Phase.ENTERING else Phase.EXITING,0f,
            portalAlpha,controlsAlpha,footerAlpha,0.0,true)
    }
    /** Captured CAMediaTimingFunction control points; solve x(u), then evaluate y(u). */
    private fun easeInOut(t:Double):Double {
        if(t<=0.0)return 0.0
        if(t>=1.0)return 1.0
        var lo=0.0;var hi=1.0
        repeat(24){
            val u=(lo+hi)*.5;val v=1.0-u
            val x=3.0*v*v*u*.42+3.0*v*u*u*.58+u*u*u
            if(x<t)lo=u else hi=u
        }
        val u=(lo+hi)*.5
        return 3.0*(1.0-u)*u*u+u*u*u
    }
    /** Independent UIView basic-animation channels (controls and footer). */
    private fun fraction(ms:Double,start:Double,duration:Double):Float =
        easeInOut(((ms-start)/duration).coerceIn(0.0,1.0)).toFloat()
    /** CAKeyframeAnimation has a global timingFunction, no segment timingFunctions. */
    private fun keyframe(ms:Double,start:Double,duration:Double):Float =
        ((easeInOut((ms/300.0).coerceIn(0.0,1.0))-start)/duration).coerceIn(0.0,1.0).toFloat()
    private fun mix(a:Float,b:Float,t:Float)=a+(b-a)*t
    fun advance(nanos:Long,animated:Boolean):Sample {
        if(!running)return sample
        lastSample=nanos
        val ms=if(animated)(nanos-started).coerceAtLeast(0L)/1e6 else 300.0
        val enter=sample.phase==Phase.ENTERING
        val done=ms>=300.0
        sample=Sample(if(done){if(enter)Phase.SELECTING else Phase.IDLE}else sample.phase,
            keyframe(ms,0.0,.85),
            mix(initialPortal,if(enter)0f else 1f,keyframe(ms,if(enter).8 else .2,.2)),
            mix(initialControls,if(enter)1f else 0f,fraction(ms,0.0,200.0)),
            mix(initialFooter,if(enter)1f else 0f,fraction(ms,if(enter)100.0 else 0.0,if(enter)100.0 else 200.0)),
            ms.coerceAtMost(300.0),!done)
        return sample
    }
    /** Detail editor owns the screen temporarily; preserve all live portal values. */
    fun hold(nanos:Long){if(running){started+=(nanos-lastSample).coerceAtLeast(0L);lastSample=nanos}}
    fun reset(selecting:Boolean=false){
        sample=Sample(if(selecting)Phase.SELECTING else Phase.IDLE,1f,0f,
            if(selecting)1f else 0f,if(selecting)1f else 0f,300.0,false)
    }
}
