/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class LocalFiles implements FileBackend {
    private final File root;
    public LocalFiles(File root) throws IOException {this.root=root.getCanonicalFile();}
    @Override public String identity(){return "local:"+root;}
    @Override public boolean writable(){return root.canWrite();}
    public File resolve(String path) throws IOException {
        FilePaths.normalize(path);
        File file=new File(root,path.substring(1));
        File next=root;
        if(!path.equals("/")) for(String part:path.substring(1).split("/")) {
            next=new File(next,part);
            if(Files.isSymbolicLink(next.toPath()))throw new IOException("Символическая ссылка недоступна");
        }
        File canonical=file.getCanonicalFile();
        if(!canonical.equals(root)&&!canonical.getPath().startsWith(root.getPath()+File.separator))
            throw new IOException("Путь за пределами выбранного диска");
        return file;
    }
    private FileEntry entry(String path,File file){return new FileEntry(path,file.getName(),file.isDirectory(),file.length(),file.lastModified());}
    @Override public List<FileEntry> list(String path)throws IOException {
        File[] files=resolve(path).listFiles();
        if(files==null)throw new IOException("Папка недоступна. Проверьте разрешение и подключение накопителя.");
        List<FileEntry> result=new ArrayList<>();
        for(File file:files)if(!Files.isSymbolicLink(file.toPath()))
            result.add(entry(FilePaths.child(path,file.getName()),file));
        return result;
    }
    @Override public FileEntry stat(String path)throws IOException {
        File file=resolve(path);return file.exists()?entry(path,file):null;
    }
    @Override public InputStream read(String path)throws IOException {
        File file=resolve(path);
        if(!file.isFile())throw new IOException("Файл недоступен");
        return new FileInputStream(file);
    }
    @Override public void mkdir(String path)throws IOException {
        if(path.equals("/")||!resolve(path).mkdir())throw new IOException("Не удалось создать папку: "+FilePaths.leaf(path));
    }
    @Override public void write(String path,InputStream input,long length,FileOperations.Control control)throws Exception {
        File target=resolve(path);
        if(target.exists())throw new IOException("Файл уже существует: "+target.getName());
        File stage=File.createTempFile(".natro-transfer-",".part",target.getParentFile());
        try {
            try(FileOutputStream output=new FileOutputStream(stage)) {
                long actual=FileOperations.pipe(input,output,control);
                if(length>=0&&actual!=length)throw new IOException("Передача прервана: размер файла не совпал");
                output.getFD().sync();
            }
            control.check();
            // No REPLACE_EXISTING: a newly appearing destination must survive a transfer.
            Files.move(stage.toPath(),resolve(path).toPath());
        } finally {Files.deleteIfExists(stage.toPath());}
    }
    @Override public void delete(String path)throws Exception {
        if(path.equals("/"))throw new IOException("Нельзя удалить корень накопителя");
        Files.delete(resolve(path).toPath()); // Non-empty directory deletion is explicit and recursive in the engine.
    }
}
