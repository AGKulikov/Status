/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import okhttp3.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class YandexFilesTest {
    private Response response(Request request,int code,String body){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    private JSONObject requestBody(Request request)throws Exception {okio.Buffer buffer=new okio.Buffer();request.body().writeTo(buffer);return new JSONObject(buffer.readUtf8());}
    @Test public void listingUsesWebSessionProtocolAndRefreshesRejectedCsrfOnce()throws Exception {
        AtomicInteger pages=new AtomicInteger(),refreshes=new AtomicInteger();
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{Request r=chain.request();
            if(r.url().encodedPath().equals("/client/disk"))return response(r,200,"{\"sk\":\"key"+refreshes.incrementAndGet()+"\",\"idClient\":\"123\"}");
            try {
                JSONObject body=requestBody(r);assertEquals("mpfs/resources",body.getString("apiMethod"));assertEquals("/disk",body.getJSONObject("requestParams").getString("idContext"));
                if(pages.incrementAndGet()==1)return response(r,200,"{\"error\":{\"code\":\"CKEY\"}}");
                assertEquals("key2",body.getString("sk"));
                return response(r,200,"{\"resources\":[{\"path\":\"/disk\",\"type\":\"dir\"},{\"path\":\"/disk/Тест.txt\",\"type\":\"file\",\"meta\":{\"size\":9},\"mtime\":10}]}");
            }catch(Exception e){throw new IOException(e);}
        }).build();
        List<FileEntry> entries=new YandexFiles(client,"").list("/");assertEquals(1,entries.size());assertEquals("Тест.txt",entries.get(0).name);assertEquals(9,entries.get(0).size);assertEquals(2,refreshes.get());
    }
    @Test public void authFailureIsExplicitAndDoesNotRetryMutations()throws Exception {
        AtomicInteger requests=new AtomicInteger();OkHttpClient client=new OkHttpClient.Builder().addInterceptor(c->{requests.incrementAndGet();return response(c.request(),403,"private body");}).build();
        assertThrows(YandexFiles.LoginRequired.class,()->new YandexFiles(client,"").mkdir("/new"));assertEquals(1,requests.get());
    }
    @Test public void paginationDoesNotSkipFortiethItemWhenParentIncluded()throws Exception {
        List<Integer> offsets=new ArrayList<>();OkHttpClient client=new OkHttpClient.Builder().addInterceptor(c->{Request r=c.request();
            if(r.url().encodedPath().equals("/client/disk"))return response(r,200,"{\"sk\":\"test\"}");
            try{int offset=requestBody(r).getJSONObject("requestParams").getInt("offset");offsets.add(offset);JSONArray rows=new JSONArray();
                if(offset==0)rows.put(new JSONObject().put("path","/disk").put("type","dir"));
                for(int i=offset;i<Math.min(43,offset+(offset==0?39:40));i++)rows.put(new JSONObject().put("path","/disk/f"+i).put("type","file"));
                return response(r,200,new JSONObject().put("resources",rows).toString());
            }catch(Exception e){throw new IOException(e);}
        }).build();assertEquals(43,new YandexFiles(client,"").list("/").size());assertEquals(Arrays.asList(0,39),offsets);
    }
    @Test public void rejectsUnexpectedPrivatePathsAndUnsafeDownloadHosts()throws Exception {
        assertThrows(IOException.class,()->YandexFiles.privateEntry(new JSONObject("{\"path\":\"/disk/../secret\",\"type\":\"file\"}")));
        assertThrows(IOException.class,()->YandexFiles.privateEntry(new JSONObject("{\"path\":\"/trash/file\",\"type\":\"file\"}")));
        for(String url:Arrays.asList("http://disk.yandex.ru/a","https://disk.yandex.ru.evil.test/a","https://127.0.0.1/a","https://user@disk.yandex.ru/a"))assertThrows(IOException.class,()->YandexFiles.checkedTransferUrl(url));
        assertTrue(YandexFiles.checkedTransferUrl("https://downloader.disk.yandex.ru/a").isHttps());
    }
    @Test public void publicLinksAreParsedStrictlyAndHaveNoWriteAccess()throws Exception {
        FileLocation link=FileLocation.publicLink("https://disk.yandex.ru/d/a-B_3/Папка");assertEquals("/Папка",link.path);assertEquals("https://disk.yandex.ru/d/a-B_3",link.root);
        assertThrows(IOException.class,()->FileLocation.publicLink("https://disk.yandex.ru.evil.test/d/key"));
        assertFalse(new YandexFiles(new OkHttpClient(),link.root).writable());
        assertThrows(IOException.class,()->new YandexFiles(new OkHttpClient(),link.root).mkdir("/new"));
    }
    @Test public void malformedOrRepeatedPagesFailInsteadOfReturningTruncatedSuccess()throws Exception {
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(c->{Request r=c.request();if(r.url().encodedPath().equals("/client/disk"))return response(r,200,"{\"sk\":\"s\"}");
            JSONArray a=new JSONArray();for(int i=0;i<40;i++)try{a.put(new JSONObject().put("path","/disk/repeated").put("type","file"));}catch(Exception e){throw new IOException(e);}return response(r,200,"{\"resources\":"+a+"}");}).build();
        assertThrows(IOException.class,()->new YandexFiles(client,"").list("/"));
    }
}
