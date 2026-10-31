// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hyperduo.plugin.ui.*
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Standalone configuration; resource operations only follow an explicit tap. */
class GallerySettingsActivity : PluginActivity() {
    private var span by mutableStateOf(true)
    private var nativeEdit by mutableStateOf(true)
    private var resourceStatus by mutableStateOf("尚未检查。已部署设备继续使用原路径。")
    private var checking by mutableStateOf(false)
    private var exporting by mutableStateOf(false)
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportResources(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPreferences()
        setPluginContent {
            val confirmCheck = remember { mutableStateOf(false) }
            PluginPage("照片组件", "4×6 半页组件 · 相册原生编辑器") {
                PluginSection("组件") {
                    PluginSwitch("相册 4×6 半页组件", "在桌面添加半页照片组件", span) { value ->
                        span = value
                        GalleryConfig.prefs(this@GallerySettingsActivity).edit().putBoolean(GalleryConfig.SPAN, value).apply()
                    }
                    PluginSwitch("使用相册原生编辑器", "从照片组件打开原生编辑入口", nativeEdit) { value ->
                        nativeEdit = value
                        GalleryConfig.prefs(this@GallerySettingsActivity).edit().putBoolean(GalleryConfig.EDIT, value).apply()
                    }
                }
                PluginSection("资源") {
                    PluginAction(if (checking) "正在检查资源…" else "检查已部署资源", "Root · 只读检查", !checking) {
                        confirmCheck.value = true
                    }
                    PluginText(resourceStatus)
                    PluginAction(if (exporting) "正在导出…" else "导出公开占位模板部署包", "ZIP · 电脑端部署", !exporting) {
                        exportLauncher.launch("HyperDuo-Gallery-resources.zip")
                    }
                }
                PluginSection("部署与使用") {
                    PluginText("可独立安装和设置。需要 LSPosed 与“小米智能助理”作用域。HyperDuo 171+ 可统一管理本插件。")
                    PluginText(DEPLOYMENT)
                }
                PluginAbout()
            }
            SuperDialog(show = confirmCheck, title = "检查资源",
                summary = "将请求 Root，只读取原 PA/Home 路径的就绪标记和组件文件是否存在。",
                onDismissRequest = { confirmCheck.value = false }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", { confirmCheck.value = false }, Modifier.weight(1f))
                    TextButton("开始检查", {
                        confirmCheck.value = false
                        checkResources()
                    }, Modifier.weight(1f))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        readPreferences()
    }

    private fun readPreferences() {
        val prefs = GalleryConfig.prefs(this)
        span = prefs.getBoolean(GalleryConfig.SPAN, true)
        nativeEdit = prefs.getBoolean(GalleryConfig.EDIT, true)
    }

    private fun checkResources() {
        checking = true
        resourceStatus = "正在执行只读检测…"
        Thread({
            val result = try {
                val command = """
                    set -e
                    test "${'$'}(id -u)" = 0
                    pa=/data/user/0/com.miui.personalassistant/files
                    home=/data/user_de/0/com.miui.home/files/maml/res/0
                    test -f "${'$'}pa/duo-gallery-halfpage-v1/ready.json"
                    test "${'$'}(cat "${'$'}pa/duo-gallery-halfpage-v3/protocol-ready")" = DUO_GALLERY_NATIVE_23_V3
                    for pair in e96919c6-105a-4b6e-986e-9eb910e1fa15:40117 10538035-e37d-4edd-88db-1420c9ccaa04:40116 1648abf9-db10-4941-8083-9a5e68241c75:40116; do
                    id=${'$'}{pair%:*}; ver=${'$'}{pair#*:}
                    test -f "${'$'}pa/duo-gallery-halfpage-v3/resources/${'$'}id/${'$'}ver/${'$'}id/widget_4x6"
                    test -f "${'$'}home/${'$'}id/${'$'}ver/${'$'}id.duo4x6.zip"
                    done
                    echo RESOURCE_FILES_PRESENT
                """.trimIndent()
                val process = ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val exit = process.waitFor()
                if (exit == 0) "资源文件与就绪标记已存在。组件 SHA／ROM 条件和 LSPosed 作用域由插件运行时检查。"
                else "资源检查未通过（exit=$exit）。请检查部署文件与 Root 授权。\n$output"
            } catch (error: Exception) { "资源检测失败：${error.message}" }
            runOnUiThread {
                resourceStatus = result
                checking = false
            }
        }, "gallery-resource-check").start()
    }

    private fun exportResources(destination: Uri) {
        exporting = true
        Thread({
            val message = try {
                contentResolver.openOutputStream(destination).use { output ->
                    ZipOutputStream(output).use { zip ->
                        exportAssets(zip, "gallery-halfpage")
                        zip.putNextEntry(ZipEntry("GALLERY-DEPLOY.txt"))
                        zip.write(DEPLOYMENT.toByteArray(StandardCharsets.UTF_8))
                        zip.closeEntry()
                    }
                }
                "资源包已导出：$destination\n按电脑端命令部署；本次只写入所选 ZIP。"
            } catch (error: Exception) { "导出失败：${error.message}" }
            runOnUiThread {
                resourceStatus = message
                exporting = false
            }
        }, "gallery-resource-export").start()
    }

    private fun exportAssets(zip: ZipOutputStream, path: String) {
        val children = assets.list(path)!!
        if (children.isNotEmpty()) {
            children.forEach { child -> exportAssets(zip, "$path/$child") }
            return
        }
        zip.putNextEntry(ZipEntry("plugin-gallery/src/main/assets/$path"))
        assets.open(path).use { it.copyTo(zip, 16384) }
        zip.closeEntry()
    }

    companion object {
        private val DEPLOYMENT = """
            部署步骤（电脑端，需要 adb、Python 3 和已授权的 Root）
            1. 将导出的 ZIP 解压到公开插件源码仓库根目录。
            2. 先在原生小米组件中心下载／授权本插件支持的相册组件。
            3. 在仓库根目录执行：
            python3 plugin-gallery/tools/stage-gallery-resources.py --apply --serial SERIAL
            将 SERIAL 替换为 adb devices 中的手机序列号。工具默认打印计划，--apply 才写资源；保留原授权包、组件记录和照片。
            部署工具适配固定 ROM／组件版本；桌面需要可容纳 4×6 的布局。
            4. 在 LSPosed 为本插件启用“小米智能助理”作用域。
        """.trimIndent()
    }
}
