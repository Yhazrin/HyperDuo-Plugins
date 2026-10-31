package io.github.yixing233.hyperduo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import io.github.hyperduo.plugin.PluginContract;

/** Host migration is signature restricted; native readers get only the enabled flag. */
public final class AirPodsPluginProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method,String arg,Bundle extras) {
        Context context=getContext();
        if (AirPodsPluginConfig.METHOD_READ.equals(method)) {
            int uid=Binder.getCallingUid();
            boolean allowed=uid==Process.myUid();
            String[] packages=context.getPackageManager().getPackagesForUid(uid);
            if(packages!=null)for(String pkg:packages)
                allowed|="com.xiaomi.bluetooth".equals(pkg) || "com.android.settings".equals(pkg);
            if(!allowed)throw new SecurityException("AirPods hosts only");
            return AirPodsPluginConfig.snapshot(context);
        }
        if (PluginContract.METHOD_IMPORT_CONFIG.equals(method)) {
            enforceHost(context);
            Bundle config=extras==null?null:extras.getBundle(PluginContract.EXTRA_CONFIG);
            if(config==null || !config.containsKey(AirPodsPluginConfig.ENABLED))
                throw new IllegalArgumentException("Missing AirPods enabled preference");
            SharedPreferences prefs=AirPodsPluginConfig.preferences(context);
            boolean imported=!prefs.contains(AirPodsPluginConfig.ENABLED);
            if(imported && !prefs.edit().putBoolean(AirPodsPluginConfig.ENABLED,
                    config.getBoolean(AirPodsPluginConfig.ENABLED,false)).commit())
                throw new IllegalStateException("AirPods preferences were not saved");
            Bundle result=AirPodsPluginConfig.snapshot(context);
            result.putBoolean("ok",true);
            result.putBoolean("imported",imported);
            return result;
        }
        throw new IllegalArgumentException("Unknown AirPods provider method: "+method);
    }

    private void enforceHost(Context context) {
        int uid=Binder.getCallingUid();
        if(uid==Process.myUid())return;
        context.enforceCallingPermission(PluginContract.PERMISSION_MANAGE_PLUGINS,
                "Signed HyperDuo host required");
        try {
            if(context.getPackageManager().getPackageUid(PluginContract.HOST_PACKAGE,0)!=uid)
                throw new SecurityException("HyperDuo host only");
        } catch(PackageManager.NameNotFoundException missing) {
            throw new SecurityException("HyperDuo host not installed",missing);
        }
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        throw new UnsupportedOperationException("Use the AirPods config call interface");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
