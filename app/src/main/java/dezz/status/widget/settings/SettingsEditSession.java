/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dezz.status.widget.WidgetService;
import dezz.status.widget.hud.HudPresentationService;
import dezz.status.widget.instrument.InstrumentDisplayLauncher;
import dezz.status.widget.instrument.InstrumentPanelStore;
import java.util.*;

/** Explicit Apply/Cancel, including nested editors and the late onStop autosave callbacks. */
public final class SettingsEditSession {
    private static final String EXTRA_PARENT="dezz.status.widget.SETTINGS_DRAFT_PARENT";
    private static final Map<Context,SettingsEditSession> activities=new WeakHashMap<>();
    private static final SettingsEditSession detachedSession=new SettingsEditSession(null);
    static { detachedSession.closed=true; }
    static final Object NO_OVERRIDE=new Object();
    private static final List<SettingsEditSession> active=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Set<String> EDITORS=new HashSet<>(Arrays.asList(
            "LauncherSettingsActivity","PassengerLauncherSettingsActivity","LauncherShortcutSettingsActivity",
            "DriverPanelSettingsActivity","PassengerPanelSettingsActivity","DriverFavoritesSettingsActivity",
            "FavoriteAppsSettingsActivity","PassengerFavoritesSettingsActivity","AllAppsSettingsActivity",
            "PassengerAllAppsSettingsActivity","ClimatePanelSettingsActivity","VehicleInfoPanelSettingsActivity",
            "HudPanelSettingsActivity","InstrumentPanelSettingsActivity","DimMenuPanelSettingsActivity",
            "SystemShadeSettingsActivity","SystemShadeEditorActivity","NavigatorWindowSettingsActivity",
            "InformationPanelSettingsActivity","MediaPanelSettingsActivity","FavoriteRoutesSettingsActivity",
            "NavigationPanelSettingsActivity","PanelElementSettingsActivity","PhoneNotificationLayoutEditorActivity",
            "VisualBrickEditorActivity","PopupSettingsActivity"));
    private static final Set<String> NO_LIVE_PREVIEW=new HashSet<>(Arrays.asList(
            "MqttSettingsActivity","HomeAssistantSettingsActivity","SprutHubSettingsActivity",
            "PhoneConnectorSettingsActivity","LiveActivityApnsSettingsActivity",
            "MediaButtonsSettingsActivity","ScenarioSettingsActivity","IntentScenarioSettingsActivity",
            "PhoneNotificationAutomationSettingsActivity"));
    private final String id=UUID.randomUUID().toString();
    private final Map<SharedPreferences,SettingsDraft> drafts=new java.util.concurrent.ConcurrentHashMap<>();
    private final SettingsEditSession parent;
    private boolean livePreview=true;
    private boolean startRuntimeAfterApply;
    private final Map<SharedPreferences,Set<String>> previewSuppressed=new HashMap<>();
    private final Map<String,SettingsFileChange> files=new LinkedHashMap<>();
    private String validationError;
    private volatile boolean closed;
    private Runnable flush=()->{};
    private Runnable reload=()->{};
    private int generation;
    private long revision;
    private boolean primed;
    private final Map<SharedPreferences,Map<String,Object>> initialChanges=new HashMap<>();
    private final Map<String,Runnable> applyActions=new LinkedHashMap<>();
    private final Map<String,Object> actionValues=new LinkedHashMap<>();
    private TextView state;
    private java.lang.ref.WeakReference<Context> boundActivity = new java.lang.ref.WeakReference<>(null);

