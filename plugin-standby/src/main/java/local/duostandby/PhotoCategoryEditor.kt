package local.duostandby

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.provider.MediaStore
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 照片面类别切换器（「精选照片」这一页的编辑态）。
 *
 * 结构依据真机参考截图 supplement-20261024-photo-category/reference-2622x1206.png：
 * 每一页 = [卡片][类别名]，卡片几何与长按 portal 的卡片完全一致（同一 RectF 传入）；
 * 卡片之间纵向 50pt；卡片右侧竖排页码点；左上「+」右上「完成」胶囊；卡片左上角眼睛按钮。
 *
 * 动画依据 AmbientUI 24A94425 的 AMUISwitcherViewController：容器是 UIScrollView
 * （_scrollView / _horizontalTrailingSpacerConstraint / _verticalTrailingSpacerConstraint /
 * _currentTransition），拖动 1:1 跟手，松手走 UIKit 分页内核 OriginalPagingMotion——
 * 与左右换面、时钟上下换样式同一个 Release 求解器。这里不使用
 * AMUIPosterSwitcherSettings 的 0.885 缩放淡出，那是 portal 配置选择页的切换效果。
 *
 * 明确保留的缺口（不猜）：类别图标。原版标签是 AMUIPhotosLabelView(symbolName:title:)，
 * 符号来自 PhotosUICore 的 PXWallpaperSmartAlbumDataSource（leaf.fill / building.fill /
 * pawprint.fill / person.2.fill / sparkles.rectangle.stack.fill）；小米时光影集没有
 * 对应的符号映射，所以这里只有文字（HANDOFF P3 同一条）。
 */
