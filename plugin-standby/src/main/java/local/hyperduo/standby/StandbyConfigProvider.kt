package local.hyperduo.standby

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.content.Intent
import io.github.hyperduo.plugin.PluginContract
import java.io.File

/** The host explicitly copies its legacy data here; the source remains untouched. */
class StandbyConfigProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app=requireNotNull(context)
        app.enforceCallingOrSelfPermission(PluginContract.PERMISSION_MANAGE_PLUGINS,"StandBy configuration requires the signed host")
        return when(method) {
            PluginContract.METHOD_IMPORT_LEGACY -> {
                val input=requireNotNull(extras)
                val prefs=input.getBundle(PluginContract.EXTRA_PREFERENCES)
                if(prefs!=null)PluginContract.importPreferences(app,prefs)
                input.getBundle(PluginContract.EXTRA_CONFIG)?.let { config ->
                    PluginContract.importPreferences(app,Bundle().apply { putBundle("standby_integration",config) })
                }
                var filesCopied=0
                input.getBundle(PluginContract.EXTRA_FILES)?.let { files ->
                    for(name in files.keySet()) {
                        require(name in setOf("photo-configurations-v41.json","world-cities-v1.json","camera-calibration-v5.json") ||
                            name.matches(Regex("camera-calibration-v5-[A-Za-z0-9_.-]+\\.json"))) { "Not a StandBy configuration file: $name" }
                        val target=File(app.filesDir,name)
                        when(val data=files.get(name)) {
                            is ByteArray -> target.writeBytes(data)
                            is ParcelFileDescriptor -> ParcelFileDescriptor.AutoCloseInputStream(data).use { source -> target.outputStream().use { source.copyTo(it) } }
                            else -> error("Expected bytes or a file descriptor for $name")
                        }
                        filesCopied++
                    }
                }
                val missing=arrayListOf<String>()
                for(value in input.getStringArrayList(PluginContract.EXTRA_PHOTO_URIS).orEmpty()) {
                    try { app.contentResolver.takePersistableUriPermission(Uri.parse(value),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch(_:SecurityException) { missing.add(value) }
                }
                val receipt=app.getSharedPreferences("plugin_migration",Context.MODE_PRIVATE)
                receipt.edit().putLong("imported_at",System.currentTimeMillis()).putStringSet("photo_access_required",missing.toSet()).apply()
                Bundle().apply { putBoolean("imported",true);putInt("filesCopied",filesCopied);putStringArrayList("photoAccessRequired",missing) }
            }
            "getConfig" -> Bundle().apply {
                putBundle(PluginContract.EXTRA_PREFERENCES,PluginContract.exportPreferences(app,*PREFERENCES))
                putLong("importedAt",app.getSharedPreferences("plugin_migration",Context.MODE_PRIVATE).getLong("imported_at",0))
            }
            else -> throw IllegalArgumentException("Unknown StandBy method: $method")
        }
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=0
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=0
    companion object {
        val PREFERENCES=arrayOf("standby","standby_trigger","standby_integration","widget_stacks_v20",
            "camera_alignment_v5","navigation_profile_v33","original_motion_v22","xiaomi_curated_photo_source","bluetooth_battery","digital_weather")
    }
}
