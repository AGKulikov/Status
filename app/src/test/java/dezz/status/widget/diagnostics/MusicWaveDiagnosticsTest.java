package dezz.status.widget.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class MusicWaveDiagnosticsTest {
 @Test public void parsesNativeTransitionAndKeepsSourceClock(){
  String p="v=1 pid=5003 elapsed=21940 seq=1 event=OFFLINEWAVE_START reason=Prolongation";
  assertEquals(p,MusicWaveDiagnostics.parse("1794494802.416 5003 5010 I NatroMusicWave: "+p));
 }
 @Test public void onlineResultIsSeparateFromMigrationAttempt(){
  for(String p:new String[]{"ONLINE_RESULT_SELECTED reason=ONLINE_PROLONGATION_COMPLETED","ONLINE_MIGRATION_STARTED reason=ONLINE_SESSION_AVAILABLE"})
   assertNotNull(MusicWaveDiagnostics.parse("NatroMusicWave: v=1 pid=2 elapsed=3 seq=4 event="+p));
 }
 @Test public void rejectsRawErrorsAndForeignMessages(){
  assertNull(MusicWaveDiagnostics.parse("NatroMusicWave: secret"));
  assertNull(MusicWaveDiagnostics.parse("OtherTag: v=1 pid=2 elapsed=3 seq=4 event=OFFLINEWAVE_START reason=Prolongation"));
  assertNull(MusicWaveDiagnostics.parse("NatroMusicWave: v=1 pid=2 elapsed=3 seq=4 event=OFFLINEWAVE_ERROR reason=https://secret"));
 }
 @Test public void acceptsDecisionUnitsAndNativeWallClockButRejectsInjectedFields(){
  String p="v=1 pid=5 elapsed=21940 seq=1 wall=1794494802416 event=OFFLINEWAVE_PREFETCH_TRIGGER reason=LOW_RATIO decision=SWITCHED thresholdType=PREFETCHED_DURATION_MS prefetchRatio=3 threshold=6000 offlineBatchWaitMs=10";
  assertEquals(p,MusicWaveDiagnostics.parse("NatroMusicWave: "+p));
  assertNull(MusicWaveDiagnostics.parse("NatroMusicWave: "+p+" sessionId=private"));
  assertNotNull(MusicWaveDiagnostics.parse("NatroMusicWave: v=1 pid=5 elapsed=3 seq=2 event=PREFETCH_PROBE_FAILED reason=TIMEOUT error_kind=TIMEOUT"));
 }
}
