/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.files.*;
import java.util.concurrent.*;

/** Credentials, QR and second-factor challenges stay on the real Yandex Passport page. */
public final class YandexDiskLoginActivity extends AppCompatActivity {
    private WebView web;
    private TextView status;
    private boolean checking;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public static boolean allowed(String value) {
        try {Uri uri=Uri.parse(value);String host=uri.getHost();
            return "https".equals(uri.getScheme())&&host!=null&&(host.equals("yandex.ru")||host.endsWith(".yandex.ru"))
                    &&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443);
        }catch(Exception ignored){return false;}
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar=new LinearLayout(this);Button back=new Button(this);back.setText("Отмена");back.setOnClickListener(v->finish());bar.addView(back);
        status=new TextView(this);status.setText("Вход в Яндекс · passport.yandex.ru");status.setTextSize(18);bar.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button done=new Button(this);done.setText("Проверить вход");done.setOnClickListener(v->checkSession());bar.addView(done);root.addView(bar);
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSaveFormData(false);settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                if(!allowed(request.getUrl().toString())){status.setText("Этот переход не относится к входу Яндекса");return true;}return false;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !allowed(url);}
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){status.setText("Яндекс · "+Uri.parse(url).getHost());}
            @Override public void onPageFinished(WebView view,String url) {
                Uri uri=Uri.parse(url);
                if(allowed(url)&&"disk.yandex.ru".equals(uri.getHost())&&uri.getPath()!=null&&uri.getPath().startsWith("/client"))checkSession();
            }
            // Default SSL handling cancels failures. No JS bridge, password interception or TLS bypass.
        });
        web.loadUrl("https://passport.yandex.ru/auth?retpath=https%3A%2F%2Fdisk.yandex.ru%2Fclient%2Fdisk");
    }
    private void checkSession() {
        if(checking)return;
        String cookies=CookieManager.getInstance().getCookie("https://disk.yandex.ru/");
        if(cookies==null){status.setText("Сначала завершите вход на странице Яндекса");return;}
        checking=true;status.setText("Проверка доступа к Диску…");
        android.content.Context app=getApplicationContext();
        worker.execute(()->{
            try {
                YandexDiskSession session=YandexDiskSession.get(app);session.importWebSession(cookies);
                new YandexFiles(session,null).list("/");
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){setResult(RESULT_OK);finish();}});
            } catch(Exception failure) {
                runOnUiThread(()->{checking=false;if(!isDestroyed())status.setText(FileTaskService.safeMessage(failure));});
            }
        });
    }
    /** Expire only Yandex cookies, leaving other embedded web sessions alone. */
    public static void clearWebSession() {
        CookieManager manager=CookieManager.getInstance();
        for(String host:new String[]{"disk.yandex.ru","passport.yandex.ru","yandex.ru"}) {
            String header=manager.getCookie("https://"+host+"/");if(header==null)continue;
            for(String item:header.split(";")) {
                int equals=item.indexOf('=');if(equals<1)continue;String name=item.substring(0,equals).trim();
                for(String domain:new String[]{host,".yandex.ru"})for(String path:new String[]{"/","/client","/auth"})
                    manager.setCookie("https://"+host+"/",name+"=; Max-Age=0; Path="+path+"; Domain="+domain+"; Secure");
            }
        }
        manager.flush();
    }
    @Override protected void onDestroy(){worker.shutdownNow();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
