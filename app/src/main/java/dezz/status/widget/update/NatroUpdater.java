/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit stable-channel operations only. No boot checks, root, silent install or GitHub credentials. */
public final class NatroUpdater {
    private static final String API="https://api.github.com/repos/AGKulikov/Status/releases?per_page=100";
    private static final String ASSETS="https://github.com/AGKulikov/Status/releases/download/";
    private static final String PACKAGE="ru.natro.statuswidget";
    private static final String CERT="6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75";
    // AOSP Android 9 getPackageArchiveInfo only invokes collectCertificates when
    // GET_SIGNATURES is present. Request both; still trust only the CURRENT
    // SigningInfo signers below, never the legacy field or signing history.
    @SuppressWarnings("deprecation")
    static final int SIGNATURE_FLAGS=PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES;
    private static final long MAX_APK=128L*1024*1024;
    private static NatroUpdater instance;
    public static synchronized NatroUpdater get(Context context) {
        if(instance==null)instance=new NatroUpdater(context.getApplicationContext());return instance;
    }
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String state="idle",message="Канал: GitHub Releases / AGKulikov/Status / stable";
    private boolean busy;
    private JSONObject candidate;
    private File apk;
    NatroUpdater(Context context){this.context=context;}
    public synchronized JSONObject status() throws Exception {
        return new JSONObject().put("state",state).put("message",message).put("busy",busy)
                .put("installed",installed().versionName).put("version",candidate==null?"":candidate.optString("versionName"))
                .put("canDownload",!busy&&candidate!=null&&apk==null).put("canInstall",!busy&&apk!=null);
    }
    public synchronized void check() throws IOException {
        begin("checking","Проверка GitHub Releases…");candidate=null;apk=null;
        worker.execute(()->{
            try {
                JSONArray releases=new JSONArray(read(API,4*1024*1024));JSONObject best=null;
                long current=installed().getLongVersionCode(),maximum=current;boolean channel=false;
                for(int i=0;i<releases.length();i++) {
                    JSONObject release=releases.getJSONObject(i);
                    if(release.optBoolean("draft")||release.optBoolean("prerelease"))continue;
                    JSONArray assets=release.getJSONArray("assets");
                    for(int j=0;j<assets.length();j++) {
                        JSONObject asset=assets.getJSONObject(j);
                        if(!"natro-update.json".equals(asset.optString("name")))continue;
                        String manifestUrl=asset.getString("browser_download_url");requireAsset(manifestUrl);
                        JSONObject manifest=new JSONObject(read(manifestUrl,64*1024));
                        validateManifest(manifest,manifestUrl);channel=true;
                        long version=manifest.getLong("versionCode");
                        if(version>maximum){best=manifest;maximum=version;}
                    }
                }
                synchronized(this){candidate=best;finish(best!=null?"available":channel?"current":"unpublished",
                        best!=null?"Доступна Natro "+best.getString("versionName"):channel?"Более новой версии в стабильном канале нет":"Канал ещё не опубликован: в Releases нет natro-update.json. Это не подтверждение актуальности установленной версии.");}
            } catch(Exception error){fail("Проверка обновлений",error);}
        });
    }
    public synchronized void download() throws IOException {
        if(candidate==null)throw new IOException("Сначала проверьте обновления");
        if(apk!=null)throw new IOException("APK уже подготовлен");
        begin("downloading","Скачивание и проверка APK…");
        final JSONObject selected=candidate;
        worker.execute(()->{
            File temporary=null;
            try {
                File directory=new File(context.getCacheDir(),"updates");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Нет места для APK");
                temporary=File.createTempFile("Natro-update-",".apk",directory);
                HttpsURLConnection connection=open(selected.getString("url"));
                try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(temporary)) {
                    byte[] block=new byte[65536];long total=0;int count;
                    while((count=input.read(block))!=-1){total+=count;if(total>selected.getLong("size")||total>MAX_APK)throw new IOException("Размер APK превышен");output.write(block,0,count);}
                    if(total!=selected.getLong("size"))throw new IOException("APK скачан не полностью");
                } finally {connection.disconnect();}
                verify(temporary,selected);
                synchronized(this){apk=temporary;temporary=null;finish("ready","APK проверен. Нажмите «Установить». Подтверждение Android появится на магнитоле.");}
            }catch(Exception error){fail("Загрузка обновления",error);}
            finally{if(temporary!=null)temporary.delete();}
        });
    }
    /** Called only after an explicit local/paired-user action; Android owns final consent. */
    public synchronized Intent installationIntent() throws Exception {
        if(busy||apk==null||candidate==null)throw new IOException("Нет проверенного APK");
        verify(apk,candidate);
        if(!context.getPackageManager().canRequestPackageInstalls()) {
            message="Разрешите установку из Natro на магнитоле и снова нажмите «Установить»";
            return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".fileprovider",apk);
        message="Запрошен системный установщик на магнитоле; установка ещё не подтверждена";
        return new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private PackageInfo installed() throws Exception {return context.getPackageManager().getPackageInfo(context.getPackageName(),SIGNATURE_FLAGS);}
    void verify(File file,JSONObject manifest) throws Exception {
        if(!file.isFile()||file.length()!=manifest.getLong("size"))throw new IOException("Размер APK изменился");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)hash.update(block,0,count);}
        if(!hex(hash.digest()).equals(manifest.getString("sha256")))throw new IOException("SHA-256 APK не совпадает");
        PackageInfo next=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),SIGNATURE_FLAGS);
        PackageInfo current=installed();
        if(next==null||!PACKAGE.equals(next.packageName)||!PACKAGE.equals(current.packageName)
                ||next.applicationInfo==null||next.applicationInfo.minSdkVersion>android.os.Build.VERSION.SDK_INT
                ||next.getLongVersionCode()!=manifest.getLong("versionCode")||next.getLongVersionCode()<=current.getLongVersionCode()
                ||!manifest.getString("versionName").equals(next.versionName))throw new IOException("Пакет или версия APK не подходят");
        requireStableSigner(next,"Скачанный APK");
        requireStableSigner(current,"Установленная Natro");
        DiagnosticJournal.infoAsync("updater","apk_verified; version="+next.versionName+"; current_signer=stable; installed_signer=stable");
    }
    static void requireStableSigner(PackageInfo info,String label)throws Exception {
        if(info==null||info.signingInfo==null)throw new IOException(label+": Android не вернул сертификат подписи");
        Signature[] signatures=info.signingInfo.getApkContentsSigners();
        if(signatures==null||signatures.length==0||signatures.length==1&&signatures[0]==null)
            throw new IOException(label+": Android не вернул сертификат подписи");
        if(signatures.length!=1)throw new IOException(label+": ожидался один сертификат подписи");
        if(!hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())).equals(CERT))
            throw new IOException(label+": сертификат подписи не совпадает со стабильным");
    }
    private static void validateManifest(JSONObject value,String source)throws Exception {
        if(value.getInt("schema")!=1||!PACKAGE.equals(value.getString("package"))||!"stable".equals(value.getString("channel"))
                ||value.getLong("versionCode")<=0||value.getString("versionName").isEmpty()
                ||!value.getString("sha256").matches("[0-9a-f]{64}")||!CERT.equals(value.getString("certificateSha256"))
                ||value.getLong("size")<=0||value.getLong("size")>MAX_APK)throw new IOException("Некорректный манифест выпуска");
        String url=value.getString("url");requireAsset(url);
        if(!url.substring(0,url.lastIndexOf('/')+1).equals(source.substring(0,source.lastIndexOf('/')+1))||!url.endsWith(".apk"))
            throw new IOException("APK не принадлежит выпуску манифеста");
    }
    private synchronized void begin(String next,String text)throws IOException {if(busy)throw new IOException("Операция обновления уже выполняется");busy=true;state=next;message=text;}
    private synchronized void finish(String next,String text){busy=false;state=next;message=text;}
    private void fail(String action,Exception error){String detail=action+": "+(error instanceof IOException?error.getMessage():error.getClass().getSimpleName());DiagnosticJournal.warn("updater",detail);finish("error",detail);}
    private static void requireAsset(String value)throws IOException {
        if(!value.startsWith(ASSETS)||value.contains("..")||value.contains("?")||value.contains("#"))throw new IOException("Недоверенный адрес выпуска");
    }
    private static HttpsURLConnection open(String address)throws Exception {
        URL url=new URL(address);
        for(int hop=0;hop<6;hop++) {
            String host=url.getHost();
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)
                    ||!(host.equals("api.github.com")||host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com")))
                throw new IOException("Недоверенное перенаправление загрузки");
            HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
            connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Natro-Updater");connection.setRequestProperty("Accept","application/vnd.github+json");
            int status=connection.getResponseCode();
            if(status==200)return connection;
            String location=connection.getHeaderField("Location");connection.disconnect();
            if(status>=300&&status<400&&location!=null){url=new URL(url,location);continue;}
            throw new IOException("GitHub HTTP "+status+"; проверьте интернет/лимит запросов");
        }
        throw new IOException("Слишком много перенаправлений");
    }
    private static String read(String url,int limit)throws Exception {
        HttpsURLConnection connection=open(url);
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(output.size()+count>limit)throw new IOException("Ответ GitHub слишком большой");output.write(bytes,0,count);}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }finally{connection.disconnect();}
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
