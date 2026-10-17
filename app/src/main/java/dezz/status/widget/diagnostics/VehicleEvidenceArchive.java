/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** User-requested full QNX ordinary files and passive cross-domain channel evidence. */
public final class VehicleEvidenceArchive {
    private VehicleEvidenceArchive() {}
    // No network probes, traffic injection, device reads, new loggers or Android image copy.
    static final String[][] CHANNELS = {
        {"identity", "id; uname -a; getprop ro.build.fingerprint; date +%s; cat /proc/uptime"},
        {"interfaces", "ip -details link show; ip address show"},
        {"routes", "ip rule show; ip route show table all; ip -6 route show table all; ip neigh show"},
        {"sockets", "ss -anp; cat /proc/net/unix; cat /proc/net/netlink"},
        {"services", "service list; dumpsys -l; lshal"},
        {"bus-devices", "ls -l /sys/class/tty /sys/bus/spi/devices /sys/bus/i2c/devices /sys/bus/rpmsg/devices /sys/class/remoteproc; ls -l /dev/ttyHS* /dev/spidev* /dev/can* /dev/rpmsg*"},
        {"kernel-channel-log", "dmesg | grep -i -E 'eth|vlan|can|spi|uart|ttyHS|rpmsg|remoteproc|habmm|hypervisor|dim|display'"},
        // No tail pipe waiting for all buffers to reach EOF. Bound the source and retain its
        // actual exit status. Only the allowlisted lines are written into the archive below.
        {"channel-log", "logcat -b all -d -t 6000 -v threadtime"}
    };
    private static final java.util.regex.Pattern CHANNEL_LOG = java.util.regex.Pattern.compile(
            "IPCP|SOME.?IP|vsomeip|ApVp|cluster_controller|uart|dimprotocol|NaviMode|HUDMode|remoteproc|rpmsg|habmm|vip_proxy|^logcat:",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    static String channelOutput(String stage,String output) throws IOException {
        if(!stage.equals("channel-log"))return output;
        StringBuilder filtered=new StringBuilder("source=logcat recent_max_lines=6000; allowlist_filter=true; raw_log_not_saved\n");
        try(BufferedReader lines=new BufferedReader(new StringReader(output))) {
            String line;while((line=lines.readLine())!=null)if(CHANNEL_LOG.matcher(line).find())filtered.append(line).append('\n');
        }
        return filtered.toString();
    }
    public static File latest(File directory) {
        File[] files=archives(directory);return files.length==0?null:files[0];
    }
    private static File[] archives(File directory) {
        File[] files=directory.listFiles(f->{try{return f.getName().matches("Natro-vehicle-[0-9]+\\.zip")&&f.isFile()
                &&f.getCanonicalFile().equals(f.getAbsoluteFile());}catch(IOException e){return false;}});
        if(files==null)return new File[0];Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return files;
    }
    private static final class Sink implements QnxFileReader.Receiver,Closeable {
        final ZipOutputStream zip;final File directory,manifest;final Writer rows;
        final SystemFileDump.Progress progress;final Set<String> names=new HashSet<>();
        final QnxExclusions exclusions;
        int files,skipped,changed,unavailable,androidProblems;boolean complete=true;long lastProgress;
        Boolean traversalFinished;
        Sink(ZipOutputStream zip,File directory,SystemFileDump.Progress progress,QnxExclusions exclusions)throws IOException {
            this.zip=zip;this.directory=directory;this.progress=progress;
            this.exclusions=exclusions;
            manifest=File.createTempFile("vehicle-manifest-",".part",directory);
            rows=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(manifest),StandardCharsets.UTF_8));
            rows.write("entry_tsv_escaped\tstatus\tbytes\tsha256\n");
        }
        void save(String name,String status,InputStream input)throws Exception {
            if(!names.add(name))throw new IOException("duplicate_archive_entry");
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long size=0;
            zip.putNextEntry(new ZipEntry(name));
            try {byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1){zip.write(block,0,n);hash.update(block,0,n);size+=n;}}
            finally{zip.closeEntry();}
            StringBuilder sha=new StringBuilder();for(byte b:hash.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
            rows.write(QnxFileReader.tsv(name)+"\t"+status+"\t"+size+"\t"+sha+"\n");
            if(!status.matches("collected_double_read|collected_snapshot|collected_live_file|read"))complete=false;
            if(name.startsWith("channels/")&&!status.equals("collected_snapshot"))androidProblems++;
        }
        void text(String name,String status,String content)throws Exception {
            try(InputStream in=new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))){save(name,status,in);}
        }
        public File temporaryFile()throws IOException{return File.createTempFile("qnx-full-",".part",directory);}
        public void file(String path,String status,byte[] bytes)throws Exception{throw new IOException("streaming_required");}
        public void progress(String text){long now=System.nanoTime();if(now-lastProgress>=500_000_000L){lastProgress=now;progress.update(text);}}
        public String previousFile(String path,long size){return exclusions==null?null:exclusions.previous(path,size);}
        public void artifact(String name,String status,File file)throws Exception {
            boolean tree=name.startsWith("tree/")&&QnxFileReader.fullPath(name.substring(4));
            boolean metadata=name.startsWith("metadata/")&&(Arrays.asList(QnxFileReader.SNAPSHOTS).contains(name.substring(9))
                    ||name.equals("metadata/full-tree.tsv"));
            boolean log=name.startsWith("slog2/")&&QnxFileReader.allowedLog(name.substring(6));
            boolean mapping=name.matches("mappings/[1-9][0-9]{0,9}-wide\\.txt");
            if(!(tree||metadata||log||mapping)||!status.matches("[a-z_]+"))throw new IOException("invalid_full_qnx_artifact");
            if(name.equals("metadata/full-tree.tsv"))readCoverage(file);
            try(InputStream in=new BufferedInputStream(new FileInputStream(file))){save("qnx/"+name,status,in);}
            if(tree)files++;
        }
        private void readCoverage(File file)throws IOException {
            try(BufferedReader input=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))) {
                String line;while((line=input.readLine())!=null) {
                    if(line.startsWith("# traversal_finished="))traversalFinished=line.startsWith("# traversal_finished=true ");
                    if(line.startsWith("#"))continue;
                    String[] cells=line.split("\t",-1);if(cells.length<9)continue;
                    String status=cells[7];
                    if(status.equals("excluded_previous_dump"))skipped++;
                    else if(status.equals("changed_during_read"))changed++;
                    else if(status.matches("open_failed_or_link|link_unavailable|parent_unavailable_or_link|parent_not_directory|stat_unavailable|partial_read_failed|not_attempted_after_failure|read_or_protocol_failed|partial_enumeration|path_rejected|invalid_utf8|invalid_name"))unavailable++;
                }
            }
        }
        String summary() {
            return "QNX: сохранено "+files+" файлов, исключено ранее скачанных "+skipped
                    +", изменились при чтении "+changed+", ошибок/недоступных путей "+unavailable+". "
                    +(traversalFinished==null?"Завершение обхода не подтверждено. ":traversalFinished?"Обход завершён. ":"Обход прерван. ")
                    +"Android: неполных/пропущенных снимков "+androidProblems+". Исключения остаются в прежних архивах.";
        }
        public void snapshot(String name,String status,byte[] bytes)throws Exception {
            if(!Arrays.asList(QnxFileReader.SNAPSHOTS).contains(name))throw new IOException("invalid_qnx_metadata");
            try(InputStream in=new ByteArrayInputStream(bytes)){save("qnx/metadata/"+name,status,in);}
        }
        public void log(String name,String status,byte[] bytes)throws Exception {
            if(!QnxFileReader.allowedLog(name))throw new IOException("invalid_qnx_log");
            try(InputStream in=new ByteArrayInputStream(bytes)){save("qnx/slog2/"+name,status,in);}
        }
        public void close()throws IOException{try{rows.close();}finally{manifest.delete();}}
        void finish()throws IOException {
            rows.flush();zip.putNextEntry(new ZipEntry("manifest.tsv"));
            try(InputStream in=new FileInputStream(manifest)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}
            finally{zip.closeEntry();}
        }
    }
    private static String shellFailure(Exception error,String stage) throws IOException {
        // Failure already bounds stdout to the requested capture limit. Do not truncate it
        // with the 512-character protocol-field sanitizer used for unrelated exceptions.
        if(error instanceof AdbShellResult.Failure&&stage.equals("channel-log")) {
            AdbShellResult.Failure failure=(AdbShellResult.Failure)error;
            String reason=failure.getMessage();int marker=reason.indexOf("\nПолученный вывод");
            if(marker>=0)reason=reason.substring(0,marker);
            return QnxFileReader.safe(reason)+"\nexit=unknown truncated="+failure.partial.truncated
                    +"; partial=true; command_not_repeated=true\n"+channelOutput(stage,failure.partial.output);
        }
        return error instanceof AdbShellResult.Failure?error.getMessage():QnxFileReader.safe(error.toString());
    }
    private static String snapshotHeader(String stage,long started) {
        return "stage="+stage+" phase=after_qnx timeout_ms=15000 elapsed_ms="
                +((System.nanoTime()-started)/1_000_000L)+"\n";
    }
    public static SystemFileDump.Report collect(File directory,SystemFileDump.Shell shell,SystemFileDump.Progress progress,
                                                String version,SystemFileDump.Qnx qnx)throws Exception {
        return collect(directory,shell,progress,version,qnx,QnxExclusions.load());
    }
    public static SystemFileDump.Report collect(File directory,SystemFileDump.Shell shell,SystemFileDump.Progress progress,
                                                String version,SystemFileDump.Qnx qnx,QnxExclusions exclusions)throws Exception {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("export_directory_unavailable");
        File part=File.createTempFile("Natro-vehicle-",".part",directory);
        File target=new File(directory,part.getName().replace(".part",".zip"));boolean published=false;
        long started=System.currentTimeMillis();int count;boolean complete;String summary;
        try {
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)));
                Sink sink=new Sink(zip,directory,progress,exclusions)) {
                // Main payload first: optional Android commands must never consume its connection.
                progress.update("Докачка QNX…");
                long qnxStarted=System.nanoTime();
                try{qnx.collect(sink);}
                catch(Exception e){sink.complete=false;sink.text("qnx/failure.txt","partial_or_unavailable",
                        "stage=qnx elapsed_ms="+((System.nanoTime()-qnxStarted)/1_000_000L)+"\n"
                        +(e instanceof QnxFileReader.Failure?((QnxFileReader.Failure)e).report:QnxFileReader.safe(e.toString())));}
                boolean su=false, stopped=Thread.currentThread().isInterrupted();
                String stopReason=stopped?"interrupted":"";
                if(!stopped) {
                    long probeStarted=System.nanoTime();
                    try {
                        AdbShellResult.Result r=shell.commandTimed("su 0 id -u",128*1024,15_000L);
                        su=r.success()&&!r.truncated&&r.output.trim().equals("0");
                        stopped=r.exitCode==null;
                        stopReason=stopped?"root_probe_missing_exit":"";
                        sink.text("channels/root-probe.txt",stopped?"partial_snapshot":"collected_snapshot",
                                snapshotHeader("root-probe",probeStarted)+"exit="+r.exitCode+" truncated="+r.truncated+"\n"+r.output);
                    } catch(Exception e) {
                        stopped=true;stopReason="root_probe_failed";
                        sink.text("channels/root-probe.txt","unavailable",snapshotHeader("root-probe",probeStarted)+shellFailure(e,"root-probe"));
                    }
                }
                for(String[] channel:CHANNELS) {
                    if(stopped||Thread.currentThread().isInterrupted()) {
                        sink.text("channels/"+channel[0]+".txt","not_attempted",
                                "stage="+channel[0]+" phase=after_qnx reason="+stopReason+"; no automatic reconnect or command retry\n");
                        continue;
                    }
                    progress.update("Каналы связи после QNX: "+channel[0]);
                    long channelStarted=System.nanoTime();String state="collected_snapshot",text;
                    try {
                        AdbShellResult.Result r=shell.commandTimed(SystemFileDump.privileged(channel[1],su),1024*1024,15_000L);
                        text=snapshotHeader(channel[0],channelStarted)+"exit="+r.exitCode+" truncated="+r.truncated+"\n"+channelOutput(channel[0],r.output);
                        if(!r.success()||r.truncated)state=r.truncated?"truncated_snapshot":
                                (r.output.trim().isEmpty()?"command_failed_or_unavailable":"partial_snapshot");
                        if(r.exitCode==null){stopped=true;stopReason=channel[0]+"_missing_exit";}
                    } catch(Exception e){state="unavailable";text=snapshotHeader(channel[0],channelStarted)+shellFailure(e,channel[0]);stopped=true;stopReason=channel[0]+"_failed";}
                    sink.text("channels/"+channel[0]+".txt",state,text);
                }
                summary=sink.summary();
                sink.text("summary.txt","collected_snapshot",summary+"\n");
                sink.text("README.txt","collected_snapshot","Natro "+version+" · full selected QNX directories + passive channel evidence\n"
                        +summary+"\n"
                        +"Started UTC millis="+started+"; finished="+System.currentTimeMillis()+"\n"
                        +"Roots="+String.join(",",QnxFileReader.FULL_ROOTS)+"\n"
                        +"Contents in qnx/tree preserve paths. Sizes/SHA-256: manifest.tsv. Coverage/errors/aliases: qnx/metadata/full-tree.tsv.\n"
                        +"No file/depth/count quota. Double reads compare observed bytes; this is not an atomic snapshot or restorable firmware image.\n"
                        +"Native lstat/readlink records symlink targets. Canonical selected targets use NOFOLLOW and regular-file checks. Special payloads are not read. Parent-path changes remain possible.\n"
                        +"Previous-dump exclusions="+(exclusions!=null)+"; verified full+partial archives dated 2026-10-17. Matching paths/sizes are skipped without rehashing remote content; same-size edits are not detected.\n"
                        +"Skipped files are listed in full-tree.tsv and remain in the previous archives; this ZIP alone is not a full copy. Live logs, mappings and channels are always refreshed.\n"
                        +"No Android image, VM disk, /dev payload, process memory, vehicle command, service restart or reboot.\n"
                        +"QNX is collected before all optional Android snapshots. Snapshots have a 15000 ms transport deadline; failure/missing exit stops subsequent commands, never retries. No before-QNX log capture.\n"
                        +"Android logcat runs last, reads at most6000 recent records before the allowlist filter; older matching events may be absent. No raw logcat is archived.\n"
                        +"Channel snapshots come from the Android gateway; command availability/permissions vary. No new logger or packet injection.\n"
                        +"198.18.34.1 AP/VP peer is not proven to be the QNX .2 host. A visible interface is not remote filesystem access.\n"
                        +"QNX programs/configs include potential cross-domain routes, not proof of physical effects.\n"
                        +"Contains private/proprietary material. Keep the archive private.\n");
                count=sink.files;complete=sink.complete;sink.finish();
            }
            if(!part.renameTo(target))throw new IOException("archive_publish_failed");published=true;
            int keep=0;for(File old:archives(directory))if(!old.equals(target)&&keep++>=1)old.delete();
            return new SystemFileDump.Report(target,count,count,complete,summary);
        } finally {if(!published)part.delete();}
    }
}
