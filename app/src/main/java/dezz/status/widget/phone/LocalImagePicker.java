/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** In-app PNG/JPEG picker for Android 9 head units without DocumentsUI. No external activity. */
public final class LocalImagePicker {
    private final Activity activity;
    private final Executor worker;
    private final Consumer<Uri> selected;
    private AlertDialog dialog;
    private TextView path;
    private ListView list;
    private File root,directory;
    private int generation;
    public LocalImagePicker(Activity activity,Executor worker,Consumer<Uri> selected){this.activity=activity;this.worker=worker;this.selected=selected;}
    public void show(){
        LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,12,24,12);
        path=new TextView(activity);path.setTextSize(19);page.addView(path);
        Button up=new Button(activity);up.setText("‹ Папка выше / носители");up.setAllCaps(false);page.addView(up);
        list=new ListView(activity);page.addView(list,new LinearLayout.LayoutParams(-1,Math.round(300*activity.getResources().getDisplayMetrics().density)));
        dialog=new AlertDialog.Builder(activity).setTitle("Выбрать PNG/JPEG из памяти ГУ или USB")
                .setView(page).setNegativeButton("Отмена",null).create();
        dialog.setOnDismissListener(d->generation++);dialog.show();
        up.setOnClickListener(v->{if(root==null||directory==null||root.equals(directory))volumes();else open(root,directory.getParentFile());});
        volumes();
    }
    private boolean current(int token){return token==generation&&!activity.isDestroyed()&&!activity.isFinishing()&&dialog.isShowing();}
    private void volumes(){
        root=null;directory=null;int token=++generation;path.setText("Загрузка носителей…");list.setEnabled(false);
        worker.execute(()->{
            List<File> roots=new ArrayList<>();
            File primary=Environment.getExternalStorageDirectory();if(primary!=null&&primary.isDirectory())roots.add(primary);
            File[] storage=new File("/storage").listFiles();
            if(storage!=null)for(File volume:storage){String name=volume.getName();if(!name.equals("self")&&!name.equals("emulated")&&volume.isDirectory()&&!roots.contains(volume))roots.add(volume);}
            File own=activity.getExternalFilesDir(null);if(own!=null&&own.isDirectory())roots.add(own);
            File downloads=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File incoming=new File(downloads==null?activity.getFilesDir():downloads,"Natro-Incoming");
            if(incoming.isDirectory())roots.add(incoming);
            activity.runOnUiThread(()->{if(!current(token))return;
                path.setText(roots.isEmpty()?"Нет доступных носителей. Подключите USB или проверьте разрешение на чтение памяти.":"Выберите носитель. Изображения с телефона можно передать через «Обмен файлами», затем открыть папку Natro-Incoming.");
                List<String> labels=new ArrayList<>();for(File file:roots)labels.add(file.equals(primary)?"Память головного устройства":file.equals(incoming)?"Полученные с телефона · Natro-Incoming":file.equals(own)?"Файлы Natro":file.getName());
                display(labels);list.setOnItemClickListener((p,v,index,id)->open(roots.get(index),roots.get(index)));
            });
        });
    }
    private void open(File volume,File folder){
        root=volume;directory=folder;int token=++generation;path.setText(folder.getAbsolutePath()+"\nЗагрузка…");list.setEnabled(false);
        worker.execute(()->{
            try{
                List<File> files=LocalImageFiles.list(volume,folder);
                activity.runOnUiThread(()->{if(!current(token))return;
                    path.setText(folder.getAbsolutePath()+(files.isEmpty()?"\nНет PNG/JPEG или вложенных папок.":"\nPNG/JPEG, до 8 МБ"));
                    List<String> labels=new ArrayList<>();for(File file:files)labels.add((file.isDirectory()?"Папка · ":"")+file.getName());
                    display(labels);list.setOnItemClickListener((p,v,index,id)->{
                        File file=files.get(index);if(file.isDirectory())open(volume,file);else{dialog.dismiss();selected.accept(Uri.fromFile(file));}
                    });
                });
            }catch(Exception error){activity.runOnUiThread(()->{if(!current(token))return;path.setText(folder.getAbsolutePath()+"\nНе удалось прочитать папку: "+error.getMessage());display(Collections.emptyList());});}
        });
    }
    private void display(List<String> labels){list.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,labels){
        @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,recycled,parent);view.setTextSize(20);view.setMinHeight(Math.round(56*activity.getResources().getDisplayMetrics().density));return view;}
    });list.setEnabled(true);}
}
