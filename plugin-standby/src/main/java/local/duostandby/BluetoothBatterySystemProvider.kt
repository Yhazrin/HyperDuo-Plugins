package local.duostandby

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Verified Android 17 RemoteDevices broadcast. Non-SDK compatibility adapter,
 * NOT a privileged metadata reader; MAIN only, no invented left/right/case.
 * Passive: no connection, scan, pairing, hidden getter or system-setting change.
 */
class BluetoothBatterySystemProvider(context: Context, private val selection: BluetoothBatterySelection,
    private val onAvailability: (BluetoothBatteryAvailability) -> Unit = {}) : BatteryProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var callback: ((BatteryDeviceModel) -> Unit)? = null
    private var registered = false
    private val expiry = Runnable { emit(null, true); onAvailability(BluetoothBatteryAvailability.EXPIRED) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !BluetoothBatteryAccess.canConnect(app)) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val same = try { device != null && device.address == selection.device.address }
                catch (_: SecurityException) { false }
            if (!same) return
            main.removeCallbacks(expiry)
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) { emit(null, true); return }
            val percent = intent.getIntExtra(EXTRA_LEVEL, -1).takeIf { it in 0..100 }
            emit(percent, false)
            if (percent != null) main.postDelayed(expiry, 15 * 60_000L)
        }
    }
    override fun start(onUpdate: (BatteryDeviceModel) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        callback = onUpdate; emit(null, false)
        if (!BluetoothBatteryAccess.stillBonded(app, selection)) {
            onAvailability(BluetoothBatteryAvailability.PERMISSION_REQUIRED); return
        }
        if (!registered) {
            val filter = IntentFilter(ACTION_LEVEL).apply { addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) }
            // The Bluetooth system sender holds this signature permission. This is a sender
            // constraint, NOT a request/grant of privileged permission to our app.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter,
                Manifest.permission.BLUETOOTH_PRIVILEGED, main, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH_PRIVILEGED, main)
            registered = true
        }
        onAvailability(BluetoothBatteryAvailability.ACTIVE)
    }
    private fun emit(percent: Int?, stale: Boolean) {
        callback?.invoke(BatteryDeviceModel(selection.id, selection.displayName, selection.kind,
            listOf(BatteryComponent(BatteryComponentRole.MAIN, percent, null, 1, stale)),
            null, BatterySource.BLUETOOTH_PUBLIC, SystemClock.elapsedRealtime()))
    }
    override fun close() {
        main.removeCallbacks(expiry)
        if (registered) app.unregisterReceiver(receiver)
        registered = false; callback = null
    }
    companion object {
        const val ACTION_LEVEL = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
