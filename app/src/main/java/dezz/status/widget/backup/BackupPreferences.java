/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Process-wide preference write barrier, including namespaces whose first XML is still queued. */
public final class BackupPreferences implements SharedPreferences {
    private static final Map<SharedPreferences,BackupPreferences> stores=new IdentityHashMap<>();
    private static final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
    private final SharedPreferences disk;
    private BackupPreferences(SharedPreferences disk){this.disk=disk;}
    public abstract static class ReadOnlyDefaultsContext extends android.content.ContextWrapper{
        protected ReadOnlyDefaultsContext(Context source){super(source);}
    }
    public static SharedPreferences open(Context context,String name,int mode){
        if(context instanceof ReadOnlyDefaultsContext)return context.getSharedPreferences(name,mode);
        gate.readLock().lock();try{
            SharedPreferences disk=context.getSharedPreferences(name,mode);
            synchronized(stores){BackupPreferences result=stores.get(disk);if(result==null){result=new BackupPreferences(disk);stores.put(disk,result);}return result;}
        }finally{gate.readLock().unlock();}
    }
    /** The same worker holds this until process exit, or releases it when maintenance fails. */
    public static AutoCloseable freezeAndFlush()throws IOException{
        gate.writeLock().lock();boolean success=false;
        try{
            synchronized(stores){for(BackupPreferences store:stores.values())if(!store.disk.edit().commit())throw new IOException("Preference flush failed");}
            success=true;return ()->gate.writeLock().unlock();
        }finally{if(!success)gate.writeLock().unlock();}
    }
    @Override public Map<String,?> getAll(){return disk.getAll();}
    @Override public String getString(String k,String d){return disk.getString(k,d);}
    @Override public Set<String> getStringSet(String k,Set<String> d){return disk.getStringSet(k,d);}
    @Override public int getInt(String k,int d){return disk.getInt(k,d);}
    @Override public long getLong(String k,long d){return disk.getLong(k,d);}
    @Override public float getFloat(String k,float d){return disk.getFloat(k,d);}
    @Override public boolean getBoolean(String k,boolean d){return disk.getBoolean(k,d);}
    @Override public boolean contains(String k){return disk.contains(k);}
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){disk.registerOnSharedPreferenceChangeListener(listener);}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){disk.unregisterOnSharedPreferenceChangeListener(listener);}
    @Override public Editor edit(){return new Change(disk.edit());}
    private static final class Change implements Editor{
        private final Editor editor;
        Change(Editor editor){this.editor=editor;}
        @Override public Editor putString(String k,String v){editor.putString(k,v);return this;}
        @Override public Editor putStringSet(String k,Set<String> v){editor.putStringSet(k,v);return this;}
        @Override public Editor putInt(String k,int v){editor.putInt(k,v);return this;}
        @Override public Editor putLong(String k,long v){editor.putLong(k,v);return this;}
        @Override public Editor putFloat(String k,float v){editor.putFloat(k,v);return this;}
        @Override public Editor putBoolean(String k,boolean v){editor.putBoolean(k,v);return this;}
        @Override public Editor remove(String k){editor.remove(k);return this;}
        @Override public Editor clear(){editor.clear();return this;}
        @Override public boolean commit(){gate.readLock().lock();try{return editor.commit();}finally{gate.readLock().unlock();}}
        @Override public void apply(){gate.readLock().lock();try{editor.apply();}finally{gate.readLock().unlock();}}
    }
}
