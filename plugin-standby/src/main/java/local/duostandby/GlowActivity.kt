package local.duostandby

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.WindowManager

/** State 6 host: cover display alarm controls + large inner display amber-light Presentation. */
class GlowActivity : Activity(), GlowView.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var innerGlow: InnerGlowPresentation? = null
    private lateinit var displayState: RootPresentationDeviceState
    private lateinit var glowView: GlowView
    private var dualDisplayActive = false
    private var ending = false
    private var destroyed = false
    private var alarmLabel = "闹钟"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        // 单屏（无 state 6）时给一个温和亮度；双屏亮起后由两侧各自设置（内屏 1.0）。
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }
        alarmLabel = intent.getStringExtra("label") ?: "闹钟"
        val deadline = intent.getLongExtra("duration_ms", 10 * 60_000L)
        val displayId = if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: 0
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId

        displayState = RootPresentationDeviceState(getSystemService(DisplayManager::class.java))
        val forceOuter = intent.getBooleanExtra("force_outer", false)
        glowView = GlowView(this, displayId == 1, alarmLabel, this, forceOuter = forceOuter)
        // A/B：--es glow_mode material|emission；默认 material（原版 timerYellow 材质路径）。
        when (intent.getStringExtra("glow_mode")?.lowercase()) {
            "emission" -> glowView.glowMode = GlowView.GlowMode.EMISSION
            "material" -> glowView.glowMode = GlowView.GlowMode.MATERIAL
        }
        setContentView(glowView)
        handler.postDelayed({ endAlarm() }, deadline.coerceAtLeast(30_000L))

        // A source merge is not consent to force an OEM device-state override.
        // Only the explicit integration control may enable this path.
        val dualOptIn = getSharedPreferences("standby_integration", MODE_PRIVATE)
            .getBoolean("dual_display_opt_in", false)
        if (dualOptIn && !forceOuter && !intent.getBooleanExtra("single_display", false)) {
            displayState.requestAsync { result ->
                handler.post {
                    if (destroyed) {
                        if (result.active) displayState.resetAsync()
                        return@post
                    }
                    dualDisplayActive = result.active
                    Log.i("DuoDisplayState", "request active=${result.active} detail=${result.detail}")
                    if (result.active) {
                        // State 6: display 0 is the cover primary; display 1 is the large inner panel.
                        // 注意：这里的 screenBrightness 会作用到整块面板（实测内屏也被拉到 0.16，
                        // 导致"柔光很暗"）。要亮内屏就不能在这里压暗，交给 Presentation 自己设 1.0。
                        window.attributes = window.attributes.apply { screenBrightness = 1.0f }
                        glowView.setDualOuterMode()
                        waitForInnerDisplay(0)
                    } else {
                        glowView.setSingleMode(displayId == 1)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (dualDisplayActive) waitForInnerDisplay(0)
    }

    override fun onStop() {
        runCatching { innerGlow?.dismiss() }
        innerGlow = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { innerGlow?.dismiss() }
        innerGlow = null
        handler.removeCallbacksAndMessages(null)
        if (dualDisplayActive) displayState.resetAsync()
        super.onDestroy()
    }

    override fun onAlarmStop() = endAlarm()

    override fun onSnooze() {
        val alarm = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && alarm?.canScheduleExactAlarms() == false) {
            // Explicit user action only; never crash the merged app or leave state 6 active.
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:$packageName"))) }
            endAlarm()
            return
        }
        val intent = Intent(this, GlowAlarmReceiver::class.java)
            .setAction(GlowAlarmReceiver.ACTION_FIRE)
            .putExtra("label", "稍后提醒")
        val pending = PendingIntent.getBroadcast(this, 42, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5 * 60_000L, pending)
        endAlarm()
    }

    private fun endAlarm() {
        if (ending) return
        ending = true
        if (dualDisplayActive) displayState.resetAsync()
        finish()
    }

    private fun waitForInnerDisplay(attempt: Int) {
        if (!dualDisplayActive || ending || innerGlow?.isShowing == true) return
        val inner = displayState.innerPresentationDisplay()
        if (inner != null && displayState.bothDisplaysOn()) {
            runCatching {
                innerGlow = InnerGlowPresentation(
                    createDisplayContext(inner), inner, alarmLabel, this, glowView.glowMode
                ).also { it.show() }
            }.onFailure { Log.e("DuoDisplayState", "inner Glow presentation failed", it) }
        } else if (attempt < 30) {
            handler.postDelayed({ waitForInnerDisplay(attempt + 1) }, 100L)
        } else {
            Log.w("DuoDisplayState", "state 6 requested but inner display did not become ON")
            dualDisplayActive = false
            displayState.resetAsync()
            glowView.setSingleMode(false)
        }
    }
}
