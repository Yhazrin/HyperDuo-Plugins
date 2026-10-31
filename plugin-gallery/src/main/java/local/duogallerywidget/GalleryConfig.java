// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

final class GalleryConfig {
    static final String HOST = "local.duodesktop";
    static final String SPAN = "gallery_4x6_enabled";
    static final String EDIT = "gallery_native_edit_enabled";
    static final String[] KEYS = {SPAN, EDIT};
    static final Uri URI = Uri.parse("content://local.hyperduo.gallery.config");
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }
    static Bundle snapshot(Context context) {
        SharedPreferences prefs = prefs(context);
        Bundle result = new Bundle();
        for (String key : KEYS) result.putBoolean(key, prefs.getBoolean(key, true));
        return result;
    }
    /** Copy settings once; a user's explicit plugin setting always wins. */
    static void migrate(Context context, Bundle values) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean("host_settings_imported", false)) return;
        if (values == null || !values.containsKey(SPAN) || !values.containsKey(EDIT)) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : KEYS)
            if (!prefs.contains(key)) edit.putBoolean(key, values.getBoolean(key));
        edit.putBoolean("host_settings_imported", true).apply();
    }
    static Bundle read(Context context) {
        long identity = Binder.clearCallingIdentity();
        try { return context.getContentResolver().call(URI, "config", null, null); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
}
