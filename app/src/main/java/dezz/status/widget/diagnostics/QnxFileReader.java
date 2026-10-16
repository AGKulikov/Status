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
        "/lib64/libMAX96755_QC_1_UART.so", "/lib64/libDP_COMMON_QC_UART.so"
    };
    public static final String[] SNAPSHOTS = {"processes.tsv", "graphics-mappings.txt", "proc-read-trace.tsv", "slog2-inventory.tsv"};
    public static final int MAPPING_LIMIT = 256 * 1024, GRAPHICS_LIMIT = 8;
    private final Wire wire;
    private byte[] pending = new byte[0];
    private int cursor;
    private String stage="greeting", path="-", lastReply="-";
    private long offset, rx, tx;
    private final StringBuilder trace=new StringBuilder("path\toffset\trequested\treceived\tsha256\tcontrol_reply\n");
    private boolean traceTruncated;
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
        if(trace.length()>2*1024*1024){traceTruncated=true;return;}
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
    private void send(String frame) throws Exception { byte[] bytes=frame.getBytes(StandardCharsets.US_ASCII);wire.write(bytes);tx+=bytes.length; }
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
        long[] s=new long[15];for(int i=0;i<15;i++)s[i]=number(f[i+1],16);
        return s;
    }
    private byte[] readFile(long fd,int size) throws Exception {
        ByteArrayOutputStream data=new ByteArrayOutputStream(size);
        while(data.size()<size) {
            offset=data.size();
            int count=Math.min(2048,size-data.size());
            send("r:"+fd+":"+Integer.toHexString(data.size())+":"+Integer.toHexString(count)+":0\r\n");
            String[] f=fields(); if(f.length<2) throw new IOException("qnx_bad_count");
            long got=number(f[1],16);
            if(got<1 || got>count) throw new IOException("qnx_short_read");
            data.write(exact((int)got));
        }
        return data.toByteArray();
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
            if((before[9]&0xf000)!=0x8000 || before[1]>8*1024*1024) {
                stage="close";send("c:"+fd+"\r\n");fields();
                receiver.file(path,(before[9]&0xf000)!=0x8000?"not_regular":"size_limit",null);
                receiver.diagnostic(path,report(null));continue;
            }
            stage="read_first";byte[] bytes=readFile(fd,(int)before[1]);
            // qconn has no remote SHA operation: independently reread the descriptor.
            stage="read_second";byte[] second=readFile(fd,(int)before[1]);
            stage="stat_after";long[] after=stat(fd);
            boolean same=Arrays.equals(bytes,second) && before[0]==after[0]
                    && before[1]==after[1] && before[6]==after[6] && before[8]==after[8] && before[9]==after[9];
            stage="close";send("c:"+fd+"\r\n");fields();
            stage="deliver";
            receiver.file(path,same?"collected_double_read":"changed_during_read",same?bytes:null);
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
        if(!line().equals("QCONN"))throw new IOException("qnx_greeting_mismatch");
        stage="negotiation";
        if(!Arrays.equals(exact(3),new byte[]{(byte)255,(byte)253,34}))
            throw new IOException("qnx_handshake_mismatch");
        stage="service_file";
        send("service file\r\n");if(!reply().equals("OK"))throw new IOException("qnx_service_failed");
    }
    /** No cmdline, environment, address-space reads, devctl, launcher or process control. */
    public void collectProcesses(Receiver receiver) throws Exception {
        StringBuilder index=new StringBuilder("pid\tstatus\texecutable\n");
        StringBuilder maps=new StringBuilder("QNX mappings: metadata only; non-atomic live snapshot\n");
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
            boolean eof=false;
            int total=0;
            for(int page=0;page<256;page++) {
                send("r:"+fd+":"+Integer.toHexString(page)+":400:1\r\n");
                String[] f=fields();if(f.length<4)throw new IOException("qnx_bad_directory_reply");
                long length=number(f[2],16);
                if(length>65536 || total+length>131072)throw new IOException("qnx_directory_size_limit");
                int size=(int)length;
                if(size==0){eof=true;break;}
                total+=size;ByteArrayOutputStream data=new ByteArrayOutputStream(size);
                for(int n=0;n<size;n+=2048)data.write(exact(Math.min(2048,size-n)));
                for(String name:new String(data.toByteArray(),StandardCharsets.US_ASCII).split("\r\n",-1)) {
                    if(name.matches("[1-9][0-9]{0,9}")) {
                        if(!pids.contains(name) && pids.size()==256)throw new IOException("qnx_pid_limit");
                        pids.add(name);
                    }
                }
            }
            send("c:"+fd+"\r\n");fields();
            if(!eof)throw new IOException("qnx_directory_page_limit");
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
                        "screen-pwrmgr","cluster_controller","sc_qnx_vip_proxy").contains(name))
                    graphics.put(pid,exe);
            }
            inventoryDone=true;
            if(!indexStatus.equals("collected_snapshot"))mapsStatus="partial_or_unavailable";
            // One denied/exited or truncated process is a result, not a broken transport.
            for(java.util.Map.Entry<String,String> process:graphics.entrySet()) {
                String pid=process.getKey();
                maps.append("--- pid=").append(pid).append(" executable=").append(safe(process.getValue())).append('\n');
                if(mapped.size()>=GRAPHICS_LIMIT) {
                    mapsStatus="partial_or_unavailable";maps.append("status=not_attempted_process_limit\n");continue;
                }
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
                if(mapping.status.equals("changed_during_read") || mapping.status.equals("read_content_invalid")) {
                    // A separate bounded attempt, never splice two live snapshots.
                    maps.append("--- pid=").append(pid).append(" attempt=wide_sequential\n");
                    stage="proc_mappings_wide";
                    try {
                        ProcRead wide=procText(path,MAPPING_LIMIT,true);
                        if(wide.status.equals("read") && !validMappings(wide.bytes))
                            wide=new ProcRead(wide.bytes,"read_content_invalid");
                        appendMapping(maps,wide.status,wide.bytes);
                    } catch(ProcFailure error) {
                        appendMapping(maps,"read_or_protocol_failed",error.prefix);throw error;
                    }
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
            receiver.snapshot(SNAPSHOTS[0],indexStatus,index.toString().getBytes(StandardCharsets.UTF_8));
            receiver.snapshot(SNAPSHOTS[1],mapsStatus,maps.toString().getBytes(StandardCharsets.UTF_8));
            if(traceTruncated)trace.append("# trace_size_limit\n");
            receiver.snapshot("proc-read-trace.tsv",traceTruncated||!mapsStatus.equals("collected_snapshot")?
                    "partial_or_unavailable":"collected_snapshot",trace.toString().getBytes(StandardCharsets.UTF_8));
        }
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
    private static void appendMapping(StringBuilder out,String status,byte[] bytes) {
        out.append("status=").append(status).append(" bytes=").append(bytes.length).append('\n')
                .append(new String(bytes,StandardCharsets.UTF_8).replace('\ufffd','?')).append('\n');
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
        StringBuilder inventory=new StringBuilder("name\tstatus\tbytes\n# live, non-atomic; local SHA only\n");
        String status="collected_snapshot";
        try {
            path="/dev/shmem/slogger2";handshake();stage="slog_directory";
            send("o:\"/dev/shmem/slogger2\":0:4000\r\n");String opened=reply();
            if(opened.startsWith("e:")) {
                status="partial_or_unavailable";inventory.append("-\tdenied_or_unavailable\t0\n");return;
            }
            long directory=descriptor(opened);int total=0;boolean eof=false;
            java.util.Set<String> names=new java.util.TreeSet<>((a,b)-> {
                int priority=Integer.compare(logPriority(a),logPriority(b));
                return priority!=0?priority:a.compareTo(b);
            });
            for(int page=0;page<64;page++) {
                send("r:"+directory+":"+Integer.toHexString(page)+":400:1\r\n");
                String[] f=fields();if(f.length<4)throw new IOException("qnx_bad_directory_reply");
                long length=number(f[2],16);
                if(length>65536||total+length>65536)throw new IOException("qnx_log_inventory_limit");
                if(length==0){eof=true;break;}
                total+=(int)length;ByteArrayOutputStream pageData=new ByteArrayOutputStream();
                for(int n=0;n<length;n+=2048)pageData.write(exact((int)Math.min(2048,length-n)));
                for(String name:new String(pageData.toByteArray(),StandardCharsets.US_ASCII).split("\r\n")) {
                    if(allowedLog(name)) {
                        names.add(name);if(names.size()>128)throw new IOException("qnx_log_name_limit");
                    }
                }
            }
            send("c:"+directory+"\r\n");fields();
            if(!eof){status="partial_or_unavailable";inventory.append("-\tdirectory_page_limit\t0\n");}
            int selected=0, bytesTotal=0;
            if(names.isEmpty()){status="partial_or_unavailable";inventory.append("-\tno_matching_logs\t0\n");}
            for(String name:names) {
                if(selected>=12||bytesTotal>=8*1024*1024) {
                    status="partial_or_unavailable";inventory.append(name).append("\tnot_selected_limit\t0\n");continue;
                }
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
                selected++;
                int limit=(int)Math.min(before[1],Math.min(1024*1024,8*1024*1024-bytesTotal));
                ByteArrayOutputStream data=new ByteArrayOutputStream();String fileStatus="collected_live_file";
                try {
                    stage="slog_read";
                    while(data.size()<limit) {
                        offset=data.size();int count=Math.min(2048,limit-data.size());
                        send("r:"+fd+":"+Integer.toHexString(data.size())+":"+Integer.toHexString(count)+":0\r\n");
                        String[] f=fields();if(f.length<2)throw new IOException("qnx_bad_log_reply");
                        long got=number(f[1],16);if(got<1||got>count)throw new IOException("qnx_short_log_read");
                        data.write(exact((int)got));
                    }
                    stage="slog_stat_after";long[] after=stat(fd);
                    inventory.append("# ").append(name).append(" before=").append(Arrays.toString(before))
                            .append(" after=").append(Arrays.toString(after)).append('\n');
                    if(before[1]>limit)fileStatus="truncated_size_limit";
                    else if(before[0]!=after[0]||before[1]!=after[1]||before[6]!=after[6]||before[8]!=after[8]||before[9]!=after[9])fileStatus="changed_during_read";
                    send("c:"+fd+"\r\n");fields();
                } catch(Exception error) {
                    receiver.log(name,"partial_read_failed",data.toByteArray());
                    inventory.append(name).append("\tpartial_read_failed\t").append(data.size()).append('\n');throw error;
                }
                if(!fileStatus.equals("collected_live_file"))status="partial_or_unavailable";
                receiver.log(name,fileStatus,data.toByteArray());bytesTotal+=data.size();
                inventory.append(name).append('\t').append(fileStatus).append('\t').append(data.size()).append('\n');
            }
        } catch(Exception error) {
            status="partial_or_unavailable";inventory.append("# ").append(safe(report(error))).append('\n');
            throw new Failure(path,report(error),error);
        } finally {
            receiver.snapshot("slog2-inventory.tsv",status,inventory.toString().getBytes(StandardCharsets.UTF_8));
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
        return procText(target,limit,false);
    }
    private ProcRead procText(String target,int limit,boolean wide) throws Exception {
        if(!target.matches("/proc/[1-9][0-9]{0,9}/(exefile|mappings)"))throw new IOException("qnx_proc_target_not_allowed");
        if(wide && (!target.endsWith("/mappings") || limit!=MAPPING_LIMIT))
            throw new IOException("qnx_wide_target_not_allowed");
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        byte[] uncommitted=new byte[0];
        try {
            offset=0;send("o:\""+target+"\":0\r\n");String opened=reply();
            if(opened.startsWith("e:"))return new ProcRead(new byte[0],"denied_or_exited");
            long fd=descriptor(opened);String status="read";
            if(trace.length()<2*1024*1024-512)
                trace.append("# attempt path=").append(target).append(" strategy=")
                    .append(wide?"wide_sequential":"bounded_overlap").append('\n');
            while(true) {
                offset=data.size();int count=Math.min(wide?MAPPING_LIMIT+1:2048,limit+1-data.size());
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
                    if(wide) {
                        byte[] prefix=block.toByteArray();
                        data.write(prefix,0,Math.min(prefix.length,limit-data.size()));
                    }
                    throw error;
                }
                byte[] part=block.toByteArray();traceRead(target,count,part);
                boolean overlapChanged=part.length<uncommitted.length;
                for(int i=0;i<Math.min(uncommitted.length,part.length);i++)
                    if(uncommitted[i]!=part[i]){overlapChanged=true;break;}
                if(overlapChanged) {
                    // Preserve the original received prefix. A contradictory retry
                    // is evidence, not a replacement for the previously received tail.
                    if(trace.length()<2*1024*1024-16384) {
                        trace.append("# overlap_mismatch path=").append(target)
                            .append(" offset=").append(offset)
                            .append(" expected_tail_hex=").append(hex(uncommitted))
                            .append(" reply_hex=").append(hex(part)).append('\n');
                    } else traceTruncated=true;
                    data.write(uncommitted);uncommitted=new byte[0];
                    status="changed_during_read";break;
                }
                int keep=Math.min(part.length,limit-data.size());
                uncommitted=new byte[0];
                if(keep<part.length){data.write(part,0,keep);status="truncated_size_limit";break;}
                if(!wide && target.endsWith("/mappings") && got==2048 && count==2048) {
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
            if(trace.length()<2*1024*1024)trace.append("# failed path=").append(target)
                    .append(" offset=").append(offset).append(" reply=").append(safe(lastReply))
                    .append(" error=").append(safe(error.getMessage())).append('\n');
            // Preserve validated payload chunks; never resume a desynchronised stream.
            data.write(uncommitted);
            throw new ProcFailure(data.toByteArray(),error);
        }
    }
}
