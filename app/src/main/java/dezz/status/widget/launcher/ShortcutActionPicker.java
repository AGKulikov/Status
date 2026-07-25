/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.graphics.Color;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.routes.FavoriteRouteConfig;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;

/**
 * Shared action editor for driver-panel and drawer collections.
 *
 * <p>It deliberately edits only what an action means. Tile layout/appearance remains owned by
 * the surface-specific settings activity, while app/system-app, car, smart-home and long-press
 * semantics stay identical everywhere.</p>
 */
public final class ShortcutActionPicker {
    private static final String DRIVE_MODE_CONTROL_ID = "vehicle.drive_mode";

    private final AppCompatActivity activity;
    private final Preferences preferences;
    private final LauncherShortcutStore store;
    private final Runnable changed;
    private boolean longPress;

    public ShortcutActionPicker(@NonNull AppCompatActivity activity,
                                @NonNull Preferences preferences,
                                @NonNull LauncherShortcutStore store,
                                @NonNull Runnable changed) {
        this.activity = activity;
        this.preferences = preferences;
        this.store = store;
        this.changed = changed;
    }

    public void showNew() {
        longPress = false;
        chooseKind(null, "Что добавить?");
    }

    public void showPrimary(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        longPress = false;
        chooseKind(shortcut.copy(), "Действие по нажатию");
    }

