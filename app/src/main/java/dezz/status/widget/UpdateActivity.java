/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import dezz.status.widget.update.NatroUpdater;
import dezz.status.widget.update.UpdateTarget;
import java.util.EnumMap;
import org.json.JSONObject;

/** One check for all apps; each update button prepares and opens its verified APK. */
public final class UpdateActivity extends dezz.status.widget.settings.SettingsActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final EnumMap<UpdateTarget,TextView> states=new EnumMap<>(UpdateTarget.class);
    private final EnumMap<UpdateTarget,Button> updates=new EnumMap<>(UpdateTarget.class);
    private Button check;
    private boolean visible,preparing,permissionWaiting;
    private UpdateTarget pending;
    private final Runnable refresh=new Runnable(){public void run(){
        if(!visible)return;
        render();handler.postDelayed(this,500);
    }};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);setTitle("Обновления приложений");
        if(saved!=null){
            try{pending=UpdateTarget.valueOf(saved.getString("pending",""));}catch(IllegalArgumentException ignored){}
            permissionWaiting=saved.getBoolean("permissionWaiting");
        }
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(12),dp(24),dp(16));
        content.addView(button("← Назад",this::finish));
        check=button("Проверить обновления",this::checkAll);content.addView(check);
        for(UpdateTarget target:UpdateTarget.values()){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(12),0,dp(12));
            LinearLayout description=new LinearLayout(this);description.setOrientation(LinearLayout.VERTICAL);
            TextView title=new TextView(this);title.setText(target.title);title.setTextSize(24);title.setTypeface(null,android.graphics.Typeface.BOLD);description.addView(title);
            TextView state=new TextView(this);state.setTextSize(20);description.addView(state);states.put(target,state);
            row.addView(description,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
            Button update=button("Обновить",()->update(target));update.setContentDescription("Обновить "+target.title);update.setEnabled(false);updates.put(target,update);
            LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(dp(210),dp(64));action.leftMargin=dp(20);row.addView(update,action);content.addView(row);
        }
        TextView help=new TextView(this);help.setTextSize(18);help.setText("Кнопка «Обновить» скачает APK и откроет установку. Подтверждение Android появится на магнитоле. Настройки сохраняются.");content.addView(help);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, content);
    }
    @Override protected boolean usesEditorSections(){return false;}
    private void render(){
        try{
            boolean busy=pending!=null||preparing;
            EnumMap<UpdateTarget,JSONObject> values=new EnumMap<>(UpdateTarget.class);
            for(UpdateTarget target:UpdateTarget.values()){
                JSONObject value=NatroUpdater.get(this,target).status();values.put(target,value);busy|=value.getBoolean("busy");
                String available=value.getString("version");
                String text="Установлено: "+value.getString("installed");
                if(!available.isEmpty())text+="  ·  Доступно: "+available;
                states.get(target).setText(text+"\n"+value.getString("message"));
            }
            check.setEnabled(!busy);
            for(UpdateTarget target:UpdateTarget.values())updates.get(target).setEnabled(!busy&&values.get(target).getBoolean("canUpdate"));
            if(pending!=null&&!preparing&&!permissionWaiting){
                JSONObject value=values.get(pending);
                if(value.getBoolean("canInstall"))prepareInstaller(pending);
                else if(!value.getBoolean("busy"))pending=null; // failure, external update or process recreation
            }
        }catch(Exception error){toast("Не удалось прочитать состояние обновлений");}
    }
    private void checkAll(){
        check.setEnabled(false);for(Button update:updates.values())update.setEnabled(false);
        for(UpdateTarget target:UpdateTarget.values())try{NatroUpdater.get(this,target).check();}catch(Exception error){toast(error.getMessage());}
        render();
    }
    private void update(UpdateTarget target){
        if(pending!=null||preparing)return;
        pending=target;
        try{
            NatroUpdater updater=NatroUpdater.get(this,target);
            if(!updater.status().getBoolean("canInstall"))updater.download();
        }catch(Exception error){pending=null;toast(error.getMessage());}
        render();
    }
    private void prepareInstaller(UpdateTarget target){
        preparing=true;
        new Thread(()->{
            try{
                Intent intent=NatroUpdater.get(this,target).installationIntent();
                handler.post(()->{
                    preparing=false;
                    if(!visible||isFinishing()||isDestroyed()||pending!=target)return;
                    permissionWaiting=Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(intent.getAction());
                    if(!permissionWaiting)pending=null; // consume BEFORE launch; cancel/back never relaunches it
                    try{startActivity(intent);}catch(RuntimeException error){pending=null;permissionWaiting=false;toast("Установщик недоступен");}
                });
            }catch(Exception error){handler.post(()->{preparing=false;if(pending==target)pending=null;if(visible)toast(error.getMessage());});}
        },"natro-install-verify").start();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setTextSize(20);button.setMinHeight(dp(56));button.setAllCaps(false);button.setOnClickListener(v->action.run());return button;}
    private void toast(String message){Toast.makeText(this,message==null?"Не удалось выполнить обновление":message,Toast.LENGTH_LONG).show();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(pending!=null)state.putString("pending",pending.name());state.putBoolean("permissionWaiting",permissionWaiting);}
    @Override protected void onResume(){
        super.onResume();visible=true;
        if(permissionWaiting&&pending!=null){
            permissionWaiting=false;
            if(!NatroUpdater.get(this,pending).canInstallPackages()){
                pending=null;toast("Установка не разрешена. Нажмите «Обновить», чтобы повторить");
            }
        }
        handler.removeCallbacks(refresh);handler.post(refresh);
    }
    @Override protected void onPause(){visible=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
