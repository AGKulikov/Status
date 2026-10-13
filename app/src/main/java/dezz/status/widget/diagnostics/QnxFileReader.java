/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Port of the evidence-proven qconn-native-nfogh GET dialect. No generic commands. */
public final class QnxFileReader {
    public interface Wire { byte[] read() throws Exception; void write(byte[] bytes) throws Exception; }
    public interface Receiver { void file(String path, String status, byte[] bytes) throws Exception; }
    public static final String[] TARGETS = {
        "/lib/libMAX96755_QC_0_UART.so", "/lib/libMAX96755_QC_1_UART.so",
        "/lib/libDP_COMMON_QC_UART.so", "/bin/qcdisplaycfg.xml",
        "/scripts/startup.sh", "/ramdisk/linux.config"
    };
    private final Wire wire;
    private byte[] pending = new byte[0];
    private int cursor;
    public QnxFileReader(Wire wire) { this.wire = wire; }
    private byte[] exact(int size) throws Exception {
        if(size<0 || size>2048) throw new IOException("qnx_count_limit");
        byte[] result=new byte[size];
        for(int i=0;i<size;i++) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if(cursor==pending.length) {
                pending=wire.read(); cursor=0;
                if(pending==null || pending.length==0 || pending.length>65536) throw new IOException("qnx_stream_incomplete");
            }
            result[i]=pending[cursor++];
        }
        return result;
    }
    private String line() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<4096;i++) {
            int b=exact(1)[0]&255; bytes.write(b);
            if(b==10) {
                byte[] value=bytes.toByteArray();
                if(value.length<2 || value[value.length-2]!=13) throw new IOException("qnx_bad_line");
                return new String(value,0,value.length-2,StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("qnx_line_limit");
    }
    private void send(String frame) throws Exception { wire.write(frame.getBytes(StandardCharsets.US_ASCII)); }
    private String[] fields() throws Exception {
        String reply=line();
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
        if((s[9]&0xf000)!=0x8000) throw new IOException("qnx_not_regular");
        if(s[1]>8*1024*1024) throw new IOException("qnx_size_limit");
        return s;
    }
    private byte[] readFile(long fd,int size) throws Exception {
        ByteArrayOutputStream data=new ByteArrayOutputStream(size);
        while(data.size()<size) {
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
        if(!line().equals("QCONN") || !Arrays.equals(exact(3),new byte[]{(byte)255,(byte)253,34}))
            throw new IOException("qnx_handshake_mismatch");
        send("service file\r\n");if(!line().equals("OK"))throw new IOException("qnx_service_failed");
        for(String path:TARGETS) {
            send("o:\""+path+"\":0\r\n");
            String reply=line();
            if(reply.startsWith("e:")) { receiver.file(path,"remote_open_failed",null); continue; }
            String[] f=reply.split(":",-1);
            if(f.length<2 || !f[0].equals("o"))throw new IOException("qnx_bad_open");
            long fd=number(f[1],10);if(fd>1000000)throw new IOException("qnx_bad_descriptor");
            long[] before=stat(fd);
            byte[] bytes=readFile(fd,(int)before[1]);
            // qconn has no remote SHA operation: independently reread the descriptor.
            byte[] second=readFile(fd,(int)before[1]);
            long[] after=stat(fd);
            boolean same=Arrays.equals(bytes,second) && before[0]==after[0]
                    && before[1]==after[1] && before[6]==after[6] && before[8]==after[8] && before[9]==after[9];
            send("c:"+fd+"\r\n");fields();
            receiver.file(path,same?"collected_double_read":"changed_during_read",same?bytes:null);
        }
    }
}
