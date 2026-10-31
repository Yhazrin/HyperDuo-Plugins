package local.duostandby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * MTMaterialKit timerYellow 的 Android 移植。
 *
 * 顺序严格按 ClockAngel.app/timerYellow.materialrecipe：
 *   壁纸 → backdropScale 0.25 → blurRadius 30（blurAtEnd = 先缩后模糊）→ 4×5 colorMatrix。
 * Android ColorMatrix 的平移列是 0..255，所以 m15/m25/m35 乘 255。
 *
 * 壁纸来自 root 拷贝，不走 WallpaperManager（该路径在这台机上是
 * SecurityException: READ_EXTERNAL_STORAGE denied）。
 * 读不到文件时退回黑底再套矩阵，并写 `backdrop=black reason=...`。不造假背景。
 *
 * Android 适配（recipe 没有写明的部分）：
 *   - blurRadius 30 按「缩放后位图」的像素半径做三遍盒式模糊，不是 CA 高斯的采样证明；
 *   - `cp wallpaper* /data/local/tmp/duo-wallpaper-*` 在 toybox 上不会把每个文件改名
 *     （实机 CP_EXIT=1，目录里没有产出）。因此在同一条 su 脚本里、这条命令之后，
 *     再按原文件名显式拷到 duo-wallpaper_orig / duo-wallpaper。
 */
class GlowBackdropMaterial(private val context: Context) {
    enum class Recipe(val assetName: String) {
        TIMER_YELLOW("timerYellow.materialrecipe"),
        TIMER_DARK("timerDark.materialrecipe"),
    }

