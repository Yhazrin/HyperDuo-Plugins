package local.duostandby

import android.content.Context
import android.graphics.*
import android.os.Build
import org.json.JSONObject

/** Float/Play source-derived character renderer. Owns no screen/page/state.
 * Call in a canvas already transformed to the original logical point space.
 * Width, height, windowHeight and color look are explicit host inputs: NO guessed
 * fit-to-screen, purple palette, clock format, or idle floating oscillation.
 * CoreText outlines/advances are retained assets, not an Android font substitute.
 */
class OriginalPlayRenderer(context:Context) {
    companion object {
        // defaultLookForKind(play) 7de30 -> 1a204 first look; native doubles
        // bc520/bc530 primary, bc620/bc3d0 secondary. Not Digital look reuse.
        val ORIGINAL_DEFAULT_PALETTE = Palette(0xff51a2ed.toInt(),0xff0062ba.toInt())
        // Play UIKit controller 92974 blackColor -> 9298c setBackgroundColor.
        const val ORIGINAL_BACKGROUND: Int = -16777216
    }
    /** 88cf8 color case0 uses [secondary,primary,clear,secondary,primary].
     * Caller supplies a resolved ORIGINAL ClockFaceColor, not an arbitrary theme. */
    data class Palette(val primary:Int,val secondary:Int) {
        fun colors(redMode:Boolean)=if(redMode) listOf(0xff990000.toInt(),0xff990000.toInt(),Color.TRANSPARENT,0xff990000.toInt(),0xff990000.toInt())
            else listOf(secondary,primary,Color.TRANSPARENT,secondary,primary)
    }
    private data class Outline(val path:Path,val advance:Double,val tightInkBounds:RectF)
    private val glyphs=mutableMapOf<String,Outline>()
    private val motion=OriginalPlayMotion()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var backdrop:RenderNode?=null
    private val ascent:Double
    private val descent:Double
    var running=false; private set
    var materialBoundary:String?=null; private set

    init {
        val json=JSONObject(context.assets.open("original_play/glyphs.json").bufferedReader().use{it.readText()})
        require(json.getDouble("font_point_size")==OriginalPlayParameters.FONT_SIZE)
        ascent=json.getDouble("ascent");descent=json.getDouble("descent")
        val gs=json.getJSONArray("glyphs")
        for(i in 0 until gs.length()){
            val g=gs.getJSONObject(i);val p=Path();val es=g.getJSONArray("elements")
            for(j in 0 until es.length()){
                val e=es.getJSONObject(j);val pts=e.getJSONArray("points")
                fun x(k:Int)=pts.getJSONArray(k).getDouble(0).toFloat()
                fun y(k:Int)=-pts.getJSONArray(k).getDouble(1).toFloat()
                when(e.getInt("type")){
                    0->p.moveTo(x(0),y(0));1->p.lineTo(x(0),y(0));2->p.quadTo(x(0),y(0),x(1),y(1))
                    3->p.cubicTo(x(0),y(0),x(1),y(1),x(2),y(2));4->p.close()
                }
            }
            // Exact source-font path bounds from CoreText extraction. Android
            // Path.computeBounds may include controls rather than tight extrema.
            val b=g.getJSONArray("path_bounds")
            val x=b.getDouble(0);val y=b.getDouble(1);val w=b.getDouble(2);val h=b.getDouble(3)
            val tight=RectF(x.toFloat(),(-y-h).toFloat(),(x+w).toFloat(),(-y).toFloat())
            glyphs[g.getString("character")]=Outline(p,g.getJSONArray("advance").getDouble(0),tight)
        }
        glyphs["\u200B"]=Outline(Path(),0.0,RectF())
    }
    fun reset(){motion.reset();running=false}

