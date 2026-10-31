package local.duostandby

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * StandBy notification bridge. The ambient poster presents the standard notification banner
 * (SBNotificationBannerDestination ambient gate); on Android the equivalent source is a
 * NotificationListenerService. Only the user's "notification access" grant is needed, no root.
 */
object NotificationRelay {
    data class Item(
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val time: Long,
        val icon: Bitmap?,
    )

    /** Called on the listener thread; the UI must switch threads itself. */
    var onItem: ((Item) -> Unit)? = null
    private var lastKey: String? = null

    fun post(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == context.packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val key = "${sbn.packageName}|${notification.`when`}|$title|$text"
        if (key == lastKey) return
        lastKey = key
        val pm = context.packageManager
        val appName = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val icon = runCatching {
            val drawable = notification.getSmallIcon()?.loadDrawable(context)
                ?: pm.getApplicationIcon(sbn.packageName)
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                drawable.draw(Canvas(bmp))
            }
        }.getOrNull()
        onItem?.invoke(Item(sbn.packageName, appName, title, text, notification.`when`, icon))
    }

    private const val ICON_PX = 144
}

class StandByNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { NotificationRelay.post(this, sbn) }
    }
}
