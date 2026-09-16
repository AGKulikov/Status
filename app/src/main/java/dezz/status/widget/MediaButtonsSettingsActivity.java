/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import dezz.status.widget.launcher.InstalledAppCatalog;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.media.*;
import dezz.status.widget.settings.SettingsBackNavigation;

/** All physical groups; the MEDIA tab retains only its original two switches. */
public final class MediaButtonsSettingsActivity extends AppCompatActivity {
    private VehicleButtonController buttons;
    private MediaButtonController media;
    private LinearLayout body;
    private VehicleButton selected = VehicleButton.MEDIA;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buttons = VehicleButtonController.get(this); media = MediaButtonController.get(this);
        if (saved != null) try { selected = VehicleButton.valueOf(saved.getString("tab", "MEDIA")); }
        catch (IllegalArgumentException ignored) {}
        LinearLayout content = column(); content.setPadding(24, 18, 24, 24); content.setBackgroundColor(0xFF0B0D12);
        Button back = button("← Назад", this::finish);
        back.setContentDescription("Назад"); back.setOnClickListener(v -> finish());
        content.addView(back); content.addView(text("Кнопки", 24));
        content.addView(text("Перенесите назначения и отключите соответствующую обработку в MConfig.", 16));
        LinearLayout tabs = new LinearLayout(this);
        for (VehicleButton item : VehicleButton.values()) tabs.addView(button(item.title, () -> {
            selected = item; showTab();
        }));
        content.addView(horizontal(tabs)); body = column(); content.addView(body);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        SettingsBackNavigation.applySafeTopInset(this, content);
        body.addView(text("Загрузка настроек…", 16));
        buttons.whenReady(() -> media.whenReady(() -> { if (!isDestroyed()) showTab(); }));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("tab", selected.name());
    }
    private void showTab() {
        if (!buttons.ready() || !media.isReady()) return;
        body.removeAllViews(); VehicleButton tab = selected; body.addView(text(tab.title, 21));
        if (tab == VehicleButton.DM) {
            LinearLayout row = new LinearLayout(this);
            Switch volume = toggle("Регулировка громкости", buttons.knobVolume());
            row.addView(volume, new LinearLayout.LayoutParams(0, -2, 1));
            NumberPicker steps = new NumberPicker(this); steps.setMinValue(1); steps.setMaxValue(20);
            steps.setValue(buttons.volumeSteps()); row.addView(steps);
            steps.setOnValueChangedListener((picker, old, value) -> buttons.setVolume(volume.isChecked(), value));
            volume.setOnCheckedChangeListener((view, enabled) -> buttons.setVolume(enabled, steps.getValue()));
            body.addView(row);
        }
        Switch enabled = toggle(tab == VehicleButton.DM ? "Пользовательские действия" : "Включить",
                tab == VehicleButton.MEDIA ? media.isEnabled() : buttons.enabled(tab));
        enabled.setOnCheckedChangeListener((view, value) -> {
            if (tab == VehicleButton.MEDIA) media.setEnabled(value); else buttons.setEnabled(tab, value);
        }); body.addView(enabled);
        Switch disable = toggle("Отключить действие по умолчанию",
                tab == VehicleButton.MEDIA ? media.isDisableDefault() : buttons.disabledDefault(tab));
        TextView status = text(tab == VehicleButton.MEDIA ? media.status() : buttons.status(), 15);
        boolean[] updating = {false};
        disable.setOnCheckedChangeListener((view, value) -> {
            if (updating[0]) return;
            disable.setEnabled(false); status.setText("Применение…");
            VehicleButtonController.Result callback = (success, detail) -> {
                if (isDestroyed()) return;
                updating[0] = true;
                disable.setChecked(tab == VehicleButton.MEDIA ? media.isDisableDefault() : buttons.disabledDefault(tab));
                updating[0] = false; disable.setEnabled(true); status.setText(detail);
            };
            if (tab == VehicleButton.MEDIA) media.setDisableDefault(value, callback::finished);
            else buttons.setDisableDefault(tab, value, callback);
        }); body.addView(disable); body.addView(status);
        if (tab == VehicleButton.MEDIA) return;
        LinearLayout gestures = new LinearLayout(this);
        if (tab == VehicleButton.DM) {
            for (String item : new String[]{"L-1X", "L-2X", "L-3X", "R-3X", "R-2X", "R-1X"}) {
                String group = item.startsWith("L") ? "knobLeft" : "knobRight", gesture = item.substring(2, 3);
                gestures.addView(button(bindingLabel(item, group, gesture), () -> editBinding(group, gesture, item)));
            }
        } else {
            for (String gesture : tab.longPress ? new String[]{"1", "2", "3", "long"} : new String[]{"1", "2", "3"})
                gestures.addView(button(bindingLabel("long".equals(gesture) ? "Длинный" : gesture + "X", tab.key, gesture),
                        () -> editBinding(tab.key, gesture, tab.title + " · " + gesture)));
        }
        body.addView(horizontal(gestures)); body.addView(button("Параметры действий…", this::actionOptions));
    }
    private void editBinding(String group, String gesture, String title) {
        ButtonBinding original = buttons.binding(group, gesture);
        LinearLayout fields = column(); fields.setPadding(20, 12, 20, 12);
        List<ButtonAction> actions = new ArrayList<>();
        for (ButtonAction action : ButtonAction.values()) {
            // MConfig kp/vj.gi/ro excludes source cycling from SRC 1X only.
            if (action == ButtonAction.SOURCE && group.equals("src") && gesture.equals("1")) continue;
            if (action == ButtonAction.MNAVI_ASSISTANT && !installed("dd.monjaro.navi")) continue;
            if (action == ButtonAction.DASHBOARD && !installed("com.auto_soft.monjaro_dashboard")) continue;
            actions.add(action);
        }
        Spinner selector = new Spinner(this);
        selector.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, actions));
        selector.setSelection(Math.max(0, actions.indexOf(original.action))); fields.addView(selector);
        EditText app = edit(original.application, "Приложение: пакет / Activity");
        Button choose = button("Выбрать приложение…", () -> chooseApp(app));
        EditText command = edit(original.command, "Команда"), target = edit(original.packageName, "Приложение (пакет получателя)");
        TextView help = text("Параметры: es:имя:текст, ei:имя:число, ez:имя:true. Флаги: ef:flags:число.", 14);
        String[] shortcut = {original.shortcutJson};
        TextView assigned = text(shortcutTitle(shortcut[0]), 16);
        Button driver = button("Выбрать действие меню водителя…", () -> {
            ShortcutActionPicker picker = new ShortcutActionPicker(this, new Preferences(this), value -> {
                try {
                    shortcut[0] = LauncherShortcutStore.encodeAction(value);
                    selector.setSelection(actions.indexOf(ButtonAction.DRIVER_MENU));
                    assigned.setText(value.title);
                }
                catch (org.json.JSONException error) { toast("Не удалось сохранить действие"); }
            });
            if (shortcut[0].isEmpty()) picker.showNew();
            else try { picker.showPrimary(LauncherShortcutStore.decodeAction(shortcut[0])); }
            catch (org.json.JSONException error) { picker.showNew(); }
        });
        driver.setText("Действия Natro…");
        fields.addView(text("Действия Natro — все функции меню экрана водителя", 16), 0);
        fields.addView(driver, 1);
        for (View view : new View[]{app, choose, command, target, help, assigned}) fields.addView(view);
        Runnable visibility = () -> {
            ButtonAction.Parameter parameter = ((ButtonAction) selector.getSelectedItem()).parameter;
            visible(app, parameter == ButtonAction.Parameter.APP); visible(choose, parameter == ButtonAction.Parameter.APP);
            boolean intent = parameter == ButtonAction.Parameter.BROADCAST || parameter == ButtonAction.Parameter.ACTIVITY;
            visible(command, intent || parameter == ButtonAction.Parameter.COMMAND || parameter == ButtonAction.Parameter.PHONE);
            visible(target, intent); visible(help, intent);
            visible(driver, true); visible(assigned, parameter == ButtonAction.Parameter.SHORTCUT);
            command.setHint(parameter == ButtonAction.Parameter.PHONE ? "Номер телефона"
                    : parameter == ButtonAction.Parameter.ACTIVITY ? "Полное имя Activity" : "Команда");
        };
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { visibility.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        }); visibility.run();
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            ButtonBinding assignment = new ButtonBinding(((ButtonAction) selector.getSelectedItem()).id,
                    app.getText().toString(), command.getText().toString(), target.getText().toString(), shortcut[0]);
            String error = assignment.validationError();
            if (!error.isEmpty()) { toast(error); return; }
            buttons.saveBinding(group, gesture, assignment, () -> { dialog.dismiss(); showTab(); });
        }));
        dialog.show();
    }
    private String bindingLabel(String gesture, String group, String key) {
        ButtonBinding binding = buttons.binding(group, key);
        return gesture + "\n" + (binding.action == ButtonAction.DRIVER_MENU
                ? shortcutTitle(binding.shortcutJson) : binding.action.toString());
    }
    private boolean installed(String pkg) {
        try { getPackageManager().getApplicationInfo(pkg, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return false; }
    }
    private void chooseApp(EditText target) {
        AlertDialog loading = new AlertDialog.Builder(this).setMessage("Загрузка приложений…").create(); loading.show();
        CompletableFuture.supplyAsync(() -> InstalledAppCatalog.load(this)).whenComplete((apps, failure) -> runOnUiThread(() -> {
            if (isDestroyed() || !loading.isShowing()) return; loading.dismiss();
            if (failure != null) { toast("Не удалось загрузить приложения"); return; }
            List<InstalledAppCatalog.App> available = new ArrayList<>();
            for (InstalledAppCatalog.App app : apps) if (app.launchable()) available.add(app);
            String[] labels = new String[available.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = available.get(i).label + " · " + available.get(i).packageName;
            new AlertDialog.Builder(this).setTitle("Приложение").setItems(labels, (dialog, which) -> {
                InstalledAppCatalog.App app = available.get(which);
                target.setText(app.label + " [" + app.component.getPackageName() + " / " + app.component.getClassName() + "]");
            }).setNegativeButton("Отмена", null).show();
        }));
    }
    private void actionOptions() {
        LinearLayout values = column(); values.setPadding(20, 12, 20, 12);
        EditText wifi = edit(buttons.string("wifi.ssid"), "Имя сохранённой сети Wi-Fi (SSID)");
        Switch connect = toggle("Подключение к выбранной сети Wi-Fi", buttons.bool("wifi.connect"));
        Switch camera = toggle("Камера 360 через broadcast", buttons.bool("camera.use_broadcast"));
        values.addView(connect); values.addView(wifi); values.addView(camera);
        Switch restore = toggle("Запоминать и восстанавливать режим движения", buttons.bool("drive.restore"));
        values.addView(restore);
        int[] driveModes = {-1, 570491138, 570491158, 570491137, 570491139, 570491155, 570491149, 570491145};
        Spinner startupMode = new Spinner(this);
        startupMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"После запуска: последний режим", "Комфорт", "Адаптивный", "Экономичный", "Спорт", "Внедорожный", "Песок", "Снег"}));
        for (int i = 0; i < driveModes.length; i++) if (driveModes[i] == buttons.integer("drive.start_mode", -1)) startupMode.setSelection(i);
        values.addView(startupMode);
        values.addView(text("Полноэкранный режим", 16));
        Spinner fullscreen = new Spinner(this); fullscreen.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Цикл: обычный → скрыть статус → скрыть всё", "Скрыть строку статуса", "Скрыть все панели"}));
        fullscreen.setSelection(buttons.integer("fullscreen.mode", 0)); values.addView(fullscreen);
        ScrollView scroll = new ScrollView(this); scroll.addView(values);
        new AlertDialog.Builder(this).setTitle("Параметры действий").setView(scroll).setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    buttons.put("wifi.ssid", wifi.getText().toString().trim()); buttons.put("wifi.connect", connect.isChecked());
                    buttons.put("camera.use_broadcast", camera.isChecked()); buttons.put("fullscreen.mode", fullscreen.getSelectedItemPosition());
                    buttons.put("drive.start_mode", driveModes[startupMode.getSelectedItemPosition()]);
                    buttons.put("drive.restore", restore.isChecked());
                }).show();
    }
    private String shortcutTitle(String json) {
        try { return json.isEmpty() ? "Не выбрано" : LauncherShortcutStore.decodeAction(json).title; }
        catch (org.json.JSONException error) { return "Выберите действие заново"; }
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private View horizontal(View child) { HorizontalScrollView view = new HorizontalScrollView(this); view.addView(child); return view; }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(-1); view.setPadding(0, 12, 0, 12); return view; }
    private Button button(String label, Runnable action) { Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run()); return view; }
    private EditText edit(String value, String hint) { EditText view = new EditText(this); view.setText(value); view.setHint(hint); view.setInputType(android.text.InputType.TYPE_CLASS_TEXT); return view; }
    private Switch toggle(String label, boolean checked) { Switch view = new Switch(this); view.setText(label); view.setTextColor(-1); view.setTextSize(18); view.setMinHeight(64); view.setChecked(checked); return view; }
    private static void visible(View view, boolean show) { view.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
