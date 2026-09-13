"""Real recorder/file IO with inert Android and JSON adapters; exports validated by Python JSON."""
from pathlib import Path
import base64
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
"androidx/annotation/NonNull.java": "package androidx.annotation;public @interface NonNull {}",
"androidx/annotation/Nullable.java": "package androidx.annotation;public @interface Nullable {}",
"android/content/Context.java": """package android.content;import java.io.*;
public class Context {File root;public Context(File r){root=r;}
public Context getApplicationContext(){return this;}public File getFilesDir(){return root;}
public File getCacheDir(){return new File(root,"cache");}}""",
"android/os/SystemClock.java": """package android.os;public class SystemClock {
static long time;public static synchronized long elapsedRealtime(){return ++time;}}""",
"dezz/status/widget/diagnostics/DiagnosticJournal.java": """package dezz.status.widget.diagnostics;
public class DiagnosticJournal {public static String redact(String s){return s;}
public static void info(String a,String b){}public static void warn(String a,String b){}
public static void error(String a,String b,Throwable t){throw new AssertionError(t);}}""",
"dezz/status/widget/diagnostics/PrivilegedActionCollector.java": """package dezz.status.widget.diagnostics;
public class PrivilegedActionCollector {public static void captureMarkerSnapshot(){}}""",
"org/json/JSONException.java": "package org.json;public class JSONException extends Exception {}",
"org/json/JSONArray.java": """package org.json;import java.util.*;
public class JSONArray {List<Object> values=new ArrayList<>();public void put(Object v){values.add(v);}
public int length(){return values.size();}public Object opt(int i){return values.get(i);}
public String toString(){return values.toString();}}""",
"org/json/JSONObject.java": r'''package org.json;import java.util.*;
public class JSONObject {
 public static final Object NULL=new Object(){public String toString(){return "null";}};
 final Map<String,Object> values=new LinkedHashMap<>();String raw;
 public JSONObject(){}public JSONObject(String text)throws JSONException{raw=text;}
 public JSONObject put(String k,Object v)throws JSONException{values.put(k,v);return this;}
 public Iterator<String> keys(){return values.keySet().iterator();}public Object opt(String k){return values.get(k);}
 public long optLong(String k){return optLong(k,0);}public long optLong(String k,long def){Object v=values.get(k);return v instanceof Number?((Number)v).longValue():def;}
 public String optString(String k,String def){Object v=values.get(k);return v==null?def:String.valueOf(v);}
 public int length(){return values.size();}
 static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 public String toString(){if(raw!=null)return raw;StringJoiner s=new StringJoiner(",","{","}");
 for(Map.Entry<String,Object> e:values.entrySet()){Object v=e.getValue();s.add(quote(e.getKey())+":"+(v instanceof String?quote((String)v):String.valueOf(v)));}return s.toString();}
}''',
"dezz/status/widget/diagnostics/RecorderReplay.java": r'''package dezz.status.widget.diagnostics;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.lang.reflect.*;
public class RecorderReplay {
 static Path folder;static android.content.Context context;
 static void check(boolean v){if(!v)throw new AssertionError();}
 static Object field(String name)throws Exception{Field f=ActionRecorder.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static void exportCompleteEnvelopeWithoutChangingSource()throws Exception{
  ActionRecorder.Session s=ActionRecorder.start("test");
  for(int i=0;i<1200;i++)ActionRecorder.record("test","EVENT",ActionRecorder.object("number",i,"text","Поездка \"2\"\nследующая строка"));
  ActionRecorder.stop("finished");
  Path source=folder.resolve("diagnostics/actions-"+s.id+".jsonl");byte[] original=Files.readAllBytes(source);
  File exported=ActionRecorder.copyLatestForExport(context,true);check(exported!=null);
  check(Arrays.equals(original,Files.readAllBytes(source)));
  File text=ActionRecorder.copyLatestForExport(context,false);check(text!=null);
  check(Arrays.equals(Files.readAllBytes(text.toPath()),Files.readAllBytes(folder.resolve("diagnostics/actions-"+s.id+".txt"))));
  String tail=ActionRecorder.latestTimeline(1000);check(tail.length()<=1000&&tail.contains("SESSION_STOP")&&tail.contains("\n"));
  try(java.util.stream.Stream<Path> files=Files.list(exported.getParentFile().toPath())){check(files.noneMatch(p->p.toString().endsWith(".snapshot")));}
  System.out.println(Base64.getEncoder().encodeToString(Files.readAllBytes(exported.toPath())));
 }
 static void observerNeverWaitsOnFileLockAndOldSessionQueueIsDiscarded()throws Exception{
  ActionRecorder.Session first=ActionRecorder.start("first");
  ThreadPoolExecutor queue=(ThreadPoolExecutor)field("ASYNC");
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  queue.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}});
  check(entered.await(2,TimeUnit.SECONDS));
  synchronized(field("LOCK")){
   CountDownLatch returned=new CountDownLatch(1);
   new Thread(()->{ActionRecorder.recordAsync("test","OLD_KEY",null);returned.countDown();}).start();
   check(returned.await(2,TimeUnit.SECONDS));
  }
  ActionRecorder.stop("stop first");ActionRecorder.Session second=ActionRecorder.start("second");
  release.countDown();CountDownLatch drained=new CountDownLatch(1);queue.execute(drained::countDown);
  check(drained.await(2,TimeUnit.SECONDS));
  ActionRecorder.recordAsync("test","NEW_KEY",null);CountDownLatch done=new CountDownLatch(1);queue.execute(done::countDown);
  check(done.await(2,TimeUnit.SECONDS));ActionRecorder.stop("stop second");
  String current=Files.readString(folder.resolve("diagnostics/actions-"+second.id+".txt"));
  check(!current.contains("OLD_KEY")&&current.contains("NEW_KEY"));
  check(!Files.readString(folder.resolve("diagnostics/actions-"+first.id+".txt")).contains("OLD_KEY"));
 }
 public static void main(String[] args)throws Exception{
  folder=Paths.get(args[1]);context=new android.content.Context(folder.toFile());ActionRecorder.initialize(context);
  RecorderReplay.class.getDeclaredMethod(args[0]).invoke(null);
 }
}'''
}


class ActionRecorderAsyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            file = cls.path / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(source)
            files.append(str(file))
        src = ROOT / "app/src/main/java/dezz/status/widget/diagnostics"
        files += [str(src / name) for name in ("ActionRecorder.java", "BoundedUtf8Tail.java")]
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), *files], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def replay(self, name):
        with tempfile.TemporaryDirectory() as data:
            return subprocess.run(["java", "-cp", str(self.path), "dezz.status.widget.diagnostics.RecorderReplay", name, data],
                                  capture_output=True, text=True, check=True, timeout=15).stdout

    def test_complete_streamed_json_and_txt_snapshot(self):
        result = json.loads(base64.b64decode(self.replay("exportCompleteEnvelopeWithoutChangingSource")))
        self.assertEqual(result["format"], "status-widget-action-session-v1")
        self.assertEqual(len(result["events"]), 1202)
        self.assertEqual(result["events"][1]["details"]["text"], 'Поездка "2"\nследующая строка')
        self.assertEqual(result["events"][-1]["event"], "SESSION_STOP")

    def test_nonblocking_input_and_session_fence(self):
        self.replay("observerNeverWaitsOnFileLockAndOldSessionQueueIsDiscarded")
