/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;

/** Reads declared defaults through the real getters against an empty, isolated preference view. */
public final class PortableBackupDefaults {
    private PortableBackupDefaults() {}
    public static Map<String,Object> capture(Context source)throws Exception {
        SharedPreferences empty=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    String name=method.getName();
                    if(name.equals("getAll"))return Collections.emptyMap();
                    if(name.equals("contains"))return false;
                    if(name.startsWith("get")&&args!=null&&args.length==2)return args[1];
                    if(name.equals("edit"))throw new IllegalStateException("Default inspection attempted a write");
                    return null;
                });
        Context isolated=new ContextWrapper(source) {
            @Override public Context getApplicationContext(){return this;}
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return empty;}
        };
        Map<String,Object> values=new TreeMap<>();
        collect(new Preferences(isolated,false),values,new IdentityHashMap<>(),0);
        collect(Preferences.forLauncher(isolated,true,false),values,new IdentityHashMap<>(),0);
        return values;
    }
    private static void collect(Object object,Map<String,Object> values,IdentityHashMap<Object,Boolean> seen,int depth)throws Exception {
        if(object==null||depth>5||seen.put(object,true)!=null)return;
        for(Field field:object.getClass().getFields()) {
            if(Modifier.isStatic(field.getModifiers()))continue;
            Object value=field.get(object);
            if(value instanceof Preferences.Preference) {
                Preferences.Preference preference=(Preferences.Preference)value;
                Object defaultValue=value.getClass().getMethod("get").invoke(value);
                if(defaultValue!=null)values.put(preference.key,defaultValue);
            } else if(value!=null&&value.getClass().getName().startsWith("dezz.status.widget.Preferences$"))collect(value,values,seen,depth+1);
        }
    }
}
