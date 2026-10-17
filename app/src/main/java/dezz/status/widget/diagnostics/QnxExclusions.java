/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit exclusion of the user's two verified archives, not remote hash validation. */
public final class QnxExclusions {
    private final Map<String,String[]> files=new HashMap<>();
    public static QnxExclusions load() throws IOException {
        InputStream in=QnxExclusions.class.getResourceAsStream("/qnx-collected-20261017.tsv");
        if(in==null)throw new IOException("Список ранее скачанных QNX файлов отсутствует");
        try(InputStream source=in){return read(source);}
    }
    static QnxExclusions read(InputStream source)throws IOException {
        QnxExclusions result=new QnxExclusions();
        BufferedReader reader=new BufferedReader(new InputStreamReader(source,StandardCharsets.UTF_8));
        String row;
        while((row=reader.readLine())!=null) {
            if(row.startsWith("#")||row.isEmpty())continue;
            String[] fields=row.split("\t",-1);
            if(fields.length!=4||!fields[0].matches("[a-f0-9]{64}")||!fields[1].matches("[0-9]{1,18}")
                    ||!fields[2].matches("[a-f0-9]{64}")||!fields[3].matches("full|partial|both"))
                throw new IOException("invalid_qnx_exclusion_index");
            if(result.files.put(fields[0],fields)!=null)throw new IOException("duplicate_qnx_exclusion");
        }
        return result;
    }
    public String previous(String path,long size) {
        // Current journals/process data are evidence of this run, never static exclusions.
        if(path.startsWith("/var/log/")||path.startsWith("/dev/")
                ||(path.startsWith("/userdata/DLT/")&&!path.endsWith(".zip")))return null;
        String[] record=files.get(pathHash(path));
        if(record==null||!record[1].equals(Long.toString(size)))return null;
        return "source="+record[3]+" archived_sha256="+record[2]+" remote_content_not_revalidated";
    }
    static String pathHash(String path) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
