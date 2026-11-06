/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import dezz.status.widget.YandexDiskSession;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import okhttp3.*;
import okio.BufferedSink;
import org.json.*;

/** Independently implemented protocol adapter, based on the supplied YD Browser 0.5 behavior.
 * Personal files use the signed-in web session; public links use the documented public REST API.
 * Web endpoints are not a stable public API: format/auth changes are reported, never hidden.
 */
public final class YandexFiles implements FileBackend {
    private static final String HOME="https://disk.yandex.ru/client/disk";
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
    private static final Pattern SK=Pattern.compile("\"sk\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CLIENT=Pattern.compile("\"idClient\"\\s*:\\s*\"([0-9]+)\"");
    private final OkHttpClient api, transfer;
    private final String publicKey;
    private String sk,connection=new java.math.BigInteger(73,new java.security.SecureRandom()).toString();
    public static final class LoginRequired extends IOException {
        public LoginRequired(){super("Сессия Яндекса истекла. Нажмите «Войти в Яндекс».");}
    }
    public YandexFiles(YandexDiskSession session,String publicKey) {
        this(new OkHttpClient.Builder().cookieJar(session==null?CookieJar.NO_COOKIES:session)
                .connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build(),publicKey);
    }
    YandexFiles(OkHttpClient client,String publicKey) {
        this(client,new OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
                .readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build(),publicKey);
    }
    YandexFiles(OkHttpClient client,OkHttpClient transferClient,String publicKey) {
        api=client;transfer=transferClient;
        this.publicKey=publicKey==null?"":publicKey;
    }
    @Override public String identity(){return publicKey.isEmpty()?"yandex:personal":"yandex:public:"+publicKey;}
    @Override public boolean writable(){return publicKey.isEmpty();}
    private Request.Builder request(String url){return new Request.Builder().url(url).header("Accept","application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With","XMLHttpRequest").header("Referer",HOME).header("X-Retpath-Y",HOME);}
    private String text(Request request)throws IOException {
        try(Response response=api.newCall(request).execute()) {
            if(response.code()==401||response.code()==403||response.isRedirect())throw new LoginRequired();
            if(!response.isSuccessful())throw new IOException("Яндекс Диск: HTTP "+response.code());
            if(response.body()==null)throw new IOException("Яндекс вернул пустой ответ");
            try(InputStream input=response.body().byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))!=-1;) {
                    if(out.size()+count>16*1024*1024)throw new IOException("Ответ Яндекса слишком большой");out.write(buffer,0,count);
                }
                return out.toString("UTF-8");
            }
        }
    }
    private void refresh()throws Exception {
        String html=text(request(HOME).build());Matcher key=SK.matcher(html);
        if(!key.find())throw new LoginRequired();sk=new JSONObject("{\"v\":\""+key.group(1)+"\"}").getString("v");
        Matcher client=CLIENT.matcher(html);if(client.find())connection=client.group(1);
    }
    synchronized JSONObject call(String method,JSONObject params)throws Exception {
        if(!writable())throw new IOException("Публичная ссылка доступна только для чтения");
        if(sk==null)refresh();
        for(int attempt=0;attempt<2;attempt++) {
            JSONObject payload=new JSONObject().put("sk",sk).put("connection_id",connection).put("apiMethod",method).put("requestParams",params);
            String body=text(request("https://disk.yandex.ru/models-v2?m="+method).post(RequestBody.create(payload.toString(),JSON)).build()).trim();
            JSONObject result=body.startsWith("[")?new JSONObject().put("items",new JSONArray(body)):new JSONObject(body);
            JSONObject error=result.optJSONObject("error");
            if(error==null) {
                if(result.has("error")&&!result.isNull("error")&&!Boolean.FALSE.equals(result.opt("error")))throw new IOException("Яндекс отклонил операцию");
                return result;
            }
            String code=error.optString("code",error.optString("id",""));
            if("CKEY".equals(code)&&attempt==0){refresh();continue;}
            if("CKEY".equals(code))throw new LoginRequired();
            throw new IOException("Яндекс отклонил операцию (код "+code.replaceAll("[^A-Za-z0-9_-]","")+")");
        }
        throw new LoginRequired();
    }
    private JSONObject publicGet(String endpoint,String path,int offset)throws Exception {
        HttpUrl url=HttpUrl.get("https://cloud-api.yandex.net/v1/disk/public/resources"+endpoint).newBuilder()
                .addQueryParameter("public_key",publicKey).addQueryParameter("path",FilePaths.normalize(path))
                .addQueryParameter("offset",String.valueOf(offset)).addQueryParameter("limit","200").build();
        return new JSONObject(text(new Request.Builder().url(url).build()));
    }
    static FileEntry privateEntry(JSONObject value)throws Exception {
        String full=value.getString("path");
        if(!full.equals("/disk")&&!full.startsWith("/disk/"))throw new IOException("Некорректный путь Яндекс Диска");
        String path=full.equals("/disk")?"/":full.substring(5);FilePaths.normalize(path);
        String name=value.optString("name",FilePaths.leaf(path));if(!path.equals("/"))FilePaths.name(name);
        JSONObject meta=value.optJSONObject("meta");
        String type=value.getString("type");if(!type.equals("file")&&!type.equals("dir"))throw new IOException("Неизвестный тип файла Яндекса");
        return new FileEntry(path,name,type.equals("dir"),meta==null?-1:meta.optLong("size",-1),value.optLong("mtime",0)*1000);
    }
    static FileEntry publicEntry(JSONObject value)throws Exception {
        String path=FilePaths.normalize(value.getString("path"));String name=FilePaths.name(value.getString("name"));
        String type=value.getString("type");if(!type.equals("dir")&&!type.equals("file"))throw new IOException("Неизвестный тип файла Яндекса");
        long modified=0;try{modified=java.time.OffsetDateTime.parse(value.optString("modified")).toInstant().toEpochMilli();}catch(Exception ignored){}
        return new FileEntry(path,name,type.equals("dir"),value.optLong("size",-1),modified);
    }
    @Override public List<FileEntry> list(String path)throws Exception {
        FilePaths.normalize(path);List<FileEntry> result=new ArrayList<>();Set<String> seen=new HashSet<>();int offset=0;
        for(int page=0;page<10000;page++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Операция отменена");
            JSONArray items;int total=Integer.MAX_VALUE;
            if(writable()) {
                JSONObject params=new JSONObject().put("idContext",disk(path)).put("amount",40).put("offset",offset).put("sort","name").put("order","1");
                if(offset==0)params.put("withParent","1");
                items=call("mpfs/resources",params).getJSONArray("resources");
            } else {
                JSONObject response=publicGet("",path,offset);
                if("file".equals(response.optString("type"))) {result.add(publicEntry(response));return result;}
                JSONObject embedded=response.getJSONObject("_embedded");items=embedded.getJSONArray("items");total=embedded.getInt("total");
            }
            int added=0;
            for(int i=0;i<items.length();i++) {
                FileEntry item=writable()?privateEntry(items.getJSONObject(i)):publicEntry(items.getJSONObject(i));
                if(item.path.equals(path))continue;
                if(!FilePaths.parent(item.path).equals(path)||!FilePaths.leaf(item.path).equals(item.name))throw new IOException("Яндекс вернул некорректный список файлов");
                if(seen.add(item.path)){result.add(item);added++;}
            }
            if(items.length()<(writable()?40:200)||offset+added>=total)return result;
            if(added==0)throw new IOException("Яндекс повторяет страницу каталога");
            offset+=added;
        }
        throw new IOException("Каталог слишком большой");
    }
    private static String disk(String path)throws IOException {return "/disk"+(FilePaths.normalize(path).equals("/")?"":path);}
    @Override public FileEntry stat(String path)throws Exception {
        FilePaths.normalize(path);
        if(path.equals("/"))return writable()?new FileEntry("/","Яндекс Диск",true,0,0):publicEntry(publicGet("","/",0));
        for(FileEntry item:list(FilePaths.parent(path)))if(item.path.equals(path))return item;return null;
    }
    public static HttpUrl checkedTransferUrl(String value)throws IOException {
        HttpUrl url=HttpUrl.parse(value.startsWith("//")?"https:"+value:value);
        if(url==null||!url.isHttps()||!url.username().isEmpty()||!url.password().isEmpty()||url.port()!=443)
            throw new IOException("Яндекс вернул недопустимый адрес передачи");
        String host=url.host();boolean allowed=false;
        for(String suffix:new String[]{"yandex.ru","yandex.net","yandex.com","yandexcloud.net"})
            if(host.equals(suffix)||host.endsWith("."+suffix))allowed=true;
        if(!allowed)throw new IOException("Адрес передачи не принадлежит хранилищу Яндекса");return url;
    }
    @Override public InputStream read(String path)throws Exception {
        String url;
        if(writable()) {
            JSONObject result=call("mpfs/url",new JSONObject().put("path",disk(path)));
            url=result.optString("file",result.optString("folder",""));
        } else url=publicGet("/download",path,0).getString("href");
        HttpUrl target=checkedTransferUrl(url);
        for(int hop=0;hop<6;hop++) {
            Response response=transfer.newCall(new Request.Builder().url(target).header("Accept-Encoding","identity").build()).execute();
            if(response.isRedirect()) {
                String location=response.header("Location");HttpUrl next=location==null?null:target.resolve(location);response.close();
                if(next==null)throw new IOException("Некорректное перенаправление");target=checkedTransferUrl(next.toString());continue;
            }
            if(!response.isSuccessful()||response.body()==null){int code=response.code();response.close();throw new IOException("Скачивание: HTTP "+code);}
            return new FilterInputStream(response.body().byteStream()) {
                @Override public void close()throws IOException {try{super.close();}finally{response.close();}}
            };
        }
        throw new IOException("Слишком много перенаправлений");
    }
    @Override public void mkdir(String path)throws Exception {call("mpfs/mkdir",new JSONObject().put("path",disk(path)).put("force",0));}
    @Override public void delete(String path)throws Exception {
        delete(path,new FileOperations.Control(null));
    }
    @Override public void delete(String path,FileOperations.Control control)throws Exception {
        control.check();
        if(path.equals("/"))throw new IOException("Нельзя удалить корень Диска");
        JSONObject result=call("mpfs/bulk-async-delete",new JSONObject().put("operations",new JSONArray().put(new JSONObject().put("src",disk(path)))))
                .getJSONArray("items").getJSONObject(0);
        if(result.has("error")&&!result.isNull("error"))throw new IOException("Яндекс не подтвердил удаление");
        String oid=result.optString("oid","");if(!oid.isEmpty())await(oid,control);
    }
    @Override public void write(String path,InputStream input,long length,FileOperations.Control control)throws Exception {
        if(length<0)throw new IOException("Для загрузки на Диск нужен известный размер файла");
        control.check();
        JSONObject store=call("mpfs/store",new JSONObject().put("path",disk(path)).put("force",0).put("size",length));
        HttpUrl url=checkedTransferUrl(store.getString("upload_url"));
        RequestBody body=new RequestBody() {
            @Override public MediaType contentType(){return MediaType.get("application/octet-stream");}
            @Override public long contentLength(){return length;}
            @Override public boolean isOneShot(){return true;}
            @Override public void writeTo(BufferedSink sink)throws IOException {
                long copied=FileOperations.pipe(input,sink.outputStream(),control);
                if(copied!=length)throw new IOException("Размер исходного файла изменился");
            }
        };
        try(Response response=transfer.newCall(new Request.Builder().url(url).header("X-Disk-Uploader-Wait-Complete-Upload","false").put(body).build()).execute()) {
            if(!response.isSuccessful())throw new IOException("Загрузка на Диск: HTTP "+response.code());
        }
        String oid=store.optString("oid","");if(!oid.isEmpty())await(oid,control);
        FileEntry check=stat(path);if(check==null||check.directory||check.size!=length)throw new IOException("Яндекс не подтвердил полный размер загрузки; исходник сохранён");
    }
    private void await(String oid,FileOperations.Control control)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
        while(System.nanoTime()<deadline) {
            control.check();JSONObject status=call("mpfs/bulk-operation-status",new JSONObject().put("oids",new JSONArray().put(oid))).optJSONObject(oid);
            if(status==null)throw new IOException("Яндекс не вернул состояние операции");
            String state=status.optString("state",status.optString("status",""));
            if(state.equals("DONE")||state.equals("COMPLETED"))return;
            if(state.equals("FAILED")||(status.has("error")&&!status.isNull("error")))throw new IOException("Операция на Диске не завершена");
            Thread.sleep(1000);
        }
        throw new IOException("Яндекс ещё выполняет операцию. Обновите папку перед повтором; исходник сохранён.");
    }
}
