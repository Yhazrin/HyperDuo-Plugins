package local.duostandby

import kotlin.math.max
import kotlin.math.min

/** Closed FixedSize -> CFPUIAnalogHandsView geometry from 24A94425.
 * Original: ClockFacePostersUI SHA ed602786fb20bfcd90f5da671e987c2cf8660056f9762ccd368e794f9b50e00c.
 * initWithFixedSize 0x2497868e0; secondHandConfiguration 0x249788740.
 * This is NOT a full analog face, tick path, or a measured Duo viewport.
 * Dimensions and UIScreen.scale are native inputs, not Android density.
 */
object OriginalAnalogGeometry {
    data class SourceInput(
        val widthPoints: Double,
        val heightPoints: Double,
        val screenScale: Double,
    ) {
        init {
            require(widthPoints.isFinite() && widthPoints > 0)
            require(heightPoints.isFinite() && heightPoints > 0)
            require(screenScale.isFinite() && screenScale > 0)
        }
    }

    data class Hands(
        val input: SourceInput,
        val regularAspect: Boolean,
        val shortestSide: Double,
        val scaleFactor: Double,
        val secondHandPadding: Double,
        // cp_handConfigurationRoundedToScaleOfView: runs AFTER this assignment.
        // Preserve this distinction until that configuration adapter is supplied.
        val secondHandLengthBeforeConfigurationRounding: Double,
    ) {
        // Non-regular branches retain original CLKUI default configuration inputs.
        // Do not invent their missing default widths or reuse old Canvas constants.
        fun hourHandWidthBeforeConfigurationRounding(defaultWidth: Double): Double =
            if (regularAspect) 17.0 else defaultWidth * (scaleFactor * 0.8)
        fun minuteHandWidthBeforeConfigurationRounding(defaultWidth: Double): Double =
            if (regularAspect) 17.0 else defaultWidth * (scaleFactor * 0.8)
        fun secondHandWidthBeforeConfigurationRounding(defaultWidth: Double): Double =
            if (regularAspect) 5.0 else defaultWidth * scaleFactor
    }

    /** uiRoundToScale must supply the actual UIKit operation or an explicitly
     * labelled platform adapter. No guessed UIKit rounding or screenScale default.
     * AnalogHands uses max/min, unlike the face dial's ordered W/H classifier.
     */
    fun derive(input: SourceInput, uiRoundToScale: (Double, Double) -> Double): Hands {
        val shortest = min(input.widthPoints, input.heightPoints)
        val regular = max(input.widthPoints, input.heightPoints) / shortest < 1.5
        val padding = if (regular) 16.0 else 29.0
        // 0x249786904..6944: UIRoundToScale(H / 165, UIScreen.main.scale)
        val scaleFactor = uiRoundToScale(input.heightPoints / 165.0, input.screenScale)
        // 0x2497888b4..8924: UIRoundToScale(shortest / 2, scale) - padding
        val length = uiRoundToScale(shortest / 2.0, input.screenScale) - padding
        return Hands(input, regular, shortest, scaleFactor, padding, length)
    }

    /** UIKitCore UIRoundToScale 0x1c43e70d8: FRINTA(value*scale)/scale.
     * Positive scale must be supplied; source scale==0 global fallback is not guessed.
     */
    fun roundToScale(value:Double,scale:Double):Double {
        require(scale.isFinite() && scale>0.0)
        val x=if(scale==1.0)value else value*scale
        val rounded=kotlin.math.sign(x)*kotlin.math.floor(kotlin.math.abs(x)+0.5)
        return if(scale==1.0)rounded else rounded/scale
    }

    data class HandConfiguration(
        val type:Int,val handLength:Double,val handWidth:Double,val pegRadius:Double,val pegStrokeWidth:Double,
        val smoothingRadius:Double,val inlayInsetRadius:Double,val armLength:Double,val armWidth:Double,
        val tailLength:Double,val dropShadowRadius:Double,val dropShadowOpacity:Double,
        val radialShadowRadius:Double,val radialShadowOpacity:Double,val shadowX:Double,val shadowY:Double,
        val removeInlay:Boolean=false,val outlineOnly:Boolean=false,val excludePeg:Boolean=false,
        val roundedSecondHand:Boolean=false,val excludeSecondTail:Boolean=false,
    )
    data class Configurations(val hour:HandConfiguration,val minute:HandConfiguration,val second:HandConfiguration)

