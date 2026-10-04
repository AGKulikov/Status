/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Calls production decoders/encoders without constructing stores, services, listeners or actions. */
public final class BackupDocumentDecoders {
    private static final String BASE="dezz.status.widget.";
    private BackupDocumentDecoders(){}
    public static JSONObject inspect(String rawKey,String raw)throws Exception {
        String key=rawKey.startsWith("passengerLauncher.")?rawKey.substring("passengerLauncher.".length()):rawKey;
        String text=raw.trim();if(text.isEmpty())return new JSONObject().put("unset",true);
        if(!text.startsWith("{")&&!text.startsWith("["))return new JSONObject().put("text",true);
        BackupJson.validate(text);Object source=text.startsWith("{")?new JSONObject(text):new JSONArray(text),decoded=null;
        String config=null,store=null;
        switch(key) {
            case "hudPanelConfigJson":config="hud.HudPanelConfig";break;
            case "dimMenuPanelConfigJson":config="dim.DimMenuPanelConfig";break;
            case "systemShadeConfigJson":config="shade.SystemShadeConfig";break;
            case "navigationIntegrationConfigJson":config="navigation.NavigationIntegrationConfig";break;
            case "launcherMediaConfigJson":store="launcher.media.MediaPanelConfigStore";break;
            case "launcherVehicleInfoConfigJson":store="launcher.vehicle.VehicleInfoPanelConfigStore";break;
            case "launcherInformationConfigJson":store="launcher.information.InformationPanelConfigStore";break;
            case "launcherNavigationConfigJson":store="launcher.navigation.NavigationPanelConfigStore";break;
            case "launcherClimateConfigJson":case "floatingClimateConfigJson":store="launcher.climate.ClimatePanelConfigStore";break;
        }
        if(config!=null) {
            Class<?> type=Class.forName(BASE+config);Object model=call(type,"fromJson",text);decoded=asJson(call(model,"toJson"));
        } else if(store!=null) {
            Class<?> type=Class.forName(BASE+store);Object model=call(type,"decode",text);decoded=asJson(call(type,"encode",model));
        } else if(key.equals("intentActionRulesJson")) {
            Class<?> type=Class.forName(BASE+"scenario.IntentActionRuleStore");decoded=asJson(call(type,"encode",call(type,"decode",text)));
        } else if(key.equals("localScenariosJson")) {
            decoded=objects((JSONArray)source,"scenario.Scenario","fromJson","toJson",false);
        } else if(key.equals("popupItemsJson")||key.equals("haMainBricksJson")) {
            decoded=objects((JSONArray)source,"popup.PopupItemConfig","fromJson","toJson",true);
        } else if(key.equals("popupOverlaysJson")) {
            decoded=objects((JSONArray)source,"popup.PopupOverlayConfig","fromJson","toJson",true);
        } else if(key.contains("ShortcutsJson")) {
            JSONObject root=(JSONObject)source;schema(root,1);JSONArray values=root.getJSONArray("items");
            decoded=new JSONObject().put("version",1).put("items",objects(values,"launcher.LauncherShortcutStore","fromJson","toJson",false));
        } else if(key.equals("launcherBackdropsJson")||key.equals("launcherHorizontalGroupsJson")) {
            JSONObject root=(JSONObject)source;schema(root,1);
            String type=key.equals("launcherBackdropsJson")?"launcher.LauncherBackdropStore":"launcher.LauncherHorizontalGroupStore";
            decoded=new JSONObject().put("version",1).put("items",objects(root.getJSONArray("items"),type,"decode","encode",false));
        } else if(key.equals("launcherActionsGridJson")) {
            Class<?> type=Class.forName(BASE+"launcher.LauncherActionsGridConfigStore");Object result=call(type,"decode",text);
            Field valid=result.getClass().getDeclaredField("valid");valid.setAccessible(true);
            if(!valid.getBoolean(result))throw new IOException("Action grid decoder rejected document");
            Field value=result.getClass().getDeclaredField("value");value.setAccessible(true);decoded=call(type,"encode",value.get(result));
        } else if(source instanceof JSONObject&&((JSONObject)source).has("presetId")&&((JSONObject)source).has("elements")) {
            Class<?> type=Class.forName(BASE+"instrument.InstrumentPanelConfig");decoded=call(call(type,"fromJson",source),"toJson");
        }
        JSONObject report=new JSONObject();JSONArray defaults=new JSONArray(),retained=new JSONArray();
        if(decoded!=null) {
            compare(source,decoded,key,defaults,retained);
            report.put("decoder","production").put("effective",decoded);
        } else {
            // Geometry, catalogs and historical documents keep their exact source; structural checks
            // below reject broken collections, IDs and scalar types before the app can ignore them.
            structural(source,key);report.put("decoder","structural").put("effective",source);
        }
        return report.put("defaults",defaults).put("retainedUnknownFields",retained);
    }
    private static JSONArray objects(JSONArray source,String name,String decode,String encode,boolean index)throws Exception {
        Class<?> type=Class.forName(BASE+name);JSONArray result=new JSONArray();Set<String> ids=new HashSet<>();
        for(int i=0;i<source.length();i++) {
            JSONObject item=source.getJSONObject(i);if(item.has("id")&&!ids.add(item.getString("id")))throw new IOException("Duplicate document id");
            Object model=index?call(type,decode,item,i):call(type,decode,item);
            if(model==null)throw new IOException("Production decoder skipped item "+i);
            Object encoded;
            try{encoded=call(model,encode);}catch(NoSuchMethodException instanceMissing){encoded=call(type,encode,model);}
            result.put(asJson(encoded));
        }
        return result;
    }
    private static void schema(JSONObject root,int version)throws Exception {if(root.getInt("version")!=version)throw new IOException("Unsupported document schema");}
    private static Object asJson(Object value)throws Exception {
        if(value instanceof String){String raw=((String)value).trim();return raw.startsWith("{")?new JSONObject(raw):new JSONArray(raw);}return value;
    }
    private static Object call(Object receiver,String name,Object... args)throws Exception {
        Class<?> type=receiver instanceof Class?(Class<?>)receiver:receiver.getClass();
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals(name)||method.getParameterTypes().length!=args.length)continue;
            Class<?>[] parameters=method.getParameterTypes();boolean compatible=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!parameters[i].isInstance(args[i])&&!(parameters[i]==int.class&&args[i] instanceof Integer))compatible=false;
            if(!compatible)continue;method.setAccessible(true);
            try{return method.invoke(receiver instanceof Class?null:receiver,args);}catch(InvocationTargetException error){throw new IOException("Декодер "+type.getSimpleName()+" отклонил документ",error.getCause());}
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static void compare(Object source,Object decoded,String path,JSONArray defaults,JSONArray retained)throws Exception {
        if(source instanceof JSONObject&&decoded instanceof JSONObject) {
            JSONObject a=(JSONObject)source,b=(JSONObject)decoded;Iterator<String> keys=a.keys();
            while(keys.hasNext()){String key=keys.next();if(b.has(key))compare(a.get(key),b.get(key),path+"."+key,defaults,retained);else retained.put(path+"."+key);}
            keys=b.keys();while(keys.hasNext()){String key=keys.next();if(!a.has(key))defaults.put(path+"."+key);}
        } else if(source instanceof JSONArray&&decoded instanceof JSONArray) {
            JSONArray a=(JSONArray)source,b=(JSONArray)decoded;
            if(a.length()!=b.length())throw new IOException("Декодер изменяет состав: "+path);
            for(int i=0;i<a.length();i++)compare(a.get(i),b.get(i),path+"["+i+"]",defaults,retained);
        } else if(source instanceof Number&&decoded instanceof Number) {
            if(new java.math.BigDecimal(source.toString()).compareTo(new java.math.BigDecimal(decoded.toString()))!=0)throw new IOException("Декодер заменяет значение: "+path);
        } else if(!Objects.equals(source,decoded))throw new IOException("Декодер заменяет тип или значение: "+path);
    }
    private static void structural(Object value,String path)throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;Iterator<String> keys=object.keys();
            while(keys.hasNext()){String key=keys.next();Object child=object.get(key);
                if(Arrays.asList("x","y","width","height","columns","rows","gapPx","columnSpan","rowSpan").contains(key)&&!(child instanceof Number))throw new IOException("Неверный тип геометрии: "+path+"."+key);
                structural(child,path+"."+key);}
        }else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;Set<String> ids=new HashSet<>();
            for(int i=0;i<a.length();i++){Object child=a.get(i);if(child instanceof JSONObject&&((JSONObject)child).has("id")&&!ids.add(((JSONObject)child).getString("id")))throw new IOException("Дубликат ID: "+path);structural(child,path+"["+i+"]");}}
    }
}
