/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.media.MediaButtonController;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Physical MEDIA controls are independent of the visual media-panel configuration. */
public final class MediaButtonsSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MediaButtonController controller = MediaButtonController.get(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 18, 24, 24);
        content.setBackgroundColor(0xFF0B0D12);
        Button back = new Button(this); back.setText("← Назад");
        back.setOnClickListener(v -> finish()); content.addView(back);
        TextView title = text("Кнопки руля · MEDIA", 24); content.addView(title);
        content.addView(text("Отключите обработку MEDIA в MConfig перед включением здесь. "
                + "Кнопкой управляется плеер, выбранный системой Android.", 16));
        Switch enabled = toggle("Включить", controller.isEnabled());
        enabled.setOnCheckedChangeListener((button, value) -> controller.setEnabled(value));
        content.addView(enabled);
        Switch disableDefault = toggle("Отключить действие по умолчанию", controller.isDisableDefault());
        TextView status = text(controller.status(), 15);
        boolean[] updating = {false};
        disableDefault.setOnCheckedChangeListener((button, value) -> {
            if (updating[0]) return;
            disableDefault.setEnabled(false);
            status.setText("Применение…");
            controller.setDisableDefault(value, (success, detail) -> {
                if (isDestroyed()) return;
                updating[0] = true;
                disableDefault.setChecked(controller.isDisableDefault());
                updating[0] = false;
                disableDefault.setEnabled(true);
                status.setText(detail);
            });
        });
        content.addView(disableDefault);
        content.addView(status);
        content.addView(text("Этот переключатель меняет штатную доставку кнопок. "
                + "Встроенный ADB применит настройку и перезапустит службу ввода. "
                + "При выключении восстанавливается штатный путь.", 15));
        Switch source = toggle("Штатный источник SRC", controller.isDefaultSource());
        source.setOnCheckedChangeListener((button, value) -> controller.setDefaultSource(value));
        content.addView(source);
        content.addView(text("Оставьте выключенным для обычного управления Android-плеером. "
                + "Включение использует штатную рассылку кнопок, как режим другого источника SRC в MConfig.", 15));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        setContentView(scroll);
        SettingsBackNavigation.applySafeTopInset(this, content);
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.WHITE); view.setPadding(0, 14, 0, 14); return view;
    }
    private Switch toggle(String title, boolean value) {
        Switch view = new Switch(this); view.setText(title); view.setTextColor(Color.WHITE);
        view.setTextSize(18); view.setMinHeight(64); view.setChecked(value);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
}
