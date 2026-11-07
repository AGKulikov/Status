"""Exercise the shipped ANR collector against deterministic Android source failures.

These host fixtures verify control flow and retained evidence, not READ_LOGS access
or DropBox availability on a real ECARX device.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class AnrEvidenceReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-anr-replay-")
        cls.directory = Path(cls.temp.name)
        cls.java = shutil.which("java")
        if not cls.java:
            raise RuntimeError("JDK with java and jdk.compiler is required")
        sources = {
            "android/content/Context.java": r'''
package android.content;
public class Context {
    public static final String DROPBOX_SERVICE="dropbox";
    public Object service;
    public Context getApplicationContext(){return this;}
    public Object getSystemService(String name){return service;}
}
''',
            "android/os/DropBoxManager.java": r'''
package android.os;
public class DropBoxManager {
    public Entry getNextEntry(String tag,long after){return null;}
    public static class Entry implements AutoCloseable {
        public final long time;
        public final String text;
        public boolean closed;
        public int requested;
        public Entry(long time,String text){this.time=time;this.text=text;}
        public long getTimeMillis(){return time;}
        public String getText(int maxBytes){requested=maxBytes;return text;}
        public void close(){closed=true;}
    }
}
''',
            "dezz/status/widget/diagnostics/DiagnosticJournal.java": r'''
package dezz.status.widget.diagnostics;
import java.util.*;
public final class DiagnosticJournal {
    public static volatile boolean enabled=true;
    public static final List<String> lines=Collections.synchronizedList(new ArrayList<>());
    public static boolean isEnabled(){return enabled;}
    public static void warn(String component,String message){lines.add(component+":"+message);}
    public static String text(){synchronized(lines){return String.join("\n",lines);}}
}
''',
            "dezz/status/widget/diagnostics/AnrEvidenceReplay.java": r'''
package dezz.status.widget.diagnostics;
import android.content.Context;
import android.os.DropBoxManager;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class AnrEvidenceReplay {
    static final String PKG="ru.yandex.yandexnavi";
    static final long WALL=100_000;
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void attempt(Context context,int number)throws Exception {
        Method method=AnrEvidence.class.getDeclaredMethod("attempt",Context.class,String.class,long.class,int.class);
        method.setAccessible(true);method.invoke(null,context,PKG,WALL,number);
    }
    static void contains(String value){require(DiagnosticJournal.text().contains(value),"missing "+value+" in "+DiagnosticJournal.text());}
    public static void main(String[] args)throws Exception {
        Context context=new Context();
        switch(args[0]) {
        case "denied":
            context.service=new DropBoxManager(){public Entry getNextEntry(String tag,long after){throw new SecurityException();}};
            attempt(context,0);contains("permission_denied; stack_unavailable; cause_unknown");
            require(!DiagnosticJournal.text().contains("capture_complete"),"denial cannot claim capture");break;
        case "unavailable":
            attempt(context,0);contains("source_unavailable");break;
        case "missing":
            context.service=new DropBoxManager();attempt(context,3);
            contains("report_not_found_after_retries; stack_unavailable; cause_unknown");break;
        case "disabled":
            DiagnosticJournal.enabled=false;
            context.service=new DropBoxManager(){public Entry getNextEntry(String tag,long after){throw new AssertionError("read while disabled");}};
            AnrEvidence.capture(context,PKG,WALL);attempt(context,0);
            require(DiagnosticJournal.lines.isEmpty(),"disabled diagnostics must remain silent");break;
        case "matching": {
            DropBoxManager.Entry wrong=new DropBoxManager.Entry(WALL-4000,"Process: private.other.app\nprivate unrelated stack");
            DropBoxManager.Entry matching=new DropBoxManager.Entry(WALL+1000,"Process: "+PKG+"\nPID: 42\nReason: Input dispatching timed out\n"+"frame\n".repeat(7000));
            context.service=new DropBoxManager(){public Entry getNextEntry(String tag,long after){
                if(!tag.equals("data_app_anr"))return null;
                if(after<wrong.time)return wrong;if(after<matching.time)return matching;return null;
            }};
            attempt(context,0);contains("PID: 42");contains("Input dispatching timed out");contains("report_chunk=3");contains("capture_complete");
            require(!DiagnosticJournal.text().contains("private unrelated stack"),"unrelated process leaked");
            require(wrong.closed&&matching.closed,"entries must close");
            require(matching.requested==262144,"source must remain bounded");
            for(String line:DiagnosticJournal.lines)require(line.length()<11000,"chunk exceeds journal limit");break;
        }
        case "future": {
            DropBoxManager.Entry future=new DropBoxManager.Entry(WALL+120001,"Process: "+PKG+"\nnot this incident");
            context.service=new DropBoxManager(){public Entry getNextEntry(String tag,long after){return future;}};
            attempt(context,3);contains("report_not_found_after_retries");
            require(!DiagnosticJournal.text().contains("not this incident"),"out-of-window report included");
            require(future.closed,"out-of-window entry must close");break;
        }
        case "late": {
            AtomicInteger calls=new AtomicInteger();
            DropBoxManager.Entry late=new DropBoxManager.Entry(WALL+3000,"Process: "+PKG+"\nPID: 43\nmain thread retained after delay");
            context.service=new DropBoxManager(){public Entry getNextEntry(String tag,long after){
                int call=calls.incrementAndGet();return call>2&&tag.equals("data_app_anr")?late:null;
            }};
            AnrEvidence.capture(context,PKG,WALL);
            long until=System.nanoTime()+8_000_000_000L;
            while(!DiagnosticJournal.text().contains("capture_complete")&&System.nanoTime()<until)Thread.sleep(20);
            contains("capture_started");contains("main thread retained after delay");contains("capture_complete");
            require(calls.get()>=3&&late.closed,"late report was not retried and closed");break;
        }
        default: throw new AssertionError("unknown scenario");
        }
        System.out.println("PASS "+args[0]);
    }
}
''',
        }
        paths = []
        for relative, content in sources.items():
            path = cls.directory / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            paths.append(str(path))
        production = ROOT / "app/src/main/java/dezz/status/widget/diagnostics/AnrEvidence.java"
        subprocess.run([cls.java, "com.sun.tools.javac.Main", "-d", str(cls.directory),
                        *paths, str(production)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_source_failures_matching_bounds_and_late_report(self):
        for scenario in ["denied", "unavailable", "missing", "disabled", "matching", "future", "late"]:
            with self.subTest(scenario=scenario):
                result = subprocess.run([self.java, "-cp", str(self.directory),
                                         "dezz.status.widget.diagnostics.AnrEvidenceReplay", scenario],
                                        capture_output=True, text=True, timeout=12)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("PASS " + scenario, result.stdout)


if __name__ == "__main__":
    unittest.main()
