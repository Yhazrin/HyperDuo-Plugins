package local.duostandby

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/** Session-only choice. Construct only after the user explicitly selects a bonded device. */
class BluetoothBatterySelection private constructor(
    internal val device: BluetoothDevice,
    val id: String,
    val displayName: String,
    val kind: BatteryDeviceKind,
) {
    companion object {
        fun fromExplicitBondedChoice(context: Context, device: BluetoothDevice,
            kind: BatteryDeviceKind = BatteryDeviceKind.OTHER): BluetoothBatterySelection? {
            if (!BluetoothBatteryAccess.canConnect(context)) return null
            return try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) null else
                    BluetoothBatterySelection(device, "bt-${UUID.randomUUID()}",
                        device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth device", kind)
            } catch (_: SecurityException) { null }
        }
    }
}

/** Status values contain no address or payload. They are UI guidance, not telemetry. */
enum class BluetoothBatteryAvailability {
    IDLE, PERMISSION_REQUIRED, DEVICE_UNAVAILABLE, ACTIVE, NO_SUPPORTED_DATA,
    READ_FAILED, SCAN_FAILED, EXPIRED, STOPPED
}

internal object BluetoothBatteryAccess {
    fun canConnect(context: Context): Boolean = context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
    ) == PackageManager.PERMISSION_GRANTED

    fun canScan(context: Context): Boolean = canConnect(context) && context.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    fun stillBonded(context: Context, selection: BluetoothBatterySelection): Boolean =
        canConnect(context) && try {
            selection.device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) { false }
}
