/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
public final class SettingsNestedSearch {
    public static final class Match {public final String activity,label;Match(String a,String l){activity=a;label=l;}}
    private static List<Match> entries;
    public static synchronized List<Match> search(Context context,String query) {
        if(entries==null) {
            entries=new ArrayList<>();
            try(InputStream input=context.getAssets().open("settings-search.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
                JSONArray array=new JSONArray(out.toString("UTF-8"));
                for(int i=0;i<array.length();i++){JSONObject item=array.getJSONObject(i);entries.add(new Match(item.getString("activity"),item.getString("text")));}
            }catch(Exception ignored){entries.clear();}
        }
        String[] words=normalize(query).split("\\s+");List<Match> result=new ArrayList<>();
        if(query.trim().length()<2)return result;
        for(Match entry:entries){String label=normalize(entry.label);boolean matches=true;for(String word:words)if(!label.contains(word))matches=false;if(matches){result.add(entry);if(result.size()==60)break;}}
        return result;
    }
    private static String normalize(String value){return value.toLowerCase(Locale.ROOT).replace('ё','е');}
}
