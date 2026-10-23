"""Execute production duplicate/freshness and traffic heartbeat policies off-device."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MediaDeliveryRecoveryTest(unittest.TestCase):
    def test_duplicate_paths_and_traffic_bursts(self):
        with tempfile.TemporaryDirectory(prefix="natro-delivery-") as directory:
            out = Path(directory)
            source = out / "RecoveryReplay.java"
            source.write_text(r'''
package dezz.status.widget.launcher;
import dezz.status.widget.media.MediaKeyPolicy;
public final class RecoveryReplay {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    TrafficNotificationPolicy traffic = new TrafficNotificationPolicy();
    int emitted = 0;
    // 400 identical accepted inputs over 20 seconds: liveness remains 1Hz.
    for (int t=10000; t<30000; t+=50)
      if (traffic.shouldNotify("green:10", "green:10", t)) emitted++;
    check(emitted == 20);
    check(traffic.shouldNotify("green:10", "green:9", 30001));
    check(!traffic.shouldNotify("green:9", "green:9", 30002));
    check(traffic.shouldNotify("green:9", "red:30", 30003));
    check(traffic.shouldNotify("red:30", "", 30004)); // clear is immediate
    check(traffic.shouldNotify("", "red:30", 30005)); // restore is immediate
    check(traffic.shouldNotify("red:30", "red:30", 31005));
    check(traffic.shouldNotify("red:30", "red:30", 1)); // reset clock

    MediaKeyPolicy keys = new MediaKeyPolicy();
    for (int code : new int[]{85,87,88}) {
      for (int action : new int[]{0,1}) {
        check(keys.admit(code,action,0,100,100,-1,0,0));
        check(!keys.admit(code,action,0,100,100,-1,0,0)); // other receiver
        check(keys.admit(code,action,0,101,101,-1,0,0)); // next physical tap
      }
    }
    check(MediaKeyPolicy.freshAt(1000,1750));
    check(!MediaKeyPolicy.freshAt(1000,1751));
    check(!MediaKeyPolicy.freshAt(362757,389649)); // observed backlog
    check(!MediaKeyPolicy.freshAt(0,0));
    check(!MediaKeyPolicy.freshAt(2000,1999));
  }
}
''', encoding="utf-8")
            base = ROOT / "app/src/main/java/dezz/status/widget"
            compiled = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(out),
                str(source), str(base / "media/MediaKeyPolicy.java"),
                str(base / "launcher/TrafficNotificationPolicy.java")],
                capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            replay = subprocess.run(["java", "-cp", str(out),
                "dezz.status.widget.launcher.RecoveryReplay"], capture_output=True, text=True)
            self.assertEqual(replay.returncode, 0, replay.stderr)
