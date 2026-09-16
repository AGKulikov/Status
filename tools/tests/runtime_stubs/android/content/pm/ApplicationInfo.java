package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_SYSTEM = 1;
    public String packageName;
    public boolean enabled = true;
    public int flags;
    public CharSequence loadLabel(PackageManager pm) { return packageName; }
}
