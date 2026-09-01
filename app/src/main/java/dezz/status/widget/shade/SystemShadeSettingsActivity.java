/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.Preferences;
import dezz.status.widget.LauncherShortcutSettingsActivity;

/** User-facing enable/safety/geometry entry point for the replacement system shade. */
public final class SystemShadeSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private SystemShadeStore store;
    private LinearLayout content;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        preferences = new Preferences(this);
        store = new SystemShadeStore(preferences);
        setTitle("Системная шторка Natro");
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(22), dp(28), dp(42));
        scroll.addView(content);
        setContentView(scroll);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        SystemShadeService.reconcile(this, false);
    }

    private void build() {
        content.removeAllViews();
        addButton("←  Назад", view -> finish());
        title("Системная шторка Natro", 28);
        note("Независимая панель поверх основного дисплея. В закрытом состоянии приложение "
                + "перехватывает только жест от верхнего края; остальной экран работает обычно.");
        MaterialSwitch enabled = toggle("Использовать шторку Natro", store.isEnabled(), checked -> {
            store.setEnabled(checked);
            SystemShadeService.reconcile(this, false);
            if (checked && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        content.addView(enabled);
        content.addView(toggle("Запускать автоматически после загрузки", store.isAutostart(),
                store::setAutostart));

        SystemShadeConfig config = store.load();
        slider("Высота открытой панели", 240, 720, config.panelHeightPx, " px", value -> {
            config.panelHeightPx = value; save(config);
        });
        slider("Зона жеста сверху", 12, 96, config.gestureHandleHeightPx, " px", value -> {
            config.gestureHandleHeightPx = value; save(config);
        });
        slider("Затемнение фона", 0, 90, config.scrimOpacityPercent, "%", value -> {
            config.scrimOpacityPercent = value; save(config);
        });
        slider("Прозрачность панели", 20, 100, config.panelOpacityPercent, "%", value -> {
            config.panelOpacityPercent = value; save(config);
        });
        content.addView(toggle("Закрывать после нажатия кнопки", config.closeAfterAction,
                checked -> { config.closeAfterAction = checked; save(config); }));

        title("Компоновка", 21);
        note("В редакторе каждый элемент можно показать или скрыть, передвинуть, изменить размер, "
                + "шрифт, внутренние отступы, иконки, интервалы и прозрачность.");
        addButton("Открыть живой редактор…", view ->
                startActivity(new Intent(this, SystemShadeEditorActivity.class)));
        addButton("Настроить кнопки и действия…", view -> startActivity(new Intent(
                this, LauncherShortcutSettingsActivity.class).putExtra(
                LauncherShortcutSettingsActivity.EXTRA_SYSTEM_SHADE, true)));
        addButton("Разрешить изменение яркости…", view -> startActivity(new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        title("Штатная шторка ECARX", 21);
        note("Отключение штатной шторки пока намеренно не выполняется целым пакетом. Сначала "
                + "диагностика определит точный компонент ecarx.notificationcenterui; только "
                + "после этого он может быть отключён обратимой командой с сохранением восстановления.");
    }

    private void save(SystemShadeConfig config) {
        store.save(config);
        SystemShadeService.reconcile(this, false);
    }

    private MaterialSwitch toggle(String text, boolean checked, Toggle listener) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(text);
        value.setTextSize(17);
        value.setMinHeight(dp(58));
        value.setChecked(checked);
        value.setOnCheckedChangeListener((button, next) -> listener.changed(next));
        return value;
    }

    private void slider(String label, int minimum, int maximum, int current, String suffix,
                        Value listener) {
        TextView caption = text(label + ": " + current + suffix, 16);
        content.addView(caption);
        SeekBar bar = new SeekBar(this);
        bar.setMax(maximum - minimum);
        bar.setProgress(current - minimum);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean user) {
                if (!user) return;
                int value = minimum + progress;
                caption.setText(label + ": " + value + suffix);
                listener.changed(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));
    }

    private void addButton(String label, View.OnClickListener action) {
        MaterialButton value = new MaterialButton(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
        params.bottomMargin = dp(8);
        content.addView(value, params);
    }

    private void title(String value, int size) {
        TextView label = text(value, size);
        label.setPadding(0, dp(12), 0, dp(6));
        content.addView(label);
    }

    private void note(String value) {
        TextView label = text(value, 15);
        label.setAlpha(.72f);
        label.setPadding(0, 0, 0, dp(12));
        content.addView(label);
    }

    private TextView text(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface Toggle { void changed(boolean value); }
    private interface Value { void changed(int value); }
}
