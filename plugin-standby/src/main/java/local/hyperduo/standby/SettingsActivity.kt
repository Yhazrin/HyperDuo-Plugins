package local.hyperduo.standby

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import local.duostandby.Face
import local.duostandby.GlowActivity
import local.duostandby.GlowAlarmReceiver
import local.duostandby.StandByNotificationListener
import local.duostandby.StandByTriggerService

/** Independent entry point; every runtime/special permission is requested by this APK. */
class SettingsActivity:Activity() {
    private lateinit var content:LinearLayout
    private val integration by lazy { getSharedPreferences("standby_integration",MODE_PRIVATE) }
    private val trigger by lazy { getSharedPreferences("standby_trigger",MODE_PRIVATE) }
    private var pendingPreview:String?=null
    override fun onCreate(state:Bundle?) { super.onCreate(state); render() }
    override fun onResume() { super.onResume(); if(::content.isInitialized)render() }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun render() {
        val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(242,242,247));isFillViewport=true }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(28),dp(20),dp(28)) }
        scroll.addView(content);setContentView(scroll)
        note("HyperDuo 待机",24)
        note("可独立使用，无需安装 HyperDuo。时钟、组件、照片和柔光闹钟均在这里设置，长按待机内容进入原有编辑器。")
        button("打开待机") { preview(null) }
        button("照片待机与照片编辑") {
            val permission=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)preview("PHOTOS")
            else { pendingPreview="PHOTOS";requestPermissions(arrayOf(permission),103) }
        }
        button("时钟与组件") { preview("WIDGETS") }
        switch("折叠时自动进入（实验）",trigger.getBoolean("enabled",false)) { enabled -> enableTrigger(enabled) }
        button("后台进入待机：悬浮窗授权") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        note("新插件的通知使用权和系统屏保需要在系统设置中重新授权；原本体的授权不会转移。")
        button("待机通知：重新授权通知使用权") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this,StandByNotificationListener::class.java).flattenToString()))
        }
        button("系统屏保：选择 HyperDuo 待机") { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
        button("允许插件发送通知") {
            if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        }
        button("蓝牙电量权限") {
            val permissions=if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(permissions,104)
        }
        button("柔光闹钟预览") {
            val intent=Intent(this,GlowActivity::class.java).putExtra("label","柔光预览")
                .putExtra("single_display",!integration.getBoolean("dual_display_opt_in",false))
            if(integration.getBoolean("dual_display_opt_in",false))confirm("预览双屏柔光","通过 Root 临时请求小米双屏显示，退出后恢复设备状态。") { startActivity(intent) }
            else startActivity(intent)
        }
        switch("双屏柔光实验（Root）",integration.getBoolean("dual_display_opt_in",false)) { value ->
            if(value)confirm("启用双屏柔光","仅在你手动预览时请求双屏显示，需要此插件单独获得 Root 授权。") {
                integration.edit().putBoolean("dual_display_opt_in",true).apply();render()
            } else integration.edit().putBoolean("dual_display_opt_in",false).apply()
        }
        button("设置柔光闹钟时间") { chooseAlarm() }
        button("精确闹钟授权") { if(Build.VERSION.SDK_INT>=31)startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }
        if(Build.VERSION.SDK_INT>=34)button("全屏闹钟授权") { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName"))) }
        for((key,title) in listOf("root_music_opt_in" to "音乐小组件数据（Root只读）","root_calendar_opt_in" to "日程小组件数据（Root只读）","root_gallery_opt_in" to "小米时光数据（Root只读）")) {
            switch(title,integration.getBoolean(key,false)) { value ->
                if(value)confirm(title,"仅在对应组件使用时读取，不修改来源数据。此插件需要单独获得 Root 授权。") {
                    integration.edit().putBoolean(key,true).apply();render()
                } else integration.edit().putBoolean(key,false).apply()
            }
        }
        val migration=getSharedPreferences("plugin_migration",MODE_PRIVATE)
        note(if(migration.getLong("imported_at",0)>0)"已接收本体显式迁移的原配置，源数据保留。" else "原时钟、照片配置可在 HyperDuo 插件库中显式迁移，源数据保留。")
        val missing=migration.getStringSet("photo_access_required",emptySet()).orEmpty().size
        if(missing>0)note("$missing 个原照片地址需要重新授权。打开照片待机，在原有照片编辑器中重新选择照片即可。")
    }
    private fun preview(face:String?) {
        startActivity(Intent(this,local.duostandby.MainActivity::class.java).apply { face?.let { putExtra("face",it) } })
    }
    private fun enableTrigger(enabled:Boolean) {
        if(!enabled) { trigger.edit().putBoolean("enabled",false).apply();StandByTriggerService.stop(this);return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101);render();return
        }
        if(!Settings.canDrawOverlays(this)) {
            confirm("后台进入待机","请允许此插件显示悬浮窗，然后重新开启折叠触发器。") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            };render();return
        }
        confirm("开启折叠待机","部分折叠、未充电并熄屏时进入待机；展开、合上或唤醒时退出。开启后显示常驻通知。") {
            StandByTriggerService.start(this);trigger.edit().putBoolean("enabled",true).apply();render()
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
    private fun note(text:String,size:Int=14) { content.addView(TextView(this).apply { this.text=text;textSize=size.toFloat();setTextColor(Color.rgb(35,35,40));setPadding(0,dp(10),0,dp(10)) }) }
    private fun button(label:String,action:()->Unit) {
        content.addView(Button(this).apply { text=label;isAllCaps=false;setOnClickListener { action() } },
            LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(6) })
    }
    private fun switch(label:String,value:Boolean,action:(Boolean)->Unit) {
        content.addView(Switch(this).apply { text=label;isChecked=value;setPadding(dp(12),dp(8),dp(12),dp(8));setOnCheckedChangeListener { _,checked -> action(checked) } },
            LinearLayout.LayoutParams(-1,dp(64)))
    }
    private fun confirm(title:String,message:String,action:()->Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消") { _,_ -> render() }
            .setPositiveButton("继续") { _,_ -> action() }.show()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray) {
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==103) { val face=pendingPreview;pendingPreview=null;if(grants.firstOrNull()==PackageManager.PERMISSION_GRANTED)preview(face) }
        render()
    }
}
