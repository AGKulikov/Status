/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Port of the evidence-proven qconn-native-nfogh GET dialect. No generic commands. */
public final class QnxFileReader {
    public interface Wire { byte[] read() throws Exception; void write(byte[] bytes) throws Exception; }
    public interface Receiver {
        void file(String path, String status, byte[] bytes) throws Exception;
        default void diagnostic(String path, String details) throws Exception {}
        default void snapshot(String name, String status, byte[] bytes) throws Exception {}
        default void log(String name, String status, byte[] bytes) throws Exception {}
        default void mapping(String pid, String status, byte[] bytes) throws Exception {
            throw new IOException("qnx_mapping_receiver_required");
        }
        /** Private scratch storage; contents are deleted after delivery, including failure. */
        default File temporaryFile() throws IOException { return File.createTempFile("qnx-work-", ".part"); }
        default void artifact(String name, String status, File file) throws Exception {
            throw new IOException("qnx_streaming_receiver_required");
        }
        default void progress(String text) {}
        /** Explicit user exclusions. A match is archived evidence, not a fresh hash. */
        default String previousFile(String path,long size) { return null; }
    }
    public static final String[] TARGETS = {
        "/etc/system/config/variant.cfg", "/bin/openwfd_server", "/bin/openwfd_telltale",
        "/bin/screen", "/lib/libopenwfd_qnx.so",
        "/lib/libMAX96755_QC_0_UART.so", "/lib/libMAX96755_QC_1_UART.so",
        "/lib/libDP_COMMON_QC_UART.so", "/bin/qcdisplaycfg.xml",
        "/scripts/startup.sh", "/ramdisk/linux.config",
        "/bin/wfd_be", "/lib/libWFD.so.1", "/lib/libplanedef.so",
        "/lib/libdalconfig.so.1", "/lib/libhalmdputil_s8155.so",
        "/etc/dlt_logadapter.conf", "/etc/dlt_usbdatalogger.conf",
        "/lib/graphics.conf", "/etc/dlt.conf",
        "/var/log/display_recovery_info.txt", "/var/log/la_gvm.txt",
        // Exact paths observed in screen's startup log and PanelLibrary config.
        // Keep /lib copies for comparison; do not assume a /lib64 alias.
        "/lib64/graphics.conf", "/lib64/libMAX96755_QC_0_UART.so",
        "/lib64/libMAX96755_QC_1_UART.so", "/lib64/libDP_COMMON_QC_UART.so",
        // Observed in the 3.2.2 process inventory / mappings. These are explicit
        // targets, never arbitrary paths downloaded from process output.
        "/lib64/libopenwfd.so", "/lib64/libdisplay_shim_util.so",
        "/lib64/libuhab-qvmhost.so", "/lib64/libbridgechip_client.so",
        "/lib64/libdalconfig-sa8155_adp_star_v1.0.0.so",
        "/usr/bin/cluster_controller", "/bin/sc_qnx_vip_proxy",
        // Observed in the complete 17 October QNX namespace inventory. These are
        // GET-only evidence files; executable names do not authorize execution.
        "/persist/dim_config", "/persist/system/config/cartype.cfg",
        "/lib64/libopenwfd_core.so", "/lib64/libscreen.so.1",
        "/bin/uart-display-config", "/usr/bin/swdl_display",
        "/proc/boot/static_UI_PSD.png", "/proc/boot/img_splash.conf",
        "/userdata/DLT/DIM_Slog.log"
    };
    public static final String[] SNAPSHOTS = {"processes.tsv", "graphics-mappings.txt", "proc-read-trace.tsv", "slog2-inventory.tsv",
            "filesystem-inventory.tsv", "filesystem-paths.txt"};
    /** Explicit user-selected QNX content scope; never VM disks, devices or process memory. */
    public static final String[] FULL_ROOTS = {"/bin", "/sbin", "/usr", "/lib", "/lib64", "/etc",
            "/scripts", "/persist", "/proc/boot", "/userdata/DLT", "/var/log", "/ramdisk/linux.config"};
    public static boolean fullPath(String value) {
        if(!directoryPath(value)||value.endsWith("/")||value.contains("//"))return false;
        for(String root:FULL_ROOTS)if(value.equals(root)||value.startsWith(root+"/"))return true;
        return false;
    }
    // First diagnostic attempt / proven maximum wire request, NOT a full-file budget.
    public static final int MAPPING_LIMIT = 256 * 1024;
    private final Wire wire;
    private byte[] pending = new byte[0];
    private int cursor;
    private String stage="greeting", path="-", lastReply="-";
    private long offset, rx, tx;
    private Artifact trace;
    private Receiver ioProgress;
    private long ioProgressAt;
    /** Disk-backed output: neither the catalog nor full mappings accumulate in Java strings. */
    private static final class Artifact implements Closeable {
        final Receiver receiver; final String name; final File file;
        final OutputStream out; boolean ended;
        Artifact(Receiver receiver, String name) throws IOException {
            this.receiver=receiver;this.name=name;file=receiver.temporaryFile();
            try { out=new BufferedOutputStream(new FileOutputStream(file)); }
            catch(IOException error) { file.delete();throw error; }
        }
        Artifact append(Object text) throws IOException { out.write(String.valueOf(text).getBytes(StandardCharsets.UTF_8));return this; }
        void flush() throws IOException { out.flush(); }
        void finish(String status) throws Exception {
            if(ended)return;ended=true;
            try { out.close();receiver.artifact(name,status,file); }
            finally { file.delete(); }
        }
        public void close() throws IOException { try { out.close(); } finally { file.delete(); } }
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray();
        char[] result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) {
            result[i*2]=digits[(bytes[i]&255)>>>4];result[i*2+1]=digits[bytes[i]&15];
        }
        return new String(result);
    }
    private String fullReply="-";
    private void traceRead(String target,int count,byte[] part) throws Exception {
        StringBuilder hash=new StringBuilder();
        for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(part))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        trace.append(target).append('\t').append(offset).append('\t').append(count).append('\t')
                .append(part.length).append('\t').append(hash).append('\t')
                .append(fullReply.replaceAll("[\\p{Cntrl}]"," ")).append('\n');
    }
    private final long started=System.nanoTime();
    public static final class Failure extends IOException {
        public final String report;
        public final String path;
        Failure(String path, String report, Exception cause) {
            super("QNX collection failed", cause); this.path=path; this.report=report;
        }
    }
    private String report(Exception error) {
        return "stage="+stage+"\npath="+path+"\noffset="+offset+"\nrx_bytes="+rx+"\ntx_bytes="+tx
                +"\nelapsed_ms="+((System.nanoTime()-started)/1000000)+"\nlast_control_reply="+lastReply
                +(error==null?"\n":"\nerror_class="+error.getClass().getName()+"\nerror="+safe(error.getMessage())+"\n");
    }
    public static String safe(String text) {
        if(text==null)return "-";
        return text.substring(0,Math.min(512,text.length())).replaceAll("[\\p{Cntrl}]"," ");
    }
    public QnxFileReader(Wire wire) { this.wire = wire; }
    private byte[] exact(int size) throws Exception {
        if(size<0 || size>2048) throw new IOException("qnx_count_limit");
        byte[] result=new byte[size];
        for(int i=0;i<size;) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if(cursor==pending.length) {
                pending=wire.read(); cursor=0;
                if(pending==null || pending.length==0 || pending.length>65536) throw new IOException("qnx_stream_incomplete");
                rx+=pending.length;
            }
            int count=Math.min(size-i,pending.length-cursor);
            System.arraycopy(pending,cursor,result,i,count);i+=count;cursor+=count;
        }
        return result;
    }
    private String line() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try { for(int i=0;i<4096;i++) {
            int b=exact(1)[0]&255; bytes.write(b);
            if(b==10) {
                byte[] value=bytes.toByteArray();
                if(value.length<2 || value[value.length-2]!=13) throw new IOException("qnx_bad_line");
                String reply=new String(value,0,value.length-2,StandardCharsets.US_ASCII);
                lastReply=safe(reply);fullReply=reply;return reply;
            }
        }
        throw new IOException("qnx_line_limit");
        } catch(Exception error) {
            lastReply=safe(new String(bytes.toByteArray(),StandardCharsets.US_ASCII));throw error;
        }
    }
    private void send(String frame) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        byte[] bytes=frame.getBytes(StandardCharsets.UTF_8);wire.write(bytes);tx+=bytes.length;
    }
    // Same non-payload line normalization as the previously successful Python reader.
    private String reply() throws Exception {
        for(int i=0;i<16;i++) {
            String value=line().trim();
            if(!value.isEmpty()&&!value.equals("error linemode-or-echo-not-supported"))return value;
        }
        throw new IOException("qnx_reply_noise_limit");
    }
    private String[] fields() throws Exception {
        String reply=reply();
        if(reply.startsWith("e:")) throw new IOException("qnx_remote_error");
        String[] fields=reply.split(":",-1);
        if(!fields[0].equals("o")) throw new IOException("qnx_bad_reply");
        return fields;
    }
    private long number(String value,int base) throws IOException {
        if(!value.matches(base==16?"[0-9a-fA-F]{1,12}":"[0-9]{1,7}")) throw new IOException("qnx_bad_number");
        try { return Long.parseLong(value,base); } catch(NumberFormatException e) { throw new IOException("qnx_bad_number"); }
    }
    private long[] stat(long fd) throws Exception {
        send("s:"+fd+"\r\n"); String[] f=fields();
        if(f.length<16) throw new IOException("qnx_bad_stat");
        long[] s=new long[15];for(int i=0;i<15;i++)s[i]=statNumber(f[i+1],i==0||i==2);
        return s;
    }
    // stat identities are 64-bit bit patterns, not payload lengths or descriptors.
    // In particular /proc on KX11 returns an all-ones inode. Never widen the
    // framing/count parser or let an unsigned size wrap into an empty-file success.
    static long statNumber(String value,boolean identity) throws IOException {
        if(!value.matches("[0-9a-fA-F]{1,16}"))throw new IOException("qnx_bad_number");
        try { return identity?Long.parseUnsignedLong(value,16):Long.parseLong(value,16); }
        catch(NumberFormatException error) { throw new IOException("qnx_bad_number",error); }
    }
    private static boolean reliableIdentity(long[] stat) {
        return stat!=null&&stat[0]!=0&&stat[0]!=-1L&&stat[2]!=-1L;
    }
    private byte[] readFile(long fd,long size,OutputStream out) throws Exception {
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        for(offset=0;offset<size;) {
            int count=(int)Math.min(2048,size-offset);
            send("r:"+fd+":"+Long.toHexString(offset)+":"+Integer.toHexString(count)+":0\r\n");
            String[] f=fields(); if(f.length<2) throw new IOException("qnx_bad_count");
            long got=number(f[1],16);
            if(got<1 || got>count) throw new IOException("qnx_short_read");
            byte[] bytes=exact((int)got);hash.update(bytes);if(out!=null)out.write(bytes);offset+=got;
            if(ioProgress!=null&&System.nanoTime()-ioProgressAt>=1_000_000_000L) {
                ioProgressAt=System.nanoTime();ioProgress.progress("QNX "+path+" · "+offset+"/"+size+" байт"
                        +(out==null?" · проверка":""));
            }
        }
        return hash.digest();
    }
    public void collect(Receiver receiver) throws Exception {
        collectTargets(TARGETS,receiver);
    }
    /** One fresh qconn connection per allowlisted file, matching the successful host runs. */
    public void collectSingle(String target,Receiver receiver) throws Exception {
        if(!Arrays.asList(TARGETS).contains(target))throw new IOException("qnx_target_not_allowed");
        path=target;collectTargets(new String[]{target},receiver);
    }
    private void collectTargets(String[] targets,Receiver receiver) throws Exception {
        try { collectFiles(targets,receiver); }
        catch(Exception error) { throw new Failure(path,report(error),error); }
    }
    private void collectFiles(String[] targets,Receiver receiver) throws Exception {
        handshake();
        for(String path:targets) {
            this.path=path;stage="open";offset=0;
            send("o:\""+path+"\":0\r\n");
            String reply=reply();
            if(reply.startsWith("e:")) {
                receiver.diagnostic(path,report(new IOException("qnx_remote_open_error")));
                receiver.file(path,"remote_open_failed",null); continue;
            }
            long fd=descriptor(reply);
            stage="stat_before";long[] before=stat(fd);
            if((before[9]&0xf000)!=0x8000) {
                stage="close";send("c:"+fd+"\r\n");fields();
                receiver.file(path,"not_regular",null);
                receiver.diagnostic(path,report(null));continue;
            }
            try(Artifact data=new Artifact(receiver,"files"+path)) {
                String status="partial_read_failed";
                try {
                    stage="read_first";byte[] first=readFile(fd,before[1],data.out);
                    // qconn has no remote SHA operation: compare independent reads' hashes.
                    stage="read_second";byte[] second=readFile(fd,before[1],null);
                    stage="stat_after";long[] after=stat(fd);
                    boolean same=Arrays.equals(first,second) && before[0]==after[0]
                            && before[1]==after[1] && before[6]==after[6] && before[8]==after[8] && before[9]==after[9];
                    stage="close";send("c:"+fd+"\r\n");fields();
                    status=same?"collected_double_read":"changed_during_read";
                } finally { data.finish(status); }
            }
            stage="deliver";
            receiver.diagnostic(path,report(null));
        }
    }
    private long descriptor(String reply) throws Exception {
        String[] f=reply.split(":",-1);
        if(f.length<2 || !f[0].equals("o"))throw new IOException("qnx_bad_open");
        long fd=number(f[1],10);if(fd>1000000)throw new IOException("qnx_bad_descriptor");
        return fd;
    }
    private void handshake() throws Exception {
        String greeting;
        try { greeting=line(); }
        catch(IOException error) {
            String lower=lastReply.toLowerCase(java.util.Locale.ROOT);
            if(lower.contains("connection refused")||lower.contains("network is unreachable")
                    ||lower.contains("no route to host"))throw new IOException("qnx_transport_unavailable",error);
            throw error;
        }
        if(!greeting.equals("QCONN")) {
            String lower=greeting.toLowerCase(java.util.Locale.ROOT);
            if(lower.contains("connection refused")||lower.contains("network is unreachable")
                    ||lower.contains("no route to host"))throw new IOException("qnx_transport_unavailable");
            throw new IOException("qnx_greeting_mismatch");
        }
        stage="negotiation";
        if(!Arrays.equals(exact(3),new byte[]{(byte)255,(byte)253,34}))
            throw new IOException("qnx_handshake_mismatch");
        stage="service_file";
        send("service file\r\n");if(!reply().equals("OK"))throw new IOException("qnx_service_failed");
    }
    private interface NameConsumer { void accept(byte[] name) throws Exception; }
    /** Page/frame bounds validate the protocol; there is no directory count or page budget. */
    private void directory(long fd, NameConsumer consumer) throws Exception {
        java.util.Set<String> pages=new java.util.HashSet<>();
        for(long page=0;;page=Math.addExact(page,1)) {
            offset=page;send("r:"+fd+":"+Long.toHexString(page)+":400:1\r\n");
            String[] f=fields();if(f.length<4)throw new IOException("qnx_bad_directory_reply");
            long length=number(f[2],16);
            if(length>65536)throw new IOException("qnx_bad_directory_frame_length");
            if(length==0)return;
            ByteArrayOutputStream data=new ByteArrayOutputStream((int)length);
            for(int n=0;n<length;n+=2048)data.write(exact((int)Math.min(2048,length-n)));
            byte[] bytes=data.toByteArray();
            String hash=hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!pages.add(hash))throw new IOException("qnx_directory_repeated_page");
            int begin=0;
            for(int i=0;i+1<bytes.length;i++)if(bytes[i]==13&&bytes[i+1]==10) {
                if(i>begin)consumer.accept(Arrays.copyOfRange(bytes,begin,i));
                begin=i+2;i++;
            }
            if(begin!=bytes.length)throw new IOException("qnx_directory_incomplete_name");
        }
    }
    private static String utf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    static String tsv(String value) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='\\')out.append("\\\\");else if(c=='\t')out.append("\\t");
            else if(c=='\n')out.append("\\n");else if(c=='\r')out.append("\\r");
            else if(Character.isISOControl(c))out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.toString();
    }
    private static boolean directoryPath(String path) {
        if(!path.startsWith("/")||path.startsWith("//")||path.indexOf('"')>=0||path.indexOf('\\')>=0)return false;
        for(String name:path.split("/",-1))if(name.equals(".")||name.equals(".."))return false;
        for(int i=0;i<path.length();i++)if(Character.isISOControl(path.charAt(i)))return false;
        // qconn control lines have a framing bound. Record longer discovered names,
        // but never send a frame that cannot be represented by the validated dialect.
        return path.getBytes(StandardCharsets.UTF_8).length+14<=4096;
    }
    private static boolean notDirectory(String reply) {
        for(String field:reply.split(":")) {
            String value=field.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ");
            if(value.equals("enotdir")||value.equals("not a directory")
                    ||value.matches("enotdir\\s*\\(\\s*(?:errno\\s*)?20\\s*\\)"))return true;
        }
        return false;
    }
    private static void inventoryRow(Artifact out,String event,String path,String type,long[] stat,String status,String detail) throws IOException {
        out.append(event).append('\t').append(tsv(path)).append('\t').append(type).append('\t')
                .append(stat==null?"unknown":stat[1]).append('\t').append(stat==null?"unknown":Long.toUnsignedString(stat[2])).append('\t')
                .append(stat==null?"unknown":Long.toUnsignedString(stat[0])).append('\t').append(stat==null?"unknown":Long.toHexString(stat[9]))
                .append('\t').append(status).append('\t').append(tsv(detail)).append('\n');
    }
    /** Full accessible namespace, DIRECTORY-ONLY opens. No discovered file is opened for content. */
    public void collectInventory(Receiver receiver) throws Exception {
        File queueFile=receiver.temporaryFile();
        String status="collected_snapshot";
        // Queue lives on disk: breadth and depth do not retain all discovered paths in heap.
        try(Artifact inventory=new Artifact(receiver,"metadata/filesystem-inventory.tsv");
            Artifact paths=new Artifact(receiver,"metadata/filesystem-paths.txt");
            RandomAccessFile queue=new RandomAccessFile(queueFile,"rw")) {
            inventory.append("event\tpath_tsv_escaped\ttype\tsize\tdevice\tinode\tmode_hex\tstatus\tdetail_tsv_escaped\n")
                    .append("# Live namespace, not atomic. Shared dev/inode alone never proves an alias.\n")
                    .append("# Unknown file types/sizes stay unknown. ENOTDIR is not proof of a regular file.\n");
            final long[] discovered={1}, examined={0};
            java.util.Map<String,String> visited=new java.util.HashMap<>();
            java.util.Map<String,String> directorySignatures=new java.util.HashMap<>();
            queue.setLength(0);enqueue(queue,"/");paths.append("/\n");
            inventoryRow(inventory,"discovered","/","unknown",null,"queued","root");
            long cursor=0;
            try {
                path="/";handshake();
                while(cursor<queue.length()) {
                    queue.seek(cursor);int size=queue.readInt();byte[] raw=new byte[size];queue.readFully(raw);
                    cursor=queue.getFilePointer();path=utf8(raw);examined[0]++;
                    receiver.progress("Каталог QNX: найдено "+discovered[0]+", проверено "+examined[0]+" · "+path);
                    if(!directoryPath(path)) {
                        status="partial_or_unavailable";
                        inventoryRow(inventory,"probe",path,"unknown",null,"unrepresentable_path","directory open not sent");continue;
                    }
                    stage="inventory_directory_open";offset=0;send("o:\""+path+"\":0:4000\r\n");String opened=reply();
                    if(opened.startsWith("e:")) {
                        boolean leaf=notDirectory(opened);
                        if(!leaf)status="partial_or_unavailable";
                        inventoryRow(inventory,"probe",path,leaf?"non_directory":"unknown",null,
                                leaf?"not_directory":"directory_open_failed",opened);continue;
                    }
                    long fd=descriptor(opened);stage="inventory_directory_stat";
                    long[] attributes;
                    try { attributes=stat(fd); }
                    catch(IOException error) {
                        if(!"qnx_remote_error".equals(error.getMessage()))throw error;
                        attributes=null;status="partial_or_unavailable";
                        inventoryRow(inventory,"stat",path,"directory_open",null,"metadata_unavailable",lastReply);
                    }
                    if(attributes!=null&&(attributes[9]&0xf000)!=0x4000)throw new IOException("qnx_directory_only_open_returned_non_directory");
                    boolean reliableIdentity=reliableIdentity(attributes);
                    String previous=reliableIdentity?visited.putIfAbsent(attributes[2]+":"+attributes[0],path):null;
                    if(previous!=null) {
                        inventoryRow(inventory,"probe",path,"directory",attributes,
                                "identity_collision_reenumerated",previous);
                    }
                    if(!reliableIdentity) {
                        status="partial_or_unavailable";
                        inventoryRow(inventory,"identity",path,"directory_open",attributes,"identity_unavailable",
                                "enumerate names; repeated unknown-identity listings cannot prove distinct subtrees");
                    }
                    inventoryRow(inventory,"probe",path,"directory",attributes,"opened",opened);
                    final String parent=path;
                    final java.util.Set<String> names=new java.util.TreeSet<>();
                    final boolean[] nameFailure={false};
                    long childrenStart=queue.length();
                    stage="inventory_directory_read";
                    try {
                        directory(fd, nameBytes -> {
                            String name;
                            try { name=utf8(nameBytes); }
                            catch(java.nio.charset.CharacterCodingException invalid) {
                                nameFailure[0]=true;
                                inventoryRow(inventory,"unrepresentable_name",parent,"unknown",null,"invalid_utf8","name_hex="+hex(nameBytes));return;
                            }
                            if(name.equals(".")||name.equals("..")||!names.add(name))return;
                            if(name.indexOf('/')>=0) {
                                nameFailure[0]=true;
                                inventoryRow(inventory,"unrepresentable_name",parent,"unknown",null,"invalid_basename","name_hex="+hex(nameBytes));return;
                            }
                            String child=parent.equals("/")?"/"+name:parent+"/"+name;
                            discovered[0]++;paths.append(tsv(child)).append('\n');
                            inventoryRow(inventory,"discovered",child,"unknown",null,"queued","parent="+parent);
                            enqueue(queue,child);
                        });
                        if(nameFailure[0])status="partial_or_unavailable";
                        inventoryRow(inventory,"enumeration",path,"directory",attributes,"eof","directory page EOF observed");
                        {
                            java.security.MessageDigest signature=java.security.MessageDigest.getInstance("SHA-256");
                            for(String name:names) { signature.update(name.getBytes(StandardCharsets.UTF_8));signature.update((byte)0); }
                            String key=(attributes==null?"unknown":attributes[2]+":"+attributes[0])+":"+hex(signature.digest());
                            String equivalent=null;
                            for(String ancestor=parentPath(path);ancestor!=null;ancestor=parentPath(ancestor))
                                if(key.equals(directorySignatures.get(ancestor))){equivalent=ancestor;break;}
                            directorySignatures.put(path,key);
                            if(equivalent!=null && queue.length()>childrenStart) {
                                // All names have already been recorded. Do not claim a
                                // real alias: identical lists are only a cycle candidate.
                                status="partial_or_unavailable";
                                inventoryRow(inventory,"enumeration",path,"directory_open",attributes,
                                        reliableIdentity?"possible_cycle_identity_and_names":"possible_cycle_identity_unknown",equivalent);
                                long tail=childrenStart;
                                while(tail<queue.length()) {
                                    queue.seek(tail);byte[] rawChild=new byte[queue.readInt()];queue.readFully(rawChild);tail=queue.getFilePointer();
                                    inventoryRow(inventory,"probe",utf8(rawChild),"unknown",null,"not_attempted_identity_ambiguous",equivalent);
                                }
                                queue.setLength(childrenStart);
                            }
                        }
                    } catch(IOException error) {
                        // A framed remote rejection or repeated page leaves the stream
                        // aligned. Other errors must stop; never guess past lost payload.
                        if(!"qnx_remote_error".equals(error.getMessage())&&!"qnx_directory_repeated_page".equals(error.getMessage()))throw error;
                        status="partial_or_unavailable";
                        inventoryRow(inventory,"enumeration",path,"directory",attributes,"incomplete",error.getMessage()+"; "+lastReply);
                    }
                    stage="inventory_directory_close";send("c:"+fd+"\r\n");fields();
                }
                inventory.append("# traversal_finished=true discovered=").append(discovered[0]).append(" examined=").append(examined[0]).append('\n');
            } catch(Exception error) {
                status="partial_or_unavailable";
                inventoryRow(inventory,"failure",path,"unknown",null,"read_or_protocol_failed",report(error));
                while(cursor<queue.length()) {
                    queue.seek(cursor);byte[] raw=new byte[queue.readInt()];queue.readFully(raw);cursor=queue.getFilePointer();
                    inventoryRow(inventory,"probe",utf8(raw),"unknown",null,"not_attempted_after_failure","previously discovered");
                }
                inventory.append("# traversal_finished=false discovered=").append(discovered[0]).append(" examined=").append(examined[0]).append('\n');
                throw new Failure(path,report(error),error);
            } finally { inventory.finish(status);paths.finish(status); }
        } finally { queueFile.delete(); }
    }
    private static void enqueue(RandomAccessFile queue,String path) throws IOException {
        byte[] bytes=path.getBytes(StandardCharsets.UTF_8);queue.seek(queue.length());queue.writeInt(bytes.length);queue.write(bytes);
    }
    private static String parentPath(String value) {
        if(value.equals("/"))return null;
        int slash=value.lastIndexOf('/');return slash==0?"/":value.substring(0,slash);
    }
    private static boolean sameFile(long[] a,long[] b) {
        return a[0]==b[0]&&a[1]==b[1]&&a[2]==b[2]&&a[6]==b[6]&&a[8]==b[8]&&a[9]==b[9];
    }
    private static boolean linkError(String reply) {
        return reply.toLowerCase(java.util.Locale.ROOT).contains("too many levels of symbolic links")
                ||reply.equalsIgnoreCase("e:ELOOP");
    }
    /** Native KX11 qconn file_open(a000) uses lstat, never open(2).
     * file_read on that handle uses readlink. See QCONN_LINKS_RU.md and its ELF hash.
     * An unknown dialect/short response fails closed; never retry by dropping NOFOLLOW. */
    private String linkValue(long fd,long[] before) throws Exception {
        if((before[9]&0xf000)!=0xa000||before[1]<1||before[1]>=2048)
            throw new IOException("qnx_link_unrepresentable");
        byte[][] values=new byte[2][];
        for(int pass=0;pass<2;pass++) {
            send("r:"+fd+":0:800:0\r\n");String[] f=fields();
            if(f.length!=4)throw new IOException("qnx_bad_link_reply");
            long count=number(f[1],16);
            if(count>2048)throw new IOException("qnx_bad_link_count");
            values[pass]=exact((int)count);
            if(count!=before[1])throw new IOException("qnx_link_changed_or_truncated");
        }
        if(!Arrays.equals(values[0],values[1])||!sameFile(before,stat(fd)))
            throw new IOException("qnx_link_changed_or_truncated");
        return utf8(values[0]);
    }
    private static boolean selectedOrParent(String target) {
        if(fullPath(target))return true;
        for(String root:FULL_ROOTS)if(root.startsWith(target+"/"))return true;
        return false;
    }
    /** Resolve each component before '..'; metadata handles cannot open a device. */
    private String resolveTreeLink(String original,Artifact inventory) throws Exception {
        java.util.ArrayDeque<String> pending=new java.util.ArrayDeque<>(Arrays.asList(original.substring(1).split("/")));
        java.util.Set<String> seen=new java.util.HashSet<>();
        String resolved="/";
        while(!pending.isEmpty()) {
            String name=pending.removeFirst();
            if(name.isEmpty()||name.equals("."))continue;
            if(name.equals("..")){resolved=parentPath(resolved);if(resolved==null)resolved="/";continue;}
            String candidate=resolved.equals("/")?"/"+name:resolved+"/"+name;
            if(!directoryPath(candidate)||!selectedOrParent(candidate))throw new IOException("qnx_link_outside_selected_roots");
            send("o:\""+candidate+"\":9880:a000\r\n");String opened=reply();
            if(opened.startsWith("e:"))throw new IOException("qnx_link_metadata_unavailable");
            long fd=descriptor(opened);long[] info=stat(fd);String target=null;boolean aligned=true;
            try {
                if((info[9]&0xf000)==0xa000) {
                    if(!seen.add(candidate))throw new IOException("qnx_link_cycle");
                    target=linkValue(fd,info);
                    inventoryRow(inventory,"link",candidate,"symlink",info,"link_target_read",target);
                } else if(!pending.isEmpty()&&(info[9]&0xf000)!=0x4000)
                    throw new IOException("qnx_link_parent_not_directory");
            } catch(Exception error) {
                String reason=error.getMessage();
                aligned=error instanceof IOException&&reason!=null
                        &&(reason.startsWith("qnx_link_")||reason.equals("qnx_remote_error"));
                throw error;
            } finally {if(aligned){send("c:"+fd+"\r\n");fields();}}
            if(target==null)resolved=candidate;
            else {
                if(target.startsWith("/"))resolved="/";
                String[] parts=target.split("/",-1);
                for(int i=parts.length-1;i>=0;i--)pending.addFirst(parts[i]);
            }
        }
        if(seen.isEmpty())throw new IOException("qnx_link_changed_or_not_link");
        if(!fullPath(resolved))throw new IOException("qnx_link_outside_selected_roots");
        return resolved;
    }
    /** Full ordinary files, bounded RAM and no content/depth/count quota. New flags are QNX,
     * not Linux: RDONLY|NONBLOCK|NOCTTY|NOFOLLOW|LARGEFILE = 0x9880 (libc/nto).
     * Symlinks are resolved with metadata-only native lstat/readlink handles, then
     * their selected canonical target is queued once. Ordinary opens keep NOFOLLOW.
     * Each discovered directory is opened without following its final component. This is a
     * live, non-atomic namespace: parent substitution between requests cannot be excluded. */
    public void collectTree(Receiver receiver) throws Exception {
        ioProgress=receiver;
        File queueFile=receiver.temporaryFile();
        String outcome="collected_snapshot";
        try(Artifact inventory=new Artifact(receiver,"metadata/full-tree.tsv");
            RandomAccessFile queue=new RandomAccessFile(queueFile,"rw")) {
            inventory.append("event\tpath_tsv_escaped\ttype\tsize\tdevice\tinode\tmode_hex\tstatus\tdetail_tsv_escaped\n");
            inventory.append("# Non-atomic copy. Native lstat/readlink resolves selected targets; NOFOLLOW content opens.\n")
                    .append("# excluded_previous_dump means prior verified bytes with matching path/size, NOT a fresh content hash.\n");
            java.util.Set<String> visited=new java.util.HashSet<>();
            long cursor=0,examined=0;
            for(String root:FULL_ROOTS)enqueue(queue,root);
            try {
                handshake();
                while(cursor<queue.length()) {
                    queue.seek(cursor);byte[] raw=new byte[queue.readInt()];queue.readFully(raw);
                    cursor=queue.getFilePointer();path=utf8(raw);offset=0;examined++;
                    if(!visited.add(path)) {
                        inventoryRow(inventory,"probe",path,"unknown",null,"path_already_attempted","exact canonical path");continue;
                    }
                    receiver.progress("Полный сбор QNX: "+examined+" · "+path);
                    if(!fullPath(path)) {
                        outcome="partial_or_unavailable";
                        inventoryRow(inventory,"probe",path,"unknown",null,"path_rejected","not opened");continue;
                    }
                    boolean parentsOk=true;
                    if(Arrays.asList(FULL_ROOTS).contains(path)) {
                        for(int slash=path.indexOf('/',1);slash>0;slash=path.indexOf('/',slash+1)) {
                            String ancestor=path.substring(0,slash);
                            send("o:\""+ancestor+"\":9880\r\n");String parentReply=reply();
                            if(parentReply.startsWith("e:")) {
                                parentsOk=false;inventoryRow(inventory,"probe",path,"unknown",null,"parent_unavailable_or_link",ancestor+"; "+parentReply);break;
                            }
                            long parentFd=descriptor(parentReply);long[] parentStat=stat(parentFd);
                            send("c:"+parentFd+"\r\n");fields();
                            if((parentStat[9]&0xf000)!=0x4000) {
                                parentsOk=false;inventoryRow(inventory,"probe",path,"unknown",null,"parent_not_directory",ancestor);break;
                            }
                        }
                    }
                    if(!parentsOk){outcome="partial_or_unavailable";continue;}
                    stage="tree_open_nofollow";send("o:\""+path+"\":9880\r\n");String opened=reply();
                    if(opened.startsWith("e:")) {
                        if(linkError(opened)) {
                            stage="tree_resolve_link";
                            try {
                                String target=resolveTreeLink(path,inventory);
                                inventoryRow(inventory,"probe",path,"symlink",null,"link_resolved",target);
                                enqueue(queue,target);continue;
                            } catch(IOException linkFailure) {
                                String reason=linkFailure.getMessage();
                                // Only semantic failures and framed denials keep the stream aligned.
                                if(reason==null||!(reason.startsWith("qnx_link_")||reason.equals("qnx_remote_error")))throw linkFailure;
                                outcome="partial_or_unavailable";
                                inventoryRow(inventory,"probe",path,"unknown",null,"link_unavailable",reason+"; "+lastReply);continue;
                            }
                        }
                        outcome="partial_or_unavailable";
                        inventoryRow(inventory,"probe",path,"unknown",null,"open_failed_or_link",opened);continue;
                    }
                    long fd=descriptor(opened);stage="tree_stat";long[] before;
                    try{before=stat(fd);}catch(IOException unavailable) {
                        if(!"qnx_remote_error".equals(unavailable.getMessage()))throw unavailable;
                        outcome="partial_or_unavailable";inventoryRow(inventory,"probe",path,"unknown",null,"stat_unavailable",lastReply);
                        send("c:"+fd+"\r\n");fields();continue;
                    }
                    int type=(int)(before[9]&0xf000);
                    if(type==0x4000) {
                        {
                            // Directory pages use qconn's directory handle, not an open(2)
                            // file descriptor. Verify the reopened directory is the same object.
                            send("c:"+fd+"\r\n");fields();
                            send("o:\""+path+"\":9880:4000\r\n");fd=descriptor(reply());
                            long[] directoryStat=stat(fd);
                            if(directoryStat[0]!=before[0]||directoryStat[2]!=before[2]
                                    ||(directoryStat[9]&0xf000)!=0x4000)throw new IOException("qnx_directory_changed");
                            final String parent=path;
                            final java.util.Set<String> names=new java.util.HashSet<>();
                            final boolean[] invalid={false};stage="tree_directory_read";
                            try { directory(fd,bytes->{
                                String name;
                                try { name=utf8(bytes); }
                                catch(java.nio.charset.CharacterCodingException bad) {
                                    invalid[0]=true;inventoryRow(inventory,"name",parent,"unknown",null,"invalid_utf8",hex(bytes));return;
                                }
                                if(name.equals(".")||name.equals("..")||!names.add(name))return;
                                if(name.indexOf('/')>=0||!fullPath(parent+"/"+name)) {
                                    invalid[0]=true;inventoryRow(inventory,"name",parent,"unknown",null,"invalid_name",hex(bytes));return;
                                }
                                enqueue(queue,parent+"/"+name);
                            }); }catch(IOException unavailable) {
                                if(!"qnx_remote_error".equals(unavailable.getMessage())&&!"qnx_directory_repeated_page".equals(unavailable.getMessage()))throw unavailable;
                                invalid[0]=true;inventoryRow(inventory,"enumeration",path,"directory",before,"incomplete",lastReply);
                            }
                            if(invalid[0])outcome="partial_or_unavailable";
                            inventoryRow(inventory,"probe",path,"directory",before,invalid[0]?"partial_enumeration":"enumerated",invalid[0]?"see errors":"EOF");
                        }
                    } else if(type==0x8000) {
                        String previous=receiver.previousFile(path,before[1]);
                        if(previous!=null) {
                            inventoryRow(inventory,"file",path,"regular",before,"excluded_previous_dump",previous);
                            send("c:"+fd+"\r\n");fields();continue;
                        }
                        String result="partial_read_failed";
                        try(Artifact data=new Artifact(receiver,"tree"+path)) {
                            try {
                                stage="tree_read_first";byte[] first=readFile(fd,before[1],data.out);
                                stage="tree_read_second";byte[] second=readFile(fd,before[1],null);
                                stage="tree_stat_after";long[] after=stat(fd);
                                boolean same=Arrays.equals(first,second)&&sameFile(before,after);
                                result=same?"collected_double_read":"changed_during_read";
                                inventoryRow(inventory,"file",path,"regular",before,result,"first_sha256="+hex(first)+" second_sha256="+hex(second)
                                        +" content_match="+Arrays.equals(first,second)+" metadata_match="+sameFile(before,after)
                                        +" before_size="+before[1]+" after_size="+after[1]);
                            } catch(IOException unavailable) {
                                if(!"qnx_remote_error".equals(unavailable.getMessage())&&!"qnx_short_read".equals(unavailable.getMessage()))throw unavailable;
                                inventoryRow(inventory,"file",path,"regular",before,result,lastReply);
                            } finally { data.finish(result); }
                        }
                        if(!result.equals("collected_double_read"))outcome="partial_or_unavailable";
                    } else {
                        inventoryRow(inventory,"probe",path,"special",before,"excluded_special","no content read");
                    }
                    stage="tree_close";send("c:"+fd+"\r\n");fields();
                }
                inventory.append("# traversal_finished=true examined=").append(examined).append('\n');
            } catch(Exception error) {
                outcome="partial_or_unavailable";
                inventoryRow(inventory,"failure",path,"unknown",null,"read_or_protocol_failed",report(error));
                while(cursor<queue.length()) {
                    queue.seek(cursor);byte[] raw=new byte[queue.readInt()];queue.readFully(raw);cursor=queue.getFilePointer();
                    inventoryRow(inventory,"probe",utf8(raw),"unknown",null,"not_attempted_after_failure","previously discovered");
                }
                inventory.append("# traversal_finished=false examined=").append(examined).append('\n');
                throw new Failure(path,report(error),error);
            } finally { inventory.finish(outcome); }
        } finally { ioProgress=null;queueFile.delete(); }
    }
    /** No cmdline, environment, address-space reads, devctl, launcher or process control. */
    public void collectProcesses(Receiver receiver) throws Exception {
        try(Artifact index=new Artifact(receiver,"metadata/processes.tsv");
            Artifact maps=new Artifact(receiver,"metadata/graphics-mappings.txt");
            Artifact procTrace=new Artifact(receiver,"metadata/proc-read-trace.tsv")) {
        trace=procTrace;
        trace.append("path\toffset\trequested\treceived\tsha256\tcontrol_reply\n");
        index.append("pid\tstatus\texecutable\n");
        maps.append("QNX mappings: format=3; non-atomic live snapshot\n"
                +"Full sequential attempts are separate qnx/mappings/PID-wide.txt entries, read to EOF without a byte cap. Never concatenate attempts.\n");
        String indexStatus="collected_snapshot", mapsStatus="collected_snapshot";
        java.util.Set<String> pids=new java.util.LinkedHashSet<>();
        java.util.Set<String> indexed=new java.util.HashSet<>(), mapped=new java.util.HashSet<>();
        java.util.Map<String,String> graphics=new java.util.LinkedHashMap<>();
        boolean inventoryDone=false;
        try {
            path="/proc";handshake();stage="proc_directory";
            send("o:\"/proc\":0:4000\r\n");String opened=reply();
            if(opened.startsWith("e:"))throw new IOException("qnx_proc_open_denied_or_unavailable");
            long fd=descriptor(opened);
            directory(fd, raw -> { String name=utf8(raw);if(name.matches("[1-9][0-9]{0,9}"))pids.add(name); });
            send("c:"+fd+"\r\n");fields();
            // Inventory first. A large mappings file must never hide later process names.
            for(String pid:pids) {
                path="/proc/"+pid+"/exefile";stage="proc_exefile";
                ProcRead data;
                try { data=procText(path,4096); }
                catch(ProcFailure error) {
                    indexed.add(pid);
                    index.append(pid).append("\tread_or_protocol_failed\t-")
                            .append("\n# exefile_prefix=").append(safe(new String(error.prefix,StandardCharsets.UTF_8))).append('\n');
                    throw error;
                }
                indexed.add(pid);
                if(!data.status.equals("read")) {
                    indexStatus="partial_or_unavailable";
                    index.append(pid).append('\t').append(data.status).append("\t-\n");continue;
                }
                String exe=new String(data.bytes,StandardCharsets.UTF_8).replace("\u0000", "").trim();
                index.append(pid).append("\tread\t").append(safe(exe)).append('\n');
                String name=exe.substring(exe.lastIndexOf('/')+1);
                if(Arrays.asList("openwfd_server","openwfd_telltale","wfd_be","screen",
                        "screen-pwrmgr","cluster_controller","sc_qnx_vip_proxy",
                        "UDS_SOMEIP_Gateway","life_cycle_man_demo","mtouch").contains(name))
                    graphics.put(pid,exe);
            }
            inventoryDone=true;
            if(!indexStatus.equals("collected_snapshot"))mapsStatus="partial_or_unavailable";
            // One denied/exited or truncated process is a result, not a broken transport.
            for(java.util.Map.Entry<String,String> process:graphics.entrySet()) {
                String pid=process.getKey();
                maps.append("--- pid=").append(pid).append(" executable=").append(safe(process.getValue())).append('\n');
                path="/proc/"+pid+"/mappings";stage="proc_mappings";mapped.add(pid);
                ProcRead mapping;
                try { mapping=procText(path,MAPPING_LIMIT); }
                catch(ProcFailure error) {
                    appendMapping(maps,"read_or_protocol_failed",error.prefix);throw error;
                }
                if(mapping.status.equals("read") && !validMappings(mapping.bytes))
                    mapping=new ProcRead(mapping.bytes,"read_content_invalid");
                if(!mapping.status.equals("read"))mapsStatus="partial_or_unavailable";
                appendMapping(maps,mapping.status,mapping.bytes);
                if(mapping.status.equals("changed_during_read") || mapping.status.equals("read_content_invalid")
                        || mapping.status.equals("truncated_size_limit")) {
                    // Independent stream to EOF, never splice two live snapshots.
                    maps.append("--- pid=").append(pid).append(" attempt=wide_sequential\n");
                    stage="proc_mappings_wide";
                    fullMapping(receiver,maps,pid);
                }
            }
            maps.append("graphics_processes_found=").append(graphics.size())
                    .append("\ngraphics_processes_attempted=").append(mapped.size()).append('\n');
        } catch(Exception error) {
            if(!inventoryDone) {
                indexStatus="partial_or_unavailable";
                index.append("# ").append(safe(report(error))).append('\n');
            }
            mapsStatus="partial_or_unavailable";
            maps.append("# ").append(safe(report(error))).append('\n');
            for(String pid:pids)if(!indexed.contains(pid))index.append(pid).append("\tnot_attempted_after_failure\t-\n");
            for(String pid:graphics.keySet())if(!mapped.contains(pid))maps.append("--- pid=").append(pid)
                    .append(" status=not_attempted_after_failure\n");
            throw new Failure(path,report(error),error);
        } finally {
            index.finish(indexStatus);maps.finish(mapsStatus);trace.finish(mapsStatus);
        }
        } finally { trace=null; }
    }
    /** Format gate only: valid rows do not prove an atomic or complete live snapshot. */
    static boolean validMappings(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(text.indexOf('\ufffd')>=0 || !text.endsWith("\n"))return false;
        String[] rows=text.split("\n",-1);
        String header="mi_vaddr,ri_flags,mi_flags,mi_pte_prot,mi_prot,ri_prot,ri_maxprot,mi_paddr,mi_hwattr,mi_pgsize,mi_sharecnt,dev,ino,ri_offset,object_name";
        if(!rows[0].equals(header))return false;
        for(int row=1;row<rows.length-1;row++) {
            // A comma in an object name is legitimate; only the first 14 delimiters matter.
            String[] fields=rows[row].split(",",15);
            if(fields.length!=15)return false;
            for(int field=0;field<14;field++)
                if(!fields[field].matches("(?:0x)?[0-9a-fA-F]{1,16}"))return false;
            for(int i=0;i<fields[14].length();i++)
                if(Character.isISOControl(fields[14].charAt(i)))return false;
        }
        return true;
    }
    private static void appendMapping(Artifact out,String status,byte[] bytes) throws IOException {
        out.append("status=").append(status).append(" bytes=").append(bytes.length).append('\n')
                .append(new String(bytes,StandardCharsets.UTF_8).replace('\ufffd','?')).append('\n');
    }
    private void fullMapping(Receiver receiver,Artifact index,String pid) throws Exception {
        String name="mappings/"+pid+"-wide.txt",status="read_or_protocol_failed";
        try(Artifact data=new Artifact(receiver,name)) {
            try {
                path="/proc/"+pid+"/mappings";offset=0;
                trace.append("# attempt path=").append(path).append(" strategy=wide_sequential_to_eof\n");
                send("o:\""+path+"\":0\r\n");String opened=reply();
                if(opened.startsWith("e:"))status="denied_or_exited";
                else {
                    long fd=descriptor(opened);
                    while(true) {
                        int count=MAPPING_LIMIT+1;
                        send("r:"+fd+":"+Long.toHexString(offset)+":"+Integer.toHexString(count)+":0\r\n");
                        String[] fields=fields();if(fields.length<2)throw new IOException("qnx_bad_proc_reply");
                        long got=number(fields[1],16);if(got>count)throw new IOException("qnx_bad_proc_count");
                        ByteArrayOutputStream block=new ByteArrayOutputStream((int)got);
                        for(int n=0;n<got;n+=2048) {
                            byte[] part=exact((int)Math.min(2048,got-n));data.out.write(part);block.write(part);
                        }
                        traceRead(path,count,block.toByteArray());
                        if(got==0)break;
                        offset=Math.addExact(offset,got);
                        receiver.progress("Mappings QNX: PID "+pid+" · "+offset+" байт");
                    }
                    send("c:"+fd+"\r\n");fields();data.flush();
                    status=validMappings(data.file)?"read":"read_content_invalid";
                }
            } catch(Exception error) {
                trace.append("# failed path=").append(path).append(" offset=").append(offset)
                        .append(" reply=").append(safe(lastReply)).append(" error=").append(safe(error.getMessage())).append('\n');
                throw error;
            } finally {
                data.flush();index.append("status=").append(status).append(" file=qnx/").append(name)
                        .append(" bytes=").append(data.file.length()).append(" sha256=").append(fileHash(data.file)).append('\n');
                data.finish(status);
            }
        }
    }
    private static String fileHash(File file) throws Exception {
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) { byte[] chunk=new byte[32768];int n;
            while((n=in.read(chunk))!=-1)hash.update(chunk,0,n); }
        return hex(hash.digest());
    }
    /** Validate arbitrarily large mappings with bounded parser memory; raw bytes remain intact. */
    static boolean validMappings(File file) throws IOException {
        String header="mi_vaddr,ri_flags,mi_flags,mi_pte_prot,mi_prot,ri_prot,ri_maxprot,mi_paddr,mi_hwattr,mi_pgsize,mi_sharecnt,dev,ino,ri_offset,object_name\n";
        try(Reader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)))) {
            for(int i=0;i<header.length();i++)if(in.read()!=header.charAt(i))return false;
            int field=0,c;StringBuilder number=new StringBuilder();boolean rowStarted=false;
            while((c=in.read())!=-1) {
                rowStarted=true;
                if(field<14) {
                    if(c==',') { if(!mappingNumber(number))return false;number.setLength(0);field++; }
                    else { if(number.length()>=18||c=='\n')return false;number.append((char)c); }
                } else if(c=='\n') { field=0;rowStarted=false; }
                else if(Character.isISOControl((char)c))return false;
            }
            return !rowStarted&&field==0;
        } catch(java.nio.charset.CharacterCodingException invalid) { return false; }
    }
    private static boolean mappingNumber(CharSequence value) {
        int start=value.length()>=2&&value.charAt(0)=='0'&&value.charAt(1)=='x'?2:0;
        if(value.length()-start<1||value.length()-start>16)return false;
        for(int i=start;i<value.length();i++) {
            char c=value.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F'))return false;
        }
        return true;
    }
    public static boolean allowedLog(String name) {
        return name.matches("(display|wfd_be|screen|screen_QC|openwfd_server|openwfd_server_QC|cluster_controller)\\.[1-9][0-9]{0,9}(?:\\.\\.[0-9]{1,3})?");
    }
    private static int logPriority(String name) {
        String family=name.substring(0,name.indexOf('.'));
        return Arrays.asList("cluster_controller","wfd_be","openwfd_server",
                "openwfd_server_QC","screen","screen_QC","display").indexOf(family);
    }
    /** Existing shared-memory files only. Never invokes slog2info or a logging daemon. */
    public void collectLogs(Receiver receiver) throws Exception {
        try(Artifact inventory=new Artifact(receiver,"metadata/slog2-inventory.tsv")) {
        inventory.append("name\tstatus\tbytes\n# live, non-atomic; local SHA only\n");
        String status="collected_snapshot";
        try {
            path="/dev/shmem/slogger2";handshake();stage="slog_directory";
            send("o:\"/dev/shmem/slogger2\":0:4000\r\n");String opened=reply();
            if(opened.startsWith("e:")) {
                status="partial_or_unavailable";inventory.append("-\tdenied_or_unavailable\t0\n");return;
            }
            long directory=descriptor(opened);
            java.util.Set<String> names=new java.util.TreeSet<>((a,b)-> {
                int priority=Integer.compare(logPriority(a),logPriority(b));
                return priority!=0?priority:a.compareTo(b);
            });
            directory(directory, raw -> { String name=utf8(raw);if(allowedLog(name))names.add(name); });
            send("c:"+directory+"\r\n");fields();
            if(names.isEmpty()){status="partial_or_unavailable";inventory.append("-\tno_matching_logs\t0\n");}
            for(String name:names) {
                path="/dev/shmem/slogger2/"+name;stage="slog_open";
                send("o:\""+path+"\":0\r\n");opened=reply();
                if(opened.startsWith("e:")) {
                    status="partial_or_unavailable";inventory.append(name).append("\tdenied_or_exited\t0\n");continue;
                }
                long fd=descriptor(opened);stage="slog_stat";long[] before=stat(fd);
                // QNX /dev/shmem objects use S_IFNAM, not necessarily S_IFREG.
                // This exception is scoped to the basename allowlist above; ordinary
                // firmware collection retains its regular-file-only check.
                inventory.append("# ").append(name).append(" before=").append(Arrays.toString(before))
                        .append(" mode=0x").append(Long.toHexString(before[9])).append('\n');
                long type=before[9]&0xf000;
                if(type!=0x8000 && type!=0x5000) {
                    send("c:"+fd+"\r\n");fields();status="partial_or_unavailable";
                    inventory.append(name).append("\tunsupported_file_type\t0\n");continue;
                }
                try(Artifact data=new Artifact(receiver,"slog2/"+name)) {
                String fileStatus="partial_read_failed";
                try {
                    stage="slog_read";readFile(fd,before[1],data.out);
                    stage="slog_stat_after";long[] after=stat(fd);
                    inventory.append("# ").append(name).append(" before=").append(Arrays.toString(before))
                            .append(" after=").append(Arrays.toString(after)).append('\n');
                    send("c:"+fd+"\r\n");fields();
                    fileStatus=before[0]!=after[0]||before[1]!=after[1]||before[6]!=after[6]||before[8]!=after[8]||before[9]!=after[9]
                            ?"changed_during_read":"collected_live_file";
                } finally {
                    if(!fileStatus.equals("collected_live_file"))status="partial_or_unavailable";
                    data.flush();inventory.append(name).append('\t').append(fileStatus).append('\t').append(data.file.length()).append('\n');
                    data.finish(fileStatus);
                }
                }
            }
        } catch(Exception error) {
            status="partial_or_unavailable";inventory.append("# ").append(safe(report(error))).append('\n');
            throw new Failure(path,report(error),error);
        } finally {
            inventory.finish(status);
        }
        }
    }

    private static final class ProcRead {
        final byte[] bytes;final String status;
        ProcRead(byte[] bytes,String status){this.bytes=bytes;this.status=status;}
    }
    private static final class ProcFailure extends IOException {
        final byte[] prefix;
        ProcFailure(byte[] prefix,Exception cause){super(cause.getMessage(),cause);this.prefix=prefix;}
    }
    private ProcRead procText(String target,int limit) throws Exception {
        if(!target.matches("/proc/[1-9][0-9]{0,9}/(exefile|mappings)"))throw new IOException("qnx_proc_target_not_allowed");
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        byte[] uncommitted=new byte[0];
        try {
            offset=0;send("o:\""+target+"\":0\r\n");String opened=reply();
            if(opened.startsWith("e:"))return new ProcRead(new byte[0],"denied_or_exited");
            long fd=descriptor(opened);String status="read";
            trace.append("# attempt path=").append(target).append(" strategy=bounded_overlap\n");
            while(true) {
                offset=data.size();int count=Math.min(2048,limit+1-data.size());
                send("r:"+fd+":"+Integer.toHexString(data.size())+":"+Integer.toHexString(count)+":0\r\n");
                String[] f=fields();if(f.length<2)throw new IOException("qnx_bad_proc_reply");
                long got=number(f[1],16);if(got>count)throw new IOException("qnx_bad_proc_count");
                if(got==0){
                    traceRead(target,count,new byte[0]);
                    // An overlapping retry may see a shorter live snapshot. Do not
                    // silently turn a previously received partial row into success.
                    if(uncommitted.length>0){
                        data.write(uncommitted);uncommitted=new byte[0];status="read_content_invalid";
                    }
                    break;
                }
                ByteArrayOutputStream block=new ByteArrayOutputStream();
                try {
                    for(int n=0;n<got;n+=2048)block.write(exact((int)Math.min(2048,got-n)));
                } catch(Exception error) {
                    // Keep complete transport pieces received before disconnect.
                    throw error;
                }
                byte[] part=block.toByteArray();traceRead(target,count,part);
                boolean overlapChanged=part.length<uncommitted.length;
                for(int i=0;i<Math.min(uncommitted.length,part.length);i++)
                    if(uncommitted[i]!=part[i]){overlapChanged=true;break;}
                if(overlapChanged) {
                    // Preserve the original received prefix. A contradictory retry
                    // is evidence, not a replacement for the previously received tail.
                    trace.append("# overlap_mismatch path=").append(target)
                        .append(" offset=").append(offset)
                        .append(" expected_tail_hex=").append(hex(uncommitted))
                        .append(" reply_hex=").append(hex(part)).append('\n');
                    data.write(uncommitted);uncommitted=new byte[0];
                    status="changed_during_read";break;
                }
                int keep=Math.min(part.length,limit-data.size());
                uncommitted=new byte[0];
                if(keep<part.length){data.write(part,0,keep);status="truncated_size_limit";break;}
                if(target.endsWith("/mappings") && got==2048 && count==2048) {
                    // Candidate for observed QNX pseudo-file loss at chunk boundaries:
                    // re-read the trailing row from its start using the same proven
                    // offset/count GET dialect. Device validation remains necessary.
                    // Trace offsets deliberately overlap; never synthesize fields.
                    int lastLf=-1;
                    for(int i=part.length-1;i>=0;i--)if(part[i]=='\n'){lastLf=i;break;}
                    if(lastLf>=0 && lastLf<part.length-1) {
                        uncommitted=Arrays.copyOfRange(part,lastLf+1,part.length);
                        keep=lastLf+1;
                    }
                }
                data.write(part,0,keep);
            }
            send("c:"+fd+"\r\n");fields();return new ProcRead(data.toByteArray(),status);
        } catch(Exception error) {
            trace.append("# failed path=").append(target)
                    .append(" offset=").append(offset).append(" reply=").append(safe(lastReply))
                    .append(" error=").append(safe(error.getMessage())).append('\n');
            // Preserve validated payload chunks; never resume a desynchronised stream.
            data.write(uncommitted);
            throw new ProcFailure(data.toByteArray(),error);
        }
    }
}
