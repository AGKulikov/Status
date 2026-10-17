/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Production parser against an inert peer modelling the observed native lstat/readlink branch. */
public final class QnxDeltaReplay {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static final class Peer implements QnxFileReader.Wire {
        byte[] queued=new byte[]{'Q','C','O','N','N',13,10,(byte)255,(byte)253,34};int pos;
        String current;boolean metadata,directory,badLink;boolean broken;int reads,knownReads;
        final Map<String,String> links=new LinkedHashMap<>();
        final Map<String,String> files=new LinkedHashMap<>();
        final Map<String,String> dirs=new LinkedHashMap<>();
        Peer() {
            dirs.put("/lib64","old\r\nnew\r\nsizechanged\r\nalias\r\nloop\r\nescape\r\ndevice\r\ndenied\r\n");
            dirs.put("/usr","lib\r\n");dirs.put("/var","log\r\n");dirs.put("/var/log","live.log\r\n");
            links.put("/lib","lib64");links.put("/usr/lib","../lib64");links.put("/lib64/alias","new");
            links.put("/lib64/loop","loop");links.put("/lib64/escape","/dev/mem");
            files.put("/lib64/old","old");files.put("/lib64/new","new bytes");files.put("/lib64/sizechanged","bigger");
            files.put("/var/log/live.log","old");
        }
        void reply(String s){queued=s.getBytes(StandardCharsets.UTF_8);pos=0;}
        void payload(byte[] b)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(("o:"+Integer.toHexString(b.length)+":0:0\r\n").getBytes(StandardCharsets.UTF_8));o.write(b);queued=o.toByteArray();pos=0;}
        public byte[] read(){if(pos==queued.length)return null;int end=Math.min(queued.length,pos+7);byte[] out=Arrays.copyOfRange(queued,pos,end);pos=end;return out;}
        public void write(byte[] b)throws Exception {
            check(!broken,"no command after malformed framing");check(pos==queued.length,"payload drained");
            String command=new String(b,StandardCharsets.UTF_8);
            if(command.equals("service file\r\n")){reply("OK\r\n");return;}
            if(command.startsWith("o:")) {
                check(command.matches("o:\"[^\"]+\":9880(?::(?:4000|a000))?\\r\\n"),"no unsafe flags");
                current=command.substring(3,command.indexOf('"',3));metadata=command.endsWith(":a000\r\n");directory=command.endsWith(":4000\r\n");
                check(!current.startsWith("/dev"),"never open link escape");
                if(links.containsKey(current)&&!metadata){reply("e:Too many levels of symbolic links or prefixes\r\n");return;}
                if(!links.containsKey(current)&&!files.containsKey(current)&&!dirs.containsKey(current)&&!current.equals("/lib64/device")){
                    check(!metadata||!current.endsWith("denied"),"no fallback after permission denial");reply("e:Permission denied\r\n");return;
                }
                reply("o:0\r\n");return;
            }
            if(command.equals("s:0\r\n")) {
                int mode=dirs.containsKey(current)?0x41ed:links.containsKey(current)?0xa1ff:current.endsWith("device")?0x21ff:0x81a4;
                long size=links.containsKey(current)?links.get(current).getBytes(StandardCharsets.UTF_8).length:files.getOrDefault(current,"").length();
                reply("o:7:"+Long.toHexString(size)+":9:0:0:0:1:0:1:"+Integer.toHexString(mode)+":1:0:0:0:0\r\n");return;
            }
            if(command.equals("c:0\r\n")){reply("o\r\n");return;}
            check(command.startsWith("r:0:"),"only read protocol");String[] f=command.trim().split(":");int offset=Integer.parseInt(f[2],16),count=Integer.parseInt(f[3],16);
            if(directory){check(f[4].equals("1"),"directory mode");byte[] data=(offset==0?dirs.get(current):"").getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(("o:0:"+Integer.toHexString(data.length)+":0\r\n").getBytes());o.write(data);queued=o.toByteArray();pos=0;return;}
            if(metadata) {
                check(links.containsKey(current)&&offset==0&&count==2048,"readlink only on symlink handle");
                if(badLink){reply("o:9000:0:0\r\n");broken=true;return;}
                payload(links.get(current).getBytes(StandardCharsets.UTF_8));return;
            }
            check(files.containsKey(current),"no special content");reads++;if(current.endsWith("/old"))knownReads++;
            payload(files.get(current).substring(offset,Math.min(offset+count,files.get(current).length())).getBytes(StandardCharsets.UTF_8));
        }
    }
    static QnxExclusions index()throws Exception {
        StringBuilder rows=new StringBuilder();
        for(String p:new String[]{"/lib64/old","/lib64/sizechanged","/var/log/live.log"})rows.append(QnxExclusions.pathHash(p)).append("\t3\t").append("a".repeat(64)).append("\tboth\n");
        return QnxExclusions.read(new ByteArrayInputStream(rows.toString().getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args)throws Exception {
        for(String ending:new String[]{"\n","\r\n"}) {
            Peer refused=new Peer();refused.reply("nc: connect: Connection refused"+ending);
            try {
                new QnxFileReader(refused).collectSingle("/bin/screen",new QnxFileReader.Receiver(){public void file(String p,String s,byte[] b){throw new AssertionError();}});
                throw new AssertionError("refusal accepted");
            } catch(QnxFileReader.Failure expected) {
                check(expected.report.contains("qnx_transport_unavailable")&&expected.report.contains("Connection refused"),"transport class and original evidence");
            }
        }
        QnxExclusions index=index();
        check(index.previous("/lib64/old",3)!=null&&index.previous("/lib64/old",4)==null,"size change resampled");
        check(index.previous("/var/log/live.log",3)==null,"live logs always refreshed");
        check(QnxExclusions.load().previous("/bin/screen",382788)!=null,"actual two-archive index packaged");
        for(boolean malformed:new boolean[]{false,true}) {
            Peer peer=new Peer();peer.badLink=malformed;Map<String,String> output=new HashMap<>();
            QnxFileReader.Receiver receiver=new QnxFileReader.Receiver() {
                public void file(String p,String s,byte[] b){throw new AssertionError();}
                public void artifact(String name,String status,File file)throws Exception{check(output.put(name,Files.readString(file.toPath()))==null,"no repeated payload");}
                public String previousFile(String p,long size){return index.previous(p,size);}
            };
            boolean failed=false;try{new QnxFileReader(peer).collectTree(receiver);}catch(QnxFileReader.Failure e){failed=true;}
            check(failed==malformed,"malformed stream terminates");
            String coverage=output.get("metadata/full-tree.tsv");
            if(!malformed) {
                check(peer.knownReads==0,"known payload not requested");
                check(output.get("tree/lib64/new").equals("new bytes"),"missing symlink target collected once");
                check(output.get("tree/lib64/sizechanged").equals("bigger")&&output.containsKey("tree/var/log/live.log"),"changed and live included");
                check(!output.containsKey("tree/lib64/alias")&&!output.containsKey("tree/lib/old"),"aliases never duplicate payloads");
                check(coverage.contains("excluded_previous_dump")&&coverage.contains("link_target_read")&&coverage.contains("qnx_link_cycle")&&coverage.contains("qnx_link_outside_selected_roots"),"explicit skip/link/error audit");
            } else check(coverage.contains("traversal_finished=false"),"failure report preserved");
        }
        System.out.println("PASS missing-file collection, exclusions, sizes/live data, native link protocol, cycles, scope and malformed stream");
    }
}
