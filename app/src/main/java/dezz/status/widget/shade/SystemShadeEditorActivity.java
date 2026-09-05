/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.LauncherElementFrame;

/** Direct move/resize editor for every shade module. */
public final class SystemShadeEditorActivity extends AppCompatActivity {
    private SystemShadeStore store;
    private SystemShadeConfig config;
    private FrameLayout canvas;
    private LinearLayout controls;
    @Nullable private SystemShadeConfig.Element selected;
    private boolean rebuildingControls;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        store = new SystemShadeStore(new Preferences(this));
        config = store.load();
        setTitle("Компоновка системной шторки");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(12), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(controls);
        root.addView(scroll, new LinearLayout.LayoutParams(dp(430), -1));

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.rgb(4, 6, 10));
        canvas = new FrameLayout(this);
        canvas.setClipChildren(false);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(parse(config.panelColor, Color.rgb(10, 13, 18)));
        canvas.setBackground(panel);
        FrameLayout.LayoutParams canvasParams = new FrameLayout.LayoutParams(
                SystemShadeConfig.LOGICAL_WIDTH, config.panelHeightPx, Gravity.TOP | Gravity.START);
        stage.addView(canvas, canvasParams);
        root.addView(stage, new LinearLayout.LayoutParams(0, -1, 1f));
        setContentView(root);
        buildFrames();
        selected = config.elements.get(0);
        buildControls();
    }

    @Override protected void onPause() {
        store.save(config);
        SystemShadeService.reconcile(this, false);
        super.onPause();
    }

    private void buildFrames() {
        canvas.removeAllViews();
        for (SystemShadeConfig.Element element : config.elements) {
            if (!element.visible) continue;
            LauncherElementFrame frame = new LauncherElementFrame(this, element.kind.id,
                    element.kind.title, (id, x, y, width, height) -> {
                        SystemShadeConfig.Element changed = config.element(
                                SystemShadeConfig.Kind.fromId(id));
                        changed.x = x;
                        changed.y = y;
                        changed.width = width;
                        changed.height = height;
                        config.normalize();
                        store.save(config);
                    });
            TextView preview = new TextView(this);
            preview.setText(previewText(element.kind));
            preview.setTextColor(parse(element.textColor, Color.WHITE));
            preview.setTextSize(element.textSizeSp);
            preview.setGravity(Gravity.CENTER);
            preview.setPadding(element.paddingPx, element.paddingPx,
                    element.paddingPx, element.paddingPx);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(parse(element.backgroundColor, Color.DKGRAY));
            bg.setCornerRadius(element.cornerRadiusPx);
            preview.setBackground(bg);
            frame.setContent(preview);
            frame.setEditMode(true, config.editorSnapPx);
            frame.setMinimumGeometryPx(80, 48);
            frame.setOnClickListener(view -> { selected = element; buildControls(); });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(element.width, element.height);
            params.leftMargin = element.x;
            params.topMargin = element.y;
            canvas.addView(frame, params);
        }
    }

    private void buildControls() {
        if (rebuildingControls) return;
        rebuildingControls = true;
        controls.removeAllViews();
        addButton("←  Готово", view -> finish());
        title("Элементы");
        for (SystemShadeConfig.Element element : config.elements) {
            MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setText(element.kind.title);
            toggle.setChecked(element.visible);
            toggle.setOnClickListener(view -> selected = element);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                element.visible = checked;
                store.save(config);
                buildFrames();
                selected = element;
                buildControls();
            });
            controls.addView(toggle, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        SystemShadeConfig.Element value = selected;
        if (value != null) {
            title("Настройка: " + value.kind.title);
            slider("Шрифт", 10, 120, value.textSizeSp, next -> value.textSizeSp = next);
            slider("Размер иконок", 16, 180, value.iconSizePx, next -> value.iconSizePx = next);
            slider("Внутренний отступ", 0, 100, value.paddingPx, next -> value.paddingPx = next);
            slider("Интервал", 0, 80, value.gapPx, next -> value.gapPx = next);
            slider("Прозрачность", 0, 100, value.opacityPercent,
                    next -> value.opacityPercent = next);
            slider("Скругление", 0, 120, value.cornerRadiusPx,
                    next -> value.cornerRadiusPx = next);
            if (value.kind == SystemShadeConfig.Kind.ACTIONS) {
                slider("Столбцы кнопок", 1, 8, value.columns, next -> value.columns = next);
            }
        }
        TextView hint = new TextView(this);
        hint.setText("Перетаскивание двигает элемент. Потяните за любой угол, чтобы изменить размер.");
        hint.setTextSize(14);
        hint.setAlpha(.7f);
        hint.setPadding(0, dp(16), 0, 0);
        controls.addView(hint);
        rebuildingControls = false;
    }

    private void slider(String name, int min, int max, int current, Change change) {
        TextView label = new TextView(this);
        label.setText(name + ": " + current);
        label.setTextSize(15);
        controls.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(current - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean user) {
                if (!user) return;
                int value = min + progress;
                label.setText(name + ": " + value);
                change.changed(value);
                config.normalize();
                store.save(config);
                buildFrames();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        controls.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        controls.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void title(String text) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(20);
        value.setPadding(0, dp(12), 0, dp(6));
        controls.addView(value);
    }

    private static String previewText(SystemShadeConfig.Kind kind) {
        switch (kind) {
            case CLOCK: return "18:36";
            case DATE: return "вторник, 1 сентября";
            case MEDIA: return "Название трека\nИсполнитель\n◀   ▶   ▶▶";
            case VOLUME: return "🔊  Громкость";
            case BRIGHTNESS: return "☀  Яркость";
            case ACTIONS: default: return "Кнопки и действия";
        }
    }

    private static int parse(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface Change { void changed(int value); }
}
