/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Explicit firmware evidence export plus the full accessible QNX namespace inventory. */
public final class SystemFileDump {
    public interface Shell {
        AdbShellResult.Result command(String command) throws Exception;
        default AdbShellResult.Result commandBounded(String command, int limit) throws Exception { return command(command); }
        default AdbShellResult.Result commandTimed(String command, int limit, long timeoutMs) throws Exception {
            throw new IOException("timed_shell_not_supported");
        }
    }
    public interface Progress { void update(String text); }
    public interface Qnx { void collect(QnxFileReader.Receiver receiver) throws Exception; }
    public static final AtomicBoolean RUNNING = new AtomicBoolean();
    static final int CHUNK = 32768, MAX_FILE = 8 * 1024 * 1024, MAX_TOTAL = 64 * 1024 * 1024;
    private static final String[] PATHS = {
        "/vendor/lib/modules/dim_dtc.ko", "/vendor_dlkm/lib/modules/dim_dtc.ko",
        "/system/lib/modules/dim_dtc.ko", "/vendor/bin/init.visteon-dim.sh",
        "/vendor/bin/init.visteon.sh", "/vendor/etc/init/hw/init.qcom.rc",
        "/system/bin/uart-tool", "/vendor/lib64/libuart.so",
        "/vendor/bin/hw/vendor.ecarx.xma.dimprotocol@1.0-service"
    };
    private SystemFileDump() {}
    public static File latest(File directory) {
        File[] files=archives(directory);
        return files.length==0 ? null : files[0];
    }
    private static File[] archives(File directory) {
        File[] files=directory.listFiles(file -> {
            try { return file.getName().matches("Natro-system-[0-9]+\\.zip") && file.isFile()
                    && file.getCanonicalFile().equals(file.getAbsoluteFile()); }
            catch(IOException error) { return false; }
        });
        if(files==null) return new File[0];
        java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        return files;
    }
    public static final class Report {
        public final File file;
        public final int collected, requested;
        public final boolean complete;
        public final String summary;
        Report(File file, int collected, int requested, boolean complete) {
            this(file,collected,requested,complete,"");
        }
        Report(File file, int collected, int requested, boolean complete,String summary) {
            this.file=file; this.collected=collected; this.requested=requested;this.complete=complete;
            this.summary=summary;
        }
    }
    static String output(AdbShellResult.Result result) throws IOException {
        if (!result.success() || result.truncated) throw new IOException("shell_incomplete");
        return result.output.trim();
    }
    static String privileged(String script, boolean su) {
        return su ? "su 0 sh -c " + AdbShellResult.quote(script) : script;
    }
    static String probe(String path) {
        String q=AdbShellResult.quote(path);
        // Do not open links, device nodes, directories or unbounded pseudo-files.
        return "if [ -L " + q + " ]; then echo link_rejected; "
            + "elif [ ! -e " + q + " ]; then echo absent_or_inaccessible; "
            + "elif [ ! -f " + q + " ]; then echo not_regular; "
            + "elif [ ! -r " + q + " ]; then echo permission_denied; "
            + "else stat -c %s " + q + "; fi";
    }
    static String hash(Shell shell, String path, boolean su) throws Exception {
        String result=output(shell.command(privileged("sha256sum " + AdbShellResult.quote(path),su)));
        String[] fields=result.split("\\s+",2);
        if(fields.length!=2 || !fields[0].matches("[0-9a-fA-F]{64}") || !fields[1].equals(path))
            throw new IOException("invalid_remote_hash");
        return fields[0].toLowerCase(Locale.ROOT);
    }
    static byte[] decode(String encoded, int expected) throws IOException {
        String compact=encoded.replace("\r", "").replace("\n", "");
        if(compact.length()!=4*((expected+2)/3) || !compact.matches("[A-Za-z0-9+/]*={0,2}"))
            throw new IOException("invalid_base64");
        try {
            byte[] bytes=Base64.getDecoder().decode(compact);
            if(bytes.length!=expected || !Base64.getEncoder().encodeToString(bytes).equals(compact))
                throw new IOException("short_or_noncanonical_chunk");
            return bytes;
        } catch(IllegalArgumentException bad) { throw new IOException("invalid_base64",bad); }
    }
    static String chunkCommand(String path, int offset) {
        return "dd if=" + AdbShellResult.quote(path) + " bs=" + CHUNK
                + " skip=" + (offset/CHUNK) + " count=1 2>/dev/null | base64";
    }
    static String digest(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    /** Caller supplies a private, cancellable ADB dump session with per-I/O deadlines. */
    public static Report collect(File directory, Shell shell, Progress progress, String version) throws Exception {
        return collect(directory,shell,progress,version,null);
    }
    public static Report collect(File directory, Shell shell, Progress progress, String version, Qnx qnx) throws Exception {
        return collect(directory,shell,progress,version,qnx,"unspecified");
    }
    public static Report collect(File directory, Shell shell, Progress progress, String version, Qnx qnx,String captureState) throws Exception {
        if(!java.util.Arrays.asList("unspecified","normal","navigation").contains(captureState))
            throw new IOException("invalid_capture_state");
        final long startWall=System.currentTimeMillis(),startMono=System.nanoTime();
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("export_directory_unavailable");
        File partial=File.createTempFile("Natro-system-", ".part", directory);
        File target=new File(directory,partial.getName().replace(".part", ".zip"));
        boolean published=false, qnxComplete=qnx==null;
        int count=0,total=0;
        StringBuilder manifest=new StringBuilder("path\tstatus\tbytes\tsha256\n");
        try {
            boolean su=false;
            String access="adb_shell", failure=null;
            try {
                String uid=output(shell.command("id -u"));
                if(!uid.matches("[0-9]+")) throw new IOException("invalid_uid");
                if(uid.equals("0")) access="adb_uid_0";
                else {
                    AdbShellResult.Result root=shell.command("su 0 id -u");
                    if(root.success() && !root.truncated && root.output.trim().equals("0")) { su=true; access="su_0"; }
                }
            } catch(Exception unavailable) { failure="transport_or_identity_failed"; }
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
                if(failure==null) try {
                    String metadata=output(shell.command("uname -a; getprop ro.build.fingerprint; getprop ro.vendor.build.fingerprint"));
                    put(zip,"firmware.txt",metadata.getBytes(StandardCharsets.UTF_8));
                } catch(Exception unavailable) {
                    put(zip,"firmware.txt","metadata_unavailable\n".getBytes(StandardCharsets.UTF_8));
                }
                int attempted=0;
                for(String path:PATHS) {
                    progress.update("Сбор " + (++attempted) + "/" + PATHS.length + ": " + path);
                    String status=failure, sha="-"; int size=0; byte[] bytes=null;
                    if(status==null) try {
                        String value=output(shell.command(privileged(probe(path),su)));
                        if(!value.matches("[0-9]{1,12}")) {
                            status=value.matches("link_rejected|absent_or_inaccessible|not_regular|permission_denied")
                                ? value : "invalid_size_reply";
                        } else {
                            long length=Long.parseLong(value);
                            if(length>MAX_FILE || total+length>MAX_TOTAL) status="size_limit";
                            else {
                                size=(int)length;
                                String before=hash(shell,path,su);
                                ByteArrayOutputStream data=new ByteArrayOutputStream(size);
                                for(int offset=0;offset<size;offset+=CHUNK) {
                                    if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                                    // Base64 is bounded below shell-v1's 128KiB text capture. No binary UTF-8 conversion.
                                    String command=chunkCommand(path,offset);
                                    data.write(decode(output(shell.command(privileged(command,su))),Math.min(CHUNK,size-offset)));
                                }
                                bytes=data.toByteArray();
                                sha=digest(bytes);
                                String after=hash(shell,path,su);
                                String finalSize=output(shell.command(privileged(probe(path),su)));
                                if(!before.equals(sha) || !after.equals(sha) || !value.equals(finalSize)) {
                                    status="changed_or_corrupt"; bytes=null;
                                } else status="collected";
                            }
                        }
                    } catch(Exception error) {
                        status="read_or_transport_failed"; bytes=null;
                        // No reconnect, retry or claims of success after a broken stream.
                        failure="not_attempted_after_failure";
                    }
                    if(bytes!=null) { put(zip,"files"+path,bytes); total+=bytes.length; count++; }
                    manifest.append(path).append('\t').append(status).append('\t')
                        .append(bytes==null ? 0 : size).append('\t').append(bytes==null ? "-" : sha).append('\n');
                }
                if(qnx!=null) {
                    // Pseudo-files are not firmware payloads; collect only exact bounded UART evidence.
                    String uart="readlink -f /sys/class/tty/ttyHS2/device; "
                            + "for p in /sys/class/tty/ttyHS2/device/uevent /sys/class/tty/ttyHS2/device/of_node/name "
                            + "/sys/class/tty/ttyHS2/device/of_node/compatible; do echo \"$p\"; "
                            + "if [ -r \"$p\" ]; then head -c 4096 \"$p\"; else echo unavailable; fi; echo; done; "
                            + "dmesg | grep -i -E 'ttyHS|geni.*serial|890000' | head -c 24000";
                    try {
                        AdbShellResult.Result r=shell.command(privileged(uart,su));
                        String context="exit="+r.exitCode+" truncated="+r.truncated+"\n"+r.output;
                        put(zip,"uart-context.txt",context.getBytes(StandardCharsets.UTF_8));
                    } catch(Exception unavailable) {
                        put(zip,"uart-context.txt","read_or_transport_failed\n".getBytes(StandardCharsets.UTF_8));
                    }
                    final int[] qcount={0}, qtotal={total};
                    final java.util.Set<String> qnxAttempted=new java.util.HashSet<>();
                    final java.util.Set<String> qnxStarted=new java.util.HashSet<>();
                    final java.util.Set<String> snapshots=new java.util.HashSet<>();
                    final boolean[] snapshotPartial={false};
                    final java.util.Set<String> graphicsNames=new java.util.HashSet<>();
                    boolean graphicsComplete;
                    try {
                        graphicsComplete=GraphicsEvidence.collect(shell,su,(name,status,bytes)-> {
                            if(!name.matches("android/(before|after)-(clock|display|layers|composition|windows)\\.txt|dlt/inventory\\.tsv|dlt/files/[A-Za-z0-9][A-Za-z0-9._/-]{0,260}\\.range")
                                    || name.contains("..") || !graphicsNames.add(name) || !status.matches("[a-z_]+"))
                                throw new IOException("invalid_graphics_snapshot");
                            if(bytes.length>GraphicsEvidence.RANGE_LIMIT || qtotal[0]+bytes.length>MAX_TOTAL) {
                                status="size_limit";bytes=new byte[0];snapshotPartial[0]=true;
                            }
                            put(zip,"graphics/"+name,bytes);qtotal[0]+=bytes.length;
                            manifest.append("graphics:").append(name).append('\t').append(status).append('\t')
                                    .append(bytes.length).append('\t').append(digest(bytes)).append('\n');
                        },progress);
                    } catch(Exception error) {
                        graphicsComplete=false;
                        manifest.append("graphics:collection\tfailed\t0\t-\n");
                    }
                    final String[] currentPath={"-"};
                    final StringBuilder qnxDetails=new StringBuilder("endpoint=198.18.34.2:8000\ntransport=adb-shell-toybox-nc\n"
                            +"connection_scope=one_file_or_metadata_stage\noverall_deadline_seconds=none\nio_deadline_seconds=30\n");
                    boolean qnxFailed=false;
                    try {
                        progress.update("Чтение QNX через ADB/qconn…");
                        qnx.collect(new QnxFileReader.Receiver() {
                            private final java.util.Set<String> logs=new java.util.HashSet<>();
                            private final java.util.Set<String> artifacts=new java.util.HashSet<>();
                            private long progressAt;
                            public File temporaryFile() throws IOException { return File.createTempFile("qnx-work-", ".part", directory); }
                            public void progress(String text) {
                                long now=System.nanoTime();
                                if(now-progressAt>=1_000_000_000L) { progressAt=now;progress.update(text); }
                            }
                            public void artifact(String name,String status,File file) throws Exception {
                                String key;boolean complete;
                                if(name.startsWith("metadata/")&&java.util.Arrays.asList(QnxFileReader.SNAPSHOTS).contains(name.substring(9))
                                        &&status.matches("collected_snapshot|partial_or_unavailable")&&snapshots.add(name.substring(9))) {
                                    key="qnx-meta:"+name.substring(9);complete=status.equals("collected_snapshot");
                                } else if(name.matches("mappings/[1-9][0-9]{0,9}-wide\\.txt")
                                        &&status.matches("read|read_content_invalid|denied_or_exited|read_or_protocol_failed")) {
                                    key="qnx-mapping:"+name.substring(9);complete=status.equals("read");
                                } else if(name.startsWith("slog2/")&&QnxFileReader.allowedLog(name.substring(6))
                                        &&status.matches("collected_live_file|changed_during_read|partial_read_failed")&&logs.add(name.substring(6))) {
                                    key="qnx-slog2:"+name.substring(6);complete=status.equals("collected_live_file");
                                } else if(name.startsWith("files/")&&java.util.Arrays.asList(QnxFileReader.TARGETS).contains(name.substring(5))
                                        &&status.matches("collected_double_read|changed_during_read|partial_read_failed")&&qnxAttempted.add(name.substring(5))) {
                                    key="qnx:"+name.substring(5);complete=status.equals("collected_double_read");
                                    if(complete)qcount[0]++;
                                } else throw new IOException("invalid_qnx_artifact");
                                if(!artifacts.add(name))throw new IOException("duplicate_qnx_artifact");
                                if(!complete)snapshotPartial[0]=true;
                                MessageDigest hash=MessageDigest.getInstance("SHA-256");long bytes=0;
                                zip.putNextEntry(new ZipEntry("qnx/"+name));
                                try(InputStream in=new BufferedInputStream(new FileInputStream(file))) {
                                    byte[] chunk=new byte[32768];int n;
                                    // Cancellation stops remote reads; already acquired local bytes are still packaged.
                                    while((n=in.read(chunk))!=-1) { zip.write(chunk,0,n);hash.update(chunk,0,n);bytes+=n; }
                                } finally { zip.closeEntry(); }
                                StringBuilder sha=new StringBuilder();
                                for(byte b:hash.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
                                manifest.append(key).append('\t').append(status).append('\t').append(bytes).append('\t').append(sha).append('\n');
                            }
                            public void log(String name,String status,byte[] bytes) throws Exception {
                                if(!QnxFileReader.allowedLog(name)||!status.matches("collected_live_file|changed_during_read|truncated_size_limit|partial_read_failed")
                                        ||bytes==null||!logs.add(name))
                                    throw new IOException("invalid_qnx_log");
                                if(!status.equals("collected_live_file"))snapshotPartial[0]=true;
                                put(zip,"qnx/slog2/"+name,bytes);
                                manifest.append("qnx-slog2:").append(name).append('\t').append(status).append('\t')
                                        .append(bytes.length).append('\t').append(digest(bytes)).append('\n');
                            }
                            public void snapshot(String name,String status,byte[] bytes) throws Exception {
                                if(!java.util.Arrays.asList(QnxFileReader.SNAPSHOTS).contains(name)
                                        || !status.matches("collected_snapshot|partial_or_unavailable")
                                        || bytes==null || !snapshots.add(name))
                                    throw new IOException("invalid_qnx_snapshot");
                                if(!status.equals("collected_snapshot"))snapshotPartial[0]=true;
                                put(zip,"qnx/metadata/"+name,bytes);
                                manifest.append("qnx-meta:").append(name).append('\t').append(status).append('\t')
                                        .append(bytes.length).append('\t').append(digest(bytes)).append('\n');
                            }
                            public void file(String path,String status,byte[] bytes) throws Exception {
                                if(!java.util.Arrays.asList(QnxFileReader.TARGETS).contains(path)
                                        || !status.matches("[a-z_]+") || !qnxAttempted.add(path))
                                    throw new IOException("invalid_qnx_result");
                                String sha="-";
                                if(bytes!=null) {sha=digest(bytes);put(zip,"qnx/files"+path,bytes);qcount[0]++;}
                                manifest.append("qnx:").append(path).append('\t').append(status).append('\t')
                                        .append(bytes==null?0:bytes.length).append('\t').append(sha).append('\n');
                            }
                            public void diagnostic(String path,String details) throws Exception {
                                if(!java.util.Arrays.asList(QnxFileReader.TARGETS).contains(path))
                                    throw new IOException("invalid_qnx_diagnostic_path");
                                currentPath[0]=path;qnxStarted.add(path);
                                if(qnxDetails.length()<24000)
                                    qnxDetails.append("---\n").append(details,0,Math.min(2048,details.length()));
                                progress.update("QNX " + qnxStarted.size() + "/" + QnxFileReader.TARGETS.length + ": " + path);
                            }
                        });
                        if(qnxAttempted.size()!=QnxFileReader.TARGETS.length)
                            throw new IOException("qnx_collector_incomplete");
                    } catch(Exception unavailable) {
                        qnxFailed=true;
                        qnxDetails.append("\nresult=failed\n");
                        if(unavailable instanceof QnxFileReader.Failure) {
                            QnxFileReader.Failure protocol=(QnxFileReader.Failure)unavailable;
                            currentPath[0]=protocol.path;qnxDetails.append(protocol.report);
                        } else qnxDetails.append("stage=transport_or_deadline\npath=").append(currentPath[0])
                                .append("\nerror_class=").append(unavailable.getClass().getName())
                                .append("\nerror=").append(QnxFileReader.safe(unavailable.getMessage())).append('\n');
                        for(Throwable suppressed:unavailable.getSuppressed())
                            qnxDetails.append("suppressed=").append(QnxFileReader.safe(suppressed.toString())).append('\n');
                    }
                    if(!qnxFailed)qnxDetails.append("result=completed\n");
                    qnxComplete=!qnxFailed && graphicsComplete && !snapshotPartial[0] && snapshots.size()==QnxFileReader.SNAPSHOTS.length;
                    qnxDetails.append("reported_files=").append(qnxAttempted.size())
                            .append("\ncollected_files=").append(qcount[0]).append('\n');
                    put(zip,"qnx-diagnostics.txt",qnxDetails.toString().getBytes(StandardCharsets.UTF_8));
                    for(String name:QnxFileReader.SNAPSHOTS)if(!snapshots.contains(name))
                        manifest.append("qnx-meta:").append(name).append("\tnot_collected\t0\t-\n");
                    for(String path:QnxFileReader.TARGETS) if(!qnxAttempted.contains(path))
                        manifest.append("qnx:").append(path).append('\t')
                                .append(path.equals(currentPath[0])?"read_or_protocol_failed":"not_attempted_after_qnx_failure")
                                .append("\t0\t-\n");
                    count+=qcount[0];
                }
                put(zip,"manifest.tsv",manifest.toString().getBytes(StandardCharsets.UTF_8));
                put(zip,"README.txt",("Natro system evidence\nversion="+version+"\naccess="+access
                    +"\ncollected="+count+"/"+(PATHS.length+(qnx==null?0:QnxFileReader.TARGETS.length))+"\nwall_ms="+System.currentTimeMillis()
                    +"\ncapture_state_user_label="+captureState+"\nstart_wall_ms="+startWall
                    +"\nelapsed_ms="+((System.nanoTime()-startMono)/1000000)
                    +"\nQNX: no total-time, file-size, payload-total, PID-count, log-count, directory-count or depth budget.\n"
                    +"Cancellation, available storage, protocol framing and 30-second I/O deadlines still apply. Android evidence retains its separate bounds.\n"
                    +"Full accessible QNX namespace inventory from /, including hidden names; discovered file contents are not downloaded.\n"
                    +"filesystem-paths.txt is a path list with TSV-style backslash escapes. filesystem-inventory.tsv is an event journal: discovered, probe, enumeration, failure.\n"
                    +"Directory-only opens; explicit ENOTDIR means non-directory, not regular file. Opaque errors, unknown types/sizes and unavailable directory identity are explicit.\n"
                    +"Canonical aliases are listed; different paths sharing dev/inode are re-enumerated. Per-directory EOF and traversal_finished report coverage, not an atomic snapshot.\n"
                    +"Content collection uses the explicit allowlist only; not a complete firmware backup. Missing and inaccessible may be indistinguishable.\n"
                    +"Files are verified against remote SHA-256 before/after transfer, but not an atomic system snapshot.\n"
                    +"QNX exception: descriptor stat and equal SHA-256 of two independent reads; hashes are local, not remote authenticated hashes.\n"
                    +"QNX process metadata is live, not atomic or double-read verified. All discovered numeric PIDs are indexed before all matching graphics processes.\n"
                    +"exefile uses a 4096-byte path probe; a longer reply is explicitly invalid/incomplete. Initial mappings probe is 256 KiB, preserved as diagnostic evidence.\n"
                    +"Mappings overlap mismatch: original received prefix retained; conflicting reply and expected tail in proc-read-trace.tsv as hex.\n"
                    +"Invalid/mismatched/truncated mappings: a separate sequential attempt through EOF; each wire request is at most 256 KiB+1, with no total byte cap or snapshot splicing.\n"
                    +"Mappings format=3: initial probes in qnx/metadata/graphics-mappings.txt; full attempts in qnx/mappings/PID-wide.txt with index references and manifest hashes. Field validation pending.\n"
                    +"Slog2: all existing matching display/graphics buffers at their observed size, no count/byte cap; no logger launch.\n"
                    +"QNX /lib and observed /lib64 configuration/driver paths collected separately; matching bytes do not prove filesystem aliases.\n"
                    +"Graphics Android context: display/layer names/clock up to 64 KiB; composition/windows up to 1 MiB before/after DLT stage.\n"
                    +"DLT: existing /mnt/userdata/DLT only, non-hidden entries, depth 2, 8 subdirectories, 128 entries, 4 newest dlt/log/txt files.\n"
                    +"At most 2 MiB block-aligned tail per log. Range offsets/stat and local hashes recorded; live non-atomic bytes, not full log files.\n"
                    +"Capture state is a user label, not detected vehicle feedback. No mode switching, screenshot, logger start or driver lock.\n"
                    +"No process memory, cmdline, environment, control, devctl or arbitrary discovered-file downloads.\n"
                    +"Read-only; no daemon restart, remount, reboot, vehicle commands or private /data.\n"
                    +"Contains proprietary firmware; share privately, do not publish.\n").getBytes(StandardCharsets.UTF_8));
            }
            if(!partial.renameTo(target)) throw new IOException("export_publish_failed");
            published=true;
            // Only our completed exports, never unrelated diagnostics or firmware originals.
            File[] old=archives(directory);
            int retained=0;
            for(File previous:old) if(!previous.equals(target) && retained++>=1) previous.delete();
            int requested=PATHS.length+(qnx==null?0:QnxFileReader.TARGETS.length);
            return new Report(target,count,requested,count==requested&&qnxComplete);
        } finally { if(!published) partial.delete(); }
    }
}
