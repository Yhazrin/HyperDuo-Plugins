package local.duostandby

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Foldable hinge pose detector. Android TYPE_HINGE_ANGLE reports 0..360:
 * 0 = fully unfolded flat, 360 = fully folded. The iOS 27 SpringBoard pose enum
 * (unknown/closed/partiallyOpen/openFlat + tabletopPose tent/book) has no published
 * angle boundary in the binary (boundaries live in SBFDevicePosePTSettings, not
 * recovered), so the ranges below are an explicit Android adaptation and are
 * intentionally coarse until calibrated on the device.
 */
enum class HingePose { UNKNOWN, FLAT, BOOK, TENT, CLOSED }

class HingePoseDetector(
    private val context: Context,
    private val onChanged: (HingePose) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private var last: HingePose = HingePose.UNKNOWN
    var angle: Float = -1f
        private set

    fun start(): Boolean {
        val s = sensor ?: return false
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        return true
    }

    fun stop() = runCatching { sensors.unregisterListener(this) }.let { }

    override fun onSensorChanged(event: SensorEvent) {
        angle = event.values.firstOrNull() ?: return
        val pose = poseFor(angle)
        if (pose != last) {
            last = pose
            onChanged(pose)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Coarse Android mapping; tent ~ L-shape (90°), book ~ partly folded. */
        fun poseFor(angle: Float): HingePose = when {
            angle < 0f -> HingePose.UNKNOWN
            angle < 15f -> HingePose.FLAT
            angle > 345f -> HingePose.CLOSED
            angle in 70f..110f -> HingePose.TENT
            angle < 180f -> HingePose.BOOK
            else -> HingePose.BOOK
        }
    }
}
