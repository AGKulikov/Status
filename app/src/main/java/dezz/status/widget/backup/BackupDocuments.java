/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Strict preflight for stored documents: malformed entries may never silently become defaults. */
public final class BackupDocuments {
    private BackupDocuments() {}
    public static void validate(String key,String raw)throws Exception {
        String text=raw.trim();
        boolean declared=key.contains("Json");
        if(text.isEmpty())return; // intentionally unset; declared defaults are snapshotted separately
        if(!text.startsWith("{")&&!text.startsWith("[")) {
            if(declared)throw new IOException("Некорректный документ настройки: "+key);
            return;
        }
        if(!declared)return;
        BackupJson.validate(text);
        Object value=text.startsWith("{")?new JSONObject(text):new JSONArray(text);
        if(key.endsWith("launcherBackdropsJson")) {
            JSONObject root=(JSONObject)value;
            if(root.getInt("version")!=1)throw new IOException("Unknown backdrop schema");
            uniqueObjects(root.getJSONArray("items"),true);
        }
        validateContainers(value,key,0);
    }
    private static void uniqueObjects(JSONArray items,boolean requireId)throws Exception {
        Set<String> ids=new HashSet<>();
        for(int index=0;index<items.length();index++) {
            Object value=items.get(index);
            if(!(value instanceof JSONObject))throw new IOException("Invalid document item");
            JSONObject item=(JSONObject)value;
            if(requireId||item.has("id")) {
                Object raw=item.get("id");
                if(!(raw instanceof String)||((String)raw).trim().isEmpty()||!ids.add((String)raw))
                    throw new IOException("Missing/duplicate document id");
            }
        }
    }
    private static void validateContainers(Object value,String key,int depth)throws Exception {
        if(depth>64)throw new IOException("Document is too deep");
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;Iterator<String> keys=object.keys();
            while(keys.hasNext()) {
                String field=keys.next();Object child=object.get(field);
                if(Arrays.asList("shortcuts","backdrops","panels","overlays","routes").contains(field)&&child instanceof JSONArray)
                    uniqueObjects((JSONArray)child,true);
                validateContainers(child,field,depth+1);
            }
        } else if(value instanceof JSONArray) {
            JSONArray values=(JSONArray)value;
            // Only item collections have uniqueness semantics; action arguments can legitimately repeat IDs.
            if(key.toLowerCase(Locale.ROOT).contains("shortcuts")||key.endsWith("FavoriteRoutesJson"))uniqueObjects(values,true);
            for(int index=0;index<values.length();index++)validateContainers(values.get(index),key,depth+1);
        }
    }
}
