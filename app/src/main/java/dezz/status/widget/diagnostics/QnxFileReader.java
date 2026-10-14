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
    }
    public static final String[] TARGETS = {
        "/etc/system/config/variant.cfg", "/bin/openwfd_server", "/bin/openwfd_telltale",
        "/bin/screen", "/lib/libopenwfd_qnx.so",
        "/lib/libMAX96755_QC_0_UART.so", "/lib/libMAX96755_QC_1_UART.so",
        "/lib/libDP_COMMON_QC_UART.so", "/bin/qcdisplaycfg.xml",
        "/scripts/startup.sh", "/ramdisk/linux.config"
    };
    public static final String[] SNAPSHOTS = {"processes.tsv", "graphics-mappings.txt"};
    private final Wire wire;
    private byte[] pending = new byte[0];
    private int cursor;
    private String stage="greeting", path="-", lastReply="-";
    private long offset, rx, tx;
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
                lastReply=safe(reply);return reply;
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
        String result="collected_snapshot";
        try {
            path="/proc";handshake();stage="proc_directory";
            send("o:\"/proc\":0:4000\r\n");String opened=reply();
            if(opened.startsWith("e:"))throw new IOException("qnx_proc_open_denied_or_unavailable");
            long fd=descriptor(opened);
            java.util.Set<String> pids=new java.util.LinkedHashSet<>();
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
                        pids.add(name);if(pids.size()>256)throw new IOException("qnx_pid_limit");
                    }
                }
            }
            send("c:"+fd+"\r\n");fields();
            if(!eof)throw new IOException("qnx_directory_page_limit");
            int graphics=0;
            for(String pid:pids) {
                path="/proc/"+pid+"/exefile";stage="proc_exefile";
                byte[] data=procText(path,4096);
                if(data==null){index.append(pid).append("\tdenied_or_exited\t-\n");continue;}
                String exe=new String(data,StandardCharsets.UTF_8).replace("\u0000", "").trim();
                index.append(pid).append("\tread\t").append(safe(exe)).append('\n');
                String name=exe.substring(exe.lastIndexOf('/')+1);
                if(Arrays.asList("openwfd_server","openwfd_telltale","screen","screen-pwrmgr","cluster_controller").contains(name)) {
                    if(++graphics>8)throw new IOException("qnx_graphics_process_limit");
                    path="/proc/"+pid+"/mappings";stage="proc_mappings";
                    maps.append("--- pid=").append(pid).append(" executable=").append(safe(exe)).append('\n');
                    byte[] mapping=procText(path,65536);
                    maps.append(mapping==null?"denied_or_exited\n":new String(mapping,StandardCharsets.UTF_8)+"\n");
                }
            }
            maps.append("graphics_processes=").append(graphics).append('\n');
        } catch(Exception error) {
            result="partial_or_unavailable";
            index.append("# ").append(safe(report(error))).append('\n');
            throw new Failure(path,report(error),error);
        } finally {
            receiver.snapshot(SNAPSHOTS[0],result,index.toString().getBytes(StandardCharsets.UTF_8));
            receiver.snapshot(SNAPSHOTS[1],result,maps.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private byte[] procText(String target,int limit) throws Exception {
        if(!target.matches("/proc/[1-9][0-9]{0,9}/(exefile|mappings)"))throw new IOException("qnx_proc_target_not_allowed");
        send("o:\""+target+"\":0\r\n");String opened=reply();
        if(opened.startsWith("e:"))return null;
        long fd=descriptor(opened);ByteArrayOutputStream data=new ByteArrayOutputStream();
        while(true) {
            offset=data.size();int count=Math.min(2048,limit+1-data.size());
            send("r:"+fd+":"+Integer.toHexString(data.size())+":"+Integer.toHexString(count)+":0\r\n");
            String[] f=fields();if(f.length<2)throw new IOException("qnx_bad_proc_reply");
            long got=number(f[1],16);if(got>count)throw new IOException("qnx_bad_proc_count");
            if(got==0)break;
            data.write(exact((int)got));
            if(data.size()>limit)throw new IOException("qnx_proc_size_limit");
        }
        send("c:"+fd+"\r\n");fields();return data.toByteArray();
    }
}
