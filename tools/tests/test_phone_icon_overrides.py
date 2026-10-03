import subprocess
import tempfile
import unittest
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class PhoneIconOverridesTest(unittest.TestCase):
    def test_real_store_persistence_reset_and_failed_write(self):
        jar = os.environ.get('NATRO_ANDROID_JSON_JAR')
        if not jar:
            self.skipTest('Set NATRO_ANDROID_JSON_JAR to the Android org.json JVM jar')
        source = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import dezz.status.widget.phone.PhoneIconOverrides;
public class IconReplay {
 static void require(boolean value) { if (!value) throw new AssertionError(); }
 static void fails(RunnableIO call) throws Exception { try {call.run();} catch(IOException expected){return;} throw new AssertionError("Accepted invalid operation"); }
 interface RunnableIO {void run() throws Exception;}
 public static void main(String[] args) throws Exception {
  File dir = new File(args[0], "icons");
  PhoneIconOverrides s = new PhoneIconOverrides(dir);
  byte[] a = {1,2,3}, b = {4,5,6};
  s.put("com.example.app", "Имя приложения", a);
  require(Arrays.equals(a, Files.readAllBytes(s.icon("com.example.app").toPath())));
  PhoneIconOverrides reopened = new PhoneIconOverrides(dir);
  require(reopened.entries().get("com.example.app").name.equals("Имя приложения"));
  reopened.put("com.example.app", "Новое имя", b);
  require(Arrays.equals(b, Files.readAllBytes(new PhoneIconOverrides(dir).icon("com.example.app").toPath())));
  fails(() -> reopened.put("../../outside", "bad", a));
  fails(() -> reopened.put("com.example.app", "", a));
  require(Arrays.equals(b, Files.readAllBytes(reopened.icon("com.example.app").toPath())));
  reopened.reset("com.example.app");
  require(new PhoneIconOverrides(dir).icon("com.example.app") == null);
  require(new PhoneIconOverrides(dir).entries().containsKey("com.example.app"));
  File blockedDir = new File(args[0], "blocked"); blockedDir.mkdirs();
  PhoneIconOverrides blocked = new PhoneIconOverrides(blockedDir); blocked.entries();
  new File(blockedDir,"catalog.json").mkdir();
  fails(() -> blocked.put("com.example.app", "Name", a));
  require(blocked.entries().isEmpty());
  File broken = new File(args[0], "broken"); broken.mkdirs();
  Files.write(new File(broken,"catalog.json").toPath(), "broken".getBytes());
  fails(() -> new PhoneIconOverrides(broken).put("com.example.app", "Name", a));
  require(new String(Files.readAllBytes(new File(broken,"catalog.json").toPath())).equals("broken"));
  System.out.println("PASS: persistence, replacement, reset, traversal rejection, failed write, corrupt catalog");
 }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            (temp / 'IconReplay.java').write_text(source)
            production = ROOT / 'app/src/main/java/dezz/status/widget/phone/PhoneIconOverrides.java'
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-cp', jar, '-d', directory,
                            str(production), str(temp / 'IconReplay.java')], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory + os.pathsep + jar,
                                     'IconReplay', directory], check=True, capture_output=True, text=True)
            self.assertIn('PASS:', result.stdout)

    def test_restore_queue_uses_catalog_without_new_notifications(self):
        jar = os.environ.get('NATRO_ANDROID_JSON_JAR')
        if not jar:
            self.skipTest('Set NATRO_ANDROID_JSON_JAR')
        source = (ROOT / 'app/src/main/java/dezz/status/widget/phone/PhoneAppIconStore.java').read_text()
        def method(signature):
            start = source.index(signature)
            brace = source.index('{', start)
            depth = 1
            end = brace + 1
            while depth:
                depth += (source[end] == '{') - (source[end] == '}')
                end += 1
            return source[start:end]
        methods = method('public synchronized void retryMissingIcons()') + method('public synchronized JSONObject automaticDownloadManifest()')
        replay = r"""
import java.io.File;
import java.util.*;
import org.json.*;
public class QueueReplay {
 static class Record {String identifier,name,sourceUrl="https://example.invalid/icon",iconType="png"; Record(String id){identifier=id;name=id;}}
 Map<String,Record> records=new LinkedHashMap<>(); Set<String> downloads=new HashSet<>();
 List<Runnable> queued=new ArrayList<>(); java.util.concurrent.Executor worker=queued::add;
 File customIcon(String id){return id.equals("custom")?new File("custom"):null;}
 File iconFile(Record r){return r.identifier.equals("cached")?new File("cached"):null;}
 void downloadIcon(String id){downloads.remove(id);}
 METHODS
 public static void main(String[] args) throws Exception {
  QueueReplay q=new QueueReplay();
  for(String id:new String[]{"missing","cached","custom"}) q.records.put(id,new Record(id));
  q.retryMissingIcons(); q.retryMissingIcons();
  if(q.queued.size()!=1 || !q.downloads.contains("missing")) throw new AssertionError("duplicate/custom/cached queued");
  q.queued.remove(0).run(); q.retryMissingIcons();
  if(q.queued.size()!=1)throw new AssertionError("failed download cannot retry");
  JSONArray list=q.automaticDownloadManifest().getJSONArray("apps");
  if(list.length()!=3 || !list.getJSONObject(0).getString("id").equals("missing")
    || !list.getJSONObject(0).has("source_url"))throw new AssertionError("manifest lost mapping");
 }
}
""".replace('METHODS', methods)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'QueueReplay.java'
            path.write_text(replay)
            subprocess.run(['java','com.sun.tools.javac.Main','-cp',jar,'-d',directory,str(path)],check=True,capture_output=True)
            subprocess.run(['java','-cp',directory+os.pathsep+jar,'QueueReplay'],check=True,capture_output=True)
