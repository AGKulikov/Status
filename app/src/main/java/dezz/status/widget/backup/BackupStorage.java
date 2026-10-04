/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import dezz.status.widget.BuildConfig;
import dezz.status.widget.PortableBackupSecrets;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Enumerates whole namespaces, not an export key allowlist. No running renderer/controller is constructed. */
public final class BackupStorage {
    private static final String PORTABLE="portable-secrets.json";
    private final Context context;
    private final Map<String,File> roots=new LinkedHashMap<>();
    private final Map<String,Set<String>> excluded=new LinkedHashMap<>();
    public BackupStorage(Context context) {
        this.context=context.getApplicationContext();
        if(this.context.isDeviceProtectedStorage())throw new IllegalStateException("The application default must remain credential-protected");
        addContext("ce",context.getApplicationContext());
        addContext("de",context.createDeviceProtectedStorageContext());
        excluded.put("de_no_backup",new HashSet<>(Collections.singletonList("natro-backup")));
        File[] external=context.getExternalFilesDirs(null);
        for(int index=0;index<external.length;index++)if(external[index]!=null)roots.put("external_"+index,external[index]);
        roots.put("shared_ancs",new File(Environment.getExternalStorageDirectory(),"StatusWidget/ANCS-icons"));
    }
    private void addContext(String name,Context storage) {
        roots.put(name+"_prefs",new File(storage.getDataDir(),"shared_prefs"));
        roots.put(name+"_files",storage.getFilesDir());
        roots.put(name+"_no_backup",storage.getNoBackupFilesDir());
        roots.put(name+"_databases",new File(storage.getDataDir(),"databases"));
    }
    public BackupTransaction transaction() {
        return new BackupTransaction(new File(BackupMaintenance.control(context),"restore"),roots,excluded,null).withParticipant(new NavigatorBackup(context));
    }
    public String installation()throws Exception {
        String androidId=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ANDROID_ID);
        if(androidId==null||androidId.isEmpty())throw new IOException("Cannot identify backup device");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] value=digest.digest((context.getPackageName()+":"+androidId).getBytes(StandardCharsets.UTF_8));
        StringBuilder text=new StringBuilder();for(byte b:value)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }

    public JSONObject capture(File snapshot)throws Exception {
        transaction().snapshot(snapshot);
        JSONObject metadata=new JSONObject().put("package",context.getPackageName()).put("sourceVersion",BuildConfig.VERSION_NAME)
                .put("sourceVersionCode",BuildConfig.VERSION_CODE).put("installation",installation())
                .put("createdAt",System.currentTimeMillis()).put("defaultsSchema",BuildConfig.VERSION_CODE)
                .put("consistency","processes-flushed-and-stopped");
        metadata.put("externalSystemFiles",BackupExternalState.capture(snapshot));
        JSONArray namespaces=new JSONArray();for(String root:roots.keySet())namespaces.put(root);
        metadata.put("namespaces",namespaces);
        metadata.put("declaredMainDefaults",BackupPreferencesXml.encode(dezz.status.widget.PortableBackupDefaults.capture(context)));
        JSONObject secrets=new JSONObject();JSONArray records=new JSONArray();JSONObject semantics=new JSONObject();
        for(Map.Entry<String,File> file:BackupFiles.inventory(snapshot).entrySet()) {
            String path=file.getKey();
            if(isPreferences(path)) {
                Map<String,Object> values=BackupPreferencesXml.read(file.getValue());
                metadata.put("preferences:"+path,BackupPreferencesXml.encode(values));
                for(Map.Entry<String,Object> value:values.entrySet())if(value.getValue() instanceof String) {
                    BackupDocuments.validate(value.getKey(),(String)value.getValue());
                    if(documentKey(value.getKey(),(String)value.getValue()))semantics.put(path+":"+value.getKey(),BackupDocumentDecoders.inspect(value.getKey(),(String)value.getValue()));
                }
                for(Map.Entry<String,Object> entry:values.entrySet()) {
                    if(entry.getValue() instanceof String&&((String)entry.getValue()).startsWith("v1:")) {
                        records.put(new JSONObject().put("path",path).put("key",entry.getKey())
                                .put("value",PortableBackupSecrets.unwrapPreference(context,(String)entry.getValue())));
                    }
                }
            }
            if(path.endsWith("/live_activity_apns_p8_v1.bin")) {
                byte[] key=PortableBackupSecrets.unwrapApns(BackupFiles.read(file.getValue(),32768));
                try {secrets.put("apns",new JSONObject().put("path",path).put("value",Base64.getEncoder().encodeToString(key)));}
                finally {Arrays.fill(key,(byte)0);}
            }
        }
        secrets.put("preferences",records);
        BackupFiles.atomicWrite(new File(snapshot,PORTABLE),secrets.toString().getBytes(StandardCharsets.UTF_8));
        metadata.put("resources",captureUriResources(snapshot));
        metadata.put("documentSemantics",semantics);
        return metadata;
    }

    private JSONArray captureUriResources(File snapshot)throws Exception {
        LinkedHashSet<String> uris=new LinkedHashSet<>();
        for(Map.Entry<String,File> entry:BackupFiles.inventory(snapshot).entrySet())if(isPreferences(entry.getKey())) {
            for(Map.Entry<String,Object> value:BackupPreferencesXml.read(entry.getValue()).entrySet())if(value.getValue() instanceof String) {
                String text=(String)value.getValue();
                if(value.getKey().toLowerCase(Locale.ROOT).endsWith("uri"))collectUri(value.getKey(),text,uris);
                if(text.trim().startsWith("{"))collectJsonUris(new JSONObject(text),uris);
                else if(text.trim().startsWith("["))collectJsonUris(new JSONArray(text),uris);
            }
        }
        JSONArray resources=new JSONArray();int index=0;
        for(String uri:uris) {
            File target=BackupFiles.child(snapshot,"resources/uri-"+(index++)+".bin");BackupFiles.directory(target.getParentFile());
            try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri));FileOutputStream output=new FileOutputStream(target)) {
                if(input==null)throw new IOException("Пользовательский ресурс недоступен: копия неполная");
                BackupFiles.copy(input,output,BackupFiles.MAX_FILE_BYTES);output.getFD().sync();
            }
            resources.put(new JSONObject().put("uri",uri).put("path","resources/"+target.getName()).put("sha256",BackupFiles.sha256(target)));
        }
        return resources;
    }
    private static void collectJsonUris(Object node,Set<String> values)throws Exception {
        if(node instanceof JSONObject) {
            JSONObject object=(JSONObject)node;Iterator<String> keys=object.keys();
            while(keys.hasNext()) {String key=keys.next();Object child=object.get(key);
                if(child instanceof String)collectUri(key,(String)child,values);else collectJsonUris(child,values);}
        } else if(node instanceof JSONArray)for(int index=0;index<((JSONArray)node).length();index++)collectJsonUris(((JSONArray)node).get(index),values);
    }
    private static void collectUri(String key,String value,Set<String> result) {
        if(key.toLowerCase(Locale.ROOT).endsWith("uri")&&(value.startsWith("content://")||value.startsWith("file://")))result.add(value);
    }

    public void validate(File snapshot,JSONObject metadata)throws Exception {
        if(!context.getPackageName().equals(metadata.getString("package")))throw new IOException("Копия другого приложения");
        BackupDefaultsMigration.validate(metadata,BuildConfig.VERSION_CODE);
        if(metadata.has("externalSystemFiles"))BackupExternalState.validate(snapshot,metadata.getJSONArray("externalSystemFiles"));
        else if(metadata.getInt("sourceVersionCode")>=208021337)throw new IOException("В копии отсутствует отчёт о системных оригиналах");
        JSONObject navigator=metadata.getJSONObject("navigator");
        if(navigator.getBoolean("installed"))BackupPreferencesXml.decode(navigator.getJSONArray("values"));
        Set<String> names=new HashSet<>();JSONArray namespaces=metadata.getJSONArray("namespaces");
        for(int index=0;index<namespaces.length();index++)if(!names.add(namespaces.getString(index)))throw new IOException("Duplicate namespace");
        for(String name:names)if(!roots.containsKey(name))throw new IOException("Недоступно хранилище копии: "+name);
        for(Map.Entry<String,File> file:BackupFiles.inventory(snapshot).entrySet()) {
            String path=file.getKey();
            if(path.equals(PORTABLE)||path.startsWith("resources/"))continue;
            int slash=path.indexOf('/');
            if(slash<1||!names.contains(path.substring(0,slash)))throw new IOException("Unknown stored namespace");
            if(isPreferences(path)) {
                Map<String,Object> values=BackupPreferencesXml.read(file.getValue());
                if(!BackupPreferencesXml.encode(values).toString().equals(metadata.getJSONArray("preferences:"+path).toString()))
                    throw new IOException("Typed preference read-back differs");
                for(Map.Entry<String,Object> value:values.entrySet())if(value.getValue() instanceof String) {
                    BackupDocuments.validate(value.getKey(),(String)value.getValue());
                    if(documentKey(value.getKey(),(String)value.getValue())) {
                        JSONObject actual=BackupDocumentDecoders.inspect(value.getKey(),(String)value.getValue());
                        JSONObject expected=metadata.getJSONObject("documentSemantics").getJSONObject(path+":"+value.getKey());
                        if(!actual.toString().equals(expected.toString()))throw new IOException("Изменился результат декодера: "+value.getKey());
                    }
                }
            } else if(path.endsWith(".json")) {
                String json=new String(BackupFiles.read(file.getValue(),16*1024*1024),StandardCharsets.UTF_8);
                BackupJson.validate(json);
            }
        }
        File portable=new File(snapshot,PORTABLE);BackupJson.validate(new String(BackupFiles.read(portable,16*1024*1024),StandardCharsets.UTF_8));
        JSONArray resources=metadata.getJSONArray("resources");
        for(int index=0;index<resources.length();index++) {
            JSONObject resource=resources.getJSONObject(index);
            if(!BackupFiles.sha256(BackupFiles.child(snapshot,resource.getString("path"))).equals(resource.getString("sha256")))
                throw new IOException("Пользовательский ресурс отсутствует или повреждён");
        }
    }

    /** Transforms only isolated files. Current prefs, credentials and UI remain untouched until apply. */
    public void prepareRestore(File snapshot,JSONObject metadata)throws Exception {
        validate(snapshot,metadata);
        BackupDefaultsMigration.materialize(snapshot,context.getPackageName(),metadata,BuildConfig.VERSION_CODE);
        JSONObject secrets=new JSONObject(new String(BackupFiles.read(new File(snapshot,PORTABLE),16*1024*1024),StandardCharsets.UTF_8));
        JSONArray entries=secrets.getJSONArray("preferences");
        for(int index=0;index<entries.length();index++) {
            JSONObject entry=entries.getJSONObject(index);File file=BackupFiles.child(snapshot,entry.getString("path"));
            if(!isPreferences(entry.getString("path")))throw new IOException("Secret targets an invalid source");
            Map<String,Object> values=BackupPreferencesXml.read(file);
            if(!values.containsKey(entry.getString("key")))throw new IOException("Secret has no matching setting");
            values.put(entry.getString("key"),PortableBackupSecrets.wrapPreference(context,entry.getString("value")));
            BackupPreferencesXml.write(file,values);
        }
        if(secrets.has("apns")) {
            JSONObject apns=secrets.getJSONObject("apns");byte[] plain=Base64.getDecoder().decode(apns.getString("value"));
            try {BackupFiles.atomicWrite(BackupFiles.child(snapshot,apns.getString("path")),PortableBackupSecrets.wrapApns(plain));}
            finally {Arrays.fill(plain,(byte)0);}
        }
        if(!installation().equals(metadata.getString("installation")))preserveLocalOwnership(snapshot,metadata);
        JSONArray resources=metadata.getJSONArray("resources");Map<String,String> replacements=new LinkedHashMap<>();
        for(int index=0;index<resources.length();index++) {
            JSONObject resource=resources.getJSONObject(index);String name=resource.getString("sha256")+".bin";
            File file=BackupFiles.child(snapshot,"ce_files/backup-resources/"+name);
            BackupFiles.atomicCopy(BackupFiles.child(snapshot,resource.getString("path")),file);
            replacements.put(resource.getString("uri"),Uri.fromFile(new File(context.getApplicationContext().getFilesDir(),"backup-resources/"+name)).toString());
        }
        for(Map.Entry<String,File> file:BackupFiles.inventory(snapshot).entrySet())if(isPreferences(file.getKey())) {
            Map<String,Object> values=BackupPreferencesXml.read(file.getValue());boolean changed=false;
            for(Map.Entry<String,Object> entry:values.entrySet())if(entry.getValue() instanceof String) {
                String text=(String)entry.getValue();
                text=replaceUris(entry.getKey(),text,replacements);
                if(!text.equals(entry.getValue())){entry.setValue(text);changed=true;}
            }
            if(changed)BackupPreferencesXml.write(file.getValue(),values);
        }
        BackupFiles.delete(new File(snapshot,PORTABLE));BackupFiles.removeTree(new File(snapshot,"resources"));
        Set<String> runtimeStores=new HashSet<>(Arrays.asList("launcher_navigation.xml","launcher_media_broadcast_v1.xml",
                "vehicle_external_state.xml","startup_work_state.xml","phone_package_replace_recovery.xml","hud_fallback_startup_lane.xml",
                context.getPackageName()+"_phone_telemetry.xml",context.getPackageName()+"_automation_state_v1.xml"));
        for(Map.Entry<String,File> entry:BackupFiles.inventory(snapshot).entrySet())if(isPreferences(entry.getKey())) {
            String name=entry.getValue().getName();String canonical=name.endsWith(".bak")?name.substring(0,name.length()-4):name;
            if(runtimeStores.contains(canonical)) {
                BackupFiles.atomicCopy(entry.getValue(),BackupFiles.child(snapshot,"ce_files/restored-history/"+entry.getKey()));
                BackupFiles.delete(entry.getValue());
            }
        }
        // A recorder session and a prior auto-resume attempt are historical, never commands to replay.
        BackupFiles.delete(BackupFiles.child(snapshot,"ce_files/diagnostics/recorder-active.json"));
        for(String namespace:new String[]{"ce_prefs","de_prefs"}) {
            File resume=BackupFiles.child(snapshot,namespace+"/launcher_media_auto_resume_state.xml");
            if(resume.isFile())BackupPreferencesXml.write(resume,Collections.emptyMap());
            BackupFiles.delete(new File(resume.getPath()+".bak"));
            File panel=BackupFiles.child(snapshot,namespace+"/instrument_panel.xml");
            if(panel.isFile()){Map<String,Object> state=BackupPreferencesXml.read(panel);
                state.keySet().removeIf(key->key.toLowerCase(Locale.ROOT).contains("launchtoken"));
                BackupPreferencesXml.write(panel,state);BackupFiles.delete(new File(panel.getPath()+".bak"));}
        }
    }
    private void preserveLocalOwnership(File snapshot,JSONObject metadata)throws Exception {
        String source=metadata.getString("installation");
        if(!source.matches("[a-f0-9]{64}"))throw new IOException("Invalid source installation");
        Set<String> boundStores=new HashSet<>(Arrays.asList(
                context.getPackageName()+"_apps_to_hide.xml",context.getPackageName()+"_screen_reservation_state_v1.xml",
                "natro_service_mode_original.xml","natro_service_mode_self.xml"));
        Set<String> identityKeys=new HashSet<>(Arrays.asList(
                "phoneBleV2SwitchSnapshot","phoneBleV2HelperInstallationId","phoneBleV2AndroidInstallationId",
                "phoneBleV2EnrollmentRecord","phoneBleV2EnrollmentPendingRecord",
                "systemUiHideStockContentGlobally","systemUiOwnedHiddenSlots",
                "systemUiPendingActive","systemUiPendingEnabled","systemUiPendingRollbackNull","systemUiPendingRollbackRaw",
                "systemUiPendingDesiredNull","systemUiPendingDesiredRaw","systemUiPendingOwnedSlots",
                "launcherSystemStatusBarOriginalPolicy"));
        for(Map.Entry<String,File> file:BackupFiles.inventory(snapshot).entrySet()) {
            if(!isPreferences(file.getKey()))continue;
            File imported=file.getValue();String owner=file.getKey().substring(0,file.getKey().indexOf('/'));
            File local=BackupFiles.child(roots.get(owner),imported.getName());
            String canonicalName=imported.getName().endsWith(".bak")?imported.getName().substring(0,imported.getName().length()-4):imported.getName();
            if(boundStores.contains(canonicalName)) {
                BackupFiles.atomicCopy(imported,BackupFiles.child(snapshot,"ce_files/restored-device-records/"+source+"/"+owner+"/"+imported.getName()));
                if(local.isFile())BackupFiles.atomicCopy(local,imported);else BackupFiles.delete(imported);
            } else if(canonicalName.equals(context.getPackageName()+"_preferences.xml")) {
                Map<String,Object> values=BackupPreferencesXml.read(imported);
                Map<String,Object> current=local.isFile()?BackupPreferencesXml.read(local):Collections.emptyMap();
                Map<String,Object> original=new LinkedHashMap<>();
                for(String key:identityKeys) {
                    if(values.containsKey(key))original.put(key,values.get(key));
                    if(current.containsKey(key))values.put(key,current.get(key));else values.remove(key);
                }
                BackupPreferencesXml.write(BackupFiles.child(snapshot,"ce_files/restored-device-records/"+source+"/"+owner+"/identities.xml"),original);
                BackupPreferencesXml.write(imported,values);
            }
        }
    }

    private static String replaceUris(String key,String text,Map<String,String> replacements)throws Exception {
        if(key.toLowerCase(Locale.ROOT).endsWith("uri")&&replacements.containsKey(text))return replacements.get(text);
        String trimmed=text.trim();Object json=null;
        if(trimmed.startsWith("{"))json=new JSONObject(text);
        else if(trimmed.startsWith("["))json=new JSONArray(text);
        if(json==null)return text;
        return replaceJsonUris(json,replacements)?json.toString():text;
    }
    private static boolean replaceJsonUris(Object node,Map<String,String> replacements)throws Exception {
        boolean changed=false;
        if(node instanceof JSONObject) {
            JSONObject object=(JSONObject)node;Iterator<String> keys=object.keys();
            while(keys.hasNext()) {String key=keys.next();Object child=object.get(key);
                if(child instanceof String&&key.toLowerCase(Locale.ROOT).endsWith("uri")&&replacements.containsKey(child)) {
                    object.put(key,replacements.get(child));changed=true;
                } else changed|=replaceJsonUris(child,replacements);
            }
        }else if(node instanceof JSONArray)for(int i=0;i<((JSONArray)node).length();i++)changed|=replaceJsonUris(((JSONArray)node).get(i),replacements);
        return changed;
    }

    private static boolean documentKey(String key,String value) {return key.contains("Json")||value.trim().startsWith("{\"schema\"")||value.contains("\"presetId\"");}
    private static boolean isPreferences(String path) {
        return (path.startsWith("ce_prefs/")||path.startsWith("de_prefs/"))&&(path.endsWith(".xml")||path.endsWith(".xml.bak"));
    }
}
