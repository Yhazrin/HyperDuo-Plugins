// SPDX-License-Identifier: GPL-3.0-only
package io.github.hyperduo.plugin.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/** Shared presentation only. Each APK keeps its own configuration and feature code. */
open class PluginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    fun setPluginContent(content: @Composable () -> Unit) {
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                    content()
                    MiuixPopupUtils.MiuixPopupHost()
                }
            }
        }
    }
}

@Composable
fun PluginPage(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            content()
        }
    }
}

@Composable
fun PluginSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 12.dp), fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun PluginSwitch(title: String, summary: String = "", checked: Boolean, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    SuperSwitch(title = title, summary = summary.takeIf { it.isNotBlank() }, checked = checked,
        enabled = enabled, onCheckedChange = onCheckedChange)
}

@Composable
fun PluginAction(title: String, summary: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    BasicComponent(title = title, summary = summary.takeIf { it.isNotBlank() }, enabled = enabled,
        onClick = onClick, rightActions = {
            Text("›", fontSize = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        })
}

@Composable
fun PluginText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
fun PluginAbout(version: String = "") {
    val context = LocalContext.current
    val appVersion = remember(version) {
        version.ifBlank { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    }
    fun open(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    PluginSection("关于 · $appVersion") {
        PluginAction("Yhazrin", "作者 · GitHub") { open("https://github.com/Yhazrin") }
        PluginAction("HyperDuo 主项目", "桌面与系统增强") { open("https://github.com/Yhazrin/HyperDuo") }
        PluginAction("插件发布仓库", "源码、版本与独立安装包") { open("https://github.com/Yhazrin/HyperDuo-Plugins") }
    }
}
