/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class SystemFileDumpReplay {
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    static AdbShellResult.Result result(String s) { return new AdbShellResult.Result(s,0,false); }
    static String manifest(SystemFileDump.Report report) throws Exception {
        try(ZipFile z=new ZipFile(report.file)) { return new String(z.getInputStream(z.getEntry("manifest.tsv")).readAllBytes(),StandardCharsets.UTF_8); }
    }
    public static void main(String[] args) throws Exception {
        File directory=new File(args[0]);
        byte[] raw=new byte[65539]; for(int i=0;i<raw.length;i++)raw[i]=(byte)i;
        String sha=SystemFileDump.digest(raw);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        SystemFileDump.Shell good=command -> {
            check(!command.contains("root:") && !command.contains("remount") && !command.contains("reboot"));
            if(command.equals("id -u"))return result("2000");
            if(command.equals("su 0 id -u"))return result("0");
            if(command.startsWith("uname")) return result("test kernel\ncustom fingerprint");
            check(command.startsWith("su 0 sh -c "));
            // Recover nested shell quoting only inside the inert test transport.
            String script=command.substring("su 0 sh -c '".length(),command.length()-1).replace("'\"'\"'", "'");
            if(script.startsWith("date +%s")||script.startsWith("dumpsys "))return result("graphics metadata");
            if(script.contains("NATRO_DLT_LIST"))return result("NATRO_DLT_LIST\nNATRO_DLT_END");
            if(script.startsWith("if "))return result(""+raw.length);
            if(script.startsWith("sha256sum "))return result(sha+"  "+script.substring(11,script.length()-1));
            if(script.startsWith("dd ")) {
                reads.incrementAndGet();
                int skip=Integer.parseInt(script.split(" skip=")[1].split(" ")[0]);
                return result(Base64.getMimeEncoder().encodeToString(Arrays.copyOfRange(raw,skip*32768,Math.min(raw.length,(skip+1)*32768))));
            }
            throw new AssertionError(script);
        };
        SystemFileDump.Report full=SystemFileDump.collect(directory,good,s->{},"test");
        check(full.collected==9 && reads.get()==27 && full.complete);
        try(ZipFile zip=new ZipFile(full.file)) {
            check(Arrays.equals(raw,zip.getInputStream(zip.getEntry("files/vendor/lib/modules/dim_dtc.ko")).readAllBytes()));
            check(zip.size()==12);
        }
        check(manifest(full).contains("collected\t65539\t"+sha));
        SystemFileDump.Report qnxFailure=SystemFileDump.collect(directory,
                c->c.contains("readlink -f")?result("UART"):good.command(c),s->{},"test",
                receiver->{throw new IOException("connection refused");});
        check(qnxFailure.collected==9);
        try(ZipFile zip=new ZipFile(qnxFailure.file)) {
            String details=new String(zip.getInputStream(zip.getEntry("qnx-diagnostics.txt")).readAllBytes(),StandardCharsets.UTF_8);
            check(details.contains("connection refused")&&details.contains("result=failed"));
            check(manifest(qnxFailure).contains("not_attempted_after_qnx_failure"));
        }
        SystemFileDump.Report perFile=SystemFileDump.collect(directory,
                c->c.contains("readlink -f")?result("UART"):good.command(c),s->{},"test",receiver->{
                    receiver.diagnostic(QnxFileReader.TARGETS[0],"stage=open\nlast_control_reply=e:2\n");
                    receiver.file(QnxFileReader.TARGETS[0],"remote_open_failed",null);
                    receiver.diagnostic(QnxFileReader.TARGETS[1],"stage=connect\n");
                    receiver.file(QnxFileReader.TARGETS[1],"collected_double_read",raw);
                    receiver.diagnostic(QnxFileReader.TARGETS[2],"stage=connect\n");
                    IOException failed=new IOException("socket closed");
                    failed.addSuppressed(new IOException("ADB timeout at qnx-file"));throw failed;
                });
        check(perFile.collected==10);
        try(ZipFile zip=new ZipFile(perFile.file)) {
            String details=new String(zip.getInputStream(zip.getEntry("qnx-diagnostics.txt")).readAllBytes(),StandardCharsets.UTF_8);
            check(details.contains("last_control_reply=e:2")&&details.contains("ADB timeout at qnx-file"));
            check(manifest(perFile).contains("qnx:"+QnxFileReader.TARGETS[2]+"\tread_or_protocol_failed"));
            check(manifest(perFile).contains("qnx:"+QnxFileReader.TARGETS[3]+"\tnot_attempted_after_qnx_failure"));
            check(Arrays.equals(raw,zip.getInputStream(zip.getEntry("qnx/files"+QnxFileReader.TARGETS[1])).readAllBytes()));
        }
        SystemFileDump.Report metadata=SystemFileDump.collect(directory,
                c->c.contains("readlink -f")?result("UART"):good.command(c),s->{},"test",receiver->{
                    for(String path:QnxFileReader.TARGETS)receiver.file(path,"collected_double_read",raw);
                    receiver.snapshot("processes.tsv","partial_or_unavailable","pid\tstatus\texecutable\n".getBytes(StandardCharsets.UTF_8));
                    receiver.snapshot("graphics-mappings.txt","partial_or_unavailable","partial\n".getBytes(StandardCharsets.UTF_8));
                    throw new IOException("process metadata timeout");
                });
        check(metadata.collected==9+QnxFileReader.TARGETS.length && !metadata.complete);
        try(ZipFile zip=new ZipFile(metadata.file)) {
            check(zip.getEntry("qnx/metadata/processes.tsv")!=null);
            check(manifest(metadata).contains("qnx-meta:processes.tsv\tpartial_or_unavailable"));
            check(manifest(metadata).contains("qnx-meta:graphics-mappings.txt\tpartial_or_unavailable"));
        }
        SystemFileDump.Report rejected=SystemFileDump.collect(directory,
                c->c.contains("readlink -f")?result("UART"):good.command(c),s->{},"test",receiver->{
                    receiver.snapshot("../escape","collected_snapshot",new byte[1]);
                });
        check(manifest(rejected).contains("qnx-meta:processes.tsv\tnot_collected"));
        for(String bad:new String[]{"a","!!!!","AA==junk","AB=="}) {
            try{SystemFileDump.decode(bad,1);throw new AssertionError();}catch(IOException expected){}
        }
        for(String status:new String[]{"permission_denied","absent_or_inaccessible","link_rejected","not_regular","8388609"}) {
            SystemFileDump.Report r=SystemFileDump.collect(directory,c -> c.contains("stat -c")?result(status):good.command(c),s->{},"test");
            check(r.collected==0); check(manifest(r).contains(status.equals("8388609")?"size_limit":status));
        }
        SystemFileDump.Report corrupt=SystemFileDump.collect(directory,c -> c.contains("dd if=")?result(Base64.getEncoder().encodeToString(new byte[32768])):good.command(c),s->{},"test");
        check(corrupt.collected==0);check(!manifest(corrupt).contains("\tcollected\t"));
        for(int mode=0;mode<3;mode++) {
            final int testMode=mode;
            SystemFileDump.Report failed=SystemFileDump.collect(directory,c -> {
                if(c.contains("dd if=")) {
                    if(testMode==0)throw new IOException("socket closed");
                    if(testMode==1)return new AdbShellResult.Result("",null,false);
                    return new AdbShellResult.Result("",0,true);
                }
                return good.command(c);
            },s->{},"test");
            check(failed.collected==0); check(manifest(failed).contains("not_attempted_after_failure"));
        }
        // Execute the production read-only probe against local disposable regular/symlink/FIFO paths.
        File regular=new File(directory,"source.bin"); Files.write(regular.toPath(),raw);
        Process p=new ProcessBuilder("sh","-c",SystemFileDump.probe(regular.getAbsolutePath())).start();
        check(new String(p.getInputStream().readAllBytes()).trim().equals("65539"));check(p.waitFor()==0);
        ByteArrayOutputStream transferred=new ByteArrayOutputStream();
        for(int offset=0;offset<raw.length;offset+=SystemFileDump.CHUNK) {
            p=new ProcessBuilder("sh","-c",SystemFileDump.chunkCommand(regular.getAbsolutePath(),offset)).start();
            transferred.write(SystemFileDump.decode(new String(p.getInputStream().readAllBytes()).trim(),Math.min(SystemFileDump.CHUNK,raw.length-offset)));
            check(p.waitFor()==0);
        }
        check(Arrays.equals(raw,transferred.toByteArray()));
        Path link=new File(directory,"link").toPath();Files.createSymbolicLink(link,regular.toPath());
        p=new ProcessBuilder("sh","-c",SystemFileDump.probe(link.toString())).start();
        check(new String(p.getInputStream().readAllBytes()).trim().equals("link_rejected"));check(p.waitFor()==0);
        File unrelated=new File(directory,"unrelated.zip");Files.write(unrelated.toPath(),new byte[]{7});
        SystemFileDump.collect(directory,good,s->{},"test");
        check(unrelated.isFile());check(SystemFileDump.latest(directory)!=null);
        check(directory.listFiles(f->f.getName().startsWith("Natro-system-")&&f.getName().endsWith(".zip")).length<=2);
        check(directory.listFiles(f->f.getName().endsWith(".part")).length==0);
        check(Arrays.equals(raw,Files.readAllBytes(regular.toPath())));
        System.out.println("PASS binary/UTF8/NUL, chunks, canonical base64, SHA, limits, errors, links, retention; inert ADB only");
    }
}
