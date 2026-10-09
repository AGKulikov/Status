"""Deterministic replays of production TSR, GATT retirement and launch recovery."""
from pathlib import Path
import subprocess, tempfile, unittest
from test_map_visibility_recovery import method, ROOT

CLOCK = 'package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}'
HANDLER = '''package android.os; public class Handler {
 static class E {long at;Runnable r;E(long t,Runnable v){at=t;r=v;}}
 static java.util.List<E> q=new java.util.ArrayList<>();
 public Handler(){}public Handler(Looper l){} public void post(Runnable r){postDelayed(r,0);}
 public void postDelayed(Runnable r,long d){q.add(new E(SystemClock.now+d,r));}
 public void removeCallbacks(Runnable r){q.removeIf(e->e.r==r);}
 public static void advance(long t){int limit=1000;while(true){E n=null;for(E e:q)if(e.at<=t&&(n==null||e.at<n.at))n=e;
 if(n==null)break;if(--limit==0)throw new AssertionError("timer loop");q.remove(n);SystemClock.now=n.at;n.r.run();}SystemClock.now=t;}
 public static void reset(){q.clear();SystemClock.now=0;}}
'''

class RecoveryReplay(unittest.TestCase):
    def execute(self, sources, main):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name, source in sources.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
            p=subprocess.run(['java','com.sun.tools.javac.Main','-d',str(root),*[str(x) for x in root.rglob('*.java')]],capture_output=True,text=True)
            self.assertEqual(p.returncode,0,p.stderr)
            p=subprocess.run(['java','-cp',str(root),main],capture_output=True,text=True)
            self.assertEqual(p.returncode,0,p.stdout+p.stderr)

    def test_tsr_late_readback_drift_rate_limit_disable_and_death(self):
        base='dezz/status/widget/car/'
        sources={
          base+'EcarxInstrumentTsrAccess.java':(ROOT/'app/src/geely/java'/base/'EcarxInstrumentTsrAccess.java').read_text(),
          base+'InstrumentTsrAccess.java':(ROOT/'app/src/main/java'/base/'InstrumentTsrAccess.java').read_text(),
          'android/os/SystemClock.java':CLOCK,'android/os/Handler.java':HANDLER,
          'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
          'android/os/HandlerThread.java':'package android.os; public class HandlerThread {public HandlerThread(String s){}public void start(){}public Looper getLooper(){return new Looper();}public void quitSafely(){}}',
          'android/content/Context.java':'package android.content; public class Context {public Context getApplicationContext(){return this;}}',
          'dezz/status/widget/diagnostics/DiagnosticJournal.java':'package dezz.status.widget.diagnostics; public class DiagnosticJournal {public static void infoAsync(String a,String b){}public static void operationFailure(String a,String b){}}',
          'ecarx/car/hardware/annotation/ApiResult.java':'package ecarx.car.hardware.annotation; public enum ApiResult {SUCCEED,FAILED}',
          'ecarx/car/hardware/signal/CarSignalManager.java':'package ecarx.car.hardware.signal; public class CarSignalManager {}',
          'ecarx/car/ECarXCar.java':'''package ecarx.car; public class ECarXCar {public static String PA_SERVICE="pa";public Object getCarManager(String s){return new ecarx.car.hardware.vehicle.ECarXCarSetManager();}}''',
          'ecarx/car/hardware/vehicle/ECarXCarSetManager.java':'''package ecarx.car.hardware.vehicle; public class ECarXCarSetManager {public ECarXCarActivesafetyManager getECarXCarActivesafetyManager(){return ECarXCarActivesafetyManager.instance;}}''',
          'ecarx/car/hardware/vehicle/ECarXCarActivesafetyManager.java':'''package ecarx.car.hardware.vehicle; public class ECarXCarActivesafetyManager {
           public static ECarXCarActivesafetyManager instance=new ECarXCarActivesafetyManager();public int value=1,writes;public boolean delayed,fail;
           public ecarx.car.hardware.annotation.ApiResult CB_ASY_TSR(int n){writes++;if(!delayed&&!fail)value=n;return fail?ecarx.car.hardware.annotation.ApiResult.FAILED:ecarx.car.hardware.annotation.ApiResult.SUCCEED;}
           public Object getPA_Asy_TSR(){return new Property(value);}public static class Property{int v;Property(int n){v=n;}public int getData(){return v;}public int getAvailability(){return 1;}}}''',
          'com/ecarx/xui/adaptapi/ECarXCarProxy.java':'''package com.ecarx.xui.adaptapi; public class ECarXCarProxy {
           public interface ECarXCarProxyMethod {void onECarXCarServiceConnected(ecarx.car.ECarXCar c,ecarx.car.hardware.signal.CarSignalManager s);void onECarXCarServiceDeath();}
           ECarXCarProxyMethod listener;public ECarXCarProxy(android.content.Context c,ECarXCarProxyMethod m){listener=m;}
           public void initECarXCar(){listener.onECarXCarServiceConnected(new ecarx.car.ECarXCar(),null);}public void cleanup(){}}''',
          base+'TsrReplay.java':'''package dezz.status.widget.car;
           import android.os.*;import ecarx.car.hardware.vehicle.*;
           public class TsrReplay {static void check(boolean v){if(!v)throw new AssertionError("at "+SystemClock.now);}
           public static void main(String[] a){
             Handler.reset();ECarXCarActivesafetyManager m=ECarXCarActivesafetyManager.instance;m.delayed=true;
             EcarxInstrumentTsrAccess t=new EcarxInstrumentTsrAccess(new android.content.Context());Handler.advance(0);
             int[] done={0};t.setHidden(true,(ok,d)->{check(ok);done[0]++;});Handler.advance(300);check(done[0]==0&&m.writes==1);
             m.value=0;Handler.advance(1300);check(done[0]==1&&m.writes==1);m.delayed=false;
             Handler.advance(10000);check(m.writes==1);m.value=1;
             Handler.advance(29999);check(m.writes==1);Handler.advance(31000);check(m.writes==2&&m.value==0);
             m.value=1;m.fail=true;Handler.advance(59999);check(m.writes==2);Handler.advance(61000);check(m.writes==3);
             Handler.advance(89999);check(m.writes==3);m.fail=false;
             t.setHidden(false,(ok,d)->check(ok));Handler.advance(90500);int count=m.writes;Handler.advance(150000);check(m.writes==count);
             t.setHidden(true,(ok,d)->{});Handler.advance(150000);t.onECarXCarServiceDeath();Handler.advance(150500);count=m.writes;
             Handler.advance(200000);check(m.writes==count);t.close();Handler.advance(250000);check(m.writes==count);
           }}'''
        }
        self.execute(sources,'dezz.status.widget.car.TsrReplay')

    def test_navigator_retries_only_fresh_absence_without_prior_surface(self):
        path='dezz/status/widget/'
        self.execute({path+'NavigatorAutoLaunchPolicy.java':(ROOT/'app/src/main/java'/path/'NavigatorAutoLaunchPolicy.java').read_text(),path+'LaunchReplay.java':'''package dezz.status.widget;
        public class LaunchReplay {static void check(boolean b){if(!b)throw new AssertionError();}public static void main(String[] a){
        check(NavigatorAutoLaunchPolicy.retry(16000,0,0,1,true,true,15900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(14000,0,0,1,true,true,13900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,0,0,2,true,true,15900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,0,0,1,false,true,15900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,0,0,1,true,false,15900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,0,0,1,true,true,10000,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,0,0,1,true,true,15900,10));
        check(!NavigatorAutoLaunchPolicy.retry(41000,0,0,1,true,true,40900,-1));
        check(!NavigatorAutoLaunchPolicy.retry(16000,-1,0,1,true,true,15900,-1));
        }}'''},'dezz.status.widget.LaunchReplay')

    def test_gatt_disconnect_callback_loss_preserves_exact_owner_and_settle(self):
        source=(ROOT/'app/src/main/java/dezz/status/widget/phone/transport/v2/android/AndroidCentralTransportV2.java').read_text()
        methods='\n'.join(method(source,n) for n in ['private void retireRegisteredGattOwner(', 'private void closeAfterDisconnectTimeout(', 'private void finishGattClose()'])
        harness='''import android.os.*;
        public class GattReplay {
         static final long REGISTERED_GATT_RETIRE_SETTLE_MS=2000;
         static class Token {long ownerId=1;}
         static class Gatt {int disconnects,closes;boolean fail;void disconnect(){disconnects++;}void close(){closes++;if(fail)throw new IllegalStateException();}}
         static class GattOwner {Token ownerToken=new Token();Gatt gatt=new Gatt();boolean connected=true,closing=true,registrationProven=true,quarantinedBeforeRegistration,retirementSettleRequested;long disconnectRequestedAtMillis;}
         static class Adapter {boolean isEnabled(){return true;}}
         static class IphoneTransportErrorV2 {enum Kind {TEARDOWN}}
         static class ProcessGattRegistrationGateV2 {static Object lease;static boolean owns(Object o){return lease==o;}static void release(Object o){if(lease==o)lease=null;}static void cancelWaiter(Object o){}}
         GattOwner owner;boolean radioResetProven;Adapter adapter=new Adapter();Handler main=new Handler();int terminals;
         void traceLifecycle(String a,String b){}void clearTelemetrySubscription(){}void clearCarRemoteChannel(){}void failPendingRouteControl(){}void maybeRefreshGattCache(GattOwner o){}
         void reportError(IphoneTransportErrorV2.Kind k,String s,boolean b){}void reportPlatformDiagnostic(Token t,String s){}void dispatchMain(Runnable r){r.run();}void maybeCompleteTeardown(){terminals++;}
         METHODS
         static void check(boolean v){if(!v)throw new AssertionError("at "+SystemClock.now);}
         static GattReplay fresh(){Handler.reset();SystemClock.now=10;GattReplay t=new GattReplay();t.owner=new GattOwner();ProcessGattRegistrationGateV2.lease=t.owner;return t;}
         public static void main(String[] args){
          GattReplay t=fresh();GattOwner o=t.owner;t.retireRegisteredGattOwner(o);t.retireRegisteredGattOwner(o);check(o.gatt.disconnects==1);
          Handler.advance(1010);check(o.gatt.closes==1&&t.owner==null&&ProcessGattRegistrationGateV2.owns(o)&&t.terminals==0);
          Handler.advance(3009);check(ProcessGattRegistrationGateV2.owns(o));Handler.advance(3010);check(ProcessGattRegistrationGateV2.lease==null&&t.terminals==1);
          t=fresh();o=t.owner;t.retireRegisteredGattOwner(o);t.finishGattClose();GattOwner next=new GattOwner();t.owner=next;ProcessGattRegistrationGateV2.lease=next;
          Handler.advance(1010);check(next.gatt.closes==0&&o.gatt.closes==1&&t.owner==next);
          t=fresh();o=t.owner;o.registrationProven=false;t.closeAfterDisconnectTimeout(o);check(o.gatt.closes==0&&t.owner==o);
          o.registrationProven=true;o.quarantinedBeforeRegistration=true;t.closeAfterDisconnectTimeout(o);check(o.gatt.closes==0);
          t=fresh();o=t.owner;o.gatt.fail=true;t.retireRegisteredGattOwner(o);Handler.advance(5000);check(t.owner==o&&ProcessGattRegistrationGateV2.owns(o)&&t.terminals==0);
         }
        }'''.replace('METHODS',methods)
        self.execute({'android/os/SystemClock.java':CLOCK,'android/os/Handler.java':HANDLER,'android/os/Looper.java':'package android.os;public class Looper {}','GattReplay.java':harness},'GattReplay')

if __name__=='__main__':unittest.main()
