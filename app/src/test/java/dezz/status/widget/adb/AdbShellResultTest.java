/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public final class AdbShellResultTest {
    @Test public void fragmentedUtf8AndExitCode() {
        AdbShellResult capture = new AdbShellResult("TOKEN");
        for (byte value : "Привет, 🌍\nвторая строка\nTOKEN:7\n".getBytes(StandardCharsets.UTF_8)) capture.accept(new byte[]{value});
        assertEquals("Привет, 🌍\nвторая строка", capture.finish().output);
        assertEquals(Integer.valueOf(7), capture.finish().exitCode);
        assertFalse(capture.finish().success());
    }
    @Test public void missingOrForgedMarkerIsNotSuccess() {
        AdbShellResult capture = new AdbShellResult("TOKEN");
        capture.accept("uid=0(root)\nTOKEN:0\nmore output".getBytes(StandardCharsets.UTF_8));
        assertNull(capture.finish().exitCode); assertFalse(capture.finish().success());
    }
    @Test public void hugeOutputIsBoundedAndPreservesExit() {
        AdbShellResult capture = new AdbShellResult("TOKEN"); byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) 'x');
        for (int i = 0; i < 1000; i++) capture.accept(block);
        capture.accept("\nTOKEN:0\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(capture.finish().success()); assertTrue(capture.finish().truncated);
        assertTrue(capture.finish().output.length() < AdbShellResult.OUTPUT_LIMIT + 200);
    }
    @Test public void subshellProtectsFramingFromExitAndComments() {
        String command = new AdbShellResult("TOKEN").wrap("echo 'hello'; exit 4 # comment");
        assertTrue(command.startsWith("sh -c '")); assertTrue(command.contains("'\"'\"'"));
        assertTrue(command.endsWith("\"$?\""));
    }
}
