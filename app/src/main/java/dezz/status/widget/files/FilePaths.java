/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.IOException;

public final class FilePaths {
    private FilePaths() {}
    public static String name(String value) throws IOException {
        if(value==null || value.isEmpty() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.length()>255)
            throw new IOException("Недопустимое имя файла");
        for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i)))
            throw new IOException("Имя содержит управляющий символ");
        return value;
    }
    public static String normalize(String path) throws IOException {
        if(path==null || !path.startsWith("/")) throw new IOException("Некорректный путь");
        if(path.equals("/")) return path;
        for(String part:path.substring(1).split("/",-1)) name(part);
        return path;
    }
    public static String child(String parent,String name) throws IOException {
        return normalize(parent)+(parent.equals("/")?"":"/")+name(name);
    }
    public static String parent(String path) {
        int index=path.lastIndexOf('/'); return index<=0?"/":path.substring(0,index);
    }
    public static String leaf(String path) {return path.substring(path.lastIndexOf('/')+1);}
    public static boolean contains(String ancestor,String path) {
        return ancestor.equals(path)||ancestor.equals("/")||path.startsWith(ancestor+"/");
    }
}
