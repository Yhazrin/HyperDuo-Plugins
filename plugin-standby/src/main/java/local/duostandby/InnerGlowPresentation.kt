package local.duostandby

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/** Pure amber indirect light on the large inner panel; alarm controls stay on the cover. */
class InnerGlowPresentation(
    innerContext: Context,
    display: Display,
    private val alarmLabel: String,
    private val listener: GlowView.Listener,
    private val glowMode: GlowView.GlowMode,
) : Presentation(innerContext, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Presentation 默认是带 dimBehind 的对话框窗口：内屏上会漏出 dim 后的背景，
            // 把柔光混成"掺灰的橙"（实测中心从 #FF9500 变成 1:0.635:0.217）。
            // 这里改成不透明黑底 + 关掉 dim，柔光才叠在纯黑上。
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            it.setDimAmount(0f)
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 官方观感是"整块面板被点亮"，之前 0.30 的亮度上限太暗（用户实测反馈）。
            // 内屏柔光给满亮度；外屏闹钟仍保持低亮度以免半夜刺眼。
            it.attributes = it.attributes.apply { screenBrightness = 1.0f }
        }
        val glow = GlowView(context, true, alarmLabel, listener).also {
            it.setDualInnerMode(true)
            // 内屏 Presentation 自己 new 的 GlowView 要跟着 A/B 模式走，否则永远停在默认实现。
            it.glowMode = glowMode
            // state 6 下这块内屏面板相对逻辑坐标是倒的（用户实测内容 180° 反着）。
            // 旋转内容视图而不是改系统显示旋转：只影响本 Presentation，不动全局。
            it.rotation = 180f
        }
        setContentView(glow)
    }
}
