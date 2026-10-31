// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;

import android.app.Application;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Additive, exact-ROM Gallery MAML picker extension. No global span mutation.
 *  User's "6x4 half page" = launcher spanX4/spanY6, not six columns.
 *  Resource staging and scope approval are separate from installing these hooks.
 */
public final class GalleryHalfPageHooks {
 private static final String PREFIX = "duo-halfpage-4x6:";
 private static final String P = "com.miui.personalassistant.picker.business.detail.";
 // Kotlin DTO hashCode/equals may dereference null server fields. Cache by weak identity only.
 private static final ArrayList<Expansion> expansions = new ArrayList<>();
 private static final WeakHashMap<Object, int[]> previewOriginalSpan = new WeakHashMap<>();
 private static WeakReference<Object> activeFragment = new WeakReference<>(null);
 private static XposedModule module;
 private static boolean installed;
 private static Method spanSize;
 private static Bundle galleryConfig;
 private static long configReadAt;
 private static final WeakHashMap<Intent,Rect[]> editorBounds=new WeakHashMap<>();
 private GalleryHalfPageHooks() {}

 public static synchronized void install(XposedModule xposed, ClassLoader loader) throws Exception {
  if (installed) return;
  Class<?> wrapper = Class.forName(P + "bean.PickerDetailResponseWrapper", false, loader);
  Class<?> response = Class.forName(P + "bean.PickerDetailResponse", false, loader);
  Class<?> fragment = Class.forName(P + "PickerDetailFragment", false, loader);
  Class<?> container = Class.forName(P + "widget.PickerItemContainer", false, loader);
  Class<?> util = Class.forName(P + "utils.PickerDetailUtil", false, loader);
  Class<?> sizeProvider = Class.forName("com.miui.personalassistant.picker.util.f", false, loader);
  // Resolve the complete small contract before installing any hook.
  Method list = declared(wrapper, "getPickerDetailResponses");
  Method create = declared(fragment, "onCreate", Bundle.class);
  Method destroy = declared(fragment, "onDestroy");
  Method add = declared(util, "getAddTargetItemInfo", response);
  Method show = named(container, "showMaMlView", 4);
  Method previewImage = named(container, "showPreviewImage", 6);
  Class<?> nativeLookup=Class.forName("com.miui.maml.widget.edit.MamlutilKt",false,loader);
  Method localMaml=named(nativeLookup,"findLocalMamlInfo",7);
  Class<?> editIntent=Class.forName(P+"widget.edititems.EditIntentConfigItemView",false,loader);
  Method prepareEdit=declared(editIntent,"prepareIntent");
  Method launchEdit=declared(Activity.class,"startActivityForResult",Intent.class,int.class,Bundle.class);
  Method span = declared(container, "currentSpanType");
  Method size = declared(container, "getSizeForSpan", int.class);
  Method recycled = declared(container, "onRecycled");
  spanSize = declared(sizeProvider, "a", Context.class, int.class, boolean.class, boolean.class, int.class);
  named(response, "copy", 29);
  Class<?> maml = Class.forName(P + "bean.PickerDetailResponseMaml", false, loader);
  named(maml, "copy", 22);
  // A layout-only image style is not a new Android appwidget provider.
  field(container, "mSpanX"); field(container, "mSpanY");
  Class<?> itemInfo = Class.forName("com.miui.personalassistant.widget.entity.ItemInfo",false,loader);
  for(String name:new String[]{"spanX","spanY","configSpanX","configSpanY"})
   if(field(itemInfo,name).getType()!=int.class) throw new IllegalStateException("Span contract changed: "+name);
  ArrayList<HookHandle> handles = new ArrayList<>(); module = xposed;
  try {
   handles.add(xposed.hook(create).setId("duo_gallery_source").intercept(chain -> {
    Object result = chain.proceed(); activeFragment = new WeakReference<>(chain.getThisObject()); return result;
   }));
   handles.add(xposed.hook(destroy).setId("duo_gallery_source_end").intercept(chain -> {
    try { return chain.proceed(); }
    finally { if (activeFragment.get() == chain.getThisObject()) activeFragment.clear(); }
   }));
   handles.add(xposed.hook(list).setId("duo_gallery_group_variants").intercept(chain -> {
    Object result = chain.proceed();
    try {
     if (!(result instanceof List<?>) || !fromHome() || !galleryEnabled(context(),false) || !PayloadIndex.ready(context())) return result;
     return expand(chain.getThisObject(), (List<?>) result, context());
    } catch (Throwable error) { log("picker kept original list", error); return result; }
   }));
   handles.add(xposed.hook(add).setId("duo_gallery_add_half_page").intercept(chain -> {
    boolean selected = isHalfPage(chain.getArg(0));
    if (selected && (!fromHome() || !galleryEnabled(context(),false) || !PayloadIndex.ready(context()))) return null;
    Object result = chain.proceed();
    if (!selected || result == null) return result;
    try {
     // Native MaMlItemInfo is forwarded through the unchanged native add/drag route.
     field(result.getClass(), "configSpanX").setInt(result, 4);
     field(result.getClass(), "configSpanY").setInt(result, 6);
     field(result.getClass(), "spanX").setInt(result, 4);
     field(result.getClass(), "spanY").setInt(result, 6);
     log("native add item configSpan=4x6 displaySpan=4x6", null);
     return result;
    } catch (Throwable error) { log("half-page item refused, no placement performed", error); return null; }
   }));
   handles.add(xposed.hook(show).setId("duo_gallery_live_preview").intercept(chain -> {
    Object view = chain.getThisObject();
    synchronized (previewOriginalSpan) {
     int[] original = previewOriginalSpan.remove(view);
     if (original != null) { field(container, "mSpanX").setInt(view, original[0]); field(container, "mSpanY").setInt(view, original[1]); }
     if (isHalfPage(chain.getArg(0))) {
      previewOriginalSpan.put(view, new int[]{field(container,"mSpanX").getInt(view), field(container,"mSpanY").getInt(view)});
      field(container,"mSpanX").setInt(view,4); field(container,"mSpanY").setInt(view,6);
     }
    }
    return chain.proceed();
   }));
   handles.add(xposed.hook(previewImage).setId("duo_gallery_original_preview_crop").intercept(chain -> {
    Object result=chain.proceed();
    if(isHalfPage(chain.getArg(4))) {
     Object image=field(container,"mImageView").get(chain.getThisObject());
     if(image instanceof ImageView) ((ImageView)image).setScaleType(ImageView.ScaleType.CENTER_CROP);
    }
    return result;
   }));
   handles.add(xposed.hook(localMaml).setId("duo_gallery_native_reedit_resource").intercept(chain -> {
    Object result=chain.proceed();
    try {
     String id=(String)chain.getArg(2);
     int x=((Number)chain.getArg(3)).intValue(), y=((Number)chain.getArg(4)).intValue();
     int requestedVersion=((Number)chain.getArg(5)).intValue();
     int version=PayloadIndex.resolveVersion(id,requestedVersion);
     Context ctx=(Context)chain.getArg(0);
     if(x!=4 || y!=6 || version==0 || !galleryEnabled(ctx,true) || !PayloadIndex.ready(ctx)
        || !PayloadIndex.validPayload(ctx,id,version)) return result;
     // The album product only ships widget_2x2; querying a fictitious 4x4 loses its editor.
     int sourceSpan=PayloadIndex.originalSpan(id);
     Object nativeBase=localMaml.invoke(null,ctx,chain.getArg(1),id,sourceSpan,sourceSpan,version,chain.getArg(6));
     if(!(nativeBase instanceof List<?>) || ((List<?>)nativeBase).isEmpty()) return result;
     Object original=((List<?>)nativeBase).get(0), info=call(original,"component1");
     if(!id.equals(call(info,"getId")) || ((Number)call(info,"getVersionCode")).intValue()!=version) return result;
     Object xy=call(original,"component2");
     Object half=xy.getClass().getConstructor(Object.class,Object.class).newInstance(4,6);
     Map<Integer,Object> changes=new HashMap<>();changes.put(2,half);
     File resource=PayloadIndex.resource(ctx,id,version);changes.put(3,resource.getAbsolutePath());
     changes.put(4,new File(resource.getParentFile(),"preview/widget_4x6.png").getAbsolutePath());
     changes.put(5,new File(resource.getParentFile(),"preview/widget_4x6_dark.png").getAbsolutePath());
     Object actual=copy(original,9,changes);
     log("native reedit resource=4x6 original licensed "+sourceSpan+"x"+sourceSpan+" record preserved id="+id,null);
     return java.util.Collections.singletonList(actual);
    } catch(Throwable error) {log("native reedit retained original result",error);return result;}
   }));
   handles.add(xposed.hook(prepareEdit).setId("duo_gallery_native_editor_bounds").intercept(chain -> {
    Object result=chain.proceed();
    try {
     if(!(result instanceof Intent)) return result;
     Intent intent=(Intent)result;
     if(!"com.miui.gallery.action.WIDGET_EDIT".equals(intent.getAction()) || intent.getData()==null
         || !"gallery".equals(intent.getData().getScheme()) || !"widget".equals(intent.getData().getHost())
         || !"/edit".equals(intent.getData().getPath())) return result;
     Object item=chain.getThisObject(), owner=field(editIntent,"localIdProvider").get(item);
     if(owner==null) return result;
     Object path=field(owner.getClass(),"mResPath").get(owner);
     Context ctx=context();
     if(!(path instanceof String) || !PayloadIndex.isOwnHalfPage(ctx,(String)path) || !galleryEnabled(ctx,true)
         || !PayloadIndex.ready(ctx) || !PayloadIndex.protocolReady(ctx)) return result;
     String kind=intent.getData().getQueryParameter("widgetType");
     String legacy=intent.getData().getQueryParameter("widgetSize");
     String localId=intent.getData().getQueryParameter("localId");
     if(!("custom".equals(kind) || "collection".equals(kind)) || localId==null || localId.isEmpty()
         || !("SIZE_4_4".equals(legacy) || "SIZE_2_2".equals(legacy) || "SIZE_2_3".equals(legacy))) return result;
     // Gallery's pinned native parser supports SIZE_2_3 (enum 4). Only a user edit invokes it.
     // Preserve every parameter and the exact localId; native Gallery owns cancel/save and DB updates.
     Uri.Builder portrait=intent.getData().buildUpon().clearQuery();
     for(String key:intent.getData().getQueryParameterNames())
      for(String value:intent.getData().getQueryParameters(key)) portrait.appendQueryParameter(key,"widgetSize".equals(key)?"SIZE_2_3":value);
     intent.setData(portrait.build());
     log("official portrait editor SIZE_2_3; existing localId preserved, no database mutation",null);
     Object mamlView=call(item,"getMMamlView");
     Object root=field(owner.getClass(),"mRootView").get(owner);
     if(!(mamlView instanceof View) || !(root instanceof View)) return result;
     Rect widget=screenBounds((View)mamlView),content=screenBounds((View)root);
     if(widget.isEmpty() || content.isEmpty()) return result;
     // Native detail-page launch omits these extras; forward actual view rectangles, not a guessed ratio.
     Rect[] bounds={new Rect(widget),new Rect(content)};
     synchronized(editorBounds) {editorBounds.put(intent,bounds);}
     applyEditorBounds(intent,bounds);
     log("official Gallery editor bounds="+widget+" container="+content+" backend="+intent.getData().getQueryParameter("widgetSize")+"; localId unchanged",null);
    } catch(Throwable error) {log("native editor intent unchanged",error);}
    return result;
   }));
   handles.add(xposed.hook(launchEdit).setId("duo_gallery_native_editor_launch").intercept(chain -> {
    // onClick may supply its original desktop rectangle after prepareIntent.
    // Keep every nonempty native rectangle; fill only absent geometry on this verified intent.
    Object arg=chain.getArg(0);
    if(arg instanceof Intent) synchronized(editorBounds) {
     Rect[] bounds=editorBounds.remove((Intent)arg);
     if(bounds!=null) applyEditorBounds((Intent)arg,bounds);
    }
    return chain.proceed();
   }));
   handles.add(xposed.hook(span).setId("duo_gallery_preview_style").intercept(chain -> {
    // Reuse the original typed 4x4 view holder, never invent an unsupported style enum.
    synchronized (previewOriginalSpan) { if (previewOriginalSpan.containsKey(chain.getThisObject())) return 4; }
    return chain.proceed();
   }));
   handles.add(xposed.hook(size).setId("duo_gallery_preview_measure").intercept(chain -> {
    Object result = chain.proceed();
    synchronized (previewOriginalSpan) { if (!previewOriginalSpan.containsKey(chain.getThisObject())) return result; }
    try {
     Context ctx = ((View) chain.getThisObject()).getContext();
     int width = ((Number) call(result,"getFirst")).intValue();
     int h4 = ((Number) call(result,"getSecond")).intValue();
     Object twoRows = spanSize.invoke(null,ctx,2,false,true,12);
     int h2 = ((Number) call(twoRows,"getSecond")).intValue();
     int h6 = 2*h4-h2;
     if (width <= 0 || h2 <= 0 || h6 <= h4) return result;
     // Uniformly fit the six-row preview inside the original page-height budget.
     // Actual launcher placement remains four columns by six rows, not this preview scale.
     double fit = (double) h4/h6;
     return result.getClass().getConstructor(Object.class,Object.class).newInstance(
       Math.max(1,(int)Math.round(width*fit)),h4);
    } catch (Throwable error) { log("preview retained native measure",error); return result; }
   }));
   handles.add(xposed.hook(recycled).setId("duo_gallery_preview_restore").intercept(chain -> {
    synchronized (previewOriginalSpan) {
     int[] original=previewOriginalSpan.remove(chain.getThisObject());
     if(original!=null) { field(container,"mSpanX").setInt(chain.getThisObject(),original[0]); field(container,"mSpanY").setInt(chain.getThisObject(),original[1]); }
    }
    return chain.proceed();
   }));
   installed = true;
   log("12 exact picker hooks installed; new variants remain absent until verified payload staging",null);
  } catch (Throwable error) {
   for (HookHandle handle: handles) try { handle.unhook(); } catch(Throwable ignored) {}
   throw new IllegalStateException("picker contract rejected; partial hooks removed",error);
  }
 }

