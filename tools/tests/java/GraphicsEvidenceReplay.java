package dezz.status.widget.diagnostics;

import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class GraphicsEvidenceReplay {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static AdbShellResult.Result result(String text){return new AdbShellResult.Result(text,0,false);}
    static class Peer implements SystemFileDump.Shell {
        final int mode;final byte[] large=new byte[GraphicsEvidence.RANGE_LIMIT+37];
        int ranges,afterStats;boolean disconnected;
        final List<String> commands=new ArrayList<>();
        Peer(int mode){this.mode=mode;for(int n=0;n<large.length;n++)large[n]=(byte)n;}
        public AdbShellResult.Result commandBounded(String command,int limit)throws Exception {
            if(command.equals("dumpsys SurfaceFlinger")) {
                check(limit==1024*1024,"full composition capture limit");
                if(!disconnected)return result("X".repeat(218124));
            }
            return command(command);
        }
        public AdbShellResult.Result command(String command)throws Exception {
            check(!disconnected,"command after broken stream");commands.add(command);
            check(!command.contains("su ")&&!command.contains("chmod")&&!command.contains("slog2info")
                    &&!command.contains("SetLock")&&!command.contains("reboot")&&!command.contains("rm "),"write/escalation");
            if(command.equals("date +%s; cat /proc/uptime"))return result("1790000000\n123.00 456.00");
            if(command.startsWith("dumpsys "))return result("display metadata");
            if(command.contains("NATRO_DLT_LIST")) {
                if(mode==1)return new AdbShellResult.Result("!permission_denied",3,false);
                if(command.contains("'/mnt/userdata/DLT/sub'"))return result("NATRO_DLT_LIST\nf\told.dlt\t2 3 10\nNATRO_DLT_END");
                return result("NATRO_DLT_LIST\nd\tsub\t-\nf\tfresh.dlt\t1 "+large.length+" 100\n"
                        +"f\ta.log\t2 3 99\nf\tb.txt\t2 3 98\nf\tc.dlt\t2 3 97\n"
                        +"f\tunknown.bin\t2 3 96\ns\tlink.dlt\t-\nf\t../../outside.dlt\t1 1 1\nNATRO_DLT_END");
            }
            Matcher path=Pattern.compile("'(/mnt/userdata/DLT/[^']*)'").matcher(command);
            check(path.find(),"unexpected command "+command);String p=path.group(1);
            check(GraphicsEvidence.allowed(p),"bad path");byte[] body=p.endsWith("fresh.dlt")?large:new byte[]{0,1,(byte)255};
            if(command.contains("dd if=")) {
                ranges++;
                if(mode==2&&ranges==2){disconnected=true;throw new IOException("socket closed");}
                if(mode==4&&ranges==2)return result("invalid base64");
                int skip=Integer.parseInt(command.split(" skip=")[1].split(" ")[0]);
                int length=Integer.parseInt(command.split("head -c ")[1].split(" ")[0]);
                return result(Base64.getEncoder().encodeToString(Arrays.copyOfRange(body,skip*SystemFileDump.CHUNK,skip*SystemFileDump.CHUNK+length)));
            }
            if(command.contains("stat -c")) {
                if(p.endsWith("fresh.dlt")) {
                    afterStats++;
                    return result("1 "+(large.length+(mode==3&&afterStats>1?1:0))+" 100");
                }
                return result("2 3 90");
            }
            throw new AssertionError(command);
        }
    }
    public static void main(String[] args)throws Exception {
        for(int size:new int[]{218124,1024*1024+100}) {
            AdbShellResult capture=new AdbShellResult(1024*1024);
            Matcher token=Pattern.compile("NATRO_EXIT_[a-f0-9]+").matcher(capture.wrap("true"));
            check(token.find(),"framing token");
            capture.accept(("\n"+token.group()+"_BEGIN\n").getBytes(StandardCharsets.UTF_8));
            byte[] body=new byte[size];Arrays.fill(body,(byte)'X');capture.accept(body);
            capture.accept(("\n"+token.group()+":0\n").getBytes(StandardCharsets.UTF_8));
            AdbShellResult.Result framed=capture.finish();
            check(framed.success(),"large result exit lost");
            check(framed.truncated==(size>1024*1024),"large result truncation flag");
            if(size<1024*1024)check(framed.output.length()==size,"large result payload cut");
        }
        try {new AdbShellResult(1024*1024+1);throw new AssertionError("unbounded capture");}
        catch(IllegalArgumentException expected){}
        for(String path:new String[]{"/mnt/userdata/DLT/../secrets","/mnt/userdata/DLT/-x","/mnt/userdata/DLT/a/b/c",
                "/mnt/userdata/DLT/a;id","/mnt/userdata/DLT/.hidden","/etc/shadow","/mnt/userdata/DLT/a//b"}) {
            check(!GraphicsEvidence.allowed(path),path);
            try{GraphicsEvidence.rangeCommand(path,0,3);throw new AssertionError(path);}catch(IOException expected){}
        }
        for(int mode=0;mode<=4;mode++) {
            Peer peer=new Peer(mode);Map<String,byte[]> saved=new LinkedHashMap<>();Map<String,String> statuses=new HashMap<>();
            boolean complete=GraphicsEvidence.collect(peer,false,(name,status,bytes)->{
                check(!saved.containsKey(name),"duplicate output");saved.put(name,bytes);statuses.put(name,status);
            },s->{});
            check(!complete,"coverage exclusions must remain visible");
            check(saved.get("android/before-composition.txt").length==218124,"composition cut at old limit");
            check(saved.containsKey("android/before-windows.txt"),"window owners missing");
            String index=new String(saved.get("dlt/inventory.tsv"),StandardCharsets.UTF_8);
            if(mode==1) {
                check(index.contains("inventory_unavailable")&&!saved.containsKey("dlt/files/fresh.dlt.range"),"denial");continue;
            }
            check(index.contains("invalid_entry")&&index.contains("metadata_only_extension")&&index.contains("link_rejected"),"inventory reasons");
            if(mode==2||mode==4) {
                check(saved.get("dlt/files/fresh.dlt.range").length==32768,"prefix lost");
                check(index.contains(mode==2?"not_attempted_after_transport_failure":"partial_read_failed"),"partial reason");
                if(mode==4)check(saved.containsKey("dlt/files/a.log.range"),"later file lost after malformed data");
            } else {
                check(Arrays.equals(saved.get("dlt/files/fresh.dlt.range"),Arrays.copyOfRange(peer.large,32768,peer.large.length)),"tail mismatch");
                check(statuses.get("dlt/files/fresh.dlt.range").equals(mode==3?"changed_during_read":"collected_live_tail"),"live status");
                check(saved.keySet().stream().filter(k->k.startsWith("dlt/files/")).count()==4,"file cap");
                check(index.contains("not_selected_file_limit"),"unselected not recorded");
            }
        }
        // Execute exact shell syntax with only the fixed root substituted into a disposable fixture.
        Path root=Files.createDirectories(Path.of(args[0],"dlt"));
        byte[] raw=new byte[65539];for(int n=0;n<raw.length;n++)raw[n]=(byte)n;
        Files.write(root.resolve("valid.dlt"),raw);Files.write(root.resolve("bad name.dlt"),raw);
        Files.createSymbolicLink(root.resolve("link.dlt"),root.resolve("valid.dlt"));
        Process p=new ProcessBuilder("sh","-c",GraphicsEvidence.inventoryCommand(GraphicsEvidence.ROOT).replace(GraphicsEvidence.ROOT,root.toString())).start();
        String listed=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        check(p.waitFor()==0&&listed.contains("f\tvalid.dlt\t")&&listed.contains("s\tlink.dlt\t")&&listed.contains("!name_rejected"),"real shell list");
        String cmd=GraphicsEvidence.rangeCommand(GraphicsEvidence.ROOT+"/valid.dlt",32768,32768).replace(GraphicsEvidence.ROOT,root.toString());
        p=new ProcessBuilder("sh","-c",cmd).start();String encoded=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        check(p.waitFor()==0&&Arrays.equals(SystemFileDump.decode(encoded,32768),Arrays.copyOfRange(raw,32768,65536)),"real range");
        p=new ProcessBuilder("sh","-c",GraphicsEvidence.rangeCommand(GraphicsEvidence.ROOT+"/link.dlt",0,3).replace(GraphicsEvidence.ROOT,root.toString())).start();
        String denied=new String(p.getInputStream().readAllBytes());check(p.waitFor()==3&&denied.contains("link_rejected"),"link read");
        check(Arrays.equals(raw,Files.readAllBytes(root.resolve("valid.dlt"))),"source changed");
        System.out.println("PASS graphics inventories, DLT tail bytes, offsets, bounds, denial, rotation, prefix retention, path guards and real shell syntax");
    }
}
