package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
public class QnxFileReaderReplay {
    static final String MAP_HEADER="mi_vaddr,ri_flags,mi_flags,mi_pte_prot,mi_prot,ri_prot,ri_maxprot,mi_paddr,mi_hwattr,mi_pgsize,mi_sharecnt,dev,ino,ri_offset,object_name\n";
    static final String MAP_ROW="0x1000,0x1,0x14,0x3,0x3,0x3,0xf,0xffffffffffffffff,0x0,0x1000,00000001,0x1,0x3,0x0,/lib/libopenwfd_qnx.so\n";
    static class Peer implements QnxFileReader.Wire {
        ByteArrayOutputStream queue=new ByteArrayOutputStream();
        byte[] body=new byte[4101]; int reads, mode; List<String> frames=new ArrayList<>();
        Peer(int mode)throws Exception {this.mode=mode;if(mode==5)body=new byte[38529];
            for(int i=0;i<body.length;i++)body[i]=(byte)i;
            add("QCONN\r\n");queue.write(new byte[]{(byte)255,(byte)253,34});}
        void add(String s)throws Exception {queue.write(s.getBytes(StandardCharsets.US_ASCII));}
        public byte[] read() {byte[] all=queue.toByteArray();queue.reset();
            if(all.length==0)return null;
            // Deliberately fragment every header and binary payload.
            int n=Math.min(7,all.length);queue.write(all,n,all.length-n);return Arrays.copyOf(all,n);}
        public void write(byte[] bytes)throws Exception {
            String s=new String(bytes,StandardCharsets.US_ASCII);frames.add(s);
            if(s.equals("service file\r\n")){add(mode==4?"\r\nerror linemode-or-echo-not-supported\r\nOK\r\n":"OK\r\n");return;}
            // Control headers from the user's 2026-08-15 successful attempt 00450.
            // No proprietary payload is committed; optional local ZIP replay below.
            if(s.startsWith("o:")){add(mode==1?"e:permission\r\n":mode==5?
                    "o:0:8140:9681:\"/bin/qcdisplaycfg.xml\"\r\n":"o:0\r\n");return;}
            if(s.equals("s:0\r\n")){add(mode==5?
                    "o:130:9681:40e:0:0:0:0:0:0:8140:1:1000:a:1000:4c\r\n":
                    "o:1:1005:0:0:0:0:1:1:1:81a4:1:0:0:0:0\r\n");return;}
            if(s.startsWith("r:0:")){
                String[] f=s.trim().split(":");int offset=Integer.parseInt(f[2],16),n=Integer.parseInt(f[3],16);
                if(mode==2){add("o:ffff\r\n");return;}
                add("o:"+Integer.toHexString(n)+(mode==5?":"+Integer.toHexString(n)+":"+Integer.toHexString(offset+n):"")+"\r\n");
                byte[] b=Arrays.copyOfRange(body,offset,offset+n);
                if(mode==3&&reads>=3)b[0]^=1;
                queue.write(b);reads++;return;
            }
            if(s.equals("c:0\r\n")){add("o\r\n");return;}
            throw new AssertionError("Forbidden frame: "+s);
        }
    }
    static class ProcPeer extends Peer {
        final int scenario;String current="";
        ProcPeer(int scenario)throws Exception{super(0);this.scenario=scenario;}
        public byte[] read() {
            byte[] all=queue.toByteArray();queue.reset();
            if(all.length==0)return null;
            int n=Math.min(997,all.length);queue.write(all,n,all.length-n);
            return Arrays.copyOf(all,n);
        }
        public void write(byte[] bytes)throws Exception {
            String s=new String(bytes,StandardCharsets.US_ASCII);frames.add(s);
            if(s.equals("service file\r\n")){add("OK\r\n");return;}
            if(s.startsWith("o:")) {
                current=s.split("\"",-1)[1];
                if(!current.equals("/proc")&&!current.matches("/proc/(12|34)/(exefile|mappings)"))
                    throw new AssertionError("Unexpected path "+current);
                if(scenario==1 || (scenario==2&&current.endsWith("/exefile"))
                        || (scenario==8&&current.equals("/proc/12/mappings"))){add("e:permission\r\n");return;}
                add("o:0\r\n");return;
            }
            if(s.equals("c:0\r\n")){add("o\r\n");return;}
            if(s.startsWith("r:0:")) {
                String[] f=s.trim().split(":");int off=Integer.parseInt(f[2],16),count=Integer.parseInt(f[3],16);
                if(current.equals("/proc")) {
                    if(!f[4].equals("1"))throw new AssertionError();
                    if(scenario==3){add("o:0:100000000:0\r\n");return;}
                    String listing=off==0||scenario==4?".\r\n..\r\n12\r\n34\r\n12\r\n../as\r\n12/ctl\r\nboot\r\n":"";
                    byte[] raw=listing.getBytes(StandardCharsets.US_ASCII);
                    add("o:0:"+Integer.toHexString(raw.length)+":0\r\n");queue.write(raw);return;
                }
                if(!f[4].equals("0"))throw new AssertionError();
                byte[] raw=(current.endsWith("/mappings")?MAP_HEADER+MAP_ROW:
                        current.contains("/12/")?"/bin/screen\n":scenario>=6?"/bin/wfd_be\n":"/bin/unrelated\n").getBytes(StandardCharsets.US_ASCII);
                if(scenario==5)raw=new byte[4097];
                if((scenario==6||scenario==9)&&current.equals("/proc/12/mappings")) {
                    raw=new byte[QnxFileReader.MAPPING_LIMIT+(scenario==6?1:0)];Arrays.fill(raw,(byte)'X');
                }
                if(scenario==10&&current.equals("/proc/12/mappings")) {
                    raw=new byte[65537];Arrays.fill(raw,(byte)'F');
                }
                if(scenario==7&&current.equals("/proc/12/mappings")) {
                    if(off>=2048)throw new IOException("synthetic socket closed");
                    raw=new byte[4096];Arrays.fill(raw,(byte)'P');
                }
                if(scenario>=11 && current.endsWith("/mappings")) {
                    raw=(MAP_HEADER+MAP_ROW.repeat(100)).getBytes(StandardCharsets.US_ASCII);
                    if(off==0){add("o:89\r\n");queue.write(raw,0,137);return;}
                    if(off>137 && off<raw.length && current.equals("/proc/12/mappings")) {
                        if(scenario==12)raw[off]='X';
                        if(scenario==13)throw new IOException("socket closed on overlap");
                        if(scenario==14){add("o:0\r\n");return;}
                    }
                    // A generated procfs row may not support continuation within it.
                    // Model the observed loss, rather than repairing corrupt text.
                    if(raw[off-1]!='\n') {
                        int end=off;while(end<raw.length && raw[end]!='\n')end++;
                        int n=Math.min(count,raw.length-end);
                        add("o:"+Integer.toHexString(n)+"\r\n");queue.write(raw,end,n);return;
                    }
                }
                int n=Math.min(count,Math.max(0,raw.length-off));
                add("o:"+Integer.toHexString(n)+"\r\n");queue.write(raw,off,n);return;
            }
            throw new AssertionError("Unexpected process frame "+s);
        }
    }
    static class LogPeer extends Peer {
        String current="";final int scenario;int stats;
        LogPeer(int scenario)throws Exception{super(0);this.scenario=scenario;}
        public void write(byte[] bytes)throws Exception {
            String s=new String(bytes,StandardCharsets.US_ASCII);frames.add(s);
            if(s.equals("service file\r\n")){add("OK\r\n");return;}
            if(s.startsWith("o:")){
                current=s.split("\"")[1];
                if(!current.equals("/dev/shmem/slogger2")&&!current.equals("/dev/shmem/slogger2/display.123..0"))throw new AssertionError(current);
                add(scenario==1?"e:permission\r\n":"o:0\r\n");return;
            }
            if(s.equals("s:0\r\n")) {
                stats++;int size=scenario==2?1024*1024+1:4101;
                String fileMode=scenario==6?"51a4":scenario==7?"21a4":scenario==8&&stats>1?"51a4":"81a4";
                add("o:1:"+Integer.toHexString(size)+":0:0:0:0:"+(scenario==3&&stats>1?"2":"1")+":1:1:"+fileMode+":1:0:0:0:0\r\n");return;
            }
            if(s.startsWith("r:0:")) {
                String[] f=s.trim().split(":");int off=Integer.parseInt(f[2],16),count=Integer.parseInt(f[3],16);
                if(current.equals("/dev/shmem/slogger2")) {
                    String list=off==0?"display.123..0\r\ndisplay.123..0\r\n../display.123\r\nother.1\r\n":"";
                    add("o:0:"+Integer.toHexString(list.length())+":0\r\n");add(list);return;
                }
                if(scenario==4&&off>=2048)throw new IOException("disconnected");
                if(scenario==5){add("o:ffff\r\n");return;}
                add("o:"+Integer.toHexString(count)+"\r\n");queue.write(new byte[count]);return;
            }
            if(s.equals("c:0\r\n")){add("o\r\n");return;}
            throw new AssertionError(s);
        }
    }
    static void logTests()throws Exception {
        for(String name:new String[]{"../display.12","display.0","display.12/x","display.12;id","screen.1..1234"})
            if(QnxFileReader.allowedLog(name))throw new AssertionError(name);
        for(int mode=0;mode<=8;mode++) {
            final int scenario=mode;Map<String,byte[]> logs=new HashMap<>();String[] inventory={null};boolean failed=false;
            try {new QnxFileReader(new LogPeer(mode)).collectLogs(new QnxFileReader.Receiver(){
                public void file(String p,String s,byte[] b){throw new AssertionError();}
                public void log(String n,String s,byte[] b){
                    if(logs.put(n,b)!=null)throw new AssertionError("duplicate");
                    String expected=scenario==0||scenario==6?"collected_live_file":scenario==2?"truncated_size_limit":scenario==3||scenario==8?"changed_during_read":"partial_read_failed";
                    if(!s.equals(expected))throw new AssertionError(s);
                }
                public void snapshot(String n,String s,byte[] b){
                    if(!n.equals("slog2-inventory.tsv")||!s.equals(scenario==0||scenario==6?"collected_snapshot":"partial_or_unavailable"))throw new AssertionError(s);
                    inventory[0]=new String(b,StandardCharsets.UTF_8);
                }
            });}catch(QnxFileReader.Failure expected){failed=true;}
            if(failed!=(mode==4||mode==5)||inventory[0]==null||logs.size()!=(mode==1||mode==7?0:1))throw new AssertionError("log mode "+mode);
            if(mode==7&&!inventory[0].contains("mode=0x21a4"))throw new AssertionError("rejected stat missing");
            if(mode!=1&&mode!=7&&logs.values().iterator().next().length!=(mode==2?1024*1024:mode==4?2048:mode==5?0:4101))throw new AssertionError("prefix "+mode);
        }
        for(boolean rejectedFirst:new boolean[]{true,false}) {
            LogPeer peer=new LogPeer(0) {
                public void write(byte[] bytes)throws Exception {
                    String s=new String(bytes,StandardCharsets.US_ASCII);
                    if(s.startsWith("o:")) {
                        current=s.split("\"")[1];frames.add(s);add("o:0\r\n");return;
                    }
                    if(s.equals("s:0\r\n")) {
                        frames.add(s);
                        String mode=rejectedFirst && current.contains("cluster_controller")?"21a4":"51a4";
                        add("o:1:1:0:0:0:0:1:1:1:"+mode+":1:0:0:0:0\r\n");return;
                    }
                    if(s.startsWith("r:0:")&&current.equals("/dev/shmem/slogger2")) {
                        frames.add(s);StringBuilder listing=new StringBuilder();
                        if(s.split(":")[2].equals("0")) {
                            for(int i=1;i<=13;i++)listing.append(rejectedFirst?"cluster_controller.":"display.").append(i).append("\r\n");
                            listing.append("wfd_be.99\r\n");
                        }
                        add("o:0:"+Integer.toHexString(listing.length())+":0\r\n");add(listing.toString());return;
                    }
                    super.write(bytes);
                }
            };
            List<String> delivered=new ArrayList<>();
            new QnxFileReader(peer).collectLogs(new QnxFileReader.Receiver(){
                public void file(String p,String s,byte[] b){throw new AssertionError();}
                public void log(String n,String s,byte[] b){
                    if(!s.equals("collected_live_file")||b.length!=1)throw new AssertionError(s);
                    delivered.add(n);
                }
            });
            if(!delivered.contains("wfd_be.99")||delivered.size()!=(rejectedFirst?1:12))
                throw new AssertionError("selection/starvation "+delivered);
            if(!rejectedFirst&&!delivered.get(0).equals("wfd_be.99"))throw new AssertionError("priority");
        }
    }

