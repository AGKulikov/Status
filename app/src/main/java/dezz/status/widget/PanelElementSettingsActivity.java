/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;

/** Visual, code-free editor for the functional elements placed inside HOME panels. */
public final class PanelElementSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private PanelElementConfigStore store;
    private LinearLayout editor;
    private FrameLayout previewHost;
    @NonNull private String selectedPanel = LauncherLayoutStore.APPS;
    @Nullable private PanelElementConfigStore.Panel current;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new PanelElementConfigStore(preferences);
        setTitle("Элементы панелей HOME");
        setContentView(buildContent());
        showPanel(selectedPanel);
    }

    @NonNull
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView navigationScroll = new ScrollView(this);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setPadding(0, 0, dp(16), dp(20));
        navigationScroll.addView(navigation, new ScrollView.LayoutParams(match(), wrap()));
        addSection(navigation, "ПАНЕЛИ HOME");
        addPanelButton(navigation, "Приложения", LauncherLayoutStore.APPS);
        addExternalButton(navigation, "Медиа", MediaPanelSettingsActivity.class);
        addPanelButton(navigation, "Часы", LauncherLayoutStore.CLOCK);
        addPanelButton(navigation, "Маршрут", LauncherLayoutStore.NAVIGATION);
        addPanelButton(navigation, "Иконки и действия", LauncherLayoutStore.ACTIONS);
        addExternalButton(navigation, "Климат", ClimatePanelSettingsActivity.class);
        TextView hint = new TextView(this);
        hint.setText("Размер и положение самой панели меняются в редакторе компоновки HOME. Здесь настраивается её содержимое.");
        hint.setTextSize(13);
        hint.setAlpha(.68f);
        hint.setPadding(dp(8), dp(18), dp(8), 0);
        navigation.addView(hint);

        ScrollView editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(true);
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(18), 0, dp(12), dp(32));
        editorScroll.addView(editor, new ScrollView.LayoutParams(match(), wrap()));

        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(12), 0, 0, 0);
        TextView previewTitle = new TextView(this);
        previewTitle.setText("ЖИВОЙ ПРЕДПРОСМОТР");
        previewTitle.setTextSize(14);
        previewTitle.setTextColor(Color.rgb(105, 165, 255));
        previewTitle.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));
        previewHost = new FrameLayout(this);
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(Color.rgb(12, 17, 25));
        previewBackground.setCornerRadius(dp(24));
        previewHost.setBackground(previewBackground);
        previewHost.setPadding(dp(18), dp(18), dp(18), dp(18));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        TextView saved = new TextView(this);
        saved.setText("✓ Сохраняется автоматически");
        saved.setGravity(Gravity.CENTER);
        saved.setTextSize(13);
        saved.setAlpha(.7f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(navigationScroll, new LinearLayout.LayoutParams(0, match(), .22f));
        root.addView(editorScroll, new LinearLayout.LayoutParams(0, match(), .43f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .35f));
        return root;
    }

    private void addPanelButton(@NonNull LinearLayout parent, @NonNull String title,
            @NonNull String panelId) {
        MaterialButton button = panelButton(title);
        button.setOnClickListener(v -> showPanel(panelId));
        parent.addView(button, buttonLp());
    }

    private void addExternalButton(@NonNull LinearLayout parent, @NonNull String title,
            @NonNull Class<?> activityClass) {
        MaterialButton button = panelButton(title + "  ›");
        button.setOnClickListener(v -> startActivity(new Intent(this, activityClass)));
        parent.addView(button, buttonLp());
    }

    @NonNull private MaterialButton panelButton(@NonNull String title) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return button;
    }

    private void showPanel(@NonNull String panelId) {
        selectedPanel = panelId;
        current = store.load(panelId);
        renderEditor();
        renderPreview();
    }

    private void renderEditor() {
        PanelElementConfigStore.Panel panel = current;
        if (panel == null) return;
        editor.removeAllViews();
        addTitle(editor, panelTitle(panel.id));
        addHint(editor, "Убирайте ненужные элементы, меняйте их порядок стрелками и размер ползунком. Изменения применятся при возврате на HOME.");
        if (LauncherLayoutStore.APPS.equals(panel.id)) {
            addColumnsControl(editor, "Столбцов приложений", preferences.launcherAppsColumns, 1, 6);
            MaterialButton favorites = new MaterialButton(this);
            favorites.setAllCaps(false);
            favorites.setText("Добавить, убрать и переставить приложения…");
            favorites.setOnClickListener(v -> startActivity(
                    new Intent(this, FavoriteAppsSettingsActivity.class)));
            editor.addView(favorites, buttonLp());
        } else if (LauncherLayoutStore.ACTIONS.equals(panel.id)) {
            addColumnsControl(editor, "Столбцов плиток", preferences.launcherActionsColumns, 1, 6);
            MaterialButton shortcuts = new MaterialButton(this);
            shortcuts.setAllCaps(false);
            shortcuts.setText("Добавить и настроить отдельные плитки…");
            shortcuts.setOnClickListener(v -> startActivity(
                    new Intent(this, LauncherShortcutSettingsActivity.class)));
            editor.addView(shortcuts, buttonLp());
        }

        List<PanelElementConfigStore.Element> enabled = panel.enabled();
        if (enabled.isEmpty()) addHint(editor,
                "Панель сейчас пустая и на HOME будет скрыта полностью.");
        for (PanelElementConfigStore.Element element : enabled) addElementEditor(panel, element);

        MaterialButton add = new MaterialButton(this);
        add.setAllCaps(false);
        add.setText("＋ Добавить элемент");
        add.setOnClickListener(v -> showAddDialog());
        editor.addView(add, buttonLp());

        MaterialButton reset = new MaterialButton(this);
        reset.setAllCaps(false);
        reset.setText("Вернуть состав и размеры по умолчанию");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить эту панель?")
                .setMessage("Положение и размер самой панели сохранятся.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    store.reset(panel.id);
                    showPanel(panel.id);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        editor.addView(reset, buttonLp());
    }

    private void addElementEditor(@NonNull PanelElementConfigStore.Panel panel,
            @NonNull PanelElementConfigStore.Element element) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(115, 70, 82, 104));
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        PanelElementConfigStore.Definition definition =
                PanelElementConfigStore.definition(panel.id, element.id);
        TextView label = new TextView(this);
        label.setText(definition == null ? element.id : definition.label);
        label.setTextSize(16);
        label.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        heading.addView(label, new LinearLayout.LayoutParams(0, wrap(), 1f));
        heading.addView(smallButton("▲", v -> move(element.id, -1)));
        heading.addView(smallButton("▼", v -> move(element.id, 1)));
        heading.addView(smallButton("Убрать", v -> {
            panel.setEnabled(element.id, false);
            saveAndRender(true);
        }));
        card.addView(heading, new LinearLayout.LayoutParams(match(), dp(48)));

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleLabel = new TextView(this);
        scaleLabel.setText("Размер");
        TextView scaleValue = new TextView(this);
        scaleValue.setGravity(Gravity.END);
        scaleValue.setMinWidth(dp(72));
        scaleValue.setText(element.scalePercent + "%");
        SeekBar scale = new SeekBar(this);
        scale.setMax(PanelElementConfigStore.MAX_SCALE - PanelElementConfigStore.MIN_SCALE);
        scale.setProgress(element.scalePercent - PanelElementConfigStore.MIN_SCALE);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean user) {
                int selected = PanelElementConfigStore.MIN_SCALE + progress;
                scaleValue.setText(selected + "%");
                if (user) {
                    panel.setScale(element.id, selected);
                    store.save(panel);
                    renderPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(dp(72), wrap()));
        scaleRow.addView(scale, new LinearLayout.LayoutParams(0, dp(42), 1f));
        scaleRow.addView(scaleValue);
        card.addView(scaleRow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(10);
        editor.addView(card, lp);
    }

    private void move(@NonNull String elementId, int delta) {
        PanelElementConfigStore.Panel panel = current;
        if (panel == null) return;
        panel.move(elementId, delta);
        saveAndRender(true);
    }

    private void showAddDialog() {
        PanelElementConfigStore.Panel panel = current;
        if (panel == null) return;
        List<PanelElementConfigStore.Definition> hidden = new ArrayList<>();
        for (PanelElementConfigStore.Definition definition :
                PanelElementConfigStore.definitions(panel.id)) {
            if (!panel.isEnabled(definition.id)) hidden.add(definition);
        }
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Все доступные элементы уже добавлены", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[hidden.size()];
        for (int index = 0; index < hidden.size(); index++) labels[index] = hidden.get(index).label;
        new AlertDialog.Builder(this)
                .setTitle("Добавить элемент")
                .setItems(labels, (dialog, which) -> {
                    panel.setEnabled(hidden.get(which).id, true);
                    saveAndRender(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveAndRender(boolean editorToo) {
        PanelElementConfigStore.Panel panel = current;
        if (panel == null) return;
        store.save(panel);
        if (editorToo) renderEditor();
        renderPreview();
    }

    private void renderPreview() {
        PanelElementConfigStore.Panel panel = current;
        if (panel == null || previewHost == null) return;
        previewHost.removeAllViews();
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER);
        for (PanelElementConfigStore.Element element : panel.enabled()) {
            TextView value = new TextView(this);
            value.setGravity(Gravity.CENTER);
            value.setText(previewText(panel.id, element.id));
            value.setTextColor(element.id.equals(PanelElementConfigStore.CLOCK_DATE)
                    || element.id.equals(PanelElementConfigStore.NAV_DURATION)
                    || element.id.equals(PanelElementConfigStore.NAV_DISTANCE)
                    || element.id.equals(PanelElementConfigStore.NAV_INACTIVE)
                    ? Color.LTGRAY : Color.WHITE);
            value.setTextSize(basePreviewSp(panel.id, element.id)
                    * element.scalePercent / 100f);
            value.setTypeface(android.graphics.Typeface.DEFAULT,
                    isPrimary(element.id) ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);
            int verticalPadding = Math.max(dp(3), dp(8) * element.scalePercent / 100);
            value.setPadding(dp(6), verticalPadding, dp(6), verticalPadding);
            preview.addView(value, new LinearLayout.LayoutParams(match(), wrap()));
        }
        if (preview.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Панель пустая");
            empty.setGravity(Gravity.CENTER);
            empty.setAlpha(.6f);
            preview.addView(empty, new LinearLayout.LayoutParams(match(), match()));
        }
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
    }

    private void addColumnsControl(@NonNull LinearLayout parent, @NonNull String title,
            @NonNull Preferences.Int preference, int minimum, int maximum) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, preference.get() - minimum)));
        TextView value = new TextView(this);
        value.setMinWidth(dp(48));
        value.setGravity(Gravity.END);
        value.setText(String.valueOf(seek.getProgress() + minimum));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean user) {
                int selected = minimum + progress;
                value.setText(String.valueOf(selected));
                if (user) preference.set(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), .42f));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(44), .58f));
        row.addView(value);
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(58)));
    }

    @NonNull private MaterialButton smallButton(@NonNull String text,
            @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        // MaterialButton 1.12 exposes only vertical inset setters. Horizontal compactness is
        // controlled by the normal padding/min-width APIs below.
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap(), dp(40));
        lp.leftMargin = dp(4);
        button.setLayoutParams(lp);
        return button;
    }

    private static boolean isPrimary(@NonNull String id) {
        return PanelElementConfigStore.CLOCK_TIME.equals(id)
                || PanelElementConfigStore.NAV_ARRIVAL.equals(id)
                || PanelElementConfigStore.APPS_HEADING.equals(id);
    }

    @NonNull private static String panelTitle(@NonNull String id) {
        if (LauncherLayoutStore.APPS.equals(id)) return "Панель приложений";
        if (LauncherLayoutStore.CLOCK.equals(id)) return "Панель часов";
        if (LauncherLayoutStore.NAVIGATION.equals(id)) return "Панель маршрута";
        return "Панель иконок и действий";
    }

    @NonNull private static String previewText(@NonNull String panelId,
            @NonNull String elementId) {
        if (PanelElementConfigStore.APPS_HEADING.equals(elementId)) return "Избранное";
        if (PanelElementConfigStore.APPS_GRID.equals(elementId)) return "▣   ▣   ▣\nПриложения";
        if (PanelElementConfigStore.CLOCK_TIME.equals(elementId)) return "14:56";
        if (PanelElementConfigStore.CLOCK_DATE.equals(elementId)) return "среда, 22 июля";
        if (PanelElementConfigStore.NAV_ARRIVAL.equals(elementId)) return "Время прибытия: 15:28";
        if (PanelElementConfigStore.NAV_DURATION.equals(elementId)) return "Осталось: 24 мин";
        if (PanelElementConfigStore.NAV_DISTANCE.equals(elementId)) return "18 км";
        if (PanelElementConfigStore.NAV_INACTIVE.equals(elementId)) return "Маршрут не запущен";
        if (PanelElementConfigStore.ACTION_ADD.equals(elementId)) return "＋ Добавить";
        return "▣   ▣   ▣\nПользовательские действия";
    }

    private static float basePreviewSp(@NonNull String panelId, @NonNull String elementId) {
        if (PanelElementConfigStore.CLOCK_TIME.equals(elementId)) return 46f;
        if (PanelElementConfigStore.NAV_ARRIVAL.equals(elementId)) return 23f;
        if (PanelElementConfigStore.NAV_DURATION.equals(elementId)) return 17f;
        if (PanelElementConfigStore.NAV_DISTANCE.equals(elementId)) return 17f;
        if (PanelElementConfigStore.NAV_INACTIVE.equals(elementId)) return 16f;
        if (PanelElementConfigStore.CLOCK_DATE.equals(elementId)) return 17f;
        return 18f;
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(25);
        title.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        parent.addView(title, new LinearLayout.LayoutParams(match(), dp(48)));
    }

    private void addSection(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(14);
        title.setTextColor(Color.rgb(105, 165, 255));
        title.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        title.setPadding(dp(8), 0, dp(8), 0);
        parent.addView(title, new LinearLayout.LayoutParams(match(), dp(42)));
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(13);
        hint.setAlpha(.7f);
        hint.setPadding(0, dp(2), 0, dp(8));
        parent.addView(hint, new LinearLayout.LayoutParams(match(), wrap()));
    }

    @NonNull private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(50));
        lp.topMargin = dp(7);
        return lp;
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
