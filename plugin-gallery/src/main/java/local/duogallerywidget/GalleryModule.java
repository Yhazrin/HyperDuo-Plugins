// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** The only owner of Gallery's Personal Assistant hooks. */
public final class GalleryModule extends XposedModule {
    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!"com.miui.personalassistant".equals(param.getPackageName())) return;
        try { GalleryHalfPageHooks.install(this, param.getClassLoader()); }
        catch (Throwable error) { log(android.util.Log.WARN, "HyperDuoGallery", "Gallery picker extension unavailable: " + error); }
    }
}
