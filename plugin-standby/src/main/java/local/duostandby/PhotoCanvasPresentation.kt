package local.duostandby

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Stateful Canvas adapter for the already-authorized Xiaomi curated-photo source.
 *
 * Native facts: Photos uses separate image/time/legibility layers; the v30 standard
 * gradient has .30 darken + .20 normal endpoints for strength=2.0. Native crop
 * selection needs face/pet/saliency/segmentation inputs that this provider doesn't
 * expose. FILL_CENTER_ADAPTER is an explicit Android fallback, NOT recovered Apple
 * subject-safe cropping. The 450ms smoothstep crossfade is likewise an Android
 * presentation policy; no claim that this is Apple's photo-transition curve.
 *
 * All methods are owned by the drawing/UI thread. No reads, permissions, shared
 * image mutations, public exports, bitmap.recycle(), or invented photo metadata.
 */
class PhotoCanvasPresentation {
    enum class CropMode { FILL_CENTER_ADAPTER, FIT_WHOLE_PHOTO }
    var cropMode = CropMode.FILL_CENTER_ADAPTER
    var transitionDurationMillis: Long = 450L
        set(value) { field = value.coerceIn(100L, 1500L) }
    var timeTypeface: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    /** Native FontSpec width/weight are variable-font axes; the host supplies the
     * bundled .SFSoftNumeric instance. Null keeps the platform fallback. */
    var timeTypefaceFactory: ((Double?, Double?) -> Typeface)? = null
    // PhotosPosterMetadataView uses 33/24-point semibold; the host injects its SFUI font.
    var metadataTypeface: Typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
    var showSourceLabel = false
    /** Retained for source compatibility only. The PhotosPoster branch owns its
     * category-specific padding; ordinary Ambient compact safe insets do not apply. */
    var metadataSafeInsetsPx = OriginalPhotoCompactMetadata.SafeInsets()
    /** Native category raw value, supplied from the actual device by the host.
     * 0=wide screen, 1=home button, 2=standard; never infer it from a scaled preview. */
    var metadataDeviceCategoryRaw: Int = 2
    var metadataLayoutRtl: Boolean = false
    private class MetadataPoster {
        val controller = OriginalPhotosPosterMetadataController()
        // A node belongs to one asset: re-recording a shared RenderNode during one
        // frame would otherwise make both moving posters display the last asset.
        val renderers = mutableMapOf<String, OriginalPhotosMetadataCanvas>()
    }
    private val metadataPosters = mutableMapOf<String, MetadataPoster>()
    private var metadataActivePoster: String? = null
    private var metadataVisibleSnapshot: XiaomiCuratedPhotoSource.Snapshot? = null
    private var metadataGate = OriginalPhotosPosterMetadataController.Gate()
    /** Original: legibility scrim alpha is multiplied by __metadataGradientOpacity (0 when hidden). */
    private var legibilityGradientOpacity = 0f
    var assetContext: android.content.Context? = null
    private val symbolBitmaps = mutableMapOf<String, Bitmap?>()
    private fun photoSymbol(name: String): Bitmap? = synchronized(symbolBitmaps) {
        symbolBitmaps.getOrPut(name) {
            runCatching {
                assetContext?.assets?.open("photo-symbols/$name.png")?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    /** Real iPhone behaviour: metadata shows as soon as a photo page arrives, then auto-hides. */
    private val autoShownPosters = mutableSetOf<String>()
    val isMetadataShowing: Boolean get() = metadataPosters[metadataActivePoster]?.controller?.isShowingMetadata == true
    val hasPhotoMetadata: Boolean get() = metadataVisibleSnapshot?.let {
        !it.captureLocationName.isNullOrBlank() || it.captureDateTime != null
    } == true
    /** Host schedules one wake-up at this monotonic deadline, not a 7-second frame loop. */
    val metadataNextWakeNanos: Long? get() = metadataPosters.values.mapNotNull {
        it.controller.nextAutoHideDeadlineNanos
    }.minOrNull()

    /** Explicit Android host request. No asset change/onDraw/onResume auto-show:
     * original show is event-driven and Duo outer tap ownership is not recovered. */
    fun requestMetadata(nowNanos: Long = System.nanoTime()): Boolean {
        if (!hasPhotoMetadata) return false
        val poster = metadataPosters[metadataActivePoster] ?: return false
        val now = max(lastSampleNanos, nowNanos)
        lastSampleNanos = now
        return poster.controller.show(now, metadataGate)
    }

    fun hideMetadata(nowNanos: Long = System.nanoTime()): Boolean {
        val poster = metadataPosters[metadataActivePoster] ?: return false
        val now = max(lastSampleNanos, nowNanos)
        lastSampleNanos = now
        return poster.controller.hide(now, metadataGate)
    }

    /** Recovered PhotosClockPoster regions: 100-point leading and trailing strips
     * step the asset; the remaining poster shows or hides metadata. The source
     * HStack gap is still unresolved, and top/bottom share the show action with
     * center, so the gap is not given a stand-in value. A gallery-owned tap must
     * not call this. */
    fun tapPoster(x: Float, y: Float, bounds: RectF, pixelsPerPoint: Float, rtl: Boolean,
                  nowNanos: Long = System.nanoTime()): OriginalPhotosPosterMetadataController.TapResult {
        val poster = metadataPosters[metadataActivePoster] ?: return OriginalPhotosPosterMetadataController.TapResult(false)
        if (!pixelsPerPoint.isFinite() || pixelsPerPoint <= 0f || bounds.isEmpty) {
            return OriginalPhotosPosterMetadataController.TapResult(false)
        }
        val px = (x - bounds.left) / pixelsPerPoint.toDouble()
        val py = (y - bounds.top) / pixelsPerPoint.toDouble()
        val width = bounds.width() / pixelsPerPoint.toDouble()
        val height = bounds.height() / pixelsPerPoint.toDouble()
        if (px < 0.0 || py < 0.0 || px >= width || py >= height) {
            return OriginalPhotosPosterMetadataController.TapResult(false)
        }
        val region = when {
            px < 100.0 -> if (rtl) OriginalPhotosPosterMetadataController.TapRegion.TRAILING
                else OriginalPhotosPosterMetadataController.TapRegion.LEADING
            px >= width - 100.0 -> if (rtl) OriginalPhotosPosterMetadataController.TapRegion.LEADING
                else OriginalPhotosPosterMetadataController.TapRegion.TRAILING
            py < 100.0 -> OriginalPhotosPosterMetadataController.TapRegion.TOP
            py >= height - 100.0 -> OriginalPhotosPosterMetadataController.TapRegion.BOTTOM
            else -> OriginalPhotosPosterMetadataController.TapRegion.CENTER
        }
        val now = max(lastSampleNanos, nowNanos)
        lastSampleNanos = now
        return poster.controller.tap(region, now, metadataGate)
    }
    /** Actual Android root-window RoundedCorner radius in Canvas pixels. Zero means
     * unavailable; no invented fixed fallback or editor radius is substituted. */
    var windowCornerRadiusPx: Float = 0f
        set(value) { field = if (value.isFinite()) value.coerceAtLeast(0f) else 0f }
    private val verticalPresentation = OriginalPhotoPagingPresentation()

    /** Vertical asset paging inside the current configuration's selection.
     * The owner supplies its existing OriginalPagingMotion fraction; no curve here.
     * Negative goes to next; positive goes to previous. Only decoded neighbors draw. */
    var verticalDragFraction: Float = 0f
        set(value) { field = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f }
    private var verticalOrigin: XiaomiCuratedPhotoSource.Snapshot? = null
    private var verticalCommitTarget: String? = null

    fun expectVerticalCommit(inReverse: Boolean): Boolean {
        val origin = verticalOrigin ?: current ?: return false
        val target = (if (inReverse) origin.readyPrevious else origin.readyNext)
            ?.takeIf { it.phase == XiaomiCuratedPhotoSource.Phase.READY && it.bitmap?.isRecycled == false }
            ?: return false
        verticalCommitTarget = target.currentAssetIdentity?.takeIf { it != origin.currentAssetIdentity } ?: return false
        return true
    }

    fun cancelVerticalDrag() {
        verticalDragFraction = 0f; verticalOrigin = null; verticalCommitTarget = null
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var current: XiaomiCuratedPhotoSource.Snapshot? = null
    private var entering: XiaomiCuratedPhotoSource.Snapshot? = null
    private var pending: XiaomiCuratedPhotoSource.Snapshot? = null
    private var transitionStartNanos = 0L
    private var transitionLengthNanos = 450_000_000L
    private var lastSampleNanos = 0L
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        .2126f * .38f, .7152f * .38f, .0722f * .38f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))

    val isAnimating: Boolean get() = entering != null
    val visiblePhotoId: String? get() = current?.currentPhotoId
    val visiblePageId: String? get() = current?.pageId
    val targetPhotoId: String? get() = entering?.currentPhotoId ?: current?.currentPhotoId
    val queuedPhotoId: String? get() = pending?.currentPhotoId

    fun clear() {
        current = null; entering = null; pending = null
        cancelVerticalDrag()
        metadataPosters.clear()
        metadataActivePoster = null; metadataVisibleSnapshot = null
        metadataGate = OriginalPhotosPosterMetadataController.Gate()
        verticalPresentation.clear()
        transitionStartNanos = 0L; lastSampleNanos = 0L
    }

    private fun key(value: XiaomiCuratedPhotoSource.Snapshot): String =
        "${value.pageId}:${value.cardId}:${value.currentPhotoId}:${value.contentSha256}"

    private fun start(value: XiaomiCuratedPhotoSource.Snapshot, nowNanos: Long) {
        entering = value
        transitionStartNanos = nowNanos
        transitionLengthNanos = transitionDurationMillis * 1_000_000L
    }

    private fun advance(nowNanos: Long) {
        // If the UI thread was delayed, follow elapsed time rather than resetting
        // the kernel. At most two transitions can be outstanding (active + latest).
        while (entering != null && nowNanos - transitionStartNanos >= transitionLengthNanos) {
            val end = transitionStartNanos + transitionLengthNanos
            current = entering; entering = null
            val next = pending; pending = null
            if (next != null && current?.let { key(it) != key(next) } == true) start(next, end)
        }
    }

    private fun accept(value: XiaomiCuratedPhotoSource.Snapshot, nowNanos: Long, animated: Boolean) {
        if (value.phase == XiaomiCuratedPhotoSource.Phase.DISCONNECTED) { clear(); return }
        if (value.bitmap == null || value.bitmap.isRecycled) return
        val active = current
        if (active == null || !animated) {
            current = value; entering = null; pending = null; return
        }
        val next = entering
        if (next == null) {
            if (key(active) == key(value)) current = value else start(value, nowNanos)
        } else if (key(next) == key(value)) {
            // LOADING/ERROR updates may carry the same still-visible bitmap.
            // They update diagnostics, not the animation start or photo identity.
            entering = value; pending = null
        } else {
            // Don't abruptly drop/reweight either live layer on rapid selections.
            // Finish their continuous blend, then display only the latest request.
            // Current + entering + pending are the only retained presenter images.
            pending = value
        }
    }

    /** Draw once per frame; return true while another frame is needed.
     * clock is the REAL current clock supplied by the owner, not capture metadata.
     * captureDateTime is drawn separately only when EXIF DateTimeOriginal exists.
     * Call clear() when the owner releases this presentation, or on disconnect.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        snapshot: XiaomiCuratedPhotoSource.Snapshot,
        clock: LocalDateTime? = null,
        use24Hour: Boolean = true,
        density: Float = 1f,
        night: Boolean = false,
        animated: Boolean = true,
        nowNanos: Long = System.nanoTime(),
        renderingForEditorPreview: Boolean = false,
    ): Boolean {
        if (bounds.isEmpty) return false
        if (snapshot.phase == XiaomiCuratedPhotoSource.Phase.DISCONNECTED) clear()
        // A monotonic clock is required. Protect an owner accidentally supplying
        // a smaller timestamp without creating a negative fraction or a jump.
        val now = max(lastSampleNanos, nowNanos)
        lastSampleNanos = now
        val expected = verticalCommitTarget
        if (expected != null && snapshot.currentAssetIdentity == expected &&
            snapshot.phase == XiaomiCuratedPhotoSource.Phase.READY && snapshot.bitmap != null) {
            // The endpoint image is already fully on screen. Transfer ownership once,
            // without replaying the unrelated Android timed crossfade.
            accept(snapshot, now, false)
            cancelVerticalDrag()
        }
        val vertical = verticalDragFraction != 0f || verticalCommitTarget != null
        if (vertical) {
            if (verticalOrigin == null) verticalOrigin = current ?: snapshot
            val origin = verticalOrigin
            if (origin != null && origin.currentAssetIdentity == snapshot.currentAssetIdentity) {
                // A background prefetch may finish while held; source identity stays fixed.
                verticalOrigin = origin.copy(readyPrevious = snapshot.readyPrevious, readyNext = snapshot.readyNext)
            }
        } else {
            verticalOrigin = null
            advance(now)
            accept(snapshot, now, animated)
        }
        val save = canvas.save(); canvas.clipRect(bounds)
        paint.reset(); paint.color = Color.BLACK; canvas.drawRect(bounds, paint)
        val shown = if (vertical) verticalOrigin else current
        shown?.let {
            verticalPresentation.beginFrame(pageIdentity(it), listOfNotNull(it, it.readyPrevious, it.readyNext)
                .map(::pageIdentity).toSet())
        }
        metadataVisibleSnapshot = shown
        metadataActivePoster = shown?.let(::metadataPosterIdentity)
        legibilityGradientOpacity = metadataPosters[metadataActivePoster]?.controller
            ?.sample(now)?.gradientOpacity?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        metadataGate = OriginalPhotosPosterMetadataController.Gate(
            assetAvailable = shown?.bitmap?.isRecycled == false,
            renderingForEditorPreview = renderingForEditorPreview,
        )
        val metadataAssets = listOfNotNull(shown, if (vertical) null else entering,
            if (vertical) shown?.readyPrevious else null, if (vertical) shown?.readyNext else null)
        metadataPosters.keys.retainAll(metadataAssets.map(::metadataPosterIdentity).toSet())
        metadataPosters.forEach { (posterId, poster) ->
            poster.renderers.keys.retainAll(metadataAssets.filter { metadataPosterIdentity(it) == posterId }
                .map(::pageIdentity).toSet())
        }
        if (vertical && shown?.bitmap != null) {
            val fraction = verticalDragFraction
            val neighbor = (if (fraction > 0f) shown.readyPrevious else shown.readyNext)
                ?.takeIf { it.phase == XiaomiCuratedPhotoSource.Phase.READY && it.bitmap?.isRecycled == false }
            val displacement = if (neighbor != null) fraction * bounds.height() else 0f
            drawVerticalPage(canvas, bounds, shown, displacement, clock, use24Hour, density, night, now, animated)
            if (neighbor != null && fraction != 0f) {
                val adjacent = displacement + if (fraction < 0f) bounds.height() else -bounds.height()
                drawVerticalPage(canvas, bounds, neighbor, adjacent, clock, use24Hour, density, night, now, animated)
            }
        } else if (shown?.bitmap != null) {
            // Keep the incoming asset's presentation spring through ownership
            // transfer. A vertical commit is not a fresh timed asset crossfade.
            val wrapper = beginPosterWrapper(canvas, bounds, shown, 0f, now, animated)
            drawPhoto(canvas, bounds, shown.bitmap, night)
            drawCaptureMetadata(canvas, bounds, shown, density, night)
            entering?.bitmap?.let { bitmap ->
                val t = ((now - transitionStartNanos).toDouble() / transitionLengthNanos).coerceIn(0.0, 1.0)
                val u = (t * t * (3.0 - 2.0 * t)).toFloat()
                // Include the black fit background in each layer. This avoids a
                // ghost of the old portrait outside a differently sized next photo.
                val layer = canvas.saveLayerAlpha(bounds, (255f * u).roundToInt())
                paint.reset(); paint.color = Color.BLACK; canvas.drawRect(bounds, paint)
                drawPhoto(canvas, bounds, bitmap, night)
                entering?.let { drawCaptureMetadata(canvas, bounds, it, density, night) }
                canvas.restoreToCount(layer)
            }
            drawClock(canvas, bounds, shown, clock, use24Hour, density, night, shown.timeLayoutRaw, shown.numberSystemRaw)
            if (showSourceLabel) {
                label(canvas, "小米精选 · ${shown.selectedIndex + 1}/${shown.photos.size}",
                    bounds.right - 24f * density, bounds.bottom - 26f * density,
                    12f * density, Paint.Align.RIGHT, night)
            }
            canvas.restoreToCount(wrapper)
        } else {
            label(canvas, snapshot.message, bounds.centerX(), bounds.centerY(),
                15f * density, Paint.Align.CENTER, night)
        }
        canvas.restoreToCount(save)
        return isAnimating || verticalPresentation.running || metadataPosters.values.any { it.controller.needsFrame(now) }
    }

    private fun pageIdentity(photo: XiaomiCuratedPhotoSource.Snapshot): String =
        photo.currentAssetIdentity ?: "unbound:${key(photo)}"

    /** Outer item view owns scale/mask; its child poster owns one composited alpha.
     * Same-source vertical profile, with Android window radius/Canvas layer adapters. */
    private fun beginPosterWrapper(canvas: Canvas, bounds: RectF,
                                   photo: XiaomiCuratedPhotoSource.Snapshot, displacement: Float,
                                   nowNanos: Long, animated: Boolean): Int {
        val sample = verticalPresentation.sample(pageIdentity(photo),
            (displacement / bounds.height()).toDouble(), nowNanos, animated)
        val saved = canvas.save()
        canvas.translate(0f, displacement)
        canvas.scale(sample.scale, sample.scale, bounds.centerX(), bounds.centerY())
        if (sample.clips) canvas.clipPath(OriginalContinuousShape.path(bounds, windowCornerRadiusPx))
        // Includes the fit background, photo, metadata AND the current clock. Never
        // leave a stationary clock floating above two independently moving posters.
        if (sample.alpha < 255) canvas.saveLayerAlpha(bounds, sample.alpha)
        paint.reset(); paint.color = Color.BLACK; canvas.drawRect(bounds, paint)
        return saved
    }

    private fun drawVerticalPage(canvas: Canvas, bounds: RectF,
                                 photo: XiaomiCuratedPhotoSource.Snapshot, displacement: Float,
                                 clock: LocalDateTime?, use24Hour: Boolean,
                                 density: Float, night: Boolean, nowNanos: Long, animated: Boolean) {
        val bitmap = photo.bitmap ?: return
        val saved = beginPosterWrapper(canvas, bounds, photo, displacement, nowNanos, animated)
        drawPhoto(canvas, bounds, bitmap, night)
        drawCaptureMetadata(canvas, bounds, photo, density, night)
        drawClock(canvas, bounds, photo, clock, use24Hour, density, night, photo.timeLayoutRaw, photo.numberSystemRaw)
        canvas.restoreToCount(saved)
    }

    private fun drawPhoto(canvas: Canvas, bounds: RectF, bitmap: Bitmap, night: Boolean) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val sx = bounds.width() / bitmap.width; val sy = bounds.height() / bitmap.height
        val scale = if (cropMode == CropMode.FILL_CENTER_ADAPTER) max(sx, sy) else min(sx, sy)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        val destination = RectF(bounds.centerX() - w / 2f, bounds.centerY() - h / 2f,
            bounds.centerX() + w / 2f, bounds.centerY() + h / 2f)
        paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true
        paint.colorFilter = if (night) nightFilter else null
        canvas.drawBitmap(bitmap, null, destination, paint)
        // Legibility belongs to the image; metadata and the live clock sit above it.
        drawLegibility(canvas, bounds)
    }

    /** 面的照片处理管线（night 滤镜 + 可读性渐变）单独暴露给编辑卡复用：
     *  编辑器过去是"裸照片"，而面上有 night colorFilter 与底部 DARKEN 渐变，
     *  进出编辑就会看到亮度/渐变跳变。这里用同一份实现，不再各画一套。 */
    fun drawPhotoLayer(canvas: Canvas, bounds: RectF, bitmap: Bitmap, night: Boolean, gradientOpacity: Float = -1f) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        if (gradientOpacity >= 0f) {
            // 编辑卡沿用"进入编辑前那一刻面上"的可读性强度，避免一进编辑整张照片变亮（用户报的亮度跳变）。
            val saved = legibilityGradientOpacity
            legibilityGradientOpacity = gradientOpacity.coerceIn(0f, 1f)
            drawPhoto(canvas, bounds, bitmap, night)
            legibilityGradientOpacity = saved
            return
        }
        drawPhoto(canvas, bounds, bitmap, night)
    }

