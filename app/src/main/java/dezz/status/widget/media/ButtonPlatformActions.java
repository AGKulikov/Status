/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import java.util.List;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.shell.PrivilegedShell;

/** Android parts of the reference actions. No dependency on a running MConfig service. */
final class ButtonPlatformActions {
    private final Context context;
    private final VehicleButtonController settings;
    private final Handler main, worker;
    private final ButtonDriverAppLauncher driver;
    private final ButtonDriveModeController drive;
    private final ButtonTemperatureOverlay temperature;
    private boolean callActive, callStateObserved;
    private String lastNumber = "";
    private long wifiGeneration;
    ButtonPlatformActions(Context context, VehicleButtonController settings) {
        this.context = context; this.settings = settings;
        main = new Handler(context.getMainLooper());
        HandlerThread thread = new HandlerThread("natro-button-platform"); thread.start();
        worker = new Handler(thread.getLooper());
        driver = new ButtonDriverAppLauncher(context, worker, this::toast);
        drive = new ButtonDriveModeController(context, settings, worker, this::toast);
        temperature = new ButtonTemperatureOverlay(context, settings);
        IntentFilter phone = new IntentFilter("android.intent.action.PHONE_STATE");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String state = intent.getStringExtra("state");
                if ("OFFHOOK".equals(state) || "RINGING".equals(state)) { callActive = true; callStateObserved = true; }
                else if ("IDLE".equals(state)) { callActive = false; callStateObserved = true; lastNumber = ""; }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, phone, null, worker, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, phone, null, worker);
        temperature.restore();
    }
    void home() {
        worker.post(() -> {
            try {
                Class<?> type = Class.forName("com.ecarx.xui.adaptapi.uiinteraction.MultiWindowImpl");
                Object owner = type.getMethod("getMultiWindowInstance", Context.class).invoke(null, context);
                type.getMethod("closeSplitScreenMode").invoke(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            main.post(() -> {
                WidgetAccessibilityService service = WidgetAccessibilityService.getInstance();
                boolean accepted = false;
                try { accepted = service != null && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
                catch (RuntimeException ignored) { }
                if (!accepted) {
                    try { context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (RuntimeException failed) { toast("Не удалось открыть домашний экран"); }
                }
            });
        });
    }
    void driverApp(ComponentName component) { driver.toggle(component); }
    void driveMode(ButtonAction action) { drive.select(action); }
    void toggleTemperature() { temperature.toggle(); }
    void settingsChanged() { drive.reconcile(); }
    void bluetoothMedia() { ButtonBluetoothMedia.get(context).start(); }
    void confirmRestart() {
        context.startActivity(new Intent(context, ButtonPromptActivity.class)
                .putExtra("operation", "restart").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    void restart() { CarIntegrations.get(context).restartInfotainment((ok, detail) -> {
        if (!ok) toast(detail == null ? "Команда перезагрузки недоступна" : detail);
    }); }
    void call(String entered) {
        String number = entered.split(" / ", 2)[0].trim();
        if (number.isEmpty()) return;
        if (context.checkSelfPermission("android.permission.CALL_PHONE") != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission("android.permission.READ_PHONE_STATE") != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(new Intent(context, ButtonPromptActivity.class).putExtra("operation", "phone")
                    .putExtra("number", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return;
        }
        worker.post(() -> {
            try {
                android.telephony.TelephonyManager phone = context.getSystemService(android.telephony.TelephonyManager.class);
                if (!callStateObserved && phone != null)
                    callActive = phone.getCallState() != android.telephony.TelephonyManager.CALL_STATE_IDLE;
            } catch (RuntimeException ignored) { }
            if (callActive) {
                if (number.equals(lastNumber)) PrivilegedShell.get(context).runCommand("input keyevent KEYCODE_ENDCALL",
                        (output, error) -> { if (error != null) toast("Не удалось завершить звонок"); });
                return;
            }
            try {
                context.startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                lastNumber = number;
            } catch (RuntimeException failed) { toast("Не удалось начать звонок"); }
        });
    }
    void wifi() {
        worker.post(() -> {
            long owner = ++wifiGeneration;
            if (!settings.bool("wifi.connect") || settings.string("wifi.ssid").trim().isEmpty()) {
                toast("Укажите сохранённую сеть в настройках действий кнопок"); return;
            }
            connectWifi(owner, SystemClock.uptimeMillis(), settings.string("wifi.ssid").trim());
        });
    }
    @SuppressWarnings("deprecation")
    private void connectWifi(long owner, long start, String ssid) {
        if (owner != wifiGeneration || !settings.bool("wifi.connect")
                || !ssid.equals(settings.string("wifi.ssid").trim())) return;
        long elapsed = SystemClock.uptimeMillis() - start;
        if (elapsed > 300000) { toast("Не удалось подключиться к " + ssid); return; }
        try {
            WifiManager manager = context.getSystemService(WifiManager.class);
            if (manager == null) return;
            String quoted = "\"" + ssid + "\"";
            WifiInfo info = manager.getConnectionInfo();
            if (info != null && quoted.equals(info.getSSID()) && info.getNetworkId() >= 0) return;
            List<WifiConfiguration> configured = manager.getConfiguredNetworks();
            if (configured != null) for (WifiConfiguration network : configured)
                if (quoted.equals(network.SSID)) { manager.enableNetwork(network.networkId, true); break; }
        } catch (RuntimeException denied) { toast("Нет доступа к сохранённым сетям Wi‑Fi"); return; }
        long delay = Math.min(30000, (elapsed / 30000 + 1) * 5000);
        worker.postDelayed(() -> connectWifi(owner, start, ssid), delay);
    }
    void toast(String value) { main.post(() -> Toast.makeText(context, value, Toast.LENGTH_LONG).show()); }
}
