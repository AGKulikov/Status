/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;
import android.app.AlertDialog;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.*;
/** A live six-section index of the current editor, retaining its preview and save/cancel owners. */
public final class SettingsSections {
    private static final String[] NAMES={"Основное","Состав","Оформление","Положение","Поведение","Дополнительно"};
    private SettingsSections(){}
    public static void show(AppCompatActivity activity,View content) {
        Map<Integer,List<String>> groups=new TreeMap<>();collect(content,groups,new HashSet<>());
        LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(16,8,16,8);
        ScrollView scroll=new ScrollView(activity);scroll.addView(page);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Разделы редактора").setView(scroll).setNegativeButton("Закрыть",null).create();
        for(int index=0;index<NAMES.length;index++) {
            List<String> labels=groups.get(index);if(labels==null||labels.isEmpty())continue;
            TextView heading=new TextView(activity);heading.setText(NAMES[index]);heading.setTextSize(24);heading.setPadding(8,20,8,8);page.addView(heading);
            for(String label:labels){Button button=new Button(activity);button.setAllCaps(false);button.setText(label);button.setTextSize(19);page.addView(button);button.setOnClickListener(v->{dialog.dismiss();SettingsAppearance.focus(content,label);});}
        }
        dialog.show();SettingsAppearance.apply(activity,scroll);
    }
    private static void collect(View view,Map<Integer,List<String>> result,Set<String> seen) {
        if(view.getVisibility()!=View.VISIBLE)return;
        String name=view.getClass().getName();if(name.startsWith("dezz.")&&!name.contains("Settings"))return;
        if(view instanceof TextView) {
            String label=((TextView)view).getText().toString().trim();
            if(label.length()>=4&&label.length()<=110&&!label.contains("\n")&&!Arrays.asList("Назад","Готово","Отмена").contains(label)&&seen.add(label))result.computeIfAbsent(section(label),ignored->new ArrayList<>()).add(label);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),result,seen);}
    }
    static int section(String text) {
        String value=text.toLowerCase(Locale.ROOT);
        if(value.matches(".*(цвет|шрифт|фон|прозрач|скругл|контур|тень|оформлен|жирн|курсив).*"))return 2;
        if(value.matches(".*(положен|ширин|высот|отступ|размер|масштаб|координат|сетка|строк|столб).*"))return 3;
        if(value.matches(".*(действ|нажат|авто|тайм|задерж|поведен|скрыва|жест).*"))return 4;
        if(value.matches(".*(элемент|добав|удал|состав|порядок|иконк|показыва).*"))return 1;
        if(value.matches(".*(отлад|диагност|сброс|токен|порт|экспорт|импорт|дополн).*"))return 5;
        return 0;
    }
}
