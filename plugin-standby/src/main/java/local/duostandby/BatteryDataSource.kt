package local.duostandby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Phone-only by default. Lifecycle and callbacks run on the main thread. No permission or scan side effects. */
class BatteryDataSource(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((List<BatteryDeviceModel>) -> Unit)? = null
    private val values = linkedMapOf<String, BatteryDeviceModel>()
    private val providers = mutableListOf<BatteryProvider>()
    private var registered = false
    private var generation = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) updatePhone(intent)
        }
    }

    fun start(onUpdate: (List<BatteryDeviceModel>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listener = onUpdate
        if (!registered) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            sticky?.let(::updatePhone)
        }
        // Persisted root opt-in: only this fixed dumpsys reader, added without any scan/connection.
        if (app.getSharedPreferences("bluetooth_battery", Context.MODE_PRIVATE).getBoolean("root_auto", false)) {
            addSelectedProvider(RootBluetoothBatteryProvider(app))
        }
        emit()
    }

    /** Call only following the user's explicit device/provider choice. No discovery is implicit. */
    fun addSelectedProvider(provider: BatteryProvider) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(registered) { "start first" }
        if (provider in providers) return
        providers += provider
        val token = generation
        provider.start { value ->
            main.post {
                if (token == generation && provider in providers) {
                    values[value.id] = value
                    emit()
                }
            }
        }
    }

    private fun updatePhone(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (scale > 0 && level in 0..scale) (100L * level / scale).toInt() else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        values["phone"] = BatteryDeviceModel("phone", Build.MODEL, BatteryDeviceKind.PHONE,
            listOf(BatteryComponent(BatteryComponentRole.MAIN, percent, charging, 1)), true,
            BatterySource.ANDROID_BATTERY, SystemClock.elapsedRealtime())
        emit()
    }

    private fun emit() { listener?.invoke(values.values.toList()) }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        providers.forEach { it.close() }
        providers.clear()
        if (registered) app.unregisterReceiver(receiver)
        registered = false
        values.clear()
        listener = null
    }
}
