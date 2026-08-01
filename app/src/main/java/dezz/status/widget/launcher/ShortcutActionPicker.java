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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.driver.DriverFavoritesPanelConfig;
import dezz.status.widget.driver.DriverFavoritesPanelStore;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.information.InformationIconPolicy;
import dezz.status.widget.launcher.information.InformationPanelConfig;
import dezz.status.widget.launcher.information.InformationShortcutDefaults;
import dezz.status.widget.launcher.information.InformationSourcePicker;
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
                "Функция автомобиля", "Устройство умного дома / сценарий", "Android Intent"};
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
        String[] values = {"Приложение", "Готовая функция", "Функция автомобиля",
                "Устройство умного дома / сценарий", "Android Intent",
                "Информационная плитка (без нажатия)", "Разделитель"};
        new AlertDialog.Builder(activity).setTitle(title)
                .setItems(values, (dialog, which) -> chooseKindIndex(value, which))
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseKindIndex(@Nullable LauncherShortcutStore.Shortcut value, int which) {
        if (which == 0) chooseApplication(value);
        else if (which == 1) chooseBuiltin(value);
        else if (which == 2) chooseCarControl(value);
        else if (which == 3) chooseSmartHome(value);
        else if (which == 4) editIntent(value);
        else if (which == 5) chooseInformation(value);
        else chooseDivider(value);
    }

    private void chooseInformation(@Nullable LauncherShortcutStore.Shortcut existing) {
        new InformationSourcePicker(activity, CarIntegrations.get(activity), item -> {
            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            value.kind = LauncherShortcutStore.Kind.INFO;
            value.target = InformationShortcutView.target(item);
            value.packageName = "";
            value.title = item.displayLabel();
            value.icon = InformationIconPolicy.resolve(item);
            value.iconCustomized = false;
            value.iconColor = item.iconColor;
            value.textColor = item.valueColor;
            value.backgroundColor = "#00000000";
            value.stateBinding = item.binding;
            value.showState = true;
            value.showTitle = true;
            value.hasLongAction = false;
            value.liveClimateIcon = false;
            InformationShortcutDefaults.apply(preferences, item, value);
            save(value);
        }).show();
    }

    private void chooseDivider(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        value.kind = LauncherShortcutStore.Kind.DIVIDER;
        value.target = "divider";
        value.packageName = "";
        value.title = "Разделитель";
        value.icon = "none";
        value.iconCustomized = true;
        value.backgroundColor = "#66FFFFFF";
        value.iconColor = "none";
        value.showTitle = false;
        value.showState = false;
        value.hasLongAction = false;
        value.liveClimateIcon = false;
        value.stateBinding = null;
        value.rowSpan = 1;
        save(value);
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
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (action == LauncherShortcutStore.Builtin.FAVORITES) {
                        chooseDriverFavorites(value);
                        return;
                    }
                    if (action == LauncherShortcutStore.Builtin.FAVORITE_ROUTE) {
                        chooseFavoriteRoute(value);
                        return;
                    }
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.BUILTIN, action.key, "",
                                CarControlCommand.Operation.TOGGLE, 0);
                    } else {
                        value.kind = LauncherShortcutStore.Kind.BUILTIN;
                        value.target = action.key;
                        value.packageName = "";
                        value.title = action.label;
                        value.icon = action.icon;
                        value.iconCustomized = false;
                        value.iconColor = "#FFE0E5F3";
                        value.stateBinding = null;
                        if (action == LauncherShortcutStore.Builtin.STOCK_CLIMATE) {
                            value.liveClimateIcon = true;
                            value.iconSizePx = Math.max(value.iconSizePx, 76);
                        }
                        save(value);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void chooseDriverFavorites(
            @NonNull LauncherShortcutStore.Shortcut value) {
        List<DriverFavoritesPanelConfig> panels =
                new DriverFavoritesPanelStore(preferences).load();
        String[] labels = new String[panels.size()];
        for (int index = 0; index < panels.size(); index++) {
            DriverFavoritesPanelConfig panel = panels.get(index);
            labels[index] = panel.title + "\n" + panel.columns + " × "
                    + panel.visibleRows + " видимых ячеек";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Какое избранное открыть")
                .setItems(labels, (dialog, which) -> {
                    DriverFavoritesPanelConfig panel = panels.get(which);
                    String target = LauncherShortcutStore.driverFavoritesTarget(panel.id);
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.BUILTIN, target, "",
                                CarControlCommand.Operation.TOGGLE, 0);
                        return;
                    }
                    value.kind = LauncherShortcutStore.Kind.BUILTIN;
                    value.target = target;
                    value.packageName = "";
                    value.title = panel.title;
                    value.icon = LauncherShortcutStore.Builtin.FAVORITES.icon;
                    value.iconCustomized = false;
                    value.iconColor = "#FFE0E5F3";
                    value.stateBinding = null;
                    save(value);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseFavoriteRoute(
            @NonNull LauncherShortcutStore.Shortcut value) {
        List<FavoriteRouteConfig> routes =
                new FavoriteRoutesConfigStore(preferences).load();
        List<FavoriteRouteConfig> enabled = new ArrayList<>();
        for (FavoriteRouteConfig route : routes) if (route.enabled) enabled.add(route);
        if (enabled.isEmpty()) {
            toast("Сначала добавьте избранную точку в настройках маршрутов");
            return;
        }
        String[] labels = new String[enabled.size()];
        for (int index = 0; index < enabled.size(); index++) {
            FavoriteRouteConfig route = enabled.get(index);
            String destination = route.coordinates.trim().isEmpty()
                    ? route.address : route.coordinates;
            labels[index] = route.title + "\n" + destination;
        }
        new AlertDialog.Builder(activity).setTitle("Избранная точка")
                .setItems(labels, (dialog, which) -> {
                    FavoriteRouteConfig route = enabled.get(which);
                    String target = LauncherShortcutStore.favoriteRouteTarget(route.id);
                    if (longPress) {
                        saveLong(value, LauncherShortcutStore.Kind.BUILTIN, target, "",
                                CarControlCommand.Operation.TOGGLE, 0);
                        return;
                    }
                    value.kind = LauncherShortcutStore.Kind.BUILTIN;
                    value.target = target;
                    value.packageName = "";
                    value.title = route.title;
                    value.icon = route.icon;
                    value.iconCustomized = false;
                    value.iconColor = route.iconColor;
                    value.backgroundColor = route.backgroundColor;
                    value.textColor = route.textColor;
                    value.stateBinding = null;
                    save(value);
                })
                .setNegativeButton("Отмена", null)
                .show();
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
        } else {
            labels.add(control.kind == CarControlDescriptor.Kind.OPTIONS
                    ? "Переключать варианты" : "Переключать уровни");
            operations.add(CarControlCommand.Operation.CYCLE);
            labels.add("Переключать только выбранные варианты…");
            operations.add(null);
        }
        values.add(0d);
        if (control.kind != CarControlDescriptor.Kind.TOGGLE) values.add(0d);
        for (CarControlDescriptor.Option option : control.options) {
            labels.add("Установить: " + option.label);
            operations.add(CarControlCommand.Operation.SET);
            values.add(option.value);
        }
        new AlertDialog.Builder(activity).setTitle(control.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    CarControlCommand.Operation operation = operations.get(which);
                    if (operation == null) {
                        chooseCycleSubset(existing, control);
                    } else {
                        saveCar(existing, control, operation, values.get(which));
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseCycleSubset(@Nullable LauncherShortcutStore.Shortcut existing,
                                   @NonNull CarControlDescriptor control) {
        if (control.options.size() < 2) {
            toast("Для этой функции доступно меньше двух вариантов");
            return;
        }
        String[] labels = new String[control.options.size()];
        boolean[] checked = new boolean[control.options.size()];
        for (int index = 0; index < control.options.size(); index++) {
            labels[index] = control.options.get(index).label;
            checked[index] = true;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(control.label + " · цикл")
                .setMultiChoiceItems(labels, checked,
                        (value, which, selected) -> checked[which] = selected)
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
                    saveCarCycle(existing, control, selected);
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
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        if (longPress) {
            saveLong(value, LauncherShortcutStore.Kind.CAR, control.id, "",
                    operation, commandValue);
            return;
        }
        value.kind = LauncherShortcutStore.Kind.CAR;
        value.target = control.id;
        value.packageName = "";
        value.command = operation;
        value.commandValue = commandValue;
        value.commandCycleValues.clear();
        value.title = control.label;
        value.icon = control.iconKey;
        value.iconCustomized = false;
        value.iconColor = "#99FFFFFF";
        value.activeIconColor = control.suggestedActiveColor;
        value.useVehicleStateColor = true;
        value.showState = control.kind != CarControlDescriptor.Kind.ACTION;
        value.stateBinding = null;
        save(value);
    }

    private void saveCarCycle(@Nullable LauncherShortcutStore.Shortcut existing,
                              @NonNull CarControlDescriptor control,
                              @NonNull List<Double> selected) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        if (longPress) {
            value.hasLongAction = true;
            value.longKind = LauncherShortcutStore.Kind.CAR;
            value.longTarget = control.id;
            value.longPackageName = "";
            value.longCommand = CarControlCommand.Operation.CYCLE;
            value.longCommandValue = 0;
            value.longCommandCycleValues = new ArrayList<>(selected);
            longPress = false;
            save(value);
            toast("Долгое нажатие настроено");
            return;
        }
        value.kind = LauncherShortcutStore.Kind.CAR;
        value.target = control.id;
        value.packageName = "";
        value.command = CarControlCommand.Operation.CYCLE;
        value.commandValue = 0;
        value.commandCycleValues = new ArrayList<>(selected);
        value.title = control.label;
        value.icon = control.iconKey;
        value.iconCustomized = false;
        value.iconColor = "#99FFFFFF";
        value.activeIconColor = control.suggestedActiveColor;
        value.useVehicleStateColor = true;
        value.showState = true;
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
        value.hasLongAction = true;
        value.longKind = kind;
        value.longTarget = target;
        value.longPackageName = packageName;
        value.longCommand = command;
        value.longCommandValue = commandValue;
        value.longCommandCycleValues.clear();
        longPress = false;
        save(value);
        toast("Долгое нажатие настроено");
    }

    private void save(@NonNull LauncherShortcutStore.Shortcut value) {
        if (!store.upsert(value)) {
            toast("На панели водителя уже 10 кнопок. "
                    + "Информационные плитки и разделители можно добавлять без ограничения.");
            return;
        }
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
