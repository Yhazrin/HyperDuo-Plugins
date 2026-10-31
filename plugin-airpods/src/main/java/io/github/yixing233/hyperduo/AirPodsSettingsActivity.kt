// SPDX-License-Identifier: GPL-3.0-only
package io.github.yixing233.hyperduo

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.hyperduo.plugin.ui.*

/** Standalone launcher and optional host-library entry; configuration stays in this APK. */
class AirPodsSettingsActivity : PluginActivity() {
    private var enabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enabled = AirPodsPluginConfig.preferences(this).getBoolean(AirPodsPluginConfig.ENABLED, false)
        setPluginContent {
            PluginPage("AirPods", "连接素材与蓝牙设置静态图 · AirPods Pro 2（2420）") {
                PluginSection("素材") {
                    PluginSwitch("启用 AirPods 素材", "连接动画和蓝牙设置图片", enabled) { value ->
                        enabled = value
                        AirPodsPluginConfig.preferences(this@AirPodsSettingsActivity).edit()
                            .putBoolean(AirPodsPluginConfig.ENABLED, value).apply()
                    }
                    PluginText(if (enabled) "素材开关已开启；系统效果需启用 LSPosed 插件" else "已关闭，使用系统原有素材。")
                }
                PluginSection("使用说明") {
                    PluginText("在原 LSPosed 管理器启用本插件，并勾选“小米蓝牙”和“设置”。")
                    PluginText("配置和素材随本插件独立保存，无需安装 HyperDuo 主程序。开关不改变配对记录或蓝牙连接。")
                }
                PluginAbout()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enabled = AirPodsPluginConfig.preferences(this).getBoolean(AirPodsPluginConfig.ENABLED, false)
    }
}
