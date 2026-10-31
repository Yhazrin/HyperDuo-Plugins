package local.duostandby

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Live two-column host. Static normal/editor rectangles are measured from the
 * physical iPhone 27.2 recording, not asserted to be Duo layout constants.
 * Normal three-slot circular geometry and 46/W, tracking6/W scale are recovered
 * from SBHWidgetStackViewController (not the outer poster transform). Its class
 * chain is SBHWidgetStackScrollView -> BSUIScrollView -> UIScrollView, pagingYES.
 * Release reuses that inherited UIKit default numerical branch; runtime overrides,
 * velocity estimator and exact CA retarget timing remain unverified.
 * The editor is separate and is NOT claimed to share the normal 3H circular host.
 * The add chooser and Bluetooth button are explicit Android utility adaptations.
 */
class OriginalWidgetStackController(
    private val context: Context,
    private val invalidate: () -> Unit,
    private val onChanged: () -> Unit = {},
    private val onBluetoothSettings: () -> Unit = {},
) : AutoCloseable {
    val host = OriginalWidgetStackHost()
    private val batteryRenderer = OriginalBatteryRenderer(context)
    var glyphResolver: OriginalBatteryRenderer.GlyphResolver? = null
    var lowChargeSlotIds: Set<String> = emptySet()
    var active = true
        set(value) { if (field != value) { cancel(); field = value; if (!value) finishEditing(false) } }
    val editing: Boolean get() = host.editing != null
    /** StandBy night mode: the battery renderer has no per-face night colors, so its
     * layer is mapped by luminance to the system red instead of leaving green rings. */
    var night=false
    val running: Boolean get() = motions.values.any { it.release?.running == true || it.programStart != null } || normalScaleRunning || editorMotion.running || pageControls.values.any { it.running }
    val batteryDescriptions: List<String> get() = batteryRenderer.lastDescriptions
    private var catalog = listOf(
        OriginalWidgetStackHost.Entry("ANALOG", "模拟时钟"),
        OriginalWidgetStackHost.Entry("CALENDAR_DATE", "日历 · 日期", OriginalWidgetStackHost.CalendarStyle.DATE),
        OriginalWidgetStackHost.Entry("CALENDAR_MONTH", "日历 · 月历", OriginalWidgetStackHost.CalendarStyle.MONTH),
        OriginalWidgetStackHost.Entry("DIGITAL", "数字时钟"),
        OriginalWidgetStackHost.Entry("WORLD_CLOCK", "城市时钟"),
        OriginalWidgetStackHost.Entry("BATTERY", "电池"),
        OriginalWidgetStackHost.Entry("WEATHER", "天气"),
        OriginalWidgetStackHost.Entry("MUSIC", "音乐"),
        OriginalWidgetStackHost.Entry("UP_NEXT", "日程"),
    )
    private val density = context.resources.displayMetrics.density.toDouble()
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val r = Color.red(VisualTokens.NIGHT_RED) / 255f
        val g = Color.green(VisualTokens.NIGHT_RED) / 255f
        val b = Color.blue(VisualTokens.NIGHT_RED) / 255f
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0.2126f*r,0.7152f*r,0.0722f*r,0f,0f,
            0.2126f*g,0.7152f*g,0.0722f*g,0f,0f,
            0.2126f*b,0.7152f*b,0.0722f*b,0f,0f,
            0f,0f,0f,1f,0f))
    }
    private val viewport = RectF()
    private var left = RectF()
    private var right = RectF()
    private var tracker: VelocityTracker? = null
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downPosition = 0.0
    private var touchSide: OriginalWidgetStackHost.Side? = null
    private var vertical = false
    private var horizontal = false
    private var longPress: Runnable? = null
    private var reorderFrom: Int? = null
    private var reorderOffset = 0f
    private var lastDevices: List<BatteryDeviceModel> = emptyList()
    private var chooser: AlertDialog? = null
    private val editorMotion = OriginalWidgetEditorMotion()
    private val editorAperturePath = Path()
    private var editorSourceBounds = RectF()
    // Presentation-space endpoints of a reversed match-move, not a new source
    // slot substituted underneath an already-running interpolation.
    private var editorExitPanel: RectF? = null
    private var editorExitWidget: RectF? = null
    private val visibleWidgetBounds = mutableMapOf<String,RectF>()

    private class Motion(var position: Double = 0.0) {
        var release: OriginalPagingMotion.Release? = null
        // Keep the numerical release in its own continuous coordinates when the
        // circular native host recenters an in-flight contentOffset.
        var releaseOffset = 0.0
        var lastTime = 0L
        var programStart: Long? = null
        var programFrom = 0.0
        var programTo = 0.0
        fun stop() { release = null; releaseOffset = 0.0; programStart = null }
    }
    private val motions = OriginalWidgetStackHost.Side.values().associateWith { Motion() }
    private val pageControls = OriginalWidgetStackHost.Side.values().associateWith { OriginalWidgetPageControl() }
    private val pageControlTimers = mutableMapOf<OriginalWidgetStackHost.Side, Runnable>()
    private var sceneVisible = false
    private var flashOnAppearance = false
    var rightToLeft = false
    val pageDescriptions: String get() = OriginalWidgetStackHost.Side.values().joinToString("；") { side ->
        val page = pageControls.getValue(side)
        val label = if(side==OriginalWidgetStackHost.Side.LEFT)"左列" else "右列"
        "$label 第${page.currentPage+1}/${page.numberOfPages}页，页码${if(page.modelAlpha>0.0&&page.numberOfPages>1)"显示" else "隐藏"}"
    }
    /** Appearance is supplied by the owning mode, not inferred from touch movement. */
    fun setSceneVisible(visible:Boolean) {
        if(sceneVisible==visible)return
        sceneVisible=visible
        if(visible)flashOnAppearance=true else resetPageControls()
    }
    private fun resetPageControls(){
        pageControlTimers.values.forEach(handler::removeCallbacks);pageControlTimers.clear()
        pageControls.values.forEach { it.reset() };flashOnAppearance=false
    }
    private fun showPageControl(side:OriginalWidgetStackHost.Side){
        pageControlTimers.remove(side)?.let(handler::removeCallbacks)
        val stack=host.stack(side);val page=pageControls.getValue(side)
        page.configure(stack.entries.size,stack.selected)
        page.setHidden(false,System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context))
        invalidate()
    }
    private fun restartPageControlTimer(side:OriginalWidgetStackHost.Side){
        pageControlTimers.remove(side)?.let(handler::removeCallbacks)
        val task=Runnable {
            pageControlTimers.remove(side)
            pageControls.getValue(side).setHidden(true,System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context))
            invalidate()
        }
        pageControlTimers[side]=task
        handler.postDelayed(task,OriginalWidgetPageControl.HIDE_DELAY_MILLIS)
    }
    private var normalScaleRunning=false
    private val normalScales=mutableMapOf<String,OriginalWidgetStackPaging.Scale>()
    init {
        // AmbientUI 24A94425 _defaultIconState leading = mobiletimer.datetime, trailing = CalendarMonth.
        // Secondary slots are the available Android catalogue equivalents; stocks/weather/up-next
        // are not fabricated. Only fresh installs (no saved stacks) use this order.
        host.replace(OriginalWidgetStackHost.Side.LEFT, catalog.filter { it.id in setOf("DIGITAL", "CALENDAR_DATE", "ANALOG") }, "DIGITAL")
        host.replace(OriginalWidgetStackHost.Side.RIGHT, catalog.filter { it.id in setOf("CALENDAR_MONTH", "BATTERY") }, "CALENDAR_MONTH")
    }

    /** Supply only widgets with real painter implementations. */
    fun setCatalog(entries: List<OriginalWidgetStackHost.Entry>) {
        require(entries.map { it.id }.distinct().size == entries.size)
        catalog = entries.toList()
        OriginalWidgetStackHost.Side.values().forEach { side ->
            val stack = host.stack(side)
            val selected = stack.entries.getOrNull(stack.selected)?.id
            host.replace(side, stack.entries.filter { old -> catalog.any { it.kind == old.kind } }, selected)
        }
        syncPositions(); onChanged(); invalidate()
    }

    fun draw(canvas: Canvas, bounds: RectF, devices: List<BatteryDeviceModel>, painter: OriginalWidgetStackHost.Painter) {
        if (!active || bounds.isEmpty) return
        if (viewport != bounds) {
            cancel(); viewport.set(bounds)
            OriginalWidgetStackHost.measuredIPhoneSlots(bounds).also { left = it.first; right = it.second }
            syncPositions()
            if(sceneVisible)flashOnAppearance=true
        }
        lastDevices = devices
        val count = batteryRenderer.pages(devices).size.coerceAtLeast(1)
        OriginalWidgetStackHost.Side.values().forEach { host.contentPage(it,"BATTERY",count) }
        paint.reset();paint.color=Color.BLACK;canvas.drawRect(bounds,paint)
        normalScaleRunning=false
        if(flashOnAppearance&&!editing){
            flashOnAppearance=false
            OriginalWidgetStackHost.Side.values().forEach { showPageControl(it);restartPageControlTimer(it) }
        }
        if (editing) drawEditor(canvas, painter) else {
            OriginalWidgetStackHost.Side.values().forEach { side ->
                val rect = slot(side); val stack = host.stack(side)
                val pointScale=motionPointScale(side)
                // iconImageInfo is the paging box. The measured rect is only
                // the visible clock/ring envelope inside that box.
                val height=pageSize(side);val width=height
                val position=advance(side,height)
                val pageControl=pageControls.getValue(side)
                if(pageControl.numberOfPages!=stack.entries.size)pageControl.configure(stack.entries.size,stack.selected)
                pageControl.sample(System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context))
                val checkpoint=canvas.save()
                // AMUIInfograph disables the individual card-envelope clip.
                // Constrain only to the fitted source screen, not Android's extra
                // letterbox area: adjacent native hosts are removed after they
                // leave that screen, never while still visible in its margins.
                val sourceScreen=r(0f,0f,2622f,1206f)
                canvas.clipRect(rect.left,sourceScreen.top,rect.right,sourceScreen.bottom)
                if(stack.entries.isEmpty())label(canvas,"长按添加小组件",rect.centerX(),rect.centerY(),rect.width()*.045f,Color.GRAY)
                else for(relative in -1..1){
                    val scrolling=normalPagesVisible(side)||abs(position-height)>1e-6
                    if(relative!=0 && (!scrolling||stack.entries.size==1))continue
                    if(stack.entries.size==2&&relative!=0&&relative!=(if(position<=height)-1 else 1))continue
                    val center=OriginalWidgetStackPaging.center(relative,height)
                    val dy=((center-height*.5-position)*pointScale).toFloat()
                    val index=Math.floorMod(stack.selected+relative,stack.entries.size)
                    val entry=stack.entries[index]
                    val b=RectF(rect).apply{offset(0f,dy)}
                    val tracking=pageControl.trackingAppearance(touchSide==side&&pointer>=0&&!horizontal)
                    val target=OriginalWidgetStackPaging.scale(width,height,center,position,tracking)
                    val spring=normalScales.getOrPut(side.name+":"+entry.id){OriginalWidgetStackPaging.Scale(target)}
                    val scale=spring.sample(target,System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context)).toFloat()
                    visibleWidgetBounds[side.name+":"+entry.id]=RectF(
                        b.centerX()-b.width()*scale*.5f,b.centerY()-b.height()*scale*.5f,
                        b.centerX()+b.width()*scale*.5f,b.centerY()+b.height()*scale*.5f)
                    val save=canvas.save();canvas.scale(scale,scale,b.centerX(),b.centerY())
                    normalScaleRunning=normalScaleRunning||spring.running
                    drawEntry(canvas,b,entry,side,painter,clip=false);canvas.restoreToCount(save)
                }
                canvas.restoreToCount(checkpoint)
            }
            // Sibling overlay, not clipped to a content card or animated with it.
            OriginalWidgetStackHost.Side.values().forEach { drawNormalPageControl(canvas,it) }
        }
        if (running) invalidate()
    }

    private fun drawNormalPageControl(canvas:Canvas,side:OriginalWidgetStackHost.Side){
        val page=pageControls.getValue(side)
        if(page.numberOfPages<=1||page.presentationAlpha<=0.0)return
        val s=scale();val card=slot(side)
        val x=card.centerX()+OriginalWidgetPageControl.MEASURED_TRAILING_OFFSET*s*(if(rightToLeft)-1f else 1f)
        val pitch=OriginalWidgetPageControl.MEASURED_PITCH*s
        val y=card.centerY()-(page.numberOfPages-1)*pitch*.5f
        paint.reset();paint.isAntiAlias=true;paint.color=Color.WHITE
        repeat(page.numberOfPages){index ->
            val tint=if(index==page.currentPage)1.0 else OriginalWidgetPageControl.INACTIVE_ALPHA
            paint.alpha=(255.0*tint*page.presentationAlpha).toInt().coerceIn(0,255)
            canvas.drawCircle(x,y+index*pitch,OriginalWidgetPageControl.MEASURED_RADIUS*s,paint)
        }
    }

    private fun drawEntry(canvas: Canvas, rect: RectF, entry: OriginalWidgetStackHost.Entry, side: OriginalWidgetStackHost.Side, painter: OriginalWidgetStackHost.Painter,clip:Boolean=true) {
        val checkpoint = canvas.save(); if(clip)canvas.clipRect(rect)
        if (entry.id == "BATTERY") {
            if (night) {
                val layer = canvas.saveLayer(rect, nightPaint)
                batteryRenderer.draw(canvas, rect, lastDevices, host.contentPage(side,entry.id,batteryRenderer.pages(lastDevices).size), glyphResolver, lowChargeSlotIds)
                canvas.restoreToCount(layer)
            } else batteryRenderer.draw(canvas, rect, lastDevices, host.contentPage(side,entry.id,batteryRenderer.pages(lastDevices).size), glyphResolver, lowChargeSlotIds)
        } else painter.draw(canvas, rect, entry)
        canvas.restoreToCount(checkpoint)
    }

    private fun slot(side: OriginalWidgetStackHost.Side) = if (side == OriginalWidgetStackHost.Side.LEFT) left else right
    // Native _pageSize is iconImageInfo, not the smaller visible content envelope.
    // The existing 874x402pt source profile has a 362pt page: at 3x that is
    // 1086px, while the recorded battery/clock content remains ~965px. Using
    // 965 as the pitch left the previous widget on-screen when it was removed.
    // Keep screen, offsets and touch velocity in the same fitted reference space.
    // This profile is NOT asserted to be Duo's unrecovered native host size.
    private fun motionPointScale(side: OriginalWidgetStackHost.Side): Double = if(editing) density else OriginalWidgetEditorGeometry.referencePointScale(viewport.width(),viewport.height()).toDouble().coerceAtLeast(1e-6)
    private fun pageSize(side: OriginalWidgetStackHost.Side): Double = if(editing) editorPitch().toDouble()/density else OriginalWidgetEditorGeometry.ICON_DIMENSION.toDouble()
    private fun syncPositions() {
        motions.forEach { (side, motion) -> motion.stop(); motion.position = if(editing)host.stack(side).selected * pageSize(side) else pageSize(side) }
    }
    private fun recenter(side:OriginalWidgetStackHost.Side,page:Double){
        if(editing||page<=0.0)return
        val motion=motions.getValue(side);val stack=host.stack(side)
        val selected=OriginalWidgetStackPaging.newActive(stack.selected,motion.position,page,stack.entries.size)
        host.select(side,selected)
        pageControls.getValue(side).configure(stack.entries.size,selected)
        motion.position=OriginalWidgetStackPaging.recenteredOffset(motion.position,page)
    }
    private fun advance(side:OriginalWidgetStackHost.Side,page:Double):Double{
        val motion=motions.getValue(side);val now=SystemClock.uptimeMillis()
        var completed=false
        motion.programStart?.let{started ->
            val f=((now-started)/300f).coerceIn(0f,1f)
            motion.position=motion.programFrom+(motion.programTo-motion.programFrom)*OriginalPagingMotion.programmaticProgress(f)
            if(f==1f){motion.programStart=null;completed=true}
        }
        motion.release?.let{release ->
            val dt=(now-motion.lastTime).coerceAtLeast(0L)
            // A delayed Canvas frame must advance the same kernel, not jump
            // directly to the destination. Lifecycle cancellation is separate.
            motion.position=release.advance(dt.toDouble())+motion.releaseOffset
            // SBH scrollViewDidScroll @0x1f854d61c..630 recenters *every*
            // out-of-range offset, including release frames, not just drags or
            // the completed animation. Preserve velocity/remaining trajectory.
            if(!editing&&(motion.position<0.0||motion.position>2.0*page)){
                val before=motion.position
                recenter(side,page)
                motion.releaseOffset+=motion.position-before
                onChanged()
            }
            if(!release.running){motion.release=null;completed=true}
        }
        motion.lastTime=now
        if(completed&&!editing){recenter(side,page);restartPageControlTimer(side);onChanged()}
        else if(!editing&&motion.programStart!=null){
            val stack=host.stack(side)
            pageControls.getValue(side).setCurrentPage(OriginalWidgetStackPaging.newActive(stack.selected,motion.position,page,stack.entries.size))
        }
        if(motion.release==null&&motion.programStart==null&&touchSide!=side){
            motion.position=if(editing)host.stack(side).selected*page else page
        }
        return motion.position
    }
    private fun normalPagesVisible(side:OriginalWidgetStackHost.Side)=
        (vertical&&touchSide==side)||motions.getValue(side).release!=null||motions.getValue(side).programStart!=null

    fun step(side:OriginalWidgetStackHost.Side,delta:Int,animated:Boolean=true){
        val motion=motions.getValue(side);val page=pageSize(side)
        if(editing)host.step(side,delta)
        else {advance(side,page);recenter(side,page);showPageControl(side)}
        val destination=if(editing)host.stack(side).selected*page else page*(1+delta.compareTo(0))
        motion.stop()
        if(animated&&OriginalStandByMotionPolicy.animationsEnabled(context)){
            motion.programFrom=motion.position;motion.programTo=destination;motion.programStart=SystemClock.uptimeMillis()
        }else {motion.position=destination;if(!editing){recenter(side,page);restartPageControlTimer(side)}}
        onChanged();invalidate()
    }

    fun beginEditing(side: OriginalWidgetStackHost.Side) {
        if(editing)return
        val selected=host.stack(side).entries.getOrNull(host.stack(side).selected)
        editorSourceBounds=RectF(visibleWidgetBounds[side.name+":"+selected?.id] ?: slot(side))
        editorExitPanel=null;editorExitWidget=null
        cancel();host.beginEditing(side);syncPositions()
        editorMotion.begin(true,System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context))
        onChanged();invalidate()
    }
    fun finishEditing(animated:Boolean=true) {
        if(!editing)return
        if(!animated){finishEditingNow();return}
        val side=host.editing ?: return
        val now=System.nanoTime()
        val animatedByPolicy=OriginalStandByMotionPolicy.animationsEnabled(context)
        val transition=editorMotion.advance(now,animatedByPolicy)
        val selectedRect=cardRect(host.stack(side).selected,advance(side,editorPitch()/density))
        // 24A94425 animateToEndpoint @1f844c41c..430 sets BeginFromCurrentState
        // when already animating. Capture BOTH live geometries before rebinding
        // the collapsed endpoint; otherwise a mid-entry Back jumps by (1-g)
        // times the difference between the tracked source and the normal slot.
        val currentPanel=editorPresentationBounds(editorCardArea(),transition.geometry,editorExitPanel)
        val currentWidget=editorPresentationBounds(selectedRect,transition.geometry,editorExitWidget)
        clearInput();chooser?.dismiss();chooser=null
        editorExitPanel=currentPanel;editorExitWidget=currentWidget
        editorSourceBounds=RectF(slot(side))
        editorMotion.begin(false,now,animatedByPolicy)
        invalidate()
    }
    private fun finishEditingNow(){
        cancel();chooser?.dismiss();chooser=null;editorMotion.reset()
        editorExitPanel=null;editorExitWidget=null
        if(editing){host.finishEditing();syncPositions();onChanged();invalidate()}
    }
    fun handleBack(): Boolean {
        if (chooser?.isShowing == true) { chooser?.dismiss(); chooser = null; return true }
        if (!editing) return false
        finishEditing(); return true
    }

    /** Normal DOWN is observed but not consumed, so the outer horizontal pager
     * sees the same stream. Once true is returned, the caller cancels that pager.
     * A widget editor consumes the entire stream. No notification/device access. */
    fun onTouchEvent(event: MotionEvent, bounds: RectF): Boolean {
        if (!active) return false
        if (viewport != bounds) {
            viewport.set(bounds); OriginalWidgetStackHost.measuredIPhoneSlots(bounds).also { left=it.first;right=it.second };syncPositions()
        }
        // The source and destination are one live widget, not two tappable copies.
        if(editing&&editorMotion.running)return true
        val wasEditing = editing
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearInput(); pointer = event.getPointerId(0); downX = event.x; downY = event.y; lastY = downY
                touchSide = host.editing ?: host.hitSide(downX, downY, left, right)
                val side = touchSide ?: return false
                val motion = motions.getValue(side)
                advance(side,pageSize(side));motion.stop()
                downPosition=motion.position
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                // A new DOWN while the control is visible is already isTracking,
                // even before crossing touch slop; the 6/W gate must redraw now.
                invalidate()
                if (!wasEditing || editorCardArea().contains(event.x,event.y)) {
                    val task = Runnable {
                        if (pointer < 0 || vertical || horizontal) return@Runnable
                        if (!editing) beginEditing(side)
                        else {
                            val index = editorIndexAt(downY)
                            if (index in host.stack(side).entries.indices) {
                                reorderFrom=index; reorderOffset=0f;invalidate()
                            }
                        }
                    }
                    longPress = task;handler.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return wasEditing
            }
            MotionEvent.ACTION_MOVE -> {
                val side = touchSide ?: return wasEditing
                val pi = event.findPointerIndex(pointer); if (pi < 0) { cancel(); return wasEditing }
                val x = event.getX(pi); val y = event.getY(pi);lastY=y;tracker?.addMovement(event)
                val dx=x-downX;val dy=y-downY
                if (reorderFrom != null) { reorderOffset=dy;invalidate();return true }
                if (!vertical && !horizontal && (abs(dx)>slop || abs(dy)>slop)) {
                    stopLongPress()
                    val axis=OriginalStandByNavigation.lock(OriginalStandByNavigation.Axis.NONE,dx.toDouble(),dy.toDouble(),slop.toDouble())
                    vertical=axis==OriginalStandByNavigation.Axis.VERTICAL;horizontal=!vertical
                    if(vertical&&!editing){
                        val motion=motions.getValue(side);val before=motion.position
                        recenter(side,pageSize(side));downPosition+=motion.position-before
                        showPageControl(side)
                    }
                }
                if (vertical) {
                    val page=pageSize(side);val motion=motions.getValue(side);val pointScale=motionPointScale(side)
                    if(editing){
                        val last=(host.stack(side).entries.size-1).coerceAtLeast(0)
                        motion.position=OriginalPagingMotion.rubberBand(downPosition-dy/pointScale,0.0,last*page,page)
                    }else if(host.stack(side).entries.size>1){
                        motion.position=OriginalPagingMotion.rubberBand(downPosition-dy/pointScale,0.0,2.0*page,page)
                        if(motion.position<0.0||motion.position>2.0*page){
                            recenter(side,page)
                            // Rebase the gesture at the displayed recentered offset.
                            // Adding only the page delta reintroduces the raw,
                            // pre-rubber-band overshoot on the next MOVE, making
                            // an unmoved finger jump the widget by part of a page.
                            downPosition=motion.position+dy/pointScale
                            onChanged()
                        }
                    }
                    invalidate();return true
                }
                return wasEditing
            }
            MotionEvent.ACTION_UP -> {
                stopLongPress(); val side=touchSide ?: return wasEditing
                tracker?.addMovement(event)
                if (reorderFrom != null) {
                    val stack=host.stack(side);val from=reorderFrom!!
                    val to=(from+(reorderOffset/editorPitch()).toInt()).coerceIn(0,(stack.entries.size-1).coerceAtLeast(0))
                    if (from!=to) host.move(side,from,to)
                    cancel();syncPositions();onChanged();invalidate();return true
                }
                if (vertical) {
                    val motion=motions.getValue(side);val page=pageSize(side)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity=-(tracker?.getYVelocity(pointer) ?: 0f)/motionPointScale(side)/1000.0
                    val last=if(editing)(host.stack(side).entries.size-1).coerceAtLeast(0)else 2
                    val target=if(!editing&&host.stack(side).entries.size<=1)1 else OriginalPagingMotion.target(motion.position,velocity,page,last)
                    releaseTo(side,target,velocity)
                    clearInput();onChanged();invalidate();return true
                }
                if (wasEditing && !horizontal) { editorTap(event.x,event.y);clearInput();return true }
                val isBattery = host.stack(side).entries.getOrNull(host.stack(side).selected)?.id=="BATTERY"
                if (!horizontal && isBattery && batteryRenderer.pages(lastDevices).size>1) {
                    host.advanceContentPage(side,"BATTERY",batteryRenderer.pages(lastDevices).size)
                    clearInput();invalidate();return true
                }
                resumeHeldSide();clearInput();invalidate();return wasEditing
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val consumed=wasEditing || vertical
                touchSide?.let { side ->
                    val page=pageSize(side);val last=if(editing)(host.stack(side).entries.size-1).coerceAtLeast(0)else 2
                    releaseTo(side,OriginalPagingMotion.target(motions.getValue(side).position,0.0,page,last),0.0)
                }
                clearInput();onChanged();invalidate();return consumed
            }
        }
        return wasEditing || vertical
    }

    private fun releaseTo(side:OriginalWidgetStackHost.Side,target:Int,velocity:Double){
        val motion=motions.getValue(side);val page=pageSize(side)
        if(editing)host.select(side,target)
        else {
            val stack=host.stack(side)
            // SBH willEndDragging chooses the target page before settling completes.
            pageControls.getValue(side).setCurrentPage(OriginalWidgetStackPaging.newActive(stack.selected,target*page,page,stack.entries.size))
        }
        motion.stop()
        if(OriginalStandByMotionPolicy.animationsEnabled(context)){
            val pointScale=motionPointScale(side)
            val boundsWidth=if(editing)slot(side).width()/pointScale else page
            motion.release=OriginalPagingMotion.Release(boundsWidth,motion.position,velocity,target*page,pointScale)
            motion.lastTime=SystemClock.uptimeMillis()
        }else {motion.position=target*page;if(!editing){recenter(side,page);restartPageControlTimer(side)}}
    }
    private fun resumeHeldSide(){
        touchSide?.let { side ->
            val page=pageSize(side)
            val target=if(editing)host.stack(side).selected else OriginalPagingMotion.target(motions.getValue(side).position,0.0,page,2)
            if(abs(motions.getValue(side).position-target*page)>1e-6)releaseTo(side,target,0.0)
        }
    }
    /** Losing horizontal arbitration cancels the recognizer/timer, not the other
     * column's animation. A touched in-flight card resumes without a snap. */
    fun cancelTouchForOuterPaging(){resumeHeldSide();clearInput();invalidate()}

    private fun stopLongPress() { longPress?.let(handler::removeCallbacks);longPress=null }
    private fun clearInput() {
        stopLongPress();tracker?.recycle();tracker=null;pointer=-1;touchSide=null
        vertical=false;horizontal=false;reorderFrom=null;reorderOffset=0f
    }
    fun cancel() { clearInput();motions.values.forEach { it.stop() };resetPageControls() }
    override fun close() { finishEditing(false);cancel();active=false }

    /** Column-local widget style, never the full-screen CalendarPoster options.
     * Version 1 CALENDAR records migrate to DATE without changing order/selection. */
    fun saveState(): String {
        val json=JSONObject().put("version",3)
        OriginalWidgetStackHost.Side.values().forEach { side ->
            val stack=host.stack(side)
            json.put(side.name,JSONObject().put("ids",JSONArray(stack.entries.map { it.id }))
                .put("selected",stack.entries.getOrNull(stack.selected)?.id)
                .put("entries",JSONArray(stack.entries.map { entry -> JSONObject().put("id",entry.id)
                    .put("calendarStyle",entry.calendarStyle?.name ?: JSONObject.NULL)
                    .put("zoneId",entry.zoneId ?: JSONObject.NULL)
                    .put("clock24Hour",entry.clock24Hour ?: JSONObject.NULL) })))
        }
        return json.toString()
    }
    fun restoreState(raw: String?) {
        if (raw.isNullOrBlank()) return
        val json=runCatching { JSONObject(raw) }.getOrNull() ?: return
        val version=json.optInt("version")
        if (version !in 1..3) return
        fun migratedId(id:String)=if(id=="CALENDAR")"CALENDAR_DATE" else id
        OriginalWidgetStackHost.Side.values().forEach { side ->
            val s=json.optJSONObject(side.name) ?: return@forEach
            val ids=s.optJSONArray("ids") ?: return@forEach
            val storedEntries=s.optJSONArray("entries")
            val entries=(0 until min(ids.length(),64)).mapNotNull { i ->
                val id=migratedId(ids.optString(i))
                val definition=catalog.find { it.kind==id.substringBefore('#') } ?: return@mapNotNull null
                val stored=storedEntries?.optJSONObject(i)?.takeIf { migratedId(it.optString("id"))==id }
                val style=stored?.optString("calendarStyle")?.let { rawStyle ->
                        runCatching { OriginalWidgetStackHost.CalendarStyle.valueOf(rawStyle) }.getOrNull()
                    }
                val zone=stored?.takeIf{!it.isNull("zoneId")}?.optString("zoneId")
                    ?.takeIf{runCatching{java.time.ZoneId.of(it)}.isSuccess}
                val use24=stored?.takeIf{!it.isNull("clock24Hour")}?.optBoolean("clock24Hour")
                definition.copy(id=id,calendarStyle=if(definition.calendarStyle!=null)style ?: definition.calendarStyle else null,
                    zoneId=zone,clock24Hour=use24)
            }.distinctBy { it.id }
            // Older adapters allowed deleting the final item. Keep this side's
            // initial stack when restoring that invalid empty record, not the other side.
            if(entries.isNotEmpty())host.replace(side,entries,migratedId(s.optString("selected")))
        }
        cancel();syncPositions();invalidate()
    }

    // Physical video frame 40.0 s, 2622x1206. Layout is explicitly a measured
    // iPhone reference profile; Android fits uniformly rather than stretching.
    private fun scale()=min(viewport.width()/2622f,viewport.height()/1206f)
    private fun ox()=viewport.centerX()-1311f*scale()
    private fun oy()=viewport.centerY()-603f*scale()
    private fun r(l:Float,t:Float,rr:Float,b:Float)=RectF(ox()+l*scale(),oy()+t*scale(),ox()+rr*scale(),oy()+b*scale())
    private fun editorCardArea():RectF {
        val half=OriginalWidgetEditorGeometry.apertureSide(viewport.width(),viewport.height())/2f
        return RectF(viewport.centerX()-half,viewport.centerY()-half,viewport.centerX()+half,viewport.centerY()+half)
    }
    private fun editorCardSide()=OriginalWidgetEditorGeometry.cardSide(viewport.width(),viewport.height())
    private fun editorPitch()=588f*scale()
    private fun editorIndexAt(y:Float):Int {
        val side=host.editing ?: return -1
        return floor((y-(viewport.centerY()-editorCardSide()/2f)+motions.getValue(side).position*density)/editorPitch()).toInt()
    }
    private fun cardRect(index:Int,position:Double):RectF {
        val half=editorCardSide()/2f
        return RectF(viewport.centerX()-half,viewport.centerY()-half,viewport.centerX()+half,viewport.centerY()+half)
            .apply { offset(0f,index*editorPitch()-(position*density).toFloat()) }
    }
    private fun drawEditor(c:Canvas,painter:OriginalWidgetStackHost.Painter) {
        val side=host.editing ?: return
        val stack=host.stack(side);val s=scale()
        val transition=editorMotion.advance(System.nanoTime(),OriginalStandByMotionPolicy.animationsEnabled(context))
        val geometry=transition.geometry
        val panel=editorPresentationBounds(editorCardArea(),geometry,editorExitPanel)
        val position=advance(side,editorPitch()/density)
        val selectedRect=cardRect(stack.selected,position)
        val matched=editorPresentationBounds(selectedRect,geometry,editorExitWidget)
        val contentScale=if(selectedRect.width()>0f)matched.width()/selectedRect.width() else 1f
        fun matchedRect(rect:RectF)=RectF(
            matched.centerX()+(rect.left-selectedRect.centerX())*contentScale,
            matched.centerY()+(rect.top-selectedRect.centerY())*contentScale,
            matched.centerX()+(rect.right-selectedRect.centerX())*contentScale,
            matched.centerY()+(rect.bottom-selectedRect.centerY())*contentScale)
        paint.reset();paint.isAntiAlias=true;paint.color=Color.rgb(33,33,33)
        // Original aperture-background alpha has its own .3/.8 duration window.
        paint.alpha=(255*transition.apertureAlpha).toInt().coerceIn(0,255)
        val pointScale=OriginalWidgetEditorGeometry.referencePointScale(viewport.width(),viewport.height())
        val iconWidth=OriginalWidgetEditorGeometry.ICON_DIMENSION*pointScale
        val sourceRadius=OriginalWidgetEditorGeometry.concentricCornerRadius(editorSourceBounds.width(),
            iconWidth,OriginalWidgetEditorGeometry.ICON_CORNER_RADIUS*pointScale,
            if(iconWidth>0f)editorSourceBounds.width()/iconWidth else 1f)
        val targetPanel=editorCardArea()
        val targetRadius=OriginalWidgetEditorGeometry.expandedCornerRadius(targetPanel.width(),targetPanel.height())
        val panelRadius=sourceRadius+(targetRadius-sourceRadius)*geometry
        c.drawRoundRect(panel,panelRadius,panelRadius,paint)
        val buttons=c.saveLayerAlpha(viewport,(255*geometry).toInt().coerceIn(0,255))
        pill(c,r(111f,47f,310f,132f),"+");pill(c,r(2314f,47f,2510f,132f),"✓")
        c.restoreToCount(buttons)
        // Native hierarchy: aperture clipsToBounds=YES (@1f8472274), child
        // scroll view clipsToBounds=NO (@1f847256c). Clip once at the rounded
        // aperture, never independently at each moving widget's rectangle.
        editorAperturePath.reset()
        editorAperturePath.addRoundRect(panel,panelRadius,panelRadius,Path.Direction.CW)
        val save=c.save();c.clipPath(editorAperturePath)
        if(stack.entries.isEmpty()) label(c,"点 + 添加小组件",panel.centerX(),panel.centerY(),34*s,Color.LTGRAY)
        stack.entries.forEachIndexed { i,entry ->
            if (reorderFrom==i) return@forEachIndexed
            val b=matchedRect(cardRect(i,position))
            if(RectF.intersects(panel,b)) {
                drawEntry(c,b,entry,side,painter)
                if(host.canRemove(side,entry.id)){
                    val chrome=c.saveLayerAlpha(viewport,(255*geometry).toInt().coerceIn(0,255))
                    deleteButton(c,b,s*contentScale);c.restoreToCount(chrome)
                }
            }
        }
        reorderFrom?.let { index ->
            stack.entries.getOrNull(index)?.let { entry ->
                val b=cardRect(index,position).apply{offset(0f,reorderOffset)}
                drawEntry(c,b,entry,side,painter);if(host.canRemove(side,entry.id))deleteButton(c,b,s)
            }
        }
        c.restoreToCount(save)
        val chrome=c.saveLayerAlpha(viewport,(255*geometry).toInt().coerceIn(0,255))
        stack.entries.indices.forEach { i ->
            paint.reset();paint.isAntiAlias=true;paint.color=if(i==stack.selected)Color.WHITE else Color.GRAY
            c.drawCircle(ox()+1815*s,oy()+(498f+i*53f)*s,11*s,paint)
        }
        label(c,"智能轮换",ox()+2193*s,oy()+477*s,34*s,Color.LTGRAY)
        label(c,"未接入",ox()+2193*s,oy()+524*s,32*s,Color.GRAY)
        label(c,"小组件建议",ox()+2193*s,oy()+881*s,34*s,Color.LTGRAY)
        label(c,"未接入",ox()+2193*s,oy()+928*s,32*s,Color.GRAY)
        // Deliberately identified Android integration, not an alleged Apple control.
        pill(c,r(1950f,1025f,2440f,1120f),"蓝牙电量 · Android 接入",25*s)
        c.restoreToCount(chrome)
        if(!transition.running&&!transition.entering)finishEditingNow()
    }
    private fun editorPresentationBounds(expanded:RectF,geometry:Float,exitStart:RectF?):RectF {
        return if(exitStart==null)mixRect(editorSourceBounds,expanded,geometry)
        else mixRect(exitStart,editorSourceBounds,editorMotion.geometryLegProgress)
    }
    private fun mixRect(a:RectF,b:RectF,t:Float)=RectF(
        a.left+(b.left-a.left)*t,a.top+(b.top-a.top)*t,
        a.right+(b.right-a.right)*t,a.bottom+(b.bottom-a.bottom)*t)
    private fun editorTap(x:Float,y:Float) {
        val side=host.editing ?: return
        when {
            r(2314f,47f,2510f,132f).contains(x,y) -> finishEditing()
            r(111f,47f,310f,132f).contains(x,y) -> {
                val available=catalog.filter { e->e.kind in setOf("ANALOG","DIGITAL","WORLD_CLOCK") || host.stack(side).entries.none { it.kind==e.kind } }
                chooser=AlertDialog.Builder(context).setTitle("添加小组件 · Android 选择器")
                    .setItems(available.map { it.title }.toTypedArray()) { _,which ->
                        available.getOrNull(which)?.let { host.add(side,it);syncPositions();onChanged();invalidate() }
                    }.setNegativeButton("取消",null).create()
                chooser?.show()
            }
            r(1950f,1025f,2440f,1120f).contains(x,y) -> onBluetoothSettings()
            else -> {
                val stack=host.stack(side); val position=motions.getValue(side).position
                stack.entries.toList().forEachIndexed { index,entry ->
                    val b=cardRect(index,position);val radius=43f*scale()
                    val cx=b.left+24f*scale();val cy=b.top+24f*scale()
                    if(host.canRemove(side,entry.id) && editorCardArea().contains(x,y) && (x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius) {
                        host.remove(side,entry.id);syncPositions();onChanged();invalidate();return
                    }
                    if(b.contains(x,y) && editorCardArea().contains(x,y) && entry.kind in setOf("ANALOG","DIGITAL","WORLD_CLOCK")){
                        showClockConfiguration(side,entry.id);return
                    }
                }
            }
        }
    }
    /** Explicit Android integration editor, scoped by side + stable instance ID.
     * Cancel changes nothing; an affirmative action persists only this instance. */
    private fun showClockConfiguration(side:OriginalWidgetStackHost.Side,id:String){
        val entry=host.stack(side).entries.find{it.id==id} ?: return
        val items=mutableListOf("时区：${entry.zoneId ?: "跟随设备"}")
        if(entry.kind!="ANALOG")items.add("时间格式：${entry.clock24Hour?.let{if(it)"24小时" else "12小时"} ?: "跟随设备"}")
        chooser=AlertDialog.Builder(context).setTitle("${entry.title} · 本侧实例")
            .setItems(items.toTypedArray()){_,which->
                if(which==0)showClockZones(side,id) else showClockFormat(side,id)
            }.setNegativeButton("取消",null).create()
        chooser?.show()
    }
    private fun showClockZones(side:OriginalWidgetStackHost.Side,id:String){
        val entry=host.stack(side).entries.find{it.id==id} ?: return
        val zones=java.time.ZoneId.getAvailableZoneIds().sorted()
        val labels=(listOf("跟随设备时区")+zones).toTypedArray()
        val checked=if(entry.zoneId==null)0 else zones.indexOf(entry.zoneId)+1
        chooser=AlertDialog.Builder(context).setTitle("时区 · ${if(side==OriginalWidgetStackHost.Side.LEFT)"左" else "右"}列")
            .setSingleChoiceItems(labels,checked){dialog,index->
                host.configureClock(side,id,if(index==0)null else zones[index-1],entry.clock24Hour)
                dialog.dismiss();onChanged();invalidate()
            }.setNegativeButton("取消",null).create()
        chooser?.show()
    }
    private fun showClockFormat(side:OriginalWidgetStackHost.Side,id:String){
        val entry=host.stack(side).entries.find{it.id==id} ?: return
        val checked=when(entry.clock24Hour){null->0;false->1;true->2}
        chooser=AlertDialog.Builder(context).setTitle("时间格式 · 本侧实例")
            .setSingleChoiceItems(arrayOf("跟随设备","12小时","24小时"),checked){dialog,index->
                host.configureClock(side,id,entry.zoneId,when(index){1->false;2->true;else->null})
                dialog.dismiss();onChanged();invalidate()
            }.setNegativeButton("取消",null).create()
        chooser?.show()
    }
    private fun deleteButton(c:Canvas,b:RectF,s:Float) {
        val x=b.left+24*s;val y=b.top+24*s
        paint.reset();paint.isAntiAlias=true;paint.color=Color.LTGRAY;c.drawCircle(x,y,43*s,paint)
        paint.color=Color.BLACK;paint.strokeWidth=6*s;paint.strokeCap=Paint.Cap.ROUND
        c.drawLine(x-18*s,y,x+18*s,y,paint)
    }
    private fun pill(c:Canvas,b:RectF,text:String,size:Float=42f*scale()) {
        paint.reset();paint.isAntiAlias=true;paint.color=Color.rgb(38,38,38)
        c.drawRoundRect(b,b.height()/2,b.height()/2,paint)
        label(c,text,b.centerX(),b.centerY(),size,Color.WHITE)
    }
    private fun label(c:Canvas,text:String,x:Float,y:Float,size:Float,color:Int) {
        paint.reset();paint.isAntiAlias=true;paint.color=color;paint.textAlign=Paint.Align.CENTER;paint.textSize=size
        c.drawText(text,x,y-(paint.ascent()+paint.descent())/2,paint)
    }
}
