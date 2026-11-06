/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Streaming ZIP creation/extraction, isolated destination and traversal/bomb guards. */
public final class FileArchives {
    private static final int MAX_ENTRIES=100000;
    private static final long MAX_UNPACKED=32L*1024*1024*1024;
    private FileArchives(){}
    public static String create(FileBackend source,List<FileEntry> entries,FileBackend destination,
                                String folder,String name,File scratch,FileOperations.Control control)throws Exception {
        File archive=File.createTempFile("natro-zip-",".part",scratch);
        try {
            try(ZipOutputStream output=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
                Set<String> seen=new HashSet<>();
                for(FileEntry entry:entries)add(source,entry,entry.name,output,seen,0,control);
            }
            control.check();String target=FileOperations.available(destination,folder,name);
            try(InputStream input=new FileInputStream(archive)){destination.write(target,input,archive.length(),control);}
            return target;
        }finally{Files.deleteIfExists(archive.toPath());}
    }
    private static void add(FileBackend backend,FileEntry item,String name,ZipOutputStream output,Set<String> seen,int depth,FileOperations.Control control)throws Exception {
        control.item(item.name);
        if(depth>128||seen.size()>=MAX_ENTRIES||!seen.add(name))throw new IOException("Слишком большой или повторяющийся список файлов");
        FilePaths.normalize("/"+name);
        ZipEntry entry=new ZipEntry(name+(item.directory?"/":""));if(item.modified>0)entry.setTime(item.modified);output.putNextEntry(entry);
        if(!item.directory)try(InputStream input=backend.read(item.path)){FileOperations.pipe(input,output,control);}
        output.closeEntry();
        if(item.directory)for(FileEntry child:backend.list(item.path)) {
            if(!FilePaths.parent(child.path).equals(item.path))throw new IOException("Некорректный дочерний путь");
            add(backend,child,name+"/"+child.name,output,seen,depth+1,control);
        }
    }
    public static String extract(FileBackend source,FileEntry archive,FileBackend destination,String parent,File scratch,FileOperations.Control control)throws Exception {
        String base=archive.name.toLowerCase(Locale.ROOT).endsWith(".zip")?archive.name.substring(0,archive.name.length()-4):archive.name+" распакован";
        if(base.isEmpty())base="Архив";
        String folder=FileOperations.available(destination,parent,base);destination.mkdir(folder);
        Set<String> entries=new HashSet<>(),directories=new HashSet<>();directories.add(folder);long total=0;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source.read(archive.path)))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                control.check();String name=entry.getName();
                if(entry.isDirectory()&&name.endsWith("/"))name=name.substring(0,name.length()-1);
                if(name.startsWith("/")||name.contains(":"))throw new IOException("Небезопасный путь в ZIP");
                FilePaths.normalize("/"+name);
                if(name.split("/").length>128||!entries.add(name)||entries.size()>MAX_ENTRIES)throw new IOException("Повторяющийся или слишком глубокий ZIP");
                String target=folder+"/"+name;control.item(name);
                ensureDirectories(destination,entry.isDirectory()?target:FilePaths.parent(target),folder,directories);
                if(!entry.isDirectory()) {
                    // Stage one entry: ZIP streams often lack length; cloud uploads require a known size.
                    File stage=File.createTempFile("natro-unzip-",".part",scratch);
                    try {
                        long count=0;
                        try(OutputStream output=new FileOutputStream(stage)) {
                            byte[] buffer=new byte[128*1024];for(int read;(read=zip.read(buffer))!=-1;) {
                                control.check();total+=read;count+=read;if(total>MAX_UNPACKED)throw new IOException("Распаковка превысила 32 ГБ");output.write(buffer,0,read);control.report(read);
                            }
                        }
                        if(entry.getSize()>=0&&count!=entry.getSize())throw new IOException("Повреждённый ZIP");
                        try(InputStream input=new FileInputStream(stage)){destination.write(target,input,count,control);}
                    } finally {Files.deleteIfExists(stage.toPath());}
                }
                zip.closeEntry();
            }
            if(entries.isEmpty())throw new IOException("Архив пуст или не является ZIP");
        }
        return folder;
    }
    private static void ensureDirectories(FileBackend backend,String path,String root,Set<String> created)throws Exception {
        if(created.contains(path))return;
        if(!FilePaths.contains(root,path))throw new IOException("Путь за пределами папки распаковки");
        ensureDirectories(backend,FilePaths.parent(path),root,created);backend.mkdir(path);created.add(path);
    }
}
