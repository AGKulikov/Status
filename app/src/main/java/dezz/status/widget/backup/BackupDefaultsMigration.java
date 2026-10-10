/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Explicit 3.0.2…3.1.1 -> 3.1.2 compatibility; unknown future schemas remain rejected. */
final class BackupDefaultsMigration {
    private BackupDefaultsMigration(){}
    static void validate(JSONObject metadata,int target)throws Exception{
        int source=metadata.getInt("sourceVersionCode"),schema=metadata.getInt("defaultsSchema");
        if(source!=schema||source>target||(source!=target&&source!=208021335&&source!=208021336&&source!=208021337&&source!=208021338&&source!=208021339&&source!=208021340&&source!=208021341&&source!=208021342&&source!=208021343&&source!=208021344)
                ||(source!=target&&target!=208021336&&target!=208021337&&target!=208021338&&target!=208021339&&target!=208021340&&target!=208021341&&target!=208021342&&target!=208021343&&target!=208021344&&target!=208021345))
            throw new IOException("Для этой версии копии ещё нет миграции; рабочие данные не изменены");
        BackupPreferencesXml.decode(metadata.getJSONArray("declaredMainDefaults"));
    }
    static void materialize(File snapshot,String packageName,JSONObject metadata,int target)throws Exception{
        validate(metadata,target);
        if(metadata.getInt("defaultsSchema")==target)return;
        // Schema-compatible keys keep the old effective default, even when the old XML omitted
        // it. Explicit values and unknown user keys always win. New target-only keys stay absent.
        Map<String,Object> defaults=BackupPreferencesXml.decode(metadata.getJSONArray("declaredMainDefaults"));
        File file=BackupFiles.child(snapshot,"de_prefs/"+packageName+"_preferences.xml");
        Map<String,Object> values=new LinkedHashMap<>(defaults);
        if(file.isFile())values.putAll(BackupPreferencesXml.read(file));
        BackupPreferencesXml.write(file,values);
        File backup=new File(file.getPath()+".bak");
        if(backup.isFile())throw new IOException("Не завершено восстановление файла настроек в исходной копии");
    }
}
