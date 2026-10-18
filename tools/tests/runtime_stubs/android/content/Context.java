package android.content;
import android.content.pm.PackageManager;
import android.telecom.TelecomManager;
import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public final PackageManager packages = new PackageManager();
    public final TelecomManager telecom = new TelecomManager();
    private final Map<String,SharedPreferences> prefs = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public java.io.File getFilesDir() { return new java.io.File(System.getProperty("java.io.tmpdir"), "fixture-files"); }
    public java.io.File getCacheDir() { return new java.io.File(System.getProperty("java.io.tmpdir"), "fixture-cache"); }
    public Context createDeviceProtectedStorageContext() { return this; }
    public String getPackageName() { return "ru.natro.statuswidget"; }
    public PackageManager getPackageManager() { return packages; }
    public synchronized SharedPreferences getSharedPreferences(String name,int mode) { return prefs.computeIfAbsent(name,n->new SharedPreferences()); }
    public <T> T getSystemService(Class<T> kind) { return kind.cast(telecom); }
}
