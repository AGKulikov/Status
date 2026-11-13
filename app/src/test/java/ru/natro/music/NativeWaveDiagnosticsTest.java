package ru.natro.music;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=android.app.Application.class)
public class NativeWaveDiagnosticsTest {
 @Test public void nativeReasonAndDecisionPreservedButSecretsExcluded(){
  Map<String,Object> data=new HashMap<>();data.put("reason","PREFETCH_FAILED");data.put("decision","SWITCHED");
  data.put("sessionId","secret");data.put("tracks","secret");data.put("prefetchRatio",3);
  String event=NativeWaveDiagnostics.telemetry("OFFLINEWAVE_PREFETCH_TRIGGER",data);
  assertTrue(event.contains("reason=PREFETCH_FAILED decision=SWITCHED"));
  assertTrue(event.contains("prefetchRatio=3"));assertFalse(event.contains("secret"));
 }
 @Test public void unknownEventsAndUntrustedFieldsDoNotLeak(){
  assertNull(NativeWaveDiagnostics.telemetry("ACCOUNT",Collections.emptyMap()));
  assertEquals("event=OFFLINEWAVE_START reason=UNKNOWN",NativeWaveDiagnostics.telemetry("OFFLINEWAVE_START",Collections.singletonMap("reason","https://secret")));
  assertEquals("event=OFFLINEWAVE_ERROR reason=UNSPECIFIED_NATIVE_ERROR",NativeWaveDiagnostics.telemetry("OFFLINEWAVE_ERROR",Collections.singletonMap("message","secret")));
 }
 @Test public void groupedPrefetchRouteIncludesThresholdTypeAndSourceClocks()throws Exception {
  ShadowLog.clear();Map<String,Object> data=new HashMap<>();
  data.put("reason","LOW_RATIO");data.put("decision","SWITCHED");data.put("thresholdType","RATIO");
  data.put("threshold",60);data.put("prefetchRatio",3);data.put("sessionId","private-session");
  Object nativeEvent=Class.forName("lc0").getConstructor(String.class,Map.class)
       .newInstance("OFFLINEWAVE_PREFETCH_TRIGGER",data);
  NativeWaveDiagnostics.onGroupedTelemetry(nativeEvent);
  assertEquals(1,ShadowLog.getLogsForTag("NatroMusicWave").size());
  String text=ShadowLog.getLogsForTag("NatroMusicWave").get(0).msg;
  assertTrue(text.contains("thresholdType=RATIO prefetchRatio=3 threshold=60"));
  assertTrue(text.contains(" wall="));assertTrue(text.contains(" elapsed="));assertFalse(text.contains("private-session"));
 }
 @Test public void unknownGroupedObjectsAndEventsAreIgnored()throws Exception {
  ShadowLog.clear();NativeWaveDiagnostics.onGroupedTelemetry(new Object());
  NativeWaveDiagnostics.onGroupedTelemetry(Class.forName("lc0").getConstructor(String.class,Map.class)
       .newInstance("ACCOUNT",Collections.singletonMap("token","private-token")));
  assertTrue(ShadowLog.getLogsForTag("NatroMusicWave").isEmpty());
 }
 @Test public void failureClassSurvivesWithoutRawThrowableOrMessage(){
  String result=NativeWaveDiagnostics.nativeLog("OfflineWavePrefetchTrigger","canStartOfflineWave failed",
      new RuntimeException("secret",new java.net.UnknownHostException("secret-host")));
  assertEquals("event=PREFETCH_PROBE_FAILED reason=RECOMMENDER_ERROR error_kind=DNS",result);
  assertNull(NativeWaveDiagnostics.nativeLog("ACCOUNT","secret",null));
  assertNull(NativeWaveDiagnostics.nativeLog("WaveProlongationOperation","https://private",null));
 }
}
