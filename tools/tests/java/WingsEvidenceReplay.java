/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public final class WingsEvidenceReplay {
    static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    static String read(ZipFile z,String name)throws Exception { return new String(z.getInputStream(z.getEntry(name)).readAllBytes(),StandardCharsets.UTF_8); }
    static class Shell implements SystemFileDump.Shell {
        byte[] png;String path;int chunks,captures,calls;boolean fail,corrupt,truncated,deny;
        Shell(byte[] png) { this.png=png; }
        public AdbShellResult.Result command(String cmd) { throw new AssertionError("untimed command"); }
        public AdbShellResult.Result commandTimed(String cmd,int limit,long timeout)throws Exception {
            calls++;check(timeout>0&&timeout<=15000,"deadline");check(limit<=1024*1024,"output bound");
            check(!cmd.contains("su 0")&&!cmd.contains("settings put")&&!cmd.contains("service call"),"passive");
            if(fail&&cmd.equals("dumpsys display"))throw new IOException("lost");
            String out="snapshot";int code=0;
            if(cmd.equals("getprop ro.build.version.sdk"))out="28\n";
            else if(cmd.equals("dumpsys display"))out="Display 2 layerStack=2\n  mBuiltInDisplayId=0\n  mBuiltInDisplayId=1\n";
            else if(cmd.equals("screencap -h"))out="usage: screencap [-hp] [-d display-id] [FILENAME]";
            else if(cmd.startsWith("umask")) {
                captures++;check(!cmd.contains("-d 2"),"no logical ID guessing");
                Matcher m=Pattern.compile("'(/data/local/tmp/natro-wings-[a-z0-9-]+/frame.png)'").matcher(cmd);
                check(m.find(),"private path");path=m.group(1);code=deny?1:0;
            } else if(cmd.startsWith("if [ -L"))out=""+png.length;
            else if(cmd.startsWith("sha256sum"))out=(corrupt?"0".repeat(64):SystemFileDump.digest(png))+"  "+path;
            else if(cmd.startsWith("dd if=")) {
                chunks++;Matcher m=Pattern.compile("skip=([0-9]+)").matcher(cmd);check(m.find(),"chunk offset");
                int offset=Integer.parseInt(m.group(1))*SystemFileDump.CHUNK;
                out=Base64.getEncoder().encodeToString(Arrays.copyOfRange(png,offset,Math.min(png.length,offset+SystemFileDump.CHUNK)));
            }
            return new AdbShellResult.Result(out,code,truncated&&cmd.equals("dumpsys SurfaceFlinger"));
        }
    }
    public static void main(String[] args)throws Exception {
        BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);Random random=new Random(7);
        for(int y=0;y<256;y++)for(int x=0;x<256;x++)image.setRGB(x,y,random.nextInt());
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);byte[] png=out.toByteArray();
        check(WingsEvidenceArchive.pngDimensions(png).equals("256x256"),"real PNG");
        byte[] bad=png.clone();bad[40]^=1;
        try {WingsEvidenceArchive.pngDimensions(bad);throw new AssertionError("bad CRC accepted");}catch(IOException expected){}
        try {WingsEvidenceArchive.pngDimensions(Arrays.copyOf(png,png.length-12));throw new AssertionError("missing IEND");}catch(IOException expected){}
        check(WingsEvidenceArchive.captureIds("29","-d","mBuiltInDisplayId=2").isEmpty(),"SDK namespace gate");
        check(WingsEvidenceArchive.captureIds("28","-d","displayId=2\nlayerStack=2").isEmpty(),"logical IDs rejected");
        check(WingsEvidenceArchive.captureIds("28","-d","mBuiltInDisplayId=0\nmBuiltInDisplayId=0\nmBuiltInDisplayId=1").equals(Arrays.asList(0,1)),"deduplicate");
        File dir=Files.createTempDirectory("wings-replay").toFile();
        Shell shell=new Shell(png);
        SystemFileDump.Report r=WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","visible");
        check(WingsEvidenceArchive.latest(dir).equals(r.file),"reopen discovers completed archive");
        check(r.complete&&shell.captures==2&&shell.chunks>2,"multi-chunk images");
        try(ZipFile z=new ZipFile(r.file)) {
            check(Arrays.equals(png,z.getInputStream(z.getEntry("images/builtin-1.png")).readAllBytes()),"exact payload");
            check(read(z,"images/builtin-1.txt").contains("remote_cleanup=removed"),"cleanup");
            check(read(z,"session.txt").contains("owner_attribution=OPEN"),"no false owner claim");
            check(read(z,"manifest.tsv").contains("start_elapsed_ms"),"timings");
        }
        shell=new Shell(png);shell.corrupt=true;
        r=WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","normal");
        check(!r.complete,"hash mismatch partial");
        try(ZipFile z=new ZipFile(r.file)){check(z.getEntry("images/builtin-0.png")==null,"reject corrupt image");}
        shell=new Shell(png);shell.fail=true;
        r=WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","visible");
        check(WingsEvidenceArchive.latest(dir).equals(r.file),"reopen discovers partial archive");
        check(!r.complete&&shell.calls==5&&shell.captures==0,"stop after transport error");
        try(ZipFile z=new ZipFile(r.file)){check(read(z,"manifest.tsv").contains("not_attempted"),"partial manifest");}
        check(dir.listFiles(f->f.getName().endsWith(".zip")).length==2,"own retention");
        shell=new Shell(png);shell.truncated=true;
        check(!WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","visible").complete,"truncation is partial");
        shell=new Shell(png);shell.deny=true;
        check(!WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","visible").complete,"denied image is partial");
        Thread.currentThread().interrupt();shell=new Shell(png);
        r=WingsEvidenceArchive.collect(dir,shell,t->{},"3.3.0","normal");
        Thread.interrupted();check(!r.complete&&shell.calls==0,"cancel preserves empty archive manifest");
        System.out.println("PASS WingsEvidenceReplay: bounds, CRC, IDs, SHA, chunks, cleanup, truncation, denied capture, transport, retention, cancel");
    }
}
