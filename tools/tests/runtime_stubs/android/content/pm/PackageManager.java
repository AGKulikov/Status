package android.content.pm;
import android.content.ComponentName;
import java.util.*;
public class PackageManager {
    public static final int MATCH_DISABLED_COMPONENTS=512, DONT_KILL_APP=1,
        COMPONENT_ENABLED_STATE_DEFAULT=0, COMPONENT_ENABLED_STATE_ENABLED=1,
        COMPONENT_ENABLED_STATE_DISABLED=2, COMPONENT_ENABLED_STATE_DISABLED_USER=3;
    public final Map<String,ApplicationInfo> apps = new HashMap<>();
    public final Map<String,Integer> states = new HashMap<>();
    public int launcherState = 0;
    public int componentWrites, failComponentWrite;
    public final Map<String,Integer> components = new HashMap<>();
    public ApplicationInfo getApplicationInfo(String pkg,int flags) throws Exception {
        ApplicationInfo app = apps.get(pkg);if(app==null)throw new Exception("not installed");return app;
    }
    public int getApplicationEnabledSetting(String pkg) { return states.getOrDefault(pkg,0); }
    public int getComponentEnabledSetting(ComponentName name) {return components.getOrDefault(name.name,0);}
    public void setComponentEnabledSetting(ComponentName name,int state,int flags) {
        if(++componentWrites==failComponentWrite)throw new IllegalStateException("simulated component write failure");
        components.put(name.name,state);
        if(name.name.endsWith("LauncherTrampolineActivity"))launcherState=state;
    }
    public void add(String pkg,int state,int flags) {
        ApplicationInfo app=new ApplicationInfo();app.packageName=pkg;app.flags=flags;apps.put(pkg,app);state(pkg,state);
    }
    public void state(String pkg,int state) { states.put(pkg,state);apps.get(pkg).enabled=state==0||state==1; }
}
