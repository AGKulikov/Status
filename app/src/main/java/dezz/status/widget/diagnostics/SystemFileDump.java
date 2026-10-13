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

/** Explicit, bounded firmware evidence export; never a backup or a system writer. */
public final class SystemFileDump {
    public interface Shell { AdbShellResult.Result command(String command) throws Exception; }
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
        Report(File file, int collected, int requested) { this.file=file; this.collected=collected; this.requested=requested; }
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
    /** Caller supplies a private ADB session with its existing 60-second deadline. */
    public static Report collect(File directory, Shell shell, Progress progress, String version) throws Exception {
        return collect(directory,shell,progress,version,null);
    }
    public static Report collect(File directory, Shell shell, Progress progress, String version, Qnx qnx) throws Exception {
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("export_directory_unavailable");
        File partial=File.createTempFile("Natro-system-", ".part", directory);
        File target=new File(directory,partial.getName().replace(".part", ".zip"));
        boolean published=false;
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
                    try {
                        progress.update("Чтение QNX через ADB/qconn…");
                        qnx.collect((path,status,bytes)-> {
                            qnxAttempted.add(path);
                            if(bytes!=null && qtotal[0]+bytes.length>MAX_TOTAL) { bytes=null;status="size_limit"; }
                            String sha="-";
                            if(bytes!=null) {sha=digest(bytes);put(zip,"qnx/files"+path,bytes);qcount[0]++;qtotal[0]+=bytes.length;}
                            manifest.append("qnx:").append(path).append('\t').append(status).append('\t')
                                    .append(bytes==null?0:bytes.length).append('\t').append(sha).append('\n');
                        });
                    } catch(Exception unavailable) {
                        // No reconnect, service start, firewall changes or alternative dialect guessing.
                    }
                    for(String path:QnxFileReader.TARGETS) if(!qnxAttempted.contains(path))
                        manifest.append("qnx:").append(path).append("\tunavailable_or_not_attempted\t0\t-\n");
                    count+=qcount[0];
                }
                put(zip,"manifest.tsv",manifest.toString().getBytes(StandardCharsets.UTF_8));
                put(zip,"README.txt",("Natro system evidence\nversion="+version+"\naccess="+access
                    +"\ncollected="+count+"/"+(PATHS.length+(qnx==null?0:QnxFileReader.TARGETS.length))+"\nwall_ms="+System.currentTimeMillis()
                    +"\nLimits: 8 MiB/file, 64 MiB total, ADB session 60 seconds.\n"
                    +"Explicit allowlist only; not a complete firmware backup. Missing and inaccessible may be indistinguishable.\n"
                    +"Files are verified against remote SHA-256 before/after transfer, but not an atomic system snapshot.\n"
                    +"QNX exception: descriptor stat and two equal reads; SHA-256 computed locally, not remote authenticated hash.\n"
                    +"Read-only; no daemon restart, remount, reboot, vehicle commands or private /data.\n"
                    +"Contains proprietary firmware; share privately, do not publish.\n").getBytes(StandardCharsets.UTF_8));
            }
            if(!partial.renameTo(target)) throw new IOException("export_publish_failed");
            published=true;
            // Only our completed exports, never unrelated diagnostics or firmware originals.
            File[] old=archives(directory);
            int retained=0;
            for(File previous:old) if(!previous.equals(target) && retained++>=1) previous.delete();
            return new Report(target,count,PATHS.length+(qnx==null?0:QnxFileReader.TARGETS.length));
        } finally { if(!published) partial.delete(); }
    }
}
