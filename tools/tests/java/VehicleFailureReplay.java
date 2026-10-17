/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** A failed optional command must not prevent or erase the primary QNX collection. */
public final class VehicleFailureReplay {
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    static String text(ZipFile z,String name)throws Exception {
        return new String(z.getInputStream(z.getEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception {
        for(String mode:new String[]{"healthy","root","log","missing_exit","qnx","coverage"}) {
            List<String> calls=new ArrayList<>();
            SystemFileDump.Shell shell=new SystemFileDump.Shell() {
                public AdbShellResult.Result command(String c){throw new AssertionError("unbounded shell");}
                public AdbShellResult.Result commandTimed(String c,int limit,long timeout)throws Exception {
                    check(!calls.isEmpty()&&calls.get(0).equals("qnx"),"QNX must precede every shell command");
                    check(timeout==15000&&limit<=1024*1024,"bounded snapshot");calls.add(c);
                    if(c.contains("logcat"))check(c.contains("-d -t 6000")&&!c.contains("|"),"finite logcat source and actual exit");
                    if(mode.equals("root")||mode.equals("log")&&c.contains("logcat")) {
                        AdbShellResult capture=new AdbShellResult();capture.accept(("IPCP partial-log"+"x".repeat(4096)+"partial-tail\nprivate unrelated line\n").getBytes());
                        throw capture.failure("ADB timeout at shell",new IOException("deadline"));
                    }
                    return new AdbShellResult.Result("0",mode.equals("missing_exit")&&c.contains("logcat")?null:0,false);
                }
            };
            SystemFileDump.Report report=VehicleEvidenceArchive.collect(new File(args[0]),shell,s->{},"test",r->{
                calls.add("qnx");
                check(r.previousFile("/bin/screen",382788)!=null,"existing exclusion index retained");
                File f=r.temporaryFile();try {
                    Files.writeString(f.toPath(),"QNX bytes");r.artifact("tree/bin/test","collected_double_read",f);
                }finally{f.delete();}
                if(mode.equals("coverage")) {
                    File coverage=r.temporaryFile();
                    try {
                        Files.writeString(coverage.toPath(),"event\tpath_tsv_escaped\ttype\tsize\tdevice\tinode\tmode_hex\tstatus\tdetail_tsv_escaped\n"
                            +"file\t/lib/old\tregular\t3\t1\t1\t81a4\texcluded_previous_dump\tverified\n"
                            +"file\t/lib/live\tregular\t3\t1\t2\t81a4\tchanged_during_read\tchanged\n"
                            +"probe\t/lib/missing\tunknown\t0\t0\t0\t0\tlink_unavailable\te:No such file or directory\n"
                            +"# traversal_finished=true examined=4\n");
                        r.artifact("metadata/full-tree.tsv","partial_or_unavailable",coverage);
                    } finally {coverage.delete();}
                }
                if(mode.equals("qnx"))throw new IOException("qnx disconnected");
            });
            check(Collections.frequency(calls,"qnx")==1,"no QNX retry");
            try(ZipFile z=new ZipFile(report.file)) {
                check(text(z,"qnx/tree/bin/test").equals("QNX bytes"),"QNX survives later failures");
                check(report.complete==(mode.equals("healthy")),"honest completeness");
                check(z.getEntry("channels/after-channel-log.txt")==null,"no duplicate log replay");
                if(mode.equals("root"))check(calls.size()==2&&text(z,"manifest.tsv").contains("not_attempted"),"stop after root connection loss");
                if(mode.equals("log")||mode.equals("missing_exit")) {
                    check(calls.get(calls.size()-1).contains("logcat"),"no commands after broken stream");
                    check(text(z,"channels/kernel-channel-log.txt").contains("exit=0"),"kernel captured before logcat failure");
                    check(text(z,"channels/channel-log.txt").contains("timeout_ms=15000"),"deadline and stage recorded");
                }
                if(mode.equals("log"))check(text(z,"channels/channel-log.txt").contains("partial-tail"),"partial output beyond 512 chars preserved");
                if(mode.equals("log"))check(!text(z,"channels/channel-log.txt").contains("private unrelated line"),"partial raw log never saved");
                if(mode.equals("qnx"))check(text(z,"qnx/failure.txt").contains("stage=qnx"),"QNX failure distinguished");
                if(mode.equals("coverage")) {
                    check(report.summary.contains("исключено ранее скачанных 1"),"separate exclusion count");
                    check(report.summary.contains("изменились при чтении 1"),"changed bytes still saved");
                    check(report.summary.contains("ошибок/недоступных путей 1"),"missing path count");
                    check(report.summary.contains("Обход завершён"),"completed traversal is distinct from complete data");
                    check(text(z,"summary.txt").trim().equals(report.summary),"HU, LAN and archived summary agree");
                }
                check(z.getEntry("manifest.tsv")!=null&&z.getEntry("README.txt")!=null,"finalized archive");
            }
        }
        System.out.println("PASS QNX-first collection, exclusions, optional timeout/missing exit, partial output and no retries");
    }
}
