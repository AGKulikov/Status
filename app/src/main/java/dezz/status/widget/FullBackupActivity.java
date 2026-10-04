/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.backup.*;
import dezz.status.widget.settings.SettingsBackNavigation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicit maintenance UI. SAF/password/check do not start controllers or execute restored actions. */
public final class FullBackupActivity extends dezz.status.widget.settings.SettingsActivity {
    private static final int CREATE=701,OPEN=702,STORAGE_PERMISSION=703;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button apply;
    private boolean busy;
    private char[] password;
    private File checkedStage;
    private JSONObject checkedMetadata;
    private BackupFilePicker localPicker;
    private boolean pendingLocalCreate;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Полная резервная копия");
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,24);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Копии и восстановление");title.setTextSize(26);root.addView(title);
        TextView detail=new TextView(this);detail.setText("Зашифрованная копия настроек, профилей, файлов, иконок, шрифтов и ключей доступа. Для геометрии окна откройте Навигатор из этой пары APK. Создание и восстановление временно закроют экраны Natro. Проверка файла ничего не применяет. Пароль нужен и на другом устройстве.");detail.setTextSize(18);root.addView(detail);
        button(root,"Создать полную копию",()->askPassword(true));
        button(root,"Открыть и проверить копию",()->askPassword(false));
        apply=button(root,"Восстановить проверенную копию",this::confirmRestore);apply.setEnabled(false);
        button(root,"Вернуть состояние до восстановления",()->confirmRollback());
        button(root,"Продолжить работу Natro",()->{
            if(busy)return;
            startActivity(new Intent(this,SettingsHubActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));finish();
        });
        button(root,"Разрешить автовозобновление музыки",()->runOperation(()->{
            BackupMaintenance.acknowledgePlayback(this);return "Автовозобновление разрешено для следующего запуска Natro.";
        }));
        status=new TextView(this);status.setTextSize(18);status.setPadding(0,20,0,20);status.setText("Копия ещё не выбрана. Системные разрешения, сопряжения Bluetooth и роль HOME проверяются на устройстве отдельно.");root.addView(status);
        setContentView(scroll);SettingsBackNavigation.install(this,scroll);
        if(getIntent().getBooleanExtra("recovery",false))runOperation(()->{
            try(BackupMaintenance session=BackupMaintenance.begin(this)) {new BackupStorage(this).transaction().recover();}
            return "Прерванное восстановление обработано. Можно продолжить работу.";
        });
    }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(18);
        parent.addView(button,new LinearLayout.LayoutParams(-1,-2));button.setOnClickListener(v->{if(!busy)action.run();});return button;
    }
    private void askPassword(boolean create) {
        EditText field=new EditText(this);field.setSingleLine();field.setHint("Минимум 8 символов");field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(create?"Пароль новой копии":"Пароль копии").setView(field).setNegativeButton("Отмена",null).setPositiveButton("Далее",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            char[] entered=field.getText().toString().toCharArray();
            if(entered.length<8){Arrays.fill(entered,'\0');field.setError("Не менее 8 символов");return;}
            clearPassword();password=entered;field.setText("");dialog.dismiss();
            chooseDocument(create);
        }));dialog.show();
    }
    private void chooseDocument(boolean create) {
        Intent intent=new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        if(create)intent.putExtra(Intent.EXTRA_TITLE,backupName());
        try { startActivityForResult(intent,create?CREATE:OPEN); }
        catch(ActivityNotFoundException missing) { chooseLocal(create); }
    }
    private String backupName(){return "Natro-"+BuildConfig.VERSION_NAME+"-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".natrobackup";}
    private void chooseLocal(boolean create) {
        String permission=create?android.Manifest.permission.WRITE_EXTERNAL_STORAGE:android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if(checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingLocalCreate=create;requestPermissions(new String[]{permission},STORAGE_PERMISSION);return;
        }
        localPicker=new BackupFilePicker(this,worker,uri->selectedDocument(create?CREATE:OPEN,uri),this::clearPassword);
        localPicker.show(create?backupName():null);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=STORAGE_PERMISSION)return;
        if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)chooseLocal(pendingLocalCreate);
        else {clearPassword();status.setText("Доступ к памяти не разрешён. Создание или проверка копии не запускались.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CREATE&&request!=OPEN)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){clearPassword();return;}
        selectedDocument(request,data.getData());
    }
    private void selectedDocument(int request,Uri uri) {
        char[] key=password;password=null;
        if(key==null){status.setText("Введите пароль повторно.");return;}
        runOperation(()->{
            try{return request==CREATE?create(uri,key):check(uri,key);}finally{Arrays.fill(key,'\0');}
        });
    }
    private File stage(String prefix)throws IOException {
        File file=new File(BackupMaintenance.control(this),prefix+"-"+UUID.randomUUID());BackupFiles.directory(file);return file;
    }
    private String create(Uri uri,char[] key)throws Exception {
        File snapshot=stage("snapshot");NavigatorBackup bridge=new NavigatorBackup(this);JSONObject navigator=null;
        try(BackupMaintenance session=BackupMaintenance.begin(this)) {
            BackupStorage storage=new BackupStorage(this);storage.transaction().recover();
            navigator=bridge.freeze();JSONObject metadata=storage.capture(snapshot);
            JSONObject portableNavigator=new JSONObject(navigator.toString());portableNavigator.remove("token");metadata.put("navigator",portableNavigator);
            storage.validate(snapshot,metadata);
            // Finish the archive privately first. A failed SAF copy is never reported as a complete backup.
            File archive=new File(snapshot.getParentFile(),snapshot.getName()+".encrypted");
            try {
                try(FileOutputStream output=new FileOutputStream(archive)){BackupArchive.write(snapshot,metadata,key,output);}
                File verification=stage("readback");
                try(FileInputStream input=new FileInputStream(archive)) {
                    JSONObject checked=BackupArchive.read(input,key,verification);storage.validate(new File(verification,"data"),checked);
                }finally{BackupFiles.removeTree(verification);}
                if("file".equals(uri.getScheme()))BackupLocalFiles.copyVerified(archive,new File(uri.getPath()));
                else {
                    try(InputStream input=new FileInputStream(archive);OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                        if(output==null)throw new IOException("Не удалось открыть файл назначения");BackupFiles.copy(input,output,BackupFiles.MAX_TOTAL_BYTES+64L*1024*1024);
                    }
                    String written;
                    try(InputStream input=getContentResolver().openInputStream(uri)) {
                        if(input==null)throw new IOException("Нельзя проверить сохранённый файл");
                        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;
                        while((count=input.read(buffer))!=-1)hash.update(buffer,0,count);
                        StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));written=hex.toString();
                    }
                    if(!written.equals(BackupFiles.sha256(archive)))throw new IOException("Сохранённая копия не прошла обратное чтение");
                }
                return "Копия сохранена и повторно проверена. Файлов: "+BackupFiles.inventory(snapshot).size()+". Версия: "+BuildConfig.VERSION_NAME+". Сохраните пароль отдельно."
                        +("file".equals(uri.getScheme())?"\nФайл: "+uri.getPath():"");
            }finally{BackupFiles.delete(archive);}
        }finally {
            try{if(navigator!=null)bridge.release(navigator);}finally{BackupFiles.removeTree(snapshot);}
        }
    }
    private String check(Uri uri,char[] key)throws Exception {
        clearChecked();File stage=stage("checked");
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("Не удалось прочитать копию");
            JSONObject metadata=BackupArchive.read(input,key,stage);new BackupStorage(this).validate(new File(stage,"data"),metadata);
            checkedStage=stage;checkedMetadata=metadata;
            runOnUiThread(()->apply.setEnabled(true));
            return "Архив проверен. Версия "+metadata.getString("sourceVersion")+", файлов: "+metadata.getJSONArray("files").length()+". Рабочие данные не изменены. После восстановления музыка не запустится автоматически; разрешения и подключения нужно проверить. Системные патчи не устанавливаются. "
                    +(metadata.has("externalSystemFiles")?"Сведения о доступности и хешах системных файлов есть в копии; доступные оригиналы сохранены отдельно.":"В этой старой копии нет сведений о системных оригиналах.")
                    +" Отключение штатных действий кнопок нужно явно применить в их настройках; импорт не повторяет эти операции.";
        }catch(Exception failure){BackupFiles.removeTree(stage);throw failure;}
    }
    private void confirmRestore() {
        if(checkedStage==null)return;
        new AlertDialog.Builder(this).setTitle("Восстановить копию?").setMessage("Текущие настройки будут заменены. Перед заменой сохранится точка отката. Экраны Natro закроются; Навигатор должен быть открыт. На другом устройстве прежние разрешения и системные журналы не активируются.")
                .setNegativeButton("Отмена",null).setPositiveButton("Восстановить",(d,w)->runOperation(this::restore)).show();
    }
    private String restore()throws Exception {
        BackupStorage storage=new BackupStorage(this);NavigatorBackup bridge=new NavigatorBackup(this);JSONObject frozen=null;boolean journalOwns=false;
        try(BackupMaintenance session=BackupMaintenance.begin(this)) {
            storage.transaction().recover();File data=new File(checkedStage,"data");storage.prepareRestore(data,checkedMetadata);
            JSONObject transaction=new JSONObject();JSONObject archived=checkedMetadata.getJSONObject("navigator");
            if(archived.getBoolean("installed")) {
                frozen=bridge.freeze();if(!frozen.optBoolean("installed"))throw new IOException("Установите совместимый Навигатор перед восстановлением его настроек");
                transaction.put("navigatorToken",frozen.getString("token")).put("navigatorValues",archived.getJSONArray("values"));
                transaction.put("participantBefore",frozen);
            }
            BackupMaintenance.markRestored(this);
            journalOwns=true;storage.transaction().apply(data,transaction);
            clearChecked();return "Восстановление завершено, чтение файлов подтверждено. Перезапустите Навигатор и продолжите работу Natro. Автовозобновление музыки приостановлено.";
        }catch(Exception failure) {
            if(storage.transaction().hasUnfinishedRestore())throw new IOException("Восстановление прервано. Нужен доступ к Навигатору для завершения отката; запуск Natro заблокирован.",failure);
            clearChecked();throw failure;
        }finally {if(frozen!=null&&!storage.transaction().hasUnfinishedRestore())bridge.release(frozen);}
    }
    private void confirmRollback() {
        new AlertDialog.Builder(this).setTitle("Вернуть предыдущие настройки?").setMessage("Будет возвращена последняя сохранённая точка до восстановления.").setNegativeButton("Отмена",null)
                .setPositiveButton("Вернуть",(d,w)->runOperation(()->{
                    File point=stage("undo");NavigatorBackup bridge=new NavigatorBackup(this);JSONObject frozen=null;
                    BackupStorage storage=new BackupStorage(this);
                    try(BackupMaintenance session=BackupMaintenance.begin(this)) {
                        storage.transaction().recover();storage.transaction().copyRollbackPoint(point);
                        JSONObject transaction=new JSONObject();JSONObject previous=storage.transaction().rollbackParticipant();
                        if(previous.optBoolean("installed")) {
                            frozen=bridge.freeze();if(!frozen.optBoolean("installed"))throw new IOException("Для возврата геометрии откройте Навигатор");
                            transaction.put("navigatorToken",frozen.getString("token")).put("navigatorValues",previous.getJSONArray("values"));
                            transaction.put("participantBefore",frozen);
                        }
                        BackupMaintenance.markRestored(this);storage.transaction().apply(point,transaction);
                    }catch(Exception failure){throw failure;}
                    finally{try{if(frozen!=null&&!storage.transaction().hasUnfinishedRestore())bridge.release(frozen);}finally{BackupFiles.removeTree(point);}}
                    return "Точка отката восстановлена. Продолжите работу Natro и перезапустите Навигатор.";
                })).show();
    }
    private interface Operation {String run()throws Exception;}
    private void runOperation(Operation operation) {
        if(busy)return;busy=true;status.setText("Выполняется операция… Не закрывайте приложение.");apply.setEnabled(false);
        worker.execute(()->{
            String message;
            try{message=operation.run();}catch(Exception failure){message="Операция не завершена: "+failure.getMessage();}
            String result=message;runOnUiThread(()->{busy=false;status.setText(result);apply.setEnabled(checkedStage!=null);});
        });
    }
    private void clearPassword(){if(password!=null){Arrays.fill(password,'\0');password=null;}}
    private void clearChecked()throws IOException{if(checkedStage!=null)BackupFiles.removeTree(checkedStage);checkedStage=null;checkedMetadata=null;}
    @Override public void finish(){if(!busy)super.finish();}
    @Override public void onBackPressed(){if(!busy)super.onBackPressed();}
    @Override protected void onDestroy(){clearPassword();if(localPicker!=null)localPicker.close();if(!busy){try{clearChecked();}catch(IOException ignored){}worker.shutdown();}super.onDestroy();}
}
