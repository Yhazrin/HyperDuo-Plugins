package local.duostandby

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.os.Build
import android.widget.Toast

/** Explicit Android access adapter; not Apple's widget editor or a background scanner. */
class BluetoothBatteryControls(
    private val activity: Activity,
    private val source: BatteryDataSource,
    private val restartPhoneOnly: () -> Unit,
) {
    companion object { const val REQUEST_CONNECT = 2020; const val REQUEST_SCAN = 2021 }
    private var dialog: AlertDialog? = null
    private var pendingScan: (() -> Unit)? = null
    private var active = false
    private val prefs = activity.getSharedPreferences("bluetooth_battery", android.content.Context.MODE_PRIVATE)
    fun start() { active = true }
    fun stop() { active = false; pendingScan = null; dialog?.dismiss(); dialog = null }
    fun show() {
        if (!active) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("蓝牙电量 · Android 接入")
            .setMessage("默认只显示本机真实电量。选择已配对设备后，可单次读取标准电池服务，或限时接收 AirPods 广播。不会自动发现附近设备；左右耳、盒子读不到时保持未知。设备选择仅保留到退出本次页面。")
            .setPositiveButton("选择已配对设备") { _, _ -> connectPermissionOrChoose() }
            .setNeutralButton("仅保留手机") { _, _ -> restartPhoneOnly() }
            .setNegativeButton("取消", null).show()
    }
    private fun connectPermissionOrChoose() {
        if (!BluetoothBatteryAccess.canConnect(activity) && Build.VERSION.SDK_INT >= 31) {
            activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
        } else chooseDevice()
    }
    fun permissionResult(code: Int) {
        if (!active) { pendingScan = null; return }
        when (code) {
            REQUEST_CONNECT -> if (BluetoothBatteryAccess.canConnect(activity)) chooseDevice() else message("未授权；继续显示手机电量")
            REQUEST_SCAN -> {
                val action = pendingScan; pendingScan = null
                if (BluetoothBatteryAccess.canScan(activity)) action?.invoke() else message("未授权扫描；耳机电量保持未知")
            }
        }
    }
    private fun chooseDevice() {
        if (!active) return
        val devices = try {
            val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { message("请先在系统中开启蓝牙；应用不会代为开启"); return }
            adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
        } catch (_: SecurityException) { message("蓝牙权限已撤销"); return }
        if (devices.isEmpty()) { message("没有已配对设备；请先在系统设置中配对"); return }
        val names = try { devices.mapIndexed { i, d -> "${d.name ?: "蓝牙设备"} · ${i + 1}" }.toTypedArray() }
            catch (_: SecurityException) { message("蓝牙权限已撤销"); return }
        dialog = AlertDialog.Builder(activity).setTitle("选择本次读取的设备")
            .setItems(names) { _, i ->
                val selection = BluetoothBatterySelection.fromExplicitBondedChoice(activity, devices[i])
                if (selection == null) message("设备或权限已不可用") else chooseProtocol(selection)
            }.setNegativeButton("取消", null).show()
    }
    private fun chooseProtocol(choice: BluetoothBatterySelection) {
        dialog = AlertDialog.Builder(activity).setTitle(choice.displayName)
            .setItems(arrayOf("跟随小米系统电量（被动，无扫描）", "读取标准蓝牙电池服务（单次）", "读取 AirPods 左右耳 / 盒子（最多 30 秒）", "AirPods 兼容扫描（软件过滤，最多 30 秒）", "root 自动读取所有耳机（dumpsys 缓存）", "导出 root dumpsys（校对解析）")) { _, i ->
                prefs.edit().putBoolean("root_auto", i == 4).apply()
                restartPhoneOnly()
                if (i == 0) {
                    source.addSelectedProvider(BluetoothBatterySystemProvider(activity, choice, ::availability))
                    message("已监听系统电量；等待系统更新，不会发起扫描或连接")
                } else if (i == 1) {
                    val provider = BluetoothBatteryGattProvider(activity, choice, ::availability)
                    source.addSelectedProvider(provider); provider.requestRead()
                } else if (i == 4) {
                    source.addSelectedProvider(RootBluetoothBatteryProvider(activity, ::availability))
                    message("root 已启用；每分钟从 dumpsys bluetooth_manager 读取缓存电量")
                } else if (i == 5) {
                    exportRootDump()
                } else {
                    val provider = BluetoothBatteryAirPodsProvider(activity, choice, ::availability,
                        softwareFiltering = i == 3)
                    source.addSelectedProvider(provider)
                    if (BluetoothBatteryAccess.canScan(activity)) provider.requestScan()
                    else {
                        pendingScan = { provider.requestScan() }
                        activity.requestPermissions(if (Build.VERSION.SDK_INT >= 31)
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_SCAN)
                    }
                }
            }.setNegativeButton("取消", null).show()
    }
    /** Diagnostic only: one fixed dumpsys command, MACs redacted, copied so the parser can be
     * corrected against the phone's real HyperOS output instead of guessed. */
    private fun exportRootDump() {
        message("正在读取 dumpsys bluetooth_manager…")
        Thread({
            val text = runCatching {
                val process = ProcessBuilder("su", "-c", "dumpsys bluetooth_manager").redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                out
            }.getOrDefault("")
            val redacted = text.replace(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"), "<mac>")
            activity.runOnUiThread {
                if (!active) return@runOnUiThread
                if (redacted.isBlank()) { message("dumpsys 为空或 su 未授权"); return@runOnUiThread }
                dialog = AlertDialog.Builder(activity).setTitle("root dumpsys（MAC 已隐藏）")
                    .setMessage(redacted.take(6000))
                    .setPositiveButton("复制全部") { _, _ ->
                        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("dumpsys", redacted))
                        message("已复制，可发给我校对解析")
                    }.setNegativeButton("关闭", null).show()
            }
        }, "RootBtDump").apply { isDaemon = true }.start()
    }

    private fun availability(status: BluetoothBatteryAvailability) {
        if (!active) return
        when(status) {
            BluetoothBatteryAvailability.NO_SUPPORTED_DATA -> message("未收到可关联的有效电量；不按名称或距离猜测设备")
            BluetoothBatteryAvailability.READ_FAILED, BluetoothBatteryAvailability.SCAN_FAILED -> message("设备未提供可读取的电量")
            BluetoothBatteryAvailability.PERMISSION_REQUIRED -> message("需要由你授权蓝牙访问")
            BluetoothBatteryAvailability.DEVICE_UNAVAILABLE -> message("已选择设备当前不可用")
            BluetoothBatteryAvailability.EXPIRED -> message("电量读数已过期；请手动刷新")
            else -> Unit
        }
    }
    private fun message(value: String) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show() }
}
