package io.github.yixing233.hyperduo;

import io.github.libxposed.api.XposedModule;

/** Independent AirPods module: no SystemUI, launcher or host-app hook entry. */
public final class AirPodsPluginModule extends XposedModule {
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        log(4,"HyperDuoAirPods","Module loaded: "+param.getProcessName());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg=param.getPackageName();
        try {
            if ("com.xiaomi.bluetooth".equals(pkg)) {
                AirPodsNativeHooks.install(this,param.getClassLoader());
            } else if ("com.android.settings".equals(pkg)) {
                AirPodsSettingsHooks.install(this,param.getClassLoader());
            }
        } catch(Throwable failure) {
            log(6,"HyperDuoAirPods","Hook install failed for "+pkg+": "+failure);
        }
    }
}
