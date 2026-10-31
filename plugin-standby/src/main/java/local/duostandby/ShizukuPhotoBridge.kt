package local.duostandby

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Explicit opt-in read bridge.
 *
 * Two routes, same fixed read commands:
 *  - Shizuku ADB mode (uid 2000) via XiaomiPhotoReadService;
 *  - root (`su -c`) when Shizuku is not ADB mode. The root command is the same
 *    /system/bin/content read/query, never a general shell runner.
 */
class ShizukuPhotoBridge(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("xiaomi_curated_photo_source", Context.MODE_PRIVATE)
    @Volatile private var service: IXiaomiPhotoReadService? = null
    @Volatile var status = "尚未授权只读图片桥"; private set
    @Volatile private var preferRoot = false
    @Volatile private var rootChecked = false
    @Volatile private var rootReady = false
    private var bound = false
    private var closed = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, XiaomiPhotoReadService::class.java.name))
        .daemon(false).processNameSuffix("curated_photo_readonly").debuggable(false).version(31)
    private fun notifyChanged() { main.post { if (!closed) changed() } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            runCatching {
                val remote = IXiaomiPhotoReadService.Stub.asInterface(binder)
                check(remote.uid == 2000)
                service = remote; status = "Shizuku ADB 只读桥已连接"
            }.onFailure { service = null; status = "图片桥连接失败：${it.javaClass.simpleName}" }
            notifyChanged()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; bound = false; status = "图片桥已断开"; notifyChanged() }
    }
    private val received = Shizuku.OnBinderReceivedListener { main.post { if (!closed) connectIfGranted() } }
    private val dead = Shizuku.OnBinderDeadListener { service = null; bound = false; status = "Shizuku 已停止"; notifyChanged() }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == 2901) {
            if (grant == PackageManager.PERMISSION_GRANTED) connectIfGranted()
            else { status = "未获得 Shizuku 授权；不会越过授权读取"; notifyChanged() }
        }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }
    /** Runs on the UI thread: no su probe here, only the Shizuku check. */
    fun request() {
        prefs.edit().putBoolean("shizuku_readonly_opt_in", true).apply()
        val shizukuAdb = runCatching {
            check(Shizuku.pingBinder()) { "Shizuku not running" }
            check(Shizuku.getUid() == 2000) { "ADB mode required" }
            true
        }.getOrDefault(false)
        if (!shizukuAdb) {
            preferRoot = true
            status = "Shizuku 非 ADB 模式；读取时改用 root 只读桥"
            notifyChanged(); return
        }
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connectIfGranted()
            else { status = "请在 Shizuku 弹窗确认只读图片桥授权"; notifyChanged(); Shizuku.requestPermission(2901) }
        }.onFailure { status = "图片桥未就绪：${it.javaClass.simpleName}"; notifyChanged() }
    }
    fun connectIfGranted() {
        if (closed || bound || !prefs.getBoolean("shizuku_readonly_opt_in", false)) return
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            check(Shizuku.getUid() == 2000)
            bound = true; status = "正在连接 Shizuku 只读图片桥…"
            Shizuku.bindUserService(args, connection)
        }.onFailure {
            bound = false
            preferRoot = true
            status = "Shizuku 非 ADB 模式；读取时改用 root 只读桥"
            notifyChanged()
        }
    }
    /** Called on the photo IO worker, so an su permission prompt cannot block the UI thread. */
    private fun ensureRoot(): Boolean {
        if (!prefs.getBoolean("shizuku_readonly_opt_in", false) && !context.getSharedPreferences("standby_integration", Context.MODE_PRIVATE).getBoolean("root_gallery_opt_in", false)) return false
        if (rootChecked) return rootReady
        synchronized(this) {
            if (rootChecked) return rootReady
            rootReady = runCatching {
                val probe = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val text = probe.inputStream.bufferedReader().use { it.readText() }
                if (!probe.waitFor(25, TimeUnit.SECONDS)) { probe.destroyForcibly(); return@runCatching false }
                probe.exitValue() == 0 && text.contains("uid=0")
            }.getOrDefault(false)
            rootChecked = true
            if (rootReady) { status = "root 只读图片桥已启用"; notifyChanged() }
        }
        return rootReady
    }
    fun openPhoto(id: String): ParcelFileDescriptor? {
        // Shizuku may be bound without the API grant; fall through to the root bridge instead of
        // returning null from the let block.
        val remote = service?.let { remote -> runCatching { remote.openPhoto(id) }.getOrNull() }
        if (remote != null) return remote
        if (!ensureRoot()) return null
        if (!id.matches(Regex("[0-9]{1,20}"))) return null
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread({
            var process: Process? = null
            try {
                // The gallery widget MAML provider can fail on HyperOS ("ContentResolver failed to open URI").
                // The id is the MediaStore media id, so read the real photo through MediaStore instead.
                val uri = "content://media/external/images/media/$id"
                process = ProcessBuilder("su", "-c", "/system/bin/content read --uri '$uri'").start()
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                process.inputStream.use { input -> input.copyTo(out, 16384) }
                out.close()
                if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) throw IOException("root gallery read failed")
            } catch (e: Exception) {
                runCatching { pipe[1].closeWithError(e.javaClass.simpleName) }
            } finally {
                process?.destroyForcibly(); runCatching { pipe[1].close() }
            }
        }, "CuratedPhotoRootRead").apply { isDaemon = true }.start()
        return pipe[0]
    }
    /** Read one local gallery photo file (时光 card image) through root; path is validated. */
    fun readFile(path: String): ParcelFileDescriptor? {
        if (!path.startsWith("/storage/emulated/") || path.contains("'")) return null
        if (!ensureRoot()) return null
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread({
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", "cat '$path'").start()
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                process.inputStream.use { input -> input.copyTo(out, 65536) }
                out.close()
                if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) throw IOException("root file read failed")
            } catch (e: Exception) {
                runCatching { pipe[1].closeWithError(e.javaClass.simpleName) }
            } finally {
                process?.destroyForcibly(); runCatching { pipe[1].close() }
            }
        }, "ShiguangFileRead").apply { isDaemon = true }.start()
        return pipe[0]
    }
    /** One already-returned curated id. The bridge reads that row; it does not accept a list or a caption. */
    fun readCapture(id: String): String? {
        if (!id.matches(Regex("[0-9]{1,20}"))) return null
        val remote = service?.let { remote -> runCatching { remote.readCapture(id) }.getOrNull() }
        if (remote != null) return remote
        if (!ensureRoot()) return null
        return runCatching {
            val uri = "content://media/external/images/media/$id"
            val child = ProcessBuilder("su", "-c",
                "/system/bin/content query --uri $uri --projection datetaken:latitude:longitude").start()
            val text = child.inputStream.bufferedReader().use { it.readText() }
            child.errorStream.bufferedReader().use { it.readText() }
            if (!child.waitFor(8, TimeUnit.SECONDS) || child.exitValue() != 0) return null
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
            json.toString()
        }.getOrNull()
    }
    fun disconnect() {
        prefs.edit().putBoolean("shizuku_readonly_opt_in", false).apply()
        service = null; preferRoot = false; rootReady = false; rootChecked = false
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false; status = "图片桥已断开"
    }
    fun close() {
        closed = true; service = null; preferRoot = false
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false
    }
}
