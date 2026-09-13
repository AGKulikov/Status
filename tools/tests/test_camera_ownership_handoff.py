"""Execute unchanged production ownership methods; no MapKit/GPU claims are made."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT


class CameraOwnershipHandoffTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.temp.name)
        src = (ROOT / "navigator-mod/src/main/java/ru/natro/navigation/CameraDirectionMapLayer.java").read_text()
        methods = method(src, "boolean hasRouteInventory()") + method(src, "private void refreshFingerprintAndRender()")
        harness = r'''import java.util.*;
public class CameraHandoffReplay {
 static class SystemClock {static long elapsedRealtime(){return 1000L;}}
 static class Layer {
  static final long YANDEX_FRESH_MS=3000L;
  boolean yandexEnabled=true,latestRouteActive=true,lastInventoryAvailable;
  long latestYandexSampleElapsedMs=900L,latestVisualFingerprint=Long.MIN_VALUE,renderedFingerprint=Long.MIN_VALUE;
  final List<Object> latestYandex=new ArrayList<>(),visibleScratch=new ArrayList<>();
  final List<String> calls=new ArrayList<>();
  boolean ordinaryVisible=true,unifiedVisible;
  Object map=new Object();
  Runnable inventoryListener=()->{
   calls.add("ordinary:"+!hasRouteInventory());
   ordinaryVisible=!hasRouteInventory();
   check(!(ordinaryVisible&&unifiedVisible));
  };
  void selectVisible(List<Object> target){target.clear();if(hasRouteInventory())target.addAll(latestYandex);}
  long visualFingerprint(List<Object> target){return target.size();}
  void render(){
   calls.add("unified:"+!visibleScratch.isEmpty());
   unifiedVisible=!visibleScratch.isEmpty();
   check(!(ordinaryVisible&&unifiedVisible));
   renderedFingerprint=visualFingerprint(visibleScratch);
  }
  METHODS
 }
 static void check(boolean value){if(!value)throw new AssertionError();}
 static void takeoverNeverCreatesTwoPinsAndReleaseClearsFirst(){
  Layer layer=new Layer();layer.latestYandex.add(new Object());layer.refreshFingerprintAndRender();
  check(layer.calls.equals(Arrays.asList("ordinary:false","unified:true")));
  layer.latestYandexSampleElapsedMs=0;layer.refreshFingerprintAndRender();
  check(layer.calls.equals(Arrays.asList("ordinary:false","unified:true","unified:false","ordinary:true")));
 }
 static void emptyAheadInventoryDoesNotResurrectPassedCameras(){
  Layer layer=new Layer();layer.refreshFingerprintAndRender();
  check(layer.hasRouteInventory()&&!layer.ordinaryVisible&&!layer.unifiedVisible);
  layer.latestYandex.add(new Object());layer.refreshFingerprintAndRender();
  layer.latestYandex.clear();layer.refreshFingerprintAndRender();
  check(!layer.ordinaryVisible&&!layer.unifiedVisible);
 }
 static void disabledEndedOrFutureInventoryIsNotAuthoritative(){
  Layer layer=new Layer();layer.latestYandexSampleElapsedMs=1001;
  check(!layer.hasRouteInventory());layer.latestYandexSampleElapsedMs=900;
  layer.yandexEnabled=false;check(!layer.hasRouteInventory());
  layer.yandexEnabled=true;layer.latestRouteActive=false;check(!layer.hasRouteInventory());
 }
 public static void main(String[] args)throws Exception{CameraHandoffReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}'''.replace("METHODS", methods)
        file = cls.path / "CameraHandoffReplay.java"
        file.write_text(harness)
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), str(file)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.path), "CameraHandoffReplay", name], check=True)

    def test_no_overlap_at_takeover_or_release(self): self.replay("takeoverNeverCreatesTwoPinsAndReleaseClearsFirst")
    def test_authoritative_empty_ahead(self): self.replay("emptyAheadInventoryDoesNotResurrectPassedCameras")
    def test_inactive_and_future_inventory(self): self.replay("disabledEndedOrFutureInventoryIsNotAuthoritative")
