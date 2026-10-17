/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Actual production wire/ZIP replay; synthetic files, no vehicle/network access. */
public final class QnxTreeReplay {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static final class Peer implements QnxFileReader.Wire {
        byte[] queued=new byte[]{'Q','C','O','N','N',13,10,(byte)255,(byte)253,34};int pos;
        String current;boolean directory;int statPass;boolean fail;long readBytes;int opens;
        void reply(String text){queued=text.getBytes(StandardCharsets.UTF_8);pos=0;}
        public byte[] read(){if(pos==queued.length)return null;int end=Math.min(pos+37,queued.length);byte[] b=Arrays.copyOfRange(queued,pos,end);pos=end;return b;}
        public void write(byte[] bytes)throws Exception {
            check(pos==queued.length,"unconsumed reply");String frame=new String(bytes,StandardCharsets.UTF_8);
            if(frame.equals("service file\r\n")){reply("OK\r\n");return;}
            if(frame.startsWith("o:")) {
                check(frame.matches("o:\"[^\"]+\":9880(?::(?:4000|a000))?\\r\\n"),"unsafe open "+frame);
                current=frame.substring(3,frame.indexOf('"',3));directory=frame.contains(":4000");statPass=0;opens++;
                if(current.equals("/bin")||current.equals("/lib")||current.equals("/lib64")||current.startsWith("/bin/")){
                    if(current.endsWith("link")){reply("e:Too many levels of symbolic links\r\n");return;}
                    reply("o:0\r\n");return;
                }
                reply("e:Permission denied\r\n");return;
            }
            if(frame.equals("s:0\r\n")) {
                int mode=current.equals("/bin")||current.equals("/lib")||current.equals("/lib64")?0x41ed:current.endsWith("device")?0x21b6:0x81a4;
                long size=current.endsWith("large")?20L*1024*1024:current.endsWith("changed")?3:0;
                long inode=current.equals("/lib")||current.equals("/lib64")?99:Math.abs(current.hashCode());
                reply("o:"+Long.toHexString(inode)+":"+Long.toHexString(size)+":10:0:0:0:"
                        +(current.endsWith("changed")&&++statPass>1?"2":"1")+":0:0:"+Integer.toHexString(mode)+":1:0:0:0:0\r\n");return;
            }
            if(frame.equals("c:0\r\n")){reply("o\r\n");return;}
            check(frame.startsWith("r:0:"),"unexpected mutation "+frame);
            String[] parts=frame.trim().split(":");long offset=Long.parseLong(parts[2],16);int count=Integer.parseInt(parts[3],16);
            if(parts[4].equals("1")) {
                check(directory,"directory page on regular descriptor");
                byte[] names=(offset==0&&current.equals("/bin")?".\r\n..\r\nlarge\r\nchanged\r\nempty\r\ndevice\r\nlink\r\nx/../../dev/mem\r\nquote\"\r\n":"").getBytes(StandardCharsets.UTF_8);
                payload("o:0:"+Integer.toHexString(names.length)+":0\r\n",names);return;
            }
            check(!directory&&!current.endsWith("device")&&!current.endsWith("link"),"special payload read");
            if(fail&&current.endsWith("large")&&offset>=4096)throw new IOException("synthetic lost stream");
            byte[] data=new byte[count];for(int i=0;i<count;i++)data[i]=(byte)((offset+i)%251);
            readBytes+=count;payload("o:"+Integer.toHexString(count)+":0:0\r\n",data);
        }
        void payload(String header,byte[] data)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(header.getBytes(StandardCharsets.UTF_8));b.write(data);queued=b.toByteArray();pos=0;}
    }
    public static void main(String[] args)throws Exception {
        File directory=new File(args[0]);
        for(boolean fail:new boolean[]{false,true}) {
            Peer peer=new Peer();peer.fail=fail;
            SystemFileDump.Shell shell=command->new dezz.status.widget.adb.AdbShellResult.Result("0",0,false);
            SystemFileDump.Report report=VehicleEvidenceArchive.collect(directory,shell,text->{},"test",r->new QnxFileReader(peer).collectTree(r));
            try(ZipFile zip=new ZipFile(report.file)) {
                check(!report.complete,"missing roots cannot be complete");
                String manifest=new String(zip.getInputStream(zip.getEntry("manifest.tsv")).readAllBytes(),StandardCharsets.UTF_8);
                for(String row:manifest.split("\n")) {
                    String[] f=row.split("\t");if(f.length!=4||f[0].equals("entry_tsv_escaped"))continue;
                    ZipEntry entry=zip.getEntry(f[0]);check(entry!=null&&entry.getSize()==Long.parseLong(f[2]),"manifest size");
                    java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
                    try(InputStream in=zip.getInputStream(entry)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
                    StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format("%02x",b&255));check(hex.toString().equals(f[3]),"manifest hash");
                }
                String coverage=new String(zip.getInputStream(zip.getEntry("qnx/metadata/full-tree.tsv")).readAllBytes(),StandardCharsets.UTF_8);
                if(fail){check(coverage.contains("traversal_finished=false"),"interruption visibility");check(zip.getEntry("qnx/tree/bin/large").getSize()==4096,"keep validated partial bytes");}
                else {
                    check(coverage.contains("traversal_finished=true")&&coverage.contains("excluded_special")&&coverage.contains("\t/lib64\t"),"coverage records");
                    check(zip.getEntry("qnx/tree/bin/large").getSize()==20L*1024*1024,"full file");
                    check(zip.getEntry("qnx/tree/bin/empty").getSize()==0,"empty regular file");
                    check(manifest.contains("changed_during_read"),"changed file preserved");
                    check(peer.readBytes==40L*1024*1024+6,"exact double read");
                }
                check(zip.getEntry("qnx/tree/bin/device")==null&&zip.getEntry("qnx/tree/bin/link")==null,"excluded payloads");
            }
        }
        check(!QnxFileReader.fullPath("/bin/../dev/mem")&&!QnxFileReader.fullPath("/bin//x")&&!QnxFileReader.fullPath("/proc/123/as"),"scope");
        File[] leftovers=directory.listFiles(f->f.getName().endsWith(".part"));check(leftovers!=null&&leftovers.length==0,"scratch cleanup");
        System.out.println("PASS full QNX streaming, hashes, boundaries, aliases, changed/empty/special files and partial ZIP");
    }
}
