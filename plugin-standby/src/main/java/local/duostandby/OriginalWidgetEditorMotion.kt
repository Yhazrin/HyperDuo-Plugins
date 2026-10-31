package local.duostandby

import kotlin.math.exp

/** Non-empty SBHStackConfigurationApertureAnimator, not the PosterBoard portal.
 * 24A94425 settings @1f846de7c..de88: .475 s; animate @1f844c59c:
 * damping1/velocity0. Aperture alpha @1f844c5a0..5f4: enter .3D,
 * exit .8D delayed .2D. Same-build isolated UIKit API probe captured the
 * CASpringAnimation damping/stiffness, mass1 and linear time for these calls.
 * Endpoint geometry comes from actual source/destination host rectangles;
 * Android endpoints remain the explicitly measured iPhone layout profile.
 * Not a claim to execute private SBH match-move/blur or its interruption engine.
 */
class OriginalWidgetEditorMotion {
    data class Sample(val geometry:Float,val apertureAlpha:Float,val entering:Boolean,val running:Boolean)
    companion object {
        const val DURATION_MS=475.0
        const val GEOMETRY_DAMPING=38.877531471171693
        const val ENTER_APERTURE_DAMPING=129.59177157057235
        const val EXIT_APERTURE_DAMPING=48.596914338964638
        // Critical damping, mass1: w=damping/2; analytical normalized step.
        fun spring(ms:Double,durationMs:Double,damping:Double):Float {
            if(ms<=0.0)return 0f
            if(ms>=durationMs)return 1f
            val x=damping*.5*ms/1000.0
            return (1.0-(1.0+x)*exp(-x)).toFloat()
        }
    }
    var sample=Sample(0f,0f,false,false);private set
    val running get()=sample.running
    // Progress of this particular leg, including a reversal starting at g=0.
    // Kept separate from absolute endpoint geometry, which may start mid-flight.
    var geometryLegProgress=1f;private set
    private var start=0L
    private var fromGeometry=0f
    private var fromAperture=0f
    fun begin(entering:Boolean,nanos:Long,animated:Boolean){
        advance(nanos,animated)
        fromGeometry=sample.geometry;fromAperture=sample.apertureAlpha;start=nanos
        geometryLegProgress=0f
        sample=Sample(fromGeometry,fromAperture,entering,true)
        if(!animated)advance(nanos,false)
    }
    fun advance(nanos:Long,animated:Boolean):Sample{
        if(!running)return sample
        val ms=if(animated)(nanos-start).coerceAtLeast(0L)/1e6 else DURATION_MS
        val target=if(sample.entering)1f else 0f
        val g=spring(ms,DURATION_MS,GEOMETRY_DAMPING)
        geometryLegProgress=g
        val a=if(sample.entering)spring(ms,DURATION_MS*.3,ENTER_APERTURE_DAMPING)
            else spring(ms-DURATION_MS*.2,DURATION_MS*.8,EXIT_APERTURE_DAMPING)
        sample=Sample(fromGeometry+(target-fromGeometry)*g,fromAperture+(target-fromAperture)*a,
            sample.entering,ms<DURATION_MS)
        return sample
    }
    fun reset(){sample=Sample(0f,0f,false,false);geometryLegProgress=1f}
}
