/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.SharedPreferences;
import dezz.status.widget.backup.BackupPreferences;
import java.io.IOException;
import java.util.*;
import okhttp3.*;
import org.json.JSONArray;

/** One encrypted, backup-aware session; never stores the account password or logs cookies. */
public final class YandexDiskSession implements CookieJar {
    private final Context context;
    private final SharedPreferences preferences;
    private final List<Cookie> cookies=new ArrayList<>();
    private boolean revoked;
    private static YandexDiskSession instance;
    public static synchronized YandexDiskSession get(Context context)throws IOException {
        if(instance==null)instance=new YandexDiskSession(context.getApplicationContext());return instance;
    }
    private YandexDiskSession(Context context)throws IOException {
        this.context=context;preferences=BackupPreferences.open(context,"natro_file_manager_v1",0);
        String stored=preferences.getString("yandexSession","");
        try {
            if(!stored.isEmpty()) {
                if(!stored.startsWith("v1:"))throw new IOException("Некорректный формат сессии");
                JSONArray values=new JSONArray(SecretStore.decrypt(context,stored));
                for(int i=0;i<values.length();i++) {
                    Cookie cookie=Cookie.parse(HttpUrl.get("https://disk.yandex.ru/"),values.getString(i));
                    if(cookie==null||!allowed(cookie.domain()))throw new IOException("Некорректная сессия Яндекса");cookies.add(cookie);
                }
            }
        }catch(Exception failure){throw new IOException("Не удалось прочитать сессию Яндекса. Отключите аккаунт и войдите повторно.");}
    }
    private static boolean allowed(String host){return host.equals("yandex.ru")||host.endsWith(".yandex.ru");}
    @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> result=new ArrayList<>();if(revoked||!url.isHttps()||!allowed(url.host()))return result;
        for(Cookie cookie:cookies)if(cookie.expiresAt()>System.currentTimeMillis()&&cookie.matches(url))result.add(cookie);return result;
    }
    @Override public synchronized void saveFromResponse(HttpUrl url,List<Cookie> received) {
        if(revoked||!url.isHttps()||!allowed(url.host()))return;
        for(Cookie cookie:received)if(allowed(cookie.domain())) {
            cookies.removeIf(old->old.name().equals(cookie.name())&&old.domain().equals(cookie.domain())&&old.path().equals(cookie.path()));
            if(cookie.expiresAt()>System.currentTimeMillis())cookies.add(cookie);
        }
        try{save();}catch(Exception failure){throw new IllegalStateException("Не удалось сохранить сессию Яндекса");}
    }
    public synchronized void importWebSession(String header)throws Exception {
        requireActive();
        if(header==null||header.length()>65536)throw new IOException("Сессия Яндекса не получена");
        cookies.clear();
        for(String part:header.split(";")) {
            int equals=part.indexOf('=');if(equals<1)continue;
            cookies.add(new Cookie.Builder().name(part.substring(0,equals).trim()).value(part.substring(equals+1).trim())
                    .hostOnlyDomain("disk.yandex.ru").path("/").secure().httpOnly().build());
        }
        save();
    }
    private void save()throws Exception {
        JSONArray values=new JSONArray();for(Cookie cookie:cookies)values.put(cookie.toString());
        if(!preferences.edit().putString("yandexSession",SecretStore.encrypt(context,values.toString())).commit())
            throw new IOException("Не удалось сохранить вход");
    }
    public synchronized void replace(List<Cookie> values)throws Exception {
        requireActive();
        cookies.clear();for(Cookie cookie:values)if(allowed(cookie.domain())&&cookie.expiresAt()>System.currentTimeMillis())cookies.add(cookie);save();
    }
    public synchronized boolean connected(){return !loadForRequest(HttpUrl.get("https://disk.yandex.ru/")).isEmpty();}
    private void requireActive()throws IOException {if(revoked||Thread.currentThread().isInterrupted())throw new IOException("Вход отменён");}
    private synchronized void revoke(){revoked=true;cookies.clear();}
    public static synchronized void disconnect(Context context)throws IOException {
        if(instance!=null)instance.revoke();instance=null;
        if(!BackupPreferences.open(context,"natro_file_manager_v1",0).edit().remove("yandexSession").commit())throw new IOException("Не удалось удалить сессию");
    }
}
