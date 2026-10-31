package io.github.yixing233.hyperduo;

import android.app.Activity;
import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Settings Qigsaw 1.85@1 ONLY. Never uses popup POLICY or a remembered model.
 * Original run always executes first. Changes only its existing ImageView drawable.
 * No native file replacement, provider write, pairing action or scope manipulation.
 */
public final class AirPodsSettingsHooks {
    private static final String TAG="HyperDuoAirPodsSettings";
    private static final String ANIMATION="plugin.settings.java.tws.MiuiHeadsetAnimation";
    private static final String ACTIVITY="plugin.settings.java.JavaActivity";
    // Preserved 2420 full-product raster: both buds and the closed case.
    // Keep its original opaque white canvas; do not substitute all-white hs_ok
    // or alter the native ImageView's frame/scaleType to invent Apple geometry.
    private static final String MODEL_2420_SETTINGS_ASSET="2420/still.png";
    private static final Set<Method> HOOKED=new HashSet<>();
    private static final ThreadLocal<Boolean> ENTER=ThreadLocal.withInitial(()->false);
    private static boolean observerInstalled;
    private static String modulePath;
    private AirPodsSettingsHooks() {}

    public static synchronized void install(XposedModule module, ClassLoader ignored) throws Exception {
        if (observerInstalled) return;
        modulePath=module.getModuleApplicationInfo().sourceDir;
        Method load=ClassLoader.class.getDeclaredMethod("loadClass",String.class,boolean.class);
        module.hook(load).intercept(chain->{
            Object result=chain.proceed();
            if (ENTER.get() || !(result instanceof Class<?>)) return result;
            Class<?> type=(Class<?>)result;
            String name=type.getName();
            if (!name.equals(ANIMATION+"$8") && !name.equals(ANIMATION+"$9")) return result;
            ENTER.set(true);
            try { installRun(module,type); }
            catch (Throwable error) { Log.w(TAG,"Image callback hook unavailable; native retained",error); }
            finally { ENTER.set(false); }
            return result;
        });
        observerInstalled=true;
        // Do not Class.forName through Qigsaw's delegate: that could load an unrequested split.
    }

    private static synchronized void installRun(XposedModule module,Class<?> type) throws Exception {
        Method run=type.getDeclaredMethod("run");
        if (HOOKED.contains(run)) return;
        if (run.getReturnType()!=void.class) return;
        Field outer=type.getDeclaredField("this$0");
        if (!outer.getType().getName().equals(ANIMATION)) return;
        outer.setAccessible(true);
        module.hook(run).intercept(chain->{
            Object result=chain.proceed(); // exactly once; do not catch native exceptions.
            try { replace(outer.get(chain.getThisObject())); }
            catch (Throwable error) { Log.w(TAG,"Static replacement failed; native drawable retained",error); }
            return result;
        });
        HOOKED.add(run); // Success-only per-Member dedup; missing methods do not duplicate earlier ones.
        Log.i(TAG,"Observed native image callback installed: "+type.getName());
    }

    private static Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static Object referent(Object owner,String name) throws Exception {
        Object value=field(owner,name);
        return value instanceof WeakReference<?> ? ((WeakReference<?>)value).get() : null;
    }
    private static boolean enabled(Context context) {
        try {
            Bundle b=AirPodsPluginConfig.read(context);
            return b!=null && b.getBoolean(AirPodsPluginConfig.ENABLED,false);
        } catch(Exception failure) { Log.w(TAG,"AirPods settings read failed",failure); return false; }
    }
    private static boolean nativeFlag(Class<?> constants,String method,String deviceId) throws Exception {
        return Boolean.TRUE.equals(constants.getDeclaredMethod(method,String.class).invoke(null,deviceId));
    }
    private static void replace(Object owner) throws Exception {
        if (owner==null || !owner.getClass().getName().equals(ANIMATION)) return;
        Object raw=referent(owner,"mContext");
        if (!(raw instanceof Activity) || !raw.getClass().getName().equals(ACTIVITY)) return;
        Activity activity=(Activity)raw;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!Boolean.TRUE.equals(field(activity,"mIsAirPods")) || !"2420".equals(field(activity,"mAirPodsType"))) return;
        // Wear class is not model identity; require BOTH local Activity model and native wear id.
        String assetPath=MODEL_2420_SETTINGS_ASSET;
        String id=(String)field(owner,"mDeviceId");
        if (!"airpodsinear".equals(id) || !enabled(activity)) return;
        Object rawRoot=referent(owner,"mRootView");
        if (!(rawRoot instanceof View)) return;
        View root=(View)rawRoot;
        ClassLoader loader=owner.getClass().getClassLoader();
        Class<?> constants=Class.forName("plugin.settings.java.HeadsetIDConstants",false,loader);
        boolean lossless=Boolean.TRUE.equals(constants.getDeclaredMethod("isSupportLossless",String.class,ContentResolver.class).invoke(null,id,activity.getContentResolver()))
            || Log.isLoggable("DEBUG_SHOW_LOSSLESSLOGO",2)
            || nativeFlag(constants,"isSupportHarman",id) || nativeFlag(constants,"isSupportDolby",id);
        Class<?> ids=Class.forName("plugin.settings.java.R$id",false,loader);
        int imageId=ids.getField(lossless ? "ticWithLossless" : "tic").getInt(null);
        View target=root.findViewById(imageId);
        if (!(target instanceof ImageView)) return;
        File apk=new File(modulePath);
        File asset=AirPodsAssetStore.materialize(apk,new File(activity.getCacheDir(),"hyperduo-airpods-settings"),assetPath);
        if (Boolean.TRUE.equals(AirPodsBitmapGuard.cached(asset))) return;
        Bitmap bitmap=BitmapFactory.decodeFile(asset.getAbsolutePath());
        boolean delivered=false;
        try {
            if (AirPodsBitmapGuard.reject(asset,bitmap)) return;
            // No frame, scaleType, visibility, padding, touch or accessibility changes.
            ((ImageView)target).setImageDrawable(new BitmapDrawable(activity.getResources(),bitmap));
            delivered=true;
            Log.i(TAG,"Settings model=2420 asset="+MODEL_2420_SETTINGS_ASSET+" target="
                    +(lossless?"ticWithLossless":"tic")+" px="+bitmap.getWidth()+"x"+bitmap.getHeight()
                    +"; native frame/scaleType retained");
        } finally {
            if(!delivered && bitmap!=null && !bitmap.isRecycled())bitmap.recycle();
        }
    }
}