    /** 当前面的可读性渐变强度（0..1），编辑器开卡时沿用同一个值。 */
    val currentLegibilityOpacity: Float get() = legibilityGradientOpacity.coerceIn(0f, 1f)

    private fun drawLegibility(canvas: Canvas, bounds: RectF) {
        // Parameters and blend identity from v30, Android color-space parity unmeasured.
        // Draw against the actual composed image, not a fresh transparent layer.
        fun gradient(endpoint: Float, darken: Boolean) {
            paint.reset(); paint.isAntiAlias = true
            paint.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                Color.TRANSPARENT, Color.argb((255f * endpoint).roundToInt(), 0, 0, 0), Shader.TileMode.CLAMP)
            paint.xfermode = if (darken) PorterDuffXfermode(PorterDuff.Mode.DARKEN) else null
            canvas.drawRect(bounds, paint)
        }
        val strength = legibilityGradientOpacity.coerceIn(0f, 1f)
        if (strength <= 0.001f) { paint.reset(); return }
        gradient(.30f * strength, true); gradient(.20f * strength, false)
        paint.reset()
    }

    private fun metadataPosterIdentity(value: XiaomiCuratedPhotoSource.Snapshot): String =
        value.pageId ?: "unbound:${value.cardId}"

    /** Original posterData: timeAppearance "glass", glassFrostLevel 0.3. The system renders a
     * vibrancy material; Android has no public equivalent, so the glyphs are filled with a
     * blurred copy of the same photo plus a 0.3 white frost. Explicit Android adaptation. */
    private val glassTimeAppearance = false // glass blur per frame is too heavy; solid until optimised

    private fun buildBlurredPhoto(bounds: RectF, bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return null
        if (bounds.width() < 2f || bounds.height() < 2f) return null
        return runCatching {
            val w = bounds.width().toInt().coerceAtLeast(1)
            val h = bounds.height().toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width / 12), max(1, bitmap.height / 12), true)
            val sx = bounds.width() / bitmap.width; val sy = bounds.height() / bitmap.height
            val scale = if (cropMode == CropMode.FILL_CENTER_ADAPTER) max(sx, sy) else min(sx, sy)
            val dw = bitmap.width * scale; val dh = bitmap.height * scale
            val dest = RectF(bounds.centerX() - dw / 2f, bounds.centerY() - dh / 2f,
                bounds.centerX() + dw / 2f, bounds.centerY() + dh / 2f)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(small, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }.getOrNull()
    }

    private fun drawTimeGlyphs(c: Canvas, text: String, x: Float, y: Float, paint: Paint,
                               blurred: Bitmap?, opacity: Float, night: Boolean) {
        if (blurred == null || text.isEmpty()) { c.drawText(text, x, y, paint); return }
        val path = Path()
        val left = when (paint.textAlign) {
            Paint.Align.CENTER -> x - paint.measureText(text) / 2f
            Paint.Align.RIGHT -> x - paint.measureText(text)
            else -> x
        }
        paint.getTextPath(text, 0, text.length, left, y, path)
        val shaderPaint = Paint(paint).apply { shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        c.drawPath(path, shaderPaint)
        val frost = Paint(paint).apply {
            shader = null
            color = if (night) Color.rgb(140, 0, 0) else Color.WHITE
            alpha = (0.30f * 255f * opacity).roundToInt().coerceIn(0, 255)
        }
        c.drawPath(path, frost)
    }

    /** Original live body 0x1e33ade38 draws a sibling actions ZStack in the metadata-show state:
     * PhotosPosterHidePillButton (ClockPoster_DoNotFeature_Button_Title) + spacer +
     * PhotosPosterButton (PHOTOS_AMBIENT_PHOTO_OPEN_PHOTOS_BUTTON_TITLE). Symbol identities are
     * unconfirmed, so labels are used; pill radius/height are Android layout, not recovered. */
    private fun drawActions(c: Canvas, bounds: RectF, density: Float,
                            frame: OriginalPhotosPosterMetadataController.Frame?) {
        val f = frame ?: return
        val opacity = f.actionsOpacity.toFloat().coerceIn(0f, 1f)
        if (opacity <= 0.01f) return
        if (!isMetadataShowing) return
        val category = when (metadataDeviceCategoryRaw) {
            0 -> OriginalPhotosPosterMetadataGeometry.Category.WIDE_SCREEN
            1 -> OriginalPhotosPosterMetadataGeometry.Category.HOME_BUTTON
            else -> OriginalPhotosPosterMetadataGeometry.Category.STANDARD
        }
        val layout = OriginalPhotosPosterMetadataGeometry.actions(
            category, (bounds.width() / density).toDouble(), (bounds.height() / density).toDouble()) ?: return
        val saved = c.save()
        val scale = f.actionsScale.toFloat().coerceIn(0.5f, 2f)
        c.scale(scale, scale, bounds.centerX(), bounds.centerY())
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = metadataTypeface
        val left = (layout.contentFrame.left * density).toFloat()
        val right = (layout.contentFrame.right * density).toFloat()
        val bottom = bounds.bottom - (layout.bottomRowPadding * density).toFloat()
        // Exact strings from the 24A94425 loctables: PhotosClockPoster.loctable
        // ClockPoster_DoNotFeature_Button_Title and PhotosUI.loctable
        // PHOTOS_AMBIENT_PHOTO_OPEN_PHOTOS_BUTTON_TITLE.
        val loc = java.util.Locale.getDefault()
        val zh = loc.language == "zh"
        val hide = when {
            zh && (loc.country == "TW" || loc.country == "HK") -> "隱藏"
            zh -> "隐藏"
            else -> "Hide"
        }
        val open = when {
            zh && loc.country == "TW" -> "在「照片」中檢視"
            zh && loc.country == "HK" -> "在「相片」檢視"
            zh -> "在“照片”中查看"
            else -> "View in Photos"
        }
        // Hide pill
        paint.textSize = 15f * density
        val tw = paint.measureText(hide)
        val pillH = 32f * density
        val pillLeft = left
        val pillTop = bottom - pillH
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((70f * opacity).roundToInt(), 255, 255, 255)
        c.drawRoundRect(RectF(pillLeft, pillTop, pillLeft + tw + 28f * density, bottom), pillH / 2f, pillH / 2f, paint)
        paint.color = Color.argb((240f * opacity).roundToInt(), 255, 255, 255)
        paint.textAlign = Paint.Align.LEFT
        c.drawText(hide, pillLeft + 14f * density, bottom - (pillH - paint.textSize) / 2f - paint.fontMetrics.descent / 2f, paint)
        // View in Photos: label then arrow.up.forward.app.fill (0x1e3391600, next to the text,
        // template tinted). The asset is the SF Symbol rendered at 512px from the same library.
        val icon = photoSymbol("arrow.up.forward.app.fill")
        val baseline = bottom - 6f * density
        paint.textAlign = Paint.Align.RIGHT
        if (icon != null) {
            val iconH = 18f * density
            val iconW = if (icon.height > 0) iconH * icon.width / icon.height else iconH
            val gap = 5f * density
            c.drawText(open, right - iconW - gap, baseline, paint)
            val cy = baseline - paint.textSize * 0.36f
            val dst = RectF(right - iconW, cy - iconH / 2f, right, cy + iconH / 2f)
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                alpha = paint.alpha
            }
            c.drawBitmap(icon, null, dst, iconPaint)
        } else {
            c.drawText(open, right, baseline, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        c.restoreToCount(saved)
    }

    private fun drawCaptureMetadata(canvas: Canvas, bounds: RectF, value: XiaomiCuratedPhotoSource.Snapshot,
                                    density: Float, night: Boolean) {
        val poster = metadataPosters.getOrPut(metadataPosterIdentity(value)) { MetadataPoster() }
        val renderer = poster.renderers.getOrPut(pageIdentity(value)) {
            OriginalPhotosMetadataCanvas(poster.controller)
        }
        val category = when (metadataDeviceCategoryRaw) {
            0 -> OriginalPhotosPosterMetadataGeometry.Category.WIDE_SCREEN
            1 -> OriginalPhotosPosterMetadataGeometry.Category.HOME_BUTTON
            else -> OriginalPhotosPosterMetadataGeometry.Category.STANDARD
        }
        // PhotosPosterMetadataModel 0x1e33408ac uses localizedGeoDescription and
        // creationDate, formatter MEDIUM date/NONE time. Our authorized source
        // supplies EXIF local capture date as the Android data adapter; missing
        // metadata remains absent. Never use GPS text or the current clock.
        // Original PhotosClockPoster: title = creationDate (33pt), subtitle = localizedGeoDescription
        // (24pt), opposite of our earlier order (audit 0x1e3340920 / 0x1e3340a48).
        val content = OriginalPhotosMetadataCanvas.Content(
            value.captureDateTime?.toLocalDate()?.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())),
            value.captureLocationName?.trim()?.takeIf(String::isNotEmpty),
        )
        renderer.draw(canvas, bounds, density, category, content, metadataTypeface,
            if (night) Color.rgb(140, 0, 0) else Color.WHITE,
            metadataLayoutRtl, lastSampleNanos,
            metadataGate.copy(assetAvailable = value.bitmap?.isRecycled == false))
        drawActions(canvas, bounds, density,
            metadataPosters[metadataActivePoster]?.controller?.sample(lastSampleNanos))
        // The native poster presents the place/date together with the time on arrival, not only
        // after a tap (user observation on iPhone); then the 7s auto-hide removes them.
        val autoPoster = metadataActivePoster
        if (hasPhotoMetadata && autoPoster != null && autoPoster !in autoShownPosters) {
            // Only remember the auto-show after show() actually started: on the arrival frame the
            // bitmap is still null, show() returns false, and marking it early killed the feature.
            val started = metadataPosters[autoPoster]?.controller?.show(lastSampleNanos, metadataGate) == true
            android.util.Log.d("DuoPhotoMeta", "autoshow poster=$autoPoster started=$started")
            if (started) autoShownPosters.add(autoPoster)
        }
    }

    private fun drawClock(canvas: Canvas, bounds: RectF, photo: XiaomiCuratedPhotoSource.Snapshot,
                          clock: LocalDateTime?, use24Hour: Boolean,
                          density: Float, night: Boolean, timeLayoutRaw: Int, numberSystemRaw: Int) {
        if (clock == null || timeLayoutRaw == 0) return
        val hour = if (use24Hour) clock.hour else (clock.hour % 12).let { if (it == 0) 12 else it }
        val western = if (use24Hour) String.format(Locale.ROOT, "%02d:%02d", hour, clock.minute)
            else String.format(Locale.ROOT, "%d:%02d", hour, clock.minute)
        val zero = when (numberSystemRaw) { 1 -> '\u0660'; 2 -> '\u0966'; else -> '0' }
        val time = western.map { if (it in '0'..'9') (zero.code + it.code - '0'.code).toChar() else it }.joinToString("")
        val frame = metadataPosters[metadataPosterIdentity(photo)]?.controller?.sample(lastSampleNanos)
        val opacity = (frame?.clockOpacity ?: 1.0).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0.001f) return
        val category = when (metadataDeviceCategoryRaw) {
            0 -> OriginalPhotosPrimaryTimeLayout.Category.WIDE_SCREEN
            1 -> OriginalPhotosPrimaryTimeLayout.Category.HOME_BUTTON
            else -> OriginalPhotosPrimaryTimeLayout.Category.STANDARD
        }
        val resolved = OriginalPhotosPrimaryTimeLayout.resolveForPhoto(
            timeLayoutRaw, category, (bounds.height() / density).toDouble())
        val spec = (resolved as? OriginalPhotosPrimaryTimeLayout.Resolution.Ready)?.spec ?: return
        val plan = OriginalPhotosPrimaryTimeLayout.containerPlan(spec)
        paint.reset(); paint.isAntiAlias = true; paint.color = if (night) Color.rgb(140, 0, 0) else Color.WHITE
        paint.alpha = (255f * opacity).roundToInt().coerceIn(0, 255)
        paint.typeface = timeTypefaceFactory?.invoke(spec.font.width, spec.font.weight) ?: timeTypeface
        paint.textSize = (spec.font.size * density).toFloat()
        // Android 必要适配: the native label view is framed to a square of side d0
        // (0x1e34459dc) and the text runs through SolidTimeView + .scaleEffect
        // (symbols 0x1e3609d82 / 0x1e3608bfc). d0's assignment (label model +0x88)
        // is not yet located, so the string is fitted into a square of the
        // container's short side instead of being clipped by the raw 472pt font.
        val sidePad = min(bounds.width(), bounds.height()) * 0.03f
        val usableWidth = min(bounds.width(), bounds.height()) - 2f * sidePad -
            ((spec.margins.leading + spec.margins.trailing) * density).toFloat()
        if (usableWidth > 1f) {
            val natural = paint.measureText(time)
            if (natural > usableWidth) paint.textSize *= usableWidth / natural
        }
        val blurredTime = if (glassTimeAppearance) buildBlurredPhoto(bounds, photo.bitmap) else null
        val verticalPad = ((plan.verticalPadding ?: 0.0) * density).toFloat()
        val baseline = if (plan.bottomPadding) bounds.bottom - verticalPad - paint.fontMetrics.descent
            else bounds.top + verticalPad - paint.fontMetrics.ascent
        val centeredBaseline = bounds.centerY() -
            (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val hours = time.substringBefore(':')
        val minutes = time.substringAfter(':', "")
        if (spec.isVertical) {
            paint.textAlign = if (spec.labelLayout == OriginalPhotosPrimaryTimeLayout.LabelLayout.STACKED_ALIGNED_RIGHT)
                Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (paint.textAlign == Paint.Align.RIGHT) bounds.right - (plan.trailingPadding * density).toFloat()
                else bounds.left + (plan.leadingPadding * density).toFloat()
            drawTimeGlyphs(canvas, hours, x, bounds.top + (spec.margins.hoursTop * density).toFloat() - paint.fontMetrics.ascent, paint, blurredTime, opacity, night)
            drawTimeGlyphs(canvas, minutes, x, bounds.top + (spec.margins.minutesTop * density).toFloat() - paint.fontMetrics.ascent, paint, blurredTime, opacity, night)
        } else {
            // Native label model: CENTERED/LEFT/RIGHT draw one full label;
            // ALIGNED_TO_EDGES draws hours (leading), separator (center) and
            // minutes (trailing) as three labels. Hours/minutes sides come from
            // the EDGES payload order @0x1e3446370 (kind 1, kind 3, kind 2) plus
            // the spec leading/trailing margins; separator centering is the
            // native container rule still marked 待确认.
            for (frame in OriginalPhotosPrimaryTimeLayout.labelFrames(spec, forceCentered = false)) {
                val text = when (frame.slot) {
                    OriginalPhotosPrimaryTimeLayout.LabelSlot.FULL -> time
                    OriginalPhotosPrimaryTimeLayout.LabelSlot.HOURS -> hours
                    OriginalPhotosPrimaryTimeLayout.LabelSlot.SEPARATOR -> ":"
                    OriginalPhotosPrimaryTimeLayout.LabelSlot.MINUTES -> minutes
                }
                paint.textAlign = when (frame.anchor) {
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.LEADING -> Paint.Align.LEFT
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.CENTER -> Paint.Align.CENTER
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.TRAILING -> Paint.Align.RIGHT
                }
                val x = when (frame.anchor) {
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.LEADING ->
                        bounds.left + (spec.margins.leading * density).toFloat()
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.CENTER ->
                        bounds.centerX() + (((spec.margins.leading - spec.margins.trailing) * density / 2.0).toFloat())
                    OriginalPhotosPrimaryTimeLayout.LabelAnchor.TRAILING ->
                        bounds.right - (spec.margins.trailing * density).toFloat()
                }
                val y = if (frame.slot == OriginalPhotosPrimaryTimeLayout.LabelSlot.FULL &&
                    frame.anchor == OriginalPhotosPrimaryTimeLayout.LabelAnchor.CENTER &&
                    !spec.anchoredAtBottom) centeredBaseline else baseline
                drawTimeGlyphs(canvas, text, x, y, paint, blurredTime, opacity, night)
            }
        }
    }

    private fun label(canvas: Canvas, value: String, x: Float, y: Float, size: Float,
                      align: Paint.Align, night: Boolean) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = if (night) Color.rgb(140, 0, 0) else Color.LTGRAY
        paint.textSize = size; paint.textAlign = align; paint.typeface = Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }
}
