/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** UI writes enter its draft; existing runtime readers see that draft only while it is alive. */
public final class SettingsPreferences implements SharedPreferences {
    private final SharedPreferences durable;
    private final SettingsEditSession session;
    private final int generation;
    private SettingsPreferences(SharedPreferences durable, SettingsEditSession session) {
        this.durable = durable; this.session = session;this.generation=session==null?0:session.generation();
    }
    public static SharedPreferences wrap(Context context, SharedPreferences actual) {
        return wrap(context,actual,context.getPackageName()+"_preferences",true);
    }
    public static SharedPreferences wrap(Context context,SharedPreferences actual,String namespace,boolean deviceProtected) {
        SettingsApplyJournal.register(actual,namespace,deviceProtected);
        return new SettingsPreferences(actual, SettingsEditSession.find(context));
    }
    @Override public Map<String, ?> getAll() {
        return session == null ? SettingsEditSession.preview(durable) : session.read(durable);
    }
    private Object value(String key) { return session==null?SettingsEditSession.previewValue(durable,key):session.value(durable,key); }
    @Override public String getString(String key, String fallback) { Object v=value(key); return v==SettingsEditSession.NO_OVERRIDE?durable.getString(key,fallback):v==null?fallback:(String)v; }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        Object v=value(key); if(v==SettingsEditSession.NO_OVERRIDE)v=durable.getStringSet(key,fallback);
        return v==null?(fallback==null?null:new HashSet<>(fallback)):new HashSet<>((Set<String>)v);
    }
    @Override public int getInt(String key, int fallback) { Object v=value(key); return v==SettingsEditSession.NO_OVERRIDE?durable.getInt(key,fallback):v==null?fallback:(Integer)v; }
    @Override public long getLong(String key, long fallback) { Object v=value(key); return v==SettingsEditSession.NO_OVERRIDE?durable.getLong(key,fallback):v==null?fallback:(Long)v; }
    @Override public float getFloat(String key, float fallback) { Object v=value(key); return v==SettingsEditSession.NO_OVERRIDE?durable.getFloat(key,fallback):v==null?fallback:(Float)v; }
    @Override public boolean getBoolean(String key, boolean fallback) { Object v=value(key); return v==SettingsEditSession.NO_OVERRIDE?durable.getBoolean(key,fallback):v==null?fallback:(Boolean)v; }
    @Override public boolean contains(String key) { Object v=value(key);return v==SettingsEditSession.NO_OVERRIDE?durable.contains(key):v!=null; }
    @Override public Editor edit() { return session==null?durable.edit():new DraftEditor(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        durable.registerOnSharedPreferenceChangeListener(listener);
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        durable.unregisterOnSharedPreferenceChangeListener(listener);
    }
    private final class DraftEditor implements Editor {
        private final Map<String,Object> edits=new LinkedHashMap<>(); private boolean clear;
        @Override public Editor putString(String key,String value){edits.put(key,value);return this;}
        @Override public Editor putStringSet(String key,Set<String> value){edits.put(key,SettingsDraft.copy(value));return this;}
        @Override public Editor putInt(String key,int value){edits.put(key,value);return this;}
        @Override public Editor putLong(String key,long value){edits.put(key,value);return this;}
        @Override public Editor putFloat(String key,float value){edits.put(key,value);return this;}
        @Override public Editor putBoolean(String key,boolean value){edits.put(key,value);return this;}
        @Override public Editor remove(String key){edits.put(key,null);return this;}
        @Override public Editor clear(){clear=true;return this;}
        @Override public void apply(){commit();}
        @Override public boolean commit(){session.write(durable,edits,clear,generation);return true;}
    }
    @SuppressWarnings("unchecked")
    static boolean commit(SharedPreferences preferences, Map<String,Object> values) {
        Editor editor=preferences.edit();
        values.forEach((key,value)->{
            if(value==null)editor.remove(key);
            else if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Float)editor.putFloat(key,(Float)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);
            else throw new IllegalArgumentException("Unsupported preference type");
        });
        return editor.commit();
    }
}
