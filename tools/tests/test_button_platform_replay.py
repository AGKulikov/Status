"""Run the real button transaction controllers with deterministic Android/vehicle adapters.

This checks event ordering and cancellation, not SDK availability or hardware effects.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    "android/os/Looper.java": "package android.os; public class Looper { public static final Looper MAIN=new Looper(); }",
    "android/os/HandlerThread.java": "package android.os; public class HandlerThread { final Looper l=new Looper(); public HandlerThread(String n){} public void start(){} public Looper getLooper(){return l;} }",
    "android/os/Handler.java": """package android.os; import java.util.*; public class Handler {
 public static long now; public static Looper current; final Looper looper; static int seq;
 static class Job {Handler h;Runnable r;long at;int seq;}
 static List<Job> jobs=new ArrayList<>(); public Handler(Looper l){looper=l;}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){Job j=new Job();j.h=this;j.r=r;j.at=now+delay;j.seq=seq++;jobs.add(j);return true;}
 public static void run(long delta,boolean main){long until=now+delta;for(;;){Job next=null;
  for(Job j:jobs)if(j.at<=until&&(main||j.h.looper!=Looper.MAIN)&&(next==null||j.at<next.at||j.at==next.at&&j.seq<next.seq))next=j;
  if(next==null)break;jobs.remove(next);now=Math.max(now,next.at);current=next.h.looper;next.r.run();current=null;
 }now=until;}
 }""",
    "android/os/SystemClock.java": "package android.os; public class SystemClock {public static long uptimeMillis(){return Handler.now;}}",
    "android/content/Intent.java": """package android.content;public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=268435456; public Intent(String a){} public Intent(){}
 public Intent setClassName(String p,String c){return this;} public Intent setFlags(int f){return this;}
 public Intent addFlags(int f){return this;} public Intent setPackage(String p){return this;}}
 """,
    "android/content/BroadcastReceiver.java": "package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);public int getResultCode(){return -1;}}",
    "android/content/Context.java": """package android.content;import android.os.*;public class Context {
 public final android.media.AudioManager audio=new android.media.AudioManager(); public int launches,acquires;
 public Context getApplicationContext(){return this;} public Looper getMainLooper(){return Looper.MAIN;}
 public <T>T getSystemService(Class<T> type){return type.cast(audio);}public void startActivity(Intent i){launches++;}
 public void sendOrderedBroadcast(Intent i,String permission,BroadcastReceiver receiver,Handler handler,int result,String data,Object extras){
  acquires++;handler.post(()->receiver.onReceive(this,i));}
 }""",
    "android/media/AudioManager.java": """package android.media;public class AudioManager {
 public static final int STREAM_MUSIC=3;public int volume=7,writes;
 void offMain(){if(android.os.Handler.current==android.os.Looper.MAIN)throw new AssertionError("AudioManager on MAIN");}
 public int getStreamVolume(int s){offMain();return volume;} public void setStreamVolume(int s,int v,int flags){offMain();volume=v;writes++;}}
 """,
    "android/app/Activity.java": "package android.app;public class Activity {public static final int RESULT_OK=-1,RESULT_CANCELED=0;}",
    "android/provider/Settings.java": "package android.provider;public class Settings {public static final String ACTION_ACCESSIBILITY_SETTINGS=\"accessibility\";}",
    "android/accessibilityservice/AccessibilityService.java": "package android.accessibilityservice;public class AccessibilityService {public static final int GLOBAL_ACTION_HOME=2;}",
    "android/view/accessibility/AccessibilityEvent.java": "package android.view.accessibility;public class AccessibilityEvent {public static final int TYPE_WINDOW_STATE_CHANGED=32;}",
    "android/widget/Toast.java": "package android.widget;public class Toast {public static final int LENGTH_LONG=1;public static Toast makeText(android.content.Context c,String m,int l){return new Toast();}public void show(){}}",
    "dezz/status/widget/WidgetAccessibilityService.java": "package dezz.status.widget;public class WidgetAccessibilityService {public static final WidgetAccessibilityService INSTANCE=new WidgetAccessibilityService();public int homes;public static WidgetAccessibilityService getInstance(){return INSTANCE;}public boolean performGlobalAction(int i){homes++;return true;}}",
    "dezz/status/widget/media/VehicleButtonController.java": """package dezz.status.widget.media;public class VehicleButtonController {
 public final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public boolean bool(String k){return Boolean.TRUE.equals(values.get(k));}public int integer(String k,int f){Object v=values.get(k);return v instanceof Integer?(Integer)v:f;}
 public void put(String k,Object v){values.put(k,v);}}
 """,
    "dezz/status/widget/car/CarControlState.java": "package dezz.status.widget.car;public class CarControlState {public boolean known=true,available=true;public double value;public CarControlState(int v){value=v;}}",
    "dezz/status/widget/car/CarIntegration.java": """package dezz.status.widget.car;import java.util.*;import java.util.function.*;public class CarIntegration {
 public interface TelemetryListener {void onTelemetry(TelemetryValue v);void onTelemetryUnavailable(String id);}
 public static class TelemetryValue {public double value;public TelemetryValue(int v){value=v;}}
 public interface ControlStateListener {void onControlState(CarControlState state);}
 public interface ControlCommandListener {void onResult(boolean ok,String message);}
 public static class Request {public int value; public BooleanSupplier gate;public ControlCommandListener callback;public void complete(){callback.onResult(gate.getAsBoolean(),"mock confirmation");}}
 public TelemetryListener ignition;public ControlStateListener state;public List<Request> requests=new ArrayList<>();
 public void subscribeTelemetry(Set<String> ids,TelemetryListener l){ignition=l;}public void unsubscribeTelemetry(TelemetryListener l){ignition=null;}
 public void subscribeControlStates(Set<String> ids,ControlStateListener l){state=l;}public void unsubscribeControlStates(ControlStateListener l){state=null;}
 public void selectButtonDriveMode(int v,BooleanSupplier gate,ControlCommandListener callback){Request r=new Request();r.value=v;r.gate=gate;r.callback=callback;requests.add(r);}
 public Request last(){return requests.get(requests.size()-1);}
 }""",
    "dezz/status/widget/car/CarIntegrations.java": "package dezz.status.widget.car;public class CarIntegrations {public static final CarIntegration MOCK=new CarIntegration();public static CarIntegration get(android.content.Context c){return MOCK;}}",
    "dezz/status/widget/media/PlatformReplay.java": """package dezz.status.widget.media;
