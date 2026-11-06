/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import okhttp3.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class YandexPassportTest {
    private Response reply(Request r,String text){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("test").body(ResponseBody.create(text,MediaType.get("application/json"))).build();}
    private JSONObject body(Request r)throws Exception {okio.Buffer b=new okio.Buffer();r.body().writeTo(b);return new JSONObject(b.readUtf8());}
    @Test public void nativeEmailChallengeFollowsReferenceSequenceWithoutPersistingPassword()throws Exception {
        List<String> calls=new ArrayList<>();final YandexPassport[] p={null};
        p[0]=new YandexPassport(new OkHttpClient.Builder().addInterceptor(chain->{Request r=chain.request();String path=r.url().encodedPath();calls.add(path);
            if(path.equals("/auth"))return reply(r,"window.__CSRF__='csrf'; {\"process_uuid\":\"process\",\"baseUrl\":\"/pwl-yandex\"}");
            try {
                assertEquals("csrf",r.header("x-csrf-token"));assertEquals("process",r.header("process-uuid"));JSONObject b=body(r);
                if(path.endsWith("multistep_start")){assertEquals("test",b.getString("login"));return reply(r,"{\"can_authorize\":true,\"auth_methods\":[\"password\"],\"track_id\":\"t\",\"location_id\":\"17\"}");}
                assertEquals("t",b.getString("track_id"));assertEquals("17",r.header("tractor-location"));
                if(path.endsWith("multistep_password")){assertEquals("secret",b.getString("password"));return reply(r,"{\"state\":\"auth_challenge\"}");}
                assertFalse(b.has("password"));
                if(path.endsWith("challenge_submit"))return reply(r,"{\"challengeType\":\"email_code\",\"hint\":[\"t***@example.test\"]}");
                if(path.endsWith("challenge_confirm_email_code"))assertEquals("12345",b.getString("code"));
                if(path.endsWith("challenge_commit"))assertEquals("email_code",b.getString("challenge"));
                if(path.endsWith("get_session"))p[0].saveFromResponse(r.url(),Collections.singletonList(new Cookie.Builder().domain("yandex.ru").path("/").name("Session_id").value("test-session").secure().build()));
                return reply(r,path.endsWith("check_session")?"{\"session_is_correct\":true}":"{}");
            }catch(Exception e){throw new IOException(e);}
        }));
        assertFalse(p[0].login("test","secret"));assertEquals("t***@example.test",p[0].hint);p[0].confirm("12345");
        assertEquals(9,calls.size());assertTrue(calls.get(7).endsWith("sessions/get_session"));assertTrue(calls.get(8).endsWith("sessions/check_session"));
        assertEquals(1,p[0].loadForRequest(HttpUrl.get("https://disk.yandex.ru/")).size());assertTrue(p[0].loadForRequest(HttpUrl.get("https://other.test/")).isEmpty());assertTrue(p[0].loadForRequest(HttpUrl.get("http://disk.yandex.ru/")).isEmpty());
    }
    @Test public void nativeErrorsNeverEchoSubmittedSecret()throws Exception {
        YandexPassport p=new YandexPassport(new OkHttpClient.Builder().addInterceptor(c->{Request r=c.request();return reply(r,r.url().encodedPath().equals("/auth")?"__CSRF__='c'; {\"process_uuid\":\"p\"}":"{\"status\":\"error\",\"errors\":[\"sensitive-secret\"]}");}));
        Exception error=assertThrows(IOException.class,()->p.login("name","sensitive-secret"));assertFalse(error.getMessage().contains("sensitive-secret"));
    }
}
