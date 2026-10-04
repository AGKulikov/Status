/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Own window preferences only. No Yandex account, history, maps or private application files. */
final class NavigatorBackupBridge {
    static final Object LOCK=new Object();
    private static final Uri URI=Uri.parse("content://ru.natro.statuswidget.backup.navigator");
    private static final String PREFS="natro_floating_window_v3";
    private static Context context;
    private static boolean frozen;
    private static Handler worker;
    static void start(Context source) {
        synchronized(LOCK) {
            if(context!=null)return;context=source.getApplicationContext();
            frozen=hasJournal();
            HandlerThread thread=new HandlerThread("natro-window-backup");thread.start();worker=new Handler(thread.getLooper());
            context.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent i){worker.post(NavigatorBackupBridge::process);}
            },new IntentFilter("ru.natro.navigation.BACKUP_REQUEST_V1"));
            worker.post(new Runnable(){@Override public void run(){recover();worker.postDelayed(this,3000);}});
        }
    }
    static boolean frozen(){synchronized(LOCK){return frozen;}}
    private static boolean trusted() {
        try{return context.getPackageManager().checkSignatures("ru.natro.statuswidget",context.getPackageName())==PackageManager.SIGNATURE_MATCH;}
        catch(RuntimeException failure){return false;}
    }
    private static AtomicFile journal(){return new AtomicFile(new File(context.getNoBackupFilesDir(),"natro-window-backup-v1.json"));}
    private static boolean hasJournal(){File base=journal().getBaseFile();return base.exists()||new File(base.getPath()+".bak").exists();}
    private static JSONObject read()throws Exception {
        byte[] bytes=journal().readFully();if(bytes.length>512*1024)throw new IOException("Oversized window journal");
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static void write(JSONObject value)throws Exception {
        AtomicFile file=journal();FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}
        catch(Exception error){if(stream!=null)file.failWrite(stream);throw error;}
    }
    private static SharedPreferences prefs(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static JSONArray snapshot()throws Exception {
        if(!prefs().edit().commit())throw new IOException("Window flush failed");
        JSONArray result=new JSONArray();Map<String,?> values=new TreeMap<>(prefs().getAll());
        for(Map.Entry<String,?> entry:values.entrySet()) {
            Object value=entry.getValue();JSONObject item=new JSONObject().put("key",entry.getKey());
            if(value instanceof Boolean)item.put("type","boolean").put("value",value);
            else if(value instanceof Integer)item.put("type","int32").put("value",value);
            else if(value instanceof Long)item.put("type","int64").put("value",value.toString());
            else if(value instanceof Float)item.put("type","float32").put("value",Integer.toHexString(Float.floatToRawIntBits((Float)value)));
            else if(value instanceof String)item.put("type","string").put("value",value);
            else if(value instanceof Set)item.put("type","stringSet").put("value",new JSONArray(new TreeSet<>((Set<String>)value)));
            else throw new IOException("Unsupported window preference");
            result.put(item);
        }
        return result;
    }
    private static void apply(JSONArray values)throws Exception {
        if(values.length()>10000)throw new IOException("Oversized window preferences");
        SharedPreferences.Editor editor=prefs().edit().clear();Set<String> keys=new HashSet<>();
        for(int i=0;i<values.length();i++) {
            JSONObject item=values.getJSONObject(i);String key=item.getString("key");
            if(!keys.add(key))throw new IOException("Duplicate key");
            switch(item.getString("type")) {
                case "boolean":editor.putBoolean(key,item.getBoolean("value"));break;
                case "int32":editor.putInt(key,item.getInt("value"));break;
                case "int64":editor.putLong(key,Long.parseLong(item.getString("value")));break;
                case "float32":editor.putFloat(key,Float.intBitsToFloat((int)Long.parseLong(item.getString("value"),16)));break;
                case "string":editor.putString(key,item.getString("value"));break;
                case "stringSet":
                    Set<String> set=new TreeSet<>();JSONArray a=item.getJSONArray("value");
                    for(int n=0;n<a.length();n++)if(!set.add(a.getString(n)))throw new IOException("Duplicate set member");
                    editor.putStringSet(key,set);break;
                default:throw new IOException("Unknown preference type");
            }
        }
        if(!editor.commit())throw new IOException("Window commit failed");
        // Snapshot re-encodes every type, so readback is a semantic comparison, independent of key order.
        Map<String,String> expected=records(values),actual=records(snapshot());
        if(!expected.equals(actual))throw new IOException("Window readback mismatch");
    }
    private static Map<String,String> records(JSONArray values)throws Exception {
        Map<String,String> result=new TreeMap<>();
        for(int i=0;i<values.length();i++){JSONObject v=values.getJSONObject(i);result.put(v.getString("key"),v.getString("type")+":"+v.get("value"));}
        return result;
    }
    private static void process() {
        if(!trusted())return;
        Bundle request;
        try{request=context.getContentResolver().call(URI,"request",null,null);}catch(RuntimeException failure){return;}
        if(request==null||!request.containsKey("request"))return;
        Bundle reply=new Bundle();reply.putString("request",request.getString("request"));
        synchronized(LOCK) {
            try {
                String token=request.getString("token"),operation=request.getString("operation");
                if(token==null||!token.matches("[a-f0-9-]{36}"))throw new IOException("Invalid token");
                if("freeze".equals(operation)) {
                    if(hasJournal())throw new IOException("Previous window transaction needs recovery");
                    JSONArray values=snapshot();write(new JSONObject().put("token",token).put("before",values));frozen=true;
                    reply.putString("values",values.toString());
                } else if(hasJournal()) {
                    JSONObject state=read();if(!token.equals(state.getString("token")))throw new IOException("Token mismatch");
                    if("apply".equals(operation)) {
                        String raw=request.getString("values");if(raw==null||raw.length()>384*1024)throw new IOException("Invalid window snapshot");
                        apply(new JSONArray(raw));
                    } else if("commit".equals(operation)||"rollback".equals(operation)) {
                        if("rollback".equals(operation))apply(state.getJSONArray("before"));
                        journal().delete();frozen=false;
                    } else throw new IOException("Unknown operation");
                } else if(!"commit".equals(operation)&&!"rollback".equals(operation))throw new IOException("No window transaction");
                reply.putBoolean("ok",true);
            }catch(Exception error){reply.putBoolean("ok",false);}
        }
        try{context.getContentResolver().call(URI,"reply",null,reply);}catch(RuntimeException ignored){}
    }
    private static void recover() {
        synchronized(LOCK) {
            if(context==null||!frozen||!trusted())return;
            try {
                JSONObject state=read();Bundle decision=context.getContentResolver().call(URI,"decision",state.getString("token"),null);
                String value=decision==null?"pending":decision.getString("decision","pending");
                if("rollback".equals(value)||"commit".equals(value)) {
                    if("rollback".equals(value))apply(state.getJSONArray("before"));
                    journal().delete();frozen=false;
                }
            }catch(Exception ignored){} // Durable journal keeps geometry writes fenced until recovery.
        }
    }
}
