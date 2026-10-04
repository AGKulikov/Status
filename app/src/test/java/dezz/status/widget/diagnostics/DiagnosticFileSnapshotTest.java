/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class DiagnosticFileSnapshotTest {
    @Test public void completeLinesSurviveAnInterruptedUtf8Append() throws Exception {
        File f=File.createTempFile("diag", ".log");
        try {Files.write(f.toPath(),"first\nкириллица\npartial".getBytes(StandardCharsets.UTF_8));
            DiagnosticFileSnapshot s=DiagnosticFileSnapshot.read(f,1000);
            assertEquals("first\nкириллица\n",s.text);assertEquals(7,s.partialTailBytes);assertFalse(s.truncated);
        } finally {f.delete();}
    }
    @Test public void boundedTailSkipsCutPrefixAndCutSuffix() throws Exception {
        File f=File.createTempFile("diag", ".log");
        try {Files.write(f.toPath(),"oldoldold\nkeep1\nkeep2\ncut".getBytes(StandardCharsets.UTF_8));
            DiagnosticFileSnapshot s=DiagnosticFileSnapshot.read(f,18);
            assertEquals("keep1\nkeep2\n",s.text);assertEquals(3,s.partialTailBytes);assertTrue(s.truncated);
        } finally {f.delete();}
    }
    @Test public void redactWholeExportDoesNotSilentlyTruncateAtOneMessageLimit() {
        StringBuilder data=new StringBuilder();for(int i=0;i<2000;i++)data.append("event-").append(i).append(" token=hidden-value\n");
        String safe=DiagnosticBundle.redactLines(data.toString());
        assertTrue(safe.length()>16000);assertTrue(safe.contains("event-1999"));assertFalse(safe.contains("hidden-value"));
    }
}
