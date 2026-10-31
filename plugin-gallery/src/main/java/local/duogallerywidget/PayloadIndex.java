// SPDX-License-Identifier: GPL-3.0-only
package local.duogallerywidget;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Fixed paths + package version + payload SHA gate. No arbitrary path/protocol. */
final class PayloadIndex {
 static final String HOME_APK="77d119ca63fc957f6bf7cbecbfaa19fe54ec1c68bdf061a23becfe04b2fe62ec";
 static final String GALLERY_APK="a2c12d04bcaf1b90497c049cffa8d83b16bf229eb932232f31f8825f6f130199";
 static final String PA_APK="277c68b24ecf4df442aef395277e166a5703001af8409d24bc52cbc7051f725d";
 private static final Map<String,String> HASH=new HashMap<>();
 private static final Map<String,String> PUBLIC_HASH=new HashMap<>();
 private static final Map<String,Integer> VERSION=new HashMap<>();
 private static final Map<String,String> validated=new HashMap<>();
 static {
  PUBLIC_HASH.put("e96919c6-105a-4b6e-986e-9eb910e1fa15", "48286853ab00ae338b7c439fdaa0f29a9eb2902064e4b0ad24ff8bd51eb3b1b5");
  PUBLIC_HASH.put("10538035-e37d-4edd-88db-1420c9ccaa04", "b929e1a4f601cd7ad846699127165b4712b33cfd1a796c2bc1209f02ca9ebb7a");
  PUBLIC_HASH.put("1648abf9-db10-4941-8083-9a5e68241c75", "aa458cfd043987e6e01d699e934ed60029b5e5e0ddd151a3edf705daa0283009");

  HASH.put("e96919c6-105a-4b6e-986e-9eb910e1fa15", "3507f94f83f946202e5e5343795cbf8e1a69150aaf78cd7594f763b913f0213d"); VERSION.put("e96919c6-105a-4b6e-986e-9eb910e1fa15", 40117);
  HASH.put("10538035-e37d-4edd-88db-1420c9ccaa04", "729bf4d96a2f2cbfba04a500142720a99269a996003d2ae8b193c9ceba5fb1d5"); VERSION.put("10538035-e37d-4edd-88db-1420c9ccaa04", 40116);
  HASH.put("1648abf9-db10-4941-8083-9a5e68241c75", "16ebcc50c37a447856db88d1e8cddd278a4fdad9fd17523276100706c68c2ba5"); VERSION.put("1648abf9-db10-4941-8083-9a5e68241c75", 40116);
 }
 static boolean known(String id,int version) {return id!=null && VERSION.containsKey(id) && VERSION.get(id)==version;}
 static int resolveVersion(String id,int requested) {
  Integer expected=VERSION.get(id);return expected!=null && (requested==0 || requested==expected)?expected:0;
 }
 static int originalSpan(String id) {return "1648abf9-db10-4941-8083-9a5e68241c75".equals(id)?2:4;}
 static boolean isOwnHalfPage(Context ctx,String path) {
  try {
   File f=new File(path);if(!"widget_4x6".equals(f.getName())) return false;
   String base=ctx.getFilesDir().getCanonicalPath()+"/";
   String canonical=f.getCanonicalPath();
   if(!canonical.startsWith(base+"duo-gallery-halfpage-v")) return false;
   for(Map.Entry<String,Integer> entry:VERSION.entrySet()) {
    String tail="/resources/"+entry.getKey()+"/"+entry.getValue()+"/"+entry.getKey()+"/widget_4x6";
    if(canonical.endsWith(tail)) return true;
   }
  } catch(Throwable ignored) {}
  return false;
 }
 static File resource(Context ctx,String id,int version) {
  if(!known(id,version)) throw new IllegalArgumentException("Unknown Gallery product");
  return new File(ctx.getFilesDir(),"duo-gallery-halfpage-v3/resources/"+id+"/"+version+"/"+id+"/widget_4x6");
 }
 static String homeArchive(String id,int version) {
  if(!known(id,version)) throw new IllegalArgumentException("Unknown Gallery product");
  return "file:///data/user_de/0/com.miui.home/files/maml/res/0/"+id+"/"+version+"/"+id+".duo4x6.zip";
 }
 static boolean protocolReady(Context ctx) {
  try {
   File marker=new File(ctx.getFilesDir(),"duo-gallery-halfpage-v3/protocol-ready");
   if(!marker.isFile() || marker.length()>128 || android.system.Os.stat(marker.getAbsolutePath()).st_uid!=ctx.getApplicationInfo().uid) return false;
   try(FileInputStream in=new FileInputStream(marker)) {return "DUO_GALLERY_NATIVE_23_V3".equals(new String(in.readAllBytes(),StandardCharsets.UTF_8).trim());}
  } catch(Throwable ignored) {return false;}
 }
 @SuppressWarnings("deprecation") static boolean ready(Context ctx) {
  try {
   if(!protocolReady(ctx) || !"com.miui.personalassistant".equals(ctx.getPackageName()) || ctx.getResources().getConfiguration().smallestScreenWidthDp<600) return false;
   File marker=new File(ctx.getFilesDir(),"duo-gallery-halfpage-v1/ready.json");
   if(!marker.isFile() || marker.length()>4096 || android.system.Os.stat(marker.getAbsolutePath()).st_uid!=ctx.getApplicationInfo().uid) return false;
   String text;
   try(FileInputStream in=new FileInputStream(marker)){text=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
   JSONObject value=new JSONObject(text);
   if(value.optInt("schema")!=1 || !value.optBoolean("enabled") || !"4x6".equals(value.optString("span")) || !HOME_APK.equals(value.optString("home_apk_sha256")) || !GALLERY_APK.equals(value.optString("gallery_apk_sha256")) || !PA_APK.equals(value.optString("pa_apk_sha256"))) return false;
   return ctx.getPackageManager().getPackageInfo("com.miui.home",0).getLongVersionCode()==801026325L
      && ctx.getPackageManager().getPackageInfo("com.miui.gallery",0).getLongVersionCode()==5040226L
      && ctx.getPackageManager().getPackageInfo("com.miui.personalassistant",0).getLongVersionCode()==2540340L;
  } catch(Throwable ignored){return false;}
 }
 static synchronized boolean validPayload(Context ctx,String id,int version) {
  try {
   File file=resource(ctx,id,version);
   if(!file.isFile() || file.length()<=0 || file.length()>4*1024*1024) return false;
   if(android.system.Os.stat(file.getAbsolutePath()).st_uid!=ctx.getApplicationInfo().uid) return false;
   String stamp=file.length()+":"+file.lastModified()+":"+android.system.Os.stat(file.getAbsolutePath()).st_ino;
   if(stamp.equals(validated.get(id))) return true;
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(FileInputStream in=new FileInputStream(file)){byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1) digest.update(buf,0,n);}
   StringBuilder hex=new StringBuilder(); for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
   if(!HASH.get(id).equals(hex.toString()) && !PUBLIC_HASH.get(id).equals(hex.toString())) return false;
   validated.put(id,stamp); return true;
  } catch(Throwable ignored){return false;}
 }
}
