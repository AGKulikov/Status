/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Read-only, single-directory listing; never recurse or follow links outside the chosen volume. */
public final class LocalImageFiles {
    private LocalImageFiles() {}
    public static boolean within(File root,File value)throws IOException {
        return value.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath());
    }
    public static List<File> list(File root,File directory)throws IOException {
        if(!within(root,directory)||!directory.isDirectory())throw new IOException("Папка недоступна");
        List<File> result=new ArrayList<>();int examined=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory.toPath())){
            for(Path path:entries){
                if(++examined>20_000)throw new IOException("Слишком много файлов в папке. Перенесите изображение в отдельную папку.");
                File file=path.toFile();String name=file.getName().toLowerCase(Locale.ROOT);
                if(file.isHidden()||!within(root,file))continue;
                if(file.isDirectory()||file.isFile()&&(name.endsWith(".png")||name.endsWith(".jpg")||name.endsWith(".jpeg")))result.add(file);
            }
        }
        result.sort(Comparator.comparing((File file)->!file.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
