/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.*;
import java.io.IOException;
import java.util.UUID;

public final class NavigatorBackup implements BackupTransaction.Participant {
    private final Context context;
    public NavigatorBackup(Context context){this.context=context;}
    public JSONObject freeze()throws Exception {
        try {context.getPackageManager().getPackageInfo("ru.yandex.yandexnavi",0);}
        catch(PackageManager.NameNotFoundException absent){return new JSONObject().put("installed",false);}
        String token=UUID.randomUUID().toString();
        String raw=NavigatorBackupProvider.request(context,"freeze",token,null).getString("values");
        if(raw==null)throw new IOException("Navigator snapshot missing");
        BackupJson.validate(raw);JSONArray values=new JSONArray(raw);BackupPreferencesXml.decode(values);
        return new JSONObject().put("installed",true).put("token",token).put("values",values);
    }
    public void release(JSONObject snapshot)throws Exception {
        if(snapshot.optBoolean("installed"))NavigatorBackupProvider.request(context,"rollback",snapshot.getString("token"),null);
    }
    @Override public void apply(JSONObject metadata)throws Exception {
        if(metadata==null||!metadata.has("navigatorToken"))return;
        NavigatorBackupProvider.request(context,"apply",metadata.getString("navigatorToken"),metadata.getJSONArray("navigatorValues").toString());
    }
    @Override public void finish(JSONObject metadata,boolean committed)throws Exception {
        if(metadata==null||!metadata.has("navigatorToken"))return;
        NavigatorBackupProvider.request(context,committed?"commit":"rollback",metadata.getString("navigatorToken"),null);
    }
}
