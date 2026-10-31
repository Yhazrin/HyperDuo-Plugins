package local.duostandby

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.concurrent.TimeUnit

/**
 * Xiaomi 18 Fold adapter for OPENED_REVERSE_PRESENTATION (identifier 6).
 *
 * The stock camera proves the DeviceStateRequest presentation mechanism with state 5. State 6
 * is the OEM-defined reverse layout needed here: cover display is primary for alarm controls and
 * the large inner display is secondary for Glow. Duo StandBy is not platform-signed, so the
 * temporary-root adapter uses the device_state shell command. A watchdog restores base state.
 */
class RootPresentationDeviceState(private val displays: DisplayManager) {
    data class Result(val active: Boolean, val detail: String)

    @Volatile private var requested = false

    fun requestAsync(callback: (Result) -> Unit) {
        if (!isLhasa()) {
            callback(Result(false, "unsupported_device:${Build.DEVICE}"))
            return
        }
        Thread({
            val result = runRoot(REQUEST_COMMAND)
            requested = result.first
            callback(Result(result.first, result.second))
        }, "DuoDisplayState6").start()
    }

    fun resetAsync() {
        if (!requested) return
        requested = false
        Thread({
            val result = runRoot(RESET_COMMAND)
            Log.i(TAG, "state reset ok=${result.first} detail=${result.second}")
        }, "DuoDisplayStateReset").start()
    }

    fun innerPresentationDisplay(): Display? = displays.displays.firstOrNull {
        it.displayId == INNER_PRESENTATION_DISPLAY_ID && it.state == Display.STATE_ON
    }

    fun bothDisplaysOn(): Boolean {
        val byId = displays.displays.associateBy { it.displayId }
        return byId[OUTER_PRIMARY_DISPLAY_ID]?.state == Display.STATE_ON &&
            byId[INNER_PRESENTATION_DISPLAY_ID]?.state == Display.STATE_ON
    }

    private fun runRoot(command: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val ok = completed && process.exitValue() == 0
        ok to "exit=${if (completed) process.exitValue() else "timeout"} output=${output.take(240)}"
    }.getOrElse { false to "${it.javaClass.simpleName}:${it.message}" }

    private fun isLhasa(): Boolean = Build.DEVICE.equals("lhasa", ignoreCase = true) ||
        Build.MODEL.equals("2608BPX34C", ignoreCase = true)

    companion object {
        private const val TAG = "DuoDisplayState"
        private const val OUTER_PRIMARY_DISPLAY_ID = 0
        private const val INNER_PRESENTATION_DISPLAY_ID = 1
        private const val COMMAND_TIMEOUT_SECONDS = 8L
        private const val PID_FILE = "/data/local/tmp/hyperduo-standby-state6-watchdog.pid"
        private const val DOLLAR = '$'

        // 单行脚本里 `&` 后面不能跟 `;`：之前的 trimIndent().replace('\n', ';') 会把
        // `(...) &` 和后面的 `echo $!` 拼成 `&;`，sh 直接 syntax error，state 6 从未被执行。
        private val REQUEST_COMMAND = "old=; " +
            "[ -r $PID_FILE ] && old=$(" + DOLLAR + "cat $PID_FILE 2>/dev/null); " +
            "[ -n \"$" + DOLLAR + "old\" ] && kill \"$" + DOLLAR + "old\" 2>/dev/null; " +
            "cmd device_state state 6 || exit 21; " +
            "(sleep 660; cmd device_state state reset >/dev/null 2>&1) </dev/null >/dev/null 2>&1 & " +
            "pid=$" + DOLLAR + "!; echo $" + DOLLAR + "pid > $PID_FILE; echo STATE6_REQUESTED"

        private val RESET_COMMAND = "old=; " +
            "[ -r $PID_FILE ] && old=$(" + DOLLAR + "cat $PID_FILE 2>/dev/null); " +
            "[ -n \"$" + DOLLAR + "old\" ] && kill \"$" + DOLLAR + "old\" 2>/dev/null; " +
            ": > $PID_FILE; " +
            "cmd device_state state reset; echo STATE_RESET"
    }
}
