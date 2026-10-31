package io.github.yixing233.hyperduo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Plugin-owned preferences and the exact read-only interface used by its two hosts. */
final class AirPodsPluginConfig {
    static final String PACKAGE="local.hyperduo.airpods";
    static final String NAME="airpods_settings";
    static final String ENABLED="airpods_enabled";
    static final String METHOD_READ="airpods_config";
    static final Uri URI=Uri.parse("content://"+PACKAGE+".config");
    private AirPodsPluginConfig() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(NAME,Context.MODE_PRIVATE);
    }

    static Bundle snapshot(Context context) {
        Bundle result=new Bundle();
        result.putBoolean(ENABLED,preferences(context).getBoolean(ENABLED,false));
        return result;
    }

    static Bundle read(Context context) {
        long identity=Binder.clearCallingIdentity();
        try { return context.getContentResolver().call(URI,METHOD_READ,null,null); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
}
