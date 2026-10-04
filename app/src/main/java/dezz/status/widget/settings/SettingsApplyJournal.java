/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.content.SharedPreferences;
import dezz.status.widget.backup.BackupFiles;
import dezz.status.widget.backup.BackupJson;
import dezz.status.widget.backup.BackupMaintenance;
import dezz.status.widget.backup.BackupPreferencesXml;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Write-ahead undo journal for a settings Apply spanning more than one preference namespace.
 * The maintenance lock excludes backup/restore and other processes' startup recovery. Only touched
 * keys are journalled; runtime telemetry, launch tokens and unrelated settings are not rolled back.
 */
public final class SettingsApplyJournal {
    private static final int SCHEMA=1;
    private static final String FILE="settings-apply-v1.json";
    private static final Map<SharedPreferences,Namespace> namespaces=Collections.synchronizedMap(new WeakHashMap<>());
    private SettingsApplyJournal(){}
    private static final class Namespace {
        final String name;final boolean device;
        Namespace(String name,boolean device){
            if(name==null||!name.matches("[A-Za-z0-9_.-]{1,180}")||name.equals(".")||name.equals(".."))
                throw new IllegalArgumentException("Invalid preference namespace");
            this.name=name;this.device=device;
        }
    }
    static void register(SharedPreferences store,String name,boolean device){namespaces.put(store,new Namespace(name,device));}
    interface Fault { void at(String stage); }
    static boolean commit(Context context,Map<SharedPreferences,Map<String,Object>> changes) {
        try { commit(context,changes,stage->{});return true; }
        catch(Exception failure){android.util.Log.e("SettingsApply","Apply failed; working keys recovered or journal retained",failure);return false;}
    }
    static void commit(Context context,Map<SharedPreferences,Map<String,Object>> changes,Fault fault)throws Exception {
        File control=BackupMaintenance.control(context);BackupFiles.directory(control);
        try(RandomAccessFile mutex=new RandomAccessFile(new File(control,"maintenance.lock"),"rw")){
            FileLock lock;
            try{lock=mutex.getChannel().tryLock();}catch(java.nio.channels.OverlappingFileLockException busy){lock=null;}
            if(lock==null)throw new IOException("Backup or settings maintenance is already running");
            try{
                recover(context);
                JSONArray stores=new JSONArray();List<SharedPreferences> targets=new ArrayList<>();
                for(Map.Entry<SharedPreferences,Map<String,Object>> change:changes.entrySet()){
                    if(change.getValue().isEmpty())continue;
                    Namespace namespace=namespaces.get(change.getKey());
                    if(namespace==null)throw new IOException("Unregistered settings storage");
                    Map<String,Object> before=new LinkedHashMap<>();Map<String,?> actual=change.getKey().getAll();
                    for(String key:change.getValue().keySet())before.put(key,SettingsDraft.copy(actual.get(key)));
                    stores.put(new JSONObject().put("name",namespace.name).put("device",namespace.device)
                            .put("before",encode(before)).put("after",encode(change.getValue())));
                    targets.add(change.getKey());
                }
                if(stores.length()==0)return;
                JSONObject journal=new JSONObject().put("schema",SCHEMA).put("state","PREPARED").put("stores",stores);
                File file=new File(control,FILE);write(file,journal);fault.at("prepared");
                try{
                    for(int i=0;i<stores.length();i++){
                        Map<String,Object> after=decode(stores.getJSONObject(i).getJSONObject("after"));
                        if(!SettingsPreferences.commit(targets.get(i),after)||!sameValues(targets.get(i).getAll(),after))
                            throw new IOException("Settings read-back failed");
                        fault.at("store:"+i);
                    }
                    journal.put("state","APPLIED");write(file,journal);fault.at("applied");
                }catch(Exception failure){
                    // commit() can fail after updating the framework's RAM map. Reconcile every
                    // participant through the same SharedPreferences API, never by replacing XML.
                    try{recover(context);}catch(Exception rollback){failure.addSuppressed(rollback);}
                    throw failure;
                }
                BackupFiles.delete(file);
            }finally{lock.release();}
        }
    }
    /** Caller holds maintenance.lock, before any controller reads settings or backup takes a snapshot. */
    public static void recover(Context context)throws Exception {
        File file=new File(BackupMaintenance.control(context),FILE);if(!file.isFile())return;
        String raw=new String(BackupFiles.read(file,16*1024*1024),StandardCharsets.UTF_8);BackupJson.validate(raw);
        JSONObject journal=new JSONObject(raw);
        if(journal.getInt("schema")!=SCHEMA)throw new IOException("Unknown settings journal schema");
        String state=journal.getString("state");
        if(!state.equals("PREPARED")&&!state.equals("APPLIED"))throw new IOException("Unknown settings journal state");
        JSONArray stores=journal.getJSONArray("stores");if(stores.length()>128)throw new IOException("Too many settings stores");
        Set<String> seen=new HashSet<>();List<SharedPreferences> targets=new ArrayList<>();List<Map<String,Object>> original=new ArrayList<>();
        // Preflight the complete journal before reverting even its first participant.
        for(int i=0;i<stores.length();i++){
            JSONObject item=stores.getJSONObject(i);
            Namespace namespace=new Namespace(item.getString("name"),item.getBoolean("device"));
            if(!seen.add(namespace.device+":"+namespace.name))throw new IOException("Duplicate settings participant");
            Map<String,Object> before=decode(item.getJSONObject("before")),after=decode(item.getJSONObject("after"));
            if(!before.keySet().equals(after.keySet()))throw new IOException("Settings journal key mismatch");
            Context storage=namespace.device?context.createDeviceProtectedStorageContext():context.createCredentialProtectedStorageContext();
            SharedPreferences target=storage.getSharedPreferences(namespace.name,Context.MODE_PRIVATE);
            if(state.equals("PREPARED")){
                Map<String,?> actual=target.getAll();
                for(String key:before.keySet())if(!Objects.equals(actual.get(key),before.get(key))&&!Objects.equals(actual.get(key),after.get(key)))
                    throw new IOException("A settings journal participant has an unexpected value");
            }
            targets.add(target);original.add(before);
        }
        if(state.equals("PREPARED"))for(int i=0;i<targets.size();i++){
            if(!SettingsPreferences.commit(targets.get(i),original.get(i))||!sameValues(targets.get(i).getAll(),original.get(i)))
                throw new IOException("Settings recovery read-back failed");
        }
        BackupFiles.delete(file);
    }
    private static boolean sameValues(Map<String,?> actual,Map<String,Object> expected){
        for(Map.Entry<String,Object> value:expected.entrySet())if(!Objects.equals(actual.get(value.getKey()),value.getValue()))return false;
        return true;
    }
    private static JSONObject encode(Map<String,Object> values)throws Exception {
        Map<String,Object> present=new LinkedHashMap<>();JSONArray absent=new JSONArray();
        for(Map.Entry<String,Object> entry:values.entrySet())if(entry.getValue()==null)absent.put(entry.getKey());else present.put(entry.getKey(),entry.getValue());
        return new JSONObject().put("values",BackupPreferencesXml.encode(present)).put("absent",absent);
    }
    private static Map<String,Object> decode(JSONObject values)throws Exception {
        Map<String,Object> result=BackupPreferencesXml.decode(values.getJSONArray("values"));JSONArray absent=values.getJSONArray("absent");
        if(absent.length()>100000)throw new IOException("Too many removed settings");
        for(int i=0;i<absent.length();i++){
            Object key=absent.get(i);if(!(key instanceof String)||result.containsKey(key))throw new IOException("Duplicate settings key");
            result.put((String)key,null);
        }
        return result;
    }
    private static void write(File file,JSONObject value)throws Exception{BackupFiles.atomicWrite(file,value.toString().getBytes(StandardCharsets.UTF_8));}
}