class PhotoCategoryEditor(
    context: Context,
    pages: List<PhotoPageRepository.Page>,
    startIndex: Int,
    card: RectF,
    private val cardCorner: Float,
    /** 面正在显示的位图：作为当前页的种子，避免进入编辑时先黑一帧再出图。 */
    seed: Bitmap? = null,
    /** 面此刻正在显示的那张照片（不是配置里记住的 currentAssetId）。 */
    seedAssetUri: String? = null,
) : View(context) {

    companion object {
        /** 参考截图：卡片 1808×832 / 屏幕 2622×1206 = 0.69（与 portal 卡片同值，已由 card 传入）。 */
        const val CARD_GAP_PT = 50f
        const val CAPTION_TOP_GAP_PT = 12.7f
        const val CAPTION_FONT_PT = 13f
        const val DOT_SIZE_PT = 8f
        const val DOT_PITCH_PT = 17.7f
        const val DOT_TRAILING_GAP_PT = 27.7f
        const val PILL_WIDTH_PT = 65.7f
        const val PILL_HEIGHT_PT = 27.7f
        const val PILL_INSET_PT = 40f
        const val PILL_TOP_PT = 16f
        const val PILL_FONT_PT = 16f
        const val EYE_SIZE_PT = 34f
        const val EYE_CENTER_X_PT = 16.7f
        const val EYE_CENTER_Y_PT = 11.3f
        const val PILL_FILL = 0xFF7C7C7D.toInt()
        const val PILL_CONTENT = 0xFF000000.toInt()
        const val EYE_FILL = 0xFF848586.toInt()
        const val BACKGROUND = 0xFF1C1C1E.toInt()
        const val DOT_ACTIVE = 0xFFFFFFFF.toInt()
        const val DOT_INACTIVE = 0xFF818182.toInt()
        const val CARD_EMPTY = 0xFF000000.toInt()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val fonts = OriginalFonts(context)
    private val zh = Locale.getDefault().language == "zh"

    var onDone: (() -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onPageSelected: ((PhotoPageRepository.Page) -> Unit)? = null
    /** 由宿主注入：和面同一套照片处理（night 滤镜 + 可读性渐变），卡片不再自己画裸图。 */
    var photoLayer: ((Canvas, RectF, Bitmap, Boolean) -> Unit)? = null
    var night = false
    /** 进入编辑前那一刻面上的可读性渐变强度；卡片沿用它，进出编辑亮度不跳。 */
    var legibility = -1f

    private var pages: List<PhotoPageRepository.Page> = pages
    private val cardBounds = RectF(card)
    private var page = startIndex.coerceAtLeast(0).toDouble()
    private var release: OriginalPagingMotion.Release? = null
    private var lastFrame = 0L
    private var destination = startIndex.coerceAtLeast(0)
    private var startPage = 0.0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var touchedPill = 0
    private var touchedEyeId: String? = null
    private var committedId: String? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val tracker = VelocityTracker.obtain()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = fonts.semibold
        textAlign = Paint.Align.CENTER
        textSize = dp(CAPTION_FONT_PT)
    }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL_CONTENT
        typeface = fonts.ui
        textAlign = Paint.Align.CENTER
        textSize = dp(PILL_FONT_PT)
    }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL_CONTENT
        style = Paint.Style.STROKE
        strokeWidth = dp(1.7f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyeInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cardPath = Path()
    private val eyePath = Path()
    private val scratch = RectF()
    private val textBounds = android.graphics.Rect()

    private var seedPageId: String? = null
    private var seedUri: String? = seedAssetUri
    private val cache = LinkedHashMap<String, Bitmap>(4, 0.75f, true)
    private val loading = HashSet<String>()
    private val hidden = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhotoCategoryPreview").apply { isDaemon = true }
    }

    private val pitch get() = cardBounds.height() + dp(CARD_GAP_PT)
    private val pageCount get() = pages.size
    private val settledIndex get() = page.roundToInt().coerceIn(0, max(0, pageCount - 1))

    init {
        val current=pages.getOrNull(startIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
        seedPageId=current?.id
        if(seed!=null&&!seed.isRecycled&&current!=null)cache[current.id]=seed
        setBackgroundColor(BACKGROUND)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setPages(list: List<PhotoPageRepository.Page>, selectedId: String?) {
        android.util.Log.i("DuoPhotoCategory", "setPages count=${list.size} selected=$selectedId index=${list.indexOfFirst { it.id == selectedId }}")
        pages = list
        val index = list.indexOfFirst { it.id == selectedId }
        release = null
        if (list.isEmpty()) return
        val kept = if (index >= 0) index else settledIndex.coerceIn(0, list.lastIndex)
        page = kept.toDouble()
        destination = kept
        committedId = list[kept].id
        prefetchAround()
        invalidate()
    }

    fun selectedPageId(): String? = pages.getOrNull(settledIndex)?.id

    override fun onDetachedFromWindow() {
        worker.shutdownNow()
        tracker.recycle()
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
            onDismiss?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- paging

    private fun settle(velocity: Double = 0.0, selected: Int? = null) {
        if (pages.isEmpty()) return
        val sizePt = pitch / density.toDouble()
        if (sizePt <= 0.0) return
        destination = selected?.coerceIn(0, pages.lastIndex)
            ?: OriginalPagingMotion.target(page * sizePt, velocity, sizePt, pages.lastIndex)
        if (!OriginalStandByMotionPolicy.animationsEnabled(context)) {
            page = destination.toDouble(); release = null
            commit(); invalidate(); return
        }
        release = OriginalPagingMotion.Release(
            cardBounds.width() / density.toDouble(), page * sizePt, velocity,
            destination * sizePt, density.toDouble())
        lastFrame = System.nanoTime()
        schedule()
    }

    private fun advance(nanos: Long) {
        val motion = release ?: return
        val sizePt = pitch / density.toDouble()
        val dt = (nanos - lastFrame) / 1_000_000.0
        if (dt >= 1.0) page = motion.advance(dt) / sizePt
        lastFrame = nanos
        if (!motion.running) {
            page = destination.toDouble()
            release = null
            commit()
        }
        invalidate()
        if (release != null) schedule()
    }

    private fun commit() {
        val item = pages.getOrNull(settledIndex) ?: return
        if (item.id == committedId) return
        committedId = item.id
        prefetchAround()
        onPageSelected?.invoke(item)
    }

    /** 当前页 ±1 先解码好，滑动时不出现"空卡"过渡帧。 */
    private fun prefetchAround() {
        val center = settledIndex
        for (index in max(0, center - 1)..min(pages.lastIndex, center + 1)) {
            pages.getOrNull(index)?.let { bitmapFor(it) }
        }
    }

    private var frameScheduled = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            frameScheduled = false
            advance(nanos)
        }
    }

    private fun schedule() {
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pages.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                startPage = page
                dragging = false
                touchedPill = pillAt(event.x, event.y)
                touchedEyeId = if (touchedPill == 0) eyeAt(event.x, event.y) else null
                release = null
                tracker.clear()
                tracker.addMovement(event)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchedPill != 0 || touchedEyeId != null) return true
                tracker.addMovement(event)
                val dy = event.y - downY
                val dx = event.x - downX
                if (!dragging && abs(dy) > slop && abs(dy) > abs(dx)) dragging = true
                if (dragging) {
                    // UIScrollView 同款：拖动 1:1 改 offset，越界走同一个回弹系数。
                    page = OriginalPagingMotion.rubberBand(
                        startPage - dy / pitch, 0.0, pages.lastIndex.toDouble(), 1.0)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchedPill != 0) {
                    val pill = touchedPill
                    touchedPill = 0
                    invalidate()
                    if (pillAt(event.x, event.y) == pill) {
                        android.util.Log.i("DuoPhotoCategory", "pill tap=$pill page=$page pages=${pages.size}")
                        if (pill == 1) onAdd?.invoke() else onDone?.invoke()
                    }
                    return true
                }
                touchedEyeId?.let { id ->
                    touchedEyeId = null
                    if (eyeAt(event.x, event.y) == id) {
                        if (!hidden.add(id)) hidden.remove(id)
                    }
                    invalidate()
                    return true
                }
                if (dragging) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000)
                    // 内容位移速度 points/ms（UIKit 的 flick 阈值 0.3pt/ms 直接可用）。
                    val velocity = -(tracker.yVelocity.coerceIn(-24000f, 24000f) / 1000.0) / density
                    settle(velocity)
                } else {
                    cardIndexAt(event.x, event.y)?.let { index ->
                        if (index != settledIndex) settle(selected = index)
                    }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchedPill = 0
                touchedEyeId = null
                if (dragging) settle()
                dragging = false
                return true
            }
        }
        return true
    }

    private fun pillRect(right: Boolean): RectF {
        val w = dp(PILL_WIDTH_PT)
        val h = dp(PILL_HEIGHT_PT)
        val top = dp(PILL_TOP_PT)
        return if (right) RectF(width - dp(PILL_INSET_PT) - w, top, width - dp(PILL_INSET_PT), top + h)
        else RectF(dp(PILL_INSET_PT), top, dp(PILL_INSET_PT) + w, top + h)
    }

    private fun pillAt(x: Float, y: Float): Int = when {
        pillRect(false).contains(x, y) -> 1
        pillRect(true).contains(x, y) -> 2
        else -> 0
    }

    private fun visibleRange(): IntRange {
        if (pages.isEmpty()) return 0..-1
        val first = floor(page).toInt() - 1
        val last = ceil(page).toInt() + 1
        return max(0, first)..min(pages.lastIndex, last)
    }

    private fun cardTopFor(index: Int) = cardBounds.top + ((index - page) * pitch).toFloat()

    private fun cardRectFor(index: Int): RectF {
        val top = cardTopFor(index)
        scratch.set(cardBounds.left, top, cardBounds.right, top + cardBounds.height())
        return scratch
    }

    private fun eyeAt(x: Float, y: Float): String? {
        val radius = dp(EYE_SIZE_PT) / 2f
        val range = visibleRange()
        for (index in range) {
            val top = cardTopFor(index)
            if (top > height || top + cardBounds.height() < 0) continue
            val cx = cardBounds.left + dp(EYE_CENTER_X_PT)
            val cy = top + dp(EYE_CENTER_Y_PT)
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) return pages[index].id
        }
        return null
    }

    private fun cardIndexAt(x: Float, y: Float): Int? {
        if (x < cardBounds.left || x > cardBounds.right) return null
        for (index in visibleRange()) {
            val top = cardTopFor(index)
            if (y >= top && y <= top + cardBounds.height()) return index
        }
        return null
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        if (pages.isEmpty()) return
        for (index in visibleRange()) {
            val rect = cardRectFor(index)
            if (rect.bottom < -pitch || rect.top > height + pitch) continue
            val item = pages[index]
            drawCard(canvas, rect, item)
            drawCaption(canvas, rect, item)
            drawEye(canvas, rect, item)
        }
        drawDots(canvas)
        drawPill(canvas, pillRect(false), 1)
        drawPill(canvas, pillRect(true), 2)
    }

    private fun drawCard(canvas: Canvas, rect: RectF, item: PhotoPageRepository.Page) {
        cardPath.reset()
        cardPath.addRoundRect(rect, cardCorner, cardCorner, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(cardPath)
        val bitmap = if (item.id in hidden) null else bitmapFor(item)
        // 只在"这张类别确实没有照片"或"被眼睛隐藏"时铺黑；还在解码时不铺黑，
        // 否则进入编辑/切页会先闪一帧纯黑（用户报告的闪烁）。
        if (bitmap == null && (item.id in hidden || item.assetUris.isEmpty())) {
            fill.color = CARD_EMPTY
            canvas.drawRect(rect, fill)
        }
        if (bitmap != null && !bitmap.isRecycled) {
            val layer = photoLayer
            if (layer != null) {
                // 同一套管线：night colorFilter + 底部 DARKEN 可读性渐变，和面逐像素同源。
                layer(canvas, rect, bitmap, night)
            } else {
                val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val dst = RectF(rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                    rect.centerX() + dw / 2f, rect.centerY() + dh / 2f)
                canvas.drawBitmap(bitmap, null, dst, image)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawCaption(canvas: Canvas, rect: RectF, item: PhotoPageRepository.Page) {
        val title = item.title.ifBlank { if (zh) "照片" else "Photos" }
        // 参考截图：文字墨迹顶边固定在卡片下沿 +12.7pt，按真实墨迹框定位而不是行框。
        caption.getTextBounds(title, 0, title.length, textBounds)
        val baseline = rect.bottom + dp(CAPTION_TOP_GAP_PT) - textBounds.top
        canvas.drawText(title, rect.centerX(), baseline, caption)
    }

    private fun drawEye(canvas: Canvas, rect: RectF, item: PhotoPageRepository.Page) {
        val cx = rect.left + dp(EYE_CENTER_X_PT)
        val cy = rect.top + dp(EYE_CENTER_Y_PT)
        val radius = dp(EYE_SIZE_PT) / 2f
        eye.color = EYE_FILL
        canvas.drawCircle(cx, cy, radius, eye)
        // SF Symbol eye.fill：实心眼形 + 挖空虹膜环 + 实心瞳孔。
        val w = radius * 0.52f
        val h = radius * 0.31f
        eyePath.reset()
        eyePath.moveTo(cx - w, cy)
        eyePath.quadTo(cx, cy - h * 1.7f, cx + w, cy)
        eyePath.quadTo(cx, cy + h * 1.7f, cx - w, cy)
        eyePath.close()
        canvas.drawPath(eyePath, eyeInk)
        if (item.id !in hidden) {
            eye.color = EYE_FILL
            canvas.drawCircle(cx, cy, h * 0.92f, eye)
            canvas.drawCircle(cx, cy, h * 0.5f, eyeInk)
        } else {
            // eye.slash.fill（PosterKit 24A94425 中 eye.fill / eye.slash.fill 两个符号都在）：
            // 先用底色在眼形上切出一道缝，再压一条黑色斜杠。
            slash.color = EYE_FILL
            slash.strokeWidth = dp(2.6f)
            canvas.drawLine(cx - radius * 0.52f, cy + radius * 0.52f,
                cx + radius * 0.52f, cy - radius * 0.52f, slash)
            slash.color = Color.BLACK
            slash.strokeWidth = dp(1.5f)
            canvas.drawLine(cx - radius * 0.62f, cy + radius * 0.62f,
                cx + radius * 0.62f, cy - radius * 0.62f, slash)
        }
    }

    private fun drawDots(canvas: Canvas) {
        val count = pageCount
        if (count <= 1) return
        var pitchY = dp(DOT_PITCH_PT)
        val available = cardBounds.height() * 0.82f
        if (pitchY * count > available) pitchY = available / count
        val radius = min(dp(DOT_SIZE_PT) / 2f, pitchY * 0.45f)
        val cx = cardBounds.right + dp(DOT_TRAILING_GAP_PT)
        val top = cardBounds.centerY() - (pitchY * (count - 1)) / 2f
        val current = settledIndex
        for (index in 0 until count) {
            dot.color = if (index == current) DOT_ACTIVE else DOT_INACTIVE
            canvas.drawCircle(cx, top + pitchY * index, radius, dot)
        }
    }

    private fun drawPill(canvas: Canvas, rect: RectF, kind: Int) {
        fill.color = PILL_FILL
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (kind == 1) {
            val arm = dp(6.2f)
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plus)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plus)
        } else {
            val metrics = pillText.fontMetrics
            val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(if (zh) "完成" else "Done", rect.centerX(), baseline, pillText)
        }
    }

    // ---------------------------------------------------------------- images

    private fun bitmapFor(item: PhotoPageRepository.Page): Bitmap? {
        cache[item.id]?.let { if (!it.isRecycled) return it }
        if (loading.add(item.id)) {
            // 当前页用"面正在显示的那张"，否则用配置里记住的那张；两者不同就是用户看到的"跳变"。
            val uri = (if (item.id == seedPageId) seedUri else null)
                ?: item.currentAssetId ?: item.assetUris.firstOrNull()
            if (uri == null) {
                loading.remove(item.id)
                return null
            }
            worker.execute {
                val bitmap = decode(uri)
                post {
                    loading.remove(item.id)
                    if (bitmap == null) {
                        invalidate()
                    } else if (!isAttachedToWindow || pages.none { it.id == item.id }) {
                        bitmap.recycle()
                    } else {
                        cache[item.id] = bitmap
                        trimCache()
                        invalidate()
                    }
                }
            }
        }
        return null
    }

    private fun trimCache() {
        while (cache.size > 4) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
            if (!oldest.value.isRecycled) oldest.value.recycle()
        }
    }

    /** file:// 时光卡与 content:// 自选照片共用同一个只读解码入口。
     *  必须和面上的解码同语义：面在 XiaomiCuratedPhotoSource.decode 里按 EXIF 方向摆正
     *  （ORIENTATION_ROTATE_90/270 等），编辑器之前没做，带方向的照片进入编辑就会转向=用户看到的"跳变"。
     */
    private fun decode(uri: String): Bitmap? = runCatching {
        val bytes = readBytes(uri) ?: return@runCatching null
        decodeBytes(bytes)
    }.getOrNull()

    private fun readBytes(uri: String): ByteArray? = runCatching {
        if (uri.startsWith("file://")) {
            val path = uri.removePrefix("file://")
            val direct = runCatching { java.io.File(path).readBytes() }.getOrNull()
            if (direct != null) return@runCatching direct
            val id = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATA + "=?", arrayOf(path), null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return@runCatching null
            context.contentResolver.openInputStream(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))?.use { it.readBytes() }
        } else {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        }
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val maxWidth = max(1, cardBounds.width().toInt())
        val maxHeight = max(1, cardBounds.height().toInt())
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(probe.outWidth, probe.outHeight, maxWidth, maxHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val orientation = runCatching {
            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        android.util.Log.d("DuoPhotoCategory", "decode ${decoded.width}x${decoded.height} orientation=$orientation uri=${bytes.size}B")
        if (orientation == android.media.ExifInterface.ORIENTATION_NORMAL) return decoded
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }.getOrDefault(decoded)
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= maxWidth && sourceHeight / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }
}
