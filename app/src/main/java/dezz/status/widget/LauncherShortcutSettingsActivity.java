/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.LauncherRuleIdPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutPicker;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;

/** Visual, code-free editor for arbitrary HOME icons. */
public final class LauncherShortcutSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_ADD_NEW = "dezz.status.widget.extra.ADD_HOME_SHORTCUT";

    private Preferences preferences;
    private LauncherShortcutStore store;
    private LinearLayout itemsHost;
    private boolean addHandled;
    private boolean editingLongAction;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new LauncherShortcutStore(preferences);
        setTitle("Иконки HOME");
        setContentView(buildScreen());
        refresh();
        if (savedInstanceState != null) addHandled = savedInstanceState.getBoolean("addHandled");
        if (!addHandled && getIntent().getBooleanExtra(EXTRA_ADD_NEW, false)) {
            addHandled = true;
            itemsHost.post(this::chooseKindForNew);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("addHandled", addHandled);
        super.onSaveInstanceState(outState);
    }

    @NonNull
    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(30));
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));

        MaterialButton back = new MaterialButton(this);
        back.setText("←  Назад к HOME");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(230), dp(52));
        backLp.bottomMargin = dp(8);
        content.addView(back, backLp);

        TextView title = text(25, true);
        title.setText("Иконки, функции и приложения");
        content.addView(title);
        TextView hint = text(15, false);
        hint.setText("Нажатие на всю плитку выполняет действие. Долгое нажатие на HOME открывает её настройку. Порядок можно изменить стрелками.");
        hint.setAlpha(.75f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(match(), wrap());
        hintLp.bottomMargin = dp(12);
        content.addView(hint, hintLp);

        MaterialButton add = new MaterialButton(this);
        add.setText("+  Добавить иконку");
        add.setAllCaps(false);
        add.setOnClickListener(v -> chooseKindForNew());
        content.addView(add, new LinearLayout.LayoutParams(match(), dp(56)));

        itemsHost = new LinearLayout(this);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(match(), wrap());
        hostLp.topMargin = dp(12);
        content.addView(itemsHost, hostLp);
        return scroll;
    }

    private void refresh() {
        store.load();
        itemsHost.removeAllViews();
        List<LauncherShortcutStore.Shortcut> values = store.all();
        if (values.isEmpty()) {
            TextView empty = text(17, false);
            empty.setText("Иконок пока нет. Нажмите «+», чтобы добавить первую.");
            itemsHost.addView(empty);
            return;
        }
        for (LauncherShortcutStore.Shortcut value : values) itemsHost.addView(buildRow(value));
    }

    @NonNull
    private View buildRow(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(v -> showItemMenu(shortcut));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(8), dp(10));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(18, true);
        name.setText(shortcut.title);
        TextView type = text(13, false);
        type.setAlpha(.7f);
        type.setText(typeLabel(shortcut));
        labels.addView(name);
        labels.addView(type);
        row.addView(labels, new LinearLayout.LayoutParams(0, wrap(), 1f));

        MaterialButton up = compactButton("↑");
        up.setOnClickListener(v -> { store.move(shortcut.id, -1); refresh(); });
        MaterialButton down = compactButton("↓");
        down.setOnClickListener(v -> { store.move(shortcut.id, 1); refresh(); });
        MaterialButton edit = compactButton("✎");
        edit.setOnClickListener(v -> showItemMenu(shortcut));
        row.addView(up);
        row.addView(down);
        row.addView(edit);
        card.addView(row, new MaterialCardView.LayoutParams(match(), wrap()));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(match(), wrap());
        cardLp.bottomMargin = dp(9);
        card.setLayoutParams(cardLp);
        return card;
    }

    private void chooseKindForNew() {
        editingLongAction = false;
        String[] choices = {"Приложение", "Готовая функция", "Функция автомобиля",
                "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this)
                .setTitle("Что добавить?")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) chooseApplication(null);
                    else if (which == 1) chooseBuiltin(null);
                    else if (which == 2) chooseCarControl(null);
                    else if (which == 3) chooseRule(null);
                    else editIntentTarget(null);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showItemMenu(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        String[] choices = {"Оформление и размер", "Действие по нажатию",
                "Действие по долгому нажатию", "Удалить"};
        new AlertDialog.Builder(this).setTitle(shortcut.title)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) { editingLongAction = false; editAppearance(shortcut.copy()); }
                    else if (which == 1) chooseKindForExisting(shortcut.copy());
                    else if (which == 2) chooseLongKind(shortcut.copy());
                    else confirmDelete(shortcut);
                }).show();
    }

    private void chooseKindForExisting(@NonNull LauncherShortcutStore.Shortcut value) {
        editingLongAction = false;
        String[] choices = {"Приложение", "Готовая функция", "Функция автомобиля",
                "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this).setTitle("Новое действие")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) chooseApplication(value);
                    else if (which == 1) chooseBuiltin(value);
                    else if (which == 2) chooseCarControl(value);
                    else if (which == 3) chooseRule(value);
                    else editIntentTarget(value);
                }).show();
    }

    private void chooseLongKind(@NonNull LauncherShortcutStore.Shortcut value) {
        String[] choices = {"Без действия", "Приложение", "Готовая функция",
                "Функция автомобиля", "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this).setTitle("Долгое нажатие")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        editingLongAction = false;
                        value.hasLongAction = false;
                        value.longTarget = "";
                        value.longPackageName = "";
                        store.upsert(value);
                        refresh();
                        return;
                    }
                    editingLongAction = true;
                    if (which == 1) chooseApplication(value);
                    else if (which == 2) chooseBuiltin(value);
                    else if (which == 3) chooseCarControl(value);
                    else if (which == 4) chooseRule(value);
                    else editIntentTarget(value);
                }).show();
    }

    private void chooseCarControl(@Nullable LauncherShortcutStore.Shortcut existing) {
        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle("Функции автомобиля")
                .setMessage("Проверяю функции, которые поддерживает эта магнитола…")
                .setNegativeButton(android.R.string.cancel, null).create();
        loading.show();
        CarIntegrations.get(this).requestControlCatalog(controls -> {
            if (isFinishing() || isDestroyed()) return;
            if (!loading.isShowing()) return;
            loading.dismiss();
            if (controls.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("Функции пока недоступны")
                        .setMessage("ECARX ещё не ответил или эта сборка запущена не на магнитоле. "
                                + "Включите зажигание и повторите через несколько секунд.")
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            String[] labels = new String[controls.size()];
            for (int index = 0; index < controls.size(); index++) {
                CarControlDescriptor control = controls.get(index);
                labels[index] = control.category + " · " + control.label
                        + (control.availability == CarControlDescriptor.Availability.UNKNOWN
                        ? "  (проверяется)" : "");
            }
            new AlertDialog.Builder(this).setTitle("Выберите функцию автомобиля")
                    .setItems(labels, (dialog, which) ->
                            chooseCarControlBehavior(existing, controls.get(which)))
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void chooseCarControlBehavior(@Nullable LauncherShortcutStore.Shortcut existing,
                                          @NonNull CarControlDescriptor control) {
        if (control.kind == CarControlDescriptor.Kind.RANGE) {
            chooseCarRange(existing, control);
            return;
        }
        if (control.kind == CarControlDescriptor.Kind.ACTION) {
            saveCarAction(existing, control, CarControlCommand.Operation.ACTIVATE, 1);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<CarControlCommand.Operation> operations = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        if (control.kind == CarControlDescriptor.Kind.TOGGLE) {
            labels.add("Переключать Вкл / Выкл");
            operations.add(CarControlCommand.Operation.TOGGLE);
            values.add(0d);
        } else {
            labels.add(control.kind == CarControlDescriptor.Kind.OPTIONS
                    ? "Переключать режимы по кругу" : "Переключать уровни по кругу");
            operations.add(CarControlCommand.Operation.CYCLE);
            values.add(0d);
        }
        for (CarControlDescriptor.Option option : control.options) {
            labels.add("Установить: " + option.label);
            operations.add(CarControlCommand.Operation.SET);
            values.add(option.value);
        }
        new AlertDialog.Builder(this).setTitle(control.label + " — нажатие")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        saveCarAction(existing, control, operations.get(which), values.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseCarRange(@Nullable LauncherShortcutStore.Shortcut existing,
                                @NonNull CarControlDescriptor control) {
        LinearLayout form = dialogForm();
        TextView current = formLabel("");
        form.addView(current);
        SeekBar seek = new SeekBar(this);
        int steps = Math.max(1, (int) Math.round(
                (control.maximum - control.minimum) / control.step));
        seek.setMax(steps);
        double initial = control.minimum + (control.maximum - control.minimum) / 2d;
        if (existing != null && existing.kind == LauncherShortcutStore.Kind.CAR
                && existing.target.equals(control.id)
                && existing.command == CarControlCommand.Operation.SET) {
            initial = existing.commandValue;
        }
        seek.setProgress(Math.max(0, Math.min(steps,
                (int) Math.round((initial - control.minimum) / control.step))));
        Runnable update = () -> {
            double value = control.minimum + seek.getProgress() * control.step;
            current.setText(control.label + ": "
                    + String.format(Locale.ROOT, "%.1f", value) + control.unit);
        };
        update.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(seek, new LinearLayout.LayoutParams(match(), dp(54)));
        new AlertDialog.Builder(this).setTitle("Целевая температура")
                .setView(form).setPositiveButton("Выбрать", (dialog, which) -> {
                    double value = control.minimum + seek.getProgress() * control.step;
                    saveCarAction(existing, control, CarControlCommand.Operation.SET, value);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void saveCarAction(@Nullable LauncherShortcutStore.Shortcut existing,
                               @NonNull CarControlDescriptor control,
                               @NonNull CarControlCommand.Operation operation, double value) {
        LauncherShortcutStore.Shortcut shortcut = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        if (editingLongAction) {
            shortcut.hasLongAction = true;
            shortcut.longKind = LauncherShortcutStore.Kind.CAR;
            shortcut.longTarget = control.id;
            shortcut.longPackageName = "";
            shortcut.longCommand = operation;
            shortcut.longCommandValue = value;
            editingLongAction = false;
            store.upsert(shortcut);
            refresh();
            Toast.makeText(this, "Долгое нажатие настроено", Toast.LENGTH_SHORT).show();
            return;
        }
        shortcut.kind = LauncherShortcutStore.Kind.CAR;
        shortcut.target = control.id;
        shortcut.packageName = "";
        shortcut.command = operation;
        shortcut.commandValue = value;
        shortcut.title = control.label;
        shortcut.icon = control.iconKey;
        shortcut.iconColor = "#99FFFFFF";
        shortcut.activeIconColor = control.suggestedActiveColor;
        shortcut.useVehicleStateColor = true;
        shortcut.showState = control.kind != CarControlDescriptor.Kind.ACTION;
        editAppearance(shortcut);
    }

    private void chooseRule(@Nullable LauncherShortcutStore.Shortcut existing) {
        String[] sources = {"Новое действие из полного каталога",
                "Ранее настроенное Intent-действие"};
        new AlertDialog.Builder(this).setTitle("Действие устройства / сценарий")
                .setItems(sources, (dialog, which) -> {
                    if (which == 0) {
                        new SmartHomeShortcutPicker(this,
                                selection -> saveCatalogAction(existing, selection))
                                .showConnectorPicker();
                    } else chooseExistingRule(existing);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseExistingRule(@Nullable LauncherShortcutStore.Shortcut existing) {
        final List<IntentActionRule> rules;
        try {
            rules = new IntentActionRuleStore(preferences).loadStrict();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "Настройки Intent-сценариев повреждены", Toast.LENGTH_LONG).show();
            return;
        }
        List<IntentActionRule> enabled = new ArrayList<>();
        for (IntentActionRule rule : rules) if (rule.enabled) enabled.add(rule);
        if (enabled.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет готовых действий")
                    .setMessage("Сначала создайте в визуальном редакторе Intent-действие для HA, MQTT или Sprut.hub, а затем выберите его здесь.")
                    .setPositiveButton("Открыть редактор", (dialog, which) ->
                            startActivity(new Intent(this, IntentScenarioSettingsActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        String[] labels = new String[enabled.size()];
        for (int i = 0; i < enabled.size(); i++) {
            IntentActionRule rule = enabled.get(i);
            String target = rule.accessoryLabel;
            if (!rule.serviceLabel.isEmpty()) target += " · " + rule.serviceLabel;
            if (target.trim().isEmpty()) target = rule.id;
            labels[i] = target;
        }
        new AlertDialog.Builder(this).setTitle("Выберите действие")
                .setItems(labels, (dialog, which) -> {
                    IntentActionRule rule = enabled.get(which);
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.RULE, rule.id, "");
                    } else {
                        value.kind = LauncherShortcutStore.Kind.RULE;
                        value.target = rule.id;
                        value.packageName = "";
                        value.title = labels[which];
                        if (!value.iconCustomized) value.icon = "devices";
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }).show();
    }

    private void saveCatalogAction(@Nullable LauncherShortcutStore.Shortcut existing,
                                   @NonNull SmartHomeShortcutPicker.Selection selection) {
        try {
            IntentActionRuleStore ruleStore = new IntentActionRuleStore(preferences);
            List<IntentActionRule> rules = new ArrayList<>(ruleStore.loadStrict());
            String reusable = existing == null ? "" : LauncherRuleIdPolicy.reusableId(
                    editingLongAction,
                    existing.kind == LauncherShortcutStore.Kind.RULE, existing.target,
                    existing.hasLongAction,
                    existing.longKind == LauncherShortcutStore.Kind.RULE, existing.longTarget);
            String ruleId = reusable.isEmpty() ? nextLauncherRuleId(rules) : reusable;
            String token = IntentActionRule.newTriggerToken();
            String actionToken = IntentActionRule.newTriggerToken();
            String actionPrefix = "dezz.statuswidget.launcher." + ruleId
                    .replace('-', '_').replace('.', '_');
            IntentActionRule replacement = new IntentActionRule(ruleId, true,
                    IntentActionRule.secureIntentAction(actionPrefix, actionToken), token,
                    selection.command, selection.title, selection.details,
                    selection.command.resourceId);
            int replaceAt = -1;
            for (int index = 0; index < rules.size(); index++) {
                if (rules.get(index).id.equals(ruleId)) {
                    replaceAt = index;
                    break;
                }
            }
            if (replaceAt >= 0) rules.set(replaceAt, replacement);
            else {
                if (rules.size() >= IntentActionRuleStore.MAX_RULES) {
                    throw new IllegalArgumentException("Достигнут лимит действий: "
                            + IntentActionRuleStore.MAX_RULES);
                }
                rules.add(replacement);
            }
            ruleStore.save(rules);
            WidgetService running = WidgetService.getInstance();
            if (running != null) running.applyPreferences();

            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            if (editingLongAction) {
                saveLongAction(value, LauncherShortcutStore.Kind.RULE, ruleId, "");
                return;
            }
            value.kind = LauncherShortcutStore.Kind.RULE;
            value.target = ruleId;
            value.packageName = "";
            value.title = selection.title;
            if (!value.iconCustomized) value.icon = selection.iconKey;
            value.iconColor = "#FFFFFFFF";
            editAppearance(value);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось сохранить действие: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private static String nextLauncherRuleId(@NonNull List<IntentActionRule> rules) {
        int suffix = 1;
        while (true) {
            String candidate = "launcher_" + suffix++;
            boolean used = false;
            for (IntentActionRule rule : rules) if (rule.id.equals(candidate)) {
                used = true;
                break;
            }
            if (!used) return candidate;
        }
    }

    private void chooseApplication(@Nullable LauncherShortcutStore.Shortcut existing) {
        List<AppChoice> apps = queryApplications();
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setVerticalSpacing(dp(8));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new AppChoiceAdapter(apps));
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle("Выберите приложение")
                .setView(grid).setNegativeButton(android.R.string.cancel, null).create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            picker.dismiss();
            AppChoice app = apps.get(position);
            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            if (editingLongAction) {
                saveLongAction(value, LauncherShortcutStore.Kind.APP,
                        app.component.flattenToString(), app.component.getPackageName());
            } else {
                value.kind = LauncherShortcutStore.Kind.APP;
                value.target = app.component.flattenToString();
                value.packageName = app.component.getPackageName();
                value.title = app.label;
                value.icon = "app";
                value.iconColor = "none";
                editAppearance(value);
            }
        });
        picker.show();
    }

    private void chooseBuiltin(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Builtin[] actions = LauncherShortcutStore.Builtin.values();
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) labels[i] = actions[i].label;
        new AlertDialog.Builder(this).setTitle("Выберите функцию")
                .setItems(labels, (dialog, which) -> {
                    LauncherShortcutStore.Builtin action = actions[which];
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.BUILTIN, action.key, "");
                    } else {
                        value.kind = LauncherShortcutStore.Kind.BUILTIN;
                        value.target = action.key;
                        value.packageName = "";
                        value.title = action.label;
                        value.icon = action.icon;
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }).show();
    }

    private void editIntentTarget(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        LinearLayout form = dialogForm();
        EditText action = field("Действие Intent, например sh.car.parkovka",
                editingLongAction && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longTarget
                        : value.kind == LauncherShortcutStore.Kind.INTENT ? value.target : "");
        EditText packageName = field("Целевой package (можно оставить пустым)",
                editingLongAction && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longPackageName
                        : value.kind == LauncherShortcutStore.Kind.INTENT ? value.packageName : "");
        form.addView(action);
        form.addView(packageName);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Android Intent")
                .setView(scrollDialog(form)).setPositiveButton("Далее", null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (action.getText().toString().trim().isEmpty()) {
                        action.setError("Укажите действие");
                        return;
                    }
                    dialog.dismiss();
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.INTENT,
                                action.getText().toString().trim(), packageName.getText().toString().trim());
                    } else {
                        value.kind = LauncherShortcutStore.Kind.INTENT;
                        value.target = action.getText().toString().trim();
                        value.packageName = packageName.getText().toString().trim();
                        if (existing == null) value.title = value.target;
                        value.icon = "power";
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }));
        dialog.show();
    }

    private void saveLongAction(@NonNull LauncherShortcutStore.Shortcut value,
                                @NonNull LauncherShortcutStore.Kind kind,
                                @NonNull String target, @NonNull String packageName) {
        value.hasLongAction = true;
        value.longKind = kind;
        value.longTarget = target;
        value.longPackageName = packageName;
        editingLongAction = false;
        store.upsert(value);
        refresh();
        Toast.makeText(this, "Долгое нажатие настроено", Toast.LENGTH_SHORT).show();
    }

    private void editAppearance(@NonNull LauncherShortcutStore.Shortcut value) {
        LinearLayout form = dialogForm();
        EditText title = field("Название", value.title);
        form.addView(title);

        TextView iconLabel = formLabel("Иконка");
        form.addView(iconLabel);
        Spinner icon = new Spinner(this);
        List<LauncherIconResolver.Preset> presets = LauncherIconResolver.presets();
        String automaticIcon = value.icon;
        icon.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presets));
        int selectedIcon = 0;
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).key.equals(value.icon)) selectedIcon = i;
        icon.setSelection(selectedIcon);
        form.addView(icon, new LinearLayout.LayoutParams(match(), dp(54)));

        EditText background = field("Цвет плитки (#AARRGGBB)", value.backgroundColor);
        EditText iconColor = field("Цвет иконки (#AARRGGBB или none)", value.iconColor);
        EditText textColor = field("Цвет названия (#AARRGGBB)", value.textColor);
        form.addView(background);
        form.addView(iconColor);
        form.addView(textColor);

        EditText activeBackground = field("Цвет активной плитки (#AARRGGBB)",
                value.activeBackgroundColor);
        EditText activeIcon = field("Цвет активной иконки (#AARRGGBB)",
                value.activeIconColor);
        MaterialSwitch vehicleStateColor = new MaterialSwitch(this);
        vehicleStateColor.setText("Цвет уровня задаёт автомобиль");
        vehicleStateColor.setChecked(value.useVehicleStateColor);
        MaterialSwitch showState = new MaterialSwitch(this);
        showState.setText("Показывать текущий режим / уровень");
        showState.setChecked(value.showState);
        if (value.kind == LauncherShortcutStore.Kind.CAR) {
            form.addView(activeBackground);
            form.addView(activeIcon);
            form.addView(vehicleStateColor);
            form.addView(showState);
        }

        SeekValue iconSize = seek(form, "Размер иконки", 24, 180, value.iconSizePx, " px");
        SeekValue width = seek(form, "Ширина плитки", 1, 4, value.columnSpan, " яч.");
        SeekValue height = seek(form, "Высота плитки", 1, 4, value.rowSpan, " яч.");
        MaterialSwitch showTitle = new MaterialSwitch(this);
        showTitle.setText("Показывать название");
        showTitle.setChecked(value.showTitle);
        form.addView(showTitle);
        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText("Иконка включена");
        enabled.setChecked(value.enabled);
        form.addView(enabled);

        new AlertDialog.Builder(this).setTitle("Оформление иконки")
                .setView(scrollDialog(form)).setPositiveButton("Применить", (dialog, which) -> {
                    value.title = title.getText().toString().trim();
                    value.icon = presets.get(icon.getSelectedItemPosition()).key;
                    if (!value.icon.equals(automaticIcon)) value.iconCustomized = true;
                    value.backgroundColor = validColor(background.getText().toString(), "#B5222733");
                    value.iconColor = "none".equalsIgnoreCase(iconColor.getText().toString().trim())
                            ? "none" : validColor(iconColor.getText().toString(), "#FFFFFFFF");
                    value.textColor = validColor(textColor.getText().toString(), "#FFFFFFFF");
                    if (value.kind == LauncherShortcutStore.Kind.CAR) {
                        value.activeBackgroundColor = validColor(
                                activeBackground.getText().toString(), "#CC374151");
                        value.activeIconColor = validColor(activeIcon.getText().toString(),
                                "#FFFFB300");
                        value.useVehicleStateColor = vehicleStateColor.isChecked();
                        value.showState = showState.isChecked();
                    }
                    value.iconSizePx = iconSize.value;
                    value.columnSpan = width.value;
                    value.rowSpan = height.value;
                    value.showTitle = showTitle.isChecked();
                    value.enabled = enabled.isChecked();
                    store.upsert(value);
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmDelete(LauncherShortcutStore.Shortcut value) {
        new AlertDialog.Builder(this).setTitle("Удалить «" + value.title + "»?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    store.remove(value.id);
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @NonNull
    private List<AppChoice> queryApplications() {
        PackageManager manager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppChoice> values = new ArrayList<>();
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null) continue;
            values.add(new AppChoice(String.valueOf(info.loadLabel(manager)),
                    new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    info.loadIcon(manager)));
        }
        values.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return values;
    }

    @NonNull
    private String typeLabel(LauncherShortcutStore.Shortcut value) {
        switch (value.kind) {
            case APP: return longSuffix("Приложение · " + value.packageName, value);
            case RULE: return longSuffix("Действие устройства · " + value.target, value);
            case INTENT: return longSuffix("Intent · " + value.target, value);
            case CAR: return longSuffix("Автомобиль · " + value.target + " · "
                    + carOperationLabel(value.command, value.commandValue), value);
            case BUILTIN:
            default: return longSuffix("Функция · "
                    + LauncherShortcutStore.Builtin.fromKey(value.target).label, value);
        }
    }

    private String carOperationLabel(CarControlCommand.Operation operation, double value) {
        switch (operation) {
            case CYCLE: return "следующее значение";
            case SET: return "установить " + String.format(Locale.ROOT, "%s", value);
            case ACTIVATE: return "выполнить";
            case TOGGLE:
            default: return "переключить";
        }
    }

    private String longSuffix(String base, LauncherShortcutStore.Shortcut value) {
        return value.hasLongAction ? base + "  ·  долгое нажатие настроено" : base;
    }

    private String validColor(String candidate, String fallback) {
        String value = candidate.trim();
        try { Color.parseColor(value); return value; }
        catch (IllegalArgumentException ignored) {
            Toast.makeText(this, "Неверный цвет заменён на " + fallback, Toast.LENGTH_SHORT).show();
            return fallback;
        }
    }

    private SeekValue seek(LinearLayout parent, String label, int min, int max, int current, String suffix) {
        TextView title = formLabel(label + ": " + current + suffix);
        parent.addView(title);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        SeekValue value = new SeekValue(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.value = min + progress;
                title.setText(label + ": " + value.value + suffix);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        parent.addView(bar, new LinearLayout.LayoutParams(match(), dp(46)));
        return value;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        return form;
    }

    private ScrollView scrollDialog(@NonNull View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));
        return scroll;
    }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setLayoutParams(new LinearLayout.LayoutParams(match(), dp(58)));
        return input;
    }

    private TextView formLabel(String value) {
        TextView label = text(15, false);
        label.setText(value);
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }

    private MaterialButton compactButton(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(20);
        button.setMinWidth(dp(48));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return button;
    }

    private TextView text(float size, boolean bold) {
        TextView value = new TextView(this);
        value.setTextSize(size);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    private final class AppChoiceAdapter extends BaseAdapter {
        private final List<AppChoice> values;
        AppChoiceAdapter(List<AppChoice> values) { this.values = values; }
        @Override public int getCount() { return values.size(); }
        @Override public Object getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout cell = recycled instanceof LinearLayout ? (LinearLayout) recycled
                    : new LinearLayout(LauncherShortcutSettingsActivity.this);
            cell.removeAllViews();
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(5), dp(5), dp(5), dp(5));
            AppChoice app = values.get(position);
            ImageView icon = new ImageView(LauncherShortcutSettingsActivity.this);
            icon.setImageDrawable(app.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
            TextView label = text(12, false);
            label.setText(app.label);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(match(), dp(38)));
            return cell;
        }
    }

    private static final class AppChoice {
        final String label;
        final ComponentName component;
        final android.graphics.drawable.Drawable icon;
        AppChoice(String label, ComponentName component, android.graphics.drawable.Drawable icon) {
            this.label = label; this.component = component; this.icon = icon;
        }
    }

    private static final class SeekValue { int value; SeekValue(int value) { this.value = value; } }
    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
