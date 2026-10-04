#!/usr/bin/env python3
"""Execute production DiagnosticJournal with real file IO and an intentionally busy disk lock."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "android/content/Context.java": """package android.content; public class Context {
      private final java.io.File root; public Context(java.io.File r){root=r;}
      public Context getApplicationContext(){return this;}
      public java.io.File getFilesDir(){return root;}
      public java.io.File getCacheDir(){return root;} public String getPackageName(){return "fixture";}public void sendBroadcast(Intent i){} }""",
    "android/os/SystemClock.java": """package android.os; public class SystemClock {
      public static long elapsedRealtime(){return System.nanoTime()/1000000;} public static long uptimeMillis(){return elapsedRealtime();} }""",
    "android/os/Build.java": """package android.os; public class Build {
      public static String MANUFACTURER="test",MODEL="test";
      public static class VERSION { public static String RELEASE="test"; public static int SDK_INT=28;} }""",
    "dezz/status/widget/VersionGetter.java": """package dezz.status.widget; public class VersionGetter {
      public static String getAppVersionName(android.content.Context c){return "test";} }""",
    "dezz/status/widget/diagnostics/SteeringKeyDiagnostics.java": """package dezz.status.widget.diagnostics;
      public class SteeringKeyDiagnostics {public static void debugChanged(boolean b){} }""",
    "dezz/status/widget/navigation/MapStartupDiagnostics.java": """package dezz.status.widget.navigation;
      public class MapStartupDiagnostics {public static void journalChanged(boolean b){} }""",
    "dezz/status/widget/diagnostics/JournalReplay.java": r"""package dezz.status.widget.diagnostics;
      import java.io.*; import java.lang.reflect.*; import java.nio.file.*;
      import java.util.concurrent.*; import java.util.concurrent.atomic.*;
      public class JournalReplay {
        static android.content.Context context;
        static Object field(String n)throws Exception{
          Field f=DiagnosticJournal.class.getDeclaredField(n);f.setAccessible(true);return f.get(null);
        }
        static void check(boolean b){if(!b)throw new AssertionError();}
        static String exported()throws Exception{
          File f=DiagnosticJournal.copyForExport(context);check(f!=null);
          return Files.readString(f.toPath());
        }
        static void normalProducersNeverWaitForDisk()throws Exception{
          DiagnosticJournal.initializeEarly(context);
          Object disk=field("DISK_LOCK");
          synchronized(disk){
            FutureTask<Void> producer=new FutureTask<>(()->{
              DiagnosticJournal.initialize(context,true);
              DiagnosticJournal.debug("test","debug");DiagnosticJournal.info("test","info");
              DiagnosticJournal.warn("test","warn");DiagnosticJournal.error("test","error");
              DiagnosticJournal.recordEarly(DiagnosticJournal.Level.INFO,"test","early");
              DiagnosticJournal.infoAsync("test","async");
              DiagnosticJournal.setEnabled(context,false);DiagnosticJournal.setEnabled(context,true);
              DiagnosticJournal.info("test","final");return null;
            });
            Thread t=new Thread(producer);t.setDaemon(true);t.start();
            producer.get(1,TimeUnit.SECONDS);
          }
          check(exported().contains("final"));
        }
        static void queueIsBoundedAndReportsLoss()throws Exception{
          DiagnosticJournal.initialize(context,true);
          synchronized(field("DISK_LOCK")){
            for(int i=0;i<2000;i++)DiagnosticJournal.info("bounded","record-"+i);
            ThreadPoolExecutor writer=(ThreadPoolExecutor)field("ASYNC");
            check(writer.getQueue().size()<=128);
            check(((DiagnosticWriteQueue)field("WRITES")).dropped()>0);
          }
          check(exported().contains("diagnostic_queue_dropped_total="));
        }
        static void clearAndDisableFenceQueuedWrites()throws Exception{
          DiagnosticJournal.initialize(context,true);
          synchronized(field("DISK_LOCK")){
            DiagnosticJournal.info("test","must-not-survive-clear");
            DiagnosticJournal.clear();
            DiagnosticJournal.info("test","after-clear");
          }
          String text=exported();check(!text.contains("must-not-survive-clear"));
          check(text.contains("journal cleared"));check(text.contains("after-clear"));
          synchronized(field("DISK_LOCK")){
            DiagnosticJournal.info("test","must-not-survive-disable");
            DiagnosticJournal.setEnabled(context,false);
          }
          check(!exported().contains("must-not-survive-disable"));
        }
        static void orderAndPrivacySurviveAsyncExport()throws Exception{
          DiagnosticJournal.initialize(context,true);
          DiagnosticJournal.debug("test","event-A");DiagnosticJournal.warn("test","event-B");
          DiagnosticJournal.error("test","event-C password=do-not-export-me AA:BB:CC:DD:EE:FF");
          String text=exported();check(text.indexOf("event-A")<text.indexOf("event-B"));
          check(text.indexOf("event-B")<text.indexOf("event-C"));
          check(!text.contains("do-not-export-me"));check(!text.contains("AA:BB:CC"));
        }
        static void crashStillPersistsWhenDebugDisabled()throws Exception{
          DiagnosticJournal.initialize(context,false);
          DiagnosticJournal.recordCrash(Thread.currentThread(),new RuntimeException("crash-marker"));
          check(exported().contains("crash-marker"));
        }
        public static void main(String[] a)throws Exception{
          context=new android.content.Context(new File(a[1]));
          JournalReplay.class.getDeclaredMethod(a[0]).invoke(null);
        }
      }""",
}

STUBS.update({
    "android/os/Process.java":"package android.os;public class Process {public static int myPid(){return 123;}}",
    "android/content/Intent.java": "package android.content;public class Intent{public static int FLAG_RECEIVER_REGISTERED_ONLY=1;public Intent(String s){} public Intent setPackage(String s){return this;} public Intent addFlags(int i){return this;}public Intent putExtra(String s,boolean b){return this;}public boolean getBooleanExtra(String s,boolean b){return b;}}",
    "android/content/IntentFilter.java":"package android.content;public class IntentFilter{public IntentFilter(String s){}}",
    "android/content/BroadcastReceiver.java":"package android.content;public abstract class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}",
    "androidx/core/content/ContextCompat.java":"package androidx.core.content;import android.content.*;public class ContextCompat{public static int RECEIVER_NOT_EXPORTED=1;public static void registerReceiver(Context c,BroadcastReceiver b,IntentFilter f,int flag){}}",
    "dezz/status/widget/AppProcessPolicy.java":"package dezz.status.widget;public class AppProcessPolicy{public static boolean isHudProcess(){return false;}}",
    "dezz/status/widget/diagnostics/MainThreadWatchdog.java":"package dezz.status.widget.diagnostics;public class MainThreadWatchdog{public static void setEnabled(boolean b){}}",
    "dezz/status/widget/diagnostics/CausalDiagnostics.java":'package dezz.status.widget.diagnostics;public class CausalDiagnostics{public static void debugChanged(boolean b){}public static void journalEvent(String a,String b){}public static String session(){return "fixture";}public static String snapshot(){return "sample";}}',
})


class JournalAsyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-journal-test-")
        cls.root = Path(cls.temp.name)
        sources = []
        for name, text in STUBS.items():
            target = cls.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
            sources.append(str(target))
        sources.extend(str(ROOT / "app/src/main/java/dezz/status/widget/diagnostics" / name) for name in ("DiagnosticJournal.java","DiagnosticWriteQueue.java","DiagnosticFileSnapshot.java"))
        compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        subprocess.run([*compiler, "-d", str(cls.root), *sources], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        with tempfile.TemporaryDirectory(prefix="natro-journal-files-") as data:
            subprocess.run(["java", "-cp", str(self.root),
                            "dezz.status.widget.diagnostics.JournalReplay", name, data],
                           check=True, timeout=15)


for case in ("normalProducersNeverWaitForDisk", "queueIsBoundedAndReportsLoss",
             "clearAndDisableFenceQueuedWrites", "orderAndPrivacySurviveAsyncExport",
             "crashStillPersistsWhenDebugDisabled"):
    setattr(JournalAsyncTest, "test_" + case, lambda self, name=case: self.replay(name))


if __name__ == "__main__":
    unittest.main()
