"""Cold-start/late-callback replay of production cluster methods, without an Android build."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT


class ClusterColdLeaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.temp.name)
        src = (ROOT / "app/src/main/java/dezz/status/widget/instrument/InstrumentPanelView.java").read_text()
        methods = "\n".join(method(src, signature) for signature in (
            "public void onSurfaceTextureAvailable(", "public void onSurfaceTextureSizeChanged(",
            "public boolean onSurfaceTextureDestroyed(", "public void onSurfaceTextureUpdated(",
            "private void beginFirstFrameGate()", "private void publishLeaseIfReady()",
            "private void replaceLeaseIfReady()", "private void publishLeaseIfReady(boolean replace)",
            "private void scheduleColdLeaseRetry()", "private void retryColdLease()",
            "private boolean hasLiveLease()", "private void recoverColdGeometry()",
            "private void revokeLease()", "private void releaseOwnedSurface()"))
        harness = r'''public class ColdLeaseReplay {
 @interface NonNull {}
 static class android {
  static class os {static class SystemClock {static long uptimeMillis(){return 5000;}}}
  static class view {static class ViewGroup {static class LayoutParams {int width,height;}}}
 }
 static class SurfaceTexture {void setDefaultBufferSize(int w,int h){} long getTimestamp(){return 1000;}}
 static class Surface {boolean valid=true;Surface(SurfaceTexture t){} boolean isValid(){return valid;}void release(){valid=false;}}
 static class LayoutParams extends android.view.ViewGroup.LayoutParams {int leftMargin,topMargin;
  LayoutParams(int w,int h){width=w;height=h;}}
 static class TextureView {
  SurfaceTexture texture=new SurfaceTexture();boolean available=true;int width=800,height=400,requests,invalidates;float alpha;
  android.view.ViewGroup.LayoutParams params=new LayoutParams(0,0);
  boolean isAvailable(){return available;}SurfaceTexture getSurfaceTexture(){return texture;}
  int getWidth(){return width;}int getHeight(){return height;}int getVisibility(){return 0;}
  void setAlpha(float v){alpha=v;}void requestLayout(){requests++;}void invalidate(){invalidates++;}
  android.view.ViewGroup.LayoutParams getLayoutParams(){return params;}
  void setLayoutParams(LayoutParams p){params=p;}
 }
 static class Store {boolean enabled=true;boolean isEnabled(){return enabled;}}
 static class InstrumentElementConfig {boolean enabled=true;}
 static class Resources {Metrics getDisplayMetrics(){return new Metrics();}}
 static class Metrics {int densityDpi=160;}
 static class DiagnosticJournal {static void infoAsync(String a,String b){}}
 static class MapStartupDiagnostics {static void frame(boolean c,Surface s,long t){}}
 static class NavigationHudEndpointService {
  static int publications,revocations;static Surface live;static boolean accept=true;
  static void ensureClusterEndpointStarted(Object c){}
  static long publishClusterSurface(Surface s,int w,int h,int dpi){publications++;if(!accept)return -1;live=s;return publications;}
  static void revokeClusterSurface(Surface s){check(live==s);live=null;revocations++;}
  static long sentMapGeneration(Surface s,boolean cluster){return live==s?publications:-1;}
 }
 static class Panel {
  static final int VISIBLE=0,COLD_LEASE_FAST_RETRY_COUNT=40;
  static final long COLD_LEASE_FAST_RETRY_MS=150,COLD_LEASE_SLOW_RETRY_MS=1000;
  TextureView mapTexture=new TextureView(),mapView=mapTexture;
  Surface mapSurface,publishedSurface;SurfaceTexture ownedTexture;
  Store panelStore=new Store();boolean attached=true,leasePublished,clusterMapEnabled=true,awaitingFirstMapFrame=true;
  int publishedWidth,publishedHeight,coldLeaseRetryCount,posts,requests,invalidates;
  long lastGeometryRecovery,firstFrameWaitStarted;float desiredMapAlpha=.7f;
  final Runnable coldLeaseRetry=this::retryColdLease;
  void postDelayed(Runnable r,long delay){posts++;}void removeCallbacks(Runnable r){}
  void requestLayout(){requests++;}void invalidate(){invalidates++;}
  void tracePresentation(){}
  void logColdWait(String reason){}Object getContext(){return this;}Resources getResources(){return new Resources();}
  int getWidth(){return 1000;}int getHeight(){return 500;}
  InstrumentElementConfig firstMap(){return new InstrumentElementConfig();}
  LayoutParams mapParams(InstrumentElementConfig map){return new LayoutParams(800,400);}
  METHODS
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void missedInitialCallbackAndLateCallbackPreserveExactSurface(){
  Panel p=new Panel();p.publishLeaseIfReady();Surface first=p.mapSurface;
  check(p.hasLiveLease());p.onSurfaceTextureUpdated(p.ownedTexture);check(p.mapView.alpha==.7f);
  int publications=NavigationHudEndpointService.publications;
  p.onSurfaceTextureAvailable(p.ownedTexture,800,400);
  check(p.mapSurface==first&&first.valid&&p.hasLiveLease());
  check(NavigationHudEndpointService.publications==publications&&p.mapView.alpha==.7f);
 }
 static void coldGeometryRecoversWithoutSettingsAndHealthyMapStopsRetry(){
  Panel p=new Panel();p.mapTexture.width=0;p.mapTexture.height=0;
  p.retryColdLease();check(p.requests==1&&p.mapView.requests==1&&!p.hasLiveLease());
  check(p.mapView.params.width==800&&p.mapView.params.height==400);
  p.mapTexture.width=800;p.mapTexture.height=400;p.retryColdLease();check(p.hasLiveLease());
  p.onSurfaceTextureUpdated(p.ownedTexture);int pubs=NavigationHudEndpointService.publications,posts=p.posts;
  for(int i=0;i<100;i++)p.retryColdLease();
  check(p.requests==1&&p.posts==posts&&NavigationHudEndpointService.publications==pubs);
 }
 static void replacementRejectsStaleCallbacksAndReleasesOnlyOldLease(){
  Panel p=new Panel();p.publishLeaseIfReady();Surface old=p.mapSurface;SurfaceTexture oldTexture=p.ownedTexture;
  p.mapTexture.texture=new SurfaceTexture();p.publishLeaseIfReady();Surface current=p.mapSurface;
  check(current!=old&&!old.valid&&current.valid&&p.hasLiveLease());
  int pubs=NavigationHudEndpointService.publications,revokes=NavigationHudEndpointService.revocations;
  p.onSurfaceTextureAvailable(oldTexture,800,400);p.onSurfaceTextureSizeChanged(oldTexture,1,1);
  p.onSurfaceTextureDestroyed(oldTexture);p.onSurfaceTextureUpdated(oldTexture);
  check(current.valid&&p.hasLiveLease()&&p.awaitingFirstMapFrame);
  check(NavigationHudEndpointService.publications==pubs&&NavigationHudEndpointService.revocations==revokes);
 }
 static void disabledOrDetachedPanelDoesNotResurrect(){
  Panel p=new Panel();p.publishLeaseIfReady();p.panelStore.enabled=false;p.retryColdLease();
  check(!p.leasePublished);int pubs=NavigationHudEndpointService.publications;
  p.onSurfaceTextureAvailable(p.mapTexture.texture,800,400);check(!p.leasePublished);
  p.panelStore.enabled=true;p.attached=false;p.retryColdLease();p.publishLeaseIfReady();
  check(NavigationHudEndpointService.publications==pubs);
 }
 static void resizeKeepsVisibleFrameAndRejectedAdmissionRetries(){
  Panel p=new Panel();NavigationHudEndpointService.accept=false;p.publishLeaseIfReady();
  check(!p.leasePublished&&p.posts>0);NavigationHudEndpointService.accept=true;p.retryColdLease();
  p.onSurfaceTextureUpdated(p.ownedTexture);Surface old=p.mapSurface;
  p.mapTexture.width=900;p.onSurfaceTextureSizeChanged(p.ownedTexture,900,400);
  check(p.mapSurface==old&&p.publishedWidth==900&&p.mapView.alpha==.7f);
 }
 public static void main(String[] args)throws Exception{ColdLeaseReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}'''.replace("METHODS", methods)
        file = cls.path / "ColdLeaseReplay.java"
        file.write_text(harness)
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), str(file)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.path), "ColdLeaseReplay", name], check=True)

    def test_late_available_callback_preserves_surface(self): self.replay("missedInitialCallbackAndLateCallbackPreserveExactSurface")
    def test_layout_recovers_without_settings(self): self.replay("coldGeometryRecoversWithoutSettingsAndHealthyMapStopsRetry")
    def test_stale_callbacks_cannot_destroy_replacement(self): self.replay("replacementRejectsStaleCallbacksAndReleasesOnlyOldLease")
    def test_disabled_and_detached(self): self.replay("disabledOrDetachedPanelDoesNotResurrect")
    def test_resize_and_admission_rejection(self): self.replay("resizeKeepsVisibleFrameAndRejectedAdmissionRetries")
