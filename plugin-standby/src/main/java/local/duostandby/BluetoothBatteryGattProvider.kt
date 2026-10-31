package local.duostandby

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Public SDK aggregate battery path: explicit, one-shot standard Battery Service read.
 * No reflection, hidden getBatteryLevel(), pairing, notifications, characteristic writes or scan.
 * Unsupported devices return unknown. BAS cannot identify left/right/case or charging state.
 */
class BluetoothBatteryGattProvider(
    context: Context,
    private val selection: BluetoothBatterySelection,
    private val onAvailability: (BluetoothBatteryAvailability) -> Unit = {},
) : BatteryProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((BatteryDeviceModel) -> Unit)? = null
    private var gatt: BluetoothGatt? = null
    private var active = false
    private var reading: BatteryDeviceModel? = null
    private val timeout = Runnable { fail(BluetoothBatteryAvailability.READ_FAILED) }
    private val expire = Runnable {
        reading?.let { value ->
            reading = value.copy(components = value.components.map { it.copy(percent = null, charging = null, stale = true) })
            listener?.invoke(reading!!)
            onAvailability(BluetoothBatteryAvailability.EXPIRED)
        }
    }

    override fun start(onUpdate: (BatteryDeviceModel) -> Unit) {
        checkMain()
        active = true
        listener = onUpdate
        listener?.invoke(unknown())
        onAvailability(BluetoothBatteryAvailability.IDLE)
    }

    /** Call on an explicit user read/refresh action, not for every bonded device. */
    fun requestRead() {
        checkMain()
        if (!active) return
        releaseGatt()
        if (!BluetoothBatteryAccess.canConnect(app)) { fail(BluetoothBatteryAvailability.PERMISSION_REQUIRED); return }
        if (!BluetoothBatteryAccess.stillBonded(app, selection)) { fail(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE); return }
        try {
            gatt = selection.device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { fail(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE); return }
            main.postDelayed(timeout, 15_000)
            onAvailability(BluetoothBatteryAvailability.ACTIVE)
        } catch (_: SecurityException) { fail(BluetoothBatteryAvailability.PERMISSION_REQUIRED) }
          catch (_: IllegalStateException) { fail(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (!accept(connection)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(BluetoothBatteryAvailability.READ_FAILED); return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) try {
                    if (!connection.discoverServices()) fail(BluetoothBatteryAvailability.READ_FAILED)
                } catch (_: SecurityException) { fail(BluetoothBatteryAvailability.PERMISSION_REQUIRED) }
            }
        }
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            main.post {
                if (!accept(connection)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(BluetoothBatteryAvailability.READ_FAILED); return@post }
                // Multiple batteries need a richer mapping. Never label the first as aggregate arbitrarily.
                val services = connection.services.filter { it.uuid == SERVICE }
                val characteristic = services.singleOrNull()?.getCharacteristic(LEVEL)
                if (characteristic == null || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                    fail(BluetoothBatteryAvailability.NO_SUPPORTED_DATA); return@post
                }
                try {
                    if (!connection.readCharacteristic(characteristic)) fail(BluetoothBatteryAvailability.READ_FAILED)
                } catch (_: SecurityException) { fail(BluetoothBatteryAvailability.PERMISSION_REQUIRED) }
            }
        }
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int) { received(connection, characteristic, value.copyOf(), status) }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) received(connection, characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)
        }
    }

    private fun received(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        main.post {
            if (!accept(connection)) return@post
            if (characteristic.uuid != LEVEL || characteristic.service?.uuid != SERVICE) return@post
            val percent = if (status == BluetoothGatt.GATT_SUCCESS && value.size == 1)
                (value[0].toInt() and 255).takeIf { it <= 100 } else null
            if (percent == null) { fail(BluetoothBatteryAvailability.READ_FAILED); return@post }
            reading = BatteryDeviceModel(selection.id, selection.displayName, selection.kind,
                listOf(BatteryComponent(BatteryComponentRole.MAIN, percent, null, 1)), null,
                BatterySource.BLUETOOTH_PUBLIC, SystemClock.elapsedRealtime())
            releaseGatt() // Reading is a timestamped observation; no persistent connection is implied.
            main.removeCallbacks(expire)
            main.postDelayed(expire, READING_TTL_MS)
            listener?.invoke(reading!!)
            onAvailability(BluetoothBatteryAvailability.IDLE)
        }
    }

    private fun accept(connection: BluetoothGatt): Boolean {
        if (!active || gatt !== connection) return false
        if (!BluetoothBatteryAccess.stillBonded(app, selection)) {
            fail(BluetoothBatteryAvailability.PERMISSION_REQUIRED); return false
        }
        return true
    }
    private fun unknown() = BatteryDeviceModel(selection.id, selection.displayName, selection.kind,
        listOf(BatteryComponent(BatteryComponentRole.MAIN, null, null, 1)), null,
        BatterySource.BLUETOOTH_PUBLIC, SystemClock.elapsedRealtime())
    private fun fail(reason: BluetoothBatteryAvailability) {
        releaseGatt()
        main.removeCallbacks(expire)
        reading = null
        listener?.invoke(unknown())
        onAvailability(reason)
    }
    private fun releaseGatt() {
        main.removeCallbacks(timeout)
        val old = gatt
        gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        try { old?.close() } catch (_: SecurityException) { }
    }
    override fun close() {
        checkMain()
        active = false
        releaseGatt()
        main.removeCallbacks(expire)
        reading = null
        listener = null
        onAvailability(BluetoothBatteryAvailability.STOPPED)
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object {
        private val SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        // Application freshness policy; not an original Apple parameter.
        const val READING_TTL_MS = 60_000L
    }
}
