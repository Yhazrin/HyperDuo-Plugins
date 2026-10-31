package local.duostandby

import android.graphics.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class StandByRenderer(context:android.content.Context) {
    var widgetController:OriginalWidgetStackController?=null
    var batteries:List<BatteryDeviceModel> = emptyList()
    var photos:XiaomiCuratedPhotoSource.Snapshot = XiaomiCuratedPhotoSource.disconnected()
    private val photoPresentation=PhotoCanvasPresentation()
    var photoWindowCornerRadiusPx:Float
        get()=photoPresentation.windowCornerRadiusPx
        set(value){photoPresentation.windowCornerRadiusPx=value}
    private var photoNeedsFrame=false
    val photoMotionRunning get()=photoPresentation.isAnimating||photoNeedsFrame
    var photoMetadataSafeInsets=OriginalPhotoCompactMetadata.SafeInsets()
    val photoVerticalInputReady get()=!photoPresentation.isAnimating&&photoPresentation.visiblePhotoId==photos.currentPhotoId&&photoPresentation.visiblePageId==photos.pageId&&photos.phase==XiaomiCuratedPhotoSource.Phase.READY
    var photoVerticalDragFraction:Float
        get()=photoPresentation.verticalDragFraction
        set(value){photoPresentation.verticalDragFraction=value}
    fun expectPhotoVerticalCommit(reverse:Boolean)=photoPresentation.expectVerticalCommit(reverse)
    fun cancelPhotoVerticalDrag()=photoPresentation.cancelVerticalDrag()
    var photoMetadataRtl:Boolean
        get()=photoPresentation.metadataLayoutRtl
        set(value){photoPresentation.metadataLayoutRtl=value}
    val photoHasMetadata get()=photoPresentation.hasPhotoMetadata
    /** 编辑卡与面共用同一套照片管线（night + legibility）。 */
    fun drawPhotoCard(canvas:Canvas,bounds:RectF,bitmap:Bitmap,night:Boolean,gradientOpacity:Float=-1f)=photoPresentation.drawPhotoLayer(canvas,bounds,bitmap,night,gradientOpacity)
    val photoLegibilityOpacity get()=photoPresentation.currentLegibilityOpacity

    val photoMetadataShowing get()=photoPresentation.isMetadataShowing
    val photoMetadataWakeNanos get()=photoPresentation.metadataNextWakeNanos
    fun tapPhotoPoster(x:Float,y:Float,width:Int,height:Int)=
        photoPresentation.tapPoster(x,y,RectF(0f,0f,width.toFloat(),height.toFloat()),density,photoPresentation.metadataLayoutRtl)
    private var lastPhotoPresentationState=""
    private val floatRenderer by lazy { OriginalPlayRenderer(context) }
    private val solarRenderer by lazy { OriginalSolarClockRenderer(context) }
    private val weatherWidgetContent by lazy { WeatherWidgetContent(context) }
    private val musicWidgetContent by lazy { MusicWidgetContent(context) }
    private val upNextWidgetContent by lazy { UpNextWidgetContent(context) }
    init {
        OriginalSolarPhase.requestLocation(context)
        DigitalWeatherRepository.load(context)
        DigitalWeatherRepository.refresh(context)
        RootMusicProvider.refresh(context)
        WeatherSymbols.init(context)
        UpNextProvider.refresh(context)
        GalleryJourneyRepository.refresh(context)
        GalleryAlbumsRepository.refresh(context)
        GalleryCardsRepository.refresh(context)
    }
    private val digitalWidgetContent by lazy { DigitalWidgetContent(context) }
    private val calendarWidgetContent by lazy { CalendarWidgetContent(context) }
    private val analogWidgetContent by lazy { WidgetAnalogContent(context) }
    private val analogFaceCanvas=OriginalAnalogFaceCanvas()
    private val worldWidgetContent by lazy { WidgetWorldClockContent(context) }
    val floatMotionRunning get()=floatRenderer.running
    fun releaseNewComponents(){solarRenderer.release();floatRenderer.reset();photoPresentation.clear();photoNeedsFrame=false}

    private val worldPreview=OriginalWorldMapPreview(context)
    private val worldDashboard=OriginalWorldDashboardRenderer(context)
    private val worldPins=OriginalWorldPinRenderer(context)
    private var worldCities=OriginalWorldPins.Model(emptyList())
    val worldMotionRunning get()=worldPins.running
    fun configureWorld(referenceJson:String?,selectedId:String?){
        worldCities=OriginalWorldPins.Model(runCatching{WorldCityInput.read(appContext,referenceJson)}.getOrElse{
            android.util.Log.e("DuoStandBy","Invalid world city input; no cities substituted",it);emptyList()})
        if(selectedId!=null && worldCities.cities.any{it.id==selectedId})worldCities.select(selectedId)
        worldPins.reset()
    }
    fun tapWorld(x:Float,y:Float,width:Int,height:Int){
        val frame=OriginalWorldMapGeometry.fit(width/density.toDouble(),height/density.toDouble(),density.toDouble())
        worldCities.tap(x/density.toDouble(),y/density.toDouble(),frame,density.toDouble(),System.nanoTime()/1e9)
    }
    private val appContext=context
    private val density=context.resources.displayMetrics.density
    init { configureWorld(null,null) }
    private val fonts=OriginalFonts(context)
    init { photoPresentation.timeTypefaceFactory = { width, weight -> fonts.photoTime(width, weight) } }
    init { photoPresentation.assetContext = appContext }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val normal=fonts.ui
    private val medium=fonts.medium
    private val condensed=Typeface.create("sans-serif-condensed",Typeface.BOLD)
    private val serif=Typeface.create("serif",Typeface.BOLD)
    private val bounds=Rect()
    private var monthNode:RenderNode?=null
    private var monthTitleNode:RenderNode?=null
    private var monthNodeKey:String?=null
    private val compactMonthNodes=arrayOfNulls<RenderNode>(3)
    private var compactMonthKey:String?=null
    private val wideHeaderNodes=arrayOfNulls<RenderNode>(2)
    private val wideHeaderKeys=arrayOfNulls<String>(2)
    private val wideGridNodes=arrayOfNulls<RenderNode>(2)
    private var wideGridKey:String?=null
    private var calendarLocaleSnapshot: OriginalCalendarLocale.Snapshot? = null
    private val shortTime=DateTimeFormatter.ofPattern("h:mm",Locale.US)
    private val shortDate=DateTimeFormatter.ofPattern("EEE d",Locale.US)
    private val secondsTime=DateTimeFormatter.ofPattern(OriginalAnalogParameters.DIGITAL_TIME_FORMAT,Locale.US)
    private val localizedDate=DateTimeFormatter.ofPattern(OriginalAnalogParameters.DATE_FORMAT,Locale.CHINA)
    private val outlineNumerals=listOf(12,2,3,4,6,8,9,10)
    private val cardinalNumerals=listOf(12,3,6,9)
    fun draw(canvas: Canvas, width: Int, height: Int, state: StandByState, time: LocalDateTime, editorFrame:Boolean=false, calendarMotion:OriginalCalendarStyleTransition.Sample?=null) {
        if(state.face==Face.PHOTOS){
            photoPresentation.metadataTypeface=fonts.semibold
            photoPresentation.metadataSafeInsetsPx=photoMetadataSafeInsets
            photoNeedsFrame=photoPresentation.draw(canvas,RectF(0f,0f,width.toFloat(),height.toFloat()),photos,
                clock=time,use24Hour=android.text.format.DateFormat.is24HourFormat(appContext),
                density=density,night=state.night,animated=OriginalStandByMotionPolicy.animationsEnabled(appContext))
            val presentationState="visible=${photoPresentation.visiblePhotoId} target=${photoPresentation.targetPhotoId} animating=${photoPresentation.isAnimating}"
            if(presentationState!=lastPhotoPresentationState){
                lastPhotoPresentationState=presentationState
                android.util.Log.i("DuoStandBy","photoPresentation $presentationState adapter=configuration_vertical_or_asset_crossfade")
            }
            return
        }
        if(state.face==Face.WIDGETS){
            widgetController?.night = state.night
            widgetController?.draw(canvas,RectF(0f,0f,width.toFloat(),height.toFloat()),batteries) { c,b,entry ->
                val instant=time.atZone(java.time.ZoneId.systemDefault()).toInstant()
                when {
                    entry.calendarStyle!=null || entry.kind=="CALENDAR" ->
                        calendarWidgetContent.draw(c,b,time.toLocalDate(),entry.calendarStyle ?: OriginalWidgetStackHost.CalendarStyle.DATE,state.night)
                    entry.kind=="DIGITAL" -> digitalWidgetContent.draw(c,b,instant,entry,state.night)
                    entry.kind=="ANALOG" -> analogWidgetContent.draw(c,b,instant,entry,state.night)
                    entry.kind=="WORLD_CLOCK" -> worldWidgetContent.draw(c,b,instant,entry,state.night)
                    entry.kind=="WEATHER" -> weatherWidgetContent.draw(c,b,state.night)
                    entry.kind=="MUSIC" -> musicWidgetContent.draw(c,b,state.night)
                    entry.kind=="UP_NEXT" -> upNextWidgetContent.draw(c,b,state.night)
                }
            };return
        }
        if(state.face==Face.FLOAT){
            canvas.drawColor(OriginalPlayRenderer.ORIGINAL_BACKGROUND)
            val use24=android.text.format.DateFormat.is24HourFormat(appContext)
            val hour=if(use24)time.hour else (time.hour%12).let{if(it==0)12 else it}
            val h=if(use24)String.format(Locale.ROOT,"%02d",hour)else hour.toString()
            val m=String.format(Locale.ROOT,"%02d",time.minute)
            // Source UIKit Play host, adapted to this actual Android window.
            // Not the ordinary-iPhone video scene and not a claimed Duo entry trace.
            floatRenderer.drawAndroidUiKitHost(canvas,width,height,density,h,m,
                OriginalPlayRenderer.ORIGINAL_DEFAULT_PALETTE,System.nanoTime()/1e9,state.night,
                OriginalStandByMotionPolicy.animationsEnabled(appContext))
            return
        }
        if(state.face==Face.SOLAR){
            // Sky is an explicit look. Null follows source pre-location branch, not local-hour phase.
            // SolarPosition.percentComplete from apparent solar time (source palette/orbit input),
            // not the previous pre-location phase 0.
            val solarPhase=OriginalSolarPhase.percentComplete(time,java.time.ZoneId.systemDefault(),
                OriginalSolarPhase.locationLongitude(appContext))
            val rendered=solarRenderer.drawLiveFace(canvas,width,height,density,time.hour,time.minute,
                android.text.format.DateFormat.is24HourFormat(appContext),solarPhase,state.night)
            if(!rendered){canvas.drawColor(Color.BLACK);text(canvas,"Sky 需要硬件渲染",width/2f,height/2f,18f*density,Color.WHITE,normal,Paint.Align.CENTER)}
            return
        }
        if(state.face==Face.WORLD){
            val instant=time.atZone(java.time.ZoneId.systemDefault()).toInstant()
            worldPreview.draw(canvas,width,height,instant,state.night)
            worldPins.draw(canvas,width,height,instant,state.night,worldCities,worldPreview,
                System.nanoTime()/1e9,OriginalStandByMotionPolicy.animationsEnabled(appContext))
            val highlighted=worldCities.highlighted(instant,java.time.ZoneId.systemDefault())
            worldDashboard.draw(canvas,width,height,instant,state.night,highlighted,worldCities.selectedTimeZone);return
        }
        val o=state.options
        val dark=state.night||o.dark
        val bg=when { state.night -> Color.BLACK; state.face==Face.ANALOG -> OriginalAnalogParameters.colors(o.palette,dark).background; state.face==Face.DIGITAL -> OriginalDigitalColors.colors(o.digitalLook,dark).background; state.face==Face.CALENDAR -> OriginalCalendarColors.colors(o.calendarLook,dark).background; state.face==Face.WEATHER -> Color.rgb(18,30,48); dark -> Color.BLACK; else -> palette(o.palette,Color.rgb(181,176,165)) }
        paint.reset();paint.color=bg
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        if(state.face==Face.DIGITAL){
            canvas.save();canvas.scale(density,density)
            originalDigital(canvas,width/density,height/density,time,dark,o,state.night)
            canvas.restore();return
        }
        if(state.face==Face.CALENDAR){
            val scene=OriginalCalendarGeometry.androidWindow(width,height,density)
            // Explicit unrotated Android-window contract. Actual Duo scene input is unverified.
            // Class2 remains on the prior adapter; class0/1 use their distinct native grids.
            if(scene.aspectClass in 0..1){
                canvas.save();canvas.scale(density,density)
                nativeClass01Calendar(canvas,scene,time,dark,o,state.night,calendarMotion)
                canvas.restore();return
            }
        }
        if(state.face==Face.ANALOG){
            // Android dp is the explicit source-point adapter. No 960x600 scene fit.
            canvas.save();canvas.scale(density,density)
            analog(canvas,width/density,height/density,time,dark,o,state.night)
            canvas.restore();return
        }
        // Face scene must be full-bleed: min() left ~49px letterbox bands that only showed on
        // faces whose background differs from the window color (weather). Editor cards still fit.
        val scale=if(editorFrame) min(width/VisualTokens.WIDTH,height/VisualTokens.HEIGHT)
            else max(width/VisualTokens.WIDTH,height/VisualTokens.HEIGHT)
        canvas.save();canvas.translate((width-960f*scale)/2f,(height-600f*scale)/2f);canvas.scale(scale,scale)
        when(state.face) { Face.CALENDAR->calendar(canvas,time,dark,o,state.night,calendarMotion);Face.DIGITAL->digital(canvas,time,dark,o,state.night);Face.WEATHER->weatherFace(canvas,VisualTokens.WIDTH.toFloat(),VisualTokens.HEIGHT.toFloat(),time,dark,o,state.night);Face.ANALOG->Unit;Face.WORLD,Face.WIDGETS,Face.FLOAT,Face.SOLAR,Face.PHOTOS->Unit }
        canvas.restore()
    }
    /** Same scene frame as nativeClass01Calendar. This Android-window proposal
     * is explicit, not a claim of original Duo host/safe-area measurements.
     * The prior class2 adapter has no verified hit geometry and stays untouched.
     */
    data class CalendarPressLayout(val width: Float, val height: Float) {
        fun zone(x: Float, y: Float, rtl: Boolean): Int? {
            if (x < 0f || y < 0f || x >= width || y >= height) return null
            val side = width.toDouble() * Double.fromBits(0x3fc5555555555555L)
            val leading = if (rtl) width - x else x
            return when { leading < side -> OriginalMonthPressFeedback.LEADING
                leading > width - side -> OriginalMonthPressFeedback.TRAILING
                else -> -1 } // Center only dims; it does not reset tiltEdge.
        }
    }
    fun calendarPressLayout(width: Int, height: Int, state: StandByState): CalendarPressLayout? {
        if (width <= 0 || height <= 0 || state.face != Face.CALENDAR || state.options.style != 1 || state.night) return null
        val scene = OriginalCalendarGeometry.androidWindow(width,height,density)
        return if (scene.aspectClass in 0..1) CalendarPressLayout(scene.width,scene.height) else null
    }
    fun drawPressScene(c: Canvas, width: Int, height: Int, state: StandByState, time: LocalDateTime,
                       motion: OriginalCalendarStyleTransition.Sample?, press: OriginalMonthPressFeedback.LiveMotion.Frame,
                       cornerRadiusPx: Float) {
        // Original MonthPressFeedback: stationary black ZStack backing, clipped
        // content + black dim overlay, followed by rotation3DEffect. NOT a generic shrink.
        c.drawColor(Color.BLACK)
        val saved = c.save()
        val matrix = Matrix().apply { setValues(OriginalMonthPressFeedback.projection(
            width.toDouble(),height.toDouble(),press.angle,press.anchorX).map { it.toFloat() }.toFloatArray()) }
        c.concat(matrix)
        c.clipPath(OriginalContinuousShape.path(RectF(0f,0f,width.toFloat(),height.toFloat()),cornerRadiusPx))
        draw(c,width,height,state,time,false,motion)
        if (press.dim > 0.0) {
            paint.reset();paint.color=Color.BLACK;paint.alpha=(press.dim.coerceIn(0.0,1.0)*255).roundToInt()
            c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        }
        c.restoreToCount(saved)
    }
    private fun palette(index:Int,default:Int)=when(index){1->Color.rgb(91,174,228);2->Color.rgb(231,117,93);3->Color.rgb(115,181,137);4->Color.rgb(228,189,102);5->Color.rgb(183,153,215);else->default}
    private fun numberFace(style:Int)=when(style){1->serif;2->Typeface.MONOSPACE;else->medium}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,face:Typeface=normal,align:Paint.Align=Paint.Align.LEFT) {
        paint.reset();paint.isAntiAlias=true;paint.typeface=face;paint.textSize=size;paint.color=color;paint.textAlign=align
        c.drawText(s,x,y,paint)
    }
    private fun calendarLocale(): OriginalCalendarLocale.Snapshot {
        val locale = Locale.getDefault()
        calendarLocaleSnapshot?.takeIf { it.locale == locale }?.let { return it }
        val systemCalendar = android.icu.util.Calendar.getInstance(locale)
        val symbols = android.icu.text.DateFormatSymbols(locale)
            .getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.NARROW)
            .drop(1).take(7)
        // LocalDate cells are Gregorian. Keep the header on the SAME calendar;
        // record non-Gregorian requests rather than displaying a mismatched year.
        val gregorianLocale = Locale.Builder().setLocale(locale)
            .setUnicodeLocaleKeyword("ca", "gregory").build()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(gregorianLocale, "yMMMM")
        return OriginalCalendarLocale.create(
            locale, systemCalendar.firstDayOfWeek, symbols, pattern, systemCalendar.type
        ).also { calendarLocaleSnapshot = it }
    }
    private fun calendar(c:Canvas,time:LocalDateTime,dark:Boolean,options:FaceOptions,night:Boolean,motion:OriginalCalendarStyleTransition.Sample?) {
        android.util.Log.i("DuoCal","calendar() style="+options.style)
        val calendarLocale = calendarLocale()
        val original=OriginalCalendarColors.colors(options.calendarLook,dark)
        val red=if(night) VisualTokens.NIGHT_RED else original.numerals
        val dateInk=if(night) VisualTokens.NIGHT_RED else OriginalCalendarColors.dateInk(options.calendarLook,dark,options.style)
        val blue=if(night) Color.rgb(160,36,38) else original.secondary
        val muted=if(night) Color.rgb(62,12,14) else OriginalCalendarColors.outsideMonth(options.calendarLook,dark)
        paint.reset();paint.isAntiAlias=true;paint.color=dateInk
        val date=time.dayOfMonth.toString()
        // Current Android scene only renders the current month (no browsing date override yet).
        // Thus the model display date and DateManager date belong to the same period.
        // Native c8fa8..c9060 retains this layer even while the month subtree waits 100 ms.
        // Do not let the old generic showDate preference suppress the native retained layer.
        val dateOpacity=OriginalCalendarComposition.dateOpacity(options.style,samePeriod=true,lowLight=night)
        if(dateOpacity>0f && options.style==0) {
            paint.alpha=(paint.alpha*dateOpacity).roundToInt()
            paint.textAlign=Paint.Align.LEFT;paint.typeface=fonts.calendar(options.calendarTypeface);paint.textSize=500f;paint.getTextBounds(date,0,date.length,bounds)
            // Existing Android date slot is retained pending original frame recovery.
            // Uniform fitting preserves the original glyph contour; no special drawing for day1.
            val dateScale=min(387f/bounds.height().coerceAtLeast(1),580f/bounds.width().coerceAtLeast(1))
            c.save();c.translate(491f-(bounds.left+bounds.width()/2f)*dateScale,532f-bounds.bottom*dateScale);c.scale(dateScale,dateScale);c.drawText(date,0f,0f,paint);c.restore()
        }
        // Month header + grid are one native c6bc4/cea10 subtree; never blur the page.
        // Existing date-style header/large-date composition remains pending native geometry.
        if(options.style==0){
            // Prior class2 fallback geometry stays an Android adapter; content now follows _header.
            val runs=OriginalCalendarHeader.runs(time,options.calendarHeader)
            paint.reset();paint.isAntiAlias=true;paint.typeface=medium;paint.textSize=43f
            var x=480f-runs.sumOf{paint.measureText(it.text).toDouble()}.toFloat()/2f
            for(run in runs){
                val ink=if(run.secondary)blue else red
                text(c,run.text,x,80f,43f,ink,medium);x+=paint.measureText(run.text)
            }
        }
        val sample=motion?:OriginalCalendarStyleTransition.settled(options.style==1)
        if(sample.opacity<=0f)return // Draw culling only; live motion state is retained by StandByView.
        c.save();c.scale(sample.scale,sample.scale,480f,300f)
        // c0d28 affects weekday/day grid, not ce3cc title; cf270 AND d57c4 call it.
        // Compositing inside a transparent RenderNode cannot see the retained date backdrop.
        // Restore the grid layer with the native blend mode on the parent Canvas instead.
        val multiply=OriginalCalendarCompositing.useMultiply(options.style,night,!dark,original.secondary)
        val gridComposite=Paint().apply {
            alpha=(sample.opacity.coerceIn(0f,1f)*255).roundToInt()
            if(multiply){
                if(android.os.Build.VERSION.SDK_INT>=29)blendMode=BlendMode.MULTIPLY
                else xfermode=PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
        if(motion!=null && android.os.Build.VERSION.SDK_INT>=31 && c.isHardwareAccelerated){
            // Shared native motion sample, separate recording scopes to keep title source-over.
            // Android layer adapter: blur/group equivalence to SwiftUI is still unverified.
            val node=monthNode?:RenderNode("CalendarPoster.month-subtree.grid").also{it.setPosition(0,0,960,600);it.setClipToBounds(false);monthNode=it}
            val title=monthTitleNode?:RenderNode("CalendarPoster.month-subtree.title").also{it.setPosition(0,0,960,600);it.setClipToBounds(false);monthTitleNode=it}
            val key="$time/$dark/$options/$night/${java.util.Locale.getDefault()}"
            if(key!=monthNodeKey){
                val rc=node.beginRecording();drawCalendarMonth(rc,time,dark,options,night);node.endRecording()
                val tc=title.beginRecording();drawCalendarMonthTitle(tc,time,dark,options,night);title.endRecording()
                monthNodeKey=key
            }
            val effect=if(sample.blur>.001f)RenderEffect.createBlurEffect(sample.blur,sample.blur,Shader.TileMode.DECAL)else null
            title.alpha=sample.opacity.coerceIn(0f,1f);title.setRenderEffect(effect);c.drawRenderNode(title)
            node.alpha=1f;node.setRenderEffect(effect)
            val gridLayer=c.saveLayer(0f,0f,960f,600f,gridComposite)
            c.drawRenderNode(node);c.restoreToCount(gridLayer)
        }else{
            // Static thumbnails and API<31/software fallback: no claimed blur parity.
            val titleLayer=c.saveLayerAlpha(0f,0f,960f,600f,(sample.opacity.coerceIn(0f,1f)*255).roundToInt())
            drawCalendarMonthTitle(c,time,dark,options,night);c.restoreToCount(titleLayer)
            val gridLayer=c.saveLayer(0f,0f,960f,600f,gridComposite)
            drawCalendarMonth(c,time,dark,options,night);c.restoreToCount(gridLayer)
        }
        c.restore()
    }
    /** Source-derived class2 endpoint renderer with EXPLICIT original-host inputs.
     * Not automatically selected from Android dp: the real scene/root contract
     * is unresolved. Fixed Text width and Grid origin are supplied by the caller
     * rather than smuggled in as native SwiftUI measurements. A retained position
     * spring sample drives the date; header transition samples apply to each
     * intrinsic branch, never the entire header column. Grid has a separate retained sample.
     * headerTextProposal supplies per-Text allocated widths, independent of the
     * outer header column. Null retains the previous unconstrained adapter; it
     * does NOT claim native fitting or infer the unknown inner Spacer allocation.
     */
    @JvmOverloads
    fun drawOriginalWideCalendar(c:Canvas,layout:OriginalCalendarWideLayout.Layout,
                                 gridOriginY:Float,measuredFixedDateWidth:Float,
                                 state:StandByState,time:LocalDateTime,
                                 datePosition:OriginalCalendarPositionSpring.Sample?=null,
                                 headerTransition:OriginalCalendarHeaderTransition.Sample?=null,
                                 gridTransition:OriginalCalendarStyleTransition.Sample?=null,
                                 headerTextProposal:OriginalCalendarTextFit.Proposal?=null){
        require(state.face==Face.CALENDAR && gridOriginY.isFinite())
        val scene=layout.scene;val o=state.options;val dark=state.night||o.dark
        val colors=OriginalCalendarColors.colors(o.calendarLook,dark)
        val primary=if(state.night)VisualTokens.NIGHT_RED else colors.numerals
        val secondary=if(state.night)VisualTokens.NIGHT_RED else colors.secondary
        c.drawColor(if(state.night)Color.BLACK else colors.background)
        val ratio=scene.height/874f
        val hx=layout.header.left;val hy=layout.header.top
        for(style in 0..1){
            val sample=if(headerTransition==null)OriginalCalendarStyleTransition.settled(style==o.style)
                       else if(style==0)headerTransition.date else headerTransition.month
            if(sample.opacity<=0f)continue
            val header=measureWideHeaderContent(style,ratio,time,o.calendarHeader,primary,secondary,headerTextProposal)
            val contentWidth=header.width;val contentHeight=header.height
            val save=c.save();c.translate(hx,hy);c.scale(sample.scale,sample.scale,contentWidth/2f,contentHeight/2f)
            if(headerTransition!=null && android.os.Build.VERSION.SDK_INT>=31 && c.isHardwareAccelerated){
                val node=wideHeaderNodes[style]?:RenderNode("CalendarPoster.class2.header.$style").also{wideHeaderNodes[style]=it}
                node.setPosition(0,0,ceil(contentWidth).toInt(),ceil(contentHeight).toInt());node.setClipToBounds(false)
                val key="$scene/$time/$style/${o.calendarHeader}/$primary/$secondary/$headerTextProposal/${Locale.getDefault()}"
                // RenderThread may discard a hidden branch display list; the content key alone is not a validity check.
                if(wideHeaderKeys[style]!=key || !node.hasDisplayList()){val rc=node.beginRecording();drawWideHeaderContent(rc,header);node.endRecording();wideHeaderKeys[style]=key}
                node.alpha=sample.opacity.coerceIn(0f,1f)
                node.setRenderEffect(if(sample.blur>.001f)RenderEffect.createBlurEffect(sample.blur,sample.blur,Shader.TileMode.DECAL)else null)
                c.drawRenderNode(node)
            }else{
                // Software keeps position/scale/alpha; native blur requires hardware path.
                val layer=c.saveLayerAlpha(null,(sample.opacity.coerceIn(0f,1f)*255).roundToInt())
                drawWideHeaderContent(c,header);c.restoreToCount(layer)
            }
            c.restoreToCount(save)
        }
        val save=c.save();c.translate(layout.right.left,layout.right.top)
        val opacity=OriginalCalendarComposition.dateOpacity(o.style,true,state.night)
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.calendar(o.calendarTypeface)
        paint.textSize=OriginalCalendarGeometry.dateFontSize(scene,time.dayOfMonth,o.nativeNumerals,o.calendarTypeface)
        paint.color=if(state.night)VisualTokens.NIGHT_RED else OriginalCalendarColors.dateInk(o.calendarLook,dark,o.style)
        paint.alpha=(paint.alpha*opacity).roundToInt();paint.textAlign=Paint.Align.CENTER
        val centerX=datePosition?.position?.toFloat()?:layout.dateCenterX(o.style,measuredFixedDateWidth,time.dayOfMonth,o.nativeNumerals)
        c.drawText(OriginalDigitalEditor.formatDigits(time.dayOfMonth.toString(),o.nativeNumerals),centerX,
            layout.dateCenterY-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f,paint)
        // c88c8: retained month GeometryReader owns StyleTransition(active,10).
        // Its bounds are the right proposal, not the Grid's intrinsic bounds.
        val gridSample=gridTransition?:OriginalCalendarStyleTransition.settled(o.style==1)
        if(gridSample.opacity>0f){
            c.save();c.scale(gridSample.scale,gridSample.scale,layout.right.width/2f,layout.right.height/2f)
            val hardware=gridTransition!=null && android.os.Build.VERSION.SDK_INT>=31 && c.isHardwareAccelerated
            val key="$layout/$gridOriginY/$time/$o/${state.night}/${Locale.getDefault()}"
            val multiply=OriginalCalendarCompositing.useMultiply(o.style,state.night,!dark,colors.secondary)
            for(layer in 0..1){
                // d2528 today glyph is a normal sibling above the multiplied grid.
                val composite=Paint().apply {
                    alpha=(gridSample.opacity.coerceIn(0f,1f)*255).roundToInt()
                    if(layer==0 && multiply){
                        if(android.os.Build.VERSION.SDK_INT>=29)blendMode=BlendMode.MULTIPLY
                        else xfermode=PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    }
                }
                // No extra clipping rectangle: blur/marker may overflow the Grid.
                val layerSave=c.saveLayer(null,composite)
                if(hardware){
                    val node=wideGridNodes[layer]?:RenderNode("CalendarPoster.class2.grid.$layer").also{wideGridNodes[layer]=it}
                    node.setPosition(0,0,ceil(layout.right.width).toInt(),ceil(layout.right.height).toInt())
                    node.setClipToBounds(false)
                    if(wideGridKey!=key || !node.hasDisplayList()){
                        val rc=node.beginRecording()
                        drawWideGridLayer(rc,layout,gridOriginY,state,time,layer)
                        node.endRecording()
                    }
                    node.setRenderEffect(if(gridSample.blur>.001f)RenderEffect.createBlurEffect(gridSample.blur,gridSample.blur,Shader.TileMode.DECAL)else null)
                    c.drawRenderNode(node)
                }else drawWideGridLayer(c,layout,gridOriginY,state,time,layer)
                c.restoreToCount(layerSave)
            }
            if(hardware)wideGridKey=key
            c.restore()
        }
        c.restoreToCount(save)
    }

    /** Two paint layers preserve original normal-today vs multiplied-grid order.
     * Hardware blur/opacity grouping remains an Android adapter, not pixel parity. */
    private fun drawWideGridLayer(c:Canvas,layout:OriginalCalendarWideLayout.Layout,gridOriginY:Float,
                                  state:StandByState,time:LocalDateTime,layer:Int){
        val o=state.options;val dark=state.night||o.dark
        val colors=OriginalCalendarColors.colors(o.calendarLook,dark)
        val secondary=if(state.night)VisualTokens.NIGHT_RED else colors.secondary
        val grid=layout.timeline;val locale=calendarLocale();val today=time.toLocalDate()
        val first=today.withDayOfMonth(1);val start=locale.gridStart(first)
        val font=OriginalCalendarGeometry.timelineFont(layout.scene)
        if(layer==0){
            locale.weekdayLabels.forEachIndexed{i,label->compactCellText(c,label,grid.columnCenter(i),gridOriginY+grid.weekdayCenterY(),font,secondary)}
            val outside=if(state.night)Color.rgb(62,12,14)else OriginalCalendarColors.outsideMonth(o.calendarLook,dark)
            repeat(42){i->
                val date=start.plusDays(i.toLong());val x=grid.columnCenter(i%7);val y=gridOriginY+grid.dayCenterY(i/7)
                if(date==today){paint.reset();paint.isAntiAlias=true;paint.color=secondary;c.drawCircle(x,y,OriginalCalendarGeometry.timelineTodayDiameter(layout.scene)/2f,paint)}
                else compactCellText(c,OriginalDigitalEditor.formatDigits(date.dayOfMonth.toString(),o.nativeNumerals),x,y,font,if(date.month==first.month)secondary else outside)
            }
        }else{
            val index=java.time.temporal.ChronoUnit.DAYS.between(start,today).toInt()
            if(index in 0..41)compactCellText(c,OriginalDigitalEditor.formatDigits(today.dayOfMonth.toString(),o.nativeNumerals),
                grid.columnCenter(index%7),gridOriginY+grid.dayCenterY(index/7),font,if(state.night)Color.BLACK else colors.background)
        }
    }

    private data class WideHeaderRun(val text:String,val x:Float,val baseline:Float,val fontSize:Float,val ink:Int)
    private data class WideHeaderContent(val runs:List<WideHeaderRun>,val width:Float,val height:Float)
    private fun measureWideHeaderContent(style:Int,ratio:Float,time:LocalDateTime,selection:CalendarHeaderSelection,primary:Int,secondary:Int,
                                         proposal:OriginalCalendarTextFit.Proposal?):WideHeaderContent {
        val size=(if(style==0)50f else 30f)*ratio
        fun fit(label:String,width:Float?):OriginalCalendarTextFit.Result {
            paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textSize=size
            return if(width==null)OriginalCalendarTextFit.Result(label,size,paint.measureText(label),1f,false)
                   else OriginalCalendarTextFit.fit(label,paint,width)
        }
        fun metrics(size:Float):Paint.FontMetrics {paint.textSize=size;return paint.fontMetrics}
        if(style==0){
            // cabfc: E + remainder uses HStack(spacing:0); EEEE / LLLL is one Text.
            val labels=OriginalCalendarHeader.runs(time,selection).map{it.text to if(it.secondary)secondary else primary}
            if(labels.size==1){
                // Existing proposal describes TWO allocated Text runs, not the native single-Text proposal.
                // Keep unconstrained measurement until that separate allocation is recovered.
                val label=fit(labels[0].first,null);val fm=metrics(label.fontSize)
                return WideHeaderContent(listOf(WideHeaderRun(label.text,0f,-fm.ascent,label.fontSize,labels[0].second)),label.width,fm.descent-fm.ascent)
            }
            val first=fit(labels[0].first,proposal?.firstDateRun)
            val second=fit(labels[1].first,proposal?.secondDateRun)
            val fm1=metrics(first.fontSize);val fm2=metrics(second.fontSize)
            val height=max(fm1.descent-fm1.ascent,fm2.descent-fm2.ascent)
            return WideHeaderContent(listOf(
                WideHeaderRun(first.text,0f,height/2f-(fm1.ascent+fm1.descent)/2f,first.fontSize,labels[0].second),
                WideHeaderRun(second.text,first.width,height/2f-(fm2.ascent+fm2.descent)/2f,second.fontSize,labels[1].second)
            ),first.width+second.width,height)
        }
        // d4910: separately constrained Text rows. Keep both40*A/874 frames;
        // do not shrink the row spacing or reuse a single scale for both lines.
        val lineH=40f*ratio
        val month=fit(calendarLocale().monthTitle(time.toLocalDate()),proposal?.monthYear)
        val clock=fit(OriginalCalendarHeader.time(time),proposal?.monthTime)
        val runs=listOf(month,clock).mapIndexed { index,result ->
            val fm=metrics(result.fontSize)
            WideHeaderRun(result.text,0f,(index+.5f)*lineH-(fm.ascent+fm.descent)/2f,result.fontSize,primary)
        }
        return WideHeaderContent(runs,max(month.width,clock.width),2f*lineH)
    }
    private fun drawWideHeaderContent(c:Canvas,header:WideHeaderContent){
        for(run in header.runs){
            paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textSize=run.fontSize
            paint.color=run.ink;paint.textAlign=Paint.Align.LEFT
            c.drawText(run.text,run.x,run.baseline,paint)
        }
    }

    /** Native class0/1 local geometry; Android text baseline/scene input remain adapters.
     * __measuredTVOSSafeArea is zero on this non-tvOS host. No max/min frame normalization.
     */
    private fun nativeClass01Calendar(c:Canvas,scene:OriginalCalendarGeometry.SceneFrame,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean,motion:OriginalCalendarStyleTransition.Sample?){
        val g=OriginalCalendarGeometry
        val original=OriginalCalendarColors.colors(o.calendarLook,dark)
        if(o.style==0){
        val opacity=OriginalCalendarComposition.dateOpacity(o.style,samePeriod=true,lowLight=night)
        val date=OriginalDigitalEditor.formatDigits(time.dayOfMonth.toString(),o.nativeNumerals)
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.calendar(o.calendarTypeface)
        paint.textSize=g.dateFontSize(scene,time.dayOfMonth,o.nativeNumerals,o.calendarTypeface)
        paint.color=if(night)VisualTokens.NIGHT_RED else OriginalCalendarColors.dateInk(o.calendarLook,dark,o.style)
        paint.alpha=(paint.alpha*opacity).roundToInt();paint.textAlign=Paint.Align.CENTER
        // SwiftUI frame526x316 + fixedSize centers the text without glyph-bounds fitting.
        // Android fontMetrics is the explicit line-baseline adapter, not measured UIKit parity.
        val fm=paint.fontMetrics
        val frameLeft=(scene.width-g.DATE_FRAME_WIDTH)/2f
        val frameTop=(scene.height-g.DATE_FRAME_HEIGHT)/2f
        val x=frameLeft+g.DATE_FRAME_WIDTH/2f+g.compactDateXOffset(time.dayOfMonth,o.nativeNumerals,o.calendarTypeface)
        val y=frameTop+g.DATE_FRAME_HEIGHT/2f+g.dateYOffset(scene.aspectClass)
        c.drawText(date,x,y-(fm.ascent+fm.descent)/2f,paint)
        drawNativeDateHeader(c,scene,time,dark,o,night)
        }
        val sample=motion?:OriginalCalendarStyleTransition.settled(o.style==1)
        if(sample.opacity<=0f)return
        val multiply=OriginalCalendarCompositing.useMultiply(o.style,night,!dark,original.secondary)
        c.save();c.scale(sample.scale,sample.scale,scene.width/2f,scene.height/2f)
        val hw=motion!=null && android.os.Build.VERSION.SDK_INT>=31 && c.isHardwareAccelerated
        val key="$scene/$time/$dark/$o/$night/${Locale.getDefault()}"
        val changed=key!=compactMonthKey
        for(layer in 0..2){
            val composite=Paint().apply {
                alpha=(sample.opacity.coerceIn(0f,1f)*255).roundToInt()
                if(layer==1&&multiply){
                    if(android.os.Build.VERSION.SDK_INT>=29)blendMode=BlendMode.MULTIPLY
                    else xfermode=PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                }
            }
            // Native month wrappers do not clip their overflowing content to scene size.
            // Retain the actual parent Canvas clip, not an added scene-frame rectangle.
            val saved=c.saveLayer(null,composite)
            if(hw){
                val node=compactMonthNodes[layer]?:RenderNode("CalendarPoster.compact.month.$layer").also{compactMonthNodes[layer]=it}
                node.setPosition(0,0,ceil(scene.width).toInt(),ceil(scene.height).toInt());node.setClipToBounds(false)
                // HWUI can discard off-screen display lists without changing the content key.
                if(changed || !node.hasDisplayList()){val rc=node.beginRecording();drawCompactMonthLayer(rc,scene,time,dark,o,night,layer);node.endRecording()}
                node.setRenderEffect(if(sample.blur>.001f)RenderEffect.createBlurEffect(sample.blur,sample.blur,Shader.TileMode.DECAL)else null)
                c.drawRenderNode(node)
            }else drawCompactMonthLayer(c,scene,time,dark,o,night,layer)
            c.restoreToCount(saved)
        }
        if(hw)compactMonthKey=key
        c.restore()
    }
    private fun drawNativeDateHeader(c:Canvas,scene:OriginalCalendarGeometry.SceneFrame,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean){
        val h=OriginalCalendarHeader;val colors=OriginalCalendarColors.colors(o.calendarLook,dark)
        // cabfc splits E+remainder or renders one full-format Text; never a showTime toggle.
        // Native _isEditingWidget white treatment remains separate from the Android poster editor.
        val dayInk=if(night)VisualTokens.NIGHT_RED else colors.numerals
        val timeInk=if(night)VisualTokens.NIGHT_RED else colors.secondary
        val runs=h.runs(time,o.calendarHeader).map{it.text to if(it.secondary)timeInk else dayInk}
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textSize=h.fontSize(scene.aspectClass)
        paint.textAlign=Paint.Align.LEFT
        val frameLeft=(scene.width-h.DATE_FORMAT_FRAME_WIDTH)/2f
        val frameTop=(scene.height-h.frameHeight(scene.aspectClass))/2f+h.offsetY(scene.aspectClass)
        val baseline=frameTop+h.frameHeight(scene.aspectClass)/2f-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f
        var x=frameLeft+(h.DATE_FORMAT_FRAME_WIDTH-runs.sumOf{paint.measureText(it.first).toDouble()}.toFloat())/2f
        for((label,ink)in runs){paint.color=ink;c.drawText(label,x,baseline,paint);x+=paint.measureText(label)}
        // Calendar header selection is independent of the retained legacy month showTime flag.
        // Bidi shaping, line-box metrics and low-light color remain Android adapters.
    }
    private fun compactCellText(c:Canvas,label:String,x:Float,centerY:Float,size:Float,color:Int,tracking:Float=0f){
        paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.semibold;paint.textSize=size;paint.color=color;paint.textAlign=Paint.Align.CENTER
        paint.letterSpacing=tracking/size
        val baseline=centerY-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f
        c.drawText(label,x,baseline,paint)
    }

    private fun drawCompactMonthLayer(c:Canvas,scene:OriginalCalendarGeometry.SceneFrame,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean,layer:Int){
        android.util.Log.i("DuoCal","compact layer="+layer)
        val g=OriginalCalendarGeometry;val locale=calendarLocale();val colors=OriginalCalendarColors.colors(o.calendarLook,dark)
        val cls=scene.aspectClass;val timeline=cls==1
        val size=g.headerFontSize(cls)
        paint.reset();paint.typeface=fonts.semibold;paint.textSize=size
        // Class1 has an explicit38pt outer header; time's43pt child may overflow.
        val headerH=if(timeline)38f else max(43f,paint.fontMetrics.descent-paint.fontMetrics.ascent)
        val frameH=if(timeline)g.CLASS1_GRID_FRAME_HEIGHT else g.COMPACT_GRID_HEIGHT
        val total=g.topSpacer(cls)+headerH+g.middleSpacer(cls)+frameH
        val top=(scene.height-total)/2f+g.MONTH_Y_OFFSET // + measuredTVOSSafeArea.top(0 on Android)
        val headerY=top+g.topSpacer(cls)+headerH/2f
        if(layer==0){
            val ink=if(night)VisualTokens.NIGHT_RED else colors.numerals
            val left=g.headerLeading(cls);val right=scene.width-g.headerTrailing(cls)
            val title=locale.monthTitle(time.toLocalDate());val clock=OriginalCalendarHeader.time(time)
            paint.reset();paint.isAntiAlias=true;paint.typeface=fonts.calendar(o.calendarTypeface);paint.textSize=size;paint.color=ink;paint.letterSpacing=g.TITLE_TRACKING/size
            val baseline=headerY-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f
            paint.textAlign=Paint.Align.LEFT;c.drawText(title,left,baseline,paint)
            if(o.showTime){paint.textAlign=Paint.Align.RIGHT;c.drawText(clock,right,baseline,paint)}
            // Original min32pt spacer/truncation still needs Android layout parity; tracking uses native -0.25pt.
            return
        }
        val gridLeft=if(timeline)g.CLASS1_GRID_INSET else (scene.width-g.COMPACT_GRID_WIDTH)/2f
        val gridTop=top+g.topSpacer(cls)+headerH+g.middleSpacer(cls)
        val layout=if(timeline)g.timelineLayout(scene,scene.width-2f*g.CLASS1_GRID_INSET,frameH)else null
        // Preserve GeometryReader frame21 without clipping the fixed cells.
        // Original SwiftUI source + macOS same-structure probe: overflow starts at top0,
        // not a guessed negative center offset. Actual iOS/Duo proposal remains unverified.
        val gridSize=if(timeline)g.timelineFont(scene)else size
        val todayDiameter=if(timeline)g.timelineTodayDiameter(scene)else g.COMPACT_CELL
        fun column(i:Int)=layout?.columnCenter(i)?:g.columnCenter(i)
        fun dayY(i:Int)=layout?.dayCenterY(i)?:g.dayCenterY(i)
        val weekdayY=layout?.weekdayCenterY()?:g.weekdayCenterY()
        val secondary=if(night)Color.rgb(160,36,38)else colors.secondary
        val outside=if(night)Color.rgb(62,12,14)else OriginalCalendarColors.outsideMonth(o.calendarLook,dark)
        val today=time.toLocalDate();val first=today.withDayOfMonth(1);val start=locale.gridStart(first)
        if(layer==1)locale.weekdayLabels.forEachIndexed{i,label->compactCellText(c,label,gridLeft+column(i),gridTop+weekdayY,gridSize,secondary,if(timeline)0f else g.TITLE_TRACKING)}
        // eb684 has two fixed loops:6 rows x7 days, including adjacent-month dates.
        repeat(g.WEEK_ROWS*g.WEEK_COLUMNS){index->
            val date=start.plusDays(index.toLong());val x=gridLeft+column(index%7);val y=gridTop+dayY(index/7)
            if(layer==1){
                if(date==today){
                    // Giant current-day numeral in the same column, behind the grid markers.
                    val colStep=column(1)-column(0)
                    val giantText=today.dayOfMonth.toString()
                    paint.reset();paint.isAntiAlias=true
                    paint.typeface=fonts.calendar(o.calendarTypeface)
                    paint.textSize=400f;paint.getTextBounds(giantText,0,giantText.length,giantBounds)
                    val colTop=gridTop+weekdayY;val colBottom=gridTop+dayY(5)
                    val targetH=(colBottom-colTop)+gridSize*1.6f
                    val gscale=min((colStep*0.9f)/giantBounds.width().coerceAtLeast(1),targetH/giantBounds.height().coerceAtLeast(1))
                    val centerY=(colTop+colBottom)/2f
                    paint.color=if(night)VisualTokens.NIGHT_RED else colors.numerals
                    c.save();c.translate(x-(giantBounds.left+giantBounds.width()/2f)*gscale,
                        centerY-giantBounds.exactCenterY()*gscale);c.scale(gscale,gscale);c.drawText(giantText,0f,0f,paint);c.restore()
                    paint.reset();paint.isAntiAlias=true;paint.color=secondary;c.drawCircle(x,y,todayDiameter/2f,paint)
                    // Base day text is clear while the marker is present. Today text is layer2.
                }else compactCellText(c,OriginalDigitalEditor.formatDigits(date.dayOfMonth.toString(),o.nativeNumerals),x,y,gridSize,if(date.month==first.month)secondary else outside)
            }else if(date==today){
                // d2528..d2554: explicit BlendMode.normal, NOT flattened into multiplied grid.
                compactCellText(c,OriginalDigitalEditor.formatDigits(date.dayOfMonth.toString(),o.nativeNumerals),x,y,gridSize,if(night)Color.BLACK else colors.background)
            }
        }
    }
    private fun drawCalendarMonthTitle(c:Canvas,time:LocalDateTime,dark:Boolean,options:FaceOptions,night:Boolean){
        android.util.Log.i("DuoCal","title")
        val red=if(night)VisualTokens.NIGHT_RED else OriginalCalendarColors.colors(options.calendarLook,dark).numerals
        val headerFace=fonts.calendar(options.calendarTypeface)
        text(c,calendarLocale().monthTitle(time.toLocalDate()),90f,80f,43f,red,headerFace)
        if(options.showTime)text(c,OriginalCalendarHeader.time(time),871f,80f,43f,red,headerFace,Paint.Align.RIGHT)
    }
    private val giantBounds=android.graphics.Rect()
    private fun getTextBoundsForGiant(paint:Paint,text:String){ paint.getTextBounds(text,0,text.length,giantBounds) }
    private fun drawCalendarMonth(c:Canvas,time:LocalDateTime,dark:Boolean,options:FaceOptions,night:Boolean){
        android.util.Log.i("DuoCal","grid")
        val calendarLocale=calendarLocale()
        val original=OriginalCalendarColors.colors(options.calendarLook,dark)
        val blue=if(night)Color.rgb(160,36,38)else original.secondary
        val muted=if(night)Color.rgb(62,12,14)else OriginalCalendarColors.outsideMonth(options.calendarLook,dark)
        // Month style: the current day's number is enlarged in its own column and sits
        // behind the grid (reference: CalendarPosterExtension style=month, Red-Blue look).
        val todayDate=time.toLocalDate()
        val firstOfMonth=todayDate.withDayOfMonth(1)
        val gridStart=calendarLocale.gridStart(firstOfMonth)
        val todayColumn=((todayDate.toEpochDay()-gridStart.toEpochDay())%7).toInt()
        if(todayColumn in 0..6){
            val text=todayDate.dayOfMonth.toString()
            paint.reset();paint.isAntiAlias=true
            paint.typeface=fonts.calendar(options.calendarTypeface)
            paint.textSize=500f;getTextBoundsForGiant(paint,text)
            val targetW=104f
            val scale=targetW/giantBounds.width().coerceAtLeast(1)
            paint.color=if(night)VisualTokens.NIGHT_RED else original.numerals
            val cx=130f+todayColumn*116.7f
            c.save();c.translate(cx-(giantBounds.left+giantBounds.width()/2f)*scale,372f-giantBounds.bottom*scale)
            c.scale(scale,scale);c.drawText(text,0f,0f,paint);c.restore()
        }
        val headers=calendarLocale.weekdayLabels
        headers.forEachIndexed { i,s -> text(c,s,130f+i*116.7f,148f,44f,blue,medium,Paint.Align.CENTER) }
        val first=time.toLocalDate().withDayOfMonth(1)
        val start=calendarLocale.gridStart(first)
        repeat(42) { index ->
            val d=start.plusDays(index.toLong());val x=130f+(index%7)*116.7f;val y=205f+(index/7)*69f
            if(d==time.toLocalDate()) { paint.color=if(night) Color.rgb(140,22,25) else original.secondary;c.drawCircle(x,y,38f,paint) }
            val color=when { d==time.toLocalDate()->if(night) Color.BLACK else original.background;d.month!=first.month->muted;else->blue }
            text(c,d.dayOfMonth.toString(),x,y+15f,44f,color,numberFace(options.numerals),Paint.Align.CENTER)
        }
    }
    /** Original default font/size/anchor rules. Android text-frame metrics remain an adapter,
     * not verified SwiftUI TimeLabel baseline equivalence. Missing weather slots stay empty. */
    private fun weatherFace(c:Canvas,w:Float,h:Float,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean){
        WeatherFaceContent.draw(c,w,h,time,
            android.text.format.DateFormat.is24HourFormat(appContext),DigitalWeatherRepository.current,fonts,night,appContext,o.weatherPhase)
    }
    private fun originalDigital(c:Canvas,w:Float,h:Float,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean){
        val wd=w.toDouble();val hd=h.toDouble();val cls=OriginalDigitalTypography.sizeClass(wd,hd)
        val ink=if(night)VisualTokens.NIGHT_RED else OriginalDigitalColors.colors(o.digitalLook,dark).numerals
        val is24=android.text.format.DateFormat.is24HourFormat(appContext)
        val narrow=OriginalDigitalTypography.widthAxis(time.hour,is24,o.nativeNumerals)==38
        val size=OriginalDigitalTypography.fontSize(wd,hd).toFloat()
        paint.reset();paint.isAntiAlias=true;paint.color=ink;paint.typeface=fonts.digital(o.digitalTypeface,narrow)
        paint.textSize=size;paint.fontFeatureSettings="'ss03' 1";paint.textAlign=Paint.Align.CENTER
        val fm=paint.fontMetrics
        val q=w*.2f;val safe=if(cls==1)34f else 0f
        val x=if(cls==0)w/2 else safe+(w-safe-safe-q)/2
        // Compact bottom anchor H-60+abs(descender), minus Android descent => baseline H-60.
        // Wide center anchor uses Android text line ascent/descent, explicitly not UIKit parity.
        val baseline=if(cls==0)h-60f else h/2-(fm.ascent+fm.descent)/2
        if(o.showTime)c.drawText(OriginalDigitalEditor.formatDigits(time.format(DateTimeFormatter.ofPattern(if(is24)"HH:mm" else "h:mm",Locale.US)),o.nativeNumerals),x,baseline,paint)
        if(o.showDate){
            val f=OriginalDigitalTypography.widgetFontSize(wd,hd).toFloat()
            paint.typeface=fonts.semibold;paint.textSize=f;paint.fontFeatureSettings=null;paint.textAlign=Paint.Align.LEFT
            val dfm=paint.fontMetrics;paint.getTextBounds("H",0,1,bounds);val k=bounds.height().toFloat()
            val boxH=if(cls==0)kotlin.math.round(1.6f*f)else f
            val y=if(cls==0)78f-boxH/2 else (h-size*OriginalDigitalTypography.CAP_HEIGHT_RATIO.toFloat())/2-(boxH-k)/2
            // Locale weekday+day pattern, matching the reference face ("23 周五"); the
            // previous uppercased fixed pattern was an adapter.
            val locale=appContext.resources.configuration.locales[0] ?: Locale.getDefault()
            val datePattern=android.text.format.DateFormat.getBestDateTimePattern(locale,"Ed")
            val dateText=time.format(DateTimeFormatter.ofPattern(datePattern,locale))
            val dateX=if(cls==0)60f else w-safe-q
            // Reference photo/video: the day number is ~1.45x the weekday label size
            // ("23 周五" / "WED 1"), not one uniform string as we drew before.
            val dateParts=dateText.split(" ",limit=2)
            val dayPart=dateParts.firstOrNull{it.any{c->c.isDigit()}} ?: dateText
            val weekdayPart=dateParts.firstOrNull{it!==dayPart} ?: ""
            val daySize=f*1.45f
            val baselineY=y-dfm.ascent
            paint.textSize=daySize
            val dayWidth=paint.measureText(dayPart)
            if(weekdayPart.isNotEmpty()&&!dateText.startsWith(weekdayPart.trim())){
                c.drawText(dayPart,dateX,baselineY,paint)
                paint.textSize=f
                c.drawText(weekdayPart,dateX+dayWidth+f*0.35f,baselineY,paint)
            }else if(weekdayPart.isNotEmpty()){
                paint.textSize=f
                c.drawText(weekdayPart,dateX,baselineY,paint)
                val weekdayWidth=paint.measureText(weekdayPart)
                paint.textSize=daySize
                c.drawText(dayPart,dateX+weekdayWidth+f*0.35f,baselineY,paint)
            }else{
                c.drawText(dayPart,dateX,baselineY,paint)
            }
            paint.textSize=daySize
            val dateWidth=paint.measureText(dayPart)+if(weekdayPart.isEmpty())0f else paint.textSize*0f+f*0.35f+
                runCatching{paint.textSize=f;paint.measureText(weekdayPart)}.getOrDefault(0f)
            val widgetsColor=if(night)VisualTokens.NIGHT_RED else OriginalDigitalColors.colors(o.digitalLook,dark).widgets
            DigitalFaceWidgets.draw(c,w,baselineY,dateX+dateWidth,
                f,widgetsColor,fonts.semibold,DigitalWeatherRepository.current,
                DigitalWeatherRepository.nextSunEvent(time.toLocalTime()))
        }
    }
    private fun digital(c:Canvas,time:LocalDateTime,dark:Boolean,options:FaceOptions,night:Boolean) {
        // Digital poster palette: light color-0 is #A4B89D with black numerals/date and
        // white widget content; the photo reference matches these recovered values.
        val digitalColors=OriginalDigitalColors.colors(options.digitalLook,dark)
        val ink=if(night) VisualTokens.NIGHT_RED else digitalColors.numerals
        val dateInk=if(night) VisualTokens.NIGHT_RED else digitalColors.date
        val locale=appContext.resources.configuration.locales[0] ?: Locale.getDefault()
        // Real face uses the locale's weekday+day pattern (zh: "23 周五", en: "Sat 24"),
        // not an uppercased fixed pattern.
        val datePattern=android.text.format.DateFormat.getBestDateTimePattern(locale,"Ed")
        val dateFmt=DateTimeFormatter.ofPattern(datePattern,locale)
        if(options.showDate) text(c,time.format(dateFmt),96f,100f,27f,dateInk,medium)
        if(!options.showTime)return
        val s=time.format(shortTime)
        paint.reset();paint.isAntiAlias=true
        // Poster descriptor typeface is softNumeric; ADTNumeric ttcIndex 11 is the
        // recovered .SFSoftNumeric-Semibold instance.
        paint.typeface=if(options.numerals==0)fonts.softCompressedSemibold else numberFace(options.numerals)
        paint.textSize=500f;paint.color=ink
        paint.getTextBounds(s,0,s.length,bounds)
        val sx=(if(options.style==0)720f else 600f)/bounds.width().coerceAtLeast(1);val sy=390f/bounds.height().coerceAtLeast(1)
        c.save();c.translate(480f-(bounds.left+bounds.width()/2f)*sx,511f-bounds.bottom*sy);c.scale(sx,sy);c.drawText(s,0f,0f,paint);c.restore()
    }
    private fun analog(c:Canvas,w:Float,h:Float,time:LocalDateTime,dark:Boolean,o:FaceOptions,night:Boolean) {
        val original=OriginalAnalogParameters.colors(o.palette,dark)
        val numeralInk=if(night)VisualTokens.NIGHT_RED else original.primary
        val minorTick=Color.argb((Color.alpha(numeralInk)*OriginalAnalogParameters.MINOR_TICK_ALPHA).roundToInt(),
            Color.red(numeralInk),Color.green(numeralInk),Color.blue(numeralInk))
        analogFaceCanvas.draw(c,w,h,density,time,o.style,o.nativeNumerals,fonts.analog,numeralInk,minorTick,
            if(night)VisualTokens.NIGHT_RED else original.secondary,
            if(night)VisualTokens.NIGHT_RED else original.accent) {
        // Ancillary labels retain their pre-existing independent legacy adapter.
        // Their original widget placement/material is not part of this geometry closure.
        if(o.showTime||o.showDate){
            val ink=if(night)VisualTokens.NIGHT_RED else if(dark)palette(o.palette,Color.rgb(164,161,151)) else Color.rgb(22,22,20)
            val scale=min(w/960f,h/600f);val saved=c.save()
            c.translate((w-960f*scale)/2f,(h-600f*scale)/2f);c.scale(scale,scale)
            if(o.showTime)text(c,time.format(secondsTime),330f,309f,25f,ink,medium,Paint.Align.CENTER)
            if(o.showDate)text(c,time.format(localizedDate),630f,309f,25f,ink,medium,Paint.Align.CENTER)
            c.restoreToCount(saved)
        }
        }
    }
    private fun hand(c:Canvas,x:Float,y:Float,angle:Float,len:Float,width:Float,color:Int) {
        val a=Math.toRadians(angle.toDouble());paint.reset();paint.isAntiAlias=true;paint.color=color;paint.strokeWidth=width;paint.strokeCap=Paint.Cap.ROUND
        c.drawLine(x-sin(a).toFloat()*24f,y+cos(a).toFloat()*24f,x+sin(a).toFloat()*len,y-cos(a).toFloat()*len,paint)
    }
    private fun miniCalendar(c:Canvas,date:LocalDate,color:Int) {
        val calendarLocale = calendarLocale()
        text(c,calendarLocale.monthName(date),62f,310f,18f,color,medium)
        val first=date.withDayOfMonth(1);val start=calendarLocale.gridStart(first)
        calendarLocale.weekdayLabels.forEachIndexed{i,s->text(c,s,69f+i*31f,342f,17f,color,normal,Paint.Align.CENTER)}
        repeat(42){i->val day=start.plusDays(i.toLong());val x=69f+i%7*31f;val y=374f+i/7*28f
            if(day==date){paint.color=color;c.drawCircle(x,y-6f,12f,paint)}
            val faded=Color.argb(if(day.month==date.month)255 else 95,Color.red(color),Color.green(color),Color.blue(color))
            text(c,day.dayOfMonth.toString(),x,y,16f,if(day==date)Color.BLACK else faded,normal,Paint.Align.CENTER)
        }
    }
}
