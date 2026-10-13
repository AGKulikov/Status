package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class QnxFileReaderReplay {
    static class Peer implements QnxFileReader.Wire {
        ByteArrayOutputStream queue=new ByteArrayOutputStream();
        byte[] body=new byte[4101]; int reads, mode; List<String> frames=new ArrayList<>();
        Peer(int mode)throws Exception {this.mode=mode;for(int i=0;i<body.length;i++)body[i]=(byte)i;
            add("QCONN\r\n");queue.write(new byte[]{(byte)255,(byte)253,34});}
        void add(String s)throws Exception {queue.write(s.getBytes(StandardCharsets.US_ASCII));}
        public byte[] read() {byte[] all=queue.toByteArray();queue.reset();
            if(all.length==0)return null;
            // Deliberately fragment every header and binary payload.
            int n=Math.min(7,all.length);queue.write(all,n,all.length-n);return Arrays.copyOf(all,n);}
        public void write(byte[] bytes)throws Exception {
            String s=new String(bytes,StandardCharsets.US_ASCII);frames.add(s);
            if(s.equals("service file\r\n")){add("OK\r\n");return;}
            if(s.startsWith("o:")){add(mode==1?"e:permission\r\n":"o:0\r\n");return;}
            if(s.equals("s:0\r\n")){add("o:1:1005:0:0:0:0:1:1:1:81a4:1:0:0:0:0\r\n");return;}
            if(s.startsWith("r:0:")){
                String[] f=s.trim().split(":");int offset=Integer.parseInt(f[2],16),n=Integer.parseInt(f[3],16);
                if(mode==2){add("o:ffff\r\n");return;}
                add("o:"+Integer.toHexString(n)+"\r\n");
                byte[] b=Arrays.copyOfRange(body,offset,offset+n);
                if(mode==3&&reads>=3)b[0]^=1;
                queue.write(b);reads++;return;
            }
            if(s.equals("c:0\r\n")){add("o\r\n");return;}
            throw new AssertionError("Forbidden frame: "+s);
        }
    }
    public static void main(String[] args)throws Exception {
        for(int mode=0;mode<4;mode++) {
            Peer p=new Peer(mode);int[] received={0};final int m=mode;
            try {new QnxFileReader(p).collect((path,status,bytes)->{
                if(m==0&&(!status.equals("collected_double_read")||!Arrays.equals(bytes,p.body)))throw new AssertionError();
                if(m==1&&!status.equals("remote_open_failed"))throw new AssertionError();
                if(m==3&&received[0]==0&&!status.equals("changed_during_read"))throw new AssertionError();
                received[0]++;
            });if(mode==2)throw new AssertionError("Oversized read accepted");}
            catch(IOException e){if(mode!=2)throw e;}
            if(mode!=2&&received[0]!=6)throw new AssertionError();
        }
        System.out.println("PASS QNX exact GET frames, fragmented binary, double read, denial, malformed count, changed content");
    }
}
