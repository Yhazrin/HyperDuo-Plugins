package local.duostandby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * iOS 27 StandBy trigger adapter: the recovered SpringBoard policy is "trigger asserted
 * (fold pose) + ambient gate", charging only affects Always-On/inactivity suppression and
 * off-charge StandBy is supported. On Android we mirror the usable part:
 *   pose(TENT/BOOK) + screen off + (charging OR off-charge allowed) -> launch StandBy;
 *   pose FLAT/CLOSED, screen on or unplugged -> exit.
 * Signals: TYPE_HINGE_ANGLE via HingePoseDetector, ACTION_BATTERY_CHANGED, ACTION_SCREEN_*}
 */
class StandByTriggerService : Service() {
    private lateinit var detector: HingePoseDetector
    private var pose: HingePose = HingePose.UNKNOWN
    private var charging = false
    private var screenOff = false
    private var presented = false

    override fun onCreate() {
        super.onCreate()
        // startForeground must complete within the ANR window; pre-create channel/notification
        // before anything else so MIUI does not tear the service down while waiting.
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        detector = HingePoseDetector(this) { p ->
            pose = p
            Log.d(TAG, "hinge pose=$p angle=${detector.angle}")
            evaluate()
        }
        if (!detector.start()) Log.w(TAG, "no TYPE_HINGE_ANGLE on this device")
        screenOff = !powerManager().isInteractive
        val batteryFilter=android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val screenFilter=android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val sticky = if(Build.VERSION.SDK_INT>=33){
            registerReceiver(screenReceiver,screenFilter,Context.RECEIVER_EXPORTED)
            registerReceiver(batteryReceiver,batteryFilter,Context.RECEIVER_EXPORTED)
        }else{
            registerReceiver(screenReceiver,screenFilter)
            registerReceiver(batteryReceiver,batteryFilter)
        }
        chargeState(sticky)
        evaluate()
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            chargeState(intent); evaluate()
        }
    }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF ||
                (intent.action == Intent.ACTION_SCREEN_ON && !powerManager().isInteractive)
            if (intent.action == Intent.ACTION_SCREEN_ON) screenOff = false
            if (intent.action == Intent.ACTION_USER_PRESENT) screenOff = false
            evaluate()
        }
    }

    private fun chargeState(intent: Intent?) {
        // registerReceiver(null, ...) throws on API 34 for non-exported callers; use the sticky
        // intent returned by registerReceiver(batteryReceiver, ...) instead.
        val i = intent ?: return
        charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
            || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun evaluate() {
        val shouldPresent = screenOff && !charging && (pose == HingePose.TENT || pose == HingePose.BOOK)
        if (shouldPresent && !presented) {
            presented = true
            Log.d(TAG, "present StandBy pose=$pose angle=${detector.angle}")
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TRIGGERED, true)
            })
        } else if (!shouldPresent && presented) {
            presented = false
            Log.d(TAG, "dismiss StandBy pose=$pose charging=$charging screenOff=$screenOff")
            sendBroadcast(Intent(ACTION_DISMISS).setPackage(packageName))
        }
    }

    private fun powerManager() = getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "StandBy trigger", NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("StandBy 触发器").setContentText("等待折叠姿态")
            .setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (presented) { presented=false; sendBroadcast(Intent(ACTION_DISMISS).setPackage(packageName)) }
        runCatching { unregisterReceiver(batteryReceiver); unregisterReceiver(screenReceiver) }
        detector.stop()
        super.onDestroy()
    }

    companion object {
        const val TAG = "DuoTrigger"
        const val CHANNEL = "duo_standby_trigger"
        const val NOTIFICATION_ID = 4201
        const val ACTION_DISMISS = "local.hyperduo.standby.DISMISS_STANDBY"
        const val EXTRA_TRIGGERED = "triggered"
        fun start(context: Context) = context.startForegroundService(Intent(context, StandByTriggerService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, StandByTriggerService::class.java))
    }
}
