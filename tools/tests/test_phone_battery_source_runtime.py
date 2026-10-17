"""Replay the actual WidgetService battery reader/threshold methods without BLE or Android IPC."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, JAVA


class PhoneBatterySourceRuntimeTest(unittest.TestCase):
    def test_coherent_exact_source_and_threshold_latches(self):
        widget = (JAVA / 'WidgetService.java').read_text()
        methods = '\n'.join(method(widget, signature) for signature in [
            'private Integer exactPhoneBatteryPercentage(',
            'private void handlePhoneLowBatteryAlert(',
            'private void reconcilePhoneLowBatteryAlertStage('])
        source = r'''
import java.util.*;
import androidx.annotation.*;
import dezz.status.widget.phone.PhoneLowBatteryAlertPolicy;
public class BatteryReplay {
 enum ConnectorType {PHONE, OTHER}
 static class ConnectorValue {
  ConnectorType connectorType=ConnectorType.PHONE;String connectorId="iphone",resourceId;
  Object rawValue;boolean fresh=true,available=true,readable=true;long updatedAt=1;
  ConnectorValue(String id,Object raw){resourceId=id;rawValue=raw;}
 }
 static class Pref<T> {T value;Pref(T v){value=v;}T get(){return value;}void set(T v){value=v;}}
 static class Prefs {
  Pref<Boolean> phoneLowBatteryAlertEnabled=new Pref<>(true),phoneLowBatteryAlertLatched=new Pref<>(false),phoneLowBatteryAlertLatched2=new Pref<>(false);
  Pref<Boolean> phoneStatusBarNotificationsEnabled=new Pref<>(false),phonePopupNotificationsEnabled=new Pref<>(true);
  Pref<Integer> phoneLowBatteryAlertThreshold=new Pref<>(20),phoneLowBatteryAlertThreshold2=new Pref<>(10);
  Pref<String> phoneLowBatteryAlertColor=new Pref<>("red"),phoneLowBatteryAlertColor2=new Pref<>("red");
 }
 static class DiagnosticJournal {static List<String> records=new ArrayList<>();static void infoAsync(String t,String m){records.add(m);}}
 Prefs prefs=new Prefs();Map<String,ConnectorValue> phoneStatusValues=new HashMap<>();
 boolean phoneLowBatteryAlertLatched,phoneLowBatteryAlertLatched2,phoneLowBatteryAlertPending,phoneLowBatteryAlertPending2;
 String lastPhoneBatteryAlertEvidence="";int queued;boolean accepts=true;
 boolean enqueuePhoneLowBatteryAlert(int level,String color,int stage){if(accepts)queued++;return accepts;}
 boolean phoneNotificationAllowedByLockState(){return true;}
 boolean phoneNotificationBlockedByForeground(){return false;}
 METHODS
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 void sample(int level,String source){ConnectorValue v=new ConnectorValue("battery.level",level);phoneStatusValues.put("battery.level",v);phoneStatusValues.put("battery.level_source",new ConnectorValue("battery.level_source",source));handlePhoneLowBatteryAlert(v);}
 public static void main(String[] args){
  BatteryReplay r=new BatteryReplay();r.sample(20,"iphone_helper_coarse");check(r.queued==0,"coarse20 must not warn");
  check(DiagnosticJournal.records.get(0).contains("thresholds=20/10")&&DiagnosticJournal.records.get(0).contains("popup_enabled=true"),"threshold/destination evidence missing");
  r.sample(20,"ble_bas");check(r.queued==1,"same20 from BAS must warn");
  r.sample(19,"android_broadcast");check(r.queued==1,"pending stage must not duplicate");
  r.sample(25,"iphone_helper_coarse");check(r.phoneLowBatteryAlertPending,"coarse rebound must not rearm");
  r.sample(22,"ble_bas");check(!r.phoneLowBatteryAlertPending,"real recovery releases stage");
  r.sample(19,"ble_bas");check(r.queued==2,"new genuine descent may warn");
  r.sample(10,"ble_bas");check(r.queued==3,"independent second threshold");
  r.sample(9,"ble_bas");check(r.queued==3,"both pending stages stay single");
  ConnectorValue v=r.phoneStatusValues.get("battery.level"),s=r.phoneStatusValues.get("battery.level_source");
  s.fresh=false;check(r.exactPhoneBatteryPercentage(v)==null,"stale provenance rejected");s.fresh=true;
  s.updatedAt=2;check(r.exactPhoneBatteryPercentage(v)==null,"different snapshots rejected");s.updatedAt=1;
  s.connectorId="other";check(r.exactPhoneBatteryPercentage(v)==null,"different phone rejected");s.connectorId="iphone";
  v.connectorType=ConnectorType.OTHER;check(r.exactPhoneBatteryPercentage(v)==null,"foreign connector rejected");v.connectorType=ConnectorType.PHONE;
  v.rawValue=9.5;check(r.exactPhoneBatteryPercentage(v)==null,"fractional value rejected");
  r.phoneStatusValues.clear();check(r.exactPhoneBatteryPercentage(v)==null,"missing source rejected");
  BatteryReplay rejected=new BatteryReplay();rejected.accepts=false;rejected.sample(19,"ble_bas");
  check(!rejected.phoneLowBatteryAlertPending&&!rejected.prefs.phoneLowBatteryAlertLatched.get(),"undelivered warning must remain retryable");
  check(DiagnosticJournal.records.stream().anyMatch(s1->s1.contains("warning_enqueue")&&s1.contains("accepted=false")&&s1.contains("presentation_confirmed=false")),"queue rejection must be observable");
  rejected.accepts=true;rejected.sample(19,"ble_bas");check(rejected.queued==1,"gate reopening retries exact warning");
 }
}
'''.replace('METHODS', methods)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = [JAVA / 'phone/PhoneLowBatteryAlertPolicy.java']
            for name, text in {
                'BatteryReplay.java': source,
                'androidx/annotation/NonNull.java': 'package androidx.annotation;public @interface NonNull {}',
                'androidx/annotation/Nullable.java': 'package androidx.annotation;public @interface Nullable {}',
            }.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                files.append(path)
            compile_result = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', directory,
                                             *map(str, files)], capture_output=True, text=True)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'BatteryReplay'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
