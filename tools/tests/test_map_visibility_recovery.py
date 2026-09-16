"""Execute the actual view callback bodies: missing tile ACK/readback must not hide maps.

Android/GPU rendering is not emulated. The production methods are extracted unchanged
and run against ownership/opacity fixtures so tests do not duplicate their decisions.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dezz/status/widget"


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class MapVisibilityRecoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-map-visibility-")
        cls.folder = Path(cls.temp.name)
        hud = (JAVA / "hud/HudCompositeView.java").read_text()
        cluster = (JAVA / "instrument/InstrumentPanelView.java").read_text()
        renderer = (ROOT / "navigator-mod/src/main/java/ru/natro/navigation/HudMapRenderer.java").read_text()
        callbacks = []
        for source in (hud, cluster):
            callbacks.append(method(source, "public void onSurfaceTextureUpdated(")
                             + method(source, "private void beginFirstFrameGate()"))
        observe = method(renderer, "private void observeMapLoading()")
        source = r'''import java.lang.reflect.*;
public class MapVisibilityReplay {
 @interface NonNull {}
 static class SurfaceTexture {long getTimestamp(){return 1000;}}
 static class Surface { boolean valid=true; boolean isValid(){return valid;} }
 static class View { float alpha; int writes; void setAlpha(float a){alpha=a;writes++;} }
 static class TextureView extends View {
  SurfaceTexture surface=new SurfaceTexture();
  SurfaceTexture getSurfaceTexture(){return surface;}
  Object getBitmap(int w,int h){throw new AssertionError("Readback must not gate visibility");}
 }
 static class DiagnosticJournal {static void info(String tag,String text){} static void infoAsync(String tag,String text){}}
 static class MapStartupDiagnostics {static void frame(boolean c,Surface s,long t){}}
 static class android {static class os {static class SystemClock {static long uptimeMillis(){return 1000L;}}}}
 static class NavigationHudEndpointService {
  static boolean sent=true;
  static long sentMapGeneration(Object surface,boolean cluster){return sent?4L:-1L;}
  static boolean isMapContentReady(Object surface,boolean cluster){
   throw new AssertionError("Full tile loading must not gate visibility");}
 }
 // This fixture covers ownership after the bounded presentation stage completes.
 // Its timing/readback/failure contract is exercised separately with the real implementation.
 static class Presentation {void reset(){}void onFrame(Surface s,long t,Runnable show){show.run();}}
 static class Hud {
  Presentation firstPresentation=new Presentation();
  TextureView mapTexture=new TextureView(); Surface leasedSurface=new Surface();
  SurfaceTexture leasedTexture=mapTexture.surface; Object activeMap=new Object();
  long firstFrameWaitStarted; boolean awaitingFirstMapFrame=true; float desiredMapAlpha=.72f;
  void tracePresentation(){}
  HUD_METHODS
 }
 static class Cluster {
  Presentation firstPresentation=new Presentation();
  TextureView mapTexture=new TextureView(); View mapView=mapTexture;
  Surface mapSurface=new Surface(); boolean attached=true,leasePublished=true;
  SurfaceTexture ownedTexture=mapTexture.surface;
  long firstFrameWaitStarted; boolean awaitingFirstMapFrame=true; float desiredMapAlpha=.43f;
  Object coldLeaseRetry=new Object();
  void removeCallbacks(Object callback){}
  void logColdWait(String reason){}
  void tracePresentation(){}
  CLUSTER_METHODS
 }
 static class Log {static void w(String tag,String text){}}
 static class NavigationBridgeClient {static void reportDiagnostic(String text){}}
 static class MapLoadedListenerBinding {
  static void set(Object target,Object listener)throws Exception {
   throw new ReflectiveOperationException("Optional API unavailable");
  }
 }
 static class Observer {
  Object map=new Object(),mapLoadedListener;long generation=4L;
  boolean mapContentLoaded,mapConfigured=true,runtimeSurfaceAttached=true;
  String displayName="HUD",TAG="test";
  static Object invoke(Object o,String n,Class<?>[] t,Object... a)throws Exception {
   return o.getClass().getMethod(n,t).invoke(o,a);
  }
  static String shortMessage(Throwable t){return t.getClass().getSimpleName();}
  void acknowledgeMapContent(){}
  OBSERVE_METHOD
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 static void absentTileAckAndUnavailableReadbackDoNotBlockEitherMap(){
  Hud h=new Hud();Cluster c=new Cluster();
  h.beginFirstFrameGate();c.beginFirstFrameGate();
  h.onSurfaceTextureUpdated(h.leasedTexture);
  c.onSurfaceTextureUpdated(c.mapTexture.surface);
  check(!h.awaitingFirstMapFrame && h.mapTexture.alpha==.72f);
  check(!c.awaitingFirstMapFrame && c.mapView.alpha==.43f);
 }
 static void staleAndRevokedSurfacesCannotRevealReplacement(){
  Hud h=new Hud();Cluster c=new Cluster();SurfaceTexture stale=new SurfaceTexture();
  h.onSurfaceTextureUpdated(stale);c.onSurfaceTextureUpdated(stale);
  check(h.awaitingFirstMapFrame && c.awaitingFirstMapFrame);
  h.leasedSurface.valid=false;c.leasePublished=false;
  h.onSurfaceTextureUpdated(h.leasedTexture);c.onSurfaceTextureUpdated(c.mapTexture.surface);
  check(h.mapTexture.alpha==0 && c.mapView.alpha==0);
  h.leasedSurface.valid=true;c.leasePublished=true;c.attached=false;
  c.onSurfaceTextureUpdated(c.mapTexture.surface);check(c.awaitingFirstMapFrame);
 }
 static void independentOwnersAndRepeatedUpdatesStayVisible(){
  Hud h=new Hud();Cluster c=new Cluster();h.onSurfaceTextureUpdated(h.leasedTexture);
  check(h.mapTexture.alpha==.72f && c.mapView.alpha==0);
  for(int i=0;i<100;i++)h.onSurfaceTextureUpdated(h.leasedTexture);
  check(h.mapTexture.alpha==.72f && h.mapTexture.writes==1);
  c.onSurfaceTextureUpdated(c.mapTexture.surface);check(c.mapView.alpha==.43f);
 }
 static void recreateRequiresCurrentUpdateButNeverTileCompletion(){
  Hud h=new Hud();h.onSurfaceTextureUpdated(h.leasedTexture);
  SurfaceTexture old=h.leasedTexture;h.beginFirstFrameGate();
  h.leasedTexture=new SurfaceTexture();h.mapTexture.surface=h.leasedTexture;
  h.onSurfaceTextureUpdated(old);check(h.awaitingFirstMapFrame && h.mapTexture.alpha==0);
  h.onSurfaceTextureUpdated(h.leasedTexture);check(h.mapTexture.alpha==.72f);
 }
 static void configuredZeroOpacityAndEditorArePreserved(){
  Hud h=new Hud();h.desiredMapAlpha=0;h.onSurfaceTextureUpdated(h.leasedTexture);
  check(!h.awaitingFirstMapFrame && h.mapTexture.alpha==0);
  Cluster editor=new Cluster();editor.mapTexture=null;editor.mapView.alpha=.5f;
  editor.beginFirstFrameGate();editor.onSurfaceTextureUpdated(new SurfaceTexture());
  check(editor.mapView.alpha==.5f);
 }
 static void unavailableMapLoadedListenerDoesNotStopAttachedRenderer(){
  Observer observer=new Observer();observer.observeMapLoading();
  check(observer.mapConfigured && observer.runtimeSurfaceAttached);
  check(observer.mapLoadedListener==null);
 }
 static void localAllocationUpdateCannotRevealAnUnsentLease(){
  Cluster c=new Cluster();NavigationHudEndpointService.sent=false;
  Hud h=new Hud();h.onSurfaceTextureUpdated(h.leasedTexture);
  check(h.awaitingFirstMapFrame && h.mapTexture.alpha==0);
  c.onSurfaceTextureUpdated(c.mapTexture.surface);
  check(c.awaitingFirstMapFrame && c.mapView.alpha==0);
  NavigationHudEndpointService.sent=true;
  h.onSurfaceTextureUpdated(h.leasedTexture);
  check(!h.awaitingFirstMapFrame);
  c.onSurfaceTextureUpdated(c.mapTexture.surface);
  check(!c.awaitingFirstMapFrame && c.mapView.alpha==.43f);
 }
 public static void main(String[] args)throws Exception {
  MapVisibilityReplay.class.getDeclaredMethod(args[0]).invoke(null);
 }
}
'''
        source = source.replace("HUD_METHODS", callbacks[0]).replace("CLUSTER_METHODS", callbacks[1])
        source = source.replace("OBSERVE_METHOD", observe)
        file = cls.folder / "MapVisibilityReplay.java"
        file.write_text(source)
        compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        result = subprocess.run([*compiler, "-d", str(cls.folder), str(file)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.folder), "MapVisibilityReplay", name], check=True,
                       capture_output=True, text=True)

    def test_no_tile_ack_or_readback_dependency(self):
        self.replay("absentTileAckAndUnavailableReadbackDoNotBlockEitherMap")

    def test_surface_ownership_and_revocation(self):
        self.replay("staleAndRevokedSurfacesCannotRevealReplacement")

    def test_independent_owners_and_no_rehiding(self):
        self.replay("independentOwnersAndRepeatedUpdatesStayVisible")

    def test_current_surface_after_recreation(self):
        self.replay("recreateRequiresCurrentUpdateButNeverTileCompletion")

    def test_opacity_and_editor(self):
        self.replay("configuredZeroOpacityAndEditorArePreserved")

    def test_optional_listener_failure(self):
        self.replay("unavailableMapLoadedListenerDoesNotStopAttachedRenderer")

    def test_unsent_local_update_is_not_a_producer_frame(self):
        self.replay("localAllocationUpdateCannotRevealAnUnsentLease")
