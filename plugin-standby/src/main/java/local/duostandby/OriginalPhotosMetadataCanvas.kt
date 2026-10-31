package local.duostandby

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** PhotosClockPoster metadata adapter, not the ordinary Ambient compact overlay.
 * Geometry/state come exclusively from the existing 24A94425 models. Android
 * 1 dp -> 1 point, StaticLayout line metrics and RenderEffect blur are explicit
 * platform adapters, not claims of Duo text/raster equivalence.
 *
 * One instance belongs to one poster state. Asset/content replacement does NOT
 * call show. Only a confirmed original show/hide event may drive controller.
 * No clock, action symbol, gradient recipe or outside layer order is invented.
 */
internal class OriginalPhotosMetadataCanvas(
    initialController: OriginalPhotosPosterMetadataController = OriginalPhotosPosterMetadataController(),
) {
    data class Content(val title:String?,val subtitle:String?)
    enum class TapOwner { GALLERY, POSTER_METADATA, UNRESOLVED }
    data class DrawResult(
        val frame:OriginalPhotosPosterMetadataController.Frame,
        val needsFrame:Boolean,
        val nextAutoHideDeadlineNanos:Long?,
        val metadataLayout:OriginalPhotosPosterMetadataGeometry.MetadataLayout?,
        val blurUnavailable:Boolean,
    )
    private enum class Layer { METADATA, ACTIONS, CLOCK, GRADIENT }
    private data class TextKey(val content:Content,val widthPixels:Int,val pixelsPerPoint:Float,
        val category:OriginalPhotosPosterMetadataGeometry.Category,val rtl:Boolean,
        val font:Typeface,val ink:Int)
    private data class TextBlock(val key:TextKey,val title:StaticLayout?,val subtitle:StaticLayout?,
        val spacingPixels:Float,val heightPoints:Double)

    var controller=initialController
        private set
    private val nodes=mutableMapOf<Layer,RenderNode>()
    private var measured:TextBlock?=null

    fun clear(){
        controller=OriginalPhotosPosterMetadataController();measured=null
        if(Build.VERSION.SDK_INT>=29)nodes.values.forEach{it.discardDisplayList()}
        nodes.clear()
    }

    /** Draws ONLY metadata. Host decides original sibling order and separately
     * invokes drawClock/drawActions/drawGradient if those source layers exist.
     * Schedule nextAutoHideDeadlineNanos as a one-shot wakeup; needsFrame alone
     * deliberately does not request a 7-second continuous redraw loop.
     */
    fun draw(canvas:Canvas,boundsPixels:RectF,pixelsPerPoint:Float,
        category:OriginalPhotosPosterMetadataGeometry.Category,content:Content,
        semiboldTypeface:Typeface,ink:Int,rtl:Boolean,nowNanos:Long,
        gate:OriginalPhotosPosterMetadataController.Gate):DrawResult {
        controller.advance(nowNanos,gate)
        val sampled=controller.sample(nowNanos)
        val frame=sampled
        fun result(layout:OriginalPhotosPosterMetadataGeometry.MetadataLayout?,missingBlur:Boolean=false)=
            DrawResult(frame,controller.needsFrame(nowNanos),controller.nextAutoHideDeadlineNanos,layout,missingBlur)
        if(!valid(boundsPixels,pixelsPerPoint))return result(null)
        val width=boundsPixels.width()/pixelsPerPoint.toDouble()
        val height=boundsPixels.height()/pixelsPerPoint.toDouble()
        val style=OriginalPhotosPosterMetadataGeometry.style(category)
        val textWidth=floor((width-style.leading-style.trailing)*pixelsPerPoint).toInt()
        if(textWidth<=0)return result(null)
        val key=TextKey(content,textWidth,pixelsPerPoint,category,rtl,semiboldTypeface,ink)
        val block=measured?.takeIf{it.key==key}?:measure(key).also{measured=it}
        val layout=OriginalPhotosPosterMetadataGeometry.metadata(category,width,height,block.heightPoints,rtl)
            ?:return result(null) // Native compressed proposal is not silently redesigned.
        val missing=withPoster(canvas,boundsPixels,pixelsPerPoint){pointCanvas ->
            group(pointCanvas,boundsPixels,pixelsPerPoint,Layer.METADATA,frame.metadata.opacity,
                frame.metadata.scale,frame.metadata.blurPoints,layout.scalePivot){c ->
                val saved=c.save()
                c.translate(layout.textBlockFrame.left.toFloat(),layout.textBlockFrame.top.toFloat())
                c.scale(1f/pixelsPerPoint,1f/pixelsPerPoint)
                block.title?.let{it.draw(c);c.translate(0f,it.height.toFloat()+block.spacingPixels)}
                block.subtitle?.draw(c)
                c.restoreToCount(saved)
            }
        }
        return result(layout,missing)
    }

    /** Body receives the full-poster point Canvas and original action contentFrame.
     * Padding precedes scale in source, so pivot is the WHOLE poster center.
     * Controls and their interaction geometry must come from their own source model.
     */
    fun drawActions(canvas:Canvas,boundsPixels:RectF,pixelsPerPoint:Float,
        category:OriginalPhotosPosterMetadataGeometry.Category,
        frame:OriginalPhotosPosterMetadataController.Frame,
        body:(Canvas,OriginalPhotosPosterMetadataGeometry.ActionsLayout)->Unit):Boolean {
        if(!valid(boundsPixels,pixelsPerPoint))return false
        val layout=OriginalPhotosPosterMetadataGeometry.actions(category,
            boundsPixels.width()/pixelsPerPoint.toDouble(),boundsPixels.height()/pixelsPerPoint.toDouble())?:return false
        return withPoster(canvas,boundsPixels,pixelsPerPoint){c ->
            group(c,boundsPixels,pixelsPerPoint,Layer.ACTIONS,frame.actionsOpacity,frame.actionsScale,
                frame.actionsBlurPoints,layout.scalePivot){body(it,layout)}
        }
    }

    /** Main clock layout/pivot must be supplied, never defaulted to metadata's pivot. */
    fun drawClock(canvas:Canvas,boundsPixels:RectF,pixelsPerPoint:Float,
        frame:OriginalPhotosPosterMetadataController.Frame,
        pivotPoints:OriginalPhotosPosterMetadataGeometry.Point,body:(Canvas)->Unit) {
        if(!valid(boundsPixels,pixelsPerPoint))return
        withPoster(canvas,boundsPixels,pixelsPerPoint){c ->
            group(c,boundsPixels,pixelsPerPoint,Layer.CLOCK,frame.clockOpacity,
                frame.clockMetadataScale,0.0,pivotPoints,body)
        }
    }

    /** Only original gradient opacity is applied; the source recipe is host-owned. */
    fun drawGradient(canvas:Canvas,boundsPixels:RectF,pixelsPerPoint:Float,
        frame:OriginalPhotosPosterMetadataController.Frame,body:(Canvas)->Unit) {
        if(!valid(boundsPixels,pixelsPerPoint))return
        withPoster(canvas,boundsPixels,pixelsPerPoint){c ->
            group(c,boundsPixels,pixelsPerPoint,Layer.GRADIENT,frame.gradientOpacity,1.0,0.0,
                OriginalPhotosPosterMetadataGeometry.Point(0.0,0.0),body)
        }
    }

    /** Parent gallery gets first refusal. UNRESOLVED and GALLERY never mutate
     * metadata and never consume even a center tap. No gesture listener installed.
     */
    fun tapResolved(owner:TapOwner,region:OriginalPhotosPosterMetadataController.TapRegion,
        nowNanos:Long,gate:OriginalPhotosPosterMetadataController.Gate):OriginalPhotosPosterMetadataController.TapResult {
        if(owner!=TapOwner.POSTER_METADATA)return OriginalPhotosPosterMetadataController.TapResult(false)
        return controller.tap(region,nowNanos,gate)
    }

    /** Optional original 100-point edge resolver. A host must provide the actual
     * resolved SwiftUI HStack spacing; null means unresolved, not a guessed 0/8.
     */
    fun tapAt(owner:TapOwner,xPixels:Float,yPixels:Float,boundsPixels:RectF,pixelsPerPoint:Float,
        resolvedHorizontalGapPoints:Double?,rtl:Boolean,nowNanos:Long,
        gate:OriginalPhotosPosterMetadataController.Gate):OriginalPhotosPosterMetadataController.TapResult {
        if(owner!=TapOwner.POSTER_METADATA||resolvedHorizontalGapPoints==null||!valid(boundsPixels,pixelsPerPoint))
            return OriginalPhotosPosterMetadataController.TapResult(false)
        val regions=OriginalPhotosPosterMetadataGeometry.tapRegions(boundsPixels.width()/pixelsPerPoint.toDouble(),
            boundsPixels.height()/pixelsPerPoint.toDouble(),resolvedHorizontalGapPoints,rtl)
            ?:return OriginalPhotosPosterMetadataController.TapResult(false)
        val region=regions.regionAt((xPixels-boundsPixels.left)/pixelsPerPoint.toDouble(),
            (yPixels-boundsPixels.top)/pixelsPerPoint.toDouble())?:return OriginalPhotosPosterMetadataController.TapResult(false)
        val resolved=when(region){
            OriginalPhotosPosterMetadataGeometry.TapRegion.CENTER->OriginalPhotosPosterMetadataController.TapRegion.CENTER
            OriginalPhotosPosterMetadataGeometry.TapRegion.LEADING->OriginalPhotosPosterMetadataController.TapRegion.LEADING
            OriginalPhotosPosterMetadataGeometry.TapRegion.TRAILING->OriginalPhotosPosterMetadataController.TapRegion.TRAILING
            OriginalPhotosPosterMetadataGeometry.TapRegion.TOP->OriginalPhotosPosterMetadataController.TapRegion.TOP
            OriginalPhotosPosterMetadataGeometry.TapRegion.BOTTOM->OriginalPhotosPosterMetadataController.TapRegion.BOTTOM
        }
        return tapResolved(owner,resolved,nowNanos,gate)
    }

    private fun measure(key:TextKey):TextBlock {
        val style=OriginalPhotosPosterMetadataGeometry.style(key.category)
        fun line(text:String?,size:Double):StaticLayout? {
            if(text.isNullOrBlank())return null
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface=key.font;textSize=(size*key.pixelsPerPoint).toFloat();color=key.ink
            }
            return StaticLayout.Builder.obtain(text,0,text.length,paint,key.widthPixels)
                .setAlignment(if(style.alignment==OriginalPhotosPosterMetadataGeometry.HorizontalAlignment.LEADING)
                    Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE)
                .setTextDirection(if(key.rtl)TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setIncludePad(false).setLineSpacing(0f,1f).build()
        }
        val title=line(key.content.title,style.titleSize);val subtitle=line(key.content.subtitle,style.subtitleSize)
        val gap=if(title!=null&&subtitle!=null)(style.elementSpacing*key.pixelsPerPoint).toFloat() else 0f
        return TextBlock(key,title,subtitle,gap,((title?.height?:0)+(subtitle?.height?:0)+gap)/key.pixelsPerPoint.toDouble())
    }

    private fun valid(bounds:RectF,scale:Float)=scale.isFinite()&&scale>0f&&
        listOf(bounds.left,bounds.top,bounds.right,bounds.bottom).all{it.isFinite()}&&!bounds.isEmpty

    private fun withPoster(canvas:Canvas,bounds:RectF,scale:Float,body:(Canvas)->Boolean):Boolean {
        val saved=canvas.save()
        try {
            canvas.clipRect(bounds);canvas.translate(bounds.left,bounds.top);canvas.scale(scale,scale)
            return body(canvas)
        } finally {canvas.restoreToCount(saved)}
    }

    /** Group composition: body -> blur -> scale around source pivot -> opacity.
     * RenderNode records at actual backing density, not one low-resolution pixel/point.
     * Software/pre31 fallback is explicit alpha/scale only, never a pretend native blur.
     */
    private fun group(canvas:Canvas,bounds:RectF,pixelsPerPoint:Float,layer:Layer,
        opacity:Double,scale:Double,blurPoints:Double,pivot:OriginalPhotosPosterMetadataGeometry.Point,
        body:(Canvas)->Unit):Boolean {
        if(opacity<=0.0)return false
        val alpha=(opacity.coerceIn(0.0,1.0)*255).roundToInt()
        val saved=canvas.save()
        try {
            canvas.scale(scale.toFloat(),scale.toFloat(),pivot.x.toFloat(),pivot.y.toFloat())
            if(blurPoints>0.0&&Build.VERSION.SDK_INT>=31&&canvas.isHardwareAccelerated){
                val node=nodes.getOrPut(layer){RenderNode("PhotosPoster ${layer.name}")}
                node.setPosition(0,0,ceil(bounds.width()).toInt(),ceil(bounds.height()).toInt())
                node.alpha=opacity.coerceIn(0.0,1.0).toFloat()
                val radius=(blurPoints*pixelsPerPoint).toFloat()
                node.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.DECAL))
                val recording=node.beginRecording()
                try {recording.scale(pixelsPerPoint,pixelsPerPoint);body(recording)}finally{node.endRecording()}
                canvas.scale(1f/pixelsPerPoint,1f/pixelsPerPoint);canvas.drawRenderNode(node)
                return false
            }
            if(alpha<255)canvas.saveLayerAlpha(RectF(0f,0f,bounds.width()/pixelsPerPoint,bounds.height()/pixelsPerPoint),alpha)
            body(canvas)
            return blurPoints>0.0
        } finally {canvas.restoreToCount(saved)}
    }
}
