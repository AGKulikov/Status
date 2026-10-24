package dezz.status.widget.launcher;
import android.app.Application;
import android.content.ComponentName;
import android.content.pm.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class YandexBrowserServiceResolverTest {
 private ResolveInfo service(String pkg,String name,boolean exported){
  ResolveInfo r=new ResolveInfo();r.serviceInfo=new ServiceInfo();r.serviceInfo.packageName=pkg;r.serviceInfo.name=name;
  r.serviceInfo.enabled=true;r.serviceInfo.exported=exported;return r;
 }
 @Test public void renamedExportedBrowserIsResolvedOnlyInExactPlayer(){
  assertEquals(new ComponentName("ru.yandex.music","new.Browser"),YandexBrowserServiceResolver.select(Arrays.asList(
   service("other.player","other.Browser",true),service("ru.yandex.music","private.Browser",false),service("ru.yandex.music","new.Browser",true))));
 }
 @Test public void missingProtectedAndAmbiguousServicesAreNotStarted(){
  assertNull(YandexBrowserServiceResolver.select(Collections.emptyList()));
  ResolveInfo p=service("ru.yandex.music","protected.Browser",true);p.serviceInfo.permission="private.permission";
  assertNull(YandexBrowserServiceResolver.select(Arrays.asList(p)));
  assertNull(YandexBrowserServiceResolver.select(Arrays.asList(service("ru.yandex.music","one",true),service("ru.yandex.music","two",true))));
 }
 @Test public void coldReceiverRetriesAreBoundedAndNeverRaceBrowser(){
  assertTrue(YandexColdSessionPolicy.retryReceiver(true,false,false,false,1));
  assertFalse(YandexColdSessionPolicy.retryReceiver(true,true,false,false,1));
  assertFalse(YandexColdSessionPolicy.retryReceiver(true,false,true,false,1));
  assertFalse(YandexColdSessionPolicy.retryReceiver(true,false,false,true,1));
  assertFalse(YandexColdSessionPolicy.retryReceiver(true,false,false,false,5));
 }
}
