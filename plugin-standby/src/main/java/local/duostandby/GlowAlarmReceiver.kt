package local.duostandby

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test alarm chain: GLOW_SCHEDULE -> AlarmManager.setAlarmClock -> GLOW_FIRE ->
 * high-importance notification with a full-screen intent to GlowActivity.
 * This mirrors the sanctioned Android alarm presentation path (full-screen intent), since a
 * background broadcast cannot start an activity directly on Android 10+.
 */
class GlowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SCHEDULE -> {
                val delay = intent.getLongExtra("delay_ms", 5_000L).coerceIn(1_000L, 3_600_000L)
                val atTime = intent.getStringExtra("at_time")
                val label = intent.getStringExtra("label") ?: atTime ?: "闹钟"
                val triggerAt = if (atTime != null) nextOccurrence(atTime)
                    ?: (System.currentTimeMillis() + delay) else System.currentTimeMillis() + delay
                val fire = Intent(context, GlowAlarmReceiver::class.java).setAction(ACTION_FIRE)
                    .putExtra("label", label)
                val pending = PendingIntent.getBroadcast(context, 7, fire,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val alarm = context.getSystemService(AlarmManager::class.java) ?: return
                if (android.os.Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) return
                alarm.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pending), pending)
            }
            ACTION_FIRE, ACTION_START -> {
                val label = intent.getStringExtra("label") ?: "闹钟"
                // Same-APK integration uses the sanctioned full-screen alarm path.
                // Never invoke the other installed StandBy app, or launch activities via su.
                show(context, label)
                // With SYSTEM_ALERT_WINDOW granted this background activity start is allowed;
                // otherwise the platform keeps the full-screen notification path.
                runCatching {
                    context.startActivity(Intent(context, GlowActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("label", label))
                }
            }
        }
    }

    /** "HH:mm" next occurrence in the device time zone; null for malformed input. */
    private fun nextOccurrence(hhmm: String): Long? {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val now = java.time.LocalDateTime.now()
        var target = now.toLocalDate().atTime(hour, minute)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun show(context: Context, label: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Glow 闹钟", NotificationManager.IMPORTANCE_HIGH))
        val full = PendingIntent.getActivity(context, 9,
            Intent(context, GlowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("label", label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Gentle Glow（Android 近似）")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }

    companion object {
        const val ACTION_SCHEDULE = "local.hyperduo.standby.GLOW_SCHEDULE"
        const val ACTION_FIRE = "local.hyperduo.standby.GLOW_FIRE"
        const val ACTION_START = "local.hyperduo.standby.GLOW_START"
        private const val CHANNEL = "glow_alarm"
    }
}
