package android.content;
import java.util.*;
/** JVM test double, never packaged in the APK. */
public final class SharedPreferences {
    public int failCommits;
    private final Map<String,Object> data = new HashMap<>();
    public synchronized Map<String,?> getAll() { return new HashMap<>(data); }
    public synchronized boolean contains(String key) { return data.containsKey(key); }
    public synchronized int getInt(String key,int fallback) { return (Integer)data.getOrDefault(key,fallback); }
    public synchronized String getString(String key,String fallback) { return (String)data.getOrDefault(key,fallback); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        final Map<String,Object> next = new HashMap<>();
        public Editor putInt(String k,int v) { next.put(k,v);return this; }
        public Editor putBoolean(String k,boolean v) { next.put(k,v);return this; }
        public Editor putString(String k,String v) { next.put(k,v);return this; }
        public Editor remove(String k) { next.put(k,null);return this; }
        public boolean commit() { synchronized(SharedPreferences.this) {
            if (failCommits > 0) { failCommits--;return false; }
            next.forEach((k,v)->{if(v==null)data.remove(k);else data.put(k,v);});return true;
        }}
    }
}