import android.os.*;import android.content.*;import dezz.status.widget.car.*;
public class PlatformReplay {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] ignored){
  Context context=new Context();Handler worker=new Handler(new Looper());
  VehicleButtonController settings=new VehicleButtonController();settings.put("drive.restore",true);
  ButtonDriveModeController drive=new ButtonDriveModeController(context,settings,worker,m->{});
  CarIntegration car=CarIntegrations.MOCK;Handler.run(0,true);
  car.state.onControlState(new CarControlState(570491138));car.ignition.onTelemetry(new CarIntegration.TelemetryValue(2097415));Handler.run(0,true);
  check(car.requests.size()==1&&car.last().value==570491138,"restore only after ignition and mode");car.last().complete();Handler.run(0,true);
  drive.select(ButtonAction.SPORT);Handler.run(0,true);check(car.last().value==570491139,"sport");car.last().complete();Handler.run(0,true);
  drive.select(ButtonAction.SPORT);Handler.run(0,true);check(car.last().value==570491138,"same assignment swaps previous mode");car.last().complete();Handler.run(0,true);
  car.state.onControlState(new CarControlState(570491158));Handler.run(2499,true);
  check(settings.integer("drive.selected",-1)==570491138,"external mode is debounced");Handler.run(1,true);
  check(settings.integer("drive.selected",-1)==570491158,"external mode becomes selected");
  drive.select(ButtonAction.ECO);Handler.run(0,true);CarIntegration.Request late=car.last();
  car.ignition.onTelemetry(new CarIntegration.TelemetryValue(0));Handler.run(0,true);
  check(!late.gate.getAsBoolean(),"ignition OFF cancels queued SDK retry");late.complete();Handler.run(0,true);
  check(settings.integer("drive.selected",-1)==570491158,"late callback cannot replace selection");
  settings.put("drive.restore",false);drive.reconcile();Handler.run(0,true);
  check(car.ignition==null&&car.state==null,"disabled feature releases subscriptions");

  ButtonBluetoothMedia bt=ButtonBluetoothMedia.get(context);bt.start();Handler.run(0,false);
  ButtonBluetoothMedia.windowChanged(32,"com.ecarx.multimedia","com.ecarx.multimedia.MainActivity");Handler.run(0,false);
  check(context.audio.volume==0,"temporary mute");Handler.run(1000,false);
  bt.start();Handler.run(0,false);check(context.launches==1,"repeat cannot begin during pending restoration");
  Handler.run(499,false);check(context.audio.volume==0,"restore delay");Handler.run(1,false);
  check(context.audio.volume==7,"restore does not wait for stalled MAIN");Handler.run(0,true);
  check(dezz.status.widget.WidgetAccessibilityService.INSTANCE.homes==0,"late HOME cannot disrupt a later screen");
  bt.start();Handler.run(0,false);check(context.launches==2,"next transaction allowed after restoration");
  ButtonBluetoothMedia.windowChanged(32,"com.ecarx.multimedia","WelcomeActivity");Handler.run(0,false);
  Handler.run(12500,false);check(context.audio.volume==7,"watchdog restores volume when MainActivity never appears");
  System.out.println("Drive-mode swap/cancellation and BT restoration: PASS");
 }
}"""
}


class ButtonPlatformReplay(unittest.TestCase):
    def test_actual_transactions_with_blocked_main_and_late_vehicle_result(self):
        with tempfile.TemporaryDirectory(prefix="natro-button-transactions-") as tmp:
            folder = Path(tmp)
            paths = []
            for name, source in STUBS.items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths += [str(ROOT / "app/src/main/java/dezz/status/widget/media" / name) for name in
                      ("ButtonAction.java", "ButtonDriveModeController.java", "ButtonBluetoothMedia.java")]
            compiled = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(folder), *paths], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", str(folder), "dezz.status.widget.media.PlatformReplay"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
