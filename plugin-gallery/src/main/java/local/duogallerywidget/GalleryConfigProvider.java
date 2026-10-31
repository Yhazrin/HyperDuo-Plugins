// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Assistant reads two booleans; only this UID or signed host can change them. */
public final class GalleryConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle values) {
        int uid = Binder.getCallingUid();
        boolean own = uid == android.os.Process.myUid();
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean assistant = false, host = false;
        if (packages != null) for (String pkg : packages) {
            assistant |= "com.miui.personalassistant".equals(pkg);
            host |= GalleryConfig.HOST.equals(pkg);
        }
        boolean writer = own || (host && pm.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH);
        if ("config".equals(method)) {
            if (!writer && !assistant) throw new SecurityException("Gallery reader only");
            return GalleryConfig.snapshot(getContext());
        }
        if (!writer) throw new SecurityException("Signed HyperDuo host only");
        if ("importLegacy".equals(method)) GalleryConfig.migrate(getContext(), values == null ? null : values.getBundle("config"));
        else if ("set".equals(method)) {
            var edit = GalleryConfig.prefs(getContext()).edit();
            for (String key : GalleryConfig.KEYS)
                if (values != null && values.containsKey(key)) edit.putBoolean(key, values.getBoolean(key));
            edit.apply();
        } else throw new IllegalArgumentException("Unknown Gallery method: " + method);
        return GalleryConfig.snapshot(getContext());
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
