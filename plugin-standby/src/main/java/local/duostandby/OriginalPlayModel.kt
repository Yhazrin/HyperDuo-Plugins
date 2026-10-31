package local.duostandby

import kotlin.math.*
import kotlin.random.Random

/** ClockPoster 24A94425 Play == Float. Source addresses in play-v20/parameters.json.
 * Native spring parameters, not a hand-picked easing. Solver and interruption policy
 * are Android adapters; SwiftUI solver/raster equality has NOT been demonstrated. */
object OriginalPlayParameters {
    const val FONT_SIZE = 400.0
    const val FONT_WIDTH = 85.0
    const val FONT_WEIGHT = 860.0
    const val OVERLAP = 30.0
    const val LAYOUT_RESPONSE = .5
    const val LAYOUT_DAMPING = 1.0
    const val ROTATION_RESPONSE = 2.0
    const val ROTATION_DAMPING = .6
    const val SEPARATOR_BLUR = 10.0
    const val SEPARATOR_WHITE_ALPHA = .75
    fun rotationDelay(index:Int) = (index - 1) / 30.0

    /** 86358 -> 880d0 creates [-5,5), 87f90 shuffles, 883ac maps prefix(5). */
    fun rotations(random:Random):List<Double> = (-5 until 5).shuffled(random).take(5).map(Int::toDouble)

    /** Native pads a single-character hour with U+200B, NOT zero or a normal space. */
    fun characters(hour:String, minute:String, separator:String=":"):List<String> {
        require(hour.length in 1..2 && minute.length==2 && separator.length==1)
        val padded=if(hour.length==1) "\u200B$hour" else hour
        return (padded+separator+minute).map(Char::toString)
    }

    /** OverlapLayout 84adc..84ba4: overlap + sum(width - overlap). */
    fun centers(widths:List<Double>, containerWidth:Double):List<Double> {
        require(widths.size==5 && widths.all{it.isFinite()&&it>=0})
        var x=(containerWidth-(widths.sum()-OVERLAP*(widths.size-1)))/2
        return widths.map { w -> (x+w/2).also { x+=w-OVERLAP } }
    }

    /** Zero-velocity step response. Native response/damping are authoritative;
     * analytic spring is the adapter, no bespoke Bezier approximation. */
    fun springProgress(seconds:Double,response:Double,damping:Double):Double {
        if(seconds<=0)return 0.0
        val w=2*PI/response
        return if(damping==1.0) 1-(1+w*seconds)*exp(-w*seconds)
        else {
            val wd=w*sqrt(1-damping*damping)
            1-exp(-damping*w*seconds)*(cos(wd*seconds)+damping*w/wd*sin(wd*seconds))
        }
    }
}

/** Each digit has its own rotation. Text changes invalidate angles; no idle timer. */
class OriginalPlayMotion(private val random:Random=Random.Default) {
    data class Glyph(val text:String,val index:Int,val x:Double,val yOffset:Double,val degrees:Double,val outgoing:Boolean,val scale:Double=1.0)
    data class Layout(val centers:List<Double>,val scale:Double=1.0)
    data class Frame(val glyphs:List<Glyph>,val running:Boolean)
    private var chars:List<String>?=null
    private var prior:List<String>?=null
    private var angles=emptyList<Double>()
    private var previousAngles=emptyList<Double>()
    private var start=0.0
    private var previousTime=Double.NEGATIVE_INFINITY

    fun reset(){chars=null;prior=null;angles=emptyList();previousAngles=emptyList();previousTime=Double.NEGATIVE_INFINITY}

    fun frame(characters:List<String>, nowSeconds:Double, width:Double, screenHeight:Double,
              advance:(String)->Double, animated:Boolean=true,
              hostLayout:((List<String>)->Layout)?=null):Frame {
        require(characters.size==5 && nowSeconds.isFinite() && nowSeconds>=previousTime)
        previousTime=nowSeconds
        val old=chars
        if(old==null || !animated){
            if(old!=characters)angles=OriginalPlayParameters.rotations(random)
            chars=characters.toList();prior=null;previousAngles=angles;start=nowSeconds
        }else if(old!=characters){
            val elapsed=nowSeconds-start
            // Preserve position on interruption; velocity inheritance remains unverified.
            previousAngles=angles.indices.map { i ->
                val p=OriginalPlayParameters.springProgress(elapsed-OriginalPlayParameters.rotationDelay(i),2.0,.6)
                if(prior==null)angles[i] else previousAngles[i]+(angles[i]-previousAngles[i])*p
            }
            prior=old;chars=characters.toList();angles=OriginalPlayParameters.rotations(random);start=nowSeconds
        }
        val current=chars!!
        val elapsed=nowSeconds-start
        val from=prior
        fun layout(c:List<String>)=hostLayout?.invoke(c)
            ?:Layout(OriginalPlayParameters.centers(c.map(advance),width))
        val currentLayout=layout(current)
        val oldLayout=if(from==null)currentLayout else layout(from)
        val x=currentLayout.centers
        val oldX=oldLayout.centers
        val position=if(from==null)1.0 else OriginalPlayParameters.springProgress(elapsed,.5,1.0)
        // Source UIKit fit is uniform per layout. Reuse the existing Android
        // position-spring adapter for scale changes; no per-axis glyph stretching.
        val scale=oldLayout.scale+(currentLayout.scale-oldLayout.scale)*position
        val output=mutableListOf<Glyph>();var running=false
        current.forEachIndexed { i,ch ->
            val rp=if(from==null)1.0 else OriginalPlayParameters.springProgress(elapsed-OriginalPlayParameters.rotationDelay(i),2.0,.6)
            val rotation=if(from==null)angles[i] else previousAngles[i]+(angles[i]-previousAngles[i])*rp
            if(from!=null && (abs(1-rp)>.00001 || abs(1-position)>.00001))running=true
            val cx=oldX[i]+(x[i]-oldX[i])*position
            if(from!=null && from[i]!=ch){
                output+=Glyph(from[i],i,cx,-screenHeight*position,rotation,true,scale)
                output+=Glyph(ch,i,cx,screenHeight*(1-position),rotation,false,scale)
            }else output+=Glyph(ch,i,cx,0.0,rotation,false,scale)
        }
        if(!running)prior=null
        return Frame(output,running)
    }
}
