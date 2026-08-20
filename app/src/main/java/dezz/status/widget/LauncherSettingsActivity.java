/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.launcher.MediaPlaybackHistoryStore;
import dezz.status.widget.launcher.MediaPlaybackTargetPolicy;
import dezz.status.widget.settings.AppleColorPickerDialog;

/**
 * The only top-level Launcher settings screen.
 *
 * <p>There are deliberately no media/climate/navigation panel subsections. Global behavior lives
 * in this one scroll; a concrete widget's appearance is edited by tapping it in layout mode.</p>
 */
public final class LauncherSettingsActivity extends AppCompatActivity {
    private interface IntSetter {
        void set(int value);
    }

    private Preferences preferences;
    private LinearLayout content;
    private TextView homeStatus;
    private TextView mediaTargetHint;
    private MaterialButton backgroundColorButton;
    private MaterialButton applicationVisibilityButton;
    private MaterialButton fixedPlayerButton;
    private MaterialSwitch fixedPlayerSwitch;
    @NonNull private List<LauncherAppCatalog.App> installedApplications =
            Collections.emptyList();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "launcher-settings-apps");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        setTitle("Лаунчер");
        View screen = buildContent();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.install(this, screen);
        loadApplications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHomeStatus();
        refreshMediaTarget();
    }

    @Override
    protected void onDestroy() {
        appLoader.shutdownNow();
        super.onDestroy();
    }

    @NonNull
    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(22), dp(28), dp(40));
        content.setBackgroundColor(0xFF0B0E13);
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));

        addTitle("Лаунчер");
        addHint("Один свободный холст и один общий пул кнопок, приложений, информации, "
                + "медиа, навигации, климата, автомобиля, умного дома, групп и подложек. "
                + "Настройки выбранного элемента открываются тапом по нему в режиме компоновки.");
        homeStatus = addHint("");
        addButton("Открыть и проверить HOME", view ->
                startActivity(new Intent(this, LauncherActivity.class)));
        addButton("＋ Добавить элемент", view -> openLayoutEditor(true));
        addButton("Режим компоновки", view -> openLayoutEditor(false));
        addButton("Выбрать домашний экран по умолчанию", view -> chooseDefaultHome());

        addSwitch("Полноэкранный режим", preferences.launcherImmersive);
        addSwitch("Показывать сетку в режиме компоновки", preferences.launcherShowGrid);
        addSwitch("HOME → наш лаунчер → оконный Навигатор",
                preferences.launcherHomeOpensWindowedNavigator);
        addHint("Оконный Навигатор создаёт ECARX внутри чужого процесса Яндекса. Natro "
                + "не владеет его Window/Surface, поэтому радиус углов задаётся самой прошивкой, "
                + "а не настройкой лаунчера.");
        addIntControl("Шаг привязки", 1, 100,
                clamp(preferences.launcherSnapPx.get(), 1, 100), " px",
                preferences.launcherSnapPx::set);
        backgroundColorButton = addButton("Цвет фона", view -> showBackgroundDialog());
        AppleColorPickerDialog.decorateButton(backgroundColorButton, "Цвет фона",
                preferences.launcherBackgroundColor.get());

        addHint("Параметры ниже общие для меню «Все приложения» на HOME и панели водителя.");
        addIntControl("Столбцы «Все приложения»", 3, 8,
                clamp(preferences.launcherAllAppsColumns.get(), 3, 8), "",
                preferences.launcherAllAppsColumns::set);
        addIntControl("Масштаб иконок «Все приложения»", 60, 180,
                clamp(preferences.launcherAllAppsIconScalePercent.get(), 60, 180), "%",
                preferences.launcherAllAppsIconScalePercent::set);
        addIntControl("Интервал иконок «Все приложения»", 0, 40,
                clamp(preferences.launcherAllAppsGapPx.get(), 0, 40), " px",
                preferences.launcherAllAppsGapPx::set);
        applicationVisibilityButton = addButton(
                "Приложения в общем меню · загрузка…", view -> showApplicationsDialog());
        applicationVisibilityButton.setEnabled(false);

        addHint("Медиа-команды всегда направляются конкретному Android-плееру, а не общему "
                + "Bluetooth-каналу. Эти параметры сохраняют поведение старого медиараздела.");
        fixedPlayerSwitch = addSwitch("Всегда управлять выбранным плеером",
                preferences.launcherMediaFixedPlayerEnabled.get(), checked -> {
                    String selected = preferences.launcherMediaFixedPlayerPackage.get().trim();
                    if (checked && selected.isEmpty()) {
                        preferences.launcherMediaFixedPlayerEnabled.set(false);
                        fixedPlayerSwitch.setChecked(false);
                        showMediaPlayerPicker();
                        return;
                    }
                    preferences.launcherMediaFixedPlayerEnabled.set(checked);
                    refreshMediaTarget();
                });
        fixedPlayerButton = addButton("Выбрать музыкальный плеер",
                view -> showMediaPlayerPicker());
        mediaTargetHint = addHint("");
        addSwitch("Автоматически продолжать музыку после загрузки",
                preferences.launcherMediaAutoResumeEnabled);
        addIntControl("Задержка команды PLAY", 0, 60,
                clamp(preferences.launcherMediaAutoResumeDelaySeconds.get(), 0, 60), " с",
                preferences.launcherMediaAutoResumeDelaySeconds::set);

        addButton("Сбросить только компоновку", view -> confirmLayoutReset());
        addHint("Доступ к уведомлениям нужен медиасессиям и данным маршрута из Яндекс "
                + "Карт/Навигатора.");
        addButton("Открыть доступ к уведомлениям", view ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        return scroll;
    }

    private void openLayoutEditor(boolean showCatalog) {
        Intent intent = new Intent(this, LauncherActivity.class)
                .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true);
        if (showCatalog) {
            intent.putExtra(LauncherActivity.EXTRA_SHOW_WIDGET_CATALOG, true);
        }
        startActivity(intent);
    }

    private void chooseDefaultHome() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException ignored) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        }
    }

    private void updateHomeStatus() {
        if (homeStatus == null) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        String selected = "";
        try {
            if (getPackageManager().resolveActivity(
                    home, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                selected = getPackageManager().resolveActivity(
                        home, PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
            }
        } catch (RuntimeException ignored) {
        }
        homeStatus.setText(getPackageName().equals(selected)
                ? "Сейчас Natro выбран как HOME."
                : "Сейчас используется другой HOME. Можно сначала открыть предпросмотр.");
    }

    private void loadApplications() {
        appLoader.execute(() -> {
            List<LauncherAppCatalog.App> values =
                    LauncherAppCatalog.loadIncludingSystem(this);
            LauncherAppCatalog.ensureDefaultSystemVisibility(this, preferences, values);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                installedApplications = Collections.unmodifiableList(
                        new ArrayList<>(values));
                if (applicationVisibilityButton != null) {
                    applicationVisibilityButton.setEnabled(true);
                    updateApplicationButton();
                }
                refreshMediaTarget();
            });
        });
    }

    private void updateApplicationButton() {
        if (applicationVisibilityButton == null) return;
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        int visible = 0;
        for (LauncherAppCatalog.App app : installedApplications) {
            if (!hidden.contains(app.component.flattenToString())) visible++;
        }
        applicationVisibilityButton.setText(
                "Приложения в общем меню · " + visible + " из "
                        + installedApplications.size());
    }

    private void showApplicationsDialog() {
        if (installedApplications.isEmpty()) {
            Toast.makeText(this, "Список приложений ещё загружается",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12), dp(6), dp(12), dp(18));
        scroll.addView(rows, new ScrollView.LayoutParams(match(), wrap()));
        TextView count = text("", 14, 0xFF9A9AA0);
        rows.addView(count, rowParams());
        for (LauncherAppCatalog.App app : installedApplications) {
            MaterialCardView card = new MaterialCardView(this);
            card.setCardBackgroundColor(0xFF1C1C1E);
            card.setRadius(dp(12));
            card.setCardElevation(0);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(LauncherAppCatalog.loadIcon(this, app));
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(app.label, 15, Color.WHITE));
            labels.addView(text(app.component.flattenToShortString(),
                    10, 0xFF8E8E93));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, wrap(), 1f);
            labelParams.leftMargin = dp(10);
            row.addView(labels, labelParams);
            String key = app.component.flattenToString();
            MaterialSwitch visible = new MaterialSwitch(this);
            visible.setChecked(!preferences.launcherAllAppsHiddenComponents.get()
                    .contains(key));
            visible.setOnCheckedChangeListener((button, checked) -> {
                Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
                if (checked) hidden.remove(key); else hidden.add(key);
                preferences.launcherAllAppsHiddenComponents.set(hidden);
                updateApplicationCount(count);
                updateApplicationButton();
            });
            row.addView(visible, new LinearLayout.LayoutParams(dp(90), dp(50)));
            card.addView(row);
            rows.addView(card, rowParams());
        }
        updateApplicationCount(count);
        new AlertDialog.Builder(this)
                .setTitle("Приложения в общем меню")
                .setMessage("Системные приложения изначально скрыты, кроме «Телефона». "
                        + "Изменения сразу действуют и на HOME, и на панели водителя.")
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .show();
    }

    private void updateApplicationCount(@NonNull TextView count) {
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        int visible = 0;
        for (LauncherAppCatalog.App app : installedApplications) {
            if (!hidden.contains(app.component.flattenToString())) visible++;
        }
        count.setText("Показывается " + visible + " из " + installedApplications.size());
    }

    private void showMediaPlayerPicker() {
        LinkedHashMap<String, LauncherAppCatalog.App> byPackage = new LinkedHashMap<>();
        for (LauncherAppCatalog.App app : installedApplications) {
            if (getPackageName().equals(app.packageName)) continue;
            byPackage.putIfAbsent(app.packageName, app);
        }
        List<LauncherAppCatalog.App> choices = new ArrayList<>(byPackage.values());
        if (choices.isEmpty()) {
            Toast.makeText(this, "Список приложений ещё загружается",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        String current = preferences.launcherMediaFixedPlayerPackage.get().trim();
        int checked = -1;
        for (int index = 0; index < choices.size(); index++) {
            LauncherAppCatalog.App app = choices.get(index);
            labels[index] = app.label + "\n" + app.packageName;
            if (current.equals(app.packageName)) checked = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Музыкальный плеер")
                .setSingleChoiceItems(labels, checked, null)
                .setNeutralButton("Последний плеер", (value, which) -> {
                    preferences.launcherMediaFixedPlayerEnabled.set(false);
                    preferences.launcherMediaFixedPlayerPackage.set("");
                    if (fixedPlayerSwitch != null) fixedPlayerSwitch.setChecked(false);
                    refreshMediaTarget();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                    LauncherAppCatalog.App app = choices.get(position);
                    preferences.launcherMediaFixedPlayerPackage.set(app.packageName);
                    preferences.launcherMediaFixedPlayerEnabled.set(true);
                    if (fixedPlayerSwitch != null) fixedPlayerSwitch.setChecked(true);
                    dialog.dismiss();
                    refreshMediaTarget();
                }));
        dialog.show();
    }

    private void refreshMediaTarget() {
        if (preferences == null) return;
        String fixedPackage = preferences.launcherMediaFixedPlayerPackage.get().trim();
        boolean fixed = preferences.launcherMediaFixedPlayerEnabled.get()
                && !fixedPackage.isEmpty();
        if (fixedPlayerSwitch != null && fixedPlayerSwitch.isChecked() != fixed) {
            fixedPlayerSwitch.setChecked(fixed);
        }
        if (fixedPlayerButton != null) {
            fixedPlayerButton.setText(fixedPackage.isEmpty()
                    ? "Выбрать музыкальный плеер · не выбран"
                    : "Выбрать музыкальный плеер · " + applicationLabel(fixedPackage));
        }
        if (mediaTargetHint == null) return;
        MediaPlaybackHistoryStore.Snapshot history = MediaPlaybackHistoryStore.read(this);
        String target = MediaPlaybackTargetPolicy.resolve(
                fixed, fixedPackage, history.packageName);
        String remembered = history.packageName.isEmpty()
                ? "последний плеер ещё не определён"
                : "последний: " + applicationLabel(history.packageName)
                + (history.wasPlaying ? " · воспроизводил" : " · был на паузе");
        mediaTargetHint.setText("Текущая цель: "
                + (target.isEmpty() ? "последняя активная медиасессия"
                : applicationLabel(target)) + "; " + remembered + ".");
    }

    @NonNull
    private String applicationLabel(@NonNull String packageName) {
        for (LauncherAppCatalog.App app : installedApplications) {
            if (packageName.equals(app.packageName)) return app.label;
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private void showBackgroundDialog() {
        String original = preferences.launcherBackgroundColor.get();
        AppleColorPickerDialog.show(this, "Цвет фона", original,
                AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override
                    public void onPreview(@Nullable String value) {
                        AppleColorPickerDialog.decorateButton(backgroundColorButton,
                                "Цвет фона", value);
                    }

                    @Override
                    public void onSelected(@Nullable String value) {
                        if (value != null) preferences.launcherBackgroundColor.set(value);
                        AppleColorPickerDialog.decorateButton(backgroundColorButton,
                                "Цвет фона", preferences.launcherBackgroundColor.get());
                    }
                });
    }

    private void confirmLayoutReset() {
        new AlertDialog.Builder(this)
                .setTitle("Сбросить компоновку?")
                .setMessage("Координаты, размеры, группы и подложки вернутся к исходным. "
                        + "Состав, действия, шрифты, цвета и параметры медиаплеера сохранятся.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    preferences.launcherLayoutJson.set("");
                    preferences.launcherGlobalElementsJson.set("");
                    preferences.launcherHorizontalGroupsJson.set("");
                    preferences.launcherBackdropsJson.set("");
                    Toast.makeText(this, "Компоновка сброшена",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private MaterialSwitch addSwitch(@NonNull String label,
                                     @NonNull Preferences.Bool preference) {
        return addSwitch(label, preference.get(), checked -> preference.set(checked));
    }

    private MaterialSwitch addSwitch(@NonNull String label, boolean checked,
                                     @NonNull java.util.function.Consumer<Boolean> listener) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(label);
        control.setTextSize(17);
        control.setTextColor(Color.WHITE);
        control.setMinHeight(dp(56));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> listener.accept(value));
        content.addView(control, new LinearLayout.LayoutParams(match(), wrap()));
        return control;
    }

    private void addIntControl(@NonNull String label, int minimum, int maximum,
                               int current, @NonNull String suffix,
                               @NonNull IntSetter setter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView value = text(label + ": " + current + suffix, 15, 0xFFC7C7CC);
        block.addView(value);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(current - minimum);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = minimum + progress;
                value.setText(label + ": " + selected + suffix);
                if (fromUser) setter.set(selected);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        content.addView(block, rowParams());
    }

    @NonNull
    private MaterialButton addButton(@NonNull String label,
                                     @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(54));
        button.setOnClickListener(listener);
        content.addView(button, rowParams());
        return button;
    }

    private void addTitle(@NonNull String value) {
        TextView title = text(value, 25, getColor(R.color.settings_accent));
        LinearLayout.LayoutParams params = rowParams();
        params.bottomMargin = dp(6);
        content.addView(title, params);
    }

    @NonNull
    private TextView addHint(@NonNull String value) {
        TextView hint = text(value, 14, 0xFF9A9AA0);
        LinearLayout.LayoutParams params = rowParams();
        params.bottomMargin = dp(6);
        content.addView(hint, params);
        return hint;
    }

    @NonNull
    private TextView text(@NonNull String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    @NonNull
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }
}
