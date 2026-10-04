/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** User-selected archives live outside application data, so a backup never includes its exports. */
public final class BackupLocalFiles {
    private BackupLocalFiles() {}
    public static boolean within(File root,File file)throws IOException {
        return file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath());
    }
    public static List<File> list(File root,File directory)throws IOException {
        if(!within(root,directory)||!directory.isDirectory())throw new IOException("Папка недоступна");
        List<File> result=new ArrayList<>();int count=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory.toPath())) {
            for(Path entry:entries) {
                if(++count>20000)throw new IOException("Слишком много файлов. Перенесите копию в отдельную папку.");
                File file=entry.toFile();
                if(file.isHidden()||!within(root,file))continue;
                if(file.isDirectory()||file.isFile()&&file.getName().toLowerCase(Locale.ROOT).endsWith(".natrobackup"))result.add(file);
            }
        }
        result.sort(Comparator.comparing((File f)->!f.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    public static File destination(File volume,String name)throws IOException {
        if(!name.matches("[A-Za-z0-9._-]+\\.natrobackup"))throw new IOException("Недопустимое имя копии");
        File folder=new File(volume,"Natro-Backups"),file=new File(folder,name);
        if(!volume.isDirectory()||Files.isSymbolicLink(folder.toPath())||!within(volume,file))
            throw new IOException("Носитель или папка копий недоступны");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось создать папку Natro-Backups");
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Файл уже существует. Он не будет заменён.");
        return file;
    }
    public static OutputStream create(File destination)throws IOException {
        // CREATE_NEW also refuses an existing file or dangling symlink created after selection.
        if(Files.isSymbolicLink(destination.getParentFile().toPath()))throw new IOException("Папка копий была заменена. Выберите носитель заново.");
        return Files.newOutputStream(destination.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
    public static void copyVerified(File source,File destination)throws IOException {
        boolean created=false;
        try {
            try(InputStream input=new FileInputStream(source);OutputStream output=create(destination)) {
                created=true;BackupFiles.copy(input,output,BackupFiles.MAX_TOTAL_BYTES+64L*1024*1024);
            }
            if(!BackupFiles.sha256(source).equals(BackupFiles.sha256(destination)))throw new IOException("Сохранённая копия не прошла обратное чтение");
        }catch(IOException failure) {
            if(created)try{Files.deleteIfExists(destination.toPath());}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
}
