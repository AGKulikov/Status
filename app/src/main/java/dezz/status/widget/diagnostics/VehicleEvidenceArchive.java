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
        {"channel-log", "logcat -b all -d -v threadtime | grep -i -E 'IPCP|SOME.?IP|vsomeip|ApVp|cluster_controller|uart|dimprotocol|NaviMode|HUDMode|remoteproc|rpmsg|habmm|vip_proxy' | tail -n 6000"},
        {"kernel-channel-log", "dmesg | grep -i -E 'eth|vlan|can|spi|uart|ttyHS|rpmsg|remoteproc|habmm|hypervisor|dim|display'"}
    };
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
        int files;boolean complete=true;long lastProgress;
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
            try(InputStream in=new BufferedInputStream(new FileInputStream(file))){save("qnx/"+name,status,in);}
            if(tree)files++;
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
    public static SystemFileDump.Report collect(File directory,SystemFileDump.Shell shell,SystemFileDump.Progress progress,
                                                String version,SystemFileDump.Qnx qnx)throws Exception {
        return collect(directory,shell,progress,version,qnx,QnxExclusions.load());
    }
    public static SystemFileDump.Report collect(File directory,SystemFileDump.Shell shell,SystemFileDump.Progress progress,
                                                String version,SystemFileDump.Qnx qnx,QnxExclusions exclusions)throws Exception {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("export_directory_unavailable");
        File part=File.createTempFile("Natro-vehicle-",".part",directory);
        File target=new File(directory,part.getName().replace(".part",".zip"));boolean published=false;
        long started=System.currentTimeMillis();int count;boolean complete;
        try {
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)));
                Sink sink=new Sink(zip,directory,progress,exclusions)) {
                boolean su=false;
                try{AdbShellResult.Result r=shell.command("su 0 id -u");su=r.success()&&!r.truncated&&r.output.trim().equals("0");}
                catch(Exception e){sink.complete=false;}
                for(String[] channel:CHANNELS) {
                    progress.update("Каналы связи: "+channel[0]);String state="collected_snapshot",text;
                    try {
                        AdbShellResult.Result r=shell.commandBounded(SystemFileDump.privileged(channel[1],su),1024*1024);
                        text="exit="+r.exitCode+" truncated="+r.truncated+"\n"+r.output;
                        if(!r.success()||r.truncated)state=r.truncated?"truncated_snapshot":
                                (r.output.trim().isEmpty()?"command_failed_or_unavailable":"partial_snapshot");
                    } catch(Exception e){state="unavailable";text=QnxFileReader.safe(e.toString());}
                    sink.text("channels/"+channel[0]+".txt",state,text);
                }
                try{qnx.collect(sink);}
                catch(Exception e){sink.complete=false;sink.text("qnx/failure.txt","partial_or_unavailable",
                        e instanceof QnxFileReader.Failure?((QnxFileReader.Failure)e).report:QnxFileReader.safe(e.toString()));}
                for(String[] channel:CHANNELS)if(channel[0].equals("channel-log")||channel[0].equals("kernel-channel-log")) {
                    if(Thread.currentThread().isInterrupted())break;
                    try {
                        AdbShellResult.Result r=shell.commandBounded(SystemFileDump.privileged(channel[1],su),1024*1024);
                        sink.text("channels/after-"+channel[0]+".txt",r.truncated?"truncated_snapshot":r.success()?"collected_snapshot":"partial_snapshot",
                                "exit="+r.exitCode+" truncated="+r.truncated+"\n"+r.output);
                    } catch(Exception e){sink.text("channels/after-"+channel[0]+".txt","unavailable",QnxFileReader.safe(e.toString()));}
                }
                sink.text("README.txt","collected_snapshot","Natro "+version+" · full selected QNX directories + passive channel evidence\n"
                        +"Started UTC millis="+started+"; finished="+System.currentTimeMillis()+"\n"
                        +"Roots="+String.join(",",QnxFileReader.FULL_ROOTS)+"\n"
                        +"Contents in qnx/tree preserve paths. Sizes/SHA-256: manifest.tsv. Coverage/errors/aliases: qnx/metadata/full-tree.tsv.\n"
                        +"No file/depth/count quota. Double reads compare observed bytes; this is not an atomic snapshot or restorable firmware image.\n"
                        +"Native lstat/readlink records symlink targets. Canonical selected targets use NOFOLLOW and regular-file checks. Special payloads are not read. Parent-path changes remain possible.\n"
                        +"Previous-dump exclusions="+(exclusions!=null)+"; verified full+partial archives dated 2026-10-17. Matching paths/sizes are skipped without rehashing remote content; same-size edits are not detected.\n"
                        +"Skipped files are listed in full-tree.tsv and remain in the previous archives; this ZIP alone is not a full copy. Live logs, mappings and channels are always refreshed.\n"
                        +"No Android image, VM disk, /dev payload, process memory, vehicle command, service restart or reboot.\n"
                        +"Channel snapshots come from the Android gateway; command availability/permissions vary. No new logger or packet injection.\n"
                        +"198.18.34.1 AP/VP peer is not proven to be the QNX .2 host. A visible interface is not remote filesystem access.\n"
                        +"QNX programs/configs include potential cross-domain routes, not proof of physical effects.\n"
                        +"Contains private/proprietary material. Keep the archive private.\n");
                count=sink.files;complete=sink.complete;sink.finish();
            }
            if(!part.renameTo(target))throw new IOException("archive_publish_failed");published=true;
            int keep=0;for(File old:archives(directory))if(!old.equals(target)&&keep++>=1)old.delete();
            return new SystemFileDump.Report(target,count,count,complete);
        } finally {if(!published)part.delete();}
    }
}
