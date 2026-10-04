/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import dezz.status.widget.adb.*;
import dezz.status.widget.settings.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
public final class HudLcaPatchActivity extends SettingsActivity {
    private AdbConsoleSession session;private TextView status;
    private final List<Button> operationButtons=new ArrayList<>();
    private final List<Button> patchButtons=new ArrayList<>();
    private boolean moduleVerified;
    @Override protected void onCreate(Bundle state){super.onCreate(state);session=new AdbConsoleSession(this);setTitle("HUD/LCA");
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,20,24,24);scroll.addView(page);
        TextView title=new TextView(this);title.setText("Системный патч HUD/LCA");title.setTextSize(26);page.addView(title);
        TextView hint=new TextView(this);hint.setText("Только известный модуль Android 9 / ARM64. Проверка сверяет размер, SHA-256 и исходный блок. Запись требует root и доступного remount. Оригинал хранится вне Natro и рядом с модулем. Применяйте на стоящем автомобиле. Физический эффект зависит от прошивки.");page.addView(hint);
        add(page,"Проверить модуль",()->execute(null));
        for(HudLcaPatch.Mode mode:HudLcaPatch.Mode.values())patchButtons.add(add(page,mode==HudLcaPatch.Mode.ORIGINAL?"Восстановить оригинал":"Применить "+mode.label,()->new AlertDialog.Builder(this).setTitle(mode.label).setMessage("Проверить совместимость, сохранить оригинал и заменить системный модуль? При разрыве связи не повторяйте операцию автоматически: сначала проверьте установленный файл.").setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->execute(mode)).show()));
        add(page,"Перезагрузить головное устройство",()->new AlertDialog.Builder(this).setTitle("Перезагрузить ГУ?").setMessage("Перезагрузка выполняется отдельно от установки патча.").setNegativeButton("Отмена",null).setPositiveButton("Перезагрузить",(d,w)->submit("Перезагрузка",s->{s.connect();AdbShellResult.Result result=s.command("reboot");if(!result.success()||result.truncated)throw new IOException(result.describe());},"Запрос перезагрузки отправлен; завершение проверяется на ГУ.")).show());
        status=new TextView(this);status.setText("Проверка ещё не выполнена.");status.setTextIsSelectable(true);page.addView(status);setContentView(scroll);SettingsBackNavigation.install(this,scroll);setOperationBusy(false);
    }
    private Button add(LinearLayout page,String text,Runnable action){Button button=new Button(this);button.setText(text);button.setAllCaps(false);page.addView(button);operationButtons.add(button);button.setOnClickListener(v->{if(!session.busy())action.run();else Toast.makeText(this,"Дождитесь завершения текущей операции",Toast.LENGTH_SHORT).show();});return button;}
    private void execute(HudLcaPatch.Mode mode){
        if(session.busy())return;
        if(mode!=null&&!moduleVerified){status.setText("Совместимость модуля не подтверждена. Доступна только проверка.");return;}
        moduleVerified=false;submit(mode==null?"Проверка модуля":"Применение "+mode.label,s->{
        s.connect();if(mode!=null)s.daemonRoot(true);
        AdbShellResult.Result result=s.command(mode==null?HudLcaPatch.inspect():HudLcaPatch.install(mode));
        if(!result.success()||result.truncated)throw new IOException(result.describe());
        if(mode==null&&HudLcaPatch.inspectedMode(result.output)==null)throw new IOException("Нет подтверждения полной проверки модуля\n"+result.describe());
        if(mode!=null&&!result.output.contains("NATRO_HUD_PATCH_VERIFIED_"+mode.name()))throw new IOException("Нет подтверждения финального чтения");
        File record=new File(getFilesDir(),"hud-lca-patch/last-report.txt");
        dezz.status.widget.backup.BackupFiles.atomicWrite(record,result.output.getBytes(StandardCharsets.UTF_8));
        runOnUiThread(()->{if(!isDestroyed()&&!isFinishing()){moduleVerified=true;status.setText(result.output+(mode==null?"\nПроверка файла завершена. Root и remount для записи этой проверкой не подтверждены.":"\nФайл проверен. Перезагрузка — отдельной кнопкой."));}});
    },null);}
    private void setOperationBusy(boolean busy){for(Button button:operationButtons)button.setEnabled(!busy&&(!patchButtons.contains(button)||moduleVerified));}
    static String failureMessage(String operation,Exception error){
        String detail=error.getMessage();if(detail==null||detail.trim().isEmpty())detail=error.getClass().getSimpleName();
        String next="Проверка модуля".equals(operation)
                ?"Проверка не завершена; установка не выполнялась. Причина указана выше. Если соединение ADB недоступно, проверьте его и разрешение RSA в разделе ADB."
                :"Результат операции не подтверждён. Автоматического повтора нет. Перед повторным применением проверьте установленный файл; не считайте его прежним или изменённым без чтения.";
        return operation+": ошибка\n"+detail+"\n\n"+next+"\nКаталог резервного оригинала (наличие не подтверждено): "+HudLcaPatch.BACKUP;
    }
    private void submit(String name,AdbConsoleSession.Operation operation,String completed){
        if(session.busy()){Toast.makeText(this,"Дождитесь завершения текущей операции",Toast.LENGTH_SHORT).show();return;}
        CharSequence previous=status.getText();status.setText(name+"…\nОперация выполняется один раз; ожидание — до 60 секунд.");setOperationBusy(true);
        boolean accepted=session.submit(operation,error->runOnUiThread(()->{
            if(isDestroyed()||isFinishing())return;
            if(error!=null){moduleVerified=false;status.setText(failureMessage(name,error));}else if(completed!=null)status.setText(completed);
            setOperationBusy(false);
        }));
        if(!accepted){setOperationBusy(false);status.setText(previous);Toast.makeText(this,"Операция не запущена: сеанс занят или закрыт",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onDestroy(){if(session!=null)session.close();super.onDestroy();}
}