    /** Captured by invoking CLKUIAnalogHandConfiguration on 24A94425 Simulator,
     * CLKDevice.currentDevice description "7 unpaired". This named profile is NOT
     * a claim about Duo device identity. Defaults are an explicit configurations input.
     */
    fun capturedUnpairedDeviceDefaults():Configurations {
        val hour=HandConfiguration(0,36.25,7.75,1.0,2.75,1.25,1.75,5.5,3.0,0.0,7.0,0.15,3.5,0.25,1.0,3.0)
        return Configurations(hour,hour.copy(handLength=68.0),
            HandConfiguration(1,84.0,1.5,0.75,2.25,1.25,0.0,0.0,0.0,14.25,7.0,0.15,2.0,0.5,1.0,2.0,roundedSecondHand=true))
    }

    /** Complete CFPUIAnalogHandsView configuration transforms, not generic scaled
     * line lengths. Keep screenScale distinct from the actual UIView display scale.
     */
    fun configurations(input:SourceInput,defaults:Configurations,viewScale:Double):Configurations {
        require(viewScale.isFinite() && viewScale>0.0)
        val g=derive(input,::roundToScale);val s=g.scaleFactor
        fun rounded(c:HandConfiguration)=c.copy(
            handLength=roundToScale(c.handLength,viewScale),handWidth=roundToScale(c.handWidth,viewScale),
            armLength=roundToScale(c.armLength,viewScale),armWidth=roundToScale(c.armWidth,viewScale),
            pegStrokeWidth=roundToScale(c.pegStrokeWidth,viewScale),pegRadius=roundToScale(c.pegRadius,viewScale),
            tailLength=roundToScale(c.tailLength,viewScale))
        fun shadows(c:HandConfiguration)=c.copy(dropShadowRadius=c.dropShadowRadius*s,
            radialShadowRadius=c.radialShadowRadius*s,shadowX=c.shadowX*s,shadowY=c.shadowY*s)
        val ds=defaults.second
        val second=rounded(shadows(ds).copy(
            handWidth=if(g.regularAspect)5.0 else ds.handWidth*s,
            pegRadius=if(g.regularAspect)0.0 else ds.pegRadius*(s*1.3333),
            pegStrokeWidth=if(g.regularAspect)8.0 else ds.pegStrokeWidth*(s/1.3333),
            handLength=g.secondHandLengthBeforeConfigurationRounding,tailLength=ds.tailLength*s,radialShadowOpacity=0.15))
        fun arm(default:HandConfiguration,minute:Boolean):HandConfiguration {
            val pr=if(minute&&g.regularAspect)4.0 else default.pegRadius*s
            val ps=if(minute&&g.regularAspect)8.0 else default.pegStrokeWidth*s
            val al=default.armLength*s
            // FNMSUB = secondLength*ratio - unroundedArmLength; then two subtractions.
            val length=(Math.fma(second.handLength,if(minute)0.92 else 0.54,-al)-pr/2.0)-ps
            return rounded(shadows(default).copy(handWidth=if(g.regularAspect)17.0 else default.handWidth*(s*0.8),
                pegRadius=pr,pegStrokeWidth=ps,armWidth=default.armWidth*s,armLength=al,handLength=length))
        }
        return Configurations(arm(defaults.hour,false),arm(defaults.minute,true),second)
    }

    data class Point(val x:Double,val y:Double)
    data class Bounds(val width:Double,val height:Double)
    sealed class PathCommand {
        data class Move(val x:Double,val y:Double):PathCommand()
        data class Line(val x:Double,val y:Double):PathCommand()
        /** CGPathAddArc radians: clockwise=true means decreasing angle. The
         * platform adapter must connect the current point to the arc start.
         */
        data class Arc(val x:Double,val y:Double,val radius:Double,val start:Double,val end:Double,val clockwise:Boolean):PathCommand()
        object Close:PathCommand()
    }
    data class HandPath(val bounds:Bounds,val anchor:Point,val commands:List<PathCommand>)

