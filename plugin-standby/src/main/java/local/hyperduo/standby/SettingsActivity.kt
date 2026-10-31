package local.hyperduo.standby

import android.Manifest
import android.app.AlertDialog
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TimePicker
import android.widget.Toast
import androidx.compose.runtime.*
import io.github.hyperduo.plugin.ui.*
import local.duostandby.GlowActivity
import local.duostandby.GlowAlarmReceiver
import local.duostandby.StandByNotificationListener
import local.duostandby.StandByTriggerService

/** Standalone settings use the shared MIUIX shell; the original scenes/editors stay native. */
class SettingsActivity:PluginActivity() {
    private val integration by lazy { getSharedPreferences("standby_integration",MODE_PRIVATE) }
    private val trigger by lazy { getSharedPreferences("standby_trigger",MODE_PRIVATE) }
    private var pendingPreview:String?=null
    private var revision by mutableIntStateOf(0)
    private val dataOptions=listOf(
        "root_music_opt_in" to "音乐小组件数据",
        "root_calendar_opt_in" to "日程小组件数据",
        "root_gallery_opt_in" to "小米时光数据")
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setPluginContent { SettingsContent() }
        openRequestedAction(intent)
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);openRequestedAction(intent)
    }
    private fun openRequestedAction(intent:Intent) {
        val action=intent.getStringExtra("plugin_action")
        intent.removeExtra("plugin_action")
        when(action) {
            "preview" -> preview(null)
            "widgets" -> preview("WIDGETS")
            "photos" -> openPhotos()
        }
    }
    override fun onResume() { super.onResume();refresh() }
    private fun refresh() { revision++ }
    private data class State(val trigger:Boolean,val dual:Boolean,val sources:List<Boolean>,val imported:Boolean,val missingPhotos:Int)
    @Composable private fun SettingsContent() {
        val state=remember(revision) {
            val migration=getSharedPreferences("plugin_migration",MODE_PRIVATE)
            State(trigger.getBoolean("enabled",false),integration.getBoolean("dual_display_opt_in",false),
                dataOptions.map { integration.getBoolean(it.first,false) },migration.getLong("imported_at",0)>0,
                migration.getStringSet("photo_access_required",emptySet()).orEmpty().size)
        }
        PluginPage("HyperDuo 待机","时钟、照片与柔光闹钟 · 可独立使用") {
            PluginSection("待机体验") {
                PluginAction("打开待机","长按内容，进入原有时钟和照片编辑器") { preview(null) }
                PluginAction("照片待机与编辑","使用系统照片授权和原有类别、轮换配置") { openPhotos() }
                PluginAction("时钟与组件","原有样式、颜色和小组件配置") { preview("WIDGETS") }
            }
            PluginSection("自动进入") {
                PluginSwitch("折叠时自动进入（实验）","部分折叠、未充电并熄屏时触发",state.trigger,onCheckedChange=::enableTrigger)
                PluginAction("后台进入待机授权","允许此插件显示悬浮窗") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                }
            }
            PluginSection("通知与系统屏保") {
                PluginAction("待机通知使用权","新插件需要在系统中单独授权") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this@SettingsActivity,StandByNotificationListener::class.java).flattenToString()))
                }
                PluginAction("系统屏保","在系统设置中选择 HyperDuo 待机") { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
                PluginAction("允许插件发送通知","用于折叠触发器和柔光闹钟") {
                    if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101)
                    else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                }
            }
            PluginSection("柔光闹钟") {
                PluginAction("预览柔光",if(state.dual) "双屏预览，退出后恢复设备状态" else "单屏预览，不改变设备姿态") {
                    val intent=Intent(this@SettingsActivity,GlowActivity::class.java).putExtra("label","柔光预览").putExtra("single_display",!state.dual)
                    if(state.dual)confirm("预览双屏柔光","通过 Root 临时请求小米双屏显示，退出后恢复设备状态。") { startActivity(intent) }
                    else startActivity(intent)
                }
                PluginSwitch("双屏柔光实验","仅在手动预览时请求双屏显示，需要 Root 授权",state.dual) { value ->
                    if(value)confirm("启用双屏柔光","仅在你手动预览时请求双屏显示，需要此插件单独获得 Root 授权。") {
                        integration.edit().putBoolean("dual_display_opt_in",true).apply();refresh()
                    } else { integration.edit().putBoolean("dual_display_opt_in",false).apply();refresh() }
                }
                PluginAction("设置闹钟时间","沿用系统 AlarmClock 与原柔光界面",onClick=::chooseAlarm)
                if(Build.VERSION.SDK_INT>=31)PluginAction("精确闹钟授权","由系统管理此插件的闹钟权限") {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
                }
                if(Build.VERSION.SDK_INT>=34)PluginAction("全屏闹钟授权","允许柔光闹钟显示全屏界面") {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName")))
                }
            }
            PluginSection("数据来源") {
                PluginAction("蓝牙电量权限","用于已连接设备的电量组件") {
                    val permissions=if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
                        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    requestPermissions(permissions,104)
                }
                dataOptions.forEachIndexed { index,(key,title) ->
                    PluginSwitch(title,"通过 Root 只读对应来源，不修改原数据",state.sources[index]) { value ->
                        if(value)confirm(title,"仅在对应组件使用时读取，不修改来源数据。此插件需要单独获得 Root 授权。") {
                            integration.edit().putBoolean(key,true).apply();refresh()
                        } else { integration.edit().putBoolean(key,false).apply();refresh() }
                    }
                }
            }
            PluginSection("原有配置") {
                PluginText(if(state.imported)"已接收 HyperDuo 显式迁移的配置，原本体数据保留。" else "直接使用即可创建配置；原本体的时钟和照片配置也可从 HyperDuo 插件库显式迁移。")
                if(state.missingPhotos>0)PluginAction("重新授权 ${state.missingPhotos} 个照片地址","打开照片编辑器，重新选择原照片",onClick=::openPhotos)
            }
            PluginAbout()
        }
    }
    private fun openPhotos() {
        val permission=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)preview("PHOTOS")
        else { pendingPreview="PHOTOS";requestPermissions(arrayOf(permission),103) }
    }
    private fun preview(face:String?) {
        startActivity(Intent(this,local.duostandby.MainActivity::class.java).apply { face?.let { putExtra("face",it) } })
    }
    private fun enableTrigger(enabled:Boolean) {
        if(!enabled) { trigger.edit().putBoolean("enabled",false).apply();StandByTriggerService.stop(this);refresh();return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101);refresh();return
        }
        if(!Settings.canDrawOverlays(this)) {
            confirm("后台进入待机","请允许此插件显示悬浮窗，然后重新开启折叠触发器。") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            };refresh();return
        }
        confirm("开启折叠待机","部分折叠、未充电并熄屏时进入待机；展开、合上或唤醒时退出。开启后显示常驻通知。") {
            StandByTriggerService.start(this);trigger.edit().putBoolean("enabled",true).apply();refresh()
        }
    }
    private fun chooseAlarm() {
        val manager=getSystemService(AlarmManager::class.java)
        if(Build.VERSION.SDK_INT>=31 && !manager.canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")));return
        }
        val picker=TimePicker(this).apply { setIs24HourView(true) }
        AlertDialog.Builder(this).setTitle("柔光闹钟").setView(picker).setNegativeButton("取消",null)
            .setPositiveButton("设置") { _,_ ->
                sendBroadcast(Intent(this,GlowAlarmReceiver::class.java).setAction(GlowAlarmReceiver.ACTION_SCHEDULE)
                    .putExtra("at_time","%02d:%02d".format(picker.hour,picker.minute)))
                Toast.makeText(this,"柔光闹钟已安排",Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun confirm(title:String,message:String,action:()->Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消") { _,_ -> refresh() }
            .setPositiveButton("继续") { _,_ -> action() }.show()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,grants:IntArray) {
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==103) { val face=pendingPreview;pendingPreview=null;if(grants.firstOrNull()==PackageManager.PERMISSION_GRANTED)preview(face) }
        refresh()
    }
}
