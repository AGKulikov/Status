/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.diagnostics.ActionRecorder;
import dezz.status.widget.diagnostics.ActionRecorderOverlayService;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.diagnostics.MainThreadWatchdog;
import dezz.status.widget.diagnostics.PrivilegedDiagnosticsAccess;
import dezz.status.widget.shell.PrivilegedShell;

/** Human-readable diagnostic journal and structured action-recorder controls. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private static final String ALL = "Все";

    private Preferences preferences;
    private Switch debugEnabled;
    private Switch overlayVisible;
    private Spinner levelFilter;
    private Spinner componentFilter;
    private TextView journal;
    private TextView recorderState;
    private TextView timeline;
    private TextView widthValue;
    private TextView alphaValue;
    private TextView expandedAccessState;
    private Button recorderToggle;
    private EditText markerComment;
    private Switch rootInputEnabled;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        preferences = new Preferences(this);
        DiagnosticJournal.initialize(this, preferences.debugModeEnabled.get());
        ActionRecorder.initialize(this);
        CarIntegrations.get(this);
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(18), dp(20), dp(48));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Отладка и регистратор действий", 24), weighted());
        page.addView(header, matchWrap());

        page.addView(label("Подробный журнал выключен по умолчанию. При включении он циклически "
                + "сохраняет действия приложения, предупреждения, ошибки, зависания главного "
                + "потока и полный стек аварии. Токены, пароли и MAC-адреса скрываются."),
                topMargin(8));

        debugEnabled = switchView("Подробный режим отладки",
                preferences.debugModeEnabled.get());
        debugEnabled.setOnCheckedChangeListener((button, checked) -> {
            preferences.debugModeEnabled.set(checked);
            DiagnosticJournal.setEnabled(this, checked);
            MainThreadWatchdog.setEnabled(checked);
            refreshJournal();
        });
        page.addView(debugEnabled, topMargin(14));

        LinearLayout filters = row();
        levelFilter = spinner(new String[]{ALL, "DEBUG", "INFO", "WARN", "ERROR"});
        filters.addView(levelFilter, weighted());
        componentFilter = spinner(new String[]{ALL});
        filters.addView(componentFilter, weightedWithMargin(8));
        Button refresh = button("Обновить");
        refresh.setOnClickListener(view -> refreshAll());
        filters.addView(refresh, new LinearLayout.LayoutParams(dp(140), dp(52)));
        page.addView(filters, topMargin(12));
        levelFilter.setOnItemSelectedListener(new SimpleSelectionListener(this::refreshJournal));
        componentFilter.setOnItemSelectedListener(
                new SimpleSelectionListener(this::refreshJournal));

        journal = logText();
        page.addView(journal, topMargin(8));

        LinearLayout journalActions = row();
        Button copy = button("Копировать");
        copy.setOnClickListener(view -> copyJournal());
        journalActions.addView(copy, weighted());
        Button export = button("Экспорт TXT");
        export.setOnClickListener(view -> share(DiagnosticJournal.copyForExport(this),
                "text/plain"));
        journalActions.addView(export, weightedWithMargin(8));
        Button clear = button("Очистить");
        clear.setOnClickListener(view -> confirmClearJournal());
        journalActions.addView(clear, weightedWithMargin(8));
        page.addView(journalActions, topMargin(8));

        page.addView(heading("Регистратор воспроизводимых действий", 21), topMargin(28));
        page.addView(label("Сессия фиксирует порядок: обычные кнопки руля "
                + "(keyCode/down/up/long/repeat), а на KX11 также прямые низкоуровневые "
                + "ECARX-сигналы кнопок ACC/G-Pilot/ограничителя и ответные состояния ADAS; "
                + "штатное появление окна ecarx.hvac.app и прямой openHvacMain; экраны и "
                + "нажатия из спецвозможностей, запуск наших сервисов, Intent action без "
                + "персональных extras и открытие/закрытие оверлеев. Файлы записываются "
                + "немедленно, поэтому незавершённая при падении сессия не теряется."),
                topMargin(6));

        recorderState = label("");
        recorderState.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        page.addView(recorderState, topMargin(10));

        LinearLayout recorderActions = row();
        recorderToggle = button("Начать запись");
        recorderToggle.setOnClickListener(view -> toggleRecorder());
        recorderActions.addView(recorderToggle, weighted());
        Button mark = button("Добавить метку");
        mark.setOnClickListener(view -> addMarker());
        recorderActions.addView(mark, weightedWithMargin(8));
        page.addView(recorderActions, topMargin(8));

        markerComment = new EditText(this);
        markerComment.setHint("Комментарий к следующей метке");
        markerComment.setSingleLine(true);
        page.addView(markerComment, topMargin(8));

        overlayVisible = switchView("Плавающее управление поверх всех приложений",
                preferences.actionRecorderOverlayVisible.get());
        overlayVisible.setOnCheckedChangeListener((button, checked) -> {
            if (checked) ActionRecorderOverlayService.show(this);
            else ActionRecorderOverlayService.hide(this);
            preferences.actionRecorderOverlayVisible.set(checked);
        });
        page.addView(overlayVisible, topMargin(12));

        widthValue = label("");
        page.addView(widthValue, topMargin(10));
        SeekBar width = new SeekBar(this);
        width.setMax(430);
        width.setProgress(clamp(preferences.actionRecorderOverlayWidth.get(),
                330, 760) - 330);
        width.setOnSeekBarChangeListener(new SeekListener(progress -> {
            preferences.actionRecorderOverlayWidth.set(330 + progress);
            widthValue.setText("Ширина плавающего фрейма: " + (330 + progress) + " px");
            refreshOverlayIfVisible();
        }));
        page.addView(width, matchWrap());

        alphaValue = label("");
        page.addView(alphaValue, topMargin(6));
        SeekBar alpha = new SeekBar(this);
        alpha.setMax(175);
        alpha.setProgress(clamp(preferences.actionRecorderOverlayAlpha.get(),
                80, 255) - 80);
        alpha.setOnSeekBarChangeListener(new SeekListener(progress -> {
            preferences.actionRecorderOverlayAlpha.set(80 + progress);
            alphaValue.setText("Непрозрачность фрейма: " + (80 + progress));
            refreshOverlayIfVisible();
        }));
        page.addView(alpha, matchWrap());

        LinearLayout exportActions = row();
        Button exportText = button("Сессия TXT");
        exportText.setOnClickListener(view -> share(
                ActionRecorder.copyLatestForExport(this, false), "text/plain"));
        exportActions.addView(exportText, weighted());
        Button exportJson = button("Сессия JSON");
        exportJson.setOnClickListener(view -> share(
                ActionRecorder.copyLatestForExport(this, true), "application/json"));
        exportActions.addView(exportJson, weightedWithMargin(8));
        page.addView(exportActions, topMargin(10));

        timeline = logText();
        page.addView(timeline, topMargin(8));

        page.addView(heading("Расширенный системный захват", 20), topMargin(24));
        page.addView(label("READ_LOGS добавляет фильтрованный след ECARX/ADAS/климата/ввода, "
                + "а DUMP — снимки Window, Activity, Input и MediaSession при старте и каждой "
                + "метке. Права выдаются один раз, сохраняются после перезагрузки и обновления "
                + "APK; удаление приложения их сбрасывает."), topMargin(5));
        expandedAccessState = logText();
        expandedAccessState.setText("Проверяю права…");
        page.addView(expandedAccessState, topMargin(8));

        LinearLayout accessActions = row();
        Button grantInternally = button("Выдать через встроенный ADB");
        grantInternally.setOnClickListener(view -> grantExpandedAccess(0));
        accessActions.addView(grantInternally, weighted());
        Button copyGrants = button("Копировать команды");
        copyGrants.setOnClickListener(view -> copyExpandedGrantCommands());
        accessActions.addView(copyGrants, weightedWithMargin(8));
        page.addView(accessActions, topMargin(8));

        rootInputEnabled = switchView(
                "Root: пассивно записывать только EV_KEY (без координат касаний)",
                preferences.actionRecorderRootInputEnabled.get());
        rootInputEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                preferences.actionRecorderRootInputEnabled.set(false);
                return;
            }
            PrivilegedDiagnosticsAccess.inspectAsync(this, access -> {
                if (access.root) {
                    preferences.actionRecorderRootInputEnabled.set(true);
                    Toast.makeText(this, "EV_KEY будет захватываться только во время записи",
                            Toast.LENGTH_SHORT).show();
                } else {
                    preferences.actionRecorderRootInputEnabled.set(false);
                    rootInputEnabled.setChecked(false);
                    Toast.makeText(this, "su не предоставил root; опция не включена",
                            Toast.LENGTH_LONG).show();
                }
                refreshExpandedAccess();
            });
        });
        page.addView(rootInputEnabled, topMargin(10));

        page.addView(heading("Доступные источники", 20), topMargin(24));
        page.addView(label("Прямой read-only канал ECARX ловит подтверждённые сигналы кнопок "
                + "ACC/G-Pilot/ограничителя без root. Спецвозможности фиксируют штатные окна и "
                + "обычные KeyEvent. Расширенные права дополняют их системным следом. "
                + "Регистратор ничего не отправляет в CAN/ECARX и не включает функции авто."),
                topMargin(5));
        Button accessibility = button("Открыть настройки спецвозможностей");
        accessibility.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException failure) {
                Toast.makeText(this, "Не удалось открыть системные настройки",
                        Toast.LENGTH_LONG).show();
            }
        });
        page.addView(accessibility, topMargin(10));

        return scroll;
    }

    private void toggleRecorder() {
        if (ActionRecorder.isRecording()) {
            ActionRecorder.stop("diagnostics screen");
        } else {
            ActionRecorder.start("diagnostics screen");
        }
        refreshRecorder();
    }

    private void addMarker() {
        if (!ActionRecorder.isRecording()) {
            Toast.makeText(this, "Сначала начните запись", Toast.LENGTH_SHORT).show();
            return;
        }
        ActionRecorder.mark(markerComment.getText().toString());
        markerComment.setText("");
        refreshRecorder();
    }

    private void refreshAll() {
        refreshComponentChoices();
        refreshJournal();
        refreshRecorder();
        int width = clamp(preferences.actionRecorderOverlayWidth.get(), 330, 760);
        widthValue.setText("Ширина плавающего фрейма: " + width + " px");
        int alpha = clamp(preferences.actionRecorderOverlayAlpha.get(), 80, 255);
        alphaValue.setText("Непрозрачность фрейма: " + alpha);
        if (overlayVisible != null) {
            overlayVisible.setChecked(preferences.actionRecorderOverlayVisible.get());
        }
        refreshExpandedAccess();
    }

    private void refreshExpandedAccess() {
        if (expandedAccessState == null) return;
        PrivilegedDiagnosticsAccess.inspectAsync(this, access -> {
            if (isFinishing() || isDestroyed() || expandedAccessState == null) return;
            String standard = access.standardCaptureReady() ? "ГОТОВ" : "НУЖНЫ ПРАВА";
            expandedAccessState.setText("Системный захват: " + standard
                    + "\nREAD_LOGS: " + yesNo(access.readLogs)
                    + "\nDUMP: " + yesNo(access.dump)
                    + "\nPACKAGE_USAGE_STATS: " + yesNo(access.usageStatsPermission)
                    + "\nUsage Access AppOp: " + yesNo(access.usageAccess)
                    + "\nsu/root: " + yesNo(access.root)
                    + "\nRoot EV_KEY: "
                    + (access.rootInputEnabled && access.root ? "включён" : "выключен"));
            if (rootInputEnabled != null
                    && rootInputEnabled.isChecked() != access.rootInputEnabled) {
                rootInputEnabled.setChecked(access.rootInputEnabled);
            }
        });
    }

    private void grantExpandedAccess(int commandIndex) {
        String[] commands = expandedGrantCommands();
        if (commandIndex >= commands.length) {
            Toast.makeText(this, "Команды выполнены; проверяю права", Toast.LENGTH_SHORT).show();
            refreshExpandedAccess();
            return;
        }
        if (expandedAccessState != null) {
            expandedAccessState.setText("Выдаю расширенные права: "
                    + (commandIndex + 1) + "/" + commands.length + "…");
        }
        PrivilegedShell.get(this).runCommand(commands[commandIndex], (output, error) -> {
            if (error != null) {
                Toast.makeText(this, "Встроенный ADB недоступен: " + error
                                + ". Используйте скопированные команды один раз с компьютера.",
                        Toast.LENGTH_LONG).show();
                refreshExpandedAccess();
                return;
            }
            grantExpandedAccess(commandIndex + 1);
        });
    }

    private void copyExpandedGrantCommands() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        StringBuilder value = new StringBuilder();
        for (String command : expandedGrantCommands()) {
            if (value.length() > 0) value.append('\n');
            value.append("adb shell ").append(command);
        }
        manager.setPrimaryClip(ClipData.newPlainText("Natro ADB grants", value));
        Toast.makeText(this, "Четыре одноразовые ADB-команды скопированы",
                Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private String[] expandedGrantCommands() {
        String packageName = getPackageName();
        return new String[] {
                "pm grant " + packageName + " android.permission.READ_LOGS",
                "pm grant " + packageName + " android.permission.DUMP",
                "pm grant " + packageName + " android.permission.PACKAGE_USAGE_STATS",
                "appops set " + packageName + " GET_USAGE_STATS allow"
        };
    }

    @NonNull
    private static String yesNo(boolean value) {
        return value ? "есть" : "нет";
    }

    private void refreshComponentChoices() {
        if (componentFilter == null) return;
        String selected = componentFilter.getSelectedItem() == null ? ALL
                : componentFilter.getSelectedItem().toString();
        Set<String> values = new LinkedHashSet<>();
        values.add(ALL);
        for (DiagnosticJournal.Entry entry : DiagnosticJournal.read()) {
            values.add(entry.component);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(values));
        componentFilter.setAdapter(adapter);
        int position = adapter.getPosition(selected);
        componentFilter.setSelection(Math.max(0, position));
    }

    private void refreshJournal() {
        if (journal == null) return;
        String level = selected(levelFilter);
        String component = selected(componentFilter);
        List<DiagnosticJournal.Entry> values = DiagnosticJournal.read();
        int start = Math.max(0, values.size() - 1_000);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int index = start; index < values.size(); index++) {
            DiagnosticJournal.Entry entry = values.get(index);
            if (!ALL.equals(level) && !entry.level.name().equals(level)) continue;
            if (!ALL.equals(component) && !entry.component.equals(component)) continue;
            int from = text.length();
            text.append(entry.readable()).append('\n');
            text.setSpan(new ForegroundColorSpan(levelColor(entry.level)), from, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (text.length() == 0) text.append("Журнал пуст");
        journal.setText(text);
    }

    private void refreshRecorder() {
        if (recorderState == null) return;
        if (ActionRecorder.isRecording()) {
            long seconds = Math.max(0L,
                    (System.currentTimeMillis() - ActionRecorder.startedAt()) / 1_000L);
            recorderState.setText("● ИДЁТ ЗАПИСЬ · " + seconds + " сек");
            recorderState.setTextColor(0xFFFF453A);
            recorderToggle.setText("Остановить запись");
        } else {
            recorderState.setText("Запись остановлена");
            recorderState.setTextColor(Color.LTGRAY);
            recorderToggle.setText("Начать запись");
        }
        timeline.setText(ActionRecorder.latestTimeline(24_000));
    }

    private void copyJournal() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(
                "Natro diagnostics", journal.getText()));
        Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearJournal() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить журнал?")
                .setMessage("Файлы отдельных сессий регистратора останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) -> {
                    DiagnosticJournal.clear();
                    refreshJournal();
                })
                .show();
    }

    private void share(@Nullable File file, @NonNull String mime) {
        if (file == null) {
            Toast.makeText(this, "Файл пока недоступен", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Отправить диагностический файл"));
    }

    private void refreshOverlayIfVisible() {
        if (preferences.actionRecorderOverlayVisible.get()) {
            ActionRecorderOverlayService.show(this);
        }
    }

    private static int levelColor(@NonNull DiagnosticJournal.Level level) {
        switch (level) {
            case ERROR: return 0xFFFF453A;
            case WARN: return 0xFFFFD60A;
            case DEBUG: return 0xFF8E8E93;
            default: return 0xFFE8E8ED;
        }
    }

    @NonNull
    private static String selected(@Nullable Spinner spinner) {
        if (spinner == null || spinner.getSelectedItem() == null) return ALL;
        return spinner.getSelectedItem().toString();
    }

    @NonNull
    private Spinner spinner(@NonNull String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    @NonNull
    private TextView logText() {
        TextView value = label("");
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextSize(14);
        value.setTextIsSelectable(true);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF101217);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0x557F7F7F);
        value.setBackground(background);
        return value;
    }

    @NonNull
    private Switch switchView(@NonNull String label, boolean checked) {
        Switch value = new Switch(this);
        value.setText(label);
        value.setTextSize(17);
        value.setChecked(checked);
        value.setPadding(dp(10), dp(8), dp(10), dp(8));
        return value;
    }

    @NonNull
    private Button button(@NonNull String label) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setTextSize(15);
        value.setMinWidth(0);
        value.setMinimumWidth(0);
        return value;
    }

    @NonNull
    private TextView heading(@NonNull String text, int sp) {
        TextView value = label(text);
        value.setTextSize(sp);
        value.setTypeface(value.getTypeface(), Typeface.BOLD);
        return value;
    }

    @NonNull
    private TextView label(@NonNull String text) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextColor(0xFFE8E8ED);
        value.setTextSize(16);
        return value;
    }

    @NonNull
    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        return value;
    }

    @NonNull
    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    @NonNull
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @NonNull
    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    @NonNull
    private LinearLayout.LayoutParams weightedWithMargin(int top) {
        LinearLayout.LayoutParams value = weighted();
        value.setMarginStart(dp(top));
        return value;
    }

    @NonNull
    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams value = matchWrap();
        value.topMargin = dp(top);
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class SeekListener implements SeekBar.OnSeekBarChangeListener {
        interface Callback {
            void changed(int progress);
        }

        @NonNull private final Callback callback;

        SeekListener(@NonNull Callback callback) {
            this.callback = callback;
        }

        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) callback.changed(progress);
        }

        @Override public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private static final class SimpleSelectionListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        @NonNull private final Runnable callback;

        SimpleSelectionListener(@NonNull Runnable callback) {
            this.callback = callback;
        }

        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                             int position, long id) {
            callback.run();
        }

        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