    public void showLong(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        LauncherShortcutStore.Shortcut value = shortcut.copy();
        String[] values = {"Без действия", "Приложение", "Готовая функция",
                "Избранная точка навигации", "Функция автомобиля",
                "Устройство умного дома / сценарий", "Android Intent"};
        new AlertDialog.Builder(activity).setTitle("Долгое нажатие")
                .setItems(values, (dialog, which) -> {
                    if (which == 0) {
                        value.hasLongAction = false;
                        value.longTarget = "";
                        value.longPackageName = "";
                        store.upsert(value);
                        changed.run();
                        return;
                    }
                    longPress = true;
                    chooseKindIndex(value, which - 1);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseKind(@Nullable LauncherShortcutStore.Shortcut value,
                            @NonNull String title) {
        String[] values = {"Приложение", "Готовая функция", "Избранная точка навигации",
                "Функция автомобиля", "Устройство умного дома / сценарий", "Android Intent"};
        new AlertDialog.Builder(activity).setTitle(title)
                .setItems(values, (dialog, which) -> chooseKindIndex(value, which))
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseKindIndex(@Nullable LauncherShortcutStore.Shortcut value, int which) {
        if (which == 0) chooseApplication(value);
        else if (which == 1) chooseBuiltin(value);
        else if (which == 2) chooseFavoriteRoute(value);
        else if (which == 3) chooseCarControl(value);
        else if (which == 4) chooseSmartHome(value);
        else editIntent(value);
    }

    private void chooseFavoriteRoute(
            @Nullable LauncherShortcutStore.Shortcut existing) {
        List<FavoriteRouteConfig> routes =
                new FavoriteRoutesConfigStore(preferences).load();
        if (routes.isEmpty()) {
            toast("Сначала добавьте точку в «Избранной навигации»");
            return;
        }
        String[] labels = new String[routes.size()];
        for (int index = 0; index < routes.size(); index++) {
            FavoriteRouteConfig route = routes.get(index);
            labels[index] = route.title + (route.coordinates.trim().isEmpty()
                    ? "\nДобавьте координаты для прямого маршрута" : "");
        }
        new AlertDialog.Builder(activity).setTitle("Избранная точка")
                .setItems(labels, (dialog, which) -> {
                    FavoriteRouteConfig route = routes.get(which);
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.ROUTE, route.id, "",
                                CarControlCommand.Operation.ACTIVATE, 1);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.ROUTE;
                        value.target = route.id;
                        value.packageName = "";
                        value.title = route.title;
                        value.icon = route.icon;
                        value.iconCustomized = false;
                        value.iconColor = route.iconColor;
                        value.stateBinding = null;
                        save(value);
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseApplication(@Nullable LauncherShortcutStore.Shortcut existing) {
        AlertDialog loading = new AlertDialog.Builder(activity)
                .setTitle("Все приложения · включая системные")
                .setMessage("Загружаю список…").setNegativeButton("Отмена", null).create();
        loading.show();
        CompletableFuture.supplyAsync(() -> InstalledAppCatalog.load(activity))
                .whenComplete((apps, failure) -> activity.runOnUiThread(() -> {
                    if (!loading.isShowing() || activity.isFinishing()
                            || activity.isDestroyed()) return;
                    loading.dismiss();
                    if (failure != null || apps == null) {
                        toast("Не удалось загрузить приложения");
                        return;
                    }
                    showApplications(existing, apps);
                }));
    }

    private void showApplications(@Nullable LauncherShortcutStore.Shortcut existing,
                                  @NonNull List<InstalledAppCatalog.App> apps) {
        List<String> labels = new ArrayList<>(apps.size());
        for (InstalledAppCatalog.App app : apps) {
            labels.add(app.label + "\n" + app.secondaryLabel());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_list_item_1, labels);
        new AlertDialog.Builder(activity).setTitle("Приложение")
                .setAdapter(adapter, (dialog, which) -> {
                    InstalledAppCatalog.App app = apps.get(which);
                    if (!app.launchable() || app.component == null) {
                        toast("У приложения нет доступного экрана");
                        return;
                    }
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.APP,
                                app.component.flattenToString(), app.packageName,
                                CarControlCommand.Operation.TOGGLE, 0);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.APP;
                        value.target = app.component.flattenToString();
                        value.packageName = app.packageName;
                        value.title = app.label;
                        value.icon = "app";
                        value.iconCustomized = false;
                        value.iconColor = "none";
                        value.stateBinding = null;
                        save(value);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void chooseBuiltin(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Builtin[] actions = LauncherShortcutStore.Builtin.values();
        String[] labels = new String[actions.length];
        for (int index = 0; index < actions.length; index++) {
            labels[index] = actions[index].label;
        }
        new AlertDialog.Builder(activity).setTitle("Готовая функция")
                .setItems(labels, (dialog, which) -> {
                    LauncherShortcutStore.Builtin action = actions[which];
                    if (action == LauncherShortcutStore.Builtin.FAVORITES) {
                        chooseFavoriteBlock(existing);
                        return;
                    }
                    saveBuiltin(existing, action, action.key, action.label);
                }).setNegativeButton("Отмена", null).show();
    }

    private void chooseFavoriteBlock(@Nullable LauncherShortcutStore.Shortcut existing) {
        List<DriverFavoriteBlocksStore.Block> blocks =
                new DriverFavoriteBlocksStore(preferences).blocks();
        String[] labels = new String[blocks.size()];
        for (int index = 0; index < blocks.size(); index++) {
            labels[index] = blocks.get(index).title;
        }
        new AlertDialog.Builder(activity).setTitle("Какой блок открыть")
                .setItems(labels, (dialog, which) -> {
                    DriverFavoriteBlocksStore.Block block = blocks.get(which);
                    saveBuiltin(existing, LauncherShortcutStore.Builtin.FAVORITES,
                            DriverFavoriteBlocksStore.targetFor(block.id), block.title);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void saveBuiltin(@Nullable LauncherShortcutStore.Shortcut existing,
                             @NonNull LauncherShortcutStore.Builtin action,
                             @NonNull String target,
                             @NonNull String title) {
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.BUILTIN, target, "",
                                CarControlCommand.Operation.TOGGLE, 0);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.BUILTIN;
                        value.target = target;
                        value.packageName = "";
                        value.title = title;
                        value.icon = action.icon;
                        value.iconCustomized = false;
                        value.iconColor = "#FFE0E5F3";
                        value.stateBinding = null;
                        save(value);
                    }
    }

    private void chooseCarControl(@Nullable LauncherShortcutStore.Shortcut existing) {
        AlertDialog loading = new AlertDialog.Builder(activity).setTitle("Функции автомобиля")
                .setMessage("Проверяю функции магнитолы…")
                .setNegativeButton("Отмена", null).create();
        loading.show();
        CarIntegrations.get(activity).requestControlCatalog(controls -> {
            if (!loading.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
            loading.dismiss();
            if (controls.isEmpty()) {
                toast("ECARX пока не сообщил доступные функции");
                return;
            }
            String[] labels = new String[controls.size()];
            for (int index = 0; index < controls.size(); index++) {
                CarControlDescriptor control = controls.get(index);
                labels[index] = control.category + " · " + control.label;
            }
            new AlertDialog.Builder(activity).setTitle("Функция автомобиля")
                    .setItems(labels, (dialog, which) ->
                            chooseCarBehavior(existing, controls.get(which)))
                    .setNegativeButton("Отмена", null).show();
        });
    }

    private void chooseCarBehavior(@Nullable LauncherShortcutStore.Shortcut existing,
                                   @NonNull CarControlDescriptor control) {
        if (control.kind == CarControlDescriptor.Kind.ACTION) {
            saveCar(existing, control, CarControlCommand.Operation.ACTIVATE, 1);
            return;
        }
        if (control.kind == CarControlDescriptor.Kind.RANGE) {
            chooseRange(existing, control);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<CarControlCommand.Operation> operations = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        if (control.kind == CarControlDescriptor.Kind.TOGGLE) {
            labels.add("Переключать Вкл / Выкл");
            operations.add(CarControlCommand.Operation.TOGGLE);
        } else if (isDriveMode(control)) {
            labels.add("Переключать только выбранные режимы…");
            operations.add(CarControlCommand.Operation.CYCLE);
            labels.add("Переключать все доступные режимы");
            operations.add(CarControlCommand.Operation.CYCLE);
        } else {
            labels.add(control.kind == CarControlDescriptor.Kind.OPTIONS
                    ? "Переключать варианты" : "Переключать уровни");
            operations.add(CarControlCommand.Operation.CYCLE);
        }
        while (values.size() < operations.size()) values.add(0d);
        for (CarControlDescriptor.Option option : control.options) {
            labels.add("Установить: " + option.label);
            operations.add(CarControlCommand.Operation.SET);
            values.add(option.value);
        }
        new AlertDialog.Builder(activity).setTitle(control.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (isDriveMode(control) && which == 0) {
                        chooseDriveModeCycle(existing, control);
                    } else {
                        saveCar(existing, control, operations.get(which), values.get(which));
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseDriveModeCycle(
            @Nullable LauncherShortcutStore.Shortcut existing,
            @NonNull CarControlDescriptor control) {
        boolean[] checked = new boolean[control.options.size()];
        List<Double> current = Collections.emptyList();
        if (existing != null) {
            if (longPress && existing.hasLongAction
                    && existing.longKind == LauncherShortcutStore.Kind.CAR
                    && existing.longTarget.equals(control.id)) {
                current = existing.longCycleValues;
            } else if (!longPress && existing.kind == LauncherShortcutStore.Kind.CAR
                    && existing.target.equals(control.id)) {
                current = existing.cycleValues;
            }
        }
        for (int index = 0; index < control.options.size(); index++) {
            checked[index] = containsValue(current, control.options.get(index).value);
        }
        String[] labels = new String[control.options.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = control.options.get(index).label;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Режимы для переключения")
                .setMessage("Выберите минимум два. Порядок переключения — как в этом списке; "
                        + "неподдерживаемые автомобилем режимы будут пропущены.")
                .setMultiChoiceItems(labels, checked,
                        (ignored, which, selected) -> checked[which] = selected)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    List<Double> selected = new ArrayList<>();
                    for (int index = 0; index < checked.length; index++) {
                        if (checked[index]) selected.add(control.options.get(index).value);
                    }
                    if (selected.size() < 2) {
                        toast("Выберите минимум два режима");
                        return;
                    }
                    dialog.dismiss();
                    saveCar(existing, control, CarControlCommand.Operation.CYCLE, 0, selected);
                }));
        dialog.show();
    }

    private void chooseRange(@Nullable LauncherShortcutStore.Shortcut existing,
                             @NonNull CarControlDescriptor control) {
        LinearLayout form = column();
        TextView current = new TextView(activity);
        current.setTextColor(Color.WHITE);
        form.addView(current);
        SeekBar seek = new SeekBar(activity);
        int steps = Math.max(1, (int) Math.round(
                (control.maximum - control.minimum) / control.step));
        seek.setMax(steps);
        seek.setProgress(steps / 2);
        Runnable update = () -> current.setText(control.label + ": "
                + String.format(Locale.ROOT, "%.1f",
                control.minimum + seek.getProgress() * control.step) + control.unit);
        update.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(seek, new LinearLayout.LayoutParams(match(), dp(56)));
        new AlertDialog.Builder(activity).setTitle(control.label).setView(form)
                .setPositiveButton("Выбрать", (dialog, which) -> saveCar(existing, control,
                        CarControlCommand.Operation.SET,
                        control.minimum + seek.getProgress() * control.step))
                .setNegativeButton("Отмена", null).show();
    }

    private void saveCar(@Nullable LauncherShortcutStore.Shortcut existing,
                         @NonNull CarControlDescriptor control,
                         @NonNull CarControlCommand.Operation operation, double commandValue) {
        saveCar(existing, control, operation, commandValue, Collections.emptyList());
    }

    private void saveCar(@Nullable LauncherShortcutStore.Shortcut existing,
                         @NonNull CarControlDescriptor control,
                         @NonNull CarControlCommand.Operation operation, double commandValue,
                         @NonNull List<Double> cycleValues) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        if (longPress) {
            saveLong(value, LauncherShortcutStore.Kind.CAR, control.id, "",
                    operation, commandValue, cycleValues);
            return;
        }
        value.kind = LauncherShortcutStore.Kind.CAR;
        value.target = control.id;
        value.packageName = "";
        value.command = operation;
        value.commandValue = commandValue;
        value.cycleValues = new ArrayList<>(cycleValues);
        value.title = carShortcutTitle(control, operation, commandValue, cycleValues);
        value.icon = carShortcutIcon(control, operation, commandValue, cycleValues);
        value.iconCustomized = false;
        value.iconColor = "#99FFFFFF";
        value.activeIconColor = control.suggestedActiveColor;
        value.useVehicleStateColor = true;
        value.showState = control.kind != CarControlDescriptor.Kind.ACTION;
        value.stateBinding = null;
        save(value);
    }

    private void chooseSmartHome(@Nullable LauncherShortcutStore.Shortcut existing) {
        String[] sources = {"Новое действие из каталога", "Ранее настроенное действие"};
        new AlertDialog.Builder(activity).setTitle("Умный дом / сценарий")
                .setItems(sources, (dialog, which) -> {
                    if (which == 0) {
                        new SmartHomeShortcutPicker(activity,
                                selection -> saveCatalogAction(existing, selection))
                                .showConnectorPicker();
                    } else {
                        chooseExistingRule(existing);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void chooseExistingRule(@Nullable LauncherShortcutStore.Shortcut existing) {
        final List<IntentActionRule> rules;
        try {
            rules = new IntentActionRuleStore(preferences).loadStrict();
        } catch (RuntimeException error) {
            toast("Настройки действий повреждены");
            return;
        }
        List<IntentActionRule> enabled = new ArrayList<>();
        for (IntentActionRule rule : rules) if (rule.enabled) enabled.add(rule);
        if (enabled.isEmpty()) {
            toast("Ранее настроенных действий пока нет");
            return;
        }
        String[] labels = new String[enabled.size()];
        for (int index = 0; index < enabled.size(); index++) {
            IntentActionRule rule = enabled.get(index);
            labels[index] = rule.accessoryLabel.isEmpty() ? rule.id : rule.accessoryLabel;
        }
        new AlertDialog.Builder(activity).setTitle("Готовое действие")
                .setItems(labels, (dialog, which) -> {
                    IntentActionRule rule = enabled.get(which);
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.RULE, rule.id, "",
                                CarControlCommand.Operation.TOGGLE, 0);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.RULE;
                        value.target = rule.id;
                        value.packageName = "";
                        value.title = labels[which];
                        value.stateBinding = new SourceBinding(rule.command.connectorType,
                                rule.command.connectorId, rule.command.resourceId, "",
                                SourceBinding.PRESENTATION_AUTO, "");
                        if (!value.iconCustomized) value.icon = LauncherRuleIconPolicy.suggest(rule);
                        save(value);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void saveCatalogAction(@Nullable LauncherShortcutStore.Shortcut existing,
                                   @NonNull SmartHomeShortcutPicker.Selection selection) {
        try {
            IntentActionRuleStore ruleStore = new IntentActionRuleStore(preferences);
            List<IntentActionRule> rules = new ArrayList<>(ruleStore.loadStrict());
            String reusable = "";
            if (existing != null) {
                if (longPress && existing.hasLongAction
                        && existing.longKind == LauncherShortcutStore.Kind.RULE) {
                    reusable = existing.longTarget;
                } else if (!longPress && existing.kind == LauncherShortcutStore.Kind.RULE) {
                    reusable = existing.target;
                }
            }
            String ruleId = reusable.isEmpty() ? nextRuleId(rules) : reusable;
            String token = IntentActionRule.newTriggerToken();
            String actionToken = IntentActionRule.newTriggerToken();
            String prefix = "dezz.statuswidget.driver." + ruleId
                    .replace('-', '_').replace('.', '_');
            IntentActionRule replacement = new IntentActionRule(ruleId, true,
                    IntentActionRule.secureIntentAction(prefix, actionToken), token,
                    selection.command, selection.title, selection.details,
                    selection.command.resourceId);
            int replaceAt = -1;
            for (int index = 0; index < rules.size(); index++) {
                if (rules.get(index).id.equals(ruleId)) replaceAt = index;
            }
            if (replaceAt >= 0) rules.set(replaceAt, replacement);
            else rules.add(replacement);
            ruleStore.save(rules);
            WidgetService running = WidgetService.getInstance();
            if (running != null) running.applyPreferences();

            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            if (longPress) {
                saveLong(value, LauncherShortcutStore.Kind.RULE, ruleId, "",
                        CarControlCommand.Operation.TOGGLE, 0);
            } else {
                value.kind = LauncherShortcutStore.Kind.RULE;
                value.target = ruleId;
                value.packageName = "";
                value.title = selection.title;
                value.stateBinding = selection.stateBinding;
                if (!value.iconCustomized) value.icon = selection.iconKey;
                value.iconColor = "#FFFFFFFF";
                save(value);
            }
        } catch (RuntimeException error) {
            toast("Не удалось сохранить действие: " + safeMessage(error));
        }
    }

    private void editIntent(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        LinearLayout form = column();
        EditText action = field("Действие Intent",
                longPress && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longTarget
                        : value.kind == LauncherShortcutStore.Kind.INTENT ? value.target : "");
        EditText packageName = field("Целевой package (необязательно)",
                longPress && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longPackageName
                        : value.kind == LauncherShortcutStore.Kind.INTENT
                        ? value.packageName : "");
        form.addView(action);
        form.addView(packageName);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Android Intent")
                .setView(form).setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String target = action.getText().toString().trim();
                    if (target.isEmpty()) {
                        action.setError("Укажите действие");
                        return;
                    }
                    dialog.dismiss();
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.INTENT, target,
                                packageName.getText().toString().trim(),
                                CarControlCommand.Operation.TOGGLE, 0);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.INTENT;
                        value.target = target;
                        value.packageName = packageName.getText().toString().trim();
                        if (existing == null) value.title = target;
                        value.icon = "power";
                        value.iconColor = "#FFFFFFFF";
                        value.stateBinding = null;
                        save(value);
                    }
                }));
        dialog.show();
    }

    private void saveLong(@NonNull LauncherShortcutStore.Shortcut value,
                          @NonNull LauncherShortcutStore.Kind kind,
                          @NonNull String target, @NonNull String packageName,
                          @NonNull CarControlCommand.Operation command, double commandValue) {
        saveLong(value, kind, target, packageName, command, commandValue,
                Collections.emptyList());
    }

    private void saveLong(@NonNull LauncherShortcutStore.Shortcut value,
                          @NonNull LauncherShortcutStore.Kind kind,
                          @NonNull String target, @NonNull String packageName,
                          @NonNull CarControlCommand.Operation command, double commandValue,
                          @NonNull List<Double> cycleValues) {
        value.hasLongAction = true;
        value.longKind = kind;
        value.longTarget = target;
        value.longPackageName = packageName;
        value.longCommand = command;
        value.longCommandValue = commandValue;
        value.longCycleValues = new ArrayList<>(cycleValues);
        longPress = false;
        save(value);
        toast("Долгое нажатие настроено");
    }

    private static boolean isDriveMode(@NonNull CarControlDescriptor control) {
        return DRIVE_MODE_CONTROL_ID.equals(control.id);
    }

    private static boolean containsValue(@NonNull List<Double> values, double candidate) {
        for (Double value : values) {
            if (value != null && Math.abs(value - candidate) < .01d) return true;
        }
        return false;
    }

    @NonNull
    private static String carShortcutTitle(
            @NonNull CarControlDescriptor control,
            @NonNull CarControlCommand.Operation operation,
            double commandValue,
            @NonNull List<Double> cycleValues) {
        if (!isDriveMode(control)) return control.label;
        if (operation == CarControlCommand.Operation.SET) {
            for (CarControlDescriptor.Option option : control.options) {
                if (Math.abs(option.value - commandValue) < .01d) {
                    return "Режим · " + option.label;
                }
            }
        }
        if (operation == CarControlCommand.Operation.CYCLE && !cycleValues.isEmpty()) {
            return "Выбранные режимы";
        }
        return control.label;
    }

    @NonNull
    private static String carShortcutIcon(
            @NonNull CarControlDescriptor control,
            @NonNull CarControlCommand.Operation operation,
            double commandValue,
            @NonNull List<Double> cycleValues) {
        if (!isDriveMode(control)) return control.iconKey;
        if (operation == CarControlCommand.Operation.CYCLE && !cycleValues.isEmpty()) {
            return "drive_cycle";
        }
        if (operation != CarControlCommand.Operation.SET) return "drive_mode";
        for (CarControlDescriptor.Option option : control.options) {
            if (Math.abs(option.value - commandValue) >= .01d) continue;
            String label = option.label.toLowerCase(Locale.ROOT);
            if (label.contains("sport")) return "drive_sport";
            if (label.contains("comfort")) return "drive_comfort";
            if (label.startsWith("eco")) return "drive_eco";
            if (label.contains("snow")) return "drive_snow";
        }
        return "drive_mode";
    }

    private void save(@NonNull LauncherShortcutStore.Shortcut value) {
        store.upsert(value);
        changed.run();
    }

    @NonNull
    private static String nextRuleId(@NonNull List<IntentActionRule> rules) {
        int suffix = 1;
        while (true) {
            String candidate = "driver_" + suffix++;
            boolean used = false;
            for (IntentActionRule rule : rules) if (rule.id.equals(candidate)) used = true;
            if (!used) return candidate;
        }
    }

    @NonNull
    private LinearLayout column() {
        LinearLayout value = new LinearLayout(activity);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(18), dp(8), dp(18), dp(8));
        return value;
    }

    @NonNull
    private EditText field(@NonNull String hint, @NonNull String value) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }

    private void toast(@NonNull String value) {
        Toast.makeText(activity, value, Toast.LENGTH_LONG).show();
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
}
