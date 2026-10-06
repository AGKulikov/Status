/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shell;
import com.tananaev.adblib.AdbProtocol;
import dezz.status.widget.adb.AdbShellResult;
import dezz.status.widget.adb.HudLcaPatch;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdbShellCommandTest {
 @Test public void originalLibraryReproducesPhotoFailureButNewOpenIsAscii() throws Exception {
  String wrapped=new AdbShellResult().wrap(HudLcaPatch.inspect());
  assertThrows(BufferOverflowException.class,()->AdbProtocol.generateOpen(1,"shell:"+wrapped));
  assertTrue(AdbProtocol.generateOpen(1,AdbShellCommand.SERVICE).length<64);
 }
 @Test public void allRealHudScriptsCrossUtf8AndPeerBoundariesWithoutLoss() throws Exception {
  List<String> commands=new ArrayList<>(); commands.add(HudLcaPatch.inspect());
  for(HudLcaPatch.Mode m:HudLcaPatch.Mode.values())commands.add(HudLcaPatch.install(m));
  for(String command:commands) for(int max:new int[]{1,1024,4096,1024*1024}) {
   byte[] script=AdbShellCommand.encode(new AdbShellResult().wrap(command));
   ByteArrayOutputStream received=new ByteArrayOutputStream();
   AdbShellCommand.send(script,max,b->{assertTrue(b.length<=Math.min(4096,max));received.write(b);});
   assertArrayEquals(script,received.toByteArray());
   assertTrue(new String(script,StandardCharsets.UTF_8).contains("запись запрещена"));
  }
 }
 @Test public void actualShellPreservesUnicodeQuotesExitAndLongCommands() throws Exception {
  String text="Русский 'текст' 🌍 $(printf wrong)\nsecond line";
  String longComment=" #"+String.join("",Collections.nCopies(12000,"x"));
  String command="printf '%s' "+AdbShellResult.quote(text)+"; exit 7"+longComment;
  AdbShellResult capture=new AdbShellResult();
  Process shell=new ProcessBuilder("sh").redirectErrorStream(true).start();
  try {
   AdbShellCommand.send(AdbShellCommand.encode(capture.wrap(command)),1024,b->shell.getOutputStream().write(b));
   shell.getOutputStream().close();
   capture.accept(shell.getInputStream().readAllBytes());assertEquals(0,shell.waitFor());
   assertEquals(text,capture.finish().output);assertEquals(Integer.valueOf(7),capture.finish().exitCode);
  } finally {shell.destroyForcibly();}
 }
 @Test public void failedUploadIsNeverRetried() {
  int[] writes={0};
  assertThrows(IOException.class,()->AdbShellCommand.send(AdbShellCommand.encode("echo hello"),1,b->{writes[0]++;throw new IOException("closed");}));
  assertEquals(1,writes[0]);
 }
 @Test public void rejectedInputCannotOpenOrExecute() {
  assertThrows(IllegalArgumentException.class,()->AdbShellCommand.encode("a\0b"));
  assertThrows(IllegalArgumentException.class,()->AdbShellCommand.send(new byte[]{1},0,b->fail()));
 }
}
