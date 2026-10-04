package dezz.status.widget.backup;
import android.content.Context;
import android.content.SharedPreferences;
/** Console replays use a synchronous store; the actual freeze barrier has Android tests. */
public final class BackupPreferences {
    public static SharedPreferences open(Context context,String name,int mode){return context.getSharedPreferences(name,mode);}
}
