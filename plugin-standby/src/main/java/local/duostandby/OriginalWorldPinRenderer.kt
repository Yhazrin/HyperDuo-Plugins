package local.duostandby

import android.content.Context
import android.graphics.*
import android.os.Build
import java.time.Instant

/** Source Pin 4e750: three filled circles, NOT a ring. Source parameters retained.
 * RenderEffect's GPU blur and Paint shadow are explicitly Android raster adapters;
 * same parameters do not establish Apple's backdrop kernel/pixel equivalence.
 */
class OriginalWorldPinRenderer(private val context:Context) {
    private val density=context.resources.displayMetrics.density.toDouble()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip=Path()
    private val springs=mutableMapOf<String,OriginalWorldPins.SelectionSpring>()
    private var backdrop:RenderNode?=null
    private var backdropKey=""
    var running=false;private set
    fun reset(){springs.clear();running=false}
    fun draw(canvas:Canvas,width:Int,height:Int,instant:Instant,red:Boolean,
             model:OriginalWorldPins.Model,map:OriginalWorldMapPreview,now:Double,animated:Boolean) {
        running=false
        if(model.cities.isEmpty())return
        val frame=OriginalWorldMapGeometry.fit(width/density,height/density,density)
        // Isolate map-only backdrop; overlap with other pins is an explicit compositor boundary.
        if(Build.VERSION.SDK_INT>=31 && canvas.isHardwareAccelerated){
            val key="$width/$height/${instant.epochSecond}/$red"
            if(key!=backdropKey){
                val node=backdrop?:RenderNode("World map pin backdrop").also{backdrop=it}
                node.setPosition(0,0,width,height)
                val recording=node.beginRecording(width,height)
                map.draw(recording,width,height,instant,red);node.endRecording()
                val saturation=ColorMatrix().apply{setSaturation(.8f)}
                node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect((10*density).toFloat(),(10*density).toFloat(),Shader.TileMode.CLAMP),
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturation))))
                backdropKey=key
            }
        }
        val orange=OriginalWorldPinColors.orange(context)
        for(city in model.orderedCities){
            val target=if(model.isSelected(city))1.0 else 0.0
            val spring=springs.getOrPut(city.id){OriginalWorldPins.SelectionSpring(target)}
            val sample=spring.sample(target,now,animated);running=running||sample.running
            val diameters=OriginalWorldPins.diameters(city.currentLocation,sample.value)
            val point=OriginalWorldPins.project(city,frame,density)
            val x=(point.x*density).toFloat();val y=(point.y*density).toFloat()
            val outer=(diameters.backdrop*density/2).toFloat()
            // Black shadow radius9 only on outer circle. No shadow on middle/accent.
            paint.reset();paint.isAntiAlias=true;paint.color=Color.BLACK
            paint.setShadowLayer((9*density).toFloat(),0f,0f,Color.BLACK)
            canvas.drawCircle(x,y,outer,paint);paint.clearShadowLayer()
            if(Build.VERSION.SDK_INT>=31 && canvas.isHardwareAccelerated){
                val save=canvas.save();clip.reset();clip.addCircle(x,y,outer,Path.Direction.CW)
                canvas.clipPath(clip);backdrop?.let{canvas.drawRenderNode(it)};canvas.restoreToCount(save)
            }
            // Original opaque red replacement does not inherit normal orange's .55 opacity.
            paint.color=if(red)Color.rgb(if(city.currentLocation)204 else 102,0,0)else orange
            paint.alpha=if(red)255 else 140
            canvas.drawCircle(x,y,outer,paint)
            paint.alpha=255;paint.color=if(red)Color.rgb(if(city.currentLocation)204 else 102,0,0)else Color.WHITE
            canvas.drawCircle(x,y,(diameters.middle*density/2).toFloat(),paint)
            paint.color=if(red)Color.rgb(if(city.currentLocation)102 else 204,0,0)else orange
            canvas.drawCircle(x,y,(diameters.accent*density/2).toFloat(),paint)
        }
    }
}
