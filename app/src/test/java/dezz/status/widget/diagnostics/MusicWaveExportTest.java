package dezz.status.widget.diagnostics;
import android.app.Application;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class MusicWaveExportTest {
 @Test public void nativeEventReachesExportedJournalAndObserverManifest()throws Exception {
  Context context=RuntimeEnvironment.getApplication();
  DiagnosticJournal.initialize(context,true);
  try {
   String event="NatroMusicWave: v=1 pid=5003 elapsed=21940 seq=9 wall=1794494802416 event=OFFLINEWAVE_START reason=Prolongation";
   long generation=MusicWaveDiagnostics.captureGeneration();
   assertTrue(MusicWaveDiagnostics.captureLine(event,generation));
   assertFalse(MusicWaveDiagnostics.captureLine(event,generation));
   assertTrue(MusicWaveDiagnostics.captureLine("NatroMusicWave: v=1 pid=5003 elapsed=23940 seq=10 event=ONLINE_RESULT_SELECTED reason=ONLINE_PROLONGATION_COMPLETED error_kind=UNKNOWN",generation));
   assertTrue(DiagnosticJournal.awaitPendingWrites());
   File result=DiagnosticBundle.create(context);assertNotNull(result);
   try(ZipFile zip=new ZipFile(result)){
    String log=new String(zip.getInputStream(zip.getEntry("journal.log")).readAllBytes(),StandardCharsets.UTF_8);
    assertTrue(log.contains("OFFLINEWAVE_START reason=Prolongation"));
    assertTrue(log.contains("wall=1794494802416"));
    assertTrue(log.contains("ONLINE_RESULT_SELECTED"));
    String manifest=new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),StandardCharsets.UTF_8);
    assertTrue(manifest.contains("music_wave_observer"));
   }
   MusicWaveDiagnostics.disabled();
   assertFalse(MusicWaveDiagnostics.captureLine(event.replace("seq=9","seq=11"),generation));
  }finally{DiagnosticJournal.setEnabled(context,false);}
 }
}
