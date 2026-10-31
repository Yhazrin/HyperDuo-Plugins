package local.duostandby

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root-only passive reader for the platform Bluetooth manager's cached battery values.
 * It runs a single fixed command (`dumpsys bluetooth_manager`) through su, never a general
 * shell runner, and emits only rows where a device block contains an explicit battery value.
 * The raw dump is logged with MAC addresses redacted so the parser can be corrected against
 * the real HyperOS format instead of guessing. Nothing is persisted.
 */
class RootBluetoothBatteryProvider(
    context: Context,
    private val onAvailability: (BluetoothBatteryAvailability) -> Unit = {},
) : BatteryProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "RootBtBattery").apply { isDaemon = true } }
    private var listener: ((BatteryDeviceModel) -> Unit)? = null
    @Volatile private var active = false
    private val mac = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private val battery = Regex("(?i)battery[^0-9%]{0,24}(\\d{1,3})")
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            worker.execute { readOnce() }
            main.postDelayed(this, 60_000L)
        }
    }
    override fun start(onUpdate: (BatteryDeviceModel) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listener = onUpdate
        active = true
        main.post(poll)
    }
    private fun readOnce() {
        val text = runCatching {
            val process = ProcessBuilder("su", "-c", "dumpsys bluetooth_manager").redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(12, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching "" }
            if (process.exitValue() != 0) "" else out
        }.getOrDefault("")
        if (text.isEmpty()) { main.post { if (active) onAvailability(BluetoothBatteryAvailability.READ_FAILED) }; return }
        android.util.Log.d("DuoRootBt", text.replace(mac, "<mac>").take(20000))
        val rows = parse(text)
        main.post {
            if (!active) return@post
            if (rows.isEmpty()) { onAvailability(BluetoothBatteryAvailability.NO_SUPPORTED_DATA); return@post }
            rows.forEach { row -> listener?.invoke(row) }
            onAvailability(BluetoothBatteryAvailability.ACTIVE)
        }
    }
    /** A device block starts at a MAC or "Device:" line; the nearest name and battery belong to it. */
    private fun parse(text: String): List<BatteryDeviceModel> {
        val rows = mutableListOf<BatteryDeviceModel>()
        var name: String? = null
        var address: String? = null
        var pending: Int? = null
        fun flush() {
            val p = pending ?: return
            val display = name ?: address ?: return
            val id = "rootbt:" + (address ?: display).lowercase().hashCode().toUInt().toString(16)
            rows += BatteryDeviceModel(id, display, BatteryDeviceKind.HEADPHONES,
                listOf(BatteryComponent(BatteryComponentRole.MAIN, p, null, 1)), null,
                BatterySource.BLUETOOTH_PUBLIC, SystemClock.elapsedRealtime())
            pending = null
        }
        for (line in text.lineSequence()) {
            val m = mac.find(line)
            if (m != null) {
                flush(); address = m.value; name = null; pending = null; continue
            }
            if (line.trim().startsWith("Device:")) {
                flush(); name = line.substringAfter("Device:").trim().ifEmpty { null }; pending = null; continue
            }
            val b = battery.find(line)
            if (b != null) {
                val v = b.groupValues[1].toIntOrNull()
                if (v != null && v in 0..100) pending = v
            }
            if (name == null) {
                val n = Regex("(?i)\\bname\\s*[=:]\\s*(\\S.*)$").find(line)?.groupValues?.get(1)?.trim()
                if (n != null && n.length in 1..60) name = n
            }
        }
        flush()
        return rows.distinctBy { it.displayName }
    }
    override fun close() {
        active = false
        main.removeCallbacks(poll)
        worker.shutdownNow()
        listener = null
    }
}
