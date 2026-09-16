package android.content;
public class ComponentName {
 public final String name;
 public ComponentName(Context c,Class<?> type) { this(c,type.getName()); }
 public ComponentName(Context c,String type) { name=type; }
}
