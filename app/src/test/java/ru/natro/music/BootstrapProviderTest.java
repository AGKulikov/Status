package ru.natro.music;
import android.app.Application;
import android.content.pm.*;
import android.os.Process;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class BootstrapProviderTest {
 private void install(String name,int uid,String signature){
  PackageInfo p=new PackageInfo();p.packageName=name;p.signatures=new Signature[]{new Signature(signature)};
  p.applicationInfo=new ApplicationInfo();p.applicationInfo.packageName=name;p.applicationInfo.uid=uid;
  Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(p);
 }
 private void endpoints(boolean legacyEnabled,boolean modernEnabled){
  PackageInfo p=new PackageInfo();p.packageName="ru.yandex.music";p.applicationInfo=new ApplicationInfo();
  p.applicationInfo.packageName=p.packageName;p.applicationInfo.enabled=true;
  p.services=new ServiceInfo[2];String[] names={"ru.yandex.music.common.media.mediabrowser.MusicBrowserService","com.yandex.music.shared.media.session.session.Media3LibraryService"};
  for(int i=0;i<2;i++){ServiceInfo v=new ServiceInfo();v.packageName=p.packageName;v.name=names[i];v.enabled=i==0?legacyEnabled:modernEnabled;v.applicationInfo=p.applicationInfo;v.permission="same.uid.permission";p.services[i]=v;}
  Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(p);
 }
 @Test public void bothNativeServicesAreOrderedInsteadOfWaitingForOne(){
  endpoints(true,true);java.util.List<android.content.ComponentName> found=BootstrapProvider.resolveEndpoints(RuntimeEnvironment.getApplication().getPackageManager());
  assertEquals(2,found.size());assertTrue(found.get(0).getClassName().endsWith("Media3LibraryService"));
 }
 @Test public void disabledModernServiceLeavesLegacyUsable(){
  endpoints(true,false);java.util.List<android.content.ComponentName> found=BootstrapProvider.resolveEndpoints(RuntimeEnvironment.getApplication().getPackageManager());
  assertEquals(1,found.size());assertTrue(found.get(0).getClassName().endsWith("MusicBrowserService"));
 }
 @Test public void onlyNamedClientWithMatchingSignatureMayPrepare(){
  install("ru.yandex.music",20001,"1234");install("ru.natro.statuswidget",20002,"1234");install("other.client",20003,"1234");
  assertTrue(BootstrapProvider.authorized(RuntimeEnvironment.getApplication(),20002));
  assertFalse(BootstrapProvider.authorized(RuntimeEnvironment.getApplication(),20003));
  assertFalse(BootstrapProvider.authorized(RuntimeEnvironment.getApplication(),99999));
 }
 @Test public void impersonatingPackageWithDifferentSignatureIsRejected(){
  install("ru.yandex.music",20001,"1234");install("ru.natro.statuswidget",20002,"5678");
  assertFalse(BootstrapProvider.authorized(RuntimeEnvironment.getApplication(),20002));
 }
 @Test public void missingPlayerFailsClosed(){
  install("ru.natro.statuswidget",20002,"1234");
  assertFalse(BootstrapProvider.authorized(RuntimeEnvironment.getApplication(),20002));
 }
 @Test public void transientConnectionFailureRetriesOnlyUntilOriginalDeadline(){
  BootstrapProvider provider=new BootstrapProvider();
  BootstrapProvider.Connection c=provider.new Connection(RuntimeEnvironment.getApplication(),"boot",
   android.os.SystemClock.elapsedRealtime()+1000,new android.os.ResultReceiver(null));
  c.onConnectionFailed();assertFalse(c.closed);
  Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(999));
  assertFalse(c.closed);
  Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
  assertTrue(c.closed);
 }
 @Test public void deliveredTokenCannotStartAnotherPreparationOnDisconnect(){
  BootstrapProvider provider=new BootstrapProvider();
  BootstrapProvider.Connection c=provider.new Connection(RuntimeEnvironment.getApplication(),"boot",
   android.os.SystemClock.elapsedRealtime()+1000,new android.os.ResultReceiver(null));
  c.delivered=true;c.onConnectionSuspended();assertTrue(c.closed);
 }
}
