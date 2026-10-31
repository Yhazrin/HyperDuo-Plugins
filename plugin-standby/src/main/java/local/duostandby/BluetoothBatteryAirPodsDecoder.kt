package local.duostandby

/**
 * Independent implementation of public, unencrypted Apple proximity battery fields.
 * Input is manufacturer 0x004C data, WITHOUT the two-byte manufacturer identifier.
 * Protocol facts/provenance and deliberately unsupported cases: references/battery-data-v20.
 * Parsing identifies a model, never ownership. The caller must independently bind identity.
 */
object BluetoothBatteryAirPodsDecoder {
    const val MANUFACTURER_ID = 0x004C
    data class Reading(val modelName: String, val components: List<BatteryComponent>)
    private val models = mapOf(
        0x0220 to "AirPods (1st generation)",
        0x0F20 to "AirPods (2nd generation)",
        0x1320 to "AirPods (3rd generation)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2nd generation)",
        0x2420 to "AirPods Pro (2nd generation, USB-C)",
    )

    fun decode(manufacturerData: ByteArray): Reading? {
        // Reject unknown/encrypted/clone formats rather than reading arbitrary offsets.
        if (manufacturerData.size != 27) return null
        fun octet(i: Int) = manufacturerData[i].toInt() and 255
        if (octet(0) != 7 || octet(1) != 25 || octet(2) != 1) return null
        val model = models[(octet(3) shl 8) or octet(4)] ?: return null
        val leftIsPrimary = octet(5) and 0x20 != 0
        val primary = octet(6) and 15
        val secondary = octet(6) ushr 4
        val flags = octet(7) ushr 4
        fun component(role: BatteryComponentRole, level: Int, chargeMask: Int): BatteryComponent {
            // F = unavailable. B..E are out-of-range/reserved: do NOT invent 100%.
            val percent = if (level in 0..10) level * 10 else null
            return BatteryComponent(role, percent, if (percent == null) null else flags and chargeMask != 0, 10)
        }
        return Reading(model, listOf(
            component(BatteryComponentRole.LEFT, if (leftIsPrimary) primary else secondary, if (leftIsPrimary) 1 else 2),
            component(BatteryComponentRole.RIGHT, if (leftIsPrimary) secondary else primary, if (leftIsPrimary) 2 else 1),
            component(BatteryComponentRole.CASE, octet(7) and 15, 4),
        ))
    }
}
