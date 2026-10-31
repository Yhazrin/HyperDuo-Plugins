package io.github.yixing233.hyperduo;
import io.github.libxposed.api.XposedModule;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.Log;
import airpods.HookPolicy;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reuses Xiaomi's existing fast-connect resource and controller lifecycle.
 * Only the current supported model's exact resource instance receives packaged artwork.
 * Native frame intervals, pairing events and dialog geometry remain unchanged.
 */
public final class AirPodsNativeHooks {
    private static XposedModule module;
    private static volatile boolean active;
    private static java.lang.ref.WeakReference<Object> sessionOwner = new java.lang.ref.WeakReference<>(null);
    private static final String TAG = "AirpodsNative";
    private static final String TARGET_PACKAGE = "com.xiaomi.bluetooth";
    /** Qigsaw fast-connect layout ids live in the peripheral split, not the host package. */
    private static final String PERIPHERAL_RESOURCE_PACKAGE = "com.xiaomi.bluetooth.peripheral";
    private static final String RESOURCE = "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectResource";
    private static final String CONTROLLER = "com.android.bluetooth.ble.app.fastconnect.airpods.MiuiAirPodsFastConnectController";
    private static final ThreadLocal<Boolean> SUCCESS_LAYOUT = new ThreadLocal<>();

    private static final HookPolicy POLICY = new HookPolicy();
    private static final AtomicBoolean RESOURCE_HOOKED = new AtomicBoolean();
    private static final AtomicBoolean CONTROLLER_HOOKED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> ENTER = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicInteger STATIC_CALLS = new AtomicInteger();
    private static final AtomicInteger FRAME_CALLS = new AtomicInteger();
    private static final AtomicInteger RELATIVE_CALLS = new AtomicInteger();
    private static final AtomicInteger START_CALLS = new AtomicInteger();

    private static volatile String modulePath = "";
    // The native loader builds one AnimationDrawable synchronously on this thread.
    // Prepare that exact requested segment before its first frame, so an absent
    // resource keeps the whole segment native instead of mixing two art sets.
    private static final ThreadLocal<MotionSegment> MOTION_SEGMENT = new ThreadLocal<>();
    /** Pending pixels belong to this one synchronous native load, not another resource/session. */
    private static final class MotionSegment {
        final Object resource;
        final Map<String, BitmapDrawable> pending;
        MotionSegment(Object resource, Map<String, BitmapDrawable> pending) {
            this.resource=resource; this.pending=pending;
        }
        BitmapDrawable publish(Object caller,String name) {
            return resource==caller ? pending.remove(name) : null;
        }
        void recycleUnpublished() {
            for(BitmapDrawable drawable:pending.values()) {
                Bitmap bitmap=drawable.getBitmap();
                if(!bitmap.isRecycled())bitmap.recycle();
            }
            pending.clear();
        }
    }