 private static List<?> expand(Object wrapper,List<?> source,Context ctx) throws Exception {
  synchronized(expansions) {
   for (int i=expansions.size()-1;i>=0;i--) {
    Expansion cached=expansions.get(i);
    Object owner=cached.owner.get();
    if(owner==null) { expansions.remove(i); continue; }
    if(owner==wrapper && cached.source==source) return cached.expanded;
    if(owner==wrapper) expansions.remove(i);
   }
   ArrayList<Object> out=new ArrayList<>(); Map<String,Boolean> added=new HashMap<>();
   for(Object item:source) {
    out.add(item);
    if(!"com.miui.gallery".equals(call(item,"getAppPackage")) || Boolean.TRUE.equals(call(item,"isPay"))) continue;
    Object original=call(item,"getMamlImplInfo"); if(original==null) continue;
    String id=(String)call(original,"getProductId");
    int version=((Number)call(original,"getMamlVersion")).intValue();
    if(!PayloadIndex.known(id,version) || added.containsKey(id) || !PayloadIndex.validPayload(ctx,id,version)) continue;
    // Do not duplicate an original or future upstream 4x6 variant.
    boolean upstream=false;
    for(Object other:source) {
     Object info=call(other,"getMamlImplInfo");
     if(info!=null && id.equals(call(info,"getProductId")) && "4x6".equals(call(info,"getMamlSize"))) upstream=true;
    }
    if(upstream) continue;
    Map<Integer,Object> changes=new HashMap<>();
    // Preserve the native product title verbatim; size belongs to layout metadata, not its name.
    changes.put(6,"4x6"); changes.put(7,4); changes.put(8,6);
    changes.put(12,PayloadIndex.homeArchive(id,version));
    changes.put(16,0); // Files are actually staged and SHA-checked; no fabricated download.
    changes.put(18,-1); changes.put(19,""); // Fresh native gadget/config, never reuse a live user's widget ID.
    changes.put(20,PayloadIndex.resource(ctx,id,version).getAbsolutePath());
    Object copy=copy(original,22,changes);
    for(String extra:new String[]{"permissionInfo","isRealEditable"}) {
     Field f=field(original.getClass(),extra); f.set(copy,f.get(original));
    }
    Map<Integer,Object> responseChanges=new HashMap<>();
    responseChanges.put(1,PREFIX+id); responseChanges.put(5,4); responseChanges.put(6,4); responseChanges.put(20,copy);
    Object variant=copy(item,29,responseChanges);
    out.add(variant); added.put(id,Boolean.TRUE);
   }
   expansions.add(new Expansion(wrapper,source,out));
   log("Gallery groups appended half-page variants="+added.size()+"; originals preserved="+source.size(),null);
   return out;
  }
 }
 private static synchronized boolean galleryEnabled(Context ctx,boolean edit) {
  long now=android.os.SystemClock.uptimeMillis();
  if(galleryConfig==null || now-configReadAt>1000L) {
   try {Bundle fresh=GalleryConfig.read(ctx);if(fresh!=null) galleryConfig=new Bundle(fresh);}
   catch(Throwable ignored) { /* Retain only a previously authenticated successful snapshot. */ }
   configReadAt=now;
  }
  return galleryConfig!=null && galleryConfig.getBoolean(edit?"gallery_native_edit_enabled":"gallery_4x6_enabled",true);
 }
 private static Rect screenBounds(View view) {
  if(!view.isShown() || !view.isAttachedToWindow() || view.getWidth()<=0 || view.getHeight()<=0) return new Rect();
  int[] position=new int[2];view.getLocationOnScreen(position);
  return new Rect(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());
 }
 @SuppressWarnings("deprecation") private static void applyEditorBounds(Intent intent,Rect[] bounds) {
  Rect widget=intent.getParcelableExtra("miuiWidgetScreenBound");
  Rect container=intent.getParcelableExtra("miuiContentContainerScreenBound");
  if(widget==null || widget.isEmpty()) intent.putExtra("miuiWidgetScreenBound",new Rect(bounds[0]));
  if(container==null || container.isEmpty()) intent.putExtra("miuiContentContainerScreenBound",new Rect(bounds[1]));
 }
 private static boolean isHalfPage(Object value) {
  if(value==null) return false;
  try {
   Object code=call(value,"getImplUniqueCode");
   if(!(code instanceof String) || !((String)code).startsWith(PREFIX) || !"com.miui.gallery".equals(call(value,"getAppPackage"))) return false;
   Object info=call(value,"getMamlImplInfo");
   return info!=null && "4x6".equals(call(info,"getMamlSize")) && PayloadIndex.known((String)call(info,"getProductId"),((Number)call(info,"getMamlVersion")).intValue());
  } catch(Throwable ignored) { return false; }
 }
 private static boolean fromHome() {
  try { Object f=activeFragment.get(); return f!=null && ((Number)call(f,"getMOpenSource")).intValue()==2; }
  catch(Throwable ignored) { return false; }
 }
 private static Context context() throws Exception {
  Class<?> type=Class.forName("android.app.ActivityThread"); Method m=type.getDeclaredMethod("currentApplication"); m.setAccessible(true);
  Application app=(Application)m.invoke(null); if(app==null) throw new IllegalStateException("application pending"); return app;
 }
 private static Object copy(Object value,int count,Map<Integer,Object> overrides) throws Exception {
  Object[] args=new Object[count]; for(int i=1;i<=count;i++) args[i-1]=overrides.containsKey(i)?overrides.get(i):call(value,"component"+i);
  return named(value.getClass(),"copy",count).invoke(value,args);
 }
 private static Object call(Object value,String name) throws Exception { Method m=value.getClass().getMethod(name);m.setAccessible(true);return m.invoke(value); }
 private static Method declared(Class<?> c,String name,Class<?>...types) throws Exception {Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
 private static Method named(Class<?> c,String name,int count) throws Exception {
  for(Method m:c.getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterCount()==count) {m.setAccessible(true);return m;}
  throw new NoSuchMethodException(c.getName()+"."+name+"/"+count);
 }
 private static Field field(Class<?> c,String name) throws Exception {
  for(Class<?> n=c;n!=null;n=n.getSuperclass()) try {Field f=n.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
  throw new NoSuchFieldException(c.getName()+"."+name);
 }
 private static void log(String text,Throwable error) {try {module.log(error==null?4:5,"DuoGalleryHalfPage",text+(error==null?"":" : "+android.util.Log.getStackTraceString(error)));}catch(Throwable ignored){}}
 private static final class Expansion {final WeakReference<Object> owner;final List<?> source;final List<?> expanded;Expansion(Object wrapper,List<?> a,List<?> b){owner=new WeakReference<>(wrapper);source=a;expanded=b;}}
}