    /** ClockAngel timerYellow.materialrecipe。平移列已换成 0..255。 */
    private val yellow = ColorMatrix(
        floatArrayOf(
            0.5f, 0f, 0f, 0f, 0.5f * 255f,
            0f, 0.5f, 0f, 0f, 0.3f * 255f,
            0f, 0f, 0.5f, 0f, 0.02f * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /** ClockAngel timerDark.materialrecipe。 */
    private val dark = ColorMatrix(
        floatArrayOf(
            0.825f, 0f, 0f, 0f, 0.174f * 255f,
            0.5084f, 0f, 0f, 0f, 0.171f * 255f,
            -0.26f, 0f, 0f, 0f, 0.195f * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var cached: Bitmap? = null
    private var cachedRecipe: Recipe? = null
    private var cachedMarker: String? = null
    @Volatile
    var backdropSource: String = "none"
        private set

    /**
     * 只在 recipe 或壁纸文件长度变化时重建。
     * 显示尺寸不进缓存键：recipe 的缩放和模糊作用在壁纸像素上，画的时候再铺到视图。
     */
    fun prepare(recipe: Recipe) {
        val marker = sourceMarker()
        val live = cached
        if (live != null && !live.isRecycled && cachedRecipe == recipe && cachedMarker == marker) return
        live?.let { if (!it.isRecycled) it.recycle() }
        cached = null
        cachedRecipe = null
        val built = buildBackdrop(recipe)
        cached = built
        cachedRecipe = recipe
        cachedMarker = marker
    }

    fun draw(canvas: Canvas, bounds: RectF, recipe: Recipe, alpha: Float) {
        prepare(recipe)
        val bmp = cached ?: return
        paint.colorFilter = null
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawBitmap(bmp, null, bounds, paint)
        paint.alpha = 255
    }

    fun release() {
        cached?.let { if (!it.isRecycled) it.recycle() }
        cached = null
        cachedRecipe = null
        cachedMarker = null
        backdropSource = "none"
    }

    private fun sourceMarker(): String {
        val orig = runCatching { File(PATH_ORIG).length() }.getOrDefault(0L)
        val plain = runCatching { File(PATH_PLAIN).length() }.getOrDefault(0L)
        return "$orig:$plain"
    }

    private fun buildBackdrop(recipe: Recipe): Bitmap {
        val src = readWallpaper()
        try {
            val w = src?.width ?: 1
            val h = src?.height ?: 1
            val sw = max(1, (w * BACKDROP_SCALE).toInt())
            val sh = max(1, (h * BACKDROP_SCALE).toInt())
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            paint.alpha = 255
            paint.colorFilter = null
            if (src != null) {
                canvas.drawBitmap(src, null, Rect(0, 0, sw, sh), paint)
            } else {
                canvas.drawColor(Color.BLACK)
            }
            val blurred = boxBlur(small, BLUR_RADIUS)
            if (blurred !== small && !small.isRecycled) small.recycle()
            val out = Bitmap.createBitmap(blurred.width, blurred.height, Bitmap.Config.ARGB_8888)
            paint.colorFilter = ColorMatrixColorFilter(if (recipe == Recipe.TIMER_YELLOW) yellow else dark)
            Canvas(out).drawBitmap(blurred, 0f, 0f, paint)
            paint.colorFilter = null
            if (blurred !== out && !blurred.isRecycled) blurred.recycle()
            return out
        } finally {
            if (src != null && !src.isRecycled) src.recycle()
        }
    }

    /**
     * 先跑调用方指定的 glob 拷贝；那条命令在本机不会产出文件，所以同一脚本里接着
     * 按 wallpaper_orig、wallpaper 的原名落到 /data/local/tmp/。
     * 解码顺序：duo-wallpaper_orig，然后 duo-wallpaper。直接打开失败再 su cat 同一路径。
     */
    private fun readWallpaper(): Bitmap? {
        val (code, output) = runSu(STAGE_COMMAND)
        val stage = output.ifBlank { "exit=$code" }
        val reasons = mutableListOf("stage $stage")
        for (path in CANDIDATES) {
            decodePath(path)?.let { bmp ->
                backdropSource = "wallpaper"
                Log.i(
                    TAG,
                    "backdrop=wallpaper path=$path read=direct decoded=${bmp.width}x${bmp.height} pkg=${context.packageName} $stage"
                )
                return bmp
            }
            reasons += "direct:$path:null"
            decodeSuCat(path)?.let { bmp ->
                backdropSource = "wallpaper"
                Log.i(
                    TAG,
                    "backdrop=wallpaper path=$path read=su-cat decoded=${bmp.width}x${bmp.height} pkg=${context.packageName} $stage"
                )
                return bmp
            }
            reasons += "su-cat:$path:null"
        }
        backdropSource = "black"
        Log.i(TAG, "backdrop=black reason=${reasons.joinToString(" | ")}")
        return null
    }

    private fun decodePath(path: String): Bitmap? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() < 16L || !file.canRead()) return@runCatching null
        BitmapFactory.decodeFile(path, imageOptions())
    }.getOrNull()

    private fun decodeSuCat(path: String): Bitmap? = runCatching {
        val process = ProcessBuilder("su", "-c", "cat '$path'").start()
        val bytes = process.inputStream.use { it.readBytes() }
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0 || !looksLikeImage(bytes)) return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, imageOptions())
    }.getOrNull()

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        return webp || jpeg || png
    }

    private fun imageOptions(): BitmapFactory.Options =
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

    private fun runSu(command: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!finished) {
            process.destroyForcibly()
            -1 to "timeout"
        } else {
            process.exitValue() to output.take(200)
        }
    }.getOrElse { -1 to "${it.javaClass.simpleName}:${it.message}" }

    companion object {
        private const val TAG = "DuoGlowMaterial"
        private const val COMMAND_TIMEOUT_SECONDS = 8L
        private const val PATH_ORIG = "/data/local/tmp/duo-wallpaper_orig"
        private const val PATH_PLAIN = "/data/local/tmp/duo-wallpaper"
        private val CANDIDATES = arrayOf(PATH_ORIG, PATH_PLAIN)

        /**
         * 第一段是指定的 glob 拷贝。toybox 不会把目标通配当成改名，所以 `requested`
         * 预期非 0；后面两行 `cp` 把原文件落到固定路径。脚本里没有 `&;`。
         */
        private val STAGE_COMMAND =
            "cp /data/system/users/0/wallpaper* /data/local/tmp/duo-wallpaper-* 2>/dev/null; " +
                "chmod 644 /data/local/tmp/duo-wallpaper-* 2>/dev/null; " +
                "requested=" + "$?; " +
                "cp /data/system/users/0/wallpaper_orig /data/local/tmp/duo-wallpaper_orig 2>/dev/null; " +
                "cp /data/system/users/0/wallpaper /data/local/tmp/duo-wallpaper 2>/dev/null; " +
                "chmod 644 /data/local/tmp/duo-wallpaper_orig /data/local/tmp/duo-wallpaper 2>/dev/null; " +
                "explicit=" + "$?; " +
                "echo requested=" + "\$requested explicit=" + "\$explicit"

        /** timerYellow.materialrecipe → backdropScale */
        const val BACKDROP_SCALE = 0.25f
        /** timerYellow.materialrecipe → blurRadius。单位按缩放后位图像素，是 Android 适配。 */
        const val BLUR_RADIUS = 30

        /** 三遍盒式模糊近似 recipe 的高斯模糊（形状是适配，半径数字来自 recipe）。 */
        fun boxBlur(src: Bitmap, radius: Int): Bitmap {
            if (radius <= 0) return src
            val w = src.width
            val h = src.height
            val inPx = IntArray(w * h)
            src.getPixels(inPx, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            var cur = inPx
            var nxt = tmp
            repeat(3) {
                boxPassH(cur, nxt, w, h, radius)
                boxPassV(nxt, cur, w, h, radius)
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(cur, 0, w, 0, 0, w, h)
            return out
        }

        private fun boxPassH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = r * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) {
                    val p = src[row + i.coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xFF; rr += (p ushr 16) and 0xFF
                    gg += (p ushr 8) and 0xFF; bb += p and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                    val outIdx = (x - r).coerceIn(0, w - 1)
                    val inIdx = (x + r + 1).coerceIn(0, w - 1)
                    val po = src[row + outIdx]; val pi = src[row + inIdx]
                    a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                    rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                    gg += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                    bb += (pi and 0xFF) - (po and 0xFF)
                }
            }
        }

        private fun boxPassV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val div = r * 2 + 1
            for (x in 0 until w) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) {
                    val p = src[i.coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xFF; rr += (p ushr 16) and 0xFF
                    gg += (p ushr 8) and 0xFF; bb += p and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                    val outIdx = (y - r).coerceIn(0, h - 1) * w + x
                    val inIdx = (y + r + 1).coerceIn(0, h - 1) * w + x
                    val po = src[outIdx]; val pi = src[inIdx]
                    a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                    rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                    gg += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                    bb += (pi and 0xFF) - (po and 0xFF)
                }
            }
        }
    }
}
