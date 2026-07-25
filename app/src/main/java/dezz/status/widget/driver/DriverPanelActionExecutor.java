/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.automation.ScenarioTriggerReceiver;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.YandexWindowLauncher;
import dezz.status.widget.launcher.routes.FavoriteRouteConfig;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;
import dezz.status.widget.launcher.routes.YandexRouteLauncher;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.settings.SettingsDestinationCatalog;
import dezz.status.widget.shell.PrivilegedShell;

/** Executes the same shortcut model outside {@code LauncherActivity}. */
final class DriverPanelActionExecutor {
    interface Host {
        void showAllApps();
        void showFavorites(@NonNull String panelId, @Nullable View anchor);
        void triggerStockClimate();
    }

    private final Context context;
    private final Preferences preferences;
    private final Host host;

    DriverPanelActionExecutor(@NonNull Context context, @NonNull Preferences preferences,
                              @NonNull Host host) {
        this.context = context.getApplicationContext();
        this.preferences = preferences;
        this.host = host;
    }

    void execute(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        execute(shortcut, null);
    }

    void execute(@NonNull LauncherShortcutStore.Shortcut shortcut,
                 @Nullable View anchor) {
        try {
            switch (shortcut.kind) {
                case APP:
                    launchComponent(shortcut.target);
                    return;
                case INTENT:
                    Intent command = new Intent(shortcut.target);
                    if (!shortcut.packageName.isEmpty()) command.setPackage(shortcut.packageName);
                    context.sendBroadcast(command);
                    return;
                case RULE:
                    executeRule(shortcut.target);
                    return;
                case CAR:
                    CarIntegrations.get(context).executeControl(new CarControlCommand(
                            shortcut.target, shortcut.command, shortcut.commandValue,
                            shortcut.commandCycleValues),
                            (success, message) -> {
                                if (!success) toast(message == null
                                        ? "Команда автомобиля не выполнена" : message);
                            });
                    return;
                case INFO:
                case DIVIDER:
                    return;
                case BUILTIN:
                default:
                    executeBuiltin(LauncherShortcutStore.Builtin.fromKey(shortcut.target),
                            shortcut.target, anchor);
            }
        } catch (RuntimeException error) {
            toast("Действие не выполнено: " + shortcut.title);
        }
    }

    boolean executeLong(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        return executeLong(shortcut, null);
    }

    boolean executeLong(@NonNull LauncherShortcutStore.Shortcut shortcut,
                        @Nullable View anchor) {
        if (!shortcut.hasLongAction) return false;
        LauncherShortcutStore.Shortcut action = shortcut.copy();
        action.kind = shortcut.longKind;
        action.target = shortcut.longTarget;
        action.packageName = shortcut.longPackageName;
        action.command = shortcut.longCommand;
        action.commandValue = shortcut.longCommandValue;
        action.commandCycleValues = new java.util.ArrayList<>(
                shortcut.longCommandCycleValues);
        execute(action, anchor);
        return true;
    }

    private void executeBuiltin(@NonNull LauncherShortcutStore.Builtin action,
                                @NonNull String rawTarget,
                                @Nullable View anchor) {
        switch (action) {
            case HOME:
                context.startActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                return;
            case BACK:
                if (!WidgetAccessibilityService.performGlobalBack()) {
                    PrivilegedShell.get(context).runCommand("input keyevent 4",
                            (output, error) -> {
                                if (error != null) toast("Включите спецвозможности для кнопки «Назад»");
                            });
                }
                return;
            case RECENTS:
                if (!WidgetAccessibilityService.performGlobalRecents(
                        accepted -> {
                            if (!accepted) openRecentsWithShell();
                        })) openRecentsWithShell();
                return;
            case STOCK_CLIMATE:
                host.triggerStockClimate();
                return;
            case ALL_APPS:
                host.showAllApps();
                return;
            case FAVORITES:
                host.showFavorites(LauncherShortcutStore.driverFavoritesPanelId(rawTarget),
                        anchor);
                return;
            case FAVORITE_ROUTE:
                for (FavoriteRouteConfig route :
                        new FavoriteRoutesConfigStore(preferences).load()) {
                    if (!route.enabled || !route.id.equals(
                            LauncherShortcutStore.favoriteRouteId(rawTarget))) continue;
                    if (!YandexRouteLauncher.launch(context, route)) {
                        toast("Не удалось открыть маршрут");
                    }
                    return;
                }
                toast("Избранная точка не найдена");
                return;
            case MAPS_WINDOW:
                launchYandex(YandexWindowLauncher.Product.MAPS, false);
                return;
            case MAPS_FULL:
                launchYandex(YandexWindowLauncher.Product.MAPS, true);
                return;
            case NAVIGATOR_WINDOW:
                launchYandex(YandexWindowLauncher.Product.NAVIGATOR, false);
                return;
            case NAVIGATOR_FULL:
                launchYandex(YandexWindowLauncher.Product.NAVIGATOR, true);
                return;
            case MEDIA_PLAY_PAUSE:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return;
            case MEDIA_PREVIOUS:
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return;
            case MEDIA_NEXT:
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                return;
            case NOTIFICATION_ACCESS:
                context.startActivity(new Intent(
                        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            case AUTOMATION_SETTINGS:
                openSettings(SettingsDestinationCatalog.Group.SMART_HOME);
                return;
            case SCENARIOS:
            case INTENT_SCENARIOS:
                openSettings(SettingsDestinationCatalog.Group.AUTOMATION);
                return;
            case POPUP_SETTINGS:
                openSettings(SettingsDestinationCatalog.Group.PANELS);
                return;
            case EDIT_HOME:
            case HOME_SETTINGS:
                openSettings(SettingsDestinationCatalog.Group.HOME);
                return;
            case WIDGET_SETTINGS:
            default:
                openSettings(SettingsDestinationCatalog.Group.STATUS);
        }
    }

    private void launchYandex(YandexWindowLauncher.Product product, boolean full) {
        if (!YandexWindowLauncher.launchOverLauncher(context, product, full)) {
            toast("Приложение Яндекса не найдено");
        }
    }

    private void openRecentsWithShell() {
        PrivilegedShell.get(context).runCommand("input keyevent 187",
                (output, error) -> {
                    if (error != null) {
                        toast("Включите спецвозможности для списка приложений");
                    }
                });
    }

    private void openSettings(SettingsDestinationCatalog.Group group) {
        context.startActivity(new Intent()
                .setClassName(context.getPackageName(),
                        "dezz.status.widget.SettingsHubActivity")
                .putExtra("dezz.status.widget.extra.SETTINGS_GROUP", group.id)
                .putExtra("dezz.status.widget.extra.SETTINGS_SHOW_BACK", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void launchComponent(String flattened) {
        ComponentName component = ComponentName.unflattenFromString(flattened);
        if (component == null) throw new IllegalArgumentException("Invalid component");
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private void executeRule(String ruleId) {
        List<IntentActionRule> rules = new IntentActionRuleStore(preferences).loadStrict();
        for (IntentActionRule rule : rules) {
            if (!rule.enabled || !rule.id.equals(ruleId)) continue;
            context.sendBroadcast(new Intent(context, ScenarioTriggerReceiver.class)
                    .setAction(ScenarioTriggerReceiver.ACTION_TRIGGER)
                    .putExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_ID, rule.id)
                    .putExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_TOKEN, rule.triggerToken));
            return;
        }
        throw new IllegalArgumentException("Missing rule");
    }

    private void mediaKey(int keyCode) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("Audio unavailable");
        long time = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void toast(String text) {
        android.os.Handler main = new android.os.Handler(context.getMainLooper());
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }
}
