package local.duostandby

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Shizuku UserService. One fixed read route, no shell interpreter, arbitrary URI or writes. */
class XiaomiPhotoReadService @JvmOverloads constructor(context: Context? = null) : IXiaomiPhotoReadService.Stub() {
    private val ownerUid = context?.applicationInfo?.uid ?: -1
    private val busy = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CuratedPhotoDeadline").apply { isDaemon = true } }
    private fun authorize() {
        check(Process.myUid() == 2000) { "Only ADB mode is supported" }
        if (ownerUid < 10000 || Binder.getCallingUid() != ownerUid) throw SecurityException("Wrong bridge owner")
    }
    override fun getUid(): Int { authorize(); return Process.myUid() }
    // Reserved Shizuku server transaction; never an app-accessible command runner.
    override fun destroy() { System.exit(0) }
    override fun openPhoto(imageId: String): ParcelFileDescriptor {
        authorize()
        require(imageId.matches(Regex("[0-9]{1,20}"))) { "Invalid curated image ID" }
        check(busy.compareAndSet(false, true)) { "Image read already in progress" }
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (e: Exception) { busy.set(false); throw e }
        Thread({
            var process: java.lang.Process? = null
            var deadline: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                val uri = "content://com.miui.gallery.widget/maml/openImage?id=$imageId&w=512&h=512&widgetId=local.duostandby"
                val child = ProcessBuilder("/system/bin/content", "read", "--uri", uri).start()
                process = child
                deadline = timer.schedule({ child.destroyForcibly(); runCatching { pipe[1].closeWithError("Image read timed out") } }, 15, TimeUnit.SECONDS)
                // Drain bounded diagnostics without mixing CLI error text into image bytes.
                val errors = java.util.concurrent.atomic.AtomicBoolean(false)
                val stderr = Thread({ child.errorStream.use { stream ->
                    val b = ByteArray(1024); while (stream.read(b) >= 0) errors.set(true)
                } }, "CuratedPhotoErrors").apply { isDaemon = true; start() }
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                child.inputStream.use { input ->
                    var total = 0; val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > 12 * 1024 * 1024) throw IOException("Image exceeds limit")
                        out.write(buffer, 0, n)
                    }
                    if (!child.waitFor(2, TimeUnit.SECONDS)) throw IOException("Reader did not exit")
                    stderr.join(500)
                    if (child.exitValue() != 0 || errors.get() || total == 0) throw IOException("Gallery read failed")
                }
                out.close()
            } catch (e: Exception) {
                runCatching { pipe[1].closeWithError(e.javaClass.simpleName) }
            } finally {
                deadline?.cancel(false); process?.destroyForcibly(); runCatching { pipe[1].close() }; busy.set(false)
            }
        }, "CuratedPhotoReadOnly").apply { isDaemon = true; start() }
        return pipe[0]
    }
    override fun readCapture(imageId: String): String {
        authorize()
        require(imageId.matches(Regex("[0-9]{1,20}"))) { "Invalid curated image ID" }
        val uri = "content://media/external/images/media/$imageId"
        val child = ProcessBuilder(
            "/system/bin/content", "query", "--uri", uri,
            "--projection", "datetaken:latitude:longitude"
        ).redirectErrorStream(false).start()
        val text = try { child.inputStream.bufferedReader().use { it.readText() } } finally {
            child.errorStream.bufferedReader().use { it.readText() }
        }
        if (!child.waitFor(8, TimeUnit.SECONDS) || child.exitValue() != 0) return "{}"
        val json = org.json.JSONObject()
        Regex("(datetaken|latitude|longitude)=([^,\\n]+)").findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (value.isEmpty() || value == "NULL") return@forEach
            when (key) {
                "datetaken" -> value.toLongOrNull()?.let { json.put(key, it) }
                else -> value.toDoubleOrNull()?.let { json.put(key, it) }
            }
        }
        return json.toString()
    }
}
