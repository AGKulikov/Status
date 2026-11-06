/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.*;
import java.util.*;

/** Sequential, cancellable engine. A failed/partial copy never deletes its source tree. */
public final class FileOperations {
    public interface Progress {void update(String name,long bytes);}
    public static final class Control {
        public volatile boolean cancelled;
        private long bytes;
        private String name="";
        private final Progress progress;
        public Control(Progress progress){this.progress=progress;}
        public void check()throws InterruptedIOException {
            if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Операция отменена");
        }
        public void item(String name)throws IOException {check();this.name=name;report(0);}
        public void report(long count)throws IOException {check();bytes+=count;if(progress!=null)progress.update(name,bytes);}
    }
    private FileOperations(){}
    public static long pipe(InputStream input,OutputStream output,Control control)throws IOException {
        byte[] buffer=new byte[128*1024];long total=0;
        for(int count;(count=input.read(buffer))!=-1;) {
            control.check();output.write(buffer,0,count);total+=count;control.report(count);
        }
        control.check();return total;
    }
    public static String copy(FileBackend source,String path,FileBackend destination,String folder,
                              String requestedName,boolean move,Control control)throws Exception {
        FilePaths.normalize(path);FilePaths.normalize(folder);FilePaths.name(requestedName);control.check();
        if(!destination.writable())throw new IOException("Назначение доступно только для чтения");
        FileEntry item=source.stat(path);
        if(item==null)throw new FileNotFoundException("Исходный файл недоступен");
        if(item.directory&&source instanceof LocalFiles&&destination instanceof LocalFiles) {
            String origin=((LocalFiles)source).resolve(path).getCanonicalPath();
            String dest=((LocalFiles)destination).resolve(folder).getCanonicalPath();
            if(dest.equals(origin)||dest.startsWith(origin+File.separator))
                throw new IOException("Нельзя скопировать папку внутрь самой себя");
        }
        if(source.identity().equals(destination.identity()) && item.directory && FilePaths.contains(path,folder))
            throw new IOException("Нельзя скопировать папку внутрь самой себя");
        String target=FilePaths.child(folder,requestedName);
        if(source.identity().equals(destination.identity())&&path.equals(target)&&move)
            throw new IOException("Исходная папка и назначение совпадают");
        target=available(destination,folder,requestedName);
        List<FileEntry> snapshot=new ArrayList<>();
        copyTree(source,item,destination,target,control,new HashSet<>(),snapshot,0);
        control.check();
        if(move)removeCopiedSource(source,snapshot,control);
        return target;
    }
    public static String available(FileBackend backend,String parent,String name)throws Exception {
        String value=FilePaths.child(parent,name);
        if(backend.stat(value)==null)return value;
        int dot=name.lastIndexOf('.');
        String stem=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";
        for(int i=1;i<=10000;i++) {
            value=FilePaths.child(parent,stem+" ("+i+")"+ext);
            if(backend.stat(value)==null)return value;
        }
        throw new IOException("Слишком много файлов с одинаковым именем");
    }
    private static void copyTree(FileBackend src,FileEntry item,FileBackend dst,String target,
                                 Control control,Set<String> seen,List<FileEntry> snapshot,int depth)throws Exception {
        control.item(item.name);
        if(depth>128||!seen.add(item.path))throw new IOException("Циклическая или слишком глубокая папка");
        snapshot.add(item);
        if(item.directory) {
            dst.mkdir(target);
            for(FileEntry child:src.list(item.path)) {
                if(!FilePaths.parent(child.path).equals(item.path))throw new IOException("Некорректный путь в ответе хранилища");
                copyTree(src,child,dst,FilePaths.child(target,child.name),control,seen,snapshot,depth+1);
            }
        } else try(InputStream input=src.read(item.path)) {dst.write(target,input,item.size,control);}
    }
    private static void unchanged(FileEntry before,FileEntry after)throws IOException {
        if(after==null||before.directory!=after.directory||(!before.directory&&before.size!=after.size)||before.modified!=after.modified)
            throw new IOException("Исходник изменился во время переноса; копия создана, удаление остановлено");
    }
    private static void removeCopiedSource(FileBackend source,List<FileEntry> snapshot,Control control)throws Exception {
        Map<String,Set<String>> children=new HashMap<>();
        for(FileEntry entry:snapshot)children.computeIfAbsent(FilePaths.parent(entry.path),k->new HashSet<>()).add(entry.path);
        // Validate the original snapshot before deletion, never deleting uncopied new children.
        for(FileEntry entry:snapshot){
            control.check();unchanged(entry,source.stat(entry.path));
            if(entry.directory){
                Set<String> current=new HashSet<>();for(FileEntry child:source.list(entry.path))current.add(child.path);
                if(!current.equals(children.getOrDefault(entry.path,Collections.emptySet())))
                    throw new IOException("Содержимое папки изменилось; исходная папка сохранена");
            }
        }
        for(int i=snapshot.size()-1;i>=0;i--){
            control.check();FileEntry entry=snapshot.get(i);
            if(entry.directory){if(!source.list(entry.path).isEmpty())throw new IOException("В исходной папке появились новые файлы; удаление остановлено");}
            else unchanged(entry,source.stat(entry.path));
            source.delete(entry.path,control);
        }
    }
    public static void deleteTree(FileBackend backend,String path,Control control,int depth)throws Exception {
        FilePaths.normalize(path);control.check();
        if(path.equals("/")||depth>128)throw new IOException("Удаление этого пути запрещено");
        FileEntry entry=backend.stat(path);
        if(entry==null)throw new IOException("Файл уже отсутствует");
        control.item(entry.name);
        if(entry.directory)for(FileEntry child:backend.list(path)) {
            if(!FilePaths.parent(child.path).equals(path))throw new IOException("Некорректный дочерний путь");
            deleteTree(backend,child.path,control,depth+1);
        }
        backend.delete(path,control);
    }
    public static List<FileEntry> search(FileBackend source,String path,String query,Control control)throws Exception {
        List<FileEntry> found=new ArrayList<>();Deque<String> pending=new ArrayDeque<>();Set<String> seen=new HashSet<>();pending.add(path);
        String match=query.toLowerCase(Locale.ROOT);
        while(!pending.isEmpty()) {
            control.check();String folder=pending.removeFirst();
            if(!seen.add(folder)||seen.size()>50000)throw new IOException("Слишком много папок; уточните поиск");
            for(FileEntry item:source.list(folder)) {
                if(!FilePaths.parent(item.path).equals(folder))throw new IOException("Некорректный дочерний путь");
                if(item.name.toLowerCase(Locale.ROOT).contains(match))found.add(item);
                if(item.directory)pending.add(item.path);
                if(found.size()>20000)throw new IOException("Слишком много результатов; уточните поиск");
            }
        }
        return found;
    }
}
