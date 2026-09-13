"""Production Android stop/drain methods against inert reducer/radio collaborators."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT


class AncsEmptyOwnerStopTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.temp.name)
        source = (ROOT / "app/src/main/java/dezz/status/widget/phone/transport/v2/android/AndroidCentralTransportV2.java").read_text()
        methods = method(source, "private void stopOnMain(") + method(source, "private void maybeCompleteTeardown()")
        harness = r'''public class AncsStopReplay {
 static class BleRouteEpoch {}
 enum IphoneTransportStopReason {APP_SHUTDOWN}
 static class BleRouteTransition<T>{boolean accepted=true;T state;BleRouteTransition(T s){state=s;}}
 static class AndroidCentralRoute {
  enum Phase {FAILED,STOPPING,STOPPED,WAIT_RADIO,RETRY_DRAINING}
  static class State {BleRouteEpoch epoch=new BleRouteEpoch();Object expected=new Object();Phase phase=Phase.FAILED;}
  static BleRouteTransition<State> stop(State s,BleRouteEpoch e,String reason){s.phase=Phase.STOPPING;return new BleRouteTransition<>(s);}
  static BleRouteTransition<State> localTeardownComplete(State s,Object expected){s.phase=Phase.STOPPED;return new BleRouteTransition<>(s);}
  static BleRouteTransition<State> attemptTeardownComplete(State s,Object expected){throw new AssertionError();}
 }
 static class ProcessGattRegistrationGateV2 {
  static boolean drain=true;static Runnable waiter;
  static boolean ownsDrainReservation(Object o){return drain;}
  static void releaseDrainReservation(Object o){drain=false;}
  static void whenFreeForDrain(Object o,Runnable r){waiter=r;}
  static boolean owns(Object o){return true;}static void cancelWaiter(Object o){throw new AssertionError();}
 }
 static class GattOwner {boolean waitingForProcessGate,closing,registrationProven,quarantinedBeforeRegistration;}
 static class Listener {void onLocalTerminal(Object mode,BleRouteEpoch epoch){throw new AssertionError();}}
 static class Transport {
  AndroidCentralRoute.State state=new AndroidCentralRoute.State();GattOwner owner;
  boolean processGateDrainRetained,scanRunning;Object processGateDrainWaiter=new Object();
  BleRouteEpoch radioOffTerminalEpoch,deferredStopTerminalEpoch;Listener listener=new Listener();
  void apply(BleRouteTransition<AndroidCentralRoute.State> t){state=t.state;}
  Object mode(){return null;}void cancelAllTimers(){}void dispatchMain(Runnable r){r.run();}
  void retainOrReleaseProcessDrain(){processGateDrainRetained=true;}
  void retireRegisteredGattOwner(GattOwner o){throw new AssertionError("Stop must not force-release a live owner");}
  METHODS
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 static void emptyOwnerCompletesWithoutWaitingForAbsentCallback(){
  Transport t=new Transport();t.stopOnMain(t.state.epoch,IphoneTransportStopReason.APP_SHUTDOWN);
  check(t.state.phase==AndroidCentralRoute.Phase.STOPPED&&t.processGateDrainRetained);
 }
 static void scanOrLiveGattStillBlocksTerminalProof(){
  Transport t=new Transport();t.owner=new GattOwner();
  t.stopOnMain(t.state.epoch,IphoneTransportStopReason.APP_SHUTDOWN);
  check(t.state.phase==AndroidCentralRoute.Phase.STOPPING&&t.owner!=null&&!t.processGateDrainRetained);
  ProcessGattRegistrationGateV2.drain=true;t.owner=null;t.scanRunning=true;t.maybeCompleteTeardown();
  check(t.state.phase==AndroidCentralRoute.Phase.STOPPING&&!t.processGateDrainRetained);
 }
 static void busyProcessGateWaitsForExactDrainAndStaleEpochIsIgnored(){
  Transport t=new Transport();ProcessGattRegistrationGateV2.drain=false;
  t.stopOnMain(new BleRouteEpoch(),IphoneTransportStopReason.APP_SHUTDOWN);
  check(t.state.phase==AndroidCentralRoute.Phase.FAILED);
  t.stopOnMain(t.state.epoch,IphoneTransportStopReason.APP_SHUTDOWN);
  check(t.state.phase==AndroidCentralRoute.Phase.STOPPING&&!t.processGateDrainRetained);
  check(ProcessGattRegistrationGateV2.waiter!=null);ProcessGattRegistrationGateV2.drain=true;
  ProcessGattRegistrationGateV2.waiter.run();check(t.state.phase==AndroidCentralRoute.Phase.STOPPED);
 }
 public static void main(String[] args)throws Exception{AncsStopReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}'''.replace("METHODS", methods)
        file = cls.path / "AncsStopReplay.java"
        file.write_text(harness)
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), str(file)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def replay(self, name):
        subprocess.run(["java", "-cp", str(self.path), "AncsStopReplay", name], check=True)

    def test_empty_owner_stops_without_timeout(self): self.replay("emptyOwnerCompletesWithoutWaitingForAbsentCallback")
    def test_live_owner_and_scan_remain_fenced(self): self.replay("scanOrLiveGattStillBlocksTerminalProof")
    def test_process_gate_and_epoch(self): self.replay("busyProcessGateWaitsForExactDrainAndStaleEpochIsIgnored")

    def test_iphone_snapshot_exposes_peer_readiness_without_claiming_delivery(self):
        root = ROOT / "ios/KX11-iPhone-ANCS-Helper-v70-personal"
        runtime = (root / "HelperSwitchRuntimeCoordinator.swift").read_text()
        ui = (root / "KX11ANCSHelper/ViewController.swift").read_text()
        self.assertIn("public let peerReady: Bool", runtime)
        self.assertIn("peerReady: phase == .active && peerReady", runtime)
        self.assertIn("self.runtimeFailure == nil, self.peerReady", runtime)
        self.assertIn("AuthenticatedC5FrameV1.isValid(frame)", runtime)
        self.assertIn("setTransportReady(snapshot.phase == .active && snapshot.peerReady)", ui)
        self.assertNotIn("emitDiagnostic(", runtime)
