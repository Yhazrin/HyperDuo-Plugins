package local.duostandby

/** Non-connecting public-builder capability check for a newer runtime than compileSdk.
 * No hidden API exemptions, old private socket constructors, permissions or discovery.
 */
object BluetoothAapPublicCapability {
    fun inspect(): String {
        if (android.os.Build.VERSION.SDK_INT < 37) return "NOT_PROBED_BELOW_API37"
        return try {
            val builder = Class.forName("android.bluetooth.BluetoothSocketSettings\$Builder")
            val instance = builder.getConstructor().newInstance()
            builder.getMethod("setSocketType", Int::class.javaPrimitiveType).invoke(instance, 3)
            builder.getMethod("setL2capPsm", Int::class.javaPrimitiveType).invoke(instance, 0x1001)
            builder.getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType).invoke(instance, true)
            builder.getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType).invoke(instance, true)
            builder.getMethod("build").invoke(instance)
            "SETTINGS_ACCEPTED_TYPE3_PSM4097_NO_SOCKET_OPENED"
        } catch (error: ReflectiveOperationException) {
            val cause = error.cause ?: error
            "UNAVAILABLE_${cause.javaClass.simpleName}:${cause.message}"
        } catch (error: IllegalArgumentException) {
            "UNAVAILABLE_IllegalArgumentException:${error.message}"
        }
    }
}
