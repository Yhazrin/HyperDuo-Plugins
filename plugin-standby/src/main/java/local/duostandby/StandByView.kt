package local.duostandby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Paint
import android.view.Choreographer
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.os.BatteryManager
import android.widget.Toast
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.format.DateTimeFormatter
import kotlin.math.*
import local.duostandby.OriginalStandByNavigation.Axis
import local.duostandby.OriginalStandByNavigation.Mode

/** One live renderer remains owner throughout drag, preview and return transitions. */
class StandByView(context:Context,val controller:StandByController,private val onExit:()->Unit,private val onStateChanged:()->Unit):FrameLayout(context) {
    private val fonts=OriginalFonts(context)
    private val renderer=StandByRenderer(context)
    private val clockTick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 1000L) }
    }
    init {
        DigitalWeatherRepository.onUpdate = { postInvalidate() }
        post(clockTick)
    }
    var onBatterySettings:()->Unit = { Toast.makeText(context,"请在前台打开应用后连接蓝牙电量",Toast.LENGTH_LONG).show() }
    var onPhotoSourceSettings:()->Unit = { Toast.makeText(context,"请在前台应用连接小米精选照片源",Toast.LENGTH_LONG).show() }
    var onPhotoStep:(Boolean)->Unit = {}
    // PhotosClockPoster photosContent accessory. Only the picker is Android;
    // the draft belongs to the existing paged editor and its stable configuration.
    var onPhotoEdit:()->Unit = {}
    var onPhotoContentBegin:()->Boolean = { false }
    var onPhotoContentFinish:(Boolean)->Boolean = { false }
    var onPhotoContentSummary:()->String = { "" }
    var onPhotoTimeLayout:()->Int = { 1 }
    var onPhotoTimeLayoutChange:(Int)->Boolean = { false }
    var onPhotoNumberSystem:()->Int = { 0 }
    var onPhotoNumberSystemChange:(Int)->Boolean = { false }
    private fun photoScriptGlobe():android.graphics.drawable.Drawable = object:android.graphics.drawable.Drawable(){
        private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(1.2f)}
        override fun getIntrinsicWidth()=dp(21f).toInt()
        override fun getIntrinsicHeight()=dp(21f).toInt()
        override fun draw(canvas:Canvas){
            val b=RectF(bounds);b.inset(dp(1.4f),dp(1.4f))
            canvas.drawOval(b,pen)
            canvas.drawOval(RectF(b.centerX()-b.width()*.22f,b.top,b.centerX()+b.width()*.22f,b.bottom),pen)
            canvas.drawLine(b.left,b.centerY(),b.right,b.centerY(),pen)
        }
        override fun setAlpha(alpha:Int){pen.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:android.graphics.ColorFilter?){pen.colorFilter=filter;invalidateSelf()}
        @Suppress("DEPRECATION") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
    fun refreshPhotoEditor(){
        if(controller.editing && controller.displayed.face==Face.PHOTOS)rebuildOptions()
        refresh()
    }
    val currentPhotoPreview get()=renderer.photos.bitmap
    var onPhotoReadyStep:(Boolean,String)->Boolean = { _,_ -> false }
    var onPhotoCardSwitch:(Boolean)->Boolean = { false }
    var onPhotoAlbums:()->Unit = {}
    var onPhotoCategoryEditor:(()->Unit)? = null
    var onPhotoInteraction:(Boolean)->Unit = {}
    fun updatePhotos(snapshot:XiaomiCuratedPhotoSource.Snapshot){
        renderer.photos=snapshot
        if(photoCommitId!=null&&snapshot.currentAssetIdentity==photoCommitId){
            // Preserve presentation's expected ID until draw consumes the new owner.
            photoCommitId=null;photoPager.snap(1);renderer.photoVerticalDragFraction=0f
            finishPhotoInteraction()
        }else if(snapshot.phase==XiaomiCuratedPhotoSource.Phase.DISCONNECTED)cancelPhotoPaging()
        updateDescription();invalidate()
    }
    private val widgetPrefs=context.getSharedPreferences("widget_stacks_v20",Context.MODE_PRIVATE)
    private val widgets:OriginalWidgetStackController=OriginalWidgetStackController(context,{postInvalidateOnAnimation()}, {
        if(!controller.referenceMode)widgetPrefs.edit().putString("stacks",widgetsState()).apply()
        updateDescription()
    },{onBatterySettings()})
    private fun widgetsState():String=widgets.saveState()
    fun updateBatteries(devices:List<BatteryDeviceModel>){renderer.batteries=devices;updateDescription();invalidate()}

    fun configureWorld(referenceJson:String?,selectedId:String?){renderer.configureWorld(referenceJson,selectedId);invalidate()}
    private var calendarStyleMotion:OriginalCalendarStyleTransition?=null
    private var calendarPress = OriginalMonthPressFeedback(null,false) // Explicit Android initial state.
    private var calendarPressMotion = OriginalMonthPressFeedback.LiveMotion(calendarPress.snapshot())
    private val calendarPressRecognizer = OriginalMonthPressFeedback.PassiveRecognizer(false)
    private var calendarPressZone: Int? = null
    private fun pressTime() = System.nanoTime()/1_000_000_000.0
    private fun dispatchCalendarPress(event: OriginalMonthPressFeedback.PassiveRecognizer.Event) {
        val zone=calendarPressZone ?: return
        event.pressChanged?.let { pressed ->
            val change=if(zone == -1)calendarPress.onDimmedPressChanged(pressed)
                       else calendarPress.onZonePressChanged(zone,pressed)
            calendarPressMotion.apply(change,pressTime());postInvalidateOnAnimation()
        }
    }
    private fun endCalendarPress() {
        calendarPressRecognizer.touchesEndedOrCancelled()
        dispatchCalendarPress(calendarPressRecognizer.reset())
        calendarPressZone=null
    }
    /** Passive observer only. The existing pager and long-press receive every event.
     * Android night is the explicit lowLight adapter; editor/calibration disable input.
     * No calendar navigation is synthesized from press release.
     */
    private fun observeCalendarPress(e:MotionEvent) {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endCalendarPress()
                if(controller.editing || editorProgress>0f || calibratingCamera || !legacyCalendar || outer.running)return
                val state=controller.displayed
                val layout=renderer.calendarPressLayout(width,height,state) ?: return
                val frame=calendarPressMotion.frame(pressTime())
                val transform=android.graphics.Matrix().apply{setValues(OriginalMonthPressFeedback.projection(
                    width.toDouble(),height.toDouble(),frame.angle,frame.anchorX).map{it.toFloat()}.toFloatArray())}
                val inverse=android.graphics.Matrix();if(!transform.invert(inverse))return
                val point=floatArrayOf(e.x,e.y);inverse.mapPoints(point)
                calendarPressZone=layout.zone(point[0]/density,point[1]/density,layoutDirection==LAYOUT_DIRECTION_RTL) ?: return
                dispatchCalendarPress(calendarPressRecognizer.touchesBegan(1,e.getPointerId(0),(point[0]/density).toDouble(),(point[1]/density).toDouble()))
            }
            MotionEvent.ACTION_POINTER_DOWN -> if(calendarPressZone!=null) {
                dispatchCalendarPress(calendarPressRecognizer.touchesBegan(1,e.getPointerId(e.actionIndex),0.0,0.0))
                // UIKit resets a failed recognizer; Android has no matching reset callback.
                if(calendarPressRecognizer.touchesMoved(-1,0.0,0.0).failed)endCalendarPress()
            }
            MotionEvent.ACTION_POINTER_UP,MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                // Weather face complication paging lives on the main-face touch path (gallery closed).
                if(e.actionMasked==MotionEvent.ACTION_UP&&controller.state.face==Face.WEATHER){
                    controller.dispatch(StandByEvent.WeatherPhase(1));invalidate()
                }
                endCalendarPress()
            }
        }
    }

    private val chargingOverlay=ChargingOverlay().apply{labelTypeface=fonts.semibold}
    // Separate schema: old safe-area-center calibration must not be silently reinterpreted.
    private val cameraPrefs=context.getSharedPreferences("camera_alignment_v5",Context.MODE_PRIVATE)
    private var calibratingCamera=false
    private var calibrationDraft:CutoutAnchor.Anchor?=null
    private var calibrationStroke=4.0
    private var calibrationStep=1.0
    private var pinchStart=0f
    private var pinchRadius=0.0
    private var gestureAnchor:CutoutAnchor.Anchor?=null
    private var gestureX=0f;private var gestureY=0f;private var pinchedGesture=false
    private var calibrationPanelKey=""
    private var calibrationPanelRotation=0
    private val calibrationControls=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(18,12,18,12);setBackgroundColor(Color.rgb(24,24,26));visibility=GONE}
    private val calibrationReadout=android.widget.TextView(context).apply{setTextColor(Color.WHITE);textSize=13f;gravity=android.view.Gravity.CENTER;typeface=fonts.ui}
    private fun cameraKey():String {
        val m=display?.mode
        return "${android.os.Build.DEVICE}:${m?.physicalWidth}x${m?.physicalHeight}"
    }
    private fun outerPanel():Boolean {
        val m=display?.mode ?: return false
        return minOf(m.physicalWidth,m.physicalHeight)==1168 && maxOf(m.physicalWidth,m.physicalHeight)==1712
    }
    private fun panelLabel()=if(outerPanel())"外屏" else "内屏／其他屏幕"
    private fun cameraAnchor():CutoutAnchor.Anchor? {
        val wc=rootWindowInsets?.displayCutout
        val sc=if(android.os.Build.VERSION.SDK_INT>=29)display?.cutout else null
        val rect=(wc?:sc)?.boundingRects?.maxByOrNull{it.width()*it.height()} ?: return null
        val loc=IntArray(2);if(wc!=null)getLocationInWindow(loc)else getLocationOnScreen(loc)
        val a=CutoutAnchor.fromBounds((rect.left-loc[0]).toDouble(),(rect.top-loc[1]).toDouble(),(rect.right-loc[0]).toDouble(),(rect.bottom-loc[1]).toDouble())
        val k=cameraKey()
        if(!cameraPrefs.contains(k+":nx"))return a // Provisional safety box, NOT an optical measurement.
        val point=OuterCameraGeometry.fromNatural(cameraPrefs.getFloat(k+":nx",.5f).toDouble(),cameraPrefs.getFloat(k+":ny",0f).toDouble(),width.toDouble(),height.toDouble(),display?.rotation?:0)
        return a.copy(x=point.x,y=point.y,radius=cameraPrefs.getFloat(k+":r",a.radius.toFloat()).toDouble(),calibrated=true)
    }
    private fun cameraStroke()=cameraPrefs.getFloat(cameraKey()+":stroke",4f).toDouble()
    private fun calibrateCamera(){
        endCalendarPress()
        val a=cameraAnchor() ?: run{Toast.makeText(context,"当前屏幕未提供挖孔区域",Toast.LENGTH_LONG).show();return}
        closeEditor(false);calibratingCamera=true;calibrationDraft=a;chargingStarted=null
        calibrationStroke=cameraStroke();calibrationStep=1.0
        calibrationPanelKey=cameraKey();calibrationPanelRotation=display?.rotation?:0
        calibrationControls.visibility=VISIBLE;calibrationControls.bringToFront();updateCalibrationReadout();invalidate()
    }
    private fun closeCalibration(){calibratingCamera=false;calibrationDraft=null;calibrationControls.visibility=GONE;invalidate()}
    private fun updateCalibrationReadout(){
        val a=calibrationDraft?:return
        val n=OuterCameraGeometry.toNatural(a.x,a.y,width.toDouble(),height.toDouble(),display?.rotation?:0)
        val naturalW=if((display?.rotation?:0)%2==0)width else height
        val naturalH=if((display?.rotation?:0)%2==0)height else width
        calibrationReadout.text=String.format(java.util.Locale.ROOT,"%s · 实际像素 px · 步长 %.1f\n画面 X %.1f  Y %.1f  孔半径 %.1f  描边 %.1f\n竖屏基准 X %.1f  Y %.1f · 下方调节，保存前不会退出",panelLabel(),calibrationStep,a.x,a.y,a.radius,calibrationStroke,n.x*naturalW,n.y*naturalH)
    }
    private fun adjustCamera(dx:Double=0.0,dy:Double=0.0,dr:Double=0.0,ds:Double=0.0){
        calibrationDraft=calibrationDraft?.let{it.copy(x=(it.x+dx).coerceIn(0.0,width.toDouble()),y=(it.y+dy).coerceIn(0.0,height.toDouble()),radius=(it.radius+dr).coerceIn(.5,minOf(width,height)/4.0),calibrated=true)}
        calibrationStroke=(calibrationStroke+ds).coerceIn(.5,20.0);updateCalibrationReadout();invalidate()
    }
    private fun saveCalibration(){
        val a=calibrationDraft?:return
        if(cameraKey()!=calibrationPanelKey||(display?.rotation?:0)!=calibrationPanelRotation){closeCalibration();Toast.makeText(context,"屏幕状态改变，请重新打开该屏校准",Toast.LENGTH_SHORT).show();return}
        val n=OuterCameraGeometry.toNatural(a.x,a.y,width.toDouble(),height.toDouble(),display?.rotation?:0)
        val k=cameraKey()
        cameraPrefs.edit().putFloat(k+":nx",n.x.toFloat()).putFloat(k+":ny",n.y.toFloat()).putFloat(k+":r",a.radius.toFloat()).putFloat(k+":stroke",calibrationStroke.toFloat()).apply()
        val data=org.json.JSONObject().put("schema","camera-optical-calibration/5").put("source","user_adjusted_not_factory_measurement").put("panel",panelLabel()).put("panelKey",k).put("rotation",display?.rotation).put("viewWidth",width).put("viewHeight",height).put("viewCenterX",a.x).put("viewCenterY",a.y).put("naturalNormalizedX",n.x).put("naturalNormalizedY",n.y).put("apertureRadiusPx",a.radius).put("strokeWidthPx",calibrationStroke).put("timestampMs",System.currentTimeMillis())
        try {java.io.File(context.filesDir,"camera-calibration-v5.json").writeText(data.toString(2));java.io.File(context.filesDir,"camera-calibration-v5-${k.replace(':','_')}.json").writeText(data.toString(2))}catch(e:java.io.IOException){Toast.makeText(context,"位置已保存，但参数记录写入失败",Toast.LENGTH_LONG).show()}
        closeCalibration();Toast.makeText(context,"${panelLabel()}参数已保存，可读取精确坐标记录",Toast.LENGTH_SHORT).show();previewCurrentBattery()
    }
    private fun setupCalibrationControls(){
        calibrationControls.addView(calibrationReadout,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(70f).toInt()))
        fun row(items:List<Pair<String,()->Unit>>){
            val line=LinearLayout(context)
            items.forEach{(label,action)->line.addView(button(label,label,false,action).apply{textSize=13f;background=GradientDrawable().apply{cornerRadius=dp(8f);setColor(Color.rgb(48,48,52))}},LinearLayout.LayoutParams(0,dp(44f).toInt(),1f).apply{setMargins(3,3,3,3)})}
            calibrationControls.addView(line,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(48f).toInt()))
        }
        row(listOf("← X" to {adjustCamera(dx=-calibrationStep)},"X →" to {adjustCamera(dx=calibrationStep)},"↑ Y" to {adjustCamera(dy=-calibrationStep)},"Y ↓" to {adjustCamera(dy=calibrationStep)}))
        row(listOf("半径 −" to {adjustCamera(dr=-calibrationStep)},"半径 +" to {adjustCamera(dr=calibrationStep)},"描边 −" to {adjustCamera(ds=-.5)},"描边 +" to {adjustCamera(ds=.5)}))
        row(listOf("步长 0.5/1/5" to {calibrationStep=when(calibrationStep){.5->1.0;1.0->5.0;else->.5};updateCalibrationReadout()},"外屏横向居中" to {val a=calibrationDraft;if(a!=null&&outerPanel()){val n=OuterCameraGeometry.toNatural(a.x,a.y,width.toDouble(),height.toDouble(),display?.rotation?:0);val q=OuterCameraGeometry.fromNatural(.5,n.y,width.toDouble(),height.toDouble(),display?.rotation?:0);calibrationDraft=a.copy(x=q.x,y=q.y);updateCalibrationReadout();invalidate()}},"取消" to {closeCalibration()}))
        row(listOf("保存并记录参数" to {saveCalibration()}))
    }
    private var chargingStarted:Long?=null
    private var chargingPercent=0
    fun showChargingPreview(percent:Int){
        if(percent !in 0..100)return
        chargingPercent=percent;chargingStarted=SystemClock.uptimeMillis()
        chargingOverlay.cameraAnchor=cameraAnchor();chargingOverlay.cameraStrokeWidthPx=cameraStroke()
        android.util.Log.i("DuoStandBy","chargingPreview percent=$percent camera=${chargingOverlay.cameraAnchor} display=${display?.displayId} rotation=${display?.rotation} viewport=${width}x${height}")
        invalidate()
    }
    fun stopChargingPreview(){chargingStarted=null;invalidate()}
    private fun previewCurrentBattery(){
        val percent=(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if(percent !in 0..100){Toast.makeText(context,"未能读取电量，未播放虚构电量",Toast.LENGTH_SHORT).show();return}
        closeEditor(false);showChargingPreview(percent)
    }
    private val density=resources.displayMetrics.density
    private fun dp(n:Float)=n*density
    private val chrome=FrameLayout(context)
    private val toolbar=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
    // Photos uses the original split chrome: property titles and action/adjunct
    // regions are different siblings, never four weighted tabs between actions.
    private val photoActionBar=FrameLayout(context)
    private val photoAdjunctHost=FrameLayout(context)
    private val photoTitleScroll=android.widget.HorizontalScrollView(context).apply{
        isHorizontalScrollBarEnabled=false;isFillViewport=false
    }
    private val photoTitleRail=LinearLayout(context).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
    }
    private val photoTabButtons=mutableListOf<Button>()
    private class PropertyOptionsHost(context:Context):ScrollView(context) {
        var maximumHeight=0
        override fun onMeasure(widthSpec:Int,heightSpec:Int){
            val available=MeasureSpec.getSize(heightSpec)
            val cap=minOf(available,maximumHeight.coerceAtLeast(0))
            super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST))
        }
    }
    private val optionsRegion=FrameLayout(context)
    private val optionsHost=PropertyOptionsHost(context).apply{isFillViewport=false;isVerticalScrollBarEnabled=false}
    // No original remote host supplied a scale to this Android View. Retain nil
    // and use the recovered fallback, rather than claiming .5 is Duo's actual s.
    private val configuredEditorFaceScale:Double?=null
    private val tabButtons=mutableListOf<Button>()
    private val preview=RectF()
    // Detail editor starts/returns at its actual selection card, not a full-screen jump.
    private var editorOrigin:RectF?=null
    private var editorReturnToConfiguration=false
    private var editorProgress=if(controller.editing)1f else 0f
    private var editorFrameTime=0L
    private var editorTarget=editorProgress
    private var editorVelocity=0.0
    private var editorRunning=false
    private enum class EditorEntryResult { POSTER_ENTERED, POSTER_REOPENED, WIDGET_ENTERED, ALREADY_OPEN, UNAVAILABLE }
    private val editorExiting get() = !controller.editing && editorTarget==0f && (editorRunning || editorProgress!=0f)
    private var editorExitTouchBlocked=false
    /** 样式切换过渡：原版 TickShape 是 Animatable（uniformProgress/collapsedProgress），
     *  切样式时整盘过渡；曲线/时长尚未解出，这里用同 build UIKit 实测 cubic(.42,0,.58,1) 的 350ms 适配。 */
    private var styleFrom:FaceOptions?=null
    private var styleBlend=1f
    private var styleAnimator:android.animation.ValueAnimator?=null
    private fun animateStyleChange(from:FaceOptions){
        styleAnimator?.cancel()
        styleFrom=from;styleBlend=0f;invalidate()
        styleAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
            duration=350L
            interpolator=android.view.animation.PathInterpolator(.42f,0f,.58f,1f)
            addUpdateListener{styleBlend=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:android.animation.Animator){
                    styleFrom=null;styleBlend=1f;invalidate()
                }
            })
            start()
        }
    }
    private val editorSpring=object:Choreographer.FrameCallback {
        override fun doFrame(nanos:Long){
            if(!editorRunning)return
            if(editorFrameTime!=0L){
                val dt=((nanos-editorFrameTime)/1e9).coerceAtMost(.064)
                // PosterKit FaceEditor didAppear animation: response0.5, damping1, blend0.
                // Analytic critical spring adapter; retains velocity on interruption.
                val w=2.0*Math.PI/.5;val y=editorProgress-editorTarget
                val a=editorVelocity+w*y;val e=exp(-w*dt)
                editorProgress=(editorTarget+(y+a*dt)*e).toFloat()
                editorVelocity=(editorVelocity-w*a*dt)*e
            }
            editorFrameTime=nanos
            if(abs(editorProgress-editorTarget)<.0005f&&abs(editorVelocity)<.005){editorProgress=editorTarget;editorVelocity=0.0;editorRunning=false}
            chrome.alpha=editorProgress.coerceIn(0f,1f);invalidate()
            if(editorReturnToConfiguration)startPagingFrames()
            if(editorRunning)Choreographer.getInstance().postFrameCallback(this)
            else if(editorTarget==0f){
                chrome.visibility=GONE;updateDescription()
                // 编辑器收起后继续走 portal 退场，最终回到运行中的整面（原版没有中间选择页）。
                if(exitConfigurationAfterEditor){
                    exitConfigurationAfterEditor=false
                    if(configurationOpen)finishConfigurationSelection(configurationPortalIndex)
                }
            }
        }
    }
    private fun stopEditorMotion(){editorRunning=false;editorFrameTime=0;Choreographer.getInstance().removeFrameCallback(editorSpring)}
    private fun settleEditorForLifecycle(){
        stopEditorMotion();editorProgress=if(controller.editing)1f else 0f
        editorTarget=editorProgress;editorVelocity=0.0;editorExitTouchBlocked=false
        chrome.alpha=editorProgress;chrome.visibility=if(controller.editing)VISIBLE else GONE
        chrome.importantForAccessibility=if(controller.editing)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    // Separate offset/selection memories: horizontal provider category and vertical clocks.
    private inner class Pager(val axis:Axis,initial:Int,var last:Int,private val committed:(Int)->Unit) {
        var page=initial.toDouble()
        private var release:OriginalPagingMotion.Release?=null
        private var lastFrame=0L
        private var destination=initial
        val running get()=release!=null
        private fun points()=(if(axis==Axis.HORIZONTAL)width else height).coerceAtLeast(1)/density.toDouble()
        fun hold(){release=null;lastFrame=0L} // Presentation offset is retained, never snap on DOWN.
        fun raw()=OriginalPagingMotion.unRubberBand(page,0.0,last.toDouble(),1.0)
        fun drag(raw:Double){page=OriginalPagingMotion.rubberBand(raw,0.0,last.toDouble(),1.0);startPagingFrames()}
        fun settle(velocity:Double=0.0,selected:Int?=null){
            val size=points();destination=selected?.coerceIn(0,last)?:OriginalPagingMotion.target(page*size,velocity,size,last)
            if(!OriginalStandByMotionPolicy.animationsEnabled(context)){page=destination.toDouble();hold();committed(destination);refresh();return}
            release=OriginalPagingMotion.Release(width.coerceAtLeast(1)/density.toDouble(),page*size,velocity,destination*size,density.toDouble())
            lastFrame=System.nanoTime();startPagingFrames()
        }
        fun advance(nanos:Long){
            val motion=release?:return
            val dt=(nanos-lastFrame)/1_000_000.0
            // UIKit 24A94425 _smoothScroll 59b20/5a384 stores the complete
            // update timestamp. The paging kernel floors this interval itself;
            // carrying fractional milliseconds into the next frame changes its path.
            if(dt>=1.0)page=motion.advance(dt)/points()
            lastFrame=nanos
            if(!motion.running){page=destination.toDouble();hold();committed(destination);refresh()}
        }
        fun snap(index:Int){hold();page=index.coerceIn(0,last).toDouble();startPagingFrames()}
    }
    private val horizontalSign get()=if(layoutDirection==LAYOUT_DIRECTION_RTL)-1.0 else 1.0
    private var legacyCalendar=controller.state.face==Face.CALENDAR
    private val outer=Pager(Axis.HORIZONTAL,OriginalStandByNavigation.mode(controller.state.face).ordinal,Mode.entries.lastIndex){ index ->
        legacyCalendar=false
        controller.dispatch(StandByEvent.Select(OriginalStandByNavigation.face(Mode.entries[index],controller.clockFace)))
    }
    private val clocks=Pager(Axis.VERTICAL,OriginalStandByNavigation.clockIndex(controller.clockFace),OriginalStandByNavigation.clockFaces.lastIndex){ index ->
        val face=OriginalStandByNavigation.clockFaces[index]
        controller.rememberClockFace(face)
        if(outer.page.roundToInt()==Mode.CLOCKS.ordinal)controller.dispatch(StandByEvent.Select(face))
    }
    // Vertical paging advances ready assets of this configuration.
    // Commit identity includes page/source/card/asset/URI, not the page UUID alone.
    // Reuse the already recovered UIKit paging kernel; do not invent a second spring.
    private var photoStartPage=1.0
    private var photoInputActive=false
    private var photoCommitId:String?=null
    private val photoPager=Pager(Axis.VERTICAL,1,2){index->finishPhotoPage(index)}
    private fun finishPhotoInteraction(){
        if(photoInputActive){photoInputActive=false;onPhotoInteraction(false)}
    }
    private fun cancelPhotoPaging(){
        photoPager.hold();photoPager.page=1.0;photoCommitId=null
        renderer.cancelPhotoVerticalDrag();finishPhotoInteraction()
    }
    private fun finishPhotoPage(index:Int){
        if(index==1){cancelPhotoPaging();return}
        val reverse=index==0
        val neighbor=if(reverse)renderer.photos.readyPrevious else renderer.photos.readyNext
        val photoList=renderer.photos.photos
        val targetIndex=renderer.photos.selectedIndex+(if(reverse)-1 else 1)
        val id=neighbor?.currentAssetIdentity?:photoList.getOrNull(targetIndex)?.id
        if(id==null||!renderer.expectPhotoVerticalCommit(reverse)){
            // Album boundary: the original has no album feed, this is the 时光 card adapter.
            if(id==null&&onPhotoCardSwitch(reverse)){
                photoPager.settle(selected=1);finishPhotoInteraction();return
            }
            photoPager.settle(selected=1);return
        }
        photoCommitId=id
        if(!onPhotoReadyStep(reverse,id)){
            photoCommitId=null;renderer.cancelPhotoVerticalDrag()
            photoPager.snap(1);finishPhotoInteraction()
        }
        android.util.Log.i("DuoPhotoPaging","commit reverse=$reverse target=$id currentPage=${renderer.photos.pageId}")
    }
    private fun dragPhotoPage(raw:Double){
        val previousReady=renderer.photos.readyPrevious?.bitmap?.isRecycled==false
        val nextReady=renderer.photos.readyNext?.bitmap?.isRecycled==false
        val bounded=raw.coerceIn(0.0,2.0)
        photoPager.drag(bounded)
        renderer.photoVerticalDragFraction=(1.0-photoPager.page).toFloat()
    }
    // Android Duo preview catalog; device Rushmore implementation is not recovered.
    // Direct paging is restored from user feedback. Same-build Simulator proves horizontal
    // paging and configuration-only scroll gating, NOT this exact Duo catalog or policy.
    // Official manual supports tap-to-choose but does not prove exclusive gesture gating.
    // Catalog order/geometry here are Android source-preview adapters. The existing
    // .35/1 presentation spring and .61 scale are reused, NOT measured Duo gallery constants.
    private val galleryFaces=listOf(Face.SOLAR,Face.FLOAT,Face.WORLD,Face.ANALOG,Face.DIGITAL,Face.CALENDAR,Face.WEATHER,Face.WIDGETS,Face.PHOTOS)
    private val navigationPrefs=context.getSharedPreferences("navigation_profile_v33",Context.MODE_PRIVATE)
    private val duoProfile=false // v39: provider H / clock configuration V, never flattened Face.ordinal.
    private var galleryOpen=false
    private val galleryReveal=OriginalStandByNavigation.Presentation(0.0)
    private var galleryProgress=0.0
    private var galleryPendingSelection:Int?=null
    private var galleryStartPage=0.0
    private val gallery=Pager(Axis.HORIZONTAL,galleryFaces.indexOf(controller.state.face).coerceAtLeast(0),galleryFaces.lastIndex){index->
        if(galleryPendingSelection==index || !galleryVisible)commitGallerySelection(index)
        updateDescription()
    }
    private val facePagingFrame=OriginalFacePagingFrame(galleryFaces.size,gallery.page)
    private val galleryVisible get()=galleryOpen || galleryProgress>1e-5 || galleryReveal.running
    fun setNavigationProfile(profile:String){
        // Older launchers may still pass "duo". Migrate routing, not saved widget/face options.
        if(!controller.referenceMode)navigationPrefs.edit().putString("profile","source_hierarchy").apply()
        galleryOpen=false;galleryPendingSelection=null;gallery.snap(galleryFaces.indexOf(controller.state.face).coerceAtLeast(0))
        legacyCalendar=controller.state.face==Face.CALENDAR
        outer.snap(OriginalStandByNavigation.mode(controller.state.face).ordinal)
        clocks.snap(OriginalStandByNavigation.clockIndex(controller.clockFace))
        startPagingFrames();refresh()
    }
    private fun openGallery(){
        if(!duoProfile || controller.editing || widgets.editing || calibratingCamera)return
        removeCallbacks(longPress);widgets.cancelTouchForOuterPaging();endCalendarPress()
        // Keep the live offset when opening during a release; never snap the presentation.
        gallery.hold()
        galleryPendingSelection=null;galleryOpen=true;startPagingFrames();updateDescription()
    }
    private fun commitGallerySelection(index:Int){
        galleryPendingSelection=null
        controller.dispatch(StandByEvent.Select(galleryFaces[index.coerceIn(0,galleryFaces.lastIndex)]))
        legacyCalendar=controller.state.face==Face.CALENDAR
        outer.snap(OriginalStandByNavigation.mode(controller.state.face).ordinal)
        clocks.snap(OriginalStandByNavigation.clockIndex(controller.clockFace))
        galleryOpen=false;refresh()
    }
    private fun chooseGallery(index:Int){
        val choice=index.coerceIn(0,galleryFaces.lastIndex)
        galleryPendingSelection=choice
        if(abs(gallery.page-choice)<1e-6&&!gallery.running)commitGallerySelection(choice)
        else gallery.settle(selected=choice)
    }
    private fun galleryTitle(face:Face)=when(face){
        Face.SOLAR->"Solar";Face.FLOAT->"Float";Face.WORLD->"世界时钟";Face.ANALOG->"模拟时钟"
        Face.DIGITAL->"数字时钟";Face.CALENDAR->"日历";Face.WIDGETS->"双列小组件";Face.PHOTOS->"照片";Face.WEATHER->"天气"
    }
    private fun drawGallery(c:Canvas){
        val p=galleryProgress.toFloat().coerceIn(0f,1f)
        val pitch=width*(1f-.28f*p) // Explicit Android spacing adapter; no claimed native geometry.
        val chooserScale=OriginalStandByNavigation.scale(Axis.HORIZONTAL,0.0).toFloat()
        val index=gallery.page.roundToInt().coerceIn(0,galleryFaces.lastIndex)
        // The entire poster owns one transform, alpha and corner mask. A child's
        // drawColor/background cannot escape this container; no inner-face fitting.
        facePagingFrame.items.forEach { item ->
            if(!item.visible&&p<=0f)return@forEach
            val i=item.index;val face=galleryFaces[i]
            val scale=item.scale.toFloat()*(1f-p)+chooserScale*p
            val x=((i-gallery.page)*pitch*horizontalSign).toFloat()
            val rect=RectF(width*(1-scale)/2+x,height*(1-scale)/2,width*(1+scale)/2+x,height*(1+scale)/2)
            if(rect.right<0||rect.left>width)return@forEach
            // Native c1b8..c2c0 gets the appearing container's trait corner and
            // clips only during appearance/disappearance. Android rounded-corner
            // insets are the explicit trait adapter; no guessed Apple radius.
            val transitioning=gallery.running||abs(gallery.page-gallery.page.roundToInt())>1e-6||item.running||galleryVisible
            val corner=if(transitioning)deviceCorner()*scale else 0f
            val saved=c.save()
            c.clipPath(OriginalContinuousShape.path(rect,corner))
            // Horizontal native bdd0 alpha acts on the passed whole item view.
            // Chooser's all-visible alpha is retained as a separately labeled adapter.
            val opacity=(item.progress*(1.0-p)+p).coerceIn(0.0,1.0)
            val alpha=c.saveLayerAlpha(rect,(255*opacity).roundToInt())
            drawIn(c,rect,controller.forFace(face),live=true)
            c.restoreToCount(alpha);c.restoreToCount(saved)
            if(p>0f){
                val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(1.5f);this.alpha=((if(i==index)220 else 55)*p).roundToInt()}
                c.drawPath(OriginalContinuousShape.path(rect,corner),line)
            }
        }
        if(p<=0f)return
        val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;alpha=(255*p).roundToInt();typeface=fonts.semibold;textAlign=Paint.Align.CENTER;textSize=dp(22f)}
        c.drawText(galleryTitle(galleryFaces[index]),width/2f,dp(40f),textPaint)
        textPaint.typeface=fonts.ui;textPaint.textSize=dp(12f);textPaint.alpha=(170*p).roundToInt()
        c.drawText("Duo 导航移植预览 · 横滑选择，轻点启用",width/2f,dp(61f),textPaint)
        textPaint.textSize=dp(15f);textPaint.alpha=(255*p).roundToInt()
        c.drawText("普通 iPhone 模式",dp(92f),height-dp(24f),textPaint)
        c.drawText("${index+1} / ${galleryFaces.size}",width/2f,height-dp(24f),textPaint)
        c.drawText("返回当前钟面",width-dp(84f),height-dp(24f),textPaint)
        if(gallery.running||galleryReveal.running)postInvalidateOnAnimation()
    }
    private fun onGalleryTouch(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                finishInput();gallery.hold();galleryPendingSelection=null
                // Grabbing a closing chooser reverses from its live presentation value.
                galleryOpen=true;galleryStartPage=gallery.raw();downX=e.x;downY=e.y;pointerId=e.getPointerId(0)
                tracker=VelocityTracker.obtain().also{it.addMovement(e)};startPagingFrames()
            }
            MotionEvent.ACTION_MOVE->{
                val pi=e.findPointerIndex(pointerId);if(pi<0)return true
                tracker?.addMovement(e)
                val dx=e.getX(pi)-downX;val dy=e.getY(pi)-downY
                if(hypot(dx,dy)>slop)moving=true
                if(moving){gestureAxis=OriginalStandByNavigation.lock(gestureAxis,dx.toDouble(),dy.toDouble(),slop.toDouble())
                    if(gestureAxis==Axis.HORIZONTAL){val pitch=width*(1.0-.28*galleryProgress);gallery.drag(galleryStartPage-dx/pitch.coerceAtLeast(1.0)*horizontalSign)}}
            }
            MotionEvent.ACTION_UP->{
                tracker?.addMovement(e)
                if(moving){
                    tracker?.computeCurrentVelocity(1000)
                    val pitchRatio=(1.0-.28*galleryProgress).coerceAtLeast(.01)
                    gallery.settle(-(tracker?.getXVelocity(pointerId)?:0f)/density.toDouble()/1000.0/pitchRatio*horizontalSign)
                }else if(e.y>height-dp(64f)){
                    if(e.x<dp(180f))setNavigationProfile("iphone")
                    else if(e.x>width-dp(168f))chooseGallery(galleryFaces.indexOf(controller.state.face).coerceAtLeast(0))
                    else chooseGallery(gallery.page.roundToInt())
                }else{
                    val pitch=width*(1.0-.28*galleryProgress)
                    val i=(gallery.page+(e.x-width/2.0)/pitch.coerceAtLeast(1.0)*horizontalSign).roundToInt().coerceIn(0,galleryFaces.lastIndex)
                    // Weather face: a tap on the current face advances the complication page
                    // (manual paging per standby-1.mp4); other faces keep gallery selection.
                    if(controller.state.face==Face.WEATHER){
                        controller.dispatch(StandByEvent.WeatherPhase(1));invalidate()
                    }else chooseGallery(i)
                }
                finishInput();performClick();updateDescription()
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{gallery.settle();finishInput()}
        }
        return true
    }
    // Dedicated first-level portal transaction. The target collection is already
    // .69 / 50pt; only the selected source morphs. Never reuse the paging spring.
    private var configurationOpen=false
    /** Editing container backdrop: blurred snapshot of the live face behind the cards, the
     * Android stand-in for the original MTMaterialView "platters" material. */
    private var editorBackdrop:android.graphics.Bitmap?=null
    private var configurationFaces:List<Face> = OriginalStandByNavigation.clockFaces
    private var configurationCommittedFace=controller.state.face
    private val configurationMotion=OriginalConfigurationPortalMotion()
    private var configurationStartPage=0.0
    private var configurationEntryTouchBlocked=false
    private var configurationFinishPending=false
    private var configurationDetailPending=false
    private var configurationWidgetPending=false
    private var configurationPortalIndex=0
    private var configurationPortalFrom=RectF()
    private var configurationPortalTo=RectF()
    private var configurationPortalFromCorner=0f
    private var configurationPortalToCorner=0f
    private var configurationPortalNode:android.graphics.RenderNode?=null
    private var configurationPortalSourceAlpha=1f
    private var editorOriginCorner:Float?=null
    private var editorConfigurationMorphFrom:RectF?=null
    private var editorConfigurationMorphCorner=0f
    private var editorConfigurationMorphProgress=0f
    private var editorConfigurationMorphToDetail=true
    /** portal 收拢完成后直接进入该面容的编辑界面（原版没有中间的配置选择页）。 */
    private var detailEntryPending=false
    private var photoEditorOpen=false
    /** 详情编辑器收起后继续走 portal 退场，回到运行中的面容。 */
    private var exitConfigurationAfterEditor=false
    private val configurationPager=Pager(Axis.HORIZONTAL,0,configurationFaces.lastIndex){index->
        if(configurationFinishPending)finishConfigurationSelection(index)
        else if(configurationDetailPending)openConfigurationDetail(index)
        updateDescription()
    }
    private val configurationVisible get()=configurationOpen||configurationMotion.running
    private fun configurationPitch()=width*.69+dp(50f)
    private fun configurationRect(index:Int):RectF {
        val x=((index-configurationPager.page)*configurationPitch()*horizontalSign).toFloat()
        return RectF(width*.155f+x,height*.155f,width*.845f+x,height*.845f)
    }
    private fun configurationCardCorner():Float {
        // Original cell-local trait, zero fallback8/scaleInLayout, then card scale.
        val trait=deviceCorner()
        return if(trait>0f)trait*.69f else dp(8f)
    }
    /** 类别切换器的卡片几何 = portal 卡片几何（.69 + 同一圆角），进入时不会跳变。 */
    fun photoCardBounds():RectF=RectF(width*.155f,height*.155f,width*.845f,height*.845f)
    /** 面此刻真正在显示的那张照片（轮播/翻页后可能与配置里记住的不同）。 */
    fun currentPhotoAssetUri():String?=renderer.photos.photos.getOrNull(renderer.photos.selectedIndex)?.uri?.toString()
    fun photoCardCorner():Float=configurationCardCorner()
    /** 编辑器卡片调用：和面完全同一套照片处理（night 滤镜 + 可读性渐变）。 */
    fun drawPhotoCard(canvas:Canvas,bounds:RectF,bitmap:android.graphics.Bitmap,night:Boolean,gradientOpacity:Float=-1f)=renderer.drawPhotoCard(canvas,bounds,bitmap,night,gradientOpacity)
    val photoLegibilityOpacity get()=renderer.photoLegibilityOpacity
    fun onPhotoEditorOpened(){photoEditorOpen=true;updateDescription()}
    val photoCategoryEditorOpen get()=photoEditorOpen
    /** 编辑器结束：清掉待进入标记，并走同一个 portal 退场（卡片放大回整面）。 */
    fun exitPhotoConfiguration(){
        photoEditorOpen=false;detailEntryPending=false
        if(configurationOpen)requestConfigurationFinish(configurationPortalIndex)
    }
    private fun portalRect():RectF {
        val f=configurationMotion.sample.geometry
        val target=if(configurationMotion.sample.phase==OriginalConfigurationPortalMotion.Phase.ENTERING)
            configurationRect(configurationPortalIndex) else configurationPortalTo
        return RectF(configurationPortalFrom.left+(target.left-configurationPortalFrom.left)*f,
            configurationPortalFrom.top+(target.top-configurationPortalFrom.top)*f,
            configurationPortalFrom.right+(target.right-configurationPortalFrom.right)*f,
            configurationPortalFrom.bottom+(target.bottom-configurationPortalFrom.bottom)*f)
    }
    private fun portalCorner():Float {
        val f=configurationMotion.sample.geometry
        return configurationPortalFromCorner+(configurationPortalToCorner-configurationPortalFromCorner)*f
    }
    private fun captureConfigurationSource(index:Int){
        configurationPortalNode=null
        if(width<=0||height<=0||android.os.Build.VERSION.SDK_INT<29)return
        // Hardware recording preserves Solar/RenderEffect paths. A software Bitmap
        // Canvas would record its unsupported-hardware placeholder instead of source.
        // Native cross-process renderId/contextId remains an explicit Android adapter.
        val node=android.graphics.RenderNode("Duo.ConfigurationPortal.Source")
        node.setPosition(0,0,width,height);node.setClipToBounds(false)
        val recording=node.beginRecording(width,height)
        try{drawIn(recording,RectF(0f,0f,width.toFloat(),height.toFloat()),controller.forFace(configurationFaces[index]),live=true)}
        finally{node.endRecording()}
        configurationPortalNode=node
    }
    private fun configurationPortalLog(event:String){
        if((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return
        val a=configurationMotion.sample
        android.util.Log.d("DuoConfigPortal","$event uptimeMs=${SystemClock.uptimeMillis()} phase=${a.phase} face=${configurationFaces[configurationPortalIndex]} elapsedMs=${a.elapsedMs} geometry=${a.geometry} portal=${a.portalAlpha} controls=${a.controlsAlpha} footer=${a.footerAlpha} from=$configurationPortalFrom to=$configurationPortalTo interpolation=same_runtime_uikit_keyframe_adapter")
    }
    private fun buildEditorBackdrop(face:Face){
        editorBackdrop?.recycle();editorBackdrop=null
        if(width<=0||height<=0)return
        runCatching{
            val small=android.graphics.Bitmap.createBitmap(max(1,width/8),max(1,height/8),android.graphics.Bitmap.Config.ARGB_8888)
            val sc=Canvas(small);sc.scale(1f/8f,1f/8f)
            drawIn(sc,RectF(0f,0f,width.toFloat(),height.toFloat()),controller.forFace(face))
            val out=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(small,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
            small.recycle();editorBackdrop=out
        }
    }
    private fun openConfigurationSelection():Boolean {
        if(controller.editing||widgets.editing||calibratingCamera||galleryVisible)return false
        val face=if(!legacyCalendar&&outer.page.roundToInt()==Mode.CLOCKS.ordinal)
            OriginalStandByNavigation.clockFaces[clocks.page.roundToInt().coerceIn(0,OriginalStandByNavigation.clockFaces.lastIndex)] else controller.state.face
        configurationFaces=if(face in OriginalStandByNavigation.clockFaces)OriginalStandByNavigation.clockFaces else listOf(face)
        configurationCommittedFace=controller.state.face
        configurationPager.last=configurationFaces.lastIndex
        val index=configurationFaces.indexOf(face).coerceAtLeast(0)
        val item=if(face in OriginalStandByNavigation.clockFaces)navigationFrame.snapshot.clocks[index] else navigationFrame.snapshot.outer[outer.page.roundToInt().coerceIn(0,Mode.entries.lastIndex)]
        val scale=item.scale.toFloat()
        val x=if(item.axis==Axis.HORIZONTAL)(item.offsetPages*width*horizontalSign).toFloat()else 0f
        val y=if(item.axis==Axis.VERTICAL)(item.offsetPages*height).toFloat()else 0f
        configurationPortalFrom=RectF(width*(1-scale)/2+x,height*(1-scale)/2+y,width*(1+scale)/2+x,height*(1+scale)/2+y)
        configurationPortalFromCorner=if(item.running||item.targetProgress<1.0)deviceCorner()*scale else 0f
        configurationPortalIndex=index;configurationPortalSourceAlpha=item.presentationProgress.toFloat()
        if(OriginalStandByMotionPolicy.animationsEnabled(context))captureConfigurationSource(index)
        else configurationPortalNode=null
        outer.hold();clocks.hold();commitConfigurationFace(index)
        widgets.cancelTouchForOuterPaging();endCalendarPress();removeCallbacks(longPress)
        configurationPager.snap(index);configurationOpen=true
        buildEditorBackdrop(face)
        configurationPortalTo=configurationRect(index);configurationPortalToCorner=configurationCardCorner()
        configurationMotion.begin(true,System.nanoTime(),1f,0f,0f)
        if(!OriginalStandByMotionPolicy.animationsEnabled(context))configurationMotion.advance(System.nanoTime(),false)
        configurationFinishPending=false;configurationDetailPending=false;configurationWidgetPending=false
        configurationEntryTouchBlocked=pointerId>=0
        // 收拢完成后直接进该面容自己的编辑界面：照片面=类别切换器，其它面=样式/颜色/数字编辑器。
        detailEntryPending=face!=Face.WIDGETS
        editorOrigin=null;editorOriginCorner=null;editorConfigurationMorphFrom=null;editorReturnToConfiguration=false
        configurationPortalLog("begin")
        if(!configurationMotion.running)configurationPortalLog("complete-reduced-motion")
        // 减少动画时不会再有帧回调，直接进编辑器。
        if(!configurationMotion.running&&detailEntryPending){
            detailEntryPending=false
            val index=configurationPortalIndex
            post{openConfigurationDetail(index)}
        }
        startPagingFrames();updateDescription()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return true
    }
    private fun reverseConfigurationToSelection(){
        if(configurationMotion.sample.phase!=OriginalConfigurationPortalMotion.Phase.EXITING)return
        val rect=portalRect();val corner=portalCorner();val old=configurationMotion.sample
        configurationPortalSourceAlpha=configurationPortalSourceAlpha+(1f-configurationPortalSourceAlpha)*old.geometry
        configurationPortalFrom=rect;configurationPortalFromCorner=corner
        configurationPortalTo=configurationRect(configurationPortalIndex);configurationPortalToCorner=configurationCardCorner()
        configurationOpen=true
        configurationMotion.begin(true,System.nanoTime(),old.portalAlpha,old.controlsAlpha,old.footerAlpha)
        configurationPortalLog("reverse-begin");startPagingFrames()
    }
    private fun finishConfigurationSelection(index:Int){
        configurationFinishPending=false;configurationDetailPending=false
        detailEntryPending=false;photoEditorOpen=false
        val movingSame=configurationMotion.running&&configurationPortalIndex==index
        val rect=if(movingSame)portalRect()else configurationRect(index)
        val corner=if(movingSame)portalCorner()else configurationCardCorner()
        val old=configurationMotion.sample
        if(!movingSame&&OriginalStandByMotionPolicy.animationsEnabled(context))captureConfigurationSource(index)
        else if(movingSame)configurationPortalSourceAlpha=configurationPortalSourceAlpha+(1f-configurationPortalSourceAlpha)*old.geometry
        configurationPortalIndex=index;configurationPortalFrom=rect;configurationPortalFromCorner=corner
        configurationPortalTo=RectF(0f,0f,width.toFloat(),height.toFloat());configurationPortalToCorner=0f
        if(!movingSame)configurationPortalSourceAlpha=1f
        commitConfigurationFace(index)
        // Synchronize the hidden live presentation before handing the owner back.
        navigationFrame.advance(System.nanoTime(),outer.page,clocks.page,false)
        configurationOpen=false
        configurationMotion.begin(false,System.nanoTime(),if(movingSame)old.portalAlpha else 0f,old.controlsAlpha,old.footerAlpha)
        if(!OriginalStandByMotionPolicy.animationsEnabled(context))configurationMotion.advance(System.nanoTime(),false)
        editorReturnToConfiguration=false;editorOrigin=null;editorOriginCorner=null
        configurationPortalLog("begin");startPagingFrames();updateDescription()
    }
    private fun commitConfigurationFace(index:Int){
        val face=configurationFaces[index.coerceIn(0,configurationFaces.lastIndex)]
        controller.dispatch(StandByEvent.Select(face));legacyCalendar=face==Face.CALENDAR
        outer.snap(OriginalStandByNavigation.mode(face).ordinal)
        clocks.snap(OriginalStandByNavigation.clockIndex(controller.clockFace))
        refresh()
    }
    private fun requestConfigurationFinish(index:Int){
        configurationDetailPending=false;configurationFinishPending=true
        if(abs(configurationPager.page-index)<1e-6&&!configurationPager.running)finishConfigurationSelection(index)
        else configurationPager.settle(selected=index)
    }
    private fun requestConfigurationDetail(){
        configurationFinishPending=false;configurationDetailPending=true
        val index=configurationPager.page.roundToInt().coerceIn(0,configurationFaces.lastIndex)
        if(abs(configurationPager.page-index)<1e-6&&!configurationPager.running)openConfigurationDetail(index)
        else configurationPager.settle(selected=index)
    }
    private fun openConfigurationDetail(index:Int){
        configurationDetailPending=false
        val face=configurationFaces[index]
        // Duo Photos uses the same paged editor host, with injected photosContent.
        // Do not route it into the unshipped ordinary Ambient photo-page manager.
        if(face==Face.WORLD){
            Toast.makeText(context,"世界时钟详细编辑仍在移植",Toast.LENGTH_SHORT).show()
            finishConfigurationSelection(index);return
        }
        commitConfigurationFace(index)
        if(face==Face.WIDGETS){
            configurationWidgetPending=true;finishConfigurationSelection(index);return
        }
        if(face==Face.PHOTOS){android.util.Log.i("DuoPhotoCategory","openConfigurationDetail PHOTOS");photoEditorOpen=true;onPhotoCategoryEditor?.invoke();return}
        val inPortal=configurationMotion.running&&configurationPortalIndex==index
        editorOrigin=if(inPortal)portalRect()else configurationRect(index)
        editorOriginCorner=if(inPortal)portalCorner()else configurationCardCorner()
        // Detail takes the sole live owner NOW. Do not hold an old source recording
        // across edits; return maps from the current detail frame to its fixed card.
        configurationMotion.reset(selecting=true);configurationPortalNode=null;configurationOpen=true
        editorConfigurationMorphFrom=RectF(editorOrigin!!)
        editorConfigurationMorphCorner=editorOriginCorner!!
        editorConfigurationMorphProgress=editorProgress
        editorConfigurationMorphToDetail=true
        editorReturnToConfiguration=true
        val result=beginEditor()
        if(result==EditorEntryResult.UNAVAILABLE){
            editorOrigin=null;editorOriginCorner=null;editorReturnToConfiguration=false
            // 没有可用编辑器时回到运行面容，而不是停在一张没有 chrome 的卡片上。
            finishConfigurationSelection(index)
        }
    }
    private fun drawConfigurationSource(c:Canvas,rect:RectF,corner:Float,alpha:Float){
        if(alpha<=0f||rect.isEmpty)return
        val save=c.save();c.clipPath(OriginalContinuousShape.path(rect,corner))
        val layer=c.saveLayerAlpha(rect,(alpha.coerceIn(0f,1f)*255).roundToInt())
        val node=configurationPortalNode
        if(android.os.Build.VERSION.SDK_INT>=29&&node!=null&&c.isHardwareAccelerated){
            c.translate(rect.left,rect.top);c.scale(rect.width()/width,rect.height()/height)
            c.drawRenderNode(node)
        }else{
            // API28/no hardware recording: one live source only, no portal alpha
            // crossfade. This safe platform fallback is not native render mirroring.
            drawIn(c,rect,controller.forFace(configurationFaces[configurationPortalIndex]),live=true)
        }
        c.restoreToCount(layer);c.restoreToCount(save)
    }
    private fun editorConfigurationRect():RectF {
        val from=editorConfigurationMorphFrom?:editorOrigin?:RectF(0f,0f,width.toFloat(),height.toFloat())
        val target=if(editorConfigurationMorphToDetail)preview else configurationRect(configurationFaces.indexOf(controller.state.face).coerceAtLeast(0))
        val start=editorConfigurationMorphProgress
        val fraction=if(editorConfigurationMorphToDetail){
            if(start>=.99999f)1f else ((editorProgress-start)/(1f-start)).coerceIn(0f,1f)
        }else if(start<=.00001f)1f else ((start-editorProgress)/start).coerceIn(0f,1f)
        return RectF(from.left+(target.left-from.left)*fraction,from.top+(target.top-from.top)*fraction,
            from.right+(target.right-from.right)*fraction,from.bottom+(target.bottom-from.bottom)*fraction)
    }
    private fun editorConfigurationCorner():Float {
        val start=editorConfigurationMorphProgress
        val fraction=if(editorConfigurationMorphToDetail){
            if(start>=.99999f)1f else ((editorProgress-start)/(1f-start)).coerceIn(0f,1f)
        }else if(start<=.00001f)1f else ((start-editorProgress)/start).coerceIn(0f,1f)
        val target=if(editorConfigurationMorphToDetail)
            min(preview.width()/width,preview.height()/height)*(deviceCorner()+dp(12f)) else configurationCardCorner()
        return editorConfigurationMorphCorner+(target-editorConfigurationMorphCorner)*fraction
    }
    private fun drawConfigurationSelection(c:Canvas){
        val photoSession=configurationFaces.getOrNull(configurationPager.page.roundToInt().coerceIn(0,configurationFaces.lastIndex))==Face.PHOTOS
        // 照片面：编辑态底色就是类别切换器的 #1C1C1E（参考截图整屏纯色），不是模糊快照。
        if(photoSession)c.drawColor(0xFF1C1C1E.toInt())
        else {
            // Material backdrop first; the cards and controls sit above it.
            editorBackdrop?.let{ bmp -> if(!bmp.isRecycled) c.drawBitmap(bmp,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG)) }
            c.drawColor(Color.argb(150,0,0,0))
        }
        val sample=configurationMotion.sample
        val selected=configurationPager.page.roundToInt().coerceIn(0,configurationFaces.lastIndex)
        configurationFaces.forEachIndexed {index,face->
            val rect=configurationRect(index)
            if(rect.right<0||rect.left>width)return@forEachIndexed
            // AMUIPosterSwitcherSettings: exiting card scales to horizontal 0.618 / vertical
            // 0.885 with target opacity 0; the center card stays 1. Distance drives both.
            val distance=abs(index-configurationPager.page).toFloat().coerceIn(0f,1f)
            val target=if(configurationPager.axis==Axis.HORIZONTAL)0.618f else 0.885f
            val cardScale=1f-(1f-target)*distance
            val cardAlpha=(1f-distance).coerceIn(0f,1f)
            if(cardAlpha<=0.01f)return@forEachIndexed
            val card=RectF(rect.centerX()-rect.width()/2f*cardScale,rect.centerY()-rect.height()/2f*cardScale,
                rect.centerX()+rect.width()/2f*cardScale,rect.centerY()+rect.height()/2f*cardScale)
            if(sample.running&&index==configurationPortalIndex){
                // Same recorded source under the portal stays opaque: complementary
                // srcOver alphas would create a 75% dark valley at a=.5.
                // API28 fallback skips this reference so only ONE live draw remains.
                if(configurationPortalNode!=null&&c.isHardwareAccelerated)
                    drawConfigurationSource(c,card,configurationCardCorner(),1f)
            }else{
                val saved=c.saveLayerAlpha(card,(cardAlpha*255f).roundToInt());c.clipPath(OriginalContinuousShape.path(card,configurationCardCorner()))
                drawIn(c,card,controller.forFace(face),live=true);c.restoreToCount(saved)
            }
        }
        if(sample.running){
            val sourceAlpha=configurationPortalSourceAlpha+(1f-configurationPortalSourceAlpha)*sample.geometry
            val alpha=if(configurationPortalNode!=null&&c.isHardwareAccelerated)sample.portalAlpha*sourceAlpha else sourceAlpha
            drawConfigurationSource(c,portalRect(),portalCorner(),alpha)
        }
        // 照片面没有"配置选择"这一层：原版长按是"卡片缩到中间，编辑内容直接在卡片里展开"，
        // 不会先出现 取消/自定义/完成 那一行。这里只为照片面抑制这层 chrome，
        // 其它面容的选择页保持不变。
        val photoSelected=configurationFaces.getOrNull(selected)==Face.PHOTOS
        // 原版没有「编辑 · 面容 / 取消 / 自定义 / 完成」这一层：长按后卡片缩到中间，
        // 直接进入该面容自己的编辑界面（照片面 = 类别切换器，其它面 = 样式/颜色/数字 + ×/✓）。
        // 之前这里画的 footer 是 Android 侧自造的中间层，v177 删除。
    }
    private fun configurationCardContains(rect:RectF,corner:Float,x:Float,y:Float):Boolean {
        if(!rect.contains(x,y))return false
        val clip=android.graphics.Region(floor(rect.left).toInt(),floor(rect.top).toInt(),
            ceil(rect.right).toInt(),ceil(rect.bottom).toInt())
        return android.graphics.Region().apply {
            setPath(OriginalContinuousShape.path(rect,corner),clip)
        }.contains(floor(x).toInt(),floor(y).toInt())
    }
    private fun configurationItemAt(x:Float,y:Float):Int? {
        // The source portal is drawn above the collection; use its presentation bounds.
        if(configurationMotion.running&&configurationMotion.sample.portalAlpha>0f&&
            configurationCardContains(portalRect(),portalCorner(),x,y))return configurationPortalIndex
        return configurationFaces.indices.lastOrNull { index ->
            configurationCardContains(configurationRect(index),configurationCardCorner(),x,y)
        }
    }
    private fun onConfigurationTouch(e:MotionEvent):Boolean {
        if(configurationEntryTouchBlocked){
            if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL){configurationEntryTouchBlocked=false;finishInput()}
            return true
        }
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                finishInput();configurationPager.hold();reverseConfigurationToSelection();configurationOpen=true
                configurationFinishPending=false;configurationDetailPending=false
                configurationStartPage=configurationPager.raw();downX=e.x;downY=e.y;pointerId=e.getPointerId(0)
                tracker=VelocityTracker.obtain().also{it.addMovement(e)};startPagingFrames()
            }
            MotionEvent.ACTION_MOVE->{
                val pi=e.findPointerIndex(pointerId);if(pi<0)return true
                tracker?.addMovement(e);val dx=e.getX(pi)-downX;val dy=e.getY(pi)-downY
                gestureAxis=OriginalStandByNavigation.lock(gestureAxis,dx.toDouble(),dy.toDouble(),slop.toDouble())
                if(gestureAxis!=Axis.NONE)moving=true
                if(gestureAxis==Axis.HORIZONTAL)configurationPager.drag(configurationStartPage-dx/configurationPitch().coerceAtLeast(1.0)*horizontalSign)
            }
            MotionEvent.ACTION_UP->{
                tracker?.addMovement(e)
                if(moving){
                    tracker?.computeCurrentVelocity(1000)
                    val ratio=(configurationPitch()/width.coerceAtLeast(1)).coerceAtLeast(.01)
                    configurationPager.settle(if(gestureAxis==Axis.HORIZONTAL)-(tracker?.getXVelocity(pointerId)?:0f)/density.toDouble()/1000.0/ratio else 0.0)
                }else{
                    // Only a visible card owns selection. Blank space, gaps and clipped
                    // rounded corners are not aliases for the nearest card's center.
                    configurationItemAt(e.x,e.y)?.let { selected ->
                        if(selected==configurationPager.page.roundToInt())requestConfigurationDetail()
                        else configurationPager.settle(selected=selected)
                    }
                }
                finishInput();performClick();updateDescription()
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{configurationPager.settle();finishInput()}
        }
        return true
    }
    private var pagingScheduled=false
    private val pagingFrame=object:Choreographer.FrameCallback {
        override fun doFrame(nanos:Long){
            val galleryWasVisible=galleryVisible
            val configurationWasVisible=configurationVisible
            pagingScheduled=false;clocks.advance(nanos);outer.advance(nanos);gallery.advance(nanos);configurationPager.advance(nanos);photoPager.advance(nanos)
            renderer.photoVerticalDragFraction=(1.0-photoPager.page).toFloat()
            facePagingFrame.advance(nanos,gallery.page,OriginalStandByMotionPolicy.animationsEnabled(context))
            galleryProgress=galleryReveal.sample(if(galleryOpen)1.0 else 0.0,nanos,OriginalStandByMotionPolicy.animationsEnabled(context))
            val portalWasRunning=configurationMotion.running
            if(editorReturnToConfiguration&&(controller.editing||editorExiting))configurationMotion.hold(nanos)
            else configurationMotion.advance(nanos,OriginalStandByMotionPolicy.animationsEnabled(context))
            if(portalWasRunning&&!configurationMotion.running){
                configurationPortalLog("complete")
                configurationPortalNode=null // Bounded source owner ends at transaction completion.
                // portal 收拢完毕 → 该面容的编辑界面接管这张卡片（同一 RectF/圆角）。
                // 照片面 = 类别切换器；其它面容 = 样式/颜色/数字 编辑器。
                android.util.Log.i("DuoEditEntry","portal complete pending=$detailEntryPending open=$configurationOpen face=${configurationFaces.getOrNull(configurationPortalIndex)}")
                if(detailEntryPending&&configurationOpen&&
                    configurationMotion.sample.phase==OriginalConfigurationPortalMotion.Phase.SELECTING){
                    detailEntryPending=false
                    openConfigurationDetail(configurationPortalIndex)
                }
            }
            if(configurationWidgetPending&&!configurationVisible){configurationWidgetPending=false;widgets.beginEditing(OriginalWidgetStackHost.Side.LEFT);invalidate()}
            if(galleryWasVisible!=galleryVisible||configurationWasVisible!=configurationVisible)updateDescription()
            navigationFrame.advance(nanos,outer.page,clocks.page,OriginalStandByMotionPolicy.animationsEnabled(context))
            invalidate()
            if(outer.running||clocks.running||photoPager.running||gallery.running||galleryReveal.running||facePagingFrame.running||configurationPager.running||configurationMotion.running||navigationFrame.snapshot.running)startPagingFrames()
        }
    }
    private fun startPagingFrames(){if(attached&&windowVisibility==VISIBLE&&!pagingScheduled){pagingScheduled=true;Choreographer.getInstance().postFrameCallback(pagingFrame)}}
    private fun stopPaging(){outer.hold();clocks.hold();photoPager.hold();gallery.hold();configurationPager.hold();pagingScheduled=false;Choreographer.getInstance().removeFrameCallback(pagingFrame)}
    private val navigationFrame=OriginalNavigationFrame(outer.page,clocks.page)
    private var attached=false
    private var downX=0f;private var downY=0f
    private var startPage=0.0;private var startClockPage=0.0
    private var moving=false;private var tracker:VelocityTracker?=null
    private var gestureAxis=Axis.NONE
    private var pointerId=-1
    private var widgetGestureCaptured=false
    private var blockUntilUp=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val longPress=Runnable{if(!moving&&!controller.editing&&pointerId>=0){
        blockUntilUp=true;requestEditorEntry()
    }}
    private val descriptionFormat=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val tick=object:Runnable{override fun run(){invalidate();updateDescription();schedule()}}
    init {
        renderer.widgetController=widgets
        if(!controller.referenceMode)widgets.restoreState(widgetPrefs.getString("stacks",null))
        setWillNotDraw(false);setBackgroundColor(Color.BLACK);isFocusable=true;isClickable=true;isLongClickable=true
        addView(chrome,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        chrome.addView(toolbar);chrome.addView(optionsRegion)
        optionsRegion.addView(optionsHost,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT,android.view.Gravity.CENTER))
        toolbar.addView(button("×","取消编辑",false){closeEditor(false)},LinearLayout.LayoutParams(dp(48f).toInt(),dp(48f).toInt()))
        listOf("样式","颜色","数字","组件").forEachIndexed{i,title->
            val b=button(title,"${title}选项",false){controller.editorTab=i;rebuildOptions()};b.textSize=14f;b.background=null
            tabButtons.add(b);toolbar.addView(b,LinearLayout.LayoutParams(0,dp(48f).toInt(),1f))
        }
        toolbar.addView(button("✓","确认并保存",true){closeEditor(true)},LinearLayout.LayoutParams(dp(48f).toInt(),dp(48f).toInt()))
        chrome.addView(photoActionBar);chrome.addView(photoTitleScroll)
        photoActionBar.addView(button("×","取消编辑",false){closeEditor(false)},LayoutParams(dp(48f).toInt(),dp(48f).toInt(),android.view.Gravity.BOTTOM or android.view.Gravity.START))
        photoActionBar.addView(button("✓","确认并保存",true){closeEditor(true)},LayoutParams(dp(48f).toInt(),dp(48f).toInt(),android.view.Gravity.BOTTOM or android.view.Gravity.END))
        photoActionBar.addView(photoAdjunctHost,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        photoTitleScroll.addView(photoTitleRail,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,android.view.Gravity.CENTER_HORIZONTAL))
        OriginalPhotosPosterEditor.properties.forEachIndexed{i,property->
            val b=option(property.titleZh,false){controller.editorTab=i;rebuildOptions()}.apply{
                contentDescription="${property.titleZh}选项";setSingleLine();textSize=17f;typeface=fonts.semibold
                minHeight=0;minimumHeight=0
            }
            photoTabButtons.add(b)
            // Native FaceEditor title HStack: spacing70, Font.body.semibold.
            // Keep text intrinsic: a 48dp row would move the body baseline downward.
            photoTitleRail.addView(b,LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply{
                if(i>0)marginStart=dp(70f).roundToInt()
            })
        }
        chrome.visibility=if(controller.editing)VISIBLE else GONE;chrome.alpha=editorProgress
        addView(calibrationControls,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));setupCalibrationControls()
        rebuildOptions();updateDescription()
    }
    private fun button(label:String,description:String,blue:Boolean=false,action:()->Unit)=Button(context).apply{
        text=label;contentDescription=description;typeface=fonts.semibold;includeFontPadding=false;stateListAnimator=null;isAllCaps=false;textSize=18f;setTextColor(Color.WHITE);setPadding(0,0,0,0);minWidth=0;minimumWidth=0;minHeight=dp(48f).toInt();minimumHeight=dp(48f).toInt()
        background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(blue)Color.rgb(0,122,221)else Color.rgb(30,30,32))}
        setOnClickListener{action()}
    }
    private fun option(label:String,selected:Boolean,action:()->Unit)=button(label,label,false,action).apply{
        textSize=15f;background=null;setTextColor(if(selected)Color.WHITE else Color.rgb(145,145,150))
    }
    override fun onMeasure(ws:Int,hs:Int){
        // Child sizes must be set BEFORE measurement, not inside onSizeChanged/layout.
        // Force the full parent size even if the window still reports insets; otherwise the
        // face keeps dark strips where the status/nav bars were.
        val w=MeasureSpec.getSize(ws)
        val h=MeasureSpec.getSize(hs)
        layoutEditor(w,h);super.onMeasure(ws,hs);setMeasuredDimension(w,h)
    }
    private fun layoutEditor(w:Int,h:Int){
        // Keep both landscape camera edges clear; calibration controls must not cover the ring.
        val panelWidth=minOf((w-dp(48f)).toInt(),dp(600f).toInt(),if(w>h)(w*.64f).toInt()else w)
        calibrationControls.layoutParams=LayoutParams(panelWidth,LayoutParams.WRAP_CONTENT,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(30f).toInt()}
        // Toolbar dimensions remain an explicit Android adapter. They do not
        // subtract from the native GeometryReader proposal or move its center.
        val pad=dp(16f)
        toolbar.layoutParams=LayoutParams(w-(pad*2).toInt(),dp(48f).toInt()).apply{leftMargin=pad.toInt();topMargin=dp(12f).toInt()}
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val safe=rootWindowInsets?.let{insets->
            if(android.os.Build.VERSION.SDK_INT>=30){
                val q=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                if(rtl)q.left else q.right
            }else 0 // API28/29 decor already fits the content View; do not subtract root bars twice.
        }?:0
        val proposal=OriginalFaceEditorLayout.resolve(w/density.toDouble(),h/density.toDouble(),configuredEditorFaceScale,safe/density.toDouble(),rtl)
        val scene=proposal.preview
        preview.set((scene.x*density).toFloat(),(scene.y*density).toFloat(),((scene.x+scene.width)*density).toFloat(),((scene.y+scene.height)*density).toFloat())
        val region=proposal.optionsRegion
        // 原版属性面板在 titled 容器头部（标题+✕/✓）之下：这里把选项区从工具栏下方开始，
        // 否则第一张样式缩略图会被 ✓ 压住（真机截图里 ✓ 与缩略图重叠）。
        val headerPx=(dp(12f)+dp(48f)+dp(8f)).roundToInt()
        optionsRegion.layoutParams=LayoutParams((region.width*density).roundToInt(),(h-headerPx).coerceAtLeast(0),
            android.view.Gravity.TOP or android.view.Gravity.LEFT).apply{
            leftMargin=(region.x*density).roundToInt();topMargin=headerPx
        }
        optionsHost.maximumHeight=((proposal.optionsMaxHeight*density).roundToInt()-headerPx).coerceAtLeast(0)
        if(controller.displayed.face==Face.PHOTOS){
            // Native iOS PlatformIsV68 is false. The action HStack and its
            // adjunct center overlay occupy the bottom preview-exterior region.
            // Q=sH+12; its fixed height is (H-Q)/2, not the property-panel height.
            val actionHeight=((h-scene.height*density-dp(12f))/2.0).coerceAtLeast(0.0).roundToInt()
            photoActionBar.layoutParams=LayoutParams(w-(pad*2).toInt(),actionHeight,android.view.Gravity.BOTTOM or android.view.Gravity.LEFT).apply{leftMargin=pad.toInt()}
            // FaceEditor title rail: padding(top:48), intrinsic centered HStack.
            // The action 48dp touch height and legacy 16dp edge inset are Android
            // adapters; the original glass/button glyph metrics remain pending.
            photoTitleScroll.layoutParams=LayoutParams(w,LayoutParams.WRAP_CONTENT,android.view.Gravity.TOP).apply{topMargin=dp(48f).roundToInt()}
        }

    }
    /** 每个面容的编辑属性来自该面容自己的扩展（见 docs/research/STANDBY_EDIT_MODEL_20261025.md）：
     *  DIGITAL  = OriginalDigitalEditor.tabs（颜色/字体/组件；原版没有样式页）
     *  ANALOG   = 样式/颜色/数字（SimpleClockPosterExtension 只有 STYLE/FONT/NUMERALS，没有小组件属性）
     *  CALENDAR = 样式/颜色/字体/小组件（CalendarPoster EditablePoster 4 项）
     *  FLOAT/SOLAR 仍待定：provider 未钉死，暂不改（不许按猜测加/删页）
     */
    private fun activeEditorTabs()=when(controller.displayed.face){
        Face.DIGITAL->OriginalDigitalEditor.tabs
        Face.ANALOG->intArrayOf(0,1,2)
        Face.FLOAT,Face.SOLAR->intArrayOf(0,3)
        else->intArrayOf(0,1,2,3)
    }
    private fun rebuildOptions(){
        val photos=controller.displayed.face==Face.PHOTOS
        toolbar.visibility=if(photos)GONE else VISIBLE
        photoActionBar.visibility=if(photos)VISIBLE else GONE
        photoTitleScroll.visibility=if(photos)VISIBLE else GONE
        photoAdjunctHost.removeAllViews()
        val tabs=activeEditorTabs()
        if(controller.editorTab !in tabs)controller.editorTab=tabs.first()
        tabButtons.forEachIndexed{i,b->
            b.visibility=if(i in tabs)VISIBLE else GONE
            val title=if(controller.displayed.face==Face.PHOTOS)
                OriginalPhotosPosterEditor.properties[i].titleZh
            else when(i){0->"样式";1->"颜色";2->if(controller.displayed.face!=Face.ANALOG)"字体" else "数字";else->if(controller.displayed.face==Face.CALENDAR)"小组件" else "组件"}
            b.text=title;b.contentDescription="${title}选项"
        }
        tabButtons.forEachIndexed{i,b->b.setTextColor(if(i==controller.editorTab)Color.WHITE else Color.rgb(105,105,110));b.isSelected=i==controller.editorTab}
        photoTabButtons.forEachIndexed{i,b->
            b.setTextColor(if(i==controller.editorTab)Color.WHITE else Color.argb(77,255,255,255))
            b.isSelected=i==controller.editorTab
        }
        optionsHost.removeAllViews()
        val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        optionsHost.addView(column,FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        val state=controller.displayed;val o=state.options
        fun add(v:View,height:Float=52f){column.addView(v,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,dp(height).toInt()).apply{bottomMargin=dp(10f).toInt()})}
        fun change(block:(FaceOptions)->FaceOptions){
            val before=controller.displayed.options
            controller.edit(block);rebuildOptions();refresh()
            // 样式变化才过渡；颜色/数字不整盘重画。
            if(controller.displayed.options.style!=before.style)animateStyleChange(before)
        }
        fun addNumeralsMenu(){
            // Analog empty group/accessory: 0x100003f18 / 0x1000030c8; Digital uses the same numeral order.
            // Native numeralsPickerMenu is an accessory, not inline property children.
            // Android PopupMenu is a platform adapter; Apple material/motion still pending.
            val numeralName=OriginalAnalogEditor.numeralLabels[OriginalAnalogEditor.numeralOrder.indexOf(o.nativeNumerals).coerceAtLeast(0)]
            val menuButton=option("文字 · $numeralName",false){}
            menuButton.contentDescription="数字体系菜单"
            menuButton.setOnClickListener{
                android.widget.PopupMenu(context,menuButton).apply{
                    OriginalAnalogEditor.numeralOrder.forEachIndexed{position,native->
                        menu.add(0,native,position,OriginalAnalogEditor.numeralLabels[position]).apply{isCheckable=true;isChecked=native==o.nativeNumerals}
                    }
                    setOnMenuItemClickListener{item->change{it.copy(nativeNumerals=item.itemId)};true}
                    show()
                }
            };add(menuButton,48f)
        }
        if(state.face==Face.PHOTOS){
            // Provider getter 0x1e345d9f0 orders injected photosContent before
            // timeLayout, Color, Font. Never fall through to calendar/analog UI.
            val property=OriginalPhotosPosterEditor.properties[controller.editorTab]
            if(OriginalPhotosPosterEditor.editingUnavailable(property,onPhotoTimeLayout())){
                add(android.widget.TextView(context).apply{
                    text="此样式无法自定义。";typeface=fonts.ui;textSize=15f;setTextColor(Color.GRAY)
                },72f)
            }else if(property==OriginalPhotosPosterEditor.Property.CONTENT){
                // Original empty property children + adjunct Select accessory,
                // not an add-page button or ordinary Ambient photo manager.
                // Android adapter: the native Content property exposes only the Select
                // accessory; the 时光 album feed is our data source, added beside it.
                val contentRow=android.widget.LinearLayout(context).apply{orientation=android.widget.LinearLayout.HORIZONTAL}
                contentRow.addView(option("选择照片",false){onPhotoEdit()}.apply{
                    setTextColor(Color.WHITE)
                    contentDescription="手动选择照片。${onPhotoContentSummary()}"
                },android.widget.LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,dp(48f).toInt()))
                contentRow.addView(View(context).apply{setBackgroundColor(Color.TRANSPARENT)},android.widget.LinearLayout.LayoutParams(dp(24f).toInt(),1))
                contentRow.addView(option("选择影集",false){onPhotoAlbums()}.apply{
                    setTextColor(Color.WHITE)
                    contentDescription="打开照片随机显示，选择相簿和频率"
                },android.widget.LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,dp(48f).toInt()))
                photoAdjunctHost.addView(contentRow,LayoutParams(LayoutParams.WRAP_CONTENT,dp(48f).toInt(),android.view.Gravity.CENTER))
            }else if(property==OriginalPhotosPosterEditor.Property.POSITION){
                // Source range is exactly 1,2,11,0. This edits the same draft as
                // Content; the live Photos preview receives the selected layout.
                // Text controls are Android accessibility adapters. Do not fake
                // native TimeLayoutSelectorView swatches with another poster.
                OriginalPhotosPosterEditor.supportedPositions.forEach { position ->
                    add(option(position.labelZh,onPhotoTimeLayout()==position.raw){
                        onPhotoTimeLayoutChange(position.raw)
                    }.apply{
                        contentDescription="位置：${position.labelZh}"
                        // The native dynamic layout resolver is still being ported.
                        // Do not accept a selection whose main preview is unchanged.
                        isEnabled=position.raw==1 || position.raw==0
                        alpha=if(isEnabled)1f else .35f
                    },48f)
                }
            }else if(property==OriginalPhotosPosterEditor.Property.NUMERALS){
                val current=OriginalPhotosPosterEditor.numberSystemCases.firstOrNull{it.raw==onPhotoNumberSystem()}
                    ?: OriginalPhotosPosterEditor.NumberSystem.ARABIC_WESTERN
                // Native NumberSystemSelectorView is a globe-labelled Menu with
                // a Script section, not a typeface grid or Analog's raw enum.
                val menuButton=option("文字 · ${current.labelZh}",false){}
                menuButton.setCompoundDrawablesRelativeWithIntrinsicBounds(photoScriptGlobe(),null,null,null)
                menuButton.compoundDrawablePadding=dp(8f).toInt()
                menuButton.contentDescription="文字菜单。${current.labelZh}"
                menuButton.setOnClickListener{
                    android.widget.PopupMenu(context,menuButton).apply{
                        menu.add(1,-1,0,OriginalPhotosPosterEditor.numberSystemSectionTitleZh).isEnabled=false
                        OriginalPhotosPosterEditor.numberSystemCases.forEachIndexed { index,script ->
                            menu.add(2,script.raw,index+1,script.labelZh).apply{
                                isCheckable=true;isChecked=script.raw==onPhotoNumberSystem()
                            }
                        }
                        menu.setGroupCheckable(2,true,true)
                        setOnMenuItemClickListener { item ->
                            item.groupId==2 && onPhotoNumberSystemChange(item.itemId)
                        }
                        // Dismiss without choosing does not change the draft.
                        show()
                    }
                }
                menuButton.setTextColor(Color.WHITE)
                photoAdjunctHost.addView(menuButton,LayoutParams(LayoutParams.WRAP_CONTENT,dp(48f).toInt(),android.view.Gravity.CENTER))
            }
            // ColorEditorStyle's glass material remains unimplemented; no
            // substitute RGB palette or unrelated provider controls are shown.
            if(width>0)layoutEditor(width,height)
            return
        }
        when(controller.editorTab){
            0->{
                // Only known analog layouts; do not offer the fabricated compressed digital page.
                val labels=when(state.face){Face.ANALOG->OriginalAnalogEditor.styleLabels;Face.CALENDAR->OriginalCalendarEditor.styleLabels;Face.DIGITAL->listOf("原版数字钟");Face.WORLD->listOf("原版地图研究预览");Face.WIDGETS->listOf("双列小组件");Face.FLOAT->listOf("Float · 原版蓝色");Face.SOLAR->listOf("Sky · 太阳位置未接入");Face.PHOTOS->listOf("小米精选 · 来源预览");Face.WEATHER->listOf("天气 · 原版 Complication")}
                labels.forEachIndexed{position,label->
                    val i=if(state.face==Face.ANALOG)OriginalAnalogEditor.styleOrder[position]else position
                    val choice=state.copy(options=o.copy(style=i))
                    val item=object:View(context){
                        private val itemBounds=RectF()
                        override fun onMeasure(ws:Int,hs:Int){
                            val w=MeasureSpec.getSize(ws)
                            // Explicit Android host inputs; actual Duo picker proposal is not yet measured.
                            val screen=OriginalStylePickerSizing.Size(this@StandByView.width/density.toDouble(),this@StandByView.height/density.toDouble())
                            val item=OriginalStylePickerSizing.itemSize(0,w/density.toDouble(),screen)
                            // Native computation stays unclamped; only Android pixel dimensions need protection.
                            val iw=(item.width*density).coerceAtLeast(0.0).toFloat()
                            val ih=(item.height*density).coerceAtLeast(0.0).toFloat()
                            itemBounds.set((w-iw)/2f,dp(4f),(w+iw)/2f,ih+dp(4f))
                            setMeasuredDimension(w,ceil((ih+dp(8f)).toDouble()).toInt())
                        }
                        override fun onDraw(c:Canvas){
                            val b=RectF(itemBounds);val shape=OriginalContinuousShape.path(b,dp(8f))
                            val save=c.save();c.clipPath(shape);c.drawColor(Color.argb(43,255,255,255))
                            b.inset(dp(3f),dp(3f));val scene=RectF();fitScene(scene,b,this@StandByView.width,this@StandByView.height)
                            c.clipPath(OriginalContinuousShape.path(b,dp(5f)));drawIn(c,scene,choice);c.restoreToCount(save)
                            if(o.style==i){val edge=RectF(itemBounds);edge.inset(-dp(3f),-dp(3f));val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(2f)};c.drawPath(OriginalContinuousShape.path(edge,dp(11f)),paint)}
                        }
                    }.apply{contentDescription=label;isClickable=true;isFocusable=true;setOnClickListener{change{it.copy(style=i)}}}
                    column.addView(item,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply{
                        // 原版 PosterKit INTER_ITEM_SPACING = 12（init 0x1e3882f54）。
                        // 之前这里减了 8，是自写的挤压，已去掉。
                        bottomMargin=dp(OriginalStylePickerSizing.INTER_ITEM_SPACING.toFloat()).roundToInt()
                    })
                }
            }
            1->{
                val colors=if(state.face==Face.ANALOG)IntArray(8){OriginalAnalogParameters.colors(it,false).background}else if(state.face==Face.DIGITAL)IntArray(OriginalDigitalColors.ids.size){OriginalDigitalColors.colors(it,false).background}else IntArray(OriginalCalendarColors.ids.size){OriginalCalendarColors.colors(it,false).background}
                val names=if(state.face==Face.ANALOG)OriginalAnalogParameters.labels else if(state.face==Face.DIGITAL)OriginalDigitalColors.labels else OriginalCalendarColors.ids
                val selectedColor=when(state.face){Face.DIGITAL->o.digitalLook;Face.CALENDAR->o.calendarLook;else->o.palette}
                repeat((colors.size+2)/3){row->val line=LinearLayout(context)
                    repeat(3) swatch@{col->val i=row*3+col;if(i>=colors.size){line.addView(View(context),LinearLayout.LayoutParams(0,dp(48f).toInt(),1f));return@swatch};val b=button(if(selectedColor==i)"✓" else "",names[i]){change{when(state.face){Face.DIGITAL->it.copy(digitalLook=i);Face.CALENDAR->it.copy(calendarLook=i);else->it.copy(palette=i)}}}
                        b.setTextColor(Color.BLACK);b.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(colors[i]);if(selectedColor==i)setStroke(dp(3f).toInt(),Color.WHITE)}
                        line.addView(b,LinearLayout.LayoutParams(0,dp(48f).toInt(),1f).apply{marginEnd=dp(5f).toInt()})
                    };add(line,48f)
                }
                val line=LinearLayout(context)
                for(dark in listOf(false,true))line.addView(option(if(dark)"深色" else "浅色",o.dark==dark){change{it.copy(dark=dark)}},LinearLayout.LayoutParams(0,dp(48f).toInt(),1f).apply{marginEnd=dp(4f).toInt()})
                add(line,48f)
                add(option("夜间红色  ${if(state.night)"开" else "关"}",state.night){controller.editNight();rebuildOptions();refresh()},48f)
            }
            2->{
                if(state.face==Face.ANALOG){
                    if(o.style==2){
                        add(android.widget.TextView(context).apply{text="此样式无法自定义。";typeface=fonts.ui;textSize=15f;setTextColor(Color.GRAY)},72f)
                    }else{
                        addNumeralsMenu()
                    }
                }else if(state.face==Face.DIGITAL){
                    addNumeralsMenu()
                    OriginalDigitalEditor.typefaceLabels.forEachIndexed{i,label->
                        val choice=state.copy(options=o.copy(digitalTypeface=i))
                        val item=object:View(context){
                            override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);setMeasuredDimension(w,(w.toFloat()*this@StandByView.height/this@StandByView.width.coerceAtLeast(1)).roundToInt())}
                            override fun onDraw(c:Canvas){
                                val b=RectF(0f,0f,width.toFloat(),height.toFloat());val save=c.save()
                                c.clipPath(OriginalContinuousShape.path(b,dp(8f)));c.drawColor(Color.argb(43,255,255,255));b.inset(dp(3f),dp(3f))
                                c.clipPath(OriginalContinuousShape.path(b,dp(5f)));val scene=RectF();fitScene(scene,b,this@StandByView.width,this@StandByView.height);drawIn(c,scene,choice);c.restoreToCount(save)
                                if(o.digitalTypeface==i){val edge=RectF(0f,0f,width.toFloat(),height.toFloat());edge.inset(dp(1f),dp(1f));val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(2f)};c.drawPath(OriginalContinuousShape.path(edge,dp(11f)),paint)}
                            }
                        }.apply{contentDescription=label;isClickable=true;isFocusable=true;setOnClickListener{change{it.copy(digitalTypeface=i)}}}
                        add(item,94f)
                    }
                }else if(state.face==Face.CALENDAR){
                    OriginalCalendarEditor.typefaceOrder.forEachIndexed { position,native ->
                        val label=OriginalCalendarEditor.typefaceLabels[position]
                        // Use recovered original font, not Android serif/monospace substitutes.
                        // Original TextSwatch.body0x2556216e0 formats12. Container/metrics remain Android adapter.
                        val item=object:View(context){
                            override fun onDraw(c:Canvas){
                                val edge=RectF(dp(2f),dp(2f),width-dp(2f),height-dp(2f))
                                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(30,255,255,255)}
                                c.drawPath(OriginalContinuousShape.path(edge,dp(8f)),paint)
                                paint.color=Color.WHITE;paint.typeface=fonts.calendar(native);paint.textSize=dp(48f);paint.textAlign=Paint.Align.CENTER
                                val fm=paint.fontMetrics;c.drawText("12",width/2f,height/2f-(fm.ascent+fm.descent)/2f,paint)
                                if(o.calendarTypeface==native){paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f);c.drawPath(OriginalContinuousShape.path(edge,dp(8f)),paint)}
                            }
                        }.apply{contentDescription=label;isClickable=true;isFocusable=true;setOnClickListener{change{it.copy(calendarTypeface=native)}}}
                        add(item,94f)
                    }
                }
            }
            3->{
                add(option(if(duoProfile)"导航 · Duo 独立钟面" else "导航 · 普通 iPhone 三类别",false){closeEditor(false);setNavigationProfile(if(duoProfile)"iphone"else"duo")},52f)
                if(state.face==Face.CALENDAR){
                    val policy=OriginalCalendarWidgetOption
                    if(!policy.canEdit(o.style)){
                        add(android.widget.TextView(context).apply{
                            text=policy.MONTH_UNAVAILABLE;typeface=fonts.ui;textSize=15f;setTextColor(Color.GRAY)
                        },72f)
                    }else{
                        val formats=policy.dateFormats(OriginalCalendarHeader.dayDoesNotComeFirst())
                        val headerButton=option("标题 · ${policy.selectionTitle(o.calendarHeader)}",false){}
                        headerButton.contentDescription="日历标题：${policy.selectionTitle(o.calendarHeader)}"
                        headerButton.setOnClickListener{
                            // Android PopupMenu adapts the proven choices, not native gallery material/layout.
                            android.widget.PopupMenu(context,headerButton).apply{
                                formats.forEachIndexed{index,format->
                                    menu.add(0,index,index,format.title()).apply{
                                        isCheckable=true;isChecked=o.calendarHeader==CalendarHeaderSelection.DateFormat(format)
                                    }
                                }
                                setOnMenuItemClickListener{item->
                                    change{it.copy(calendarHeader=CalendarHeaderSelection.DateFormat(formats[item.itemId]))};true
                                }
                                show()
                            }
                        };add(headerButton,72f)
                        // The only native provider is weather.conditions; neither invent data nor offer empty.
                        add(android.widget.TextView(context).apply{
                            text=policy.WEATHER_UNAVAILABLE;typeface=fonts.ui;textSize=14f;setTextColor(Color.GRAY)
                        },72f)
                    }
                }else{
                    if(state.face !in listOf(Face.FLOAT,Face.SOLAR))
                        add(option("时间  ${if(o.showTime)"开" else "关"}",o.showTime){change{it.copy(showTime=!it.showTime)}},52f)
                    add(option("充电预览",false){previewCurrentBattery()},56f)
                    add(option("镜头圆环校准",false){calibrateCamera()},52f)
                    if(state.face !in listOf(Face.FLOAT,Face.SOLAR)){
                        add(option("日期  ${if(o.showDate)"开" else "关"}",o.showDate){change{it.copy(showDate=!it.showDate)}},52f)
                    }
                }
            }
        }
        if(width>0)layoutEditor(width,height)
    }
    /** Explicit app preference; never toggles Android animator/global settings. */
    fun setReduceMotion(reduce:Boolean){
        OriginalStandByMotionPolicy.setReduceMotion(context,reduce)
        startPagingFrames()
        if(reduce){
            if(configurationPager.running)configurationPager.settle()
            if(gallery.running)gallery.settle()
            if(outer.running)outer.settle()
            if(clocks.running)clocks.settle()
            if(editorRunning)animateEditor(editorTarget)
        }
        invalidate()
    }
    fun refresh(){if(controller.displayed.face!=Face.PHOTOS)cancelPhotoPaging();startPagingFrames();invalidate();updateDescription();schedule();onStateChanged()}
    fun handleBack():Boolean {if(configurationVisible&&!controller.editing&&!editorExiting){requestConfigurationFinish(configurationFaces.indexOf(configurationCommittedFace).coerceAtLeast(0));return true};if(galleryVisible){chooseGallery(galleryFaces.indexOf(controller.state.face).coerceAtLeast(0));return true};if(widgets.handleBack()){invalidate();return true};if(calibratingCamera){closeCalibration();return true};if(chargingStarted!=null){stopChargingPreview();return true};if(controller.editing){closeEditor(false);return true};if(editorExiting)return true;return false}
    fun saveState(out:Bundle){controller.save(out)}
    /** Entry result, not merely a gesture, owns success feedback. Native outer
     * success occurs after child installation (AmbientUI fd08..fe04). Android
     * LONG_PRESS is an explicit adapter, NOT the iOS raw0 waveform/auth policy.
     * Widget-stack entry is a different host and does not inherit outer feedback.
     */
    private fun requestEditorEntry():Boolean {
        cancelPhotoPaging()
        if(!controller.editing&&!editorExiting&&!configurationVisible&&controller.displayed.face!=Face.WIDGETS){
            // 照片面：原版长按是"圆角矩形连续缩小到中间，编辑内容在卡片里展开"，
            // 卡片尺寸 = 类别切换器的 0.69，所以先走 portal，收拢完成后自动进切换器。
            if(controller.displayed.face==Face.PHOTOS)return openConfigurationSelection()
            // 其它面容：原版是**一段**连续 shrink 直接进 PREditor（预览卡约 0.5 屏），
            // 中间没有"切换器卡片"这一站。v183 起不再先走 portal，否则会出现
            // 全屏→0.69 卡片→0.5 编辑卡 的两段收缩，也就是用户看到的"中间态残留"。
            return beginEditorDirect()
        }
        val result=beginEditor()
        if(result==EditorEntryResult.POSTER_ENTERED)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return result==EditorEntryResult.POSTER_ENTERED || result==EditorEntryResult.POSTER_REOPENED || result==EditorEntryResult.WIDGET_ENTERED
    }
    /** 直接进入该面容的编辑器：起点就是当前整面（editorOrigin 留空），只有一段 shrink。 */
    private fun beginEditorDirect():Boolean {
        // 直接进入编辑器时，上一轮 portal 留下的模糊快照会把别的面容（例如精选照片）残留在背景里。
        // 原版这条路径没有这层快照，直接清掉。
        editorBackdrop?.recycle();editorBackdrop=null
        editorOrigin=null;editorOriginCorner=null;editorReturnToConfiguration=false
        editorConfigurationMorphFrom=null
        exitConfigurationAfterEditor=false
        val result=beginEditor()
        if(result==EditorEntryResult.POSTER_ENTERED||result==EditorEntryResult.WIDGET_ENTERED)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return result==EditorEntryResult.POSTER_ENTERED||result==EditorEntryResult.POSTER_REOPENED||result==EditorEntryResult.WIDGET_ENTERED
    }

    private fun beginEditor():EditorEntryResult {
        val reopening=editorExiting
        if(controller.editing || widgets.editing)return EditorEntryResult.ALREADY_OPEN
        // Gate the same clock selection that will be snapped below, not a stale
        // committed face when a held pager already sits on its next integer page.
        val entryFace=if(!duoProfile && !reopening && !legacyCalendar && controller.displayed.face in OriginalStandByNavigation.clockFaces)
            OriginalStandByNavigation.clockFaces[clocks.page.roundToInt().coerceIn(0,OriginalStandByNavigation.clockFaces.lastIndex)]
            else controller.displayed.face
        if(entryFace==Face.PHOTOS && !onPhotoContentBegin())return EditorEntryResult.UNAVAILABLE
        if(entryFace==Face.WIDGETS){
            widgets.beginEditing(OriginalWidgetStackHost.Side.LEFT);invalidate()
            return if(widgets.editing)EditorEntryResult.WIDGET_ENTERED else EditorEntryResult.UNAVAILABLE
        }
        if(entryFace==Face.WORLD){Toast.makeText(context,"世界时钟：城市选择与编辑器仍在迁移，当前显示本地时间",Toast.LENGTH_SHORT).show();return EditorEntryResult.UNAVAILABLE}
        endCalendarPress();removeCallbacks(longPress);stopPaging()
        if(entryFace!=Face.PHOTOS && !duoProfile && !reopening && !legacyCalendar && !editorReturnToConfiguration){
            outer.snap(Mode.CLOCKS.ordinal);clocks.snap(clocks.page.roundToInt())
            controller.dispatch(StandByEvent.Select(OriginalStandByNavigation.clockFaces[clocks.page.toInt()]))
        }
        if(reopening&&editorReturnToConfiguration){
            val current=editorConfigurationRect();val corner=editorConfigurationCorner()
            editorConfigurationMorphFrom=current;editorConfigurationMorphCorner=corner
            editorConfigurationMorphProgress=editorProgress;editorConfigurationMorphToDetail=true
        }
        val previousTab=controller.editorTab
        controller.beginEdit()
        if(reopening)controller.editorTab=previousTab
        if(!controller.editing)return EditorEntryResult.UNAVAILABLE
        chrome.visibility=VISIBLE;chrome.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_AUTO
        rebuildOptions();animateEditor(1f);updateDescription()
        // Reversal keeps current progress/velocity through animateEditor, the current
        // navigation frame and tab. It is not a new outer child-install success event.
        return if(reopening)EditorEntryResult.POSTER_REOPENED else EditorEntryResult.POSTER_ENTERED
    }
    private fun closeEditor(save:Boolean){
        if(!controller.editing)return
        // Match the original selection/draft boundary. A failed persisted READ
        // grant or disk write must leave both the photo draft and detail open.
        if(controller.displayed.face==Face.PHOTOS && !onPhotoContentFinish(save))return
        if(editorReturnToConfiguration){
            val current=editorConfigurationRect();val corner=editorConfigurationCorner()
            editorConfigurationMorphFrom=current;editorConfigurationMorphCorner=corner
            editorConfigurationMorphProgress=editorProgress;editorConfigurationMorphToDetail=false
            exitConfigurationAfterEditor=true
        }
        // Keep the existing transaction boundary: confirm persists; cancel discards now.
        // Native draft/snapshot handoff is unresolved, so do not invent deferred rollback.
        if(save)controller.confirmEdit()else controller.cancelEdit()
        removeCallbacks(longPress);finishInput()
        chrome.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        animateEditor(0f);refresh()
    }
    private fun animateEditor(target:Float){
        stopEditorMotion();editorTarget=target
        if(!OriginalStandByMotionPolicy.animationsEnabled(context)){editorProgress=target;editorVelocity=0.0;chrome.alpha=target;chrome.visibility=if(target==0f)GONE else VISIBLE;invalidate();return}
        editorRunning=true;Choreographer.getInstance().postFrameCallback(editorSpring)
    }

    private fun finishInput(){
        removeCallbacks(longPress);tracker?.recycle();tracker=null;pointerId=-1
        gestureAxis=Axis.NONE;moving=false;widgetGestureCaptured=false;blockUntilUp=false
    }
    private fun cancelGesture(){
        removeCallbacks(longPress);widgets.cancelTouchForOuterPaging()
        if(!controller.editing){if(duoProfile && abs(gallery.page-gallery.page.roundToInt())>1e-6)gallery.settle();if(abs(outer.page-outer.page.roundToInt())>1e-6)outer.settle();if(abs(clocks.page-clocks.page.roundToInt())>1e-6)clocks.settle()}
        if(photoInputActive)photoPager.settle(selected=1)
        finishInput()
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(configurationVisible&&!controller.editing&&!editorExiting)return true
        if(galleryVisible)return true
        if(editorExiting || editorExitTouchBlocked)return true
        if(calibratingCamera)return !(e.x>=calibrationControls.left&&e.x<calibrationControls.right&&e.y>=calibrationControls.top&&e.y<calibrationControls.bottom)
        return !controller.editing
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(configurationVisible&&!controller.editing&&!editorExiting)return onConfigurationTouch(e)
        if(galleryVisible)return onGalleryTouch(e)
        if(editorExiting || editorExitTouchBlocked){
            // Hold this stream out of the live pager through UP/CANCEL. A genuinely
            // new DOWN may still recognize an explicit long-press reentry; an old
            // MOVE/UP inherited from closing the editor cannot create that request.
            editorExitTouchBlocked=true
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    finishInput();downX=e.x;downY=e.y;pointerId=e.getPointerId(0)
                    postDelayed(longPress,300L)
                }
                MotionEvent.ACTION_MOVE->{
                    val pi=e.findPointerIndex(pointerId)
                    if(pi<0 || hypot(e.getX(pi)-downX,e.getY(pi)-downY)>slop){moving=true;removeCallbacks(longPress)}
                }
                MotionEvent.ACTION_POINTER_DOWN->{moving=true;removeCallbacks(longPress)}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{finishInput();editorExitTouchBlocked=false}
            }
            return true
        }
        if(calibratingCamera){
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{gestureX=e.x;gestureY=e.y;gestureAnchor=calibrationDraft;pinchedGesture=false}
                MotionEvent.ACTION_POINTER_DOWN->{if(e.pointerCount>=2){pinchedGesture=true;pinchStart=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));pinchRadius=calibrationDraft?.radius?:0.0}}
                MotionEvent.ACTION_MOVE->{
                    if(e.pointerCount>=2&&pinchStart>0){val span=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));calibrationDraft=calibrationDraft?.copy(radius=(pinchRadius*span/pinchStart).coerceIn(.5,minOf(width,height)/4.0),calibrated=true)}
                    else if(!pinchedGesture)calibrationDraft=gestureAnchor?.let{it.copy(x=(it.x+e.x-gestureX).coerceIn(0.0,width.toDouble()),y=(it.y+e.y-gestureY).coerceIn(0.0,height.toDouble()),calibrated=true)}
                    updateCalibrationReadout();invalidate()
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{gestureAnchor=null} // Explicit Save, never auto-save on finger lift.
            };return true
        }
        // Child stack owns an accepted vertical stream and every editor event.
        val widgetSurface=(if(duoProfile)controller.displayed.face==Face.WIDGETS && abs(gallery.page-galleryFaces.indexOf(Face.WIDGETS))<1e-6 else !legacyCalendar && abs(outer.page-Mode.WIDGETS.ordinal)<.001) && !controller.editing
        if(widgetGestureCaptured||widgets.editing||widgetSurface){
            val consumed=widgets.onTouchEvent(e,RectF(0f,0f,width.toFloat(),height.toFloat()))
            if(consumed||widgetGestureCaptured){
                widgetGestureCaptured=true;removeCallbacks(longPress);outer.hold()
                tracker?.recycle();tracker=null;moving=false
                if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)finishInput()
                return true
            }
        }
        observeCalendarPress(e)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                stopPaging();startPagingFrames();downX=e.x;downY=e.y;startPage=outer.raw();startClockPage=clocks.raw();galleryStartPage=gallery.raw()
                moving=false;gestureAxis=Axis.NONE;pointerId=e.getPointerId(0);blockUntilUp=false
                if(controller.displayed.face==Face.PHOTOS&&renderer.photoVerticalInputReady&&photoCommitId==null){
                    photoStartPage=photoPager.raw();photoInputActive=true;onPhotoInteraction(true)
                }else cancelPhotoPaging()
                tracker?.recycle();tracker=VelocityTracker.obtain().also{it.addMovement(e)}
                if(!controller.editing&&(!widgetSurface||duoProfile)&&abs(outer.page-outer.page.roundToInt())<1e-6&&abs(clocks.page-clocks.page.roundToInt())<1e-6)
                    postDelayed(longPress,300L)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN->{cancelGesture();blockUntilUp=true;return true}
            MotionEvent.ACTION_CANCEL->{cancelGesture();return true}
            MotionEvent.ACTION_MOVE->{
                if(blockUntilUp)return true
                val pi=e.findPointerIndex(pointerId);if(pi<0){cancelGesture();return true}
                tracker?.addMovement(e);val dx=e.getX(pi)-downX;val dy=e.getY(pi)-downY
                val next=OriginalStandByNavigation.lock(gestureAxis,dx.toDouble(),dy.toDouble(),slop.toDouble())
                if(next!=Axis.NONE){moving=true;removeCallbacks(longPress)}
                if(gestureAxis==Axis.NONE&&next!=Axis.NONE){
                    gestureAxis=next
                    if(next==Axis.HORIZONTAL){widgets.cancelTouchForOuterPaging();if(photoInputActive)photoPager.settle(selected=1);if(abs(clocks.page-clocks.page.roundToInt())>1e-6)clocks.settle()}
                    else {
                        if(duoProfile && abs(gallery.page-gallery.page.roundToInt())>1e-6)gallery.settle()
                        if(abs(outer.page-outer.page.roundToInt())>1e-6)outer.settle()
                    }
                }
                if(!controller.editing){
                    when(gestureAxis){
                        Axis.HORIZONTAL -> if(duoProfile)gallery.drag(galleryStartPage-dx/width.coerceAtLeast(1)*horizontalSign)
                            else outer.drag(startPage-dx/width.coerceAtLeast(1)*horizontalSign)
                        Axis.VERTICAL -> if(controller.displayed.face==Face.PHOTOS&&photoInputActive)
                            dragPhotoPage(photoStartPage-dy/height.coerceAtLeast(1))
                        else if(!duoProfile && !legacyCalendar && outer.page.roundToInt()==Mode.CLOCKS.ordinal)
                            clocks.drag(startClockPage-dy/height.coerceAtLeast(1))
                        Axis.NONE -> Unit
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP->{
                removeCallbacks(longPress);tracker?.addMovement(e)
                if(!blockUntilUp){
                    if(controller.editing){
                        // Existing Android editor-tab adapter, isolated from live poster paging.
                        if(moving&&gestureAxis==Axis.HORIZONTAL&&abs(e.x-downX)>dp(64f)){
                            val tabs=activeEditorTabs();val index=tabs.indexOf(controller.editorTab).coerceAtLeast(0)
                            controller.editorTab=tabs[(index+if(e.x<downX)1 else -1).coerceIn(0,tabs.lastIndex)];rebuildOptions()
                        }
                    }else if(moving){
                        tracker?.computeCurrentVelocity(1000)
                        when(gestureAxis){
                            Axis.HORIZONTAL -> if(duoProfile)gallery.settle(-(tracker?.getXVelocity(pointerId)?:0f)/density.toDouble()/1000.0*horizontalSign)
                                else outer.settle(-(tracker?.getXVelocity(pointerId)?:0f)/density.toDouble()/1000.0*horizontalSign)
                            Axis.VERTICAL -> if(controller.displayed.face==Face.PHOTOS){
                                if(photoInputActive){
                                    val velocity=-(tracker?.getYVelocity(pointerId)?:0f)/density.toDouble()/1000.0
                                    val destination=OriginalPagingMotion.target(photoPager.page*height/density,velocity,height/density.toDouble(),2)
                                    val ready=when(destination){0->renderer.photos.readyPrevious;2->renderer.photos.readyNext;else->renderer.photos}
                                    photoPager.settle(velocity,if(ready?.bitmap?.isRecycled==false)destination else 1)
                                    android.util.Log.i("DuoPhotoPaging","release page=${photoPager.page} target=$destination ready=${ready?.currentPhotoId}")
                                }
                            }else if(!duoProfile&&!legacyCalendar&&outer.page.roundToInt()==Mode.CLOCKS.ordinal)
                                clocks.settle(-(tracker?.getYVelocity(pointerId)?:0f)/density.toDouble()/1000.0)
                            Axis.NONE -> Unit
                        }
                    }else if(photoInputActive&&abs(photoPager.page-1.0)>1e-6){
                        // A re-grab can hold a fractional frame without a new MOVE.
                        // This UP completes that stream; it must not trigger an edge tap
                        // on the old owner or leave rotation paused indefinitely.
                        photoPager.settle(selected=1)
                    }else{
                        if(abs(outer.page-outer.page.roundToInt())>1e-6)outer.settle()
                        if(abs(clocks.page-clocks.page.roundToInt())>1e-6)clocks.settle()
                        if(!duoProfile&&controller.displayed.face==Face.WORLD&&abs(clocks.page-OriginalStandByNavigation.clockIndex(Face.WORLD))<.001){renderer.tapWorld(e.x,e.y,width,height);invalidate()}
                        if(duoProfile && abs(gallery.page-gallery.page.roundToInt())>1e-6){
                            gallery.settle() // A tap during re-grab must not operate stale photo/widget content.
                        }else if(controller.displayed.face==Face.PHOTOS){
                            // Recovered poster regions own the tap only when this photo has
                            // place or capture date, or metadata is already showing. The
                            // gallery chooser is a separate surface and is not forwarded.
                            val posterOwns=renderer.photoHasMetadata||renderer.photoMetadataShowing
                            val sourceStrip=!renderer.photoMetadataShowing&&e.y>=height-dp(42f)
                            if(posterOwns&&!sourceStrip){
                                val tap=renderer.tapPhotoPoster(e.x,e.y,width,height)
                                if(tap.photoStep!=0)onPhotoStep(tap.photoStep<0)
                                if(tap.consumed){invalidate();schedulePhotoMetadataWake()}
                            }else if(e.y>=height-dp(42f))onPhotoSourceSettings()
                            else if(e.x<dp(100f))onPhotoStep(true)
                            else if(e.x>width-dp(100f))onPhotoStep(false)
                            else if(duoProfile)openGallery()else onPhotoSourceSettings()
                        }else if(duoProfile)openGallery()
                        performClick()
                    }
                }
                if(duoProfile && !galleryVisible && !controller.editing && !gallery.running && abs(gallery.page-gallery.page.roundToInt())>1e-6)gallery.settle()
                if(!photoPager.running&&photoCommitId==null&&photoPager.page==1.0)finishPhotoInteraction()
                finishInput();return true
            }
        };return true
    }
    override fun onKeyUp(keyCode:Int,event:android.view.KeyEvent):Boolean {
        if(keyCode==android.view.KeyEvent.KEYCODE_BACK){if(!handleBack())onExit();return true}
        return super.onKeyUp(keyCode,event)
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean{
        if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK&&duoProfile&&!controller.editing){
            if(configurationVisible)requestConfigurationDetail()
            else if(galleryVisible)chooseGallery(gallery.page.roundToInt())else openGallery()
            return true
        }
        return super.performAccessibilityAction(action,args)
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun performLongClick():Boolean {
        // Same result gate for accessibility/platform entry as for our touch timer.
        // Do not call super: its implicit long-click feedback would bypass this gate
        // or duplicate the explicit successful-poster feedback above.
        val entered=requestEditorEntry()
        if(entered)sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
        return entered
    }
    private fun fitScene(out:RectF,space:RectF,w:Int,h:Int){
        val scale=min(space.width()/w.coerceAtLeast(1),space.height()/h.coerceAtLeast(1)).coerceAtLeast(0f)
        val sw=w*scale;val sh=h*scale;out.set(space.centerX()-sw/2,space.centerY()-sh/2,space.centerX()+sw/2,space.centerY()+sh/2)
    }
    /** standby-1.mp4 frame_0005: the face page indicator is a horizontal dot row at the
     * bottom-center (the bottom-right circle in the video/photo is the physical camera, not UI).
     * WeatherFaceContent draws its own 4 complication-page dots, so it is skipped here. */
    private fun drawClockDots(c:Canvas,r:RectF,state:StandByState){
        if(state.face==Face.WEATHER)return
        val faces=OriginalStandByNavigation.clockFaces.filter{it!=Face.WEATHER}
        val index=faces.indexOf(state.face)
        if(index<0)return
        val ink=when{
            state.face==Face.DIGITAL -> if(state.night)VisualTokens.NIGHT_RED else OriginalDigitalColors.colors(state.options.digitalLook,state.options.dark).numerals
            state.face==Face.CALENDAR -> Color.BLACK
            else -> Color.WHITE
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val cx=r.centerX();val cy=r.bottom-dp(18f)
        val gap=dp(10f);val radius=dp(3f)
        for(i in faces.indices){
            paint.color=Color.argb(if(i==index)235 else 80,Color.red(ink),Color.green(ink),Color.blue(ink))
            c.drawCircle(cx+(i-(faces.size-1)/2f)*gap,cy,radius,paint)
        }
    }
    private fun deviceCorner():Float=if(android.os.Build.VERSION.SDK_INT>=31)(0..3).mapNotNull{rootWindowInsets?.getRoundedCorner(it)?.radius?.toFloat()}.maxOrNull()?:0f else 0f
    private fun drawIn(c:Canvas,r:RectF,state:StandByState,editorFrame:Boolean=false,live:Boolean=false,frameCorner:Float?=null){
        if(r.width()<=0||r.height()<=0||width==0||height==0)return
        // Face is full-bleed (fill + crop); editor cards keep fitting so the whole card stays visible.
        val scale=if(editorFrame) min(r.width()/width,r.height()/height) else max(r.width()/width,r.height()/height)
        val corner=frameCorner?:if(editorFrame){
            val p=editorProgress.coerceIn(0f,1f)
            val origin=editorOrigin
            val first=if(editorReturnToConfiguration&&origin!=null)
                editorOriginCorner?:configurationCardCorner() else 0f
            first*(1f-p)+scale*(deviceCorner()+dp(12f))*p
        }else 0f
        if(state.face==Face.WEATHER)android.util.Log.d("DuoGeom","face=WEATHER viewW=$width viewH=$height r=${r.width()}x${r.height()} scale=$scale density=$density")
        c.save();c.clipPath(OriginalContinuousShape.path(r,corner));c.translate(r.centerX()-width*scale/2f,r.centerY()-height*scale/2f);c.scale(scale,scale)
        // Keep the full scene's metrics stable while shrinking the SAME composition.
        val motion=if(live&&state.face==Face.CALENDAR){
            val active=state.options.style==1
            val engine=calendarStyleMotion?:OriginalCalendarStyleTransition(active).also{calendarStyleMotion=it}
            engine.sample(active,System.nanoTime()).also{if(it.running)postInvalidateOnAnimation()}
        }else null
        val press=if(live && renderer.calendarPressLayout(width,height,state)!=null)
            calendarPressMotion.frame(pressTime()).also{if(it.running)postInvalidateOnAnimation()} else null
        // The original photo wrapper reads the display trait, not a guessed card
        // radius. Android provides the corresponding window-space pixel input.
        renderer.photoWindowCornerRadiusPx=deviceCorner()
        if(press!=null)renderer.drawPressScene(c,width,height,state,controller.now(),motion,press,deviceCorner())
        else renderer.draw(c,width,height,state,controller.now(),false,motion)
        drawClockDots(c,r,state)
        c.restore()
        if(editorFrame){val edge=RectF(r);val stroke=dp(2f);edge.inset(stroke/2,stroke/2)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;alpha=(255*editorProgress.coerceIn(0f,1f)).toInt();style=Paint.Style.STROKE;strokeWidth=stroke}
            c.drawPath(OriginalContinuousShape.path(edge,(corner-stroke/2).coerceAtLeast(0f)),paint)
        }
    }
    /** Static source progress is 1-clamp(abs(item*pageSize-offset)/pageSize,0,1)
     * (AmbientUI36a0..ac). Each layer has its own scale/alpha spring and keeps
     * full scene metrics. Device corner is Android's display-corner adapter. */
    private fun presentation(c:Canvas,layer:OriginalNavigationFrame.Layer,draw:()->Unit){
        if(!layer.visible)return
        val axis=layer.axis
        val offset=(layer.offsetPages*(if(axis==Axis.HORIZONTAL)width*horizontalSign else height.toDouble())).toFloat()
        val checkpoint=c.save()
        c.translate(if(axis==Axis.HORIZONTAL)offset else 0f,if(axis==Axis.VERTICAL)offset else 0f)
        c.scale(layer.scale.toFloat(),layer.scale.toFloat(),width/2f,height/2f)
        val rect=RectF(0f,0f,width.toFloat(),height.toFloat())
        c.clipPath(OriginalContinuousShape.path(rect,deviceCorner()))
        val alpha=c.saveLayerAlpha(rect,(255*layer.presentationProgress).roundToInt().coerceIn(0,255))
        draw();c.restoreToCount(alpha);c.restoreToCount(checkpoint)
    }
    private fun drawNavigation(c:Canvas){
        // One immutable geometry/presentation snapshot for this whole traversal.
        val frame=navigationFrame.snapshot
        Mode.entries.forEach { mode ->
            presentation(c,frame.outer[mode.ordinal]){
                if(mode==Mode.CLOCKS){
                    OriginalStandByNavigation.clockFaces.forEachIndexed { index,face ->
                        presentation(c,frame.clocks[index]){
                            drawIn(c,RectF(0f,0f,width.toFloat(),height.toFloat()),controller.forFace(face),live=true)
                        }
                    }
                }else{
                    val face=if(mode==Mode.WIDGETS&&legacyCalendar)Face.CALENDAR else OriginalStandByNavigation.face(mode,controller.clockFace)
                    drawIn(c,RectF(0f,0f,width.toFloat(),height.toFloat()),controller.forFace(face),live=true)
                }
            }
        }
    }
    private fun isFaceVisible(face:Face):Boolean {
        if(duoProfile){
            if(controller.editing||editorExiting)return controller.displayed.face==face
            val page=if(configurationVisible)configurationPager.page else gallery.page
            return galleryFaces.indexOf(face).let{it>=0&&abs(it-page)<1.5}
        }
        if(editorProgress>0f||controller.editing)return controller.displayed.face==face
        // Legacy provider pages use their real presentation visibility, including
        // outgoing/adjacent layers. Photos isn't a member of the clock-face stack.
        if(face==Face.PHOTOS)return navigationFrame.snapshot.outer[Mode.PHOTOS.ordinal].visible
        if(face==Face.WIDGETS)return !legacyCalendar&&navigationFrame.snapshot.outer[Mode.WIDGETS.ordinal].visible
        val index=OriginalStandByNavigation.clockFaces.indexOf(face)
        return index>=0&&navigationFrame.snapshot.clockVisible(index)
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(Color.BLACK)
        val photoInsets=if(android.os.Build.VERSION.SDK_INT>=30)rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) else null
        renderer.photoMetadataSafeInsets=OriginalPhotoCompactMetadata.SafeInsets(photoInsets?.left?.toFloat()?:0f,photoInsets?.right?.toFloat()?:0f)
        renderer.photoMetadataRtl=layoutDirection==LAYOUT_DIRECTION_RTL
        widgets.rightToLeft=layoutDirection==LAYOUT_DIRECTION_RTL
        widgets.setSceneVisible(!configurationVisible&&!galleryVisible&&!controller.editing&&!widgets.editing&&
            (if(duoProfile)controller.displayed.face==Face.WIDGETS else !legacyCalendar&&navigationFrame.snapshot.outer[Mode.WIDGETS.ordinal].targetProgress>0.0))
        val p=editorProgress
        if(p>0f||controller.editing){
            editorBackdrop?.let{ bmp -> if(!bmp.isRecycled){ c.drawBitmap(bmp,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG)); c.drawColor(Color.argb(150,0,0,0)) } }
            // Android single-owner correction: detail owns the sole live poster.
            // Native portal mirrors one renderId/contextId source; no original
            // main-poster setHidden selector has been recovered. Do not redraw the
            // selected face/neighbors underneath. editorOrigin anchors both directions.
            val origin=editorOrigin?:RectF(0f,0f,width.toFloat(),height.toFloat())
            val rect=if(editorReturnToConfiguration)editorConfigurationRect() else
                RectF(origin.left+(preview.left-origin.left)*p,origin.top+(preview.top-origin.top)*p,
                    origin.right+(preview.right-origin.right)*p,origin.bottom+(preview.bottom-origin.bottom)*p)
            val styleCorner=if(editorReturnToConfiguration)editorConfigurationCorner()else null
            val from=styleFrom
            if(from!=null&&styleBlend<1f){
                val layer=c.saveLayerAlpha(null,(255f*(1f-styleBlend)).roundToInt())
                drawIn(c,rect,controller.displayed.copy(options=from),true,true,styleCorner)
                c.restoreToCount(layer)
                val overl=c.saveLayerAlpha(null,(255f*styleBlend).roundToInt())
                drawIn(c,rect,controller.displayed,true,true,styleCorner)
                c.restoreToCount(overl)
            }else{
                drawIn(c,rect,controller.displayed,true,true,styleCorner)
            }

        }else if(configurationVisible){
            drawConfigurationSelection(c)
        }else if(duoProfile){
            drawGallery(c) // One live presentation for direct paging and chooser; no release handoff snap.
        }else{
            drawNavigation(c)
        }
        if(controller.displayed.face==Face.WIDGETS&&lastWidgetPageDescription!=widgets.pageDescriptions){
            lastWidgetPageDescription=widgets.pageDescriptions;updateDescription()
        }
        if(calibratingCamera){
            c.drawColor(Color.WHITE) // White calibration backdrop exposes the physical aperture and green ring.
            if(cameraKey()!=calibrationPanelKey||(display?.rotation?:0)!=calibrationPanelRotation){post{closeCalibration()}}
            else calibrationDraft?.let{chargingOverlay.drawCameraRing(c,it,1f,1f,calibrationStroke)}
        }
        chargingStarted?.let{start ->
            chargingOverlay.cameraAnchor=cameraAnchor();chargingOverlay.cameraStrokeWidthPx=cameraStroke()
            val elapsed=(SystemClock.uptimeMillis()-start)/1000.0
            val sample=if(OriginalStandByMotionPolicy.animationsEnabled(context))elapsed else 2.5
            val running=chargingOverlay.draw(c,width.toFloat(),height.toFloat(),density,sample,chargingPercent,layoutDirection==LAYOUT_DIRECTION_RTL)
            if(elapsed>=9.8||!running)chargingStarted=null
            else if(attached&&windowVisibility==VISIBLE)postInvalidateOnAnimation()
        }
        if(attached&&windowVisibility==VISIBLE&&isFaceVisible(Face.PHOTOS)&&renderer.photoMotionRunning)postInvalidateOnAnimation()
        if(isFaceVisible(Face.PHOTOS))schedulePhotoMetadataWake()
        if(attached&&windowVisibility==VISIBLE&&isFaceVisible(Face.FLOAT)&&renderer.floatMotionRunning)postInvalidateOnAnimation()
        if(attached&&windowVisibility==VISIBLE&&isFaceVisible(Face.WORLD)&&renderer.worldMotionRunning)postInvalidateOnAnimation()
        if(attached&&windowVisibility==VISIBLE&&!controller.referenceMode&&(isFaceVisible(Face.ANALOG)||visibleAnalogWidget())&&OriginalStandByMotionPolicy.animationsEnabled(context))postInvalidateOnAnimation()
    }
    private var lastWidgetPageDescription=""
    private fun updateDescription(){contentDescription=if(configurationVisible&&!controller.editing&&!editorExiting)"CONFIGURATION_SELECTION selected=${configurationFaces[configurationPager.page.roundToInt().coerceIn(0,configurationFaces.lastIndex)].name} committed=${controller.state.face.name}。横滑配置；自定义进入详细编辑；完成回到运行。Simulator编辑层级的Android适配。" else if(galleryVisible)"DUO_GALLERY selected=${galleryFaces[gallery.page.roundToInt().coerceIn(0,galleryFaces.lastIndex)].name} page=${gallery.page} committed=${controller.state.face.name}。横滑选择独立钟面，轻点启用；Duo 导航移植预览，非已验证原版几何。" else if(controller.editing)null else if(editorExiting)"正在退出编辑" else if(duoProfile&&controller.displayed.face !in listOf(Face.WIDGETS,Face.PHOTOS))"DUO_ACTIVE ${controller.state.face.name} ${controller.now().format(descriptionFormat)}。左右滑动切换，轻点选择钟面，长按编辑；导航适配预览。" else if(controller.displayed.face==Face.WIDGETS) {
        val left=widgets.host.left;val right=widgets.host.right
        val phone=renderer.batteries.firstOrNull{it.kind==BatteryDeviceKind.PHONE}?.components?.firstOrNull()?.percent
        "WIDGETS ${controller.now().format(descriptionFormat)} 左列=${left.entries.getOrNull(left.selected)?.id} 右列=${right.entries.getOrNull(right.selected)?.id} 手机=${phone?.let{"$it%"}?:"未知"} 编辑=${widgets.editing}。${widgets.pageDescriptions}。上下滑动独立切换，长按编辑。"
    } else if(controller.displayed.face==Face.PHOTOS) "PHOTOS page=${renderer.photos.pageId} index=${renderer.photos.selectedIndex} id=${renderer.photos.currentPhotoId} readyPrevious=${renderer.photos.readyPrevious?.pageId} readyNext=${renderer.photos.readyNext?.pageId} ${renderer.photos.message} metadata=${renderer.photoHasMetadata} showing=${renderer.photoMetadataShowing}。上下切换照片；有地点或拍摄日期时按原区域轻点显示并在到期后收回；左右边缘切图；长按编辑；底部管理来源。" else "${controller.state.face.name} ${controller.now().format(descriptionFormat)}。左右切换小组件、照片、时钟；时钟上下切换样式，长按自定义，系统返回退出。"}
    private fun visibleAnalogWidget():Boolean = controller.displayed.face==Face.WIDGETS &&
        listOf(widgets.host.left,widgets.host.right).any{it.entries.getOrNull(it.selected)?.kind in setOf("ANALOG","WORLD_CLOCK")}
    private var postedMetadataWake:Long?=null
    private val metadataWake=Runnable{postedMetadataWake=null;invalidate()}
    private fun schedulePhotoMetadataWake(){
        val deadline=renderer.photoMetadataWakeNanos
        if(deadline==postedMetadataWake)return
        removeCallbacks(metadataWake)
        postedMetadataWake=deadline
        if(deadline==null||!attached)return
        postDelayed(metadataWake,((deadline-System.nanoTime())/1_000_000L).coerceAtLeast(0L))
    }
    private fun schedule(){removeCallbacks(tick);if(attached&&windowVisibility==VISIBLE&&!controller.referenceMode){val quantum=if(controller.displayed.face==Face.ANALOG||controller.displayed.face==Face.WIDGETS)1000L else 60000L;postDelayed(tick,quantum-System.currentTimeMillis()%quantum+10)}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();widgets.active=true;attached=true;startPagingFrames();schedule()}
    override fun onDetachedFromWindow(){cancelPhotoPaging();configurationMotion.reset();configurationPortalNode=null;widgets.close();renderer.releaseNewComponents();endCalendarPress();calendarPress=OriginalMonthPressFeedback(null,false);calendarPressMotion=OriginalMonthPressFeedback.LiveMotion(calendarPress.snapshot());calendarStyleMotion=null;closeCalibration();chargingStarted=null;attached=false;removeCallbacks(tick);removeCallbacks(metadataWake);postedMetadataWake=null;removeCallbacks(longPress);settleEditorForLifecycle();stopPaging();finishInput();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE){startPagingFrames();invalidate();schedule()}else {cancelPhotoPaging();configurationMotion.reset(configurationOpen);configurationPortalNode=null;configurationEntryTouchBlocked=false;configurationFinishPending=false;configurationDetailPending=false;configurationWidgetPending=false;widgets.cancel();endCalendarPress();removeCallbacks(tick);removeCallbacks(longPress);settleEditorForLifecycle();stopPaging();outer.snap(OriginalStandByNavigation.mode(controller.state.face).ordinal);clocks.snap(OriginalStandByNavigation.clockIndex(controller.clockFace));gallery.snap(galleryFaces.indexOf(controller.state.face).coerceAtLeast(0));configurationPager.snap(configurationFaces.indexOf(controller.state.face).coerceAtLeast(0));finishInput()}}
}
