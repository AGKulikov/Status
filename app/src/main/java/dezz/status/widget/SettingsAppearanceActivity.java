/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import android.os.Bundle;
import android.widget.*;
import dezz.status.widget.settings.*;
public final class SettingsAppearanceActivity extends SettingsActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Оформление настроек");
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(24,20,24,24);scroll.addView(page);
        TextView title=new TextView(this);title.setText("Оформление настроек");title.setTextSize(28);page.addView(title);
        TextView notice=new TextView(this);notice.setText("Тема и размер текста меняют только меню настроек. Оформление панелей, HUD и лаунчеров задаётся в их редакторах.");page.addView(notice);
        RadioGroup themes=new RadioGroup(this);String[] names={"Как в системе","Светлая","Тёмная"};int[] modes={-1,1,2};
        int selected=SettingsAppearance.preferences(this).getInt("theme",-1);
        for(int i=0;i<names.length;i++){RadioButton option=new RadioButton(this);option.setId(4100+i);option.setText(names[i]);themes.addView(option);if(modes[i]==selected)option.setChecked(true);}page.addView(themes);
        themes.setOnCheckedChangeListener((group,id)->{int index=id-4100;if(index<0||index>2)return;SettingsAppearance.preferences(this).edit().putInt("theme",modes[index]).apply();recreate();});
        TextView size=new TextView(this);size.setText("Размер текста меню");page.addView(size);
        SeekBar slider=new SeekBar(this);slider.setMax(8);slider.setProgress(SettingsAppearance.preferences(this).getInt("textSp",20)-18);page.addView(slider);
        TextView sample=new TextView(this);sample.setText("Основной текст — "+(18+slider.getProgress())+" sp");sample.setTextSize(18+slider.getProgress());page.addView(sample);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){SettingsAppearance.preferences(SettingsAppearanceActivity.this).edit().putInt("textSp",18+s.getProgress()).apply();recreate();}public void onProgressChanged(SeekBar s,int n,boolean user){sample.setText("Основной текст — "+(18+n)+" sp");sample.setTextSize(18+n);}});
        Switch toggle=new Switch(this);toggle.setText("Пример переключателя");page.addView(toggle);
        EditText field=new EditText(this);field.setHint("Пример поля ввода");page.addView(field);
        Button action=new Button(this);action.setText("Пример действия");page.addView(action);
        setContentView(scroll);SettingsBackNavigation.install(this,scroll);
    }
}
