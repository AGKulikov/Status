/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** No DocumentsUI dependency; directory reads run off MAIN and late results are discarded. */
public final class BackupFilePicker {
    private final Activity activity;
    private final Executor worker;
    private final Consumer<Uri> selected;
    private final Runnable cancelled;
    private AlertDialog dialog;
    private TextView path;
    private ListView list;
    private File root,directory;
    private String outputName;
    private int generation;
    private boolean picked;
    public BackupFilePicker(Activity activity,Executor worker,Consumer<Uri> selected,Runnable cancelled) {
        this.activity=activity;this.worker=worker;this.selected=selected;this.cancelled=cancelled;
    }
    public void show(String outputName) {
        this.outputName=outputName;picked=false;
        LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,12,24,12);
        path=new TextView(activity);path.setTextSize(19);page.addView(path);
        {
            Button up=new Button(activity);up.setAllCaps(false);up.setText(outputName==null?"‹ Папка выше / носители":"Выбрать другой носитель");page.addView(up);
            up.setOnClickListener(v->{if(root==null||directory==null||root.equals(directory))volumes();else open(root,directory.getParentFile());});
        }
        list=new ListView(activity);page.addView(list,new LinearLayout.LayoutParams(-1,Math.round(300*activity.getResources().getDisplayMetrics().density)));
        dialog=new AlertDialog.Builder(activity).setTitle(outputName==null?"Открыть копию из памяти ГУ или USB":"Куда сохранить копию?")
                .setView(page).setNegativeButton("Отмена",null).create();
        dialog.setOnDismissListener(d->{generation++;if(!picked)cancelled.run();});dialog.show();volumes();
    }
    public void close(){generation++;if(dialog!=null)dialog.dismiss();}
    private boolean current(int token){return token==generation&&!activity.isDestroyed()&&!activity.isFinishing()&&dialog.isShowing();}
    private void choose(Uri uri){picked=true;dialog.dismiss();selected.accept(uri);}
    private void volumes() {
        root=null;directory=null;int token=++generation;path.setText("Загрузка носителей…");list.setEnabled(false);
        worker.execute(()->{
            List<File> volumes=new ArrayList<>();File primary=Environment.getExternalStorageDirectory();
            if(primary!=null&&primary.isDirectory())volumes.add(primary);
            File[] storage=new File("/storage").listFiles();
            if(storage!=null)for(File volume:storage)if(!Arrays.asList("self","emulated").contains(volume.getName())&&volume.isDirectory()&&!volumes.contains(volume))volumes.add(volume);
            activity.runOnUiThread(()->{if(!current(token))return;
                path.setText(volumes.isEmpty()?"Нет доступных носителей. Проверьте разрешение на доступ к памяти или подключите USB.":outputName==null?"Выберите носитель с файлом .natrobackup":"Копия сохранится в папку Natro-Backups выбранного носителя.");
                List<String> labels=new ArrayList<>();for(File file:volumes)labels.add(file.equals(primary)?"Память головного устройства":file.getName());
                display(labels);list.setOnItemClickListener((p,v,index,id)->{
                    File volume=volumes.get(index);
                    if(outputName==null){open(volume,volume);return;}
                    int saveToken=++generation;list.setEnabled(false);
                    worker.execute(()->{try {
                        File target=BackupLocalFiles.destination(volume,outputName);
                        activity.runOnUiThread(()->{if(current(saveToken))choose(Uri.fromFile(target));});
                    }catch(Exception error){failure(saveToken,error);}});
                });
            });
        });
    }
    private void open(File volume,File folder) {
        root=volume;directory=folder;int token=++generation;path.setText(folder.getAbsolutePath()+"\nЗагрузка…");list.setEnabled(false);
        worker.execute(()->{try {
            List<File> files=BackupLocalFiles.list(volume,folder);
            activity.runOnUiThread(()->{if(!current(token))return;
                path.setText(folder.getAbsolutePath()+(files.isEmpty()?"\nКопий .natrobackup или вложенных папок нет.":"\nВыберите копию .natrobackup"));
                List<String> labels=new ArrayList<>();for(File file:files)labels.add((file.isDirectory()?"Папка · ":"")+file.getName());
                display(labels);list.setOnItemClickListener((p,v,index,id)->{File file=files.get(index);if(file.isDirectory())open(volume,file);else choose(Uri.fromFile(file));});
            });
        }catch(Exception error){failure(token,error);}});
    }
    private void failure(int token,Exception error){activity.runOnUiThread(()->{if(current(token)){path.setText("Не удалось открыть носитель: "+error.getMessage());display(Collections.emptyList());list.setOnItemClickListener(null);}});}
    private void display(List<String> labels) {
        list.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,labels){
            @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,recycled,parent);view.setTextSize(20);view.setMinHeight(Math.round(56*activity.getResources().getDisplayMetrics().density));return view;}
        });list.setEnabled(true);
    }
}
