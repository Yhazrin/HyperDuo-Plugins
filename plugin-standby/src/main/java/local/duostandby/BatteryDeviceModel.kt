package local.duostandby

/** Local presentation identity: never a Bluetooth address. Unknown is not zero. */
data class BatteryDeviceModel(
    val id: String,
    val displayName: String,
    val kind: BatteryDeviceKind,
    val components: List<BatteryComponent>,
    val connected: Boolean?,
    val source: BatterySource,
    val updatedAtElapsedMillis: Long,
)

data class BatteryComponent(
    val role: BatteryComponentRole,
    val percent: Int?,
    val charging: Boolean?,
    /** Quantization, NOT a guarantee of measurement accuracy. */
    val precisionPercent: Int,
    val stale: Boolean = false,
) {
    init {
        require(percent == null || percent in 0..100)
        require(precisionPercent in 1..100)
    }
}

enum class BatteryDeviceKind { PHONE, HEADPHONES, OTHER }
enum class BatteryComponentRole { MAIN, LEFT, RIGHT, CASE }
enum class BatterySource { ANDROID_BATTERY, BLUETOOTH_PUBLIC, AIRPODS_BLE }

/** Extension point; providers must emit only selected devices, not nearby discoveries. */
interface BatteryProvider : AutoCloseable {
    fun start(onUpdate: (BatteryDeviceModel) -> Unit)
    override fun close()
}
