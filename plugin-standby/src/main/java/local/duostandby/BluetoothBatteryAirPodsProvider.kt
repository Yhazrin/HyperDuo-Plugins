package local.duostandby

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * A bounded, explicit BLE scan, NEVER proximity-based ownership guessing.
 * Exact bonded identity only: rotating/private addresses often cannot be resolved by a normal app.
 * Those devices intentionally remain unknown. No MAC, adverts, or history are persisted/logged.
 */
class BluetoothBatteryAirPodsProvider(
    context: Context,
    private val selection: BluetoothBatterySelection,
    private val onAvailability: (BluetoothBatteryAvailability) -> Unit = {},
    private val softwareFiltering: Boolean = false,
) : BatteryProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((BatteryDeviceModel) -> Unit)? = null
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var current: BatteryDeviceModel? = null
    private var active = false
    private val expire = Runnable {
        current?.let { value ->
            current = value.copy(components = value.components.map { it.copy(percent = null, charging = null, stale = true) })
            listener?.invoke(current!!)
            onAvailability(BluetoothBatteryAvailability.EXPIRED)
        }
    }
    private val stopWindow = Runnable {
        stopScan()
        onAvailability(if (current == null) BluetoothBatteryAvailability.NO_SUPPORTED_DATA else BluetoothBatteryAvailability.IDLE)
    }

    override fun start(onUpdate: (BatteryDeviceModel) -> Unit) {
        checkMain()
        listener = onUpdate
        active = true
        // Registering a provider does not initiate scanning or request permission.
        listener?.invoke(unknown())
        onAvailability(BluetoothBatteryAvailability.IDLE)
    }

    /** Invoke only from an explicit user scan action, after the app has obtained runtime permission. */
    fun requestScan() {
        checkMain()
        if (!active) return
        stopScan()
        if (!BluetoothBatteryAccess.canScan(app)) {
            invalidate(BluetoothBatteryAvailability.PERMISSION_REQUIRED); return
        }
        if (!BluetoothBatteryAccess.stillBonded(app, selection)) {
            invalidate(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE); return
        }
        try {
            val manager = app.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            val localScanner = if (adapter?.isEnabled == true) adapter.bluetoothLeScanner else null
            if (localScanner == null) {
                invalidate(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE); return
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    main.post {
                        if (!active || callback !== this) return@post
                        if (!BluetoothBatteryAccess.canScan(app) || !BluetoothBatteryAccess.stillBonded(app, selection)) {
                            stopScan(); invalidate(BluetoothBatteryAvailability.PERMISSION_REQUIRED); return@post
                        }
                        try {
                            // This is the ONLY association criterion. Names, model, RSSI are insufficient.
                            if (result.device.address != selection.device.address) return@post
                            val bytes = result.scanRecord?.getManufacturerSpecificData(BluetoothBatteryAirPodsDecoder.MANUFACTURER_ID)
                                ?: return@post
                            val reading = BluetoothBatteryAirPodsDecoder.decode(bytes) ?: return@post
                            val now = SystemClock.elapsedRealtime()
                            val age = now - result.timestampNanos / 1_000_000L
                            if (age < 0 || age > 5_000L) return@post
                            current = BatteryDeviceModel(selection.id, selection.displayName, BatteryDeviceKind.HEADPHONES,
                                reading.components, null, BatterySource.AIRPODS_BLE, now)
                            main.removeCallbacks(expire)
                            main.postDelayed(expire, READING_TTL_MS)
                            listener?.invoke(current!!)
                        } catch (_: SecurityException) {
                            stopScan(); invalidate(BluetoothBatteryAvailability.PERMISSION_REQUIRED)
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if (callback === this) { stopScan(); invalidate(BluetoothBatteryAvailability.SCAN_FAILED) }
                    }
                }
            }
            scanner = localScanner
            callback = cb
            val filter = ScanFilter.Builder().setDeviceAddress(selection.device.address)
                .setManufacturerData(BluetoothBatteryAirPodsDecoder.MANUFACTURER_ID, byteArrayOf(7, 25, 1))
                .build()
            // Some Xiaomi/HyperOS stacks drop manufacturer adverts in offloaded filters.
            // Compatibility is an explicit user choice, not an automatic broader scan.
            // Callback identity + manufacturer + decoder gates above remain identical;
            // no unrelated address, name or payload is stored or surfaced.
            val platformFilters = if (softwareFiltering) emptyList() else listOf(filter)
            localScanner.startScan(platformFilters, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(0).build(), cb)
            main.postDelayed(stopWindow, SCAN_WINDOW_MS)
            onAvailability(BluetoothBatteryAvailability.ACTIVE)
        } catch (_: SecurityException) {
            stopScan(); invalidate(BluetoothBatteryAvailability.PERMISSION_REQUIRED)
        } catch (_: IllegalStateException) {
            stopScan(); invalidate(BluetoothBatteryAvailability.DEVICE_UNAVAILABLE)
        }
    }

    private fun unknown() = BatteryDeviceModel(selection.id, selection.displayName, BatteryDeviceKind.HEADPHONES,
        listOf(BatteryComponentRole.LEFT, BatteryComponentRole.RIGHT, BatteryComponentRole.CASE).map {
            BatteryComponent(it, null, null, 10)
        }, null, BatterySource.AIRPODS_BLE, SystemClock.elapsedRealtime())

    private fun invalidate(reason: BluetoothBatteryAvailability) {
        main.removeCallbacks(expire)
        current = null
        listener?.invoke(unknown())
        onAvailability(reason)
    }

    private fun stopScan() {
        main.removeCallbacks(stopWindow)
        val oldCallback = callback
        callback = null
        if (oldCallback != null) try { scanner?.stopScan(oldCallback) } catch (_: SecurityException) {
            // Permission may have been revoked while a scan was pending. Never log identifying data.
        } catch (_: IllegalStateException) { }
        scanner = null
    }

    override fun close() {
        checkMain()
        active = false
        stopScan()
        main.removeCallbacks(expire)
        current = null
        listener = null
        onAvailability(BluetoothBatteryAvailability.STOPPED)
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object {
        // App freshness/privacy policy, NOT Apple protocol timing.
        const val READING_TTL_MS = 60_000L
        const val SCAN_WINDOW_MS = 30_000L
    }
}
