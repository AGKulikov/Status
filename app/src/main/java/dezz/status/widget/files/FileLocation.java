/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.content.Context;
import android.net.Uri;
import dezz.status.widget.YandexDiskSession;
import java.io.*;
import java.util.*;
import okhttp3.HttpUrl;
import org.json.JSONObject;

public final class FileLocation {
    public final String kind,root,path,label;
    public FileLocation(String kind,String root,String path,String label)throws IOException {
        if(!Arrays.asList("local","tree","disk","public").contains(kind))throw new IOException("Неизвестное хранилище");
        this.kind=kind;this.root=root;this.path=FilePaths.normalize(path);this.label=label;
    }
    public FileLocation at(String path)throws IOException{return new FileLocation(kind,root,path,label);}
    public FileBackend backend(Context context)throws Exception {
        switch(kind) {
            case "local": return new LocalFiles(new File(root));
            case "tree": return new DocumentFiles(context.getContentResolver(),Uri.parse(root));
            case "disk": return new YandexFiles(YandexDiskSession.get(context),null);
            case "public": return new YandexFiles((YandexDiskSession)null,root);
            default:throw new IOException("Неизвестный диск");
        }
    }
    public String encode() {
        try{return new JSONObject().put("kind",kind).put("root",root).put("path",path).put("label",label).toString();}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    public static FileLocation decode(String encoded)throws Exception {
        JSONObject object=new JSONObject(encoded);return new FileLocation(object.getString("kind"),object.getString("root"),object.getString("path"),object.getString("label"));
    }
    public static FileLocation publicLink(String value)throws Exception {
        HttpUrl url=HttpUrl.parse(value.trim());
        if(url==null||!url.isHttps()||url.port()!=443||!url.username().isEmpty()||!url.password().isEmpty())throw new IOException("Нужна HTTPS-ссылка Яндекс Диска");
        String host=url.host();if(host.startsWith("www."))host=host.substring(4);
        if(!Arrays.asList("disk.yandex.ru","disk.yandex.com","disk.yandex.by","disk.yandex.kz","disk.yandex.uz","yadi.sk").contains(host))
            throw new IOException("Эта ссылка не относится к Яндекс Диску");
        List<String> parts=url.pathSegments();
        if(parts.size()<2||!Arrays.asList("d","i").contains(parts.get(0))||!parts.get(1).matches("[A-Za-z0-9_-]+"))throw new IOException("Неизвестный формат публичной ссылки");
        String path="/";for(int i=2;i<parts.size();i++)if(!parts.get(i).isEmpty())path=FilePaths.child(path,parts.get(i));
        String base="https://disk.yandex.ru/"+parts.get(0)+"/"+parts.get(1);
        return new FileLocation("public",base,path,"Публичная ссылка");
    }
}