    static void processTests()throws Exception {
        for(int mode=0;mode<=14;mode++) {
            ProcPeer peer=new ProcPeer(mode);Map<String,String> payloads=new HashMap<>();
            final int scenario=mode;
            boolean failed=false;
            try {new QnxFileReader(peer).collectProcesses(new QnxFileReader.Receiver(){
                public void file(String p,String s,byte[] b){throw new AssertionError();}
                public void snapshot(String n,String s,byte[] b){
                    boolean complete=scenario==0||scenario==11||(scenario>=6&&n.equals("processes.tsv"));
                    if(!s.equals(complete?"collected_snapshot":"partial_or_unavailable"))throw new AssertionError(n+" "+s);
                    payloads.put(n,new String(b,StandardCharsets.UTF_8));
                }
            });}catch(QnxFileReader.Failure expected){failed=true;}
            if(failed!=(mode==1||mode==3||mode==4||mode==7||mode==13)||payloads.size()!=3)throw new AssertionError("mode "+mode);
            if(mode==0) {
                if(!payloads.get("processes.tsv").contains("12\tread\t/bin/screen"))throw new AssertionError();
                if(!payloads.get("graphics-mappings.txt").contains("libopenwfd_qnx.so"))throw new AssertionError();
                if(peer.frames.stream().filter(s->s.equals("o:\"/proc/12/exefile\":0\r\n")).count()!=1)throw new AssertionError();
                if(peer.frames.stream().anyMatch(s->s.contains("/34/mappings")))throw new AssertionError();
            }
            if(mode==2&&!payloads.get("processes.tsv").contains("denied_or_exited"))throw new AssertionError();
            if(mode==11) {
                String maps=payloads.get("graphics-mappings.txt");
                if(maps.contains("invalid")||!maps.contains(MAP_HEADER+MAP_ROW.repeat(100)))
                    throw new AssertionError("row-boundary read did not preserve exact mappings");
                if(peer.frames.contains("r:0:889:800:0\r\n"))throw new AssertionError("continued in partial row");
            }
            if(mode==12) {
                String maps=payloads.get("graphics-mappings.txt");
                String original=(MAP_HEADER+MAP_ROW.repeat(100)).substring(0,2185);
                if(!maps.contains("status=changed_during_read bytes=2185\n"+original))
                    throw new AssertionError("mismatch replaced original prefix");
                String trace=payloads.get("proc-read-trace.tsv");
                if(!trace.contains("# overlap_mismatch path=/proc/12/mappings")
                        ||!trace.contains(" expected_tail_hex=")||!trace.contains(" reply_hex=58"))
                    throw new AssertionError("conflicting reply evidence lost");
                if(!maps.contains("attempt=wide_sequential\nstatus=read bytes="+(MAP_HEADER+MAP_ROW.repeat(100)).length()
                        +"\n"+MAP_HEADER+MAP_ROW.repeat(100)))
                    throw new AssertionError("wide attempt failed to preserve complete independent snapshot");
                if(!peer.frames.contains("r:0:0:40001:0\r\n"))throw new AssertionError("wide request missing");
            }
            if(mode==13&&!payloads.get("graphics-mappings.txt").contains("status=read_or_protocol_failed bytes=2185"))throw new AssertionError("overlap prefix lost");
            if(mode==14&&!payloads.get("graphics-mappings.txt").contains("status=read_content_invalid bytes=2185"))throw new AssertionError("EOF partial row lost");
            if(mode>=6) {
                if(!payloads.get("processes.tsv").contains("34\tread\t/bin/wfd_be"))throw new AssertionError();
                if(peer.frames.indexOf("o:\"/proc/34/exefile\":0\r\n")>
                        peer.frames.indexOf("o:\"/proc/12/mappings\":0\r\n"))throw new AssertionError("inventory must finish first");
                if(mode!=7&&mode!=13&&!peer.frames.contains("o:\"/proc/34/mappings\":0\r\n"))throw new AssertionError("later mapping lost");
                if(mode==6&&!payloads.get("graphics-mappings.txt").contains("status=truncated_size_limit bytes=262144"))throw new AssertionError();
                if(mode==7&&(!payloads.get("graphics-mappings.txt").contains("status=read_or_protocol_failed bytes=2048")
                        ||!payloads.get("graphics-mappings.txt").contains("pid=34 status=not_attempted_after_failure")))throw new AssertionError();
                if(mode==9&&payloads.get("graphics-mappings.txt").contains("truncated_size_limit"))throw new AssertionError("exact limit is not truncated");
                if((mode==9||mode==10)&&!payloads.get("graphics-mappings.txt").contains("status=read_content_invalid bytes="+(mode==9?262144:65537)))throw new AssertionError("invalid full payload must be preserved and marked");
            }
        }
    }
    public static void main(String[] args)throws Exception {
        for(String valid:new String[]{MAP_HEADER,MAP_HEADER+MAP_ROW,MAP_HEADER+MAP_ROW.replace(".so",",extra.so")})
            if(!QnxFileReader.validMappings(valid.getBytes(StandardCharsets.UTF_8)))throw new AssertionError("valid mapping rejected");
        for(String invalid:new String[]{"",MAP_ROW,MAP_HEADER+MAP_ROW.trim(),MAP_HEADER+MAP_ROW.replace("0x14,", ""),MAP_HEADER+MAP_ROW.replace("0x14", "0x140x1000"),MAP_HEADER+MAP_ROW.replace(".so", "\u0000.so")})
            if(QnxFileReader.validMappings(invalid.getBytes(StandardCharsets.UTF_8)))throw new AssertionError("corrupt mapping accepted");
        processTests();
        for(boolean oversized:new boolean[]{false,true}) {
            ProcPeer peer=new ProcPeer(12) {
                public void write(byte[] bytes)throws Exception {
                    String s=new String(bytes,StandardCharsets.US_ASCII);
                    if(s.equals("r:0:0:40001:0\r\n")) {
                        frames.add(s);add(oversized?"o:40002\r\n":"e:unsupported\r\n");return;
                    }
                    super.write(bytes);
                }
            };
            Map<String,String> outputs=new HashMap<>();boolean rejected=false;
            try {new QnxFileReader(peer).collectProcesses(new QnxFileReader.Receiver(){
                public void file(String p,String s,byte[] b){throw new AssertionError();}
                public void snapshot(String n,String s,byte[] b){outputs.put(n,new String(b,StandardCharsets.UTF_8));}
            });}catch(QnxFileReader.Failure expected){rejected=true;}
            if(!rejected||!outputs.get("graphics-mappings.txt").contains("status=changed_during_read bytes=2185")
                    ||!outputs.get("graphics-mappings.txt").contains("status=read_or_protocol_failed bytes=0")
                    ||!outputs.get("graphics-mappings.txt").contains("pid=34 status=not_attempted_after_failure"))
                throw new AssertionError("wide rejection/oversized reply must preserve evidence and stop stream");
        }
        for(String target:new String[]{"/lib64/graphics.conf","/lib64/libMAX96755_QC_0_UART.so",
                "/lib64/libMAX96755_QC_1_UART.so","/lib64/libDP_COMMON_QC_UART.so"})
            if(!Arrays.asList(QnxFileReader.TARGETS).contains(target))throw new AssertionError("observed path missing");
        logTests();
        for(String stat:new String[]{"o:1:800001:0:0:0:0:1:1:1:81a4:1:0:0:0:0\r\n",
                "o:1:0:0:0:0:0:1:1:1:41a4:1:0:0:0:0\r\n"}) {
            Peer rejected=new Peer(0){public void write(byte[] bytes)throws Exception {
                if(new String(bytes,StandardCharsets.US_ASCII).equals("s:0\r\n")){add(stat);return;}
                super.write(bytes);
            }};
            new QnxFileReader(rejected).collectSingle(QnxFileReader.TARGETS[0],(p,s,b)->{
                if(b!=null||!s.equals(stat.contains("800001")?"size_limit":"not_regular"))throw new AssertionError();
            });
            if(rejected.reads!=0||!rejected.frames.contains("c:0\r\n"))throw new AssertionError();
        }
        for(int mode=0;mode<5;mode++) {
            Peer p=new Peer(mode);int[] received={0};final int m=mode;
            try {new QnxFileReader(p).collect((path,status,bytes)->{
                if(m==0&&(!status.equals("collected_double_read")||!Arrays.equals(bytes,p.body)))throw new AssertionError();
                if(m==1&&!status.equals("remote_open_failed"))throw new AssertionError();
                if(m==3&&received[0]==0&&!status.equals("changed_during_read"))throw new AssertionError();
                received[0]++;
            });if(mode==2)throw new AssertionError("Oversized read accepted");}
            catch(QnxFileReader.Failure e){if(mode!=2)throw e;
                if(!e.report.contains("stage=read_first")||!e.report.contains("qnx_short_read"))throw new AssertionError(e.report);}
            if(mode!=2&&received[0]!=QnxFileReader.TARGETS.length)throw new AssertionError();
        }
        QnxFileReader.Wire unavailable=new QnxFileReader.Wire(){
            public byte[] read(){return "nc: connection refused\n".getBytes(StandardCharsets.US_ASCII);}
            public void write(byte[] bytes){throw new AssertionError();}
        };
        try{new QnxFileReader(unavailable).collect((p,s,b)->{});throw new AssertionError();}
        catch(QnxFileReader.Failure e){if(!e.report.contains("stage=greeting")||!e.report.contains("connection refused"))throw new AssertionError(e.report);}
        Peer recorded=new Peer(5);
        if(args.length==1)try(ZipFile zip=new ZipFile(args[0])) {
            recorded.body=zip.getInputStream(zip.getEntry("KX11-QNX-Deep-Evidence/qnx/files/bin/qcdisplaycfg.xml")).readAllBytes();
            if(recorded.body.length!=38529)throw new AssertionError("Evidence size mismatch");
        }
        int[] delivered={0},diagnostics={0};
        new QnxFileReader(recorded).collectSingle("/bin/qcdisplaycfg.xml",new QnxFileReader.Receiver(){
            public void file(String path,String status,byte[] bytes){
                if(!status.equals("collected_double_read")||!Arrays.equals(bytes,recorded.body))throw new AssertionError();
                delivered[0]++;
            }
            public void diagnostic(String path,String details){
                if(!details.contains("stage=deliver")||!details.contains("rx_bytes="))throw new AssertionError(details);
                diagnostics[0]++;
            }
        });
        if(delivered[0]!=1||diagnostics[0]!=1||recorded.reads!=38
                ||!recorded.frames.get(3).equals("r:0:0:800:0\r\n"))throw new AssertionError();
        Peer forbidden=new Peer(0);
        try {new QnxFileReader(forbidden).collectSingle("/etc/shadow",(p,s,b)->{});throw new AssertionError();}
        catch(IOException expected){if(!forbidden.frames.isEmpty())throw new AssertionError();}
        Peer noisy=new Peer(0){public void write(byte[] bytes)throws Exception{add("\r\n".repeat(16));}};
        try {new QnxFileReader(noisy).collectSingle(QnxFileReader.TARGETS[0],(p,s,b)->{});throw new AssertionError();}
        catch(QnxFileReader.Failure expected){if(!expected.report.contains("qnx_reply_noise_limit"))throw new AssertionError();}
        Thread.currentThread().interrupt();
        try{new QnxFileReader(new Peer(0)).collectSingle(QnxFileReader.TARGETS[0],(p,s,b)->{});throw new AssertionError();}
        catch(QnxFileReader.Failure expected){if(!(expected.getCause() instanceof InterruptedIOException))throw new AssertionError();}
        finally{Thread.interrupted();}
        System.out.println("PASS QNX GET frames, recorded control headers, fragmented binary, double read, denial, malformed count, changed content, bounded noise, cancellation, allowlist");
    }
}
