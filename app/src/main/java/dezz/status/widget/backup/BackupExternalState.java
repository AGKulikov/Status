/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import dezz.status.widget.adb.HudLcaPatch;
import dezz.status.widget.media.MediaInputPatch;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** Fixed, read-only system evidence. Restored originals stay inside Natro, never at a system path. */
final class BackupExternalState {
    private BackupExternalState(){}
    static JSONArray capture(File snapshot)throws Exception{
        Map<String,Boolean> sources=new LinkedHashMap<>();
        sources.put(MediaInputPatch.PATH,false);sources.put(MediaInputPatch.PATH+".natro-backup",true);
        for(String directory:new String[]{"/vendor/lib64/","/system/vendor/lib64/"}){
            sources.put(directory+HudLcaPatch.MODULE,false);sources.put(directory+HudLcaPatch.MODULE+".hudlab-original.bak",true);
        }
        sources.put(HudLcaPatch.BACKUP+"/original.so",true);
        JSONArray result=new JSONArray();int index=0;
        for(Map.Entry<String,Boolean> source:sources.entrySet())
            result.put(capture(new File(source.getKey()),source.getValue(),snapshot,"original-"+(index++)+".bin"));
        return result;
    }
    static JSONObject capture(File source,boolean original,File snapshot,String name)throws Exception{
        JSONObject record=new JSONObject().put("path",source.getAbsolutePath()).put("original",original);
        BasicFileAttributes attributes;
        try{attributes=Files.readAttributes(source.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException missing){return record.put("status","missing");}
        catch(IOException|SecurityException unavailable){return record.put("status","unreadable");}
        if(!attributes.isRegularFile()||attributes.isSymbolicLink())return record.put("status","not-regular");
        if(attributes.size()>64L*1024*1024)return record.put("status","too-large");
        String hash;
        try{hash=BackupFiles.sha256(source);}catch(IOException unreadable){return record.put("status","unreadable");}
        record.put("status","readable").put("bytes",attributes.size()).put("sha256",hash);
        if(original){
            String path="ce_files/restored-system-originals/"+hash+"-"+name;
            File destination=BackupFiles.child(snapshot,path);
            if(destination.exists()&&!hash.equals(BackupFiles.sha256(destination)))throw new IOException("Конфликт сохранённого системного оригинала");
            BackupFiles.atomicCopy(source,destination);
            if(!hash.equals(BackupFiles.sha256(destination))||!hash.equals(BackupFiles.sha256(source)))
                throw new IOException("Системный оригинал изменился во время копирования");
            record.put("archived",path);
        }
        return record;
    }
    static void validate(File snapshot,JSONArray records)throws Exception{
        if(records.length()>16)throw new IOException("Invalid external system inventory");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<records.length();i++){
            JSONObject item=records.getJSONObject(i);
            if(!seen.add(item.getString("path")))throw new IOException("Duplicate external system record");
            String state=item.getString("status");
            if(!Arrays.asList("missing","unreadable","not-regular","too-large","readable").contains(state))throw new IOException("Unknown external system state");
            if(state.equals("readable")&&(!item.getString("sha256").matches("[a-f0-9]{64}")||item.getLong("bytes")<0))throw new IOException("Invalid external system digest");
            if(item.has("archived")){
                String path=item.getString("archived");
                if(!item.getBoolean("original")||!state.equals("readable")||!path.startsWith("ce_files/restored-system-originals/"))throw new IOException("Invalid original destination");
                File file=BackupFiles.child(snapshot,path);
                if(file.length()!=item.getLong("bytes")||!BackupFiles.sha256(file).equals(item.getString("sha256")))throw new IOException("Системный оригинал отсутствует или повреждён в копии");
            }
        }
    }
}
