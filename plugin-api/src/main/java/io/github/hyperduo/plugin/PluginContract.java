package io.github.hyperduo.plugin;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/** Versioned cross-APK contract. No feature implementation or executable loading. */
public final class PluginContract {
 public static final int API_VERSION=1;
 public static final String HOST_PACKAGE="local.duodesktop", ACTION_SETTINGS="io.github.hyperduo.plugin.SETTINGS";
 public static final String META_ID="io.github.hyperduo.plugin.ID", META_API_VERSION="io.github.hyperduo.plugin.API_VERSION", META_HOST_MIN_VERSION="io.github.hyperduo.plugin.HOST_MIN_VERSION";
 public static final String PERMISSION_MANAGE_PLUGINS="local.duodesktop.MANAGE_PLUGINS";
 public static final String METHOD_IMPORT_CONFIG="importLegacy",METHOD_IMPORT_LEGACY="importLegacy";
 public static final String EXTRA_CONFIG="config",EXTRA_PREFERENCES="preferences",EXTRA_FILES="files",EXTRA_PHOTO_URIS="photoUris";
 private PluginContract(){}
 public static Bundle exportPreferences(Context context,String...names){
  Bundle all=new Bundle();
  for(String name:names){Bundle values=new Bundle();for(Map.Entry<String,?> e:context.getSharedPreferences(name,0).getAll().entrySet()){
   String k=e.getKey();Object v=e.getValue();
   if(v instanceof Boolean)values.putBoolean(k,(Boolean)v);else if(v instanceof Integer)values.putInt(k,(Integer)v);else if(v instanceof Long)values.putLong(k,(Long)v);else if(v instanceof Float)values.putFloat(k,(Float)v);else if(v instanceof String)values.putString(k,(String)v);else if(v instanceof Set)values.putStringArrayList(k,new ArrayList<>((Set<String>)v));
  }all.putBundle(name,values);}return all;
 }
 public static void importPreferences(Context context,Bundle all){
  for(String name:all.keySet()){Bundle values=all.getBundle(name);SharedPreferences.Editor edit=context.getSharedPreferences(name,0).edit();
   for(String k:values.keySet()){Object v=values.get(k);if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof ArrayList)edit.putStringSet(k,new HashSet<>((ArrayList<String>)v));}edit.apply();
  }
 }
}
