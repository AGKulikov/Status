/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import dezz.status.widget.backup.BackupFiles;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import org.json.JSONObject;

/** Small private resources staged with their metadata. APNs bytes are already encrypted. */
public final class SettingsFileChange {
    private static final int LIMIT=64*1024;
    public final String path;
    final byte[] before,after;
    SettingsFileChange(String path,byte[] before,byte[] after)throws IOException{
        BackupFiles.safePath(path);this.path=path;this.before=copy(before);this.after=copy(after);
    }
    private static byte[] copy(byte[] bytes)throws IOException{
        if(bytes!=null&&bytes.length>LIMIT)throw new IOException("Settings resource too large");
        return bytes==null?null:bytes.clone();
    }
    public static SettingsFileChange capture(Context context,String path,byte[] after)throws IOException{
        return new SettingsFileChange(path,read(context,path),after);
    }
    public static byte[] read(Context context,String path)throws IOException{
        File file=resolve(context,path);
        if(!file.exists())return null;
        if(!file.isFile())throw new IOException("Settings resource is not a regular file");
        return BackupFiles.read(file,LIMIT);
    }
    static File resolve(Context context,String path)throws IOException{
        BackupFiles.safePath(path);int split=path.indexOf('/');
        if(split<0)throw new IOException("Missing settings resource namespace");
        String domain=path.substring(0,split);Context app=context.getApplicationContext();
        if(app.isDeviceProtectedStorage())throw new IOException("Settings resources require a CE application context");
        Context storage=domain.startsWith("de_")?app.createDeviceProtectedStorageContext():app;
        File root;
        switch(domain){
            case "ce_files":case "de_files":root=storage.getFilesDir();break;
            case "ce_no_backup":case "de_no_backup":root=storage.getNoBackupFilesDir();break;
            default:throw new IOException("Unknown settings resource namespace");
        }
        String relative=path.substring(split+1);
        if(domain.equals("de_no_backup")&&(relative.equals("natro-backup")||relative.startsWith("natro-backup/")))
            throw new IOException("Settings cannot replace their maintenance journal");
        return BackupFiles.child(root,relative);
    }
    boolean dirty(){return !Arrays.equals(before,after);}
    boolean matchesBefore(Context context)throws IOException{return Arrays.equals(before,read(context,path));}
    void write(Context context,boolean restore)throws IOException{
        File target=resolve(context,path);byte[] bytes=restore?before:after;
        if(bytes==null)BackupFiles.delete(target);else BackupFiles.atomicWrite(target,bytes);
        if(!Arrays.equals(bytes,read(context,path)))throw new IOException("Settings resource read-back failed");
    }
    JSONObject encode()throws Exception{
        return new JSONObject().put("path",path).put("before",encodeBytes(before)).put("after",encodeBytes(after));
    }
    static SettingsFileChange decode(JSONObject object)throws Exception{
        return new SettingsFileChange(object.getString("path"),decodeBytes(object.get("before")),decodeBytes(object.get("after")));
    }
    private static Object encodeBytes(byte[] bytes){return bytes==null?JSONObject.NULL:Base64.getEncoder().encodeToString(bytes);}
    private static byte[] decodeBytes(Object value)throws IOException{
        if(value==JSONObject.NULL)return null;
        if(!(value instanceof String)||((String)value).length()>4*((LIMIT+2)/3))throw new IOException("Invalid settings resource data");
        try{return copy(Base64.getDecoder().decode((String)value));}catch(IllegalArgumentException invalid){throw new IOException("Invalid settings resource encoding",invalid);}
    }
}
