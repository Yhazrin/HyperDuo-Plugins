package local.duostandby

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Android drawing adapter for the recovered 24A94425 dial + hand paths.
 * All geometry is in one point space: Android 1 dp -> 1 source point. Android
 * density is explicitly used for both rounding scales, NOT a measured Duo scale.
 * Shadow/inlay materials and SwiftUI text line metrics are not yet ported.
 */
class OriginalAnalogFaceCanvas {
    private data class Key(val width:Float,val height:Float,val scale:Float,val layoutRaw:Int)
    private data class Hand(val source:OriginalAnalogGeometry.HandPath,val path:Path)
    private data class Scene(val key:Key,val ticks:List<Pair<Int,OriginalAnalogTicks.Segment>>,
        val numerals:List<OriginalAnalogLayout.Numeral>,val hour:Hand,val minute:Hand,val second:Hand)
    private var cached:Scene?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)

    /** Caller has already scaled the Canvas by Android density. */
    fun draw(c:Canvas,widthPoints:Float,heightPoints:Float,pxPerPoint:Float,time:LocalDateTime,
        legacyStyle:Int,numeralSystem:Int,numeralFont:Typeface,numeralInk:Int,minorInk:Int,
        handInk:Int,secondInk:Int,legacyLabels:(()->Unit)?=null) {
        if(widthPoints<=0f||heightPoints<=0f||pxPerPoint<=0f)return
        // Existing persisted style IDs are reverse-ordered relative to original enum.
        val layoutRaw=when(legacyStyle){0->2;1->1;else->0}
        val key=Key(widthPoints,heightPoints,pxPerPoint,layoutRaw)
        val scene=cached?.takeIf{it.key==key}?:scene(key).also{cached=it}
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE
        // Original StrokeStyle @0x10001cfac: round cap, miter join, miterLimit 10.
        paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.MITER;paint.strokeMiter=10f
        for((index,segment) in scene.ticks){
            paint.color=if(layoutRaw==2||index%5!=0)minorInk else numeralInk
            paint.strokeWidth=segment.thickness.toFloat()
            c.drawLine(segment.outer.x.toFloat(),segment.outer.y.toFloat(),
                segment.inner.x.toFloat(),segment.inner.y.toFloat(),paint)
        }
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL;paint.typeface=numeralFont
        paint.color=numeralInk;paint.textAlign=Paint.Align.CENTER
        for(n in scene.numerals){
            paint.textSize=n.size.toFloat();val fm=paint.fontMetrics
            // Existing Android line-box baseline adapter; not claimed as SwiftUI parity.
            c.drawText(OriginalAnalogEditor.numeral(numeralSystem,n.value),n.x.toFloat(),
                n.y.toFloat()-(fm.ascent+fm.descent)/2f,paint)
        }
        legacyLabels?.invoke()
        val second=time.second+time.nano/1_000_000_000.0
        // Preserve the prior live clock-angle progression; geometry is the changed mechanism.
        drawHand(c,scene.hour,widthPoints/2f,heightPoints/2f,(time.hour%12)*30.0+time.minute/2.0,handInk)
        drawHand(c,scene.minute,widthPoints/2f,heightPoints/2f,time.minute*6.0+time.second*0.1,handInk)
        drawHand(c,scene.second,widthPoints/2f,heightPoints/2f,second*6.0,secondInk)
    }

    private fun scene(key:Key):Scene {
        val w=key.width.toDouble();val h=key.height.toDouble();val scale=key.scale.toDouble()
        val dial=OriginalAnalogTicks.dial(w,h)
        val rect=OriginalAnalogTicks.Rect(0.0,0.0,w,h)
        val ticks=OriginalAnalogTicks.ticks(dial,key.layoutRaw)
            // Source visibility branch @0x10001d0a8; do not drop all collapsed minor ticks.
            .filterNot{key.layoutRaw==2&&(it.index==15||it.index==45)}
            .map{it.index to OriginalAnalogTicks.path(rect,dial,it,squareProgress=1.0)}
        val configurations=OriginalAnalogGeometry.configurations(
            OriginalAnalogGeometry.SourceInput(w,h,scale),
            OriginalAnalogGeometry.capturedUnpairedDeviceDefaults(),viewScale=scale)
        fun hand(config:OriginalAnalogGeometry.HandConfiguration):Hand {
            val source=OriginalAnalogGeometry.handPath(config)
            return Hand(source,androidPath(source.commands))
        }
        android.util.Log.i("DuoStandBy","analogGeometry points=${w}x${h} pxPerPoint=$scale class=${dial.widthClass} layout=${key.layoutRaw} adapter=android_dp_to_source_point defaults=24A94425_unpaired")
        return Scene(key,ticks,if(key.layoutRaw==0)emptyList() else OriginalAnalogLayout.layout(w,h,key.layoutRaw==2),
            hand(configurations.hour),hand(configurations.minute),hand(configurations.second))
    }

    private fun drawHand(c:Canvas,hand:Hand,cx:Float,cy:Float,angle:Double,ink:Int){
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL;paint.color=ink
        val saved=c.save();c.translate(cx,cy);c.rotate(angle.toFloat())
        c.translate((-hand.source.anchor.x*hand.source.bounds.width).toFloat(),
            (-hand.source.anchor.y*hand.source.bounds.height).toFloat())
        c.drawPath(hand.path,paint);c.restoreToCount(saved)
    }

    /** Widget hands only. Same recovered CFPUIAnalogHandsView paths as the face,
     * but with the WorldClockWidget SmallSizeDimentions widths
     * (hourMinuteHandWidth 5, secondsHandWidth 1 at 136 points) instead of the
     * face's regular-aspect 17/5. sizePoints is in source points; the caller has
     * already placed/scaled the Canvas. */
    fun drawHands(c:Canvas,sizePoints:Float,time:LocalDateTime,ink:Int,secondInk:Int,
        hourMinuteWidth:Double=5.0,secondWidth:Double=1.0){
        if(sizePoints<=0f)return
        val s=sizePoints.toDouble()
        val input=OriginalAnalogGeometry.SourceInput(s,s,1.0)
        val cfgs=OriginalAnalogGeometry.configurations(input,
            OriginalAnalogGeometry.capturedUnpairedDeviceDefaults(),viewScale=1.0)
        fun hand(config:OriginalAnalogGeometry.HandConfiguration,width:Double):Hand{
            val source=OriginalAnalogGeometry.handPath(config.copy(handWidth=width))
            return Hand(source,androidPath(source.commands))
        }
        val hour=hand(cfgs.hour,hourMinuteWidth)
        val minute=hand(cfgs.minute,hourMinuteWidth)
        val second=hand(cfgs.second,secondWidth)
        val sec=time.second+time.nano/1_000_000_000.0
        drawHand(c,hour,sizePoints/2f,sizePoints/2f,(time.hour%12)*30.0+time.minute/2.0,ink)
        drawHand(c,minute,sizePoints/2f,sizePoints/2f,time.minute*6.0+time.second*0.1,ink)
        drawHand(c,second,sizePoints/2f,sizePoints/2f,sec*6.0,secondInk)
    }

    /** CGPath arc directions preserved in the shared y-down drawing space.
     * Split full circles so Android's modulo-360 arc API cannot erase the peg hole.
     */
    private fun androidPath(commands:List<OriginalAnalogGeometry.PathCommand>)=Path().apply {
        fillType=Path.FillType.WINDING
        for(command in commands)when(command){
            is OriginalAnalogGeometry.PathCommand.Move->moveTo(command.x.toFloat(),command.y.toFloat())
            is OriginalAnalogGeometry.PathCommand.Line->lineTo(command.x.toFloat(),command.y.toFloat())
            is OriginalAnalogGeometry.PathCommand.Close->close()
            is OriginalAnalogGeometry.PathCommand.Arc->{
                var sweep=command.end-command.start
                if(command.clockwise){while(sweep>0.0)sweep-=2.0*PI}
                else {while(sweep<0.0)sweep+=2.0*PI}
                if(isEmpty)moveTo((command.x+command.radius*cos(command.start)).toFloat(),
                    (command.y+command.radius*sin(command.start)).toFloat())
                val oval=RectF((command.x-command.radius).toFloat(),(command.y-command.radius).toFloat(),
                    (command.x+command.radius).toFloat(),(command.y+command.radius).toFloat())
                val degrees=(command.start*180.0/PI).toFloat()
                if(kotlin.math.abs(sweep)>=2.0*PI-1e-12){
                    val half=if(sweep<0.0)-180f else 180f
                    arcTo(oval,degrees,half,false);arcTo(oval,degrees+half,half,false)
                }else arcTo(oval,degrees,(sweep*180.0/PI).toFloat(),false)
            }
        }
    }
}
