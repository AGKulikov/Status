package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
public class QnxFileReaderReplay {
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
    public static void main(String[] args)throws Exception {
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
            if(mode!=2&&received[0]!=6)throw new AssertionError();
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
