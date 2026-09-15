"""Execute production MEDIA routing/cache transforms and the driver-display policy off-device."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MediaOemControlsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-media-oem-")
        cls.out = Path(cls.temp.name)
        test = cls.out / "ControlsReplay.java"
        test.write_text(r'''
import dezz.status.widget.media.*;
import dezz.status.widget.instrument.InstrumentOemPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public class ControlsReplay {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  static byte[] cache(String action) {
    byte[] b=new byte[256];Arrays.fill(b,(byte)0xe8);
    byte[] header={'v','d','e','x','0','1','9',0};System.arraycopy(header,0,b,0,8);
    byte[] s=action.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(s,0,b,40,s.length);b[40+s.length]=0;
    System.arraycopy(s,0,b,140,s.length);b[140+s.length]=0;
    b[255]=10;return b;
  }
  static void routing() {
    check(MediaKeyPolicy.route(MediaKeyPolicy.NATRO,true,false)==MediaKeyPolicy.Route.AUDIO_MANAGER);
    check(MediaKeyPolicy.route(MediaKeyPolicy.STOCK,true,false)==MediaKeyPolicy.Route.AUDIO_MANAGER);
    for(boolean enabled:new boolean[]{false,true}) {
      check(MediaKeyPolicy.route(MediaKeyPolicy.NATRO,enabled,true)==MediaKeyPolicy.Route.STOCK_BROADCAST);
      check(MediaKeyPolicy.route(MediaKeyPolicy.STOCK,enabled,true)==MediaKeyPolicy.Route.IGNORE);
      check(MediaKeyPolicy.route(MediaKeyPolicy.MCONFIG,enabled,false)==MediaKeyPolicy.Route.IGNORE);
      check(MediaKeyPolicy.route("unrelated",enabled,false)==MediaKeyPolicy.Route.IGNORE);
    }
    check(MediaKeyPolicy.route(MediaKeyPolicy.NATRO,false,false)==MediaKeyPolicy.Route.STOCK_BROADCAST);
    check(MediaKeyPolicy.route(MediaKeyPolicy.STOCK,false,false)==MediaKeyPolicy.Route.IGNORE);
  }
  static void keys() {
    MediaKeyPolicy p=new MediaKeyPolicy();
    check(p.admit(87,0,0,100,100,4,3,257));
    check(!p.admit(87,0,0,100,100,4,3,257)); // AudioManager fallback/return: same key.
    check(p.admit(87,0,1,100,150,4,3,257)); // Genuine long-press repeat retained.
    check(p.admit(87,1,0,100,170,4,3,257)); // Original release retained.
    check(p.admit(87,0,0,171,171,4,3,257)); // No 900ms click-detection suppression.
    check(p.admit(87,0,0,171,171,5,3,257)); // Another input device is independent.
    for(int i=0;i<10000;i++) check(p.admit(88,0,0,1000+i,1000+i,1,0,257));
  }
  static void reversiblePatch() {
    byte[] stock=cache(MediaKeyPolicy.STOCK),before=stock.clone();
    byte[] active=MediaInputPatch.apply(stock,true);
    check(Arrays.equals(stock,before));check(stock.length==active.length);
    check(MediaInputPatch.state(active).equals(MediaKeyPolicy.NATRO));
    check(Arrays.equals(MediaInputPatch.apply(active,false),stock));
    check(Arrays.equals(MediaInputPatch.apply(active,true),active));
    byte[] prior=cache(MediaKeyPolicy.MCONFIG);
    check(Arrays.equals(MediaInputPatch.apply(prior,true),active));
    // Everything except the first letter of the two reviewed strings is untouched.
    int changed=0;for(int i=0;i<stock.length;i++)if(stock[i]!=active[i])changed++;
    check(changed==2);
  }
  static void rejectUnknownCache() {
    for(byte[] b:new byte[][]{new byte[0],new byte[256],cache("android.intent.action.UNRELATED")}) {
      try{MediaInputPatch.apply(b,true);throw new AssertionError();}catch(IllegalArgumentException expected){}
    }
    byte[] mixed=cache(MediaKeyPolicy.STOCK);
    byte[] other=MediaKeyPolicy.MCONFIG.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(other,0,mixed,140,other.length);
    try{MediaInputPatch.apply(mixed,true);throw new AssertionError();}catch(IllegalArgumentException expected){}
  }
  static void driverModes() {
    check(InstrumentOemPolicy.suppressWhiteBar(true,3));
    for(int mode:new int[]{-1,0,1,2,4,255})check(!InstrumentOemPolicy.suppressWhiteBar(true,mode));
    check(!InstrumentOemPolicy.suppressWhiteBar(false,3));
    check(InstrumentOemPolicy.appOpMatches("SYSTEM_ALERT_WINDOW: deny; time=+1m",true));
    check(InstrumentOemPolicy.appOpMatches("  SYSTEM_ALERT_WINDOW: allow\n",false));
    for(String output:new String[]{null,"No operations.","permission denied", "SYSTEM_ALERT_WINDOW: ignore", "other: allow"})
      check(!InstrumentOemPolicy.appOpMatches(output,false));
    check(!InstrumentOemPolicy.appOpMatches("SYSTEM_ALERT_WINDOW: allow",true));
  }
  public static void main(String[] args)throws Exception { ControlsReplay.class.getDeclaredMethod(args[0]).invoke(null); }
}
''')
        source = ROOT / "app/src/main/java/dezz/status/widget"
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.out), str(test),
            str(source / "media/MediaKeyPolicy.java"), str(source / "media/MediaInputPatch.java"),
            str(source / "instrument/InstrumentOemPolicy.java")], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, name):
        result = subprocess.run(["java", "-cp", str(self.out), "ControlsReplay", name], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_reference_routing_matrix_and_mconfig_isolation(self): self.run_case("routing")
    def test_down_up_repeat_and_echo_identity(self): self.run_case("keys")
    def test_fixed_size_reversible_takeover(self): self.run_case("reversiblePatch")
    def test_unknown_and_mixed_cache_is_not_modified(self): self.run_case("rejectUnknownCache")
    def test_driver_mode_restoration_and_actual_appop_readback(self): self.run_case("driverModes")
