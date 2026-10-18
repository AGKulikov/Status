/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import dezz.status.widget.adb.AdbShellResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Passive Android evidence. A framework screenshot is NOT a proof of final HWC/DIM output. */
public final class WingsEvidenceArchive {
    private static final int LIMIT=1024*1024, IMAGE_LIMIT=8*1024*1024;
    private static final long DEADLINE_NS=170_000_000_000L;
    private static final String CLOCK="date +%s; cat /proc/uptime; cat /proc/sys/kernel/random/boot_id";
    private final SystemFileDump.Shell shell;
    private final SystemFileDump.Progress progress;
    private final ZipOutputStream zip;
    private final long started=System.nanoTime();
    private final StringBuilder manifest=new StringBuilder("path\tstatus\tstart_wall_ms\tend_wall_ms\tstart_elapsed_ms\tend_elapsed_ms\texit\ttruncated\tbytes\tsha256\n");
    private boolean broken, complete=true;
    private int collected, requested;

    private WingsEvidenceArchive(SystemFileDump.Shell shell,SystemFileDump.Progress progress,ZipOutputStream zip) {
        this.shell=shell;this.progress=progress;this.zip=zip;
    }
    private long elapsed() { return (System.nanoTime()-started)/1_000_000L; }
    private boolean stopped() {
        return broken || Thread.currentThread().isInterrupted() || System.nanoTime()-started>=DEADLINE_NS;
    }
    private AdbShellResult.Result run(String command) throws Exception {
        if(stopped()) throw new InterruptedIOException("collection_stopped");
        long remaining=(DEADLINE_NS-(System.nanoTime()-started))/1_000_000L;
        try {
            AdbShellResult.Result r=shell.commandTimed(command,LIMIT,Math.max(1,Math.min(15_000,remaining)));
            if(r.exitCode==null) broken=true;
            return r;
        } catch(Exception e) { broken=true;throw e; }
    }
    private void record(String name,String status,byte[] bytes,long wall,long mono,Integer exit,boolean truncated) throws Exception {
        requested++;
        if(status.equals("collected")) collected++; else complete=false;
        SystemFileDump.put(zip,name,bytes);
        manifest.append(name).append('\t').append(status).append('\t').append(wall).append('\t')
                .append(System.currentTimeMillis()).append('\t').append(mono).append('\t').append(elapsed())
                .append('\t').append(exit==null?"unknown":exit).append('\t').append(truncated).append('\t')
                .append(bytes.length).append('\t').append(SystemFileDump.digest(bytes)).append('\n');
    }
    private String text(String name,String command) throws Exception {
        long wall=System.currentTimeMillis(),mono=elapsed();
        if(stopped()) { record(name,"not_attempted",new byte[0],wall,mono,null,false);return null; }
        progress.update("Диагностика крыльев: "+name);
        AdbShellResult.Result r;
        try { r=run(command); }
        catch(AdbShellResult.Failure e) { r=e.partial; }
        catch(Exception e) { record(name,"transport_failed",new byte[0],wall,mono,null,false);return null; }
        boolean ok=r.success()&&!r.truncated;
        record(name,ok?"collected":r.truncated?"truncated":"command_failed",r.output.getBytes(StandardCharsets.UTF_8),wall,mono,r.exitCode,r.truncated);
        return ok?r.output:null;
    }
    /** API28 uses built-in IDs; neither logical displayId nor layerStack is substituted. */
    static List<Integer> captureIds(String sdk,String help,String display) {
        List<Integer> out=new ArrayList<>();
        if(sdk==null||!sdk.trim().equals("28")||help==null||!help.contains("-d")||display==null)return out;
        Matcher m=Pattern.compile("(?m)^\\s*mBuiltInDisplayId=([0-9]{1,3})\\s*$").matcher(display);
        while(m.find()) {
            int id=Integer.parseInt(m.group(1));
            if(!out.contains(id))out.add(id);
            if(out.size()==4)break;
        }
        return out;
    }
    /** Validate complete PNG framing/CRC without allocating a decoded bitmap. */
    static String pngDimensions(byte[] bytes)throws IOException {
        byte[] magic={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<57||!Arrays.equals(magic,Arrays.copyOf(bytes,8)))throw new IOException("png_signature");
        int pos=8,width=0,height=0;boolean data=false,end=false;
        while(pos+12<=bytes.length) {
            int length=integer(bytes,pos);
            if(length<0||length>bytes.length-pos-12)throw new IOException("png_chunk_length");
            String type=new String(bytes,pos+4,4,StandardCharsets.US_ASCII);
            CRC32 crc=new CRC32();crc.update(bytes,pos+4,length+4);
            if(crc.getValue()!=Integer.toUnsignedLong(integer(bytes,pos+8+length)))throw new IOException("png_crc");
            if(pos==8) {
                if(!type.equals("IHDR")||length!=13)throw new IOException("png_ihdr");
                width=integer(bytes,pos+8);height=integer(bytes,pos+12);
                if(width<1||height<1||width>16384||height>16384)throw new IOException("png_dimensions");
            } else if(type.equals("IHDR"))throw new IOException("png_duplicate_ihdr");
            if(type.equals("IDAT"))data=true;
            pos+=length+12;
            if(type.equals("IEND")) { if(length!=0||pos!=bytes.length)throw new IOException("png_iend");end=true;break; }
        }
        if(!data||!end)throw new IOException("png_incomplete");
        return width+"x"+height;
    }
    private static int integer(byte[] b,int at) { return (b[at]&255)<<24|(b[at+1]&255)<<16|(b[at+2]&255)<<8|(b[at+3]&255); }
    private String checked(String command)throws Exception {
        return SystemFileDump.output(run(command));
    }
    private void capture(String key,Integer id)throws Exception {
        long wall=System.currentTimeMillis(),mono=elapsed();
        String dir="/data/local/tmp/natro-wings-"+UUID.randomUUID().toString();
        String path=dir+"/frame.png", q=AdbShellResult.quote(path);
        String status="not_attempted",dimensions="unknown",cleanup="not_needed";
        byte[] bytes=new byte[0];
        if(!stopped()) {
            progress.update("Диагностика крыльев: снимок "+key);
            try {
                cleanup="unconfirmed";
                // Unique private directory; only this generated PNG is removed. Bound remote disk use.
                checked("umask 077; mkdir "+AdbShellResult.quote(dir)+" && (ulimit -f 16384; screencap -p "
                        +(id==null?"":"-d "+id+" ")+q+")");
                String size=checked(SystemFileDump.probe(path));
                if(!size.matches("[0-9]{1,9}"))throw new IOException("invalid_image_size");
                int length=Integer.parseInt(size);
                if(length<57||length>IMAGE_LIMIT)throw new IOException("image_size_limit");
                String before=checked("sha256sum "+q);
                ByteArrayOutputStream data=new ByteArrayOutputStream(length);
                for(int offset=0;offset<length;offset+=SystemFileDump.CHUNK)
                    data.write(SystemFileDump.decode(checked(SystemFileDump.chunkCommand(path,offset)),Math.min(SystemFileDump.CHUNK,length-offset)));
                bytes=data.toByteArray();
                String sha=SystemFileDump.digest(bytes);
                String after=checked("sha256sum "+q);
                if(!before.equals(after)||!before.matches("(?s)"+sha+"\\s+"+Pattern.quote(path)))throw new IOException("image_hash");
                dimensions=pngDimensions(bytes);status="collected";
            } catch(Exception e) { bytes=new byte[0];status=broken?"transport_failed":"capture_failed"; }
            finally {
                if(!stopped()) {
                    try { checked("rm -f "+q+"; rmdir "+AdbShellResult.quote(dir));cleanup="removed"; }
                    catch(Exception e) { cleanup="unconfirmed";complete=false; }
                } else if(cleanup.equals("unconfirmed"))complete=false;
            }
        }
        record("images/"+key+(status.equals("collected")?".png":".unavailable"),status,bytes,wall,mono,null,false);
        SystemFileDump.put(zip,"images/"+key+".txt",("request="+(id==null?"screencap_default":"API28_built_in_"+id)
                +"\nlogical_display_mapping=requires_review_of_display_dump\nsize="+dimensions
                +"\npng_validation=framing_crc_only\nfinal_HWC_or_DIM_output=unproven\nremote_temporary="+dir
                +"\nremote_cleanup="+cleanup+"\n").getBytes(StandardCharsets.UTF_8));
    }
    public static SystemFileDump.Report collect(File directory,SystemFileDump.Shell shell,SystemFileDump.Progress progress,
                                                String version,String state)throws Exception {
        if(!Arrays.asList("normal","visible").contains(state))throw new IOException("capture_state");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("export_directory");
        File partial=File.createTempFile("Natro-wings-",".part",directory);
        File target=new File(directory,partial.getName().replace(".part",".zip"));
        boolean published=false;
        WingsEvidenceArchive c;
        try {
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
                c=new WingsEvidenceArchive(shell,progress,zip);
                SystemFileDump.put(zip,"session.txt",("schema=1\nversion="+version+"\nuser_state="+state+"\nsession="+target.getName()
                        +"\nstart_wall_ms="+System.currentTimeMillis()+"\nsequence=non_atomic_keep_layout_stable"
                        +"\nphoto_required=true\nowner_attribution=OPEN\nmode_changes=false\nroot_escalation=false"
                        +"\ncapture_limit=4_built_in_or_default\nlogical_display_2_is_not_screencap_id_2"
                        +"\nnegative_framework_image_does_not_exclude_HWC_overlay_or_crop\n").getBytes(StandardCharsets.UTF_8));
                c.text("before/clock.txt",CLOCK);
                c.text("firmware.txt","getprop ro.build.fingerprint; getprop ro.vendor.build.fingerprint; id");
                String sdk=c.text("sdk.txt","getprop ro.build.version.sdk");
                c.text("before/nav-mode.txt","settings get global NaviMode");
                String display=c.text("before/display.txt","dumpsys display");
                c.text("before/layers.txt","dumpsys SurfaceFlinger --list");
                c.text("before/composition.txt","dumpsys SurfaceFlinger");
                c.text("before/windows.txt","dumpsys window windows");
                c.text("processes.txt","ps -A -o USER,UID,PID,PPID,NAME,ARGS");
                c.text("packages.txt","pm list packages -U -f");
                c.text("activities.txt","dumpsys activity activities");
                // Some OEM help commands exit nonzero. Preserve output, but do not infer IDs from it.
                String help=c.text("screencap-help.txt","screencap -h");
                List<Integer> ids=captureIds(sdk,help,display);
                if(ids.isEmpty())c.capture("default-unmapped",null);
                else for(int id:ids)c.capture("builtin-"+id,id);
                c.text("after/nav-mode.txt","settings get global NaviMode");
                c.text("after/display.txt","dumpsys display");
                c.text("after/composition.txt","dumpsys SurfaceFlinger");
                c.text("after/clock.txt",CLOCK);
                SystemFileDump.put(zip,"manifest.tsv",c.manifest.toString().getBytes(StandardCharsets.UTF_8));
            }
            if(!partial.renameTo(target))throw new IOException("archive_publish");
            published=true;
            File[] old=directory.listFiles(f->f.getName().matches("Natro-wings-[0-9]+\\.zip")&&f.isFile());
            if(old!=null) {
                Arrays.sort(old,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
                int kept=0;
                for(File f:old)if(!f.equals(target)&&kept++>=1&&f.getCanonicalFile().equals(f.getAbsoluteFile()))f.delete();
            }
            return new SystemFileDump.Report(target,c.collected,c.requested,c.complete,
                    "Получено "+c.collected+"/"+c.requested+" записей. Фото приборки передайте вместе с ZIP.");
        } finally { if(!published)partial.delete(); }
    }
}
