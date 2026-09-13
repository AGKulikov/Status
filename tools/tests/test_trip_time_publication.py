"""Exercise both real emit paths: an estimated duration must never become the stock timer."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_map_visibility_recovery import method

ROOT = Path(__file__).resolve().parents[2]


class TripTimePublicationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-trip-time-")
        cls.path = Path(cls.temp.name)
        source = (ROOT / "app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java").read_text()
        methods = "\n".join(method(source, signature) for signature in [
            "private void emitCurrentTrip(", "private void emitRealtimeCurrentTrip(",
            "private void invalidateUnavailableTripFields("])
        fixture = r'''import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dezz.status.widget.car.CurrentTripMetrics;
public class Replay {
 @interface NonNull {}
 static class SystemClock {static long elapsedRealtimeNanos(){return 1_000_000_000L;}}
 static class EcarxTrip2Access {static class Sample {
  int distanceRaw,averageSpeedRaw,speedUnitRaw;long observedAtElapsedNanos=1_000_000_000L;
  Sample(int d,int s,int u){distanceRaw=d;averageSpeedRaw=s;speedUnitRaw=u;}
 }}
 static class Listener {Set<String> unavailable=new HashSet<>();void onTelemetryUnavailable(String id){unavailable.add(id);}}
 static class TelemetrySubscription {
  AtomicBoolean cancelled=new AtomicBoolean();Listener listener=new Listener();
  Set<String> metricIds=new HashSet<>(Arrays.asList(CurrentTripMetrics.DISTANCE_ID,CurrentTripMetrics.DURATION_ID,CurrentTripMetrics.AVERAGE_SPEED_ID));
 }
 static class RealtimeTelemetrySubscription extends TelemetrySubscription {}
 static class TelemetryValue {String id;float value;
  TelemetryValue(String i,String l,float v,String u,long at){id=i;value=v;}
 }
 static class Handler {void post(Runnable r){r.run();}}
 Handler mainHandler=new Handler();Map<String,Float> values=new HashMap<>();
 void deliverTelemetry(TelemetrySubscription s,TelemetryValue v){values.put(v.id,v.value);}
 void deliverRealtimeTelemetry(RealtimeTelemetrySubscription s,String id,float value,long at){values.put(id,value);}
 METHODS
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  for(int[] row:new int[][]{{49,8,0},{174,24,0},{0,0,0},{-1,8,0},{49,-1,0}}){
   Replay r=new Replay();EcarxTrip2Access.Sample sample=new EcarxTrip2Access.Sample(row[0],row[1],row[2]);
   boolean realtime=args[0].equals("realtime");
   if(realtime){
    RealtimeTelemetrySubscription s=new RealtimeTelemetrySubscription();r.emitRealtimeCurrentTrip(s,sample);
    check(r.values.containsKey(CurrentTripMetrics.DURATION_ID)&&Float.isNaN(r.values.get(CurrentTripMetrics.DURATION_ID)),"Realtime must invalidate old timer, including 0/0");
   }else{
    TelemetrySubscription s=new TelemetrySubscription();r.emitCurrentTrip(s,sample);
    check(s.listener.unavailable.contains(CurrentTripMetrics.DURATION_ID),"Regular stream must invalidate old timer");
    check(!r.values.containsKey(CurrentTripMetrics.DURATION_ID),"An estimate cannot be emitted as stock time");
   }
   if(row[0]>=0)check(Math.abs(r.values.get(CurrentTripMetrics.DISTANCE_ID)-row[0]/10f)<.001,"Independent distance must survive");
   if(row[1]>=0)check(r.values.get(CurrentTripMetrics.AVERAGE_SPEED_ID)==row[1],"Independent speed must survive");
  }
 }
}'''.replace("METHODS", methods)
        file = cls.path / "Replay.java"
        file.write_text(fixture)
        metrics = ROOT / "app/src/main/java/dezz/status/widget/car/CurrentTripMetrics.java"
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), str(file), str(metrics)],
                                capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, mode):
        result = subprocess.run(["java", "-cp", str(self.path), "Replay", mode], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_regular_stream_clears_unverified_timer(self):
        self.replay("regular")

    def test_realtime_stream_clears_unverified_timer(self):
        self.replay("realtime")
