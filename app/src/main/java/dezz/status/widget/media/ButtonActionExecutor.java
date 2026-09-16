/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import java.util.Map;
import dezz.status.widget.LauncherActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.driver.DriverPanelActionExecutor;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.shell.PrivilegedShell;

/** Dispatches button assignments; shares Natro actions with the DIM and driver menus. */
final class ButtonActionExecutor {
    private final Context context;
    private final VehicleButtonController settings;
    private final Handler main;
    private final DriverPanelActionExecutor driver;
    private final ButtonPlatformActions platform;
    private final java.util.Map<String, CarControlState> driverCarStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final dezz.status.widget.car.CarIntegration.ControlStateListener driverCarListener = state ->
            driverCarStates.put(state.controlId, state);
    ButtonActionExecutor(Context context, VehicleButtonController settings) {
        this.context = context; this.settings = settings;
        main = new Handler(context.getMainLooper());
        platform = new ButtonPlatformActions(context, settings);
        driver = new DriverPanelActionExecutor(context, new Preferences(context), new DriverPanelActionExecutor.Host() {
            @Override public void showAllApps(View anchor) {
                DriverPanelService.showAllApps(context);
            }
            @Override public void showFavorites(String panelId, View anchor) { DriverPanelService.showFavorites(context, panelId); }
            @Override public void triggerStockClimate() { DriverPanelService.triggerStockClimate(context); }
            @Override public CarControlState carControlState(String id) { return driverCarStates.get(id); }
        });
        settingsChanged();
    }
    void execute(ButtonBinding binding) {
        DriveSelectorButtonPreset selector = DriveSelectorButtonPreset.fromAction(binding.action);
        if (selector != null) {
            DriveSelectorController.request(context, selector.steps);
            return;
        }
        switch (binding.action) {
            case NONE: return;
            case APP: launch(binding.application, 0, true); return;
            case PASSENGER_APP: launch(binding.application, 3, false); return;
            case DRIVER_APP: platform.driverApp(component(binding.application)); return;
            case BROADCAST: case ACTIVITY:
                ButtonIntentSpec spec = ButtonIntentSpec.parse(binding.command, binding.packageName,
                        binding.action == ButtonAction.ACTIVITY);
                Intent intent = binding.action == ButtonAction.BROADCAST ? new Intent(spec.target)
                        : new Intent().setClassName(spec.packageName, spec.target);
                if (binding.action == ButtonAction.BROADCAST && !spec.packageName.isEmpty()) intent.setPackage(spec.packageName);
                intent.setFlags(spec.flags);
                for (Map.Entry<String, Object> extra : spec.extras.entrySet()) {
                    Object value = extra.getValue();
                    if (value instanceof Integer) intent.putExtra(extra.getKey(), (Integer) value);
                    else if (value instanceof Boolean) intent.putExtra(extra.getKey(), (Boolean) value);
                    else intent.putExtra(extra.getKey(), (String) value);
                }
                if (binding.action == ButtonAction.BROADCAST) context.sendBroadcast(intent);
                else context.startActivity(intent);
                return;
            case ADB:
                if (!binding.command.trim().isEmpty()) shell(binding.command.trim());
                return;
            case HOME: platform.home(); return;
            case BACK:
                if (!WidgetAccessibilityService.performGlobalBack(ok -> { if (!ok) shell("input keyevent 4"); })) shell("input keyevent 4");
                return;
            case RECENTS:
                if (!WidgetAccessibilityService.performGlobalRecents(ok -> { if (!ok) shell("input keyevent 187"); })) shell("input keyevent 187");
                return;
            case LAST_APP:
                // The reference uses two GLOBAL_ACTION_RECENTS calls, with 50 ms between them.
                WidgetAccessibilityService.performGlobalRecents(ok -> {
                    if (ok) main.postDelayed(WidgetAccessibilityService::performGlobalRecents, 50);
                }); return;
            case FORCE_STOP:
                String foreground = foreground();
                if (foreground != null && !foreground.equals(context.getPackageName())
                        && !foreground.equals("com.android.systemui") && !foreground.equals("com.ecarx.dimmenu")
                        && !foreground.equals("com.ecarx.hud")) shell("su 0 am force-stop " + MediaButtonController.quote(foreground));
                return;
            case PASSENGER_HOME:
                context.startActivity(new Intent().setClassName("ecarx.launcher3", "ecarx.launcher3.psd.AppPaneForPsd")
                        .setFlags(1417674752), ActivityOptions.makeBasic().setLaunchDisplayId(3).toBundle()); return;
            case PASSENGER_POWER: car("comfort.passenger_screen", CarControlCommand.Operation.TOGGLE, 0); return;
            case THEME: car("vehicle.screen_theme", CarControlCommand.Operation.CYCLE, 0); return;
            case SCREENSAVER:
                context.startService(new Intent("android.intent.action.SCREENSAVER")
                        .addCategory("android.intent.category.SCREENSAVER").setPackage("com.ecarx.screensaver")); return;
            case CLIMATE: DriverPanelService.triggerStockClimate(context); return;
            case APP_DRAWER:
                context.startActivity(new Intent(Intent.ACTION_MAIN).setClassName("ecarx.launcher3", "ecarx.launcher3.AppPane")
                        .putExtra("APPPANE_EXIT_TYPE", 4).setFlags(1417674752)); return;
            case CAMERA:
                if (settings.bool("camera.use_broadcast")) context.sendBroadcast(new Intent("com.ecarx.action.avm.openclose").setFlags(0x11000000));
                else car("vehicle.camera_360", CarControlCommand.Operation.TOGGLE, 0);
                return;
            case CALL: platform.call(binding.command); return;
            case FULLSCREEN: fullscreen(); return;
            case TEMPERATURE: platform.toggleTemperature(); return;
            case BT_MEDIA: platform.bluetoothMedia(); return;
            case WIFI: platform.wifi(); return;
            case SOURCE: MediaButtonController.get(context).cycleSource(); return;
            case RESTART: platform.confirmRestart(); return;
            case COMFORT: case ADAPTIVE: case ECO: case SPORT: case OFFROAD: case SAND: case SNOW:
                platform.driveMode(binding.action); return;
            case MNAVI_ASSISTANT: context.sendBroadcast(new Intent("mnavi.call_assistant").setPackage("dd.monjaro.navi")); return;
            case DASHBOARD:
                platform.driverApp(new ComponentName("com.auto_soft.monjaro_dashboard", "com.auto_soft.monjaro_dashboard.MainActivity")); return;
            case DRIVER_MENU:
                try {
                LauncherShortcutStore.Shortcut shortcut = LauncherShortcutStore.decodeAction(binding.shortcutJson);
                ButtonActionDeadline deadline = ButtonActionDeadline.current();
                if (shortcut.enabled && LauncherShortcutStore.isInteractive(shortcut)) main.post(() -> {
                    if (deadline.valid()) driver.execute(shortcut);
                });
                } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Некорректное действие меню", invalid); }
                return;
        }
    }
    static ComponentName component(String raw) {
        String value = raw.trim();
        int open = value.lastIndexOf('['), close = value.lastIndexOf(']');
        if (open >= 0 && close > open) value = value.substring(open + 1, close);
        String[] parts = value.split("/", 2);
        if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty())
            throw new IllegalArgumentException("Не выбрано приложение");
        return new ComponentName(parts[0].trim(), parts[1].trim());
    }
    private void launch(String raw, int display, boolean toggleHome) {
        ComponentName component = component(raw);
        if (toggleHome && component.getPackageName().equals(foreground())) { platform.home(); return; }
        if (display == 0 && java.util.Arrays.asList("MConfig.favorites", "MConfig.maps", "MConfig.split",
                "MConfig.climate", "MConfig.reboot", "w.s.m", "p.bt", "m.tg").contains(component.getPackageName())) display = 1;
        context.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(component).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
    }
    private String foreground() {
        WidgetAccessibilityService service = WidgetAccessibilityService.getInstance();
        return service == null ? null : service.getForegroundPackageOnDisplay(0);
    }
    private void fullscreen() {
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Нет разрешения на полноэкранный режим"); return;
        }
        String current = Settings.Global.getString(context.getContentResolver(), "policy_control");
        int mode = settings.integer("fullscreen.mode", 0);
        String next;
        if (mode == 1) next = current != null && current.contains("immersive.status") ? "" : "immersive.status=*";
        else if (mode == 2) next = current != null && current.contains("immersive.full") ? "" : "immersive.full=*";
        else if (current == null || current.trim().isEmpty()) next = "immersive.status=*";
        else if (current.contains("immersive.status")) next = "immersive.full=*";
        else next = "";
        Settings.Global.putString(context.getContentResolver(), "policy_control", next);
    }
    private void car(String id, CarControlCommand.Operation operation, double value) {
        CarIntegrations.get(context).executeControl(new CarControlCommand(id, operation, value),
                (success, message) -> { if (!success) toast(message == null ? "Команда недоступна" : message); });
    }
    void setStarDefaultDisabled(boolean disabled, VehicleButtonController.Result callback) {
        CarIntegrations.get(context).executeControl(new CarControlCommand("vehicle.star_default",
                CarControlCommand.Operation.SET, disabled ? 0 : 1), callback::finished);
    }
    void starHeld() { platform.restart(); }
    void callNumber(String number) { platform.call(number); }
    void settingsChanged() {
        platform.settingsChanged();
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (ButtonBinding binding : settings.driverMenuBindings()) {
            try {
                LauncherShortcutStore.Shortcut shortcut = LauncherShortcutStore.decodeAction(binding.shortcutJson);
                if (shortcut.enabled && shortcut.kind == LauncherShortcutStore.Kind.CAR) targets.add(shortcut.target);
            } catch (org.json.JSONException ignored) { }
        }
        driverCarStates.keySet().retainAll(targets);
        if (targets.isEmpty()) CarIntegrations.get(context).unsubscribeControlStates(driverCarListener);
        else CarIntegrations.get(context).subscribeControlStates(targets, driverCarListener);
    }
    private void shell(String command) { PrivilegedShell.get(context).runCommand(command,
            (output, error) -> { if (error != null) toast("Команда не выполнена: " + error); }); }
    private void toast(String message) { main.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()); }
}