    /** Explicit host adapter for a measured scene point-to-pixel mapping.
     * It never derives a scale from Android density, a glyph fit or UIKit insets.
     * The same SwiftUI-derived draw path is used inside the clipped viewport.
     * The caller must pass the SOURCE window height in points independently of
     * viewport height: native insertion/removal uses UIScreen bounds height. */
    fun drawInViewport(canvas:Canvas,viewport:OriginalPlaySceneViewport,
                       hour:String,minute:String,palette:Palette,nowSeconds:Double,
                       redMode:Boolean=false,animated:Boolean=true,
                       materialPainter:((Canvas,Path,Double,Double)->Unit)?=null,
                       blurSeparator:Boolean=true) {
        val saved=canvas.save()
        try {
            canvas.clipRect(viewport.leftPixels.toFloat(),viewport.topPixels.toFloat(),
                (viewport.leftPixels+viewport.widthPixels).toFloat(),
                (viewport.topPixels+viewport.heightPixels).toFloat())
            canvas.translate(viewport.leftPixels.toFloat(),viewport.topPixels.toFloat())
            canvas.scale(viewport.pixelsPerPoint.toFloat(),viewport.pixelsPerPoint.toFloat())
            draw(canvas,viewport.widthPoints,viewport.heightPoints,viewport.sourceWindowHeightPoints,
                hour,minute,palette,nowSeconds,redMode,animated,materialPainter,blurSeparator)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** iPhone 27.2 video-calibrated adapter, NOT recovered Duo/SwiftUI metrics.
     * Use OriginalPlayVideoReference.viewportIn for a measured scene mapping.
     * Tight ink width/center is supported by the recorded 21:22 glyph positions;
     * it is deliberately separate from the original advance-based draw entry. */
    fun drawIPhoneVideoCalibrated(canvas:Canvas,viewport:OriginalPlaySceneViewport,
                                 hour:String,minute:String,palette:Palette,nowSeconds:Double,
                                 redMode:Boolean=false,animated:Boolean=true,
                                 materialPainter:((Canvas,Path,Double,Double)->Unit)?=null,
                                 blurSeparator:Boolean=true) {
        val saved=canvas.save()
        try {
            canvas.clipRect(viewport.leftPixels.toFloat(),viewport.topPixels.toFloat(),
                (viewport.leftPixels+viewport.widthPixels).toFloat(),
                (viewport.topPixels+viewport.heightPixels).toFloat())
            canvas.translate(viewport.leftPixels.toFloat(),viewport.topPixels.toFloat())
            canvas.scale(viewport.pixelsPerPoint.toFloat(),viewport.pixelsPerPoint.toFloat())
            drawGeometry(canvas,viewport.widthPoints,viewport.heightPoints,viewport.sourceWindowHeightPoints,
                hour,minute,palette,nowSeconds,redMode,animated,materialPainter,blurSeparator,true)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** Active Android adapter of the source UIKit Play/RollingTimeView host.
     * Full actual window; inset64/24 + uniform min-fit. CoreText path bounds are
     * explicitly the UILabel-tight-metric substitute, not native-label equality.
     * Original glyphs, overlap, rotations and existing spring adapter stay intact.
     */
    fun drawAndroidUiKitHost(canvas:Canvas,widthPixels:Int,heightPixels:Int,pixelsPerPoint:Float,
                            hour:String,minute:String,palette:Palette,nowSeconds:Double,
                            redMode:Boolean=false,animated:Boolean=true) {
        require(pixelsPerPoint.isFinite()&&pixelsPerPoint>0f)
        val width=widthPixels/pixelsPerPoint.toDouble()
        val height=heightPixels/pixelsPerPoint.toDouble()
        // No fabricated replacement for an impossible native inset rectangle.
        if(width<=2*OriginalPlayHostLayout.HORIZONTAL_SCREEN_INSET ||
            height<=2*OriginalPlayHostLayout.VERTICAL_SCREEN_INSET){running=false;return}
        val saved=canvas.save()
        try {
            canvas.clipRect(0f,0f,widthPixels.toFloat(),heightPixels.toFloat())
            canvas.scale(pixelsPerPoint,pixelsPerPoint)
            drawGeometry(canvas,width,height,height,hour,minute,palette,nowSeconds,
                redMode,animated,null,OriginalPlayHostLayout.ORIGINAL_SWIFTUI_BLUR_SEPARATOR,
                tightInkLayout=true,uiKitWindowHost=true)
        } finally {canvas.restoreToCount(saved)}
    }

    /** Paints only the face content; original host owns background/crop/scale.
     * The source material path is exposed separately instead of faking a blur.
     * Optional materialPainter overrides the local Android RenderEffect adapter;
     * it must paint 10pt blurred backdrop plus white75% within the supplied mask. */
    fun draw(canvas:Canvas,width:Double,height:Double,windowHeight:Double,
             hour:String,minute:String,palette:Palette,nowSeconds:Double,
             redMode:Boolean=false,animated:Boolean=true,
             materialPainter:((Canvas,Path,Double,Double)->Unit)?=null,
             blurSeparator:Boolean=true) {
        drawGeometry(canvas,width,height,windowHeight,hour,minute,palette,nowSeconds,
            redMode,animated,materialPainter,blurSeparator,false)
    }

    private fun drawGeometry(canvas:Canvas,width:Double,height:Double,windowHeight:Double,
                             hour:String,minute:String,palette:Palette,nowSeconds:Double,
                             redMode:Boolean,animated:Boolean,
                             materialPainter:((Canvas,Path,Double,Double)->Unit)?,
                             blurSeparator:Boolean,tightInkLayout:Boolean,uiKitWindowHost:Boolean=false) {
        require(width>0 && height>0 && windowHeight>0)
        val chars=OriginalPlayParameters.characters(hour,minute)
        require(chars.all{glyphs.containsKey(it)}){"Original glyph asset lacks requested numeral; refusing fallback font"}
        val frame=motion.frame(chars,nowSeconds,width,windowHeight,{
            val outline=glyphs.getValue(it)
            if(tightInkLayout)outline.tightInkBounds.width().toDouble() else outline.advance
        },animated,if(uiKitWindowHost) { characters ->
            val widths=characters.map{glyphs.getValue(it).tightInkBounds.width().toDouble()}
            val heights=characters.map{glyphs.getValue(it).tightInkBounds.height().toDouble()}
            val fit=OriginalPlayHostLayout.androidWindowFit(width,height,characters,widths,heights)
            OriginalPlayMotion.Layout(OriginalPlayHostLayout.uiKitCenters(widths,fit),fit.scale)
        } else null)
        running=frame.running
        val colors=palette.colors(redMode)
        // A private node is the Android material adapter. It records only this face,
        // never reads another app or the device screen. Native backdrop compositor
        // topology and Gaussian kernel are not claimed pixel-identical.
        var recording:Canvas?=null
        if(blurSeparator && materialPainter==null && Build.VERSION.SDK_INT>=31 && canvas.isHardwareAccelerated){
            val node=backdrop?:RenderNode("Original Float glyph backdrop").also{backdrop=it}
            node.setPosition(0,0,kotlin.math.ceil(width).toInt(),kotlin.math.ceil(height).toInt())
            recording=node.beginRecording()
            node.setRenderEffect(RenderEffect.createBlurEffect(10f,10f,Shader.TileMode.CLAMP))
        }
        val separatorMasks=mutableListOf<Path>()
        materialBoundary=when {
            !blurSeparator->"Explicit original unblurred branch"
            materialPainter!=null->null
            recording!=null->"Original radius10/white75 retained; Android RenderEffect compositor parity unverified"
            else->"GPU backdrop blur unavailable; only source white75 separator overlay rendered"
        }
        frame.glyphs.forEach { g ->
            val outline=glyphs.getValue(g.text)
            val transformed=Path(outline.path)
            val matrix=Matrix().apply {
                if(tightInkLayout) {
                    setTranslate(-outline.tightInkBounds.centerX(),-outline.tightInkBounds.centerY())
                } else {
                    setTranslate((-outline.advance/2).toFloat(),((ascent-descent)/2).toFloat())
                }
                if(uiKitWindowHost)postScale(g.scale.toFloat(),g.scale.toFloat())
                postRotate(g.degrees.toFloat());postTranslate(g.x.toFloat(),(height/2+g.yOffset).toFloat())
            }
            transformed.transform(matrix)
            if(g.index==2 && blurSeparator){
                separatorMasks+=transformed
            }else {
                paint.reset();paint.isAntiAlias=true
                // The unblurred text retains source clear colon in this look.
                paint.color=colors[g.index]
                if(Build.VERSION.SDK_INT>=29)paint.blendMode=BlendMode.SCREEN
                else paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                canvas.drawPath(transformed,paint)
                recording?.drawPath(transformed,paint)
            }
        }
        if(recording!=null)backdrop!!.endRecording()
        separatorMasks.forEach { path ->
            if(materialPainter!=null)materialPainter(canvas,path,10.0,.75)
            else {
                val save=canvas.save();canvas.clipPath(path)
                if(recording!=null)canvas.drawRenderNode(backdrop!!)
                paint.reset();paint.isAntiAlias=true;paint.color=0xbfffffff.toInt()
                canvas.drawPath(path,paint);canvas.restoreToCount(save)
            }
        }
    }
}
