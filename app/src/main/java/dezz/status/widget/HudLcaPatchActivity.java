/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import dezz.status.widget.adb.*;
import dezz.status.widget.settings.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class HudLcaPatchActivity extends SettingsActivity {
    private AdbConsoleSession session;private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);session=new AdbConsoleSession(this);setTitle("HUD/LCA");
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,20,24,24);scroll.addView(page);
        TextView title=new TextView(this);title.setText("Системный патч HUD/LCA");title.setTextSize(26);page.addView(title);
        TextView hint=new TextView(this);hint.setText("Только известный модуль Android 9 / ARM64. Проверка сверяет размер, SHA-256 и исходный блок. Запись требует root и доступного remount. Оригинал хранится вне Natro и рядом с модулем. Применяйте на стоящем автомобиле. Физический эффект зависит от прошивки.");page.addView(hint);
        add(page,"Проверить модуль",()->execute(null));
        for(HudLcaPatch.Mode mode:HudLcaPatch.Mode.values())add(page,mode==HudLcaPatch.Mode.ORIGINAL?"Восстановить оригинал":"Применить "+mode.label,()->new AlertDialog.Builder(this).setTitle(mode.label).setMessage("Проверить совместимость, сохранить оригинал и заменить системный модуль? При разрыве связи не повторяйте операцию автоматически: сначала проверьте установленный файл.").setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->execute(mode)).show());
        add(page,"Перезагрузить головное устройство",()->new AlertDialog.Builder(this).setTitle("Перезагрузить ГУ?").setMessage("Перезагрузка выполняется отдельно от установки патча.").setNegativeButton("Отмена",null).setPositiveButton("Перезагрузить",(d,w)->submit(s->{s.connect();s.command("reboot");},"Запрос перезагрузки отправлен; завершение проверяется на ГУ.")).show());
        status=new TextView(this);status.setText("Проверка ещё не выполнена.");status.setTextIsSelectable(true);page.addView(status);setContentView(scroll);SettingsBackNavigation.install(this,scroll);
    }
    private void add(LinearLayout page,String text,Runnable action){Button button=new Button(this);button.setText(text);button.setAllCaps(false);page.addView(button);button.setOnClickListener(v->{if(!session.busy())action.run();});}
    private void execute(HudLcaPatch.Mode mode){submit(s->{
        s.connect();if(mode!=null)s.daemonRoot(true);
        AdbShellResult.Result result=s.command(mode==null?HudLcaPatch.inspect():HudLcaPatch.install(mode));
        if(!result.success()||result.truncated)throw new IOException(result.describe());
        if(mode!=null&&!result.output.contains("NATRO_HUD_PATCH_VERIFIED_"+mode.name()))throw new IOException("Нет подтверждения финального чтения");
        File record=new File(getFilesDir(),"hud-lca-patch/last-report.txt");
        dezz.status.widget.backup.BackupFiles.atomicWrite(record,result.output.getBytes(StandardCharsets.UTF_8));
        runOnUiThread(()->status.setText(result.output+(mode==null?"":"\nФайл проверен. Перезагрузка — отдельной кнопкой.")));
    },null);}
    private void submit(AdbConsoleSession.Operation operation,String completed){status.setText("Выполняется…");session.submit(operation,error->runOnUiThread(()->{if(error!=null)status.setText("Операция не подтверждена: "+error.getMessage()+"\nСначала выполните проверку. Оригинал: "+HudLcaPatch.BACKUP);else if(completed!=null)status.setText(completed);}));}
    @Override protected void onDestroy(){if(session!=null)session.close();super.onDestroy();}
}
