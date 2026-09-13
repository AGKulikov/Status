/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class InputDiagnosticsTest {
    @Test public void mainCannotBeLostBehindOtherThreads() {
        Thread main = new Thread("main");
        Thread media = new Thread("steering-media-command");
        Map<Thread, StackTraceElement[]> stacks = new LinkedHashMap<>();
        StackTraceElement[] trace = {new StackTraceElement("Player", "blocked", "Player.java", 12)};
        for (int i=0; i<100; i++) stacks.put(new Thread("worker-"+i), trace);
        stacks.put(media, trace); stacks.put(main, trace);
        String result = ThreadDumpFormatter.format(main, stacks);
        assertTrue(result.startsWith("THREAD main "));
        assertTrue(result.indexOf("THREAD steering-media-command") < result.indexOf("THREAD worker-0"));
        assertEquals(24, result.split("THREAD ").length - 1);
        assertTrue(result.length() <= 14_000);
    }
    @Test public void boundedDumpStillContainsTheBlockingMainFrame() {
        Thread main=new Thread("main"); Map<Thread,StackTraceElement[]> stacks=new LinkedHashMap<>();
        StackTraceElement[] trace=new StackTraceElement[100];
        java.util.Arrays.fill(trace,new StackTraceElement("Main", "block", "Main.java", 1));
        stacks.put(main,trace);
        assertTrue(ThreadDumpFormatter.format(main,stacks).contains("Main.block"));
        assertEquals(48,ThreadDumpFormatter.format(main,stacks).split("  at ").length-1);
    }
    @Test public void mediaCodesSurviveWithoutExposingCredentials() {
        String value=DiagnosticJournal.redact("key_code=87, after_key_code=88, session_id=ab12, "
                + "token=secret123, key=secret456, password=secret789");
        assertTrue(value.contains("key_code=87"));assertTrue(value.contains("after_key_code=88"));
        assertTrue(value.contains("session_id=ab12"));assertFalse(value.contains("secret123"));
        assertFalse(value.contains("secret456"));assertFalse(value.contains("secret789"));
    }
}
