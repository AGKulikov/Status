/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.lang.reflect.*;
import java.util.*;

/** A search hit opens its actual form, or explicitly asks which existing object to edit. */
public final class SettingsSearchNavigator {
    public static final String EXTRA_ROUTE="dezz.status.widget.SETTINGS_SEARCH_FORM";
    private static final Map<Activity,String> pending=new WeakHashMap<>();
    private SettingsSearchNavigator(){}
    public static void open(Activity activity,View root){
        String query=activity.getIntent().getStringExtra(SettingsAppearance.EXTRA_FOCUS);
        if(query==null||query.isEmpty())return;
        String route=activity.getIntent().getStringExtra(EXTRA_ROUTE);
        activity.getIntent().removeExtra(SettingsAppearance.EXTRA_FOCUS);activity.getIntent().removeExtra(EXTRA_ROUTE);
        if((route==null||route.isEmpty())&&SettingsAppearance.focus(root,query))return;
        Method target=null;SettingsSearchForm form=null;
        for(Class<?> type=activity.getClass();type!=null;type=type.getSuperclass())for(Method method:type.getDeclaredMethods()){
            SettingsSearchForm candidate=method.getAnnotation(SettingsSearchForm.class);if(candidate==null)continue;
            String id=candidate.value().isEmpty()?method.getName():candidate.value();
            if(id.equals(route)){target=method;form=candidate;break;}
        }
        if(target==null){
            if(!SettingsAppearance.focus(root,query))Toast.makeText(activity,"Параметр доступен после выбора элемента в этом редакторе: "+query,Toast.LENGTH_LONG).show();
            return;
        }
        pending.put(activity,query);
        final Method selected=target;final SettingsSearchForm spec=form;
        if(spec.choices().isEmpty()){invoke(activity,selected);return;}
        try{
            Object raw=readPath(activity,spec.choices());if(!(raw instanceof List))throw new IllegalArgumentException("Search choices are not a list");
            List<?> all=(List<?>)raw;List<Object> choices=new ArrayList<>();List<Integer> indices=new ArrayList<>();List<String> labels=new ArrayList<>();
            for(int i=0;i<all.size();i++){
                Object item=all.get(i);if(!spec.kind().isEmpty()&&!spec.kind().equals(String.valueOf(readPath(item,"type"))))continue;
                choices.add(item);indices.add(i);labels.add(label(item,spec.label(),i));
            }
            if(choices.isEmpty()){
                pending.remove(activity);new AlertDialog.Builder(activity).setTitle("Сначала добавьте элемент")
                        .setMessage("Этот параметр относится к отдельному элементу. В редакторе пока нет подходящего элемента: "+query)
                        .setPositiveButton("Понятно",null).show();return;
            }
            java.util.function.IntConsumer show=index->invoke(activity,selected,spec.index()?indices.get(index):choices.get(index));
            if(choices.size()==1){show.accept(0);return;}
            new SettingsDialogBuilder(activity).setTitle("Выберите элемент · "+query)
                    .setItems(labels.toArray(new String[0]),(dialog,which)->show.accept(which))
                    .setNegativeButton("Отмена",(dialog,which)->pending.remove(activity)).show();
        }catch(ReflectiveOperationException|IllegalArgumentException error){failed(activity,error);}
    }
    public static void focusDialog(Context context,AlertDialog dialog){
        Activity activity=activity(context);if(activity==null)return;
        String query=pending.remove(activity);if(query==null)return;
        dialog.getWindow().getDecorView().post(()->{
            SettingsAppearance.focus(dialog.getWindow().getDecorView(),query);
        });
    }
    private static void invoke(Activity activity,Method method,Object... arguments){
        try{method.setAccessible(true);method.invoke(activity,arguments);}
        catch(ReflectiveOperationException|IllegalArgumentException error){failed(activity,error);}
    }
    private static void failed(Activity activity,Exception error){
        pending.remove(activity);android.util.Log.w("SettingsSearch","Could not open indexed form",error);
        Toast.makeText(activity,"Не удалось открыть вложенную форму. Настройки не изменены.",Toast.LENGTH_LONG).show();
    }
    private static Object readPath(Object value,String path)throws ReflectiveOperationException {
        for(String member:path.split("\\.")){
            if(value==null)return null;
            if(member.endsWith("()")){
                String name=member.substring(0,member.length()-2);Method method=value.getClass().getMethod(name);method.setAccessible(true);value=method.invoke(value);
            }else{
                Field field=null;
                for(Class<?> type=value.getClass();type!=null&&field==null;type=type.getSuperclass())try{field=type.getDeclaredField(member);}catch(NoSuchFieldException ignored){}
                if(field==null)throw new NoSuchFieldException(member);field.setAccessible(true);value=field.get(value);
            }
        }
        return value;
    }
    private static String label(Object value,String path,int index){
        if(!path.isEmpty())try{Object label=readPath(value,path);if(label!=null&&!label.toString().isEmpty())return label.toString();}catch(ReflectiveOperationException ignored){}
        for(String field:new String[]{"title","label","name","type.label","id","scenario.id"})try{Object label=readPath(value,field);if(label!=null&&!label.toString().isEmpty())return label.toString();}catch(ReflectiveOperationException ignored){}
        return "Элемент "+(index+1);
    }
    private static Activity activity(Context context){
        while(context instanceof ContextWrapper){if(context instanceof Activity)return(Activity)context;Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;}return null;
    }
}
