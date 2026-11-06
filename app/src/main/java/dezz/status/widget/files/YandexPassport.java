/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import okhttp3.*;
import org.json.*;

/** Optional native Passport flow for old head-unit WebViews. Password and challenge are RAM only.
 * Matches the reference's multistep password + email-code flow. Other challenges use the web UI.
 */
public final class YandexPassport implements CookieJar {
    private static final String AUTH="https://passport.yandex.ru/auth?retpath=https%3A%2F%2Fdisk.yandex.ru%2Fclient%2Fdisk";
    private final List<Cookie> cookies=new ArrayList<>();
    private final Map<String,String> headers=new LinkedHashMap<>();
    private final OkHttpClient client;
    private String base,track;
    public String hint="";
    public YandexPassport(){this(new OkHttpClient.Builder());}
    YandexPassport(OkHttpClient.Builder builder){client=builder.cookieJar(this).followRedirects(false).followSslRedirects(false)
            .connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).build();}
    private static boolean host(String name){return name.equals("yandex.ru")||name.endsWith(".yandex.ru");}
    @Override public synchronized List<Cookie> loadForRequest(HttpUrl url){List<Cookie> result=new ArrayList<>();if(!url.isHttps()||!host(url.host()))return result;for(Cookie cookie:cookies)if(cookie.matches(url)&&cookie.expiresAt()>System.currentTimeMillis())result.add(cookie);return result;}
    @Override public synchronized void saveFromResponse(HttpUrl url,List<Cookie> values){if(!url.isHttps()||!host(url.host()))return;for(Cookie cookie:values)if(host(cookie.domain())){cookies.removeIf(old->old.name().equals(cookie.name())&&old.domain().equals(cookie.domain())&&old.path().equals(cookie.path()));cookies.add(cookie);}}
    public synchronized List<Cookie> cookies(){return new ArrayList<>(cookies);}
    private String request(Request request)throws Exception {
        try(Response response=client.newCall(request).execute()) {
            if(response.isRedirect())throw new IOException("Яндекс требует вход через веб-страницу");
            if(!response.isSuccessful()||response.body()==null)throw new IOException("Вход Яндекса: HTTP "+response.code());
            try(InputStream input=response.body().byteStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;){if(output.size()+n>4*1024*1024)throw new IOException("Неизвестный ответ страницы входа");output.write(buffer,0,n);}return output.toString("UTF-8");
            }
        }
    }
    private static String field(String text,String key) {
        Matcher match=Pattern.compile("[\"']"+Pattern.quote(key)+"[\"']\\s*:\\s*[\"']([^\"']*)[\"']").matcher(text);return match.find()?match.group(1):null;
    }
    private JSONObject call(String method,JSONObject params)throws Exception {
        Request.Builder request=new Request.Builder().url(base+"/api/passport/"+method).header("Referer",AUTH)
                .header("X-Requested-With","XMLHttpRequest");
        for(Map.Entry<String,String> header:headers.entrySet())request.header(header.getKey(),header.getValue());
        JSONObject result=new JSONObject(request(request.post(RequestBody.create(params.toString(),MediaType.get("application/json; charset=utf-8"))).build()));
        JSONArray errors=result.optJSONArray("errors");
        boolean error=(errors!=null&&errors.length()>0)||"error".equals(result.optString("status"))
                ||(result.has("error")&&!result.isNull("error")&&!Boolean.FALSE.equals(result.opt("error"))&&!"".equals(result.opt("error")));
        if(error)throw new IOException(method.contains("email_code")||method.contains("challenge")?"Код не принят. Проверьте код или откройте веб-вход.":"Яндекс не подтвердил вход. Проверьте логин и пароль или откройте веб-вход.");
        return result;
    }
    private JSONObject tracked()throws Exception {if(track==null||track.isEmpty())throw new IOException("Начните вход заново");return new JSONObject().put("track_id",track);}
    /** Returns true when complete; false means the account requested its email challenge. */
    public boolean login(String login,String password)throws Exception {
        headers.clear();track=null;hint="";cookies.clear();
        String page=request(new Request.Builder().url(AUTH).build());
        Matcher csrf=Pattern.compile("__CSRF__\\s*=\\s*[\"']([^\"']+)[\"']").matcher(page);
        String process=field(page,"process_uuid");if(!csrf.find()||process==null)throw new IOException("Страница входа изменилась. Используйте веб-вход.");
        String relative=field(page,"baseUrl");if(relative==null)relative="/pwl-yandex";
        if(!relative.matches("/[A-Za-z0-9_/-]*"))throw new IOException("Некорректный адрес входа");
        base="https://passport.yandex.ru"+relative;
        headers.put("x-csrf-token",csrf.group(1));headers.put("process-uuid",process);
        headers.put("tractor-location",Objects.toString(field(page,"location_id"),"0"));headers.put("tractor-non-proxy","1");headers.put("c11n",Objects.toString(field(page,"monitoring_group"),"default"));
        JSONObject start=call("auth/multistep_start",new JSONObject().put("login",login).put("retpath","https://disk.yandex.ru/client/disk"));
        if(!start.optBoolean("can_authorize",false))throw new IOException("Яндекс не нашёл этот аккаунт");
        JSONArray methods=start.optJSONArray("auth_methods");if(methods!=null&&!methods.toString().contains("\"password\""))throw new IOException("Аккаунт требует другой способ входа. Используйте веб-вход.");
        track=start.getString("track_id");location(start);
        JSONObject authenticated=call("auth/multistep_password",tracked().put("password",password));location(authenticated);
        String state=authenticated.optString("state","");
        if(state.isEmpty()){finish();return true;}
        if(!state.equals("auth_challenge"))throw new IOException("Завершите дополнительную проверку через веб-вход");
        JSONObject challenge=call("auth/challenge_submit",tracked());
        if(!"email_code".equals(challenge.optString("challengeType")))throw new IOException("Эту проверку нужно пройти через веб-вход");
        JSONArray hints=challenge.optJSONArray("hint");hint=hints==null?challenge.optString("hint",""):hints.optString(0,"");
        call("auth/challenge_send_email_code",tracked());return false;
    }
    private void location(JSONObject result){String value=result.optString("location_id","");if(!value.isEmpty())headers.put("tractor-location",value);}
    public void confirm(String code)throws Exception {
        call("auth/challenge_confirm_email_code",tracked().put("code",code));
        JSONObject committed=call("auth/challenge_commit",tracked().put("challenge","email_code"));
        if(!committed.optString("state","").isEmpty())throw new IOException("Требуется дополнительная проверка через веб-вход");finish();
    }
    private void finish()throws Exception {
        call("sessions/get_session",tracked());
        JSONObject state=call("sessions/check_session",tracked());
        if(state.has("session_is_correct")&&!state.optBoolean("session_is_correct"))throw new IOException("Яндекс не подтвердил сессию");
        if(loadForRequest(HttpUrl.get("https://disk.yandex.ru/")).isEmpty())throw new IOException("Яндекс не выдал сессию Диска");
    }
}