    private SettingsEditSession(SettingsEditSession parent){this.parent=parent;livePreview=parent==null||parent.livePreview;}
    public static synchronized SettingsEditSession begin(Activity activity, Object retained) {
        if(!EDITORS.contains(activity.getClass().getSimpleName())&&!NO_LIVE_PREVIEW.contains(activity.getClass().getSimpleName())
                &&!"AutomationSettingsActivity".equals(activity.getClass().getSimpleName()))return null;
        return beginEditor(activity,retained);
    }
    /** HOME starts a draft only for an explicitly requested editing mode. */
    public static synchronized SettingsEditSession beginEditor(Activity activity,Object retained) {
        return beginEditor(activity,activity.getIntent(),retained);
    }
    public static synchronized SettingsEditSession beginEditor(Context activity,Intent intent,Object retained) {
        SettingsEditSession session=retained instanceof SettingsEditSession?(SettingsEditSession)retained:null;
        if(session!=null&&session.closed)session=null;
        if(session==null){
            String parentId=intent==null?null:intent.getStringExtra(EXTRA_PARENT);
            SettingsEditSession parent=null;
            for(SettingsEditSession candidate:active)if(candidate.id.equals(parentId)&&!candidate.closed)parent=candidate;
            session=new SettingsEditSession(parent);active.add(session);
        }
        if(NO_LIVE_PREVIEW.contains(activity.getClass().getSimpleName())){
            session.livePreview=false;session.startRuntimeAfterApply=true;
        }
        session.boundActivity = new java.lang.ref.WeakReference<>(activity);
        activities.put(activity,session);return session;
    }
    /** Release the old view tree even when the draft survives a configuration change.
     * WeakHashMap alone is insufficient: its value's listeners can retain the key. */
    public static void detach(Context activity) {
        SettingsEditSession session;
        synchronized (SettingsEditSession.class) {
            session = activities.get(activity);
            // A weak, view-free tombstone also blocks a delayed callback constructing a NEW
            // Preferences wrapper from the destroyed Activity. It cannot fall through to disk.
            if(session!=null)activities.put(activity,detachedSession);
        }
        if (session == null || session == detachedSession) return;
        synchronized (session) {
            if (session.boundActivity.get() == activity) {
                session.generation++;
                session.releaseViews();
            }
        }
    }
    private void releaseViews() {
        state = null;
        flush = () -> {};
        reload = () -> {};
        boundActivity.clear();
        validationError=null;
    }
    public static synchronized SettingsEditSession find(Context context) {
        while(context!=null){
            if(activities.containsKey(context))return activities.get(context);
            if(context instanceof Activity)return null;
            if(!(context instanceof ContextWrapper))return null;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)return null;context=next;
        }
        return null;
    }
    public static void carry(Context context,Intent intent) {
        SettingsEditSession session=find(context);
        if(session!=null&&!session.closed&&intent.getComponent()!=null
                &&context.getPackageName().equals(intent.getComponent().getPackageName()))intent.putExtra(EXTRA_PARENT,session.id);
    }
    static Map<String,?> preview(SharedPreferences store) {
        Map<String,?> result=store.getAll();
        for(SettingsEditSession session:active){
            SettingsDraft draft=session.drafts.get(store);
            if(!session.closed&&session.livePreview&&draft!=null){
                Map<String,Object> next=new LinkedHashMap<>(result);
                for(Map.Entry<String,Object> value:draft.changes().entrySet())if(session.canPreview(store,value.getKey())){
                    if(value.getValue()==null)next.remove(value.getKey());else next.put(value.getKey(),value.getValue());
                }
                result=next;
            }
        }
        return result;
    }
    static Object previewValue(SharedPreferences store,String key) {
        if(!previewEligible(key))return NO_OVERRIDE;
        Object result=NO_OVERRIDE;
        for(SettingsEditSession session:active){
            SettingsDraft draft=session.drafts.get(store);
            if(!session.closed&&session.livePreview&&session.canPreview(store,key)&&draft!=null&&draft.contains(key))result=draft.value(key);
        }
        return result;
    }
    Object value(SharedPreferences store,String key) {
        SettingsDraft draft=drafts.get(store);
        if(!closed&&draft!=null&&draft.contains(key))return draft.value(key);
        return parent==null?NO_OVERRIDE:parent.value(store,key);
    }
    synchronized Map<String,?> read(SharedPreferences store) {
        Map<String,?> original=parent==null?store.getAll():parent.read(store);
        SettingsDraft draft=drafts.get(store);return closed||draft==null?original:draft.overlay(original);
    }
    synchronized int generation() { return generation; }
    public synchronized long revision() { return revision; }
    public synchronized boolean isClosed() { return closed; }
    public boolean isNested(){return parent!=null;}
    /** A resource handle has the same lifetime fence as preference controls. */
    public static FileDraft files(Context context){return new FileDraft(context);}
    public static final class FileDraft {
        private final Context context;
        private final SettingsEditSession session;
        private final int generation;
        private FileDraft(Context owner){context=owner.getApplicationContext();session=find(owner);generation=session==null?0:session.generation();}
        public boolean isDraft(){return session!=null;}
        public boolean isCurrent(){return session==null||(!session.closed&&session.generation()==generation);}
        public byte[] read(String path)throws java.io.IOException{
            return session==null?SettingsFileChange.read(context,path):session.readFile(context,path);
        }
        public void stage(String path,byte[] bytes)throws java.io.IOException{
            if(session==null)throw new java.io.IOException("Resource requires an editor");
            synchronized(session){
                if(!isCurrent())throw new java.io.IOException("Редактор уже закрыт или обновлён");
                session.stageFile(context,path,bytes);
            }
        }
    }
    private synchronized byte[] readFile(Context context,String path)throws java.io.IOException{
        SettingsFileChange change=closed?null:files.get(path);
        if(change!=null)return change.after==null?null:change.after.clone();
        return parent==null?SettingsFileChange.read(context,path):parent.readFile(context,path);
    }
    private synchronized void stageFile(Context context,String path,byte[] bytes)throws java.io.IOException{
        SettingsFileChange.resolve(context,path);
        SettingsFileChange previous=files.get(path);
        files.put(path,new SettingsFileChange(path,previous==null?readFile(context,path):previous.before,bytes));
        revision++;updateState();
    }
    public synchronized void invalidateOldControls() { generation++; }
    synchronized void write(SharedPreferences store,Map<String,Object> edits,boolean clear,int generation) {
        if(generation!=this.generation)return;
        write(store,edits,clear);
    }
    synchronized void write(SharedPreferences store,Map<String,Object> edits,boolean clear) {
        if(closed)return;
        SettingsDraft draft=drafts.get(store);
        if(draft==null){draft=new SettingsDraft();drafts.put(store,draft);}
        Map<String,?> original=parent==null?store.getAll():parent.read(store);
        if(clear)for(String key:read(store).keySet())draft.put(key,null,original);
        for(Map.Entry<String,Object> entry:edits.entrySet())draft.put(entry.getKey(),entry.getValue(),original);
        revision++;
        updateState();
    }
    public synchronized boolean dirty(){
        if(validationError!=null)return true;
        if(!applyActions.isEmpty())return true;
        for(SettingsFileChange file:files.values())if(file.dirty())return true;
        for(Map.Entry<SharedPreferences,SettingsDraft> entry:drafts.entrySet()){
            Map<String,Object> initial=initialChanges.get(entry.getKey());
            if(initial==null)initial=Collections.emptyMap();
            if(!entry.getValue().changes().equals(initial))return true;
        }
        return false;
    }
    public synchronized boolean apply(Context activity) {
        if(closed)return true;
        if(!flushChanges()){Toast.makeText(activity,validationError,Toast.LENGTH_LONG).show();return false;}
        if(!dirty())return true;
        if(parent!=null&&parent.closed){
            Toast.makeText(activity,"Родительский редактор уже закрыт. Откройте настройки заново.",Toast.LENGTH_LONG).show();return false;
        }
        try{
            for(SettingsFileChange file:files.values())if(file.dirty()){
                byte[] current=parent==null?SettingsFileChange.read(activity,file.path):parent.readFile(activity,file.path);
                if(!Arrays.equals(current,file.before))throw new java.io.IOException("Файл изменён в другом редакторе. Откройте настройки заново.");
            }
        }catch(java.io.IOException conflict){Toast.makeText(activity,conflict.getMessage(),Toast.LENGTH_LONG).show();return false;}
        for(Map.Entry<SharedPreferences,SettingsDraft> entry:drafts.entrySet()){
            Map<String,?> current=parent==null?entry.getKey().getAll():parent.read(entry.getKey());
            if(!entry.getValue().conflicts(current).isEmpty()){
                new SettingsDialogBuilder(activity).setTitle("Настройки изменились")
                        .setMessage("Эти параметры изменены в другом окне. Закройте черновик через «Отмена» и откройте редактор заново, чтобы не потерять изменения.")
                        .setPositiveButton("Продолжить редактирование",null).show();return false;
            }
        }
        Map<SharedPreferences,Map<String,Object>> changes=new LinkedHashMap<>();
        // Preflight every resource before merging any participant into the parent.
        if(parent!=null)try{
            for(SettingsFileChange file:files.values())if(file.dirty())parent.stageFile(activity,file.path,file.after);
        }catch(java.io.IOException invalid){Toast.makeText(activity,invalid.getMessage(),Toast.LENGTH_LONG).show();return false;}
        for(Map.Entry<SharedPreferences,SettingsDraft> entry:drafts.entrySet()){
            if(!entry.getValue().dirty())continue;
            if(parent!=null){
                Set<String> hidden=previewSuppressed.get(entry.getKey());
                if(!livePreview||hidden!=null)parent.previewSuppressed.computeIfAbsent(entry.getKey(),ignored->new HashSet<>())
                        .addAll(!livePreview?entry.getValue().changes().keySet():hidden);
                parent.write(entry.getKey(),entry.getValue().changes(),false);continue;
            }
            changes.put(entry.getKey(),entry.getValue().changes());
        }
        if(parent==null&&!SettingsApplyJournal.commit(activity,changes,files)){
            Toast.makeText(activity,"Не удалось сохранить. Черновик остаётся открыт; повторите после завершения обслуживания.",Toast.LENGTH_LONG).show();return false;
        }
        for(SettingsDraft draft:drafts.values())draft.applied();initialChanges.clear();
        files.clear();
        Map<String,Runnable> commands=new LinkedHashMap<>(applyActions);applyActions.clear();
        if(parent!=null){parent.applyActions.putAll(commands);parent.actionValues.putAll(actionValues);
            parent.startRuntimeAfterApply|=startRuntimeAfterApply;if(!commands.isEmpty())parent.revision++;}
        else for(Runnable command:commands.values())try{command.run();}catch(RuntimeException failure){
            android.util.Log.e("SettingsApply","Explicit operation failed",failure);
            Toast.makeText(activity,"Настройки сохранены, но операция не выполнена. Проверьте её состояние.",Toast.LENGTH_LONG).show();
        }
        actionValues.clear();
        updateState();if(parent==null||livePreview)refresh(activity);
        if(parent==null&&startRuntimeAfterApply&&WidgetService.getInstance()==null)
            dezz.status.widget.WidgetServiceStarter.startIfNeeded(activity.getApplicationContext());
        return true;
    }
    public synchronized void cancel(Context activity){
        if(closed)return;boolean changed=dirty();closed=true;for(SettingsDraft draft:drafts.values())draft.close();applyActions.clear();actionValues.clear();files.clear();
        active.remove(this);
        try { if(changed&&livePreview)refresh(activity); } finally { releaseViews(); }
    }
    public void bind(Runnable flush,Runnable reload) {
        this.flush=flush;this.reload=reload;
        if(!primed){
            flushChanges();
            for(Map.Entry<SharedPreferences,SettingsDraft> entry:drafts.entrySet())initialChanges.put(entry.getKey(),entry.getValue().changes());
            primed=true;
        }
    }
    public void install(Activity activity,Runnable flush,Runnable reload,Runnable finish) {
        bind(flush,reload);
        ViewGroup content=activity.findViewById(android.R.id.content);
        if(content==null||content.getChildCount()==0)return;
        if("natro.settings.draft".equals(content.getChildAt(0).getTag()))return;
        View original=content.getChildAt(0);ViewGroup.LayoutParams old=original.getLayoutParams();
        content.removeView(original);
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setTag("natro.settings.draft");
        column.addView(original,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);
        int padding=Math.round(10*activity.getResources().getDisplayMetrics().density);
        footer.setPadding(padding,padding/2,padding,padding/2);
        state=new TextView(activity);state.setTextSize(18);footer.addView(state,new LinearLayout.LayoutParams(0,-2,1));
        Button cancel=new Button(activity);cancel.setAllCaps(false);cancel.setText("Отмена");cancel.setTextSize(20);
        cancel.setOnClickListener(v->{cancel(activity);finish.run();});footer.addView(cancel);
        Button apply=new Button(activity);apply.setAllCaps(false);apply.setText("Применить");apply.setTextSize(20);
        apply.setOnClickListener(v->{if(apply(activity)){cancel(activity);finish.run();}});footer.addView(apply);
        column.addView(footer,new LinearLayout.LayoutParams(-1,-2));content.addView(column,0,old);updateState();
    }
    /** Invalid form input must not crash pause/back or silently commit the previous value. */
    public boolean flushChanges(){
        if(closed)return true;
        try{flush.run();validationError=null;return true;}
        catch(RuntimeException invalid){
            String message=invalid.getMessage();validationError=message==null||message.trim().isEmpty()
                    ?"Проверьте введённые значения":message;
            return false;
        }finally{updateState();}
    }
    public Savepoint checkpoint(){flushChanges();return new Savepoint(this);}
    /** Explicit external operations are never run by preview or by a nested dialog's Apply. */
    public static void afterApply(Activity activity,String key,Runnable command){
        afterApply(activity,key,null,command);
    }
    public static void afterApply(Activity activity,String key,Object value,Runnable command){
        SettingsEditSession session=find(activity);
        if(session==null){command.run();return;}
        synchronized(session){if(!session.closed){session.applyActions.put(key,command);session.actionValues.put(key,value);session.revision++;session.updateState();}}
    }
    public static boolean pendingBoolean(Activity activity,String key,boolean fallback){
        SettingsEditSession session=find(activity);
        while(session!=null){Object value=session.actionValues.get(key);if(value instanceof Boolean)return(Boolean)value;session=session.parent;}
        return fallback;
    }
    public static Set<String> pendingKeys(Activity activity,String key){
        Set<String> result=new HashSet<>();SettingsEditSession session=find(activity);
        while(session!=null){Object value=session.actionValues.get(key);if(value instanceof Set)
            for(Object item:(Set<?>)value)if(item instanceof String)result.add((String)item);
            session=session.parent;}
        return result;
    }
    private static boolean previewEligible(String key){
        return !key.equals("passengerLauncherAutoStart")
                &&!key.startsWith("hudStock")&&!key.startsWith("hide_oem_")&&!key.startsWith("oem_");
    }
    private boolean canPreview(SharedPreferences store,String key){
        Set<String> hidden=previewSuppressed.get(store);return previewEligible(key)&&(hidden==null||!hidden.contains(key));
    }
    public static final class Savepoint {
        private final SettingsEditSession session;
        private final Map<SharedPreferences,Map<String,Object>> changes=new HashMap<>();
        private final Map<SharedPreferences,Map<String,?>> values=new HashMap<>();
        private final Map<String,Runnable> actions;
        private final Map<String,Object> actionValues;
        private final Map<String,SettingsFileChange> files;
        private final Map<SharedPreferences,Set<String>> suppressed=new HashMap<>();
        private boolean accepted,finished;
        private Savepoint(SettingsEditSession session){
            this.session=session;
            actions=new LinkedHashMap<>(session.applyActions);
            actionValues=new LinkedHashMap<>(session.actionValues);
            files=new LinkedHashMap<>(session.files);
            session.previewSuppressed.forEach((store,keys)->suppressed.put(store,new HashSet<>(keys)));
            for(Map.Entry<SharedPreferences,SettingsDraft> entry:session.drafts.entrySet()){
                changes.put(entry.getKey(),entry.getValue().changes());values.put(entry.getKey(),session.read(entry.getKey()));
            }
        }
        public void accept(){accepted=true;}
        public void finish(){
            if(finished)return;finished=true;if(accepted||session.closed)return;
            boolean changed=!actions.equals(session.applyActions);
            changed|=!files.equals(session.files);
            session.files.clear();session.files.putAll(files);
            session.previewSuppressed.clear();suppressed.forEach((store,keys)->session.previewSuppressed.put(store,new HashSet<>(keys)));
            session.applyActions.clear();session.applyActions.putAll(actions);
            session.actionValues.clear();session.actionValues.putAll(actionValues);
            for(Map.Entry<SharedPreferences,SettingsDraft> entry:session.drafts.entrySet()){
                Map<String,Object> before=changes.get(entry.getKey());if(before==null)before=Collections.emptyMap();
                Map<String,Object> after=entry.getValue().changes();
                Set<String> keys=new HashSet<>(before.keySet());keys.addAll(after.keySet());
                Map<String,Object> restore=new LinkedHashMap<>();
                Map<String,?> original=values.get(entry.getKey());
                if(original==null)original=entry.getValue().original();
                for(String key:keys)if(before.containsKey(key)!=after.containsKey(key)||!Objects.equals(before.get(key),after.get(key)))
                    restore.put(key,original.get(key));
                if(!restore.isEmpty()){session.write(entry.getKey(),restore,false);changed=true;}
            }
            if(changed){
                // Old controls may still have queued debounced writes while Android recreates
                // the view. Their wrappers cannot overwrite the restored checkpoint.
                session.generation++;session.reload.run();
            }
        }
    }
    public boolean requestFinish(Context activity,Runnable finish) {
        if(closed)return false;
        boolean valid=flushChanges();
        if(valid&&!dirty()){cancel(activity);return false;}
        new SettingsDialogBuilder(activity).setTitle("Сохранить изменения?")
                .setMessage("Предпросмотр ещё не записан в настройки.")
                .setPositiveButton("Применить",(d,w)->{if(apply(activity)){cancel(activity);finish.run();}})
                .setNegativeButton("Отменить изменения",(d,w)->{cancel(activity);finish.run();})
                .setNeutralButton("Продолжить",null).show();return true;
    }
    public static void applyAndFinish(Activity activity) {
        SettingsEditSession session=find(activity);
        if(session==null){activity.finish();return;}
        if(session.apply(activity)){session.cancel(activity);activity.finish();}
    }
    private void updateState(){if(state!=null)state.setText(dirty()?"Предпросмотр · есть изменения":"Изменений нет");}
    private static void refresh(Context activity){
        WidgetService service=WidgetService.getInstance();if(service!=null)service.applyPreferences();
        String owner=activity.getClass().getSimpleName();
        if(owner.equals("HudPanelSettingsActivity"))HudPresentationService.apply(activity.getApplicationContext());
        if(owner.equals("InstrumentPanelSettingsActivity")){
            InstrumentDisplayLauncher.apply(activity.getApplicationContext());
            activity.sendBroadcast(new Intent(InstrumentPanelStore.ACTION_CONFIG_CHANGED).setPackage(activity.getPackageName()));
        }
        if(owner.equals("HudPanelSettingsActivity")||owner.equals("InstrumentPanelSettingsActivity")
                ||owner.equals("NavigatorWindowSettingsActivity")||owner.equals("NavigationPanelSettingsActivity"))
            dezz.status.widget.navigation.NavigationHudEndpointService.requestConfigurationRefresh(activity.getApplicationContext());
        if(owner.equals("DriverPanelSettingsActivity")||owner.equals("DriverFavoritesSettingsActivity"))
            dezz.status.widget.driver.DriverPanelService.apply(activity.getApplicationContext());
        if(owner.equals("PassengerPanelSettingsActivity")||owner.equals("PassengerFavoritesSettingsActivity"))
            dezz.status.widget.driver.PassengerPanelService.apply(activity.getApplicationContext());
        if(owner.startsWith("SystemShade"))dezz.status.widget.shade.SystemShadeService.reconcile(activity.getApplicationContext(),false);
    }
}
