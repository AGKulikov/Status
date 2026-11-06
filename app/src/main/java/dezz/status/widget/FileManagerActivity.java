/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import dezz.status.widget.backup.BackupPreferences;
import dezz.status.widget.files.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Native two-pane manager; its workers never share the boot/media/Bluetooth executors. */
public final class FileManagerActivity extends AppCompatActivity {
    private static final int STORAGE=8340,TREE=8341,LOGIN=8342;
    private static final int BG=0xff101722,CARD=0xff192332,ACCENT=0xff73c7ff,TEXT=0xffedf4ff,MUTED=0xffa3b4c9;
    private final ExecutorService reads=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Pane[] panes=new Pane[2];
    private int active,treePane,sort;
    private boolean compact,showHidden,resumed;
    private SharedPreferences settings;
    private TextView operation;
    private FileLocation clipboardSource;
    private List<FileEntry> clipboard=new ArrayList<>();
    private boolean cut;
    private long seenRevision=-1;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(isDestroyed())return;
        operation.setText(FileTaskService.status+(FileTaskService.busy?" · нажмите, чтобы отменить":""));
        if(seenRevision!=FileTaskService.revision){seenRevision=FileTaskService.revision;if(!FileTaskService.busy){reloadBoth();}}
        main.postDelayed(this,500);
    }};
    private final class Pane {
        final int index;
        FileLocation location;
        LinearLayout box;
        Button place;
        TextView path,summary;
        EditText filter;
        ListView list;
        List<FileEntry> entries=new ArrayList<>(),visible=new ArrayList<>();
        Set<String> selected=new LinkedHashSet<>();
        Future<?> loading;
        long generation;
        boolean searchResults;
        Pane(int index,FileLocation location){this.index=index;this.location=location;}
    }
    public static void launch(Context context){context.startActivity(new Intent(context,FileManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private TextView text(String label,float size,int color){TextView view=new TextView(this);view.setText(label);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff26374b));b.setTextSize(16);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Файлы Natro");
        settings=BackupPreferences.open(this,"natro_file_manager_v1",0);
        showHidden=settings.getBoolean("showHidden",false);sort=settings.getInt("sort",0);
        compact=getResources().getConfiguration().screenWidthDp<900;
        try {
            FileLocation root=new FileLocation("local",Environment.getExternalStorageDirectory().getAbsolutePath(),"/","Устройство");
            for(int i=0;i<2;i++) {
                FileLocation location=root;
                String encoded=state==null?settings.getString("pane"+i,""):state.getString("pane"+i,"");
                if(!encoded.isEmpty())try{location=FileLocation.decode(encoded);}catch(Exception ignored){}
                panes[i]=new Pane(i,location);
            }
            if(state!=null)active=state.getInt("active",0)==1?1:0;
        }catch(Exception impossible){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(18),dp(8),dp(18),dp(10));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("Назад",this::finish));
        TextView title=text("Файлы",28,TEXT);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(16),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        header.addView(button("Войти в Яндекс",()->startActivityForResult(new Intent(this,YandexDiskConnectActivity.class),LOGIN)));
        header.addView(button("Ещё",this::more));root.addView(header);
        HorizontalScrollView actions=new HorizontalScrollView(this);actions.setHorizontalScrollBarEnabled(false);LinearLayout tools=new LinearLayout(this);
        tools.addView(button("Новая папка",this::newFolder));tools.addView(button("Копировать",()->copySelection(false)));
        tools.addView(button("Вырезать",()->copySelection(true)));tools.addView(button("Вставить",this::paste));
        tools.addView(button("Переименовать",this::rename));tools.addView(button("Удалить",this::delete));
        tools.addView(button("Выделить все",()->{Pane p=current();for(FileEntry e:p.visible)p.selected.add(e.path);render(p);}));
        tools.addView(button("ZIP",this::archives));
        tools.addView(button("Снять выбор",()->{current().selected.clear();render(current());}));
        if(compact)tools.addView(button("Другая панель",()->activate(1-active)));
        actions.addView(tools);root.addView(actions);
        LinearLayout columns=new LinearLayout(this);
        for(Pane pane:panes) {buildPane(pane);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(4),dp(8),dp(4),dp(6));columns.addView(pane.box,lp);}
        root.addView(columns,new LinearLayout.LayoutParams(-1,0,1));
        operation=text("Готово к работе",15,MUTED);operation.setMinHeight(dp(36));operation.setGravity(Gravity.CENTER_VERTICAL);operation.setOnClickListener(v->{if(FileTaskService.busy)new AlertDialog.Builder(this).setTitle("Отменить текущую операцию?").setMessage("Уже завершённые файлы останутся в назначении.").setNegativeButton("Продолжить",null).setPositiveButton("Отменить",(d,w)->FileTaskService.cancel()).show();});root.addView(operation);
        setContentView(root);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        activate(active);
        if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE);
    }
    private void buildPane(Pane pane) {
        pane.box=new LinearLayout(this);pane.box.setOrientation(LinearLayout.VERTICAL);pane.box.setPadding(dp(12),dp(10),dp(12),dp(8));pane.box.setBackground(background(CARD));
        LinearLayout top=new LinearLayout(this);pane.place=button(pane.location.label+" ▾",()->{activate(pane.index);places(pane);});top.addView(pane.place,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("↑",()->{activate(pane.index);navigate(pane,FilePaths.parent(pane.location.path));}));top.addView(button("Обновить",()->load(pane)));pane.box.addView(top);
        pane.path=text(pane.location.path,16,ACCENT);pane.path.setPadding(dp(5),dp(5),dp(5),dp(8));pane.path.setSingleLine();pane.path.setEllipsize(TextUtils.TruncateAt.MIDDLE);pane.path.setOnClickListener(v->input("Перейти в папку",pane.location.path,value->navigate(pane,FilePaths.normalize(value))));pane.box.addView(pane.path);
        LinearLayout find=new LinearLayout(this);pane.filter=new EditText(this);pane.filter.setTextColor(TEXT);pane.filter.setHintTextColor(MUTED);pane.filter.setSingleLine();pane.filter.setTextSize(16);pane.filter.setHint("Фильтр по имени");find.addView(pane.filter,new LinearLayout.LayoutParams(0,dp(48),1));find.addView(button("Поиск",()->search(pane)));pane.box.addView(find);
        pane.filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){activate(pane.index);render(pane);}public void afterTextChanged(Editable e){}});
        pane.summary=text("Загрузка…",14,MUTED);pane.summary.setPadding(dp(5),dp(6),0,dp(6));pane.box.addView(pane.summary);
        pane.list=new ListView(this);pane.list.setDivider(null);pane.list.setChoiceMode(ListView.CHOICE_MODE_NONE);pane.box.addView(pane.list,new LinearLayout.LayoutParams(-1,0,1));
        pane.list.setOnItemClickListener((parent,view,position,id)->{
            activate(pane.index);FileEntry item=pane.visible.get(position);
            if(!pane.selected.isEmpty()){toggle(pane,item);return;}
            if(item.directory)navigate(pane,item.path);else openEntry(pane,item,false);
        });
        pane.list.setOnItemLongClickListener((parent,view,position,id)->{activate(pane.index);toggle(pane,pane.visible.get(position));return true;});
    }
    private Pane current(){return panes[active];}
    private void activate(int index){active=index;for(Pane pane:panes)if(pane!=null&&pane.box!=null){pane.box.setVisibility(compact&&pane.index!=active?View.GONE:View.VISIBLE);GradientDrawable bg=background(CARD);bg.setStroke(dp(pane.index==active?2:1),pane.index==active?ACCENT:0xff2c3a4c);pane.box.setBackground(bg);}}
    private void toggle(Pane pane,FileEntry item){if(!pane.selected.remove(item.path))pane.selected.add(item.path);render(pane);}
    private void navigate(Pane pane,String path){try{pane.location=pane.location.at(path);pane.selected.clear();pane.filter.setText("");saveLocation(pane);load(pane);}catch(Exception e){error(e);}}
    private void setLocation(Pane pane,FileLocation location){pane.location=location;pane.selected.clear();pane.filter.setText("");saveLocation(pane);load(pane);}
    private void saveLocation(Pane pane){settings.edit().putString("pane"+pane.index,pane.location.encode()).apply();}
    private void reloadBoth(){for(Pane p:panes)if(p!=null)load(p);}
    private void load(Pane pane) {
        if(pane.loading!=null)pane.loading.cancel(true);long generation=++pane.generation;FileLocation location=pane.location;
        pane.path.setText(location.path);pane.place.setText(location.label+" ▾");pane.searchResults=false;
        pane.entries=new ArrayList<>();render(pane);pane.summary.setText("Загрузка…");
        Context app=getApplicationContext();
        pane.loading=reads.submit(()->{try{List<FileEntry> entries=location.backend(app).list(location.path);
            main.post(()->{if(isDestroyed()||generation!=pane.generation)return;pane.entries=entries;Set<String> existing=new HashSet<>();for(FileEntry e:entries)existing.add(e.path);pane.selected.retainAll(existing);render(pane);});
        }catch(Exception failure){main.post(()->{if(isDestroyed()||generation!=pane.generation)return;pane.entries=new ArrayList<>();pane.selected.clear();render(pane);pane.summary.setText(FileTaskService.safeMessage(failure));});}});
    }
    private void render(Pane pane) {
        if(pane.list==null)return;String filter=pane.filter.getText().toString().toLowerCase(Locale.ROOT);
        pane.visible=new ArrayList<>();for(FileEntry e:pane.entries)if((showHidden||!e.name.startsWith("."))&&e.name.toLowerCase(Locale.ROOT).contains(filter))pane.visible.add(e);
        Comparator<FileEntry> order=sort==1?Comparator.comparingLong((FileEntry e)->e.modified).reversed():sort==2?Comparator.comparingLong((FileEntry e)->e.size).reversed():Comparator.comparing(e->e.name.toLowerCase(Locale.ROOT));
        pane.visible.sort(Comparator.comparing((FileEntry e)->!e.directory).thenComparing(order));
        pane.summary.setText((pane.searchResults?"Результаты поиска · ":"")+pane.visible.size()+" объектов"+(pane.selected.isEmpty()?" · удерживайте для выбора":" · выбрано "+pane.selected.size()));
        pane.list.setAdapter(new BaseAdapter(){
            public int getCount(){return pane.visible.size();}public Object getItem(int position){return pane.visible.get(position);}public long getItemId(int position){return position;}
            public View getView(int position,View convert,ViewGroup parent){
                FileEntry e=pane.visible.get(position);LinearLayout row=new LinearLayout(FileManagerActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(7),dp(7),dp(7),dp(7));row.setMinimumHeight(dp(70));
                if(pane.selected.contains(e.path))row.setBackground(background(0xff29445c));
                TextView kind=text(e.directory?"ПАПКА":extension(e.name).toUpperCase(Locale.ROOT),e.directory?10:12,e.directory?ACCENT:MUTED);kind.setGravity(Gravity.CENTER);kind.setBackground(background(0xff26374b));row.addView(kind,new LinearLayout.LayoutParams(dp(55),dp(44)));
                LinearLayout labels=new LinearLayout(FileManagerActivity.this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(12),0,dp(8),0);
                TextView name=text(e.name,19,TEXT);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.MIDDLE);labels.addView(name);
                String detail=e.directory?"Папка":e.size<0?"Размер неизвестен":Formatter.formatFileSize(FileManagerActivity.this,e.size);
                if(e.modified>0)detail+="  ·  "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(e.modified));
                if(pane.searchResults)detail=FilePaths.parent(e.path)+"  ·  "+detail;
                TextView sub=text(detail,13,MUTED);sub.setSingleLine();sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);labels.addView(sub);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                CheckBox select=new CheckBox(FileManagerActivity.this);select.setFocusable(false);select.setFocusableInTouchMode(false);select.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));select.setChecked(pane.selected.contains(e.path));select.setContentDescription("Выбрать "+e.name);select.setOnClickListener(v->{activate(pane.index);toggle(pane,e);});row.addView(select,new LinearLayout.LayoutParams(dp(48),dp(48)));return row;
            }
        });
    }
    private static String extension(String name){int i=name.lastIndexOf('.');return i<0?"ФАЙЛ":name.substring(i+1).substring(0,Math.min(6,name.length()-i-1));}
    private List<FileEntry> selection(){List<FileEntry> result=new ArrayList<>();Pane p=current();for(FileEntry e:p.entries)if(p.selected.contains(e.path))result.add(e);
        List<FileEntry> roots=new ArrayList<>(result);result.removeIf(child->roots.stream().anyMatch(parent->parent.directory&&!parent.path.equals(child.path)&&FilePaths.contains(parent.path,child.path)));return result;}
    private List<FileEntry> requireSelection(){List<FileEntry> result=selection();if(result.isEmpty())message("Выберите файлы флажками или долгим нажатием");return result;}
    private void copySelection(boolean move){List<FileEntry> selected=requireSelection();if(selected.isEmpty())return;if(move&&current().location.kind.equals("public")){message("Публичные файлы можно только копировать");return;}clipboardSource=current().location;clipboard=selected;cut=move;message("Выбрано "+selected.size()+". Откройте папку назначения и нажмите «Вставить».");}
    private void paste() {
        if(clipboard.isEmpty()){message("Сначала выберите «Копировать» или «Вырезать»");return;}
        FileLocation src=clipboardSource,dst=current().location;List<FileEntry> files=new ArrayList<>(clipboard);boolean move=cut;Context app=getApplicationContext();
        new AlertDialog.Builder(this).setTitle(move?"Перенести файлы?":"Скопировать файлы?").setMessage(files.size()+" объектов → "+dst.label+dst.path+"\nПри совпадении имён будут сохранены оба файла.").setNegativeButton("Отмена",null).setPositiveButton("Начать",(d,w)->{
            if(startTask("Передача файлов",control->{FileBackend source=src.backend(app),dest=dst.backend(app);int done=0;
                for(FileEntry e:files){control.check();FileOperations.copy(source,e.path,dest,dst.path,e.name,move,control);done++;}
                return "Готово: "+done+" объектов → "+dst.label+dst.path;})){clipboard.clear();current().selected.clear();render(current());}
        }).show();
    }
    private void newFolder(){FileLocation dst=current().location;Context app=getApplicationContext();input("Новая папка","Новая папка",name->{FilePaths.name(name);startTask("Создание папки",c->{dst.backend(app).mkdir(FilePaths.child(dst.path,name));return "Папка создана";});});}
    private void rename(){List<FileEntry> list=requireSelection();if(list.size()!=1){if(!list.isEmpty())message("Для переименования выберите один объект");return;}FileEntry e=list.get(0);FileLocation src=current().location;Context app=getApplicationContext();
        input("Переименовать",e.name,name->{FilePaths.name(name);if(name.equals(e.name))return;startTask("Переименование",c->{FileBackend b=src.backend(app);String target=FilePaths.child(FilePaths.parent(e.path),name);
            if(b.stat(target)!=null)throw new IOException("Такое имя уже существует");
            if(b instanceof LocalFiles){java.nio.file.Files.move(((LocalFiles)b).resolve(e.path).toPath(),((LocalFiles)b).resolve(target).toPath());}
            else {FileOperations.copy(b,e.path,b,FilePaths.parent(e.path),name,true,c);}return "Переименовано: "+name;});});}
    private void delete(){List<FileEntry> files=requireSelection();if(files.isEmpty())return;FileLocation src=current().location;Context app=getApplicationContext();
        new AlertDialog.Builder(this).setTitle("Удалить выбранное?").setMessage(files.size()+" объектов, включая содержимое папок. Локальные файлы удаляются без корзины.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->startTask("Удаление",c->{FileBackend b=src.backend(app);if(!b.writable())throw new IOException("Хранилище только для чтения");for(FileEntry e:files)FileOperations.deleteTree(b,e.path,c,0);return "Удалено: "+files.size()+" объектов";})).show();}
    private boolean startTask(String title,FileTaskService.Task task){if(!FileTaskService.submit(getApplicationContext(),title,task)){message("Дождитесь завершения текущей операции");return false;}return true;}
    private void search(Pane pane){String query=pane.filter.getText().toString().trim();if(query.isEmpty()){message("Введите часть имени для поиска во вложенных папках");return;}
        if(pane.loading!=null)pane.loading.cancel(true);long generation=++pane.generation;FileLocation src=pane.location;Context app=getApplicationContext();pane.summary.setText("Поиск во вложенных папках…");
        pane.loading=reads.submit(()->{try{List<FileEntry> result=FileOperations.search(src.backend(app),src.path,query,new FileOperations.Control(null));main.post(()->{if(!isDestroyed()&&generation==pane.generation){pane.searchResults=true;pane.entries=result;pane.selected.clear();render(pane);}});}catch(Exception e){main.post(()->{if(!isDestroyed()&&generation==pane.generation)pane.summary.setText(FileTaskService.safeMessage(e));});}});
    }
    private void places(Pane pane) {
        List<String> labels=new ArrayList<>(Arrays.asList("Память устройства","Загрузки","Яндекс Диск","Открыть публичную ссылку","Подключить USB / SD через систему","Добавить эту папку в закладки"));
        List<FileLocation> extra=new ArrayList<>();
        File storage=new File("/storage");File[] volumes=storage.listFiles();if(volumes!=null)for(File volume:volumes)if(volume.isDirectory()&&!Arrays.asList("emulated","self").contains(volume.getName()))try{extra.add(new FileLocation("local",volume.getAbsolutePath(),"/","USB / SD · "+volume.getName()));}catch(Exception ignored){}
        for(android.content.UriPermission grant:getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&DocumentsContract.isTreeUri(grant.getUri()))try{extra.add(new FileLocation("tree",grant.getUri().toString(),"/","Подключённый накопитель"));}catch(Exception ignored){}
        for(String raw:new TreeSet<>(settings.getStringSet("bookmarks",Collections.emptySet())))try{extra.add(FileLocation.decode(raw));}catch(Exception ignored){}
        for(FileLocation l:extra)labels.add(l.label+" · "+l.path);
        new AlertDialog.Builder(this).setTitle("Места · панель "+(pane.index+1)).setItems(labels.toArray(new String[0]),(d,which)->{try{
            switch(which){
                case 0:setLocation(pane,new FileLocation("local",Environment.getExternalStorageDirectory().getAbsolutePath(),"/","Устройство"));break;
                case 1:setLocation(pane,new FileLocation("local",Environment.getExternalStorageDirectory().getAbsolutePath(),"/Download","Загрузки"));break;
                case 2:setLocation(pane,new FileLocation("disk","","/","Яндекс Диск"));break;
                case 3:input("Публичная ссылка Яндекс Диска","",value->setLocation(pane,FileLocation.publicLink(value)));break;
                case 4:treePane=pane.index;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),TREE);}catch(ActivityNotFoundException e){message("Системный выбор папок отсутствует. Выберите USB / SD в списке мест.");}break;
                case 5:input("Название закладки",pane.location.label,name->{Set<String> bookmarks=new LinkedHashSet<>(settings.getStringSet("bookmarks",Collections.emptySet()));bookmarks.add(new FileLocation(pane.location.kind,pane.location.root,pane.location.path,name).encode());settings.edit().putStringSet("bookmarks",bookmarks).apply();message("Закладка сохранена");});break;
                default:setLocation(pane,extra.get(which-6));
            }
        }catch(Exception e){error(e);}}).show();
    }
    private void more(){String[] items={"Сортировать по имени","Сначала новые","Сначала большие",showHidden?"Скрыть скрытые файлы":"Показать скрытые файлы","Открыть / поделиться выбранным","Свойства выбранного","Удалить закладку","Отключить Яндекс Диск"};
        new AlertDialog.Builder(this).setTitle("Файлы").setItems(items,(d,index)->{
            if(index<=2){sort=index;settings.edit().putInt("sort",sort).apply();for(Pane p:panes)render(p);}
            else if(index==3){showHidden=!showHidden;settings.edit().putBoolean("showHidden",showHidden).apply();for(Pane p:panes)render(p);}
            else if(index==4){List<FileEntry> list=requireSelection();if(list.size()==1&&!list.get(0).directory)new AlertDialog.Builder(this).setItems(new String[]{"Открыть","Поделиться"},(a,b)->openEntry(current(),list.get(0),b==1)).show();}
            else if(index==5){List<FileEntry> selected=requireSelection();if(selected.isEmpty())return;long size=0;for(FileEntry e:selected)if(!e.directory&&e.size>0)size+=e.size;new AlertDialog.Builder(this).setTitle("Свойства").setMessage(selected.size()+" объектов\nФайлы: "+Formatter.formatFileSize(this,size)+"\n"+current().location.label+"\n"+selected.get(0).path).setPositiveButton("Закрыть",null).show();}
            else if(index==6){List<String> values=new ArrayList<>(settings.getStringSet("bookmarks",Collections.emptySet()));List<String> names=new ArrayList<>();for(String v:values)try{FileLocation l=FileLocation.decode(v);names.add(l.label+" "+l.path);}catch(Exception e){names.add("Недоступная закладка");}new AlertDialog.Builder(this).setTitle("Удалить закладку").setItems(names.toArray(new String[0]),(a,b)->{Set<String> set=new LinkedHashSet<>(values);set.remove(values.get(b));settings.edit().putStringSet("bookmarks",set).apply();}).show();}
            else if(index==7){if(FileTaskService.busy){message("Сначала завершите передачу файлов");return;}new AlertDialog.Builder(this).setTitle("Отключить Яндекс Диск?").setMessage("Локальные файлы останутся. Для следующего подключения потребуется вход.").setNegativeButton("Отмена",null).setPositiveButton("Отключить",(a,b)->{try{YandexDiskSession.disconnect(this);YandexDiskLoginActivity.clearWebSession();reloadBoth();}catch(Exception e){error(e);}}).show();}
        }).show();
    }
    private void archives(){
        List<FileEntry> selected=requireSelection();if(selected.isEmpty())return;
        FileLocation source=current().location,destination=panes[1-active].location;Context app=getApplicationContext();
        new AlertDialog.Builder(this).setTitle("ZIP · назначение: "+destination.label+destination.path)
                .setItems(new String[]{"Создать ZIP из выбранного","Распаковать выбранный ZIP"},(dialog,choice)->{
            if(choice==0)input("Имя архива","Архив.zip",name->{FilePaths.name(name);String fileName=name.toLowerCase(Locale.ROOT).endsWith(".zip")?name:name+".zip";
                startTask("Создание ZIP",c->{String path=FileArchives.create(source.backend(app),selected,destination.backend(app),destination.path,fileName,app.getCacheDir(),c);return "ZIP сохранён: "+path;});
            });
            else if(selected.size()!=1||selected.get(0).directory)message("Выберите один ZIP-файл");
            else startTask("Распаковка ZIP",c->{String path=FileArchives.extract(source.backend(app),selected.get(0),destination.backend(app),destination.path,app.getCacheDir(),c);return "Распаковано: "+path;});
        }).show();
    }
    private void openEntry(Pane pane,FileEntry entry,boolean share) {
        FileLocation location=pane.location;Context app=getApplicationContext();
        if(location.kind.equals("local")){try{openLocal(new LocalFiles(new File(location.root)).resolve(entry.path),share);}catch(Exception e){error(e);}return;}
        if(location.kind.equals("tree")){reads.submit(()->{try{Uri uri=new DocumentFiles(app.getContentResolver(),Uri.parse(location.root)).uri(entry.path);main.post(()->openUri(uri,entry.name,share));}catch(Exception e){main.post(()->error(e));}});return;}
        if(entry.directory)return;
        new AlertDialog.Builder(this).setTitle("Скачать файл для открытия?").setMessage(entry.name+"\nФайл будет сохранён в «Загрузки/Natro».").setNegativeButton("Отмена",null).setPositiveButton("Скачать",(d,w)->{
            // Only the explicit foreground screen may open a downloaded file; never background-install APKs.
            startTask("Скачивание",c->{File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Natro");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось создать папку загрузки");LocalFiles target=new LocalFiles(folder);
                String path=FileOperations.copy(location.backend(app),entry.path,target,"/",entry.name,false,c);File file=target.resolve(path);main.post(()->{if(!isDestroyed()&&!isFinishing())openLocal(file,share);});return "Скачано: "+file.getName();});
        }).show();
    }
    private void openLocal(File file,boolean share){try{openUri(FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file),file.getName(),share);}catch(Exception e){error(e);}}
    private void openUri(Uri uri,String name,boolean share){if(!resumed||isDestroyed()||isFinishing())return;String ext=name.lastIndexOf('.')>=0?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        String mime=ext.equals("apk")?"application/vnd.android.package-archive":MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);if(mime==null)mime="application/octet-stream";
        Intent intent=share?new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri):new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime);
        intent.setClipData(ClipData.newRawUri(name,uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(share?Intent.createChooser(intent,"Поделиться файлом"):intent);}catch(ActivityNotFoundException e){message("Нет приложения для открытия этого типа файла");}
    }
    private interface Entered {void accept(String text)throws Exception;}
    private void input(String title,String value,Entered accept){EditText field=new EditText(this);field.setText(value);field.setSingleLine();field.setSelectAllOnFocus(true);field.setPadding(dp(24),dp(12),dp(24),dp(12));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("Отмена",null).setPositiveButton("Готово",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{accept.accept(field.getText().toString());dialog.dismiss();}catch(Exception e){field.setError(FileTaskService.safeMessage(e));}}));dialog.show();}
    private void message(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void error(Exception e){if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Не удалось выполнить").setMessage(FileTaskService.safeMessage(e)).setPositiveButton("Закрыть",null).show();}
    @Override protected void onResume(){super.onResume();resumed=true;main.post(poll);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(poll);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);for(Pane p:panes)state.putString("pane"+p.index,p.location.encode());state.putInt("active",active);}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);reads.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] result){super.onRequestPermissionsResult(code,permissions,result);if(code==STORAGE)reloadBoth();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(result!=RESULT_OK)return;
        if(request==LOGIN){try{setLocation(current(),new FileLocation("disk","","/","Яндекс Диск"));}catch(Exception e){error(e);}}
        else if(request==TREE&&data!=null&&data.getData()!=null){try{Uri uri=data.getData();getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));setLocation(panes[treePane],new FileLocation("tree",uri.toString(),"/","USB / SD"));}catch(Exception e){error(e);}}
    }
}
