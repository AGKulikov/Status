/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import dezz.status.widget.update.NatroUpdater;

public final class UpdateActivity extends dezz.status.widget.settings.SettingsActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state;
    private Button check,download,install;
    private boolean visible;
    private final Runnable refresh=new Runnable(){public void run(){
        if(!visible)return;
        try{org.json.JSONObject value=NatroUpdater.get(UpdateActivity.this).status();
            state.setText("Установлена Natro "+value.getString("installed")+"\n\n"+value.getString("message"));
            check.setEnabled(!value.getBoolean("busy"));download.setEnabled(value.getBoolean("canDownload"));install.setEnabled(value.getBoolean("canInstall"));
        }catch(Exception error){state.setText("Не удалось прочитать состояние обновления");}
        handler.postDelayed(this,1000);
    }};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(24,24,24,24);
        content.addView(button("← Назад",this::finish));state=new TextView(this);state.setTextSize(20);content.addView(state);
        check=button("Проверить обновления",()->run(()->NatroUpdater.get(this).check()));content.addView(check);
        download=button("Скачать обновление",()->run(()->NatroUpdater.get(this).download()));content.addView(download);
        install=button("Установить обновление",()->new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Обновить Natro?").setMessage("Будет открыт системный установщик. Устанавливайте на стоянке. Настройки не сбрасываются; завершение подтвердит Android.")
                .setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(dialog,which)->run(()->{
                    android.content.Intent intent=NatroUpdater.get(this).installationIntent();
                    handler.post(()->{if(visible&&!isFinishing())try{startActivity(intent);}catch(RuntimeException error){Toast.makeText(this,"Установщик недоступен",Toast.LENGTH_LONG).show();}});
                })).show());content.addView(install);
        TextView help=new TextView(this);help.setText("Источник: GitHub Releases · AGKulikov/Status · stable. Только подписанные APK Natro с большим versionCode. Проверка и установка выполняются по вашей кнопке, не при загрузке магнитолы. Нужен интернет на магнитоле.");content.addView(help);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, content);
    }
    private Button button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setOnClickListener(v->action.run());return button;}
    private interface Task{void run()throws Exception;}
    private void run(Task task){new Thread(()->{try{task.run();}catch(Exception error){handler.post(()->{if(visible)Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();});}},"natro-update-ui").start();}
    @Override protected void onResume(){super.onResume();visible=true;handler.post(refresh);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(refresh);super.onPause();}
}
