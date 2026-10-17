/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Inert file-service peer: no socket, command execution or proprietary fixture. */
public final class QnxInventoryReplay {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static class Wire implements QnxFileReader.Wire {
        byte[] queued;int position;String current="";int opens;boolean disconnect,badFrame;
        final Set<String> paths=new HashSet<>();
        Wire() { queued=new byte[]{'Q','C','O','N','N',13,10,(byte)255,(byte)253,34}; }
        void reply(String line) { queued=line.getBytes(StandardCharsets.UTF_8);position=0; }
        void payload(String header,byte[] body) throws IOException {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();buffer.write(header.getBytes(StandardCharsets.UTF_8));buffer.write(body);
            queued=buffer.toByteArray();position=0;
        }
        public byte[] read() {
            if(position==queued.length)return null;
            int end=Math.min(position+997,queued.length);byte[] out=Arrays.copyOfRange(queued,position,end);position=end;return out;
        }
        public void write(byte[] bytes)throws Exception {
            check(position==queued.length,"undrained payload");
            String frame=new String(bytes,StandardCharsets.UTF_8);
            if(frame.equals("service file\r\n")){reply("OK\r\n");return;}
            if(frame.startsWith("o:")) {
                check(frame.endsWith("\":0:4000\r\n"),"inventory must use directory-only open");
                current=frame.substring(3,frame.length()-10);opens++;paths.add(current);
                if(current.equals("/denied")) {
                    if(disconnect)throw new IOException("synthetic disconnect");
                    reply("e:permission denied\r\n");return;
                }
                if(current.equals("/opaque")){reply("e:20\r\n");return;}
                boolean directory=current.equals("/")||current.equals("/alias")||current.equals("/.hidden")||current.equals("/русский")
                        ||current.equals("/repeated")||current.equals("/statdenied")||current.startsWith("/zero")
                        ||current.equals("/deep")||current.startsWith("/deep/d");
                if(current.endsWith("/leaf")||current.endsWith("/bottom"))directory=false;
                reply(directory?"o:0\r\n":"e:ENOTDIR\r\n");return;
            }
            if(frame.equals("s:0\r\n")) {
                if(current.equals("/statdenied")){reply("e:permission denied\r\n");return;}
                int inode=current.equals("/")||current.equals("/alias")?1:current.startsWith("/zero")?0:100+paths.size();
                reply("o:"+Integer.toHexString(inode)+":0:9:0:0:0:0:0:0:41ed:1:0:0:0:0\r\n");return;
            }
            if(frame.equals("c:0\r\n")){reply("o\r\n");return;}
            if(frame.startsWith("r:0:")) {
                String[] fields=frame.trim().split(":");check(fields[4].equals("1"),"inventory read a file");
                int page=Integer.parseInt(fields[2],16);String listing="";
                if(badFrame&&current.equals("/")&&page==1){reply("o:0:1000000:0\r\n");return;}
                if(current.equals("/")) {
                    if(page==0)listing=".\r\n..\r\n.hidden\r\nрусский\r\nalias\r\ndenied\r\nopaque\r\ndeep\r\nrepeated\r\nzero\r\nstatdenied\r\nquote\"name\r\nback\\name\r\ntab\tname\r\n.hidden\r\n../escape\r\n";
                    else if(page<=300)listing="file"+page+"\r\n";
                } else if(current.equals("/repeated"))listing="leaf\r\n";
                else if(page==0) {
                    if(current.equals("/.hidden")||current.equals("/русский"))listing="leaf\r\n";
                    else if(current.equals("/statdenied"))listing="available-name\r\n";
                    else if(current.startsWith("/zero"))listing="again\r\n";
                    else if(current.startsWith("/deep"))listing=current.split("/").length<152?"d\r\n":"bottom\r\n";
                }
                byte[] data=listing.getBytes(StandardCharsets.UTF_8);payload("o:0:"+Integer.toHexString(data.length)+":0\r\n",data);return;
            }
            throw new AssertionError("forbidden frame "+frame);
        }
    }
    static class Capture implements QnxFileReader.Receiver {
        final File directory;final Map<String,String> output=new HashMap<>(),status=new HashMap<>();
        Capture(File directory){this.directory=directory;}
        public File temporaryFile()throws IOException { return File.createTempFile("qnx-test-", ".part",directory); }
        public void file(String p,String s,byte[] b){throw new AssertionError("unexpected file content");}
        public void artifact(String name,String state,File file)throws Exception {
            check(output.put(name,Files.readString(file.toPath()))==null,"duplicate artifact");status.put(name,state);
        }
    }
    static void inventory(File directory)throws Exception {
        for(int mode=0;mode<4;mode++) {
            Wire wire=new Wire();wire.disconnect=mode==1;wire.badFrame=mode==2;
            Capture capture=new Capture(directory);boolean failed=false;
            if(mode==3)Thread.currentThread().interrupt();
            try { new QnxFileReader(wire).collectInventory(capture); }
            catch(QnxFileReader.Failure error) { failed=true; }
            finally { Thread.interrupted(); }
            check(failed==(mode!=0),"failure state "+mode);
            check(capture.output.size()==2,"both outputs survive failure");
            String list=capture.output.get("metadata/filesystem-paths.txt"),report=capture.output.get("metadata/filesystem-inventory.tsv");
            check(capture.status.values().stream().allMatch("partial_or_unavailable"::equals),"no false complete");
            check(report.contains("traversal_finished="+(mode==0)),"explicit finish state");
            if(mode==0) {
                Set<String> listed=new HashSet<>(Arrays.asList(list.split("\n")));
                check(listed.size()==list.split("\n").length,"no duplicate paths");
                check(listed.contains("/file300")&&listed.contains("/.hidden/leaf")&&listed.contains("/русский/leaf"),"broad, hidden, UTF-8");
                check(listed.stream().anyMatch(p->p.endsWith("/bottom")&&p.length()>300),"deep tree reached");
                check(report.contains("alias_already_enumerated\t/"),"cycle identity");
                check(report.contains("possible_cycle_identity_unknown"),"unknown inode cycle");
                check(listed.contains("/statdenied/available-name"),"stat denial must not suppress directory names");
                check(report.contains("qnx_directory_repeated_page"),"non-progress guard");
                check(report.contains("probe\t/opaque\tunknown\tunknown"),"numeric error is opaque");
                check(report.contains("probe\t/file300\tnon_directory\tunknown"),"ENOTDIR is not regular-file evidence");
                check(report.contains("unrepresentable_path")&&report.contains("invalid_basename"),"unsafe names recorded");
                check(!wire.paths.contains("/quote\"name")&&!wire.paths.contains("/back\\name")&&!wire.paths.contains("/tab\tname"),"unsafe open");
            } else if(mode==1)check(report.contains("not_attempted_after_failure")&&list.contains("/file300\n"),"pending discovery preserved");
            check(directory.listFiles((d,n)->n.endsWith(".part")).length==0,"temporary files leaked");
        }
    }
    static final byte[] HEADER=QnxFileReaderReplay.MAP_HEADER.getBytes(StandardCharsets.UTF_8);
    static final byte[] ROW=QnxFileReaderReplay.MAP_ROW.getBytes(StandardCharsets.UTF_8);
    static class Maps extends Wire {
        int pass;long fullSize;long wideOffset;
        final Set<Integer> mapped=new HashSet<>();
        public void write(byte[] bytes)throws Exception {
            check(position==queued.length,"undrained maps");String frame=new String(bytes,StandardCharsets.US_ASCII);
            if(frame.equals("service file\r\n")){reply("OK\r\n");return;}
            if(frame.startsWith("o:")) {
                current=frame.split("\"")[1];
                if(current.endsWith("mappings")) { int pid=Integer.parseInt(current.split("/")[2]);mapped.add(pid);pass=(pass+1)%2;
                    fullSize=HEADER.length+(long)ROW.length*(pid==1?90000:3000);wideOffset=0; }
                reply("o:0\r\n");return;
            }
            if(frame.equals("c:0\r\n")){reply("o\r\n");return;}
            String[] fields=frame.trim().split(":");check(fields[0].equals("r"),"unexpected maps frame");
            long offset=Long.parseLong(fields[2],16);int count=Integer.parseInt(fields[3],16);byte[] data;
            if(current.equals("/proc")) {
                StringBuilder listing=new StringBuilder();if(offset==0)for(int pid=1;pid<=300;pid++)listing.append(pid).append("\r\n");
                data=listing.toString().getBytes(StandardCharsets.US_ASCII);payload("o:0:"+Integer.toHexString(data.length)+":0\r\n",data);return;
            }
            if(current.endsWith("exefile")) {
                int pid=Integer.parseInt(current.split("/")[2]);byte[] raw=(pid<=10?"/bin/screen\n":"/bin/unrelated\n").getBytes(StandardCharsets.US_ASCII);
                data=Arrays.copyOfRange(raw,(int)Math.min(offset,raw.length),(int)Math.min(offset+count,raw.length));
            } else if(pass==1) {
                data=new byte[(int)Math.min(count,Math.max(0,262145-offset))];Arrays.fill(data,(byte)'X');
            } else {
                check(offset==wideOffset,"wide offset must be monotonic");
                data=new byte[(int)Math.min(count,Math.max(0,fullSize-offset))];
                for(int i=0;i<data.length;i++)data[i]=offset+i<HEADER.length?HEADER[(int)offset+i]:ROW[(int)((offset+i-HEADER.length)%ROW.length)];
                wideOffset+=data.length;
            }
            payload("o:"+Integer.toHexString(data.length)+"\r\n",data);
        }
    }
    static void mappings(File directory)throws Exception {
        Maps wire=new Maps();Map<String,Long> sizes=new HashMap<>();
        new QnxFileReader(wire).collectProcesses(new Capture(directory) {
            public void artifact(String name,String state,File file)throws Exception {
                if(name.startsWith("mappings/")) {
                    check(state.equals("read"),"full mapping valid through EOF");
                    check(QnxFileReader.validMappings(file),"stream validation");sizes.put(name,file.length());
                    if(name.equals("mappings/1-wide.txt")) {
                        MessageDigest expected=MessageDigest.getInstance("SHA-256"),actual=MessageDigest.getInstance("SHA-256");
                        expected.update(HEADER);for(int i=0;i<90000;i++)expected.update(ROW);
                        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)actual.update(buffer,0,n);}
                        check(Arrays.equals(expected.digest(),actual.digest()),"full file SHA mismatch");
                    }
                } else {
                    String content=Files.readString(file.toPath());
                    if(name.endsWith("processes.tsv"))check(content.contains("300\tread\t/bin/unrelated"),"PID limit survived");
                }
            }
        });
        check(wire.mapped.size()==10&&sizes.size()==10,"process cap survived");
        check(sizes.get("mappings/1-wide.txt")>8*1024*1024,"test must exceed proposed 8 MiB cap");
        check(directory.listFiles((d,n)->n.endsWith(".part")).length==0,"maps spool leaked");
    }
    public static void main(String[] args)throws Exception {
        File directory=new File(args[0]);inventory(directory);mappings(directory);
        System.out.println("PASS full QNX inventory, disk queue, hidden/UTF8/deep/broad, aliases/denials/cancel, 300 PIDs, 10 full mappings, >8 MiB exact SHA; inert peer only");
    }
}
