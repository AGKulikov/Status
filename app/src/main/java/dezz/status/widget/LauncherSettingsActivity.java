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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.launcher.EmbeddedNavigatorContract;

/** Human-readable HOME setup. Every switch writes immediately; there is no Save button. */
public final class LauncherSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private LinearLayout content;
    private TextView homeStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        setTitle("Домашний экран");
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHomeStatus();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(22), dp(28), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));

        addTitle("Домашний экран Status Widget");
        addHint("Новый HOME работает внутри того же пакета и сохраняет все текущие кирпичики, коннекторы и сценарии.");
        homeStatus = addHint("");
        addButton("Открыть и проверить HOME", v ->
                startActivity(new Intent(this, LauncherActivity.class)));
        addButton("Открыть редактор компоновки", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));
        addButton("Выбрать домашний экран по умолчанию", v -> {
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
            catch (RuntimeException ignored) {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            }
        });

        addTitle("Вид и поведение");
        addSwitch("Полноэкранный режим", preferences.launcherImmersive);
        addSwitch("Показывать сетку в режиме редактирования", preferences.launcherShowGrid);
        addSnapControl();
        addButton("Цвет фона…", v -> showBackgroundDialog());

        addTitle("Панели");
        addHint("Координаты и размеры хранятся в пикселях. На HOME нажмите «Изменить», перетащите панели и потяните за их правый нижний угол.");
        addSwitch("Избранные приложения", preferences.launcherAppsVisible);
        addSwitch("Медиапанель", preferences.launcherMediaVisible);
        addSwitch("Часы", preferences.launcherClockVisible);
        addCombinedNavigationSwitch();
        addSwitch("Быстрые действия", preferences.launcherActionsVisible);
        addSwitch("Отдельная климатическая панель", preferences.launcherClimateVisible);
        addSwitch("Данные автомобиля / HUD", preferences.launcherVehicleInfoVisible);
        addSwitch("Информация: автомобиль и умный дом",
                preferences.launcherInformationVisible);
        if (EmbeddedNavigatorContract.isBundled(this)) {
            addSwitch("Встроенный Яндекс Навигатор",
                    preferences.launcherEmbeddedNavigatorVisible);
            addHint("Живое окно Навигатора является частью единого мода. Его положение и размер "
                    + "меняются на HOME вместе с остальными панелями.");
        }
        addButton("Состав, порядок и размеры элементов панелей…", v ->
                startActivity(new Intent(this, PanelElementSettingsActivity.class)));
        addButton("Настроить избранные приложения…", v ->
                startActivity(new Intent(this, FavoriteAppsSettingsActivity.class)));
        addButton("Настроить медиапанель…", v ->
                startActivity(new Intent(this, MediaPanelSettingsActivity.class)));
        addButton("Сетка данных навигации…", v ->
                startActivity(new Intent(this, NavigationPanelSettingsActivity.class)));
        addButton("Настроить маршрут и избранное…", v ->
                startActivity(new Intent(this, FavoriteRoutesSettingsActivity.class)));
        addButton("Настроить климатическую панель…", v ->
                startActivity(new Intent(this, ClimatePanelSettingsActivity.class)));
        addButton("Настроить данные автомобиля…", v ->
                startActivity(new Intent(this, VehicleInfoPanelSettingsActivity.class)));
        addButton("Настроить панель «Информация»…", v ->
                startActivity(new Intent(this, InformationPanelSettingsActivity.class)));
        addButton("Иконки, функции и приложения…", v ->
                startActivity(new Intent(this, LauncherShortcutSettingsActivity.class)));
        addActionsColumnsControl();
        addButton("Сбросить расположение панелей", v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить компоновку?")
                .setMessage("Панели вернутся в исходные позиции. Остальные настройки не изменятся.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    preferences.launcherLayoutJson.set("");
                    Toast.makeText(this, "Компоновка сброшена", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null).show());

        addTitle("Доступы");
        addHint("Доступ к уведомлениям нужен для медиасессий и для времени прибытия/оставшегося пути из Яндекс Карт и Навигатора.");
        addButton("Открыть доступ к уведомлениям", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        return scroll;
    }

    private void updateHomeStatus() {
        if (homeStatus == null) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        String selected = "";
        try {
            if (getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                selected = getPackageManager().resolveActivity(home,
                        PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
            }
        } catch (RuntimeException ignored) {}
        homeStatus.setText(getPackageName().equals(selected)
                ? "Сейчас Status Widget выбран как HOME."
                : "Сейчас используется другой HOME. Можно сначала открыть предпросмотр.");
    }

    private void addSnapControl() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setTextSize(17);
        SeekBar seek = new SeekBar(this);
        seek.setMax(96);
        seek.setProgress(Math.max(0, preferences.launcherSnapPx.get() - 4));
        TextView value = new TextView(this);
        value.setMinWidth(dp(80));
        value.setGravity(Gravity.END);
        value.setText((seek.getProgress() + 4) + " px");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int pixels = progress + 4;
                value.setText(pixels + " px");
                if (user) preferences.launcherSnapPx.set(pixels);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        label.setText("Шаг сетки");
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), .35f));
        row.addView(seek, new LinearLayout.LayoutParams(0, wrap(), .65f));
        row.addView(value);
        content.addView(row, new LinearLayout.LayoutParams(match(), dp(64)));
    }

    private void addActionsColumnsControl() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setTextSize(17);
        label.setText("Столбцов в панели иконок");
        SeekBar seek = new SeekBar(this);
        seek.setMax(5);
        seek.setProgress(Math.max(0, Math.min(5, preferences.launcherActionsColumns.get() - 1)));
        TextView value = new TextView(this);
        value.setMinWidth(dp(60));
        value.setGravity(Gravity.END);
        value.setText(String.valueOf(seek.getProgress() + 1));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int columns = progress + 1;
                value.setText(String.valueOf(columns));
                if (user) preferences.launcherActionsColumns.set(columns);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), .45f));
        row.addView(seek, new LinearLayout.LayoutParams(0, wrap(), .55f));
        row.addView(value);
        content.addView(row, new LinearLayout.LayoutParams(match(), dp(64)));
    }

    private void showBackgroundDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(preferences.launcherBackgroundColor.get());
        input.setHint("#101827");
        new AlertDialog.Builder(this)
                .setTitle("Цвет фона")
                .setMessage("Укажите HEX-цвет. Изменение будет видно при следующем открытии HOME.")
                .setView(input)
                .setPositiveButton("Применить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    try {
                        Color.parseColor(value);
                        preferences.launcherBackgroundColor.set(value);
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, "Неверный цвет", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addSwitch(String label, Preferences.Bool preference) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(label);
        control.setTextSize(17);
        control.setMinHeight(dp(56));
        control.setChecked(preference.get());
        control.setOnCheckedChangeListener((button, checked) -> preference.set(checked));
        content.addView(control, new LinearLayout.LayoutParams(match(), wrap()));
    }

    private void addCombinedNavigationSwitch() {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText("Маршрут и избранные направления");
        control.setTextSize(17);
        control.setMinHeight(dp(56));
        control.setChecked(preferences.launcherNavigationVisible.get()
                || preferences.launcherFavoriteRoutesVisible.get());
        control.setOnCheckedChangeListener((button, checked) -> {
            // Keep both legacy keys synchronized so an update preserves every existing setup.
            preferences.launcherNavigationVisible.set(checked);
            preferences.launcherFavoriteRoutesVisible.set(checked);
        });
        content.addView(control, new LinearLayout.LayoutParams(match(), wrap()));
    }

    private void addButton(String label, android.view.View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(54));
        lp.topMargin = dp(7);
        content.addView(button, lp);
    }

    private void addTitle(String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(23);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        content.addView(title, lp);
    }

    private TextView addHint(String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(15);
        hint.setAlpha(.78f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(8);
        content.addView(hint, lp);
        return hint;
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
