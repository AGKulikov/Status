/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing Android graphics metadata and DLT ranges only. Never starts or reconfigures a logger. */
public final class GraphicsEvidence {
    static final String ROOT="/mnt/userdata/DLT";
    static final int DIRECTORY_LIMIT=8, ENTRY_LIMIT=128, FILE_LIMIT=4, RANGE_LIMIT=2*1024*1024;
    public interface Sink { void save(String name,String status,byte[] bytes) throws Exception; }
    private final SystemFileDump.Shell shell;
    private final Sink sink;
    private final SystemFileDump.Progress progress;
    private final boolean su;
    private boolean complete=true, broken;
    private final long started=System.nanoTime();
    private int entries;
    private final StringBuilder inventory=new StringBuilder("path\tstatus\tinode\tsize\tmtime\toffset\tbytes\n");
    private static final class Entry {
        final String path;final long inode,size,mtime;
        Entry(String path,long inode,long size,long mtime){this.path=path;this.inode=inode;this.size=size;this.mtime=mtime;}
    }
    private GraphicsEvidence(SystemFileDump.Shell shell,boolean su,Sink sink,SystemFileDump.Progress progress) {
        this.shell=shell;this.su=su;this.sink=sink;this.progress=progress;
    }
    static boolean allowed(String path) {
        if(path.equals(ROOT))return true;
        if(!path.startsWith(ROOT+"/"))return false;
        String[] parts=path.substring(ROOT.length()+1).split("/",-1);
        if(parts.length>2)return false;
        for(String part:parts)if(!part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))return false;
        return true;
    }
    static String guard(String path) throws IOException {
        if(!allowed(path))throw new IOException("dlt_path_not_allowed");
        // Check canonical path on every operation; never fall back to another root or su on denial.
        String q=AdbShellResult.quote(path);
        return "if [ -L "+q+" ]; then echo '!link_rejected'; exit 3; fi; "
                +"if [ ! -e "+q+" ]; then echo '!absent_or_inaccessible'; exit 3; fi; "
                +"if [ \"$(readlink -f "+q+")\" != "+q+" ]; then echo '!alias_rejected'; exit 3; fi; ";
    }
    static String inventoryCommand(String directory) throws IOException {
        return guard(directory)+"if [ ! -d "+AdbShellResult.quote(directory)+" ]; then echo '!not_directory'; exit 3; fi; "
                +"if [ ! -r "+AdbShellResult.quote(directory)+" ]; then echo '!permission_denied'; exit 3; fi; "
                +"echo NATRO_DLT_LIST; n=0; for p in "+AdbShellResult.quote(directory)+"/*; do "
                +"[ -e \"$p\" ] || [ -L \"$p\" ] || continue; n=$((n+1)); "
                +"if [ \"$n\" -gt 64 ]; then echo '!entry_limit'; break; fi; name=${p##*/}; "
                +"case \"$name\" in ''|.*|-*|*[!A-Za-z0-9._-]*) echo '!name_rejected'; continue;; esac; "
                +"if [ -L \"$p\" ]; then printf 's\\t%s\\t-\\n' \"$name\"; "
                +"elif [ -d \"$p\" ]; then printf 'd\\t%s\\t-\\n' \"$name\"; "
                +"elif [ -f \"$p\" ] && [ -r \"$p\" ]; then "
                +"v=$(stat -c '%i %s %Y' \"$p\") || { echo '!stat_failed'; continue; }; "
                +"printf 'f\\t%s\\t%s\\n' \"$name\" \"$v\"; "
                +"else printf 'x\\t%s\\t-\\n' \"$name\"; fi; done; echo NATRO_DLT_END";
    }
    static String rangeCommand(String path,long offset,int length) throws IOException {
        if(offset<0||offset%SystemFileDump.CHUNK!=0||length<1||length>SystemFileDump.CHUNK)
            throw new IOException("dlt_range_invalid");
        return guard(path)+"if [ ! -f "+AdbShellResult.quote(path)+" ] || [ ! -r "+AdbShellResult.quote(path)
                +" ]; then exit 3; fi; dd if="+AdbShellResult.quote(path)+" bs="+SystemFileDump.CHUNK
                +" skip="+(offset/SystemFileDump.CHUNK)+" count=1 2>/dev/null | head -c "+length+" | base64";
    }
    private AdbShellResult.Result command(String script) throws Exception {
        return command(script, AdbShellResult.OUTPUT_LIMIT);
    }
    private AdbShellResult.Result command(String script, int limit) throws Exception {
        if(System.nanoTime()-started>120_000_000_000L){broken=true;throw new IOException("graphics_stage_time_limit");}
        if(broken||Thread.currentThread().isInterrupted())throw new InterruptedIOException("evidence_session_stopped");
        try {
            AdbShellResult.Result r=shell.commandBounded(SystemFileDump.privileged(script,su), limit);
            if(r.exitCode==null)broken=true;
            return r;
        } catch(Exception error){broken=true;throw error;}
    }
    private void row(String path,String status,Entry e,long offset,int size) {
        inventory.append(path).append('\t').append(status).append('\t')
                .append(e==null?"-":e.inode).append('\t').append(e==null?"-":e.size).append('\t')
                .append(e==null?"-":e.mtime).append('\t').append(offset).append('\t').append(size).append('\n');
    }
    private void mark(String path,String status){complete=false;row(path,status,null,0,0);}
    private static Entry parseStat(String path,String text) throws IOException {
        if(!text.matches("[0-9]{1,18} [0-9]{1,12} [0-9]{1,12}"))throw new IOException("invalid_dlt_stat");
        String[] f=text.split(" ");return new Entry(path,Long.parseLong(f[0]),Long.parseLong(f[1]),Long.parseLong(f[2]));
    }
    private void list(String directory,boolean descend,List<String> directories,List<Entry> candidates) throws Exception {
        AdbShellResult.Result r=command(inventoryCommand(directory));
        if(!r.success()||r.truncated) {
            mark(directory,r.truncated?"inventory_truncated":"inventory_unavailable");
            inventory.append("# reply=").append(QnxFileReader.safe(r.output)).append('\n');return;
        }
        String text=r.output.trim();
        if(!text.startsWith("NATRO_DLT_LIST\n")||!text.endsWith("NATRO_DLT_END")) {
            mark(directory,"invalid_inventory");return;
        }
        for(String line:text.split("\n")) {
            if(line.equals("NATRO_DLT_LIST")||line.equals("NATRO_DLT_END"))continue;
            if(line.startsWith("!")){mark(directory,QnxFileReader.safe(line));continue;}
            if(++entries>ENTRY_LIMIT){mark(directory,"total_entry_limit");break;}
            String[] f=line.split("\t",-1);
            if(f.length!=3||!f[1].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {mark(directory,"invalid_entry");continue;}
            String path=directory+"/"+f[1];
            if(!allowed(path)){mark(directory,"path_rejected");continue;}
            if(f[0].equals("d")) {
                if(descend&&directories.size()<DIRECTORY_LIMIT) {directories.add(path);row(path,"directory_queued",null,0,0);}
                else mark(path,"directory_depth_or_count_limit");
            } else if(f[0].equals("f")) {
                try {
                    Entry entry=parseStat(path,f[2]);
                    if(f[1].toLowerCase(Locale.ROOT).matches(".*\\.(dlt|log|txt)")) {
                        candidates.add(entry);row(path,"eligible_log",entry,0,0);
                    }
                    else row(path,"metadata_only_extension",entry,0,0);
                } catch(IOException bad){mark(path,"invalid_stat");}
            } else mark(path,f[0].equals("s")?"link_rejected":"not_regular_or_unreadable");
        }
    }
    private void copy(Entry listed) throws Exception {
        Entry before=null;ByteArrayOutputStream data=new ByteArrayOutputStream();long offset=0;
        String status="read_failed";
        try {
            before=parseStat(listed.path,SystemFileDump.output(command(guard(listed.path)+"stat -c '%i %s %Y' "+AdbShellResult.quote(listed.path))));
            // Freeze a bounded, block-aligned tail range, not the continually growing whole log.
            long start=Math.max(0,before.size-RANGE_LIMIT);
            offset=((start+SystemFileDump.CHUNK-1)/SystemFileDump.CHUNK)*SystemFileDump.CHUNK;
            for(long pos=offset;pos<before.size;) {
                int n=(int)Math.min(SystemFileDump.CHUNK,before.size-pos);
                byte[] bytes=SystemFileDump.decode(SystemFileDump.output(command(rangeCommand(listed.path,pos,n))),n);
                data.write(bytes);pos+=n;
            }
            Entry after=parseStat(listed.path,SystemFileDump.output(command(guard(listed.path)+"stat -c '%i %s %Y' "+AdbShellResult.quote(listed.path))));
            row(listed.path,"stat_after",after,offset,data.size());
            boolean unchanged=before.inode==after.inode&&before.size==after.size&&before.mtime==after.mtime;
            status=unchanged?(offset==0?"collected_live_file":"collected_live_tail"):"changed_during_read";
            if(!unchanged)complete=false;
        } catch(Exception failure) {
            complete=false;status=broken?"partial_transport_failed":"partial_read_failed";
            inventory.append("# error=").append(QnxFileReader.safe(failure.getMessage())).append('\n');
        }
        row(listed.path,status,before==null?listed:before,offset,data.size());
        // Keep validated chunks even if rotation, loss of access or timeout interrupted the range.
        if(data.size()>0||status.equals("collected_live_file"))
            sink.save("dlt/files/"+listed.path.substring(ROOT.length()+1)+".range",status,data.toByteArray());
    }
    private void context(String phase) throws Exception {
        for(String[] item:new String[][]{
                {"clock","date +%s; cat /proc/uptime"},
                {"display","dumpsys display"},
                {"layers","dumpsys SurfaceFlinger --list"},
                {"composition","dumpsys SurfaceFlinger"},
                {"windows","dumpsys window windows"}}) {
            String status="collected_snapshot";byte[] bytes=new byte[0];
            try {
                AdbShellResult.Result r=command(item[1], 1024*1024);bytes=r.output.getBytes(StandardCharsets.UTF_8);
                if(!r.success())status="command_failed";
                else if(r.truncated||bytes.length>1024*1024)status="truncated_snapshot";
                if(bytes.length>1024*1024)bytes=Arrays.copyOf(bytes,1024*1024);
            } catch(Exception unavailable){status=broken?"not_collected_transport_failed":"not_collected";}
            if(!status.equals("collected_snapshot"))complete=false;
            sink.save("android/"+phase+"-"+item[0]+".txt",status,bytes);
        }
    }
    public static boolean collect(SystemFileDump.Shell shell,boolean su,Sink sink,SystemFileDump.Progress progress) throws Exception {
        GraphicsEvidence collector=new GraphicsEvidence(shell,su,sink,progress);
        collector.context("before");
        try {
            List<String> directories=new ArrayList<>();List<Entry> candidates=new ArrayList<>();
            progress.update("Графика: перечень существующих DLT-журналов…");
            collector.list(ROOT,true,directories,candidates);
            for(String dir:directories) {
                if(collector.broken||collector.entries>=ENTRY_LIMIT){collector.mark(dir,"not_attempted_limit_or_transport");continue;}
                collector.list(dir,false,directories,candidates);
            }
            candidates.sort((a,b)->{int order=Long.compare(b.mtime,a.mtime);return order!=0?order:a.path.compareTo(b.path);});
            Set<String> seen=new HashSet<>();int selected=0;
            for(Entry candidate:candidates) {
                if(!seen.add(candidate.path)){collector.mark(candidate.path,"duplicate_inventory_entry");continue;}
                if(collector.broken){collector.mark(candidate.path,"not_attempted_after_transport_failure");continue;}
                if(selected++>=FILE_LIMIT){collector.mark(candidate.path,"not_selected_file_limit");continue;}
                progress.update("DLT: "+candidate.path);
                collector.copy(candidate);
            }
            if(candidates.isEmpty())collector.mark(ROOT,"no_eligible_logs_observed");
        } catch(Exception unavailable) {
            collector.mark(ROOT,"inventory_or_transport_failed");
            collector.inventory.append("# error=").append(QnxFileReader.safe(unavailable.getMessage())).append('\n');
        }
        collector.context("after");
        collector.inventory.append("# non_hidden_entries_only; depth=2; max_directories=8; max_entries=128; newest_files=4; tail_bytes=2097152\n")
                .append("# Live ranges, not atomic; local SHA only. Same inode/size/mtime does not prove unchanged contents.\n");
        sink.save("dlt/inventory.tsv",collector.complete?"collected_snapshot":"partial_or_unavailable",
                collector.inventory.toString().getBytes(StandardCharsets.UTF_8));
        return collector.complete;
    }
}