    /** Default fill-path branch of CLKUIAnalogHandFactory, inset=0.
     * Actual smoothed arm + peg + capsule path, NOT drawLine with guessed caps.
     * Insets/inlay-removal/zero-smoothing are explicitly unsupported, not approximated.
     * Shadow and inlay material compositing remain outside this geometric path.
     */
    fun handPath(c:HandConfiguration):HandPath {
        require(c.type==0 || c.type==1)
        require(c.smoothingRadius>0.0 && !c.removeInlay)
        val pi=Math.PI;val sm=c.smoothingRadius;val half=c.handWidth/2.0
        val peg=c.pegRadius+c.pegStrokeWidth
        val width=max(c.handWidth,peg*2.0)
        val height=if(c.type==0)Math.fma(peg,2.0,c.handLength+c.armLength)
            else c.handLength+(if(c.excludeSecondTail)peg else c.tailLength)
        val cy=if(c.type==0)((c.handLength+c.armLength)+c.pegRadius)+c.pegStrokeWidth else c.handLength
        val cx=width/2.0
        val commands=mutableListOf<PathCommand>()
        fun arc(x:Double,y:Double,r:Double,a:Double,b:Double,cw:Boolean){commands+=PathCommand.Arc(x,y,r,a,b,cw)}
        if(c.type==0){
            val neck=Math.fma(c.armWidth,0.5,sm)
            val pa=kotlin.math.acos(neck/(peg+sm))
            val py=cy-kotlin.math.sqrt(Math.fma(peg+sm,peg+sm,-neck*neck))
            arc(cx+neck,py,sm,pi,pi-pa,true)
            arc(cx,cy,peg,-pa,pa+pi,c.excludePeg)
            arc(cx-neck,py,sm,pa,0.0,true)
            val bodyY=c.handLength-half
            val ba=kotlin.math.acos(neck/(half+sm))
            val by=bodyY+kotlin.math.sqrt(Math.fma(half+sm,half+sm,-neck*neck))
            arc(cx-neck,by,sm,0.0,-ba,true)
            arc(cx,bodyY,half,pi-ba,pi,false)
            arc(cx,half,half,-pi,0.0,false)
            arc(cx,bodyY,half,0.0,ba,false)
            arc(cx+neck,by,sm,ba+pi,pi,true)
        } else {
            val neck=Math.fma(c.handWidth,0.5,sm)
            val a=kotlin.math.acos(neck/(peg+sm))
            val dy=kotlin.math.sqrt(Math.fma(peg+sm,peg+sm,-neck*neck))
            arc(cx,cy,peg,pi,pi+a,false)
            arc(cx-neck,cy-dy,sm,a,0.0,true)
            if(c.roundedSecondHand){commands+=PathCommand.Line(cx-half,half);arc(cx,half,half,pi,0.0,false)}
            else {commands+=PathCommand.Line(cx-half,0.0);commands+=PathCommand.Line(cx+half,0.0)}
            arc(cx+neck,cy-dy,sm,pi,pi-a,true)
            arc(cx,cy,peg,-a,0.0,false)
            var endStart=0.0
            if(!c.excludeSecondTail){
                arc(cx,cy,peg,0.0,a,false)
                arc(cx+neck,cy+dy,sm,a+pi,pi,true)
                val tailY=c.handLength+c.tailLength
                if(c.roundedSecondHand){commands+=PathCommand.Line(cx+half,tailY-half);arc(cx,tailY-half,half,0.0,-pi,false)}
                else{commands+=PathCommand.Line(cx+half,tailY);commands+=PathCommand.Line(cx-half,tailY)}
                arc(cx-neck,cy+dy,sm,0.0,-a,true)
                endStart=pi-a
            }
            arc(cx,cy,peg,endStart,pi,false)
            commands+=PathCommand.Close
        }
        if(c.pegRadius>0.0 && (c.type==1 || !c.excludePeg)){
            commands+=PathCommand.Move(cx+c.pegRadius,cy)
            arc(cx,cy,c.pegRadius,0.0,-2*pi,true)
            commands+=PathCommand.Close
        }
        return HandPath(Bounds(width,height),Point(0.5,cy/height),commands)
    }

}