    public static void install(XposedModule owner, ClassLoader loader) {
        module=owner;
        modulePath=owner.getModuleApplicationInfo().sourceDir;
        Bridge.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (ENTER.get()) {
                    return;
                }
                Object loaded = param.getResult();
                if (!(loaded instanceof Class<?>)) {
                    return;
                }
                String name = (String) param.args[0];
                ENTER.set(Boolean.TRUE);
                try {
                    if (RESOURCE.equals(name)) {
                        hookResource((Class<?>) loaded);
                    } else if (CONTROLLER.equals(name)) {
                        hookController((Class<?>) loaded);
                    }
                } catch (Throwable error) {
                    note("hook install failed " + name);
                    Log.w(TAG, "AirPods hook", error);
                } finally {
                    ENTER.set(Boolean.FALSE);
                }
            }
        });
        // Cover classes resolved before PackageReady; no class initialization forced.
        try { hookResource(Class.forName(RESOURCE,false,loader)); } catch(Throwable unavailable) { note("Resource awaits plugin loader"); }
        try { hookController(Class.forName(CONTROLLER,false,loader)); } catch(Throwable unavailable) { note("Controller awaits plugin loader"); }
    }

    private static void hookResource(Class<?> type) {
        if (RESOURCE_HOOKED.get()) {
            return;
        }
        note("resource loader=" + type.getClassLoader());
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if ("getDrawableFromFile".equals(name) || "getDrawableRelativeName".equals(name) || "startAnimation".equals(name)) {
                note("signature " + method);
            }
        }
        // No cloud record: native initParameter assigns builtin tws ids directly,
        // with zero animation counts. That branch never enters either file loader.
        Bridge.findAndHookMethod(type, "getDrawable", int.class, new Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Context context=(Context)Bridge.getObjectField(param.thisObject,"mContext");
                String name=context.getResources().getResourceEntryName((Integer)param.args[0]);
                String relative;
                switch(name) {
                    case "fc_0000_tws_hs_find": relative="2420/frames/1000.png";break;
                    case "fc_0000_tws_hs_ok": relative="2420/hs_ok.png";break;
                    case "fc_0000_tws_box": relative="2420/box.png";break;
                    default:return; // Battery and all other native resource ids stay native.
                }
                BitmapDrawable drawable=packagedDrawable(param.thisObject,relative);
                if(drawable==null)return; // Existing blank hs_ok guard retains native artwork.
                param.setResult(drawable);
                note("builtin REPLACE entry="+name+" asset="+relative);
            }
        });
        Bridge.findAndHookMethod(type, "getDrawableFromFile", String.class, String.class, new Callback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int call = STATIC_CALLS.incrementAndGet();
                String deviceId = String.valueOf(param.args[0]);
                String fileName = String.valueOf(param.args[1]);
                param.setObjectExtra("action", "PASS");
                param.setObjectExtra("poolBefore", poolSize(param.thisObject));
                Path root = ensureTestRoot(param.thisObject);
                Optional<Path> file = POLICY.replacement(fileName, root);
                if (file.isEmpty()) {
                    return;
                }
                BitmapDrawable drawable = decode(param.thisObject, file.get().toFile());
                if (drawable == null) {
                    note("static decode-failed call=" + call + " keep-original file=" + fileName);
                    return;
                }
                param.setResult(drawable);
                param.setObjectExtra("action", "REPLACE");
                Bitmap bitmap = drawable.getBitmap();
                note("static REPLACE call=" + call
                        + " broadcast=" + POLICY.broadcast()
                        + " deviceId=" + deviceId
                        + " file=" + fileName
                        + " px=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " poolBefore=" + param.getObjectExtra("poolBefore"));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if ("REPLACE".equals(param.getObjectExtra("action"))) {
                    note("static REPLACE poolAfter=" + poolSize(param.thisObject)
                            + " keys=" + poolKeys(param.thisObject));
                    return;
                }
                note("static PASS call=" + STATIC_CALLS.get()
                        + " broadcast=" + emptyAsDash(POLICY.broadcast())
                        + " deviceId=" + param.args[0]
                        + " file=" + param.args[1]
                        + " result=" + describe(param.getResult())
                        + " pool=" + poolSize(param.thisObject)
                        + " keys=" + poolKeys(param.thisObject));
            }
        });
        Bridge.findAndHookMethod(type, "getDrawableFromFile", String.class, new Callback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra("action", "PASS");
                String fileName = String.valueOf(param.args[0]);
                MotionSegment segment = MOTION_SEGMENT.get();
                BitmapDrawable drawable = segment == null ? null : segment.publish(param.thisObject,fileName);
                if (drawable == null) return;
                int index = frameIndex(fileName);
                param.setResult(drawable);
                param.setObjectExtra("action", "REPLACE");
                if (index % 1000 <= 1) {
                    Bitmap bitmap = drawable.getBitmap();
                    note("frame REPLACE index=" + index
                            + " px=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                            + " file=" + fileName);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int call = FRAME_CALLS.incrementAndGet();
                if ("REPLACE".equals(param.getObjectExtra("action"))) {
                    return;
                }
                note("frame PASS call=" + call
                        + " name=" + param.args[0]
                        + " result=" + describe(param.getResult())
                        + " broadcast=" + emptyAsDash(POLICY.broadcast()));
            }
        });
        // Exact MiuiFastConnectResource.loadDynamicPicture signature recovered
        // from the peripheral plugin: native retains intervals, order and frames.
        Bridge.findAndHookMethod(type, "loadDynamicPicture", AnimationDrawable.class,
                String.class, int.class, int.class, int.class, new Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // The static-resource branch below has already prepared this exact
                // synchronous invocation. Reuse its pixels for the native loader.
                if (MOTION_SEGMENT.get() == null)
                    MOTION_SEGMENT.set(prepareMotionSegment(param.thisObject,
                            (String) param.args[1], (Integer) param.args[2], (Integer) param.args[3]));
            }
        });
        // Native built-in animations use loadStaticPicture -> Resources.getDrawable,
        // so they never reach getDrawableFromFile. Feed the same frame range into
        // Xiaomi's own file loader; it still owns frame order and interval.
        Bridge.findAndHookMethod(type, "loadStaticPicture", AnimationDrawable.class,
                String.class, int.class, int.class, int.class, new Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                MotionSegment segment = prepareMotionSegment(param.thisObject,
                        (String) param.args[1], (Integer) param.args[2], (Integer) param.args[3]);
                if (segment == null) return;
                MOTION_SEGMENT.set(segment);
                try {
                    param.setResult(Bridge.callMethod(param.thisObject, "loadDynamicPicture", param.args));
                } finally {
                    finishMotionSegment();
                }
            }
        });
        // Native cleanUp owns its worker/callback shutdown; release our exact session after it.
        Bridge.findAndHookMethod(type, "cleanUp", new Callback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                endResourceSession(param.thisObject);
            }
        });
        Bridge.findAndHookMethod(type, "getDrawableRelativeName", String.class, new Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Native setConnectLessLayout displays one phase-start image before
                // starting its AnimationDrawable. Its static Resources path also
                // bypasses getDrawableFromFile and must use the matching first frame.
                String token=(String)param.args[0];
                if(!"1000".equals(token) && !"2000".equals(token))return;
                BitmapDrawable drawable=packagedDrawable(param.thisObject,"2420/frames/"+token+".png");
                if(drawable==null)return;
                param.setResult(drawable);
                note("phase-start REPLACE index="+token);
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int call = RELATIVE_CALLS.incrementAndGet();
                note("relative call=" + call
                        + " token=" + param.args[0]
                        + " result=" + describe(param.getResult())
                        + " folder=" + field(param.thisObject, "mFolderName")
                        + " suffix=" + field(param.thisObject, "mPicSuffix")
                        + " dynamic=" + boolField(param.thisObject, "mDynamicLoading")
                        + " find=" + intField(param.thisObject, "mPicNum1FindHeadset")
                        + " connecting=" + intField(param.thisObject, "mPicNum2Connecting")
                        + " success=" + intField(param.thisObject, "mPicNum3ConnectSuccess")
                        + " reset=" + intField(param.thisObject, "mPicNum4ResetHeadset")
                        + " intervalFind=" + intField(param.thisObject, "mIntervalFind")
                        + " intervalConnecting=" + intField(param.thisObject, "mIntervalConnecting")
                        + " intervalSuccess=" + intField(param.thisObject, "mIntervalSuccess")
                        + " intervalReset=" + intField(param.thisObject, "mIntervalReset"));
            }
        });
        Bridge.findAndHookMethod(type, "getAnimationConnectSuccess", new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof AnimationDrawable) {
                    ((AnimationDrawable) param.getResult()).setOneShot(false);
                    note("success animation loops while the buds stay in the case");
                }
            }
        });
        Bridge.findAndHookMethod(type, "startAnimation", android.widget.ImageView.class, int.class, new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int call = START_CALLS.incrementAndGet();
                Object view = param.args[0];
                int state = (Integer) param.args[1];
                note("start call=" + call
                        + " state=" + state
                        + " label=" + animationLabel(state)
                        + " view=" + viewId(view)
                        + " result=" + param.getResult()
                        + " dialog=" + intField(param.thisObject, "mDialogState"));
            }
        });
        RESOURCE_HOOKED.set(true);
    }

    private static void hookController(Class<?> type) {
        if (CONTROLLER_HOOKED.get()) {
            return;
        }
        note("controller loader=" + type.getClassLoader());
        Bridge.findAndHookMethod(type, "initParameter", new Callback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                beginSession(param.thisObject);
            }
        });
        Bridge.findAndHookMethod(type, "onActivityDestroy", new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                endSession(param.thisObject);
            }
        });
        Bridge.findAndHookMethod(type, "setConnectSuccessLayout", View.class, new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(SUCCESS_LAYOUT.get())) {
                    return;
                }
                if (Boolean.TRUE.equals(param.getResult()) || budsInCase(param.thisObject)) {
                    if (Boolean.TRUE.equals(param.getResult())) {
                        showTakenOutStill(param.thisObject, (View) param.args[0]);
                    }
                    return;
                }
                Object resource = Bridge.getObjectField(param.thisObject, "mLocalResource");
                Bridge.callMethod(resource, "setAlphaAnimationState", 2, null);
                SUCCESS_LAYOUT.set(Boolean.TRUE);
                try {
                    Bridge.callMethod(param.thisObject, "setConnectSuccessLayout", param.args[0]);
                    Bridge.callMethod(param.thisObject, "setConnectSuccessLayoutDetail", param.args[0]);
                    showTakenOutStill(param.thisObject, (View) param.args[0]);
                    note("buds are out, hold the side-by-side still");
                } finally {
                    SUCCESS_LAYOUT.set(Boolean.FALSE);
                }
            }
        });
        Bridge.findAndHookMethod(type, "setConnectSuccessLayoutDetail", View.class, new Callback() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                paintBatteryRings(param.thisObject, (View) param.args[0]);
            }
        });
        try {
            Class<?> callback = type.getClassLoader().loadClass(CONTROLLER + "$3");
            Bridge.findAndHookMethod(callback, "onAnimationRunFinish", int.class, new Callback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Integer.valueOf(3).equals(param.args[0])) {
                        return;
                    }
                    Object controller = Bridge.getObjectField(param.thisObject, "this$0");
                    if (!budsInCase(controller)) {
                        return;
                    }
                    param.setResult(null);
                    note("buds are still in the case, keep the rotation");
                }
            });
        } catch (ClassNotFoundException error) {
            note("rotation callback missing");
        }
        CONTROLLER_HOOKED.set(true);
    }

    private static boolean budsInCase(Object controller) {
        try {
            Object device = Bridge.getObjectField(controller, "mCurrentDevice");
            if (device == null) {
                return false;
            }
            String address = (String) Bridge.callMethod(device, "getAddress");
            Object state = Bridge.callMethod(controller, "getAirPodsState", address);
            if (!(state instanceof String[]) || ((String[]) state).length < 7) {
                return false;
            }
            String[] values = (String[]) state;
            return "true".equals(values[5]) && "true".equals(values[6]);
        } catch (Throwable error) {
            note("in-case state unavailable");
            return false;
        }
    }

    private static void showTakenOutStill(Object controller, View root) {
        if (!"2420".equals(POLICY.broadcast())) return;
        try {
            Object context = Bridge.getObjectField(controller, "mActivityContext");
            Object resource = Bridge.getObjectField(controller, "mLocalResource");
            if (!(context instanceof Context) || resource == null) {
                return;
            }
            Context activity = (Context) context;
            Path assets = ensureTestRoot(resource);
            if (assets == null) {
                return;
            }
            File still = assets.resolve("2420/still.png").toFile();
            BitmapDrawable drawable = decode(resource, still);
            if (drawable == null) {
                return;
            }
            ImageView animation = image(activity, root, "imageViewFindHeadset");
            if (animation == null) return; // Never remove native art without a replacement host.
            show(activity, root, "imageFindHeadsetLayout");
            animation.setVisibility(View.VISIBLE);
            animation.setImageDrawable(drawable);
            hide(activity, root, "imageViewHeadset");
            hide(activity, root, "imageViewBox");
        } catch (Throwable error) {
            note("taken-out still skipped");
        }
    }

    private static ImageView image(Context context, View root, String name) {
        View view = find(context, root, name);
        return view instanceof ImageView ? (ImageView) view : null;
    }

    private static void show(Context context, View root, String name) {
        View view = find(context, root, name);
        if (view != null) {
            view.setVisibility(View.VISIBLE);
        }
    }

    private static void hide(Context context, View root, String name) {
        View view = find(context, root, name);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    private static View find(Context context, View root, String name) {
        int id = resolveViewId(context, name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static int resolveViewId(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "id", PERIPHERAL_RESOURCE_PACKAGE);
        if (id != 0) {
            return id;
        }
        id = context.getResources().getIdentifier(name, "id", TARGET_PACKAGE);
        if (id != 0) {
            return id;
        }
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    private static void finishMotionSegment() {
        MotionSegment segment=MOTION_SEGMENT.get();
        MOTION_SEGMENT.remove();
        if(segment!=null)segment.recycleUnpublished();
    }

    private static MotionSegment prepareMotionSegment(Object resource,
            String pattern, int start, int end) {
        if (!"1%03d".equals(pattern) && !"2%03d".equals(pattern)
                && !"3%03d".equals(pattern) && !"4%03d".equals(pattern)) return null;
        Path root = ensureTestRoot(resource);
        if (root == null) return null;
        Map<String, BitmapDrawable> frames = new java.util.HashMap<>();
        boolean complete = false;
        try {
            String prefix = (String) Bridge.callMethod(resource, "getPicPrefix");
            String suffix = (String) Bridge.getObjectField(resource, "mPicSuffix");
            int phase = pattern.charAt(0) - '0';
            for (int i = start; i < end; i++) {
                int index = phase * 1000 + i;
                File file = AirPodsAssetStore.materialize(new File(modulePath), root.toFile(),
                        "2420/frames/" + index + ".png");
                BitmapDrawable drawable = decode(resource, file);
                if (drawable == null) return null;
                frames.put(prefix + String.format(java.util.Locale.US, pattern, i) + suffix, drawable);
            }
            complete = true;
            return new MotionSegment(resource,frames);
        } catch (Exception unavailable) {
            note("motion segment " + pattern + " has no complete replacement; keep native");
            return null;
        } finally {
            if (!complete) for (BitmapDrawable drawable : frames.values()) {
                Bitmap bitmap = drawable.getBitmap();
                if (!bitmap.isRecycled()) bitmap.recycle();
            }
        }
    }

    private static void paintBatteryRings(Object controller, View root) {
        try {
            Object context = Bridge.getObjectField(controller, "mActivityContext");
            if (!(context instanceof Context)) {
                return;
            }
            Context activity = (Context) context;
            String[] state = stateOf(controller);
            if (state == null || state.length < 8 || !"2420".equals(POLICY.broadcast())) return;
            hide(activity, root, "imageViewHeadsetLCharge");
            hide(activity, root, "imageViewHeadsetRCharge");
            hide(activity, root, "imageViewBoxCharge");
            ring(activity, root, "imageViewHeadsetLBattery", percent(state, 0), state != null && "true".equals(state[5]));
            ring(activity, root, "imageViewHeadsetRBattery", percent(state, 1), state != null && "true".equals(state[6]));
            ring(activity, root, "imageViewBoxBattery", percent(state, 2), state != null && "true".equals(state[7]));
            label(activity, root, "textViewHeadsetL", "L");
            label(activity, root, "textViewHeadsetR", "R");
            note("battery rings applied");
        } catch (Throwable error) {
            note("battery ring skipped");
        }
    }

    private static String[] stateOf(Object controller) {
        Object device = Bridge.getObjectField(controller, "mCurrentDevice");
        if (device == null) {
            return null;
        }
        String address = (String) Bridge.callMethod(device, "getAddress");
        Object state = Bridge.callMethod(controller, "getAirPodsState", address);
        return state instanceof String[] ? (String[]) state : null;
    }

    private static int percent(String[] state, int index) {
        if (state == null || index >= state.length) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(state[index])));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static void ring(Context context, View root, String name, int percent, boolean charging) {
        ImageView view = image(context, root, name);
        if (view == null) {
            note("battery ring missing " + name);
            return;
        }
        if(view.isLaidOut()) {
            drawRing(context,view,percent,charging);
        } else {
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View changed,int left,int top,int right,int bottom,
                        int oldLeft,int oldTop,int oldRight,int oldBottom) {
                    changed.removeOnLayoutChangeListener(this);
                    drawRing(context,view,percent,charging);
                }
            });
        }
    }

    private static void drawRing(Context context,ImageView view,int percent,boolean charging) {
        int width=view.getWidth(),height=view.getHeight();
        int size=Math.min(width,height);
        if(size<=0)return;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(context, 3));
        float inset = paint.getStrokeWidth();
        float left=(width-size)/2f,top=(height-size)/2f;
        paint.setColor(0xFFE5E5EA);
        canvas.drawArc(left+inset, top+inset, left+size-inset, top+size-inset, -90f, 360f, false, paint);
        paint.setColor(0xFF34C759);
        canvas.drawArc(left+inset, top+inset, left+size-inset, top+size-inset, -90f, percent * 3.6f, false, paint);
        if (charging) {
            paint.setStyle(Paint.Style.FILL);
            float midX = width / 2f,midY=height / 2f;
            float bolt = size / 5.2f;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(midX + bolt * 0.15f, midY - bolt);
            path.lineTo(midX - bolt * 0.55f, midY + bolt * 0.1f);
            path.lineTo(midX - bolt * 0.05f, midY + bolt * 0.1f);
            path.lineTo(midX - bolt * 0.15f, midY + bolt);
            path.lineTo(midX + bolt * 0.55f, midY - bolt * 0.1f);
            path.lineTo(midX + bolt * 0.05f, midY - bolt * 0.1f);
            path.close();
            canvas.drawPath(path, paint);
        }
        view.setImageDrawable(new BitmapDrawable(context.getResources(), bitmap));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void label(Context context, View root, String name, String text) {
        int id = resolveViewId(context, name);
        View view = id == 0 ? null : root.findViewById(id);
        if (view instanceof TextView) {
            ((TextView) view).setText(text);
        }
    }

    private static void bindSession(Object controller) {
        String address = "";
        String type = "";
        try {
            Object device = Bridge.getObjectField(controller, "mCurrentDevice");
            if (device != null) {
                Object value = device.getClass().getMethod("getAddress").invoke(device);
                address = value == null ? "" : String.valueOf(value);
            }
            Object activity = Bridge.getObjectField(controller, "mActivityContext");
            if (activity instanceof Context && !address.isEmpty()) {
                SharedPreferences prefs = ((Context) activity).getSharedPreferences("AirpodsModel", Context.MODE_PRIVATE);
                type = prefs.getString(address + "-type", "");
            }
            POLICY.bind(address, type);
        } catch (Throwable error) {
            POLICY.clear();
            note("session bind failed");
            Log.w(TAG, "AirPods hook", error);
            return;
        }
        note("session bind tail=" + tail(address)
                + " type=" + emptyAsDash(POLICY.broadcast())
                + " process=" + processName());
    }

    private static synchronized Path ensureTestRoot(Object resource) {
        try {
            Object value = Bridge.getObjectField(resource, "mContext");
            if (!(value instanceof Context) || modulePath.isEmpty()) return null;
            File dir = new File(((Context) value).getCacheDir(), "airpods-native");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            // A stamp is not proof that Android has retained individual cache files.
            for (String name : new String[]{"hs_ok.png", "hs_disc_l.png", "hs_disc_r.png", "box.png", "still.png"})
                AirPodsAssetStore.materialize(new File(modulePath), dir, "2420/" + name);
            return dir.toPath();
        } catch (Exception error) {
            note("asset root unavailable; keep native resources");
            return null;
        }
    }

    private static BitmapDrawable packagedDrawable(Object resource,String relative) throws Exception {
        Path root=ensureTestRoot(resource);
        if(root==null)return null;
        File file=AirPodsAssetStore.materialize(new File(modulePath),root.toFile(),relative);
        return decode(resource,file);
    }

    private static BitmapDrawable decode(Object resource, File file) {
        Bitmap bitmap = null;
        boolean delivered = false;
        try {
            // A known all-white hs_ok is rejected without decoding it again.
            if (Boolean.TRUE.equals(AirPodsBitmapGuard.cached(file))) return null;
            Object context = Bridge.getObjectField(resource, "mContext");
            if (!(context instanceof Context)) return null;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (AirPodsBitmapGuard.reject(file, bitmap)) {
                note("blank or unreadable asset; keep native drawable");
                return null;
            }
            BitmapDrawable drawable = new BitmapDrawable(((Context) context).getResources(), bitmap);
            delivered = true;
            return drawable;
        } catch (Throwable error) {
            note("decode failed");
            Log.w(TAG, "AirPods hook", error);
            return null;
        } finally {
            // Only unpublished pixels are ours to recycle. Delivered frames stay
            // owned by the original AnimationDrawable/ImageView lifecycle.
            if (!delivered && bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static int poolSize(Object resource) {
        try {
            Object pool = Bridge.getObjectField(resource, "bitmapPool");
            if (pool == null) {
                return -1;
            }
            Object size = pool.getClass().getMethod("size").invoke(pool);
            return size instanceof Integer ? (Integer) size : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    private static String poolKeys(Object resource) {
        try {
            Object pool = Bridge.getObjectField(resource, "bitmapPool");
            if (pool == null) {
                return "-";
            }
            Object snap = pool.getClass().getMethod("snapshot").invoke(pool);
            if (!(snap instanceof Map)) {
                return "-";
            }
            return ((Map<?, ?>) snap).keySet().toString();
        } catch (Throwable error) {
            return "-";
        }
    }

    private static String field(Object target, String name) {
        try {
            Object value = Bridge.getObjectField(target, name);
            return value == null ? "-" : String.valueOf(value);
        } catch (Throwable error) {
            return "-";
        }
    }

    private static int intField(Object target, String name) {
        try {
            return Bridge.getIntField(target, name);
        } catch (Throwable error) {
            return -1;
        }
    }

    private static boolean boolField(Object target, String name) {
        try {
            return Bridge.getBooleanField(target, name);
        } catch (Throwable error) {
            return false;
        }
    }

    private static String describe(Object drawable) {
        if (drawable == null) {
            return "null";
        }
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) {
                return drawable.getClass().getSimpleName() + " " + bitmap.getWidth() + "x" + bitmap.getHeight();
            }
        }
        return drawable.getClass().getName();
    }

    private static String viewId(Object view) {
        try {
            if (view == null) {
                return "null";
            }
            int id = (Integer) view.getClass().getMethod("getId").invoke(view);
            return Integer.toHexString(id);
        } catch (Throwable error) {
            return "?";
        }
    }

    private static int frameIndex(String fileName) {
        if (fileName == null) {
            return -1;
        }
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        String prefix = "fc_airpodsinear_";
        String suffix = ".webp";
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
            return -1;
        }
        String digits = name.substring(prefix.length(), name.length() - suffix.length());
        if (digits.isEmpty() || digits.length() > 4) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    private static String animationLabel(int state) {
        if (state == 1) {
            return "find";
        }
        if (state == 2) {
            return "connecting";
        }
        if (state == 3) {
            return "success";
        }
        if (state == 4) {
            return "reset";
        }
        return "unknown";
    }

    private static String tail(String address) {
        if (address == null || address.length() < 5) {
            return "-";
        }
        return address.substring(address.length() - 5);
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String processName() {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/cmdline"));
            return new String(raw).replace('\0', ' ').trim();
        } catch (Exception error) {
            return "?";
        }
    }

    private static void note(String message) {
        Log.i(TAG, message);
    }

    private static synchronized void beginSession(Object controller) {
        // Clear before config/model lookup: failure or disable cannot reuse an old 2420.
        active=false; POLICY.clear(); sessionOwner.clear();
        refreshSettings(controller);
        if (active) bindSession(controller);
        if (active && "2420".equals(POLICY.broadcast()))
            sessionOwner=new java.lang.ref.WeakReference<>(controller);
        else { active=false; POLICY.clear(); }
    }
    private static synchronized void endSession(Object controller) {
        if (sessionOwner.get()!=controller) return;
        active=false; POLICY.clear(); sessionOwner.clear();
        note("session clear");
    }
    private static synchronized void endResourceSession(Object resource) {
        Object controller=sessionOwner.get();
        if(controller!=null && Bridge.getObjectField(controller,"mLocalResource")==resource)
            endSession(controller);
    }
    private static void refreshSettings(Object controller) {
        active=false;
        try {
            Object value=Bridge.getObjectField(controller,"mActivityContext");
            if (!(value instanceof Context)) return;
            Context context=(Context)value;
            android.os.Bundle settings=AirPodsPluginConfig.read(context);
            active=settings!=null && settings.getBoolean(AirPodsPluginConfig.ENABLED,false);
        } catch(Exception failure) {Log.w(TAG,"Config unavailable; keep native",failure);}
    }
    /** Latest enabled controller owns only its exact resource and callback instances.
     * No global broadcast value grants a different live/stale session permission. */
    private static synchronized boolean ownsInvocation(Class<?> declaredType,Object receiver) {
        if (!active || !"2420".equals(POLICY.broadcast()) || receiver==null) return false;
        Object controller=sessionOwner.get();
        if (controller==null) return false;
        String type=declaredType.getName();
        if (type.equals(CONTROLLER)) return receiver==controller;
        try {
            if (type.equals(RESOURCE))
                return Bridge.getObjectField(controller,"mLocalResource")==receiver;
            if (type.equals(CONTROLLER+"$3"))
                return Bridge.getObjectField(receiver,"this$0")==controller;
        } catch (Throwable unavailable) { return false; }
        return false;
    }
    private static class Callback {
        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
        protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
        static class MethodHookParam {
            Object thisObject,result;Object[] args;boolean replace;java.util.HashMap<String,Object> extras=new java.util.HashMap<>();
            Object getResult(){return result;}void setResult(Object v){result=v;replace=true;}
            void setObjectExtra(String k,Object v){extras.put(k,v);}Object getObjectExtra(String k){return extras.get(k);}
        }
    }
    private static class Bridge {
        private static final java.util.Set<Method> installed = new java.util.HashSet<>();
        static void findAndHookMethod(Class<?> type,String name,Object... signature) {
            try {
                Callback hook=(Callback)signature[signature.length-1];Class<?>[] types=new Class<?>[signature.length-1];
                for(int i=0;i<types.length;i++)types[i]=(Class<?>)signature[i];
                Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);
                synchronized (installed) {
                if (installed.contains(method)) return;
                module.hook(method).intercept(chain->{
                    boolean lifecycle=name.equals("loadClass") || name.equals("initParameter") || name.equals("onActivityDestroy");
                    Object receiver=chain.getThisObject();
                    if (!lifecycle && !ownsInvocation(type,receiver)) return chain.proceed();
                    Callback.MethodHookParam p=new Callback.MethodHookParam();p.thisObject=receiver;p.args=chain.getArgs().toArray();
                    try{hook.beforeHookedMethod(p);}catch(Throwable failure){Log.w(TAG,"Before hook retained original",failure);return chain.proceed();}
                    // A before callback may re-enter native code and replace the active session.
                    if (!lifecycle && !ownsInvocation(type,receiver)) {
                        if(name.equals("loadDynamicPicture")) finishMotionSegment();
                        return chain.proceed();
                    }
                    try {
                        if(!p.replace)p.result=chain.proceed();
                    } finally {
                        if(name.equals("loadDynamicPicture")) finishMotionSegment();
                    }
                    // Do not let an old native completion mutate the newly active session.
                    if (!lifecycle && !ownsInvocation(type,receiver)) return p.result;
                    try{hook.afterHookedMethod(p);}catch(Throwable failure){Log.w(TAG,"After hook failed",failure);}
                    return p.result;
                });
                installed.add(method); // Only after this exact member installed successfully.
                }
            } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        }
        static Object getObjectField(Object value,String name){
            for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException missing){}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            throw new IllegalStateException("Missing field "+name);
        }
        static int getIntField(Object value,String name){return ((Number)getObjectField(value,name)).intValue();}
        static boolean getBooleanField(Object value,String name){return (Boolean)getObjectField(value,name);}
        static Object callMethod(Object value,String name,Object... args){
            for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods()){
                if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
                Class<?>[] ts=m.getParameterTypes();boolean match=true;
                for(int i=0;i<ts.length;i++){Class<?> t=ts[i];if(t==int.class)t=Integer.class;if(t==boolean.class)t=Boolean.class;if(args[i]!=null&&!t.isInstance(args[i]))match=false;}
                if(!match)continue;
                try{m.setAccessible(true);return m.invoke(value,args);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }
            throw new IllegalStateException("Missing method "+name);
        }
    }
}
