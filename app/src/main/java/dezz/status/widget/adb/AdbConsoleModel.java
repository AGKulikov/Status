/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.provider.Settings;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.*;

/** Retained screen state. All package/property/ADB work runs on the dedicated console lane. */
public final class AdbConsoleModel extends AndroidViewModel {
    public static final int NORMAL = 0, COMMAND = 1, SUCCESS = 2, ERROR = 3;
    public static final class Line {
        public final long id; public final String text; public final int kind;
        Line(long id, String text, int kind) { this.id = id; this.text = text; this.kind = kind; }
    }
    public static final class State {
        public final String status, properties;
        public final boolean busy, connected;
        public final int mode;
        public final List<Line> lines;
        public final List<AdbCommandCatalog.Entry> commands, services;
        State(AdbConsoleModel model) {
            status = model.session.endpoint(); connected = model.session.connected(); busy = model.session.busy();
            mode = model.mode; properties = model.properties;
            lines = new ArrayList<>(model.lines); commands = new ArrayList<>(model.commands);
            services = new ArrayList<>(model.services);
        }
    }
    private final AdbConsoleSession session;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final List<Line> lines = new ArrayList<>();
    private List<AdbCommandCatalog.Entry> commands = new ArrayList<>(), services = new ArrayList<>();
    private int mode = -1, logSize;
    private long nextLine;
    private String properties = "Состояние ещё не прочитано";
    private final AdbLocalActions local;

    public AdbConsoleModel(Application app) {
        super(app); session = new AdbConsoleSession(app); local = new AdbLocalActions(app);
        run("connect", s -> { refreshLocal(); s.connect(); add(s.endpoint(), SUCCESS); refresh(); });
    }
    public LiveData<State> state() { return state; }
    private void publish() { synchronized (this) { state.postValue(new State(this)); } }
    private synchronized void add(String text, int kind) {
        if (text == null || text.isEmpty()) return;
        lines.add(new Line(++nextLine, text, kind)); logSize += text.length();
        while (lines.size() > 100 || logSize > 1024 * 1024) logSize -= lines.remove(0).text.length();
        publish();
    }
    private void run(String label, AdbConsoleSession.Operation operation) {
        if (session.submit(s -> { if (!label.isEmpty()) add(label, COMMAND); operation.run(s); }, error -> {
            if (error != null) add(error.getMessage() == null ? error.toString() : error.getMessage(), ERROR);
            publish();
        })) publish();
    }
    public void cancel() { if (session.busy()) session.cancel("Отменено пользователем"); }
    public void refreshScreen() {
        for (String line : AdbRemoteInbox.drain()) add(line, NORMAL);
        if (!session.busy()) run("", s -> refresh());
    }
    public void execute(String raw) {
        String command = raw.trim();
        if (command.isEmpty() || command.indexOf('\0') >= 0) return;
        run(command, s -> {
            String alias = command.toLowerCase(Locale.ROOT);
            switch (alias) {
                case "connect": case "s": s.connect(); add(s.endpoint(), SUCCESS); break;
                case "devices": add(s.connected() ? s.endpoint() : "no devices/emulators found", s.connected() ? SUCCESS : ERROR); break;
                case "k": s.disconnect(); add("Disconnected", NORMAL); break;
                case "type": add("new", NORMAL); break;
                case "root": case "adb root": add(s.daemonRoot(true), SUCCESS); break;
                case "unroot": case "adb unroot": add(s.daemonRoot(false), SUCCESS); break;
                case "r": case "d": case "remount":
                    add("Not supported", ERROR); break;
                case "pi": case "od": case "wechat": case "ah": case "ahss": case "ahoff": case "fadb":
                case "360": case "3": case "cn": case "bt": case "tl0": case "tl1":
                    add(local.execute(alias, s), SUCCESS); break;
                default:
                    if (command.equals("adb") || command.startsWith("adb ")) {
                        add("Введите команду без префикса adb", ERROR); return;
                    }
                    AdbShellResult.Result result = s.command(command);
                    add(result.describe(), result.success() ? SUCCESS : ERROR);
                    if (result.success()) verifyPreset(command);
            }
            refresh();
        });
    }
    private void verifyPreset(String command) throws Exception {
        if (command.startsWith("pm grant ")) {
            String pkg = command.split(" ")[2];
            boolean secure = getApplication().getPackageManager().checkPermission(
                    "android.permission.WRITE_SECURE_SETTINGS", pkg) == PackageManager.PERMISSION_GRANTED;
            add("WRITE_SECURE_SETTINGS: " + (secure ? "выдано" : "не выдано"), secure ? SUCCESS : ERROR);
            AdbShellResult.Result overlay = session.command("appops get " + AdbShellResult.quote(pkg) + " SYSTEM_ALERT_WINDOW");
            add(overlay.describe(), overlay.success() && overlay.output.contains("allow") ? SUCCESS : ERROR);
            if (!pkg.equals("com.arlosoft.macrodroid")) {
                String component = pkg + "/" + (pkg.equals("ru.natro.statuswidget")
                        ? "dezz.status.widget.MediaNotificationListener" : "plus.monjaro.NotificationListener");
                String listeners = readSecure("enabled_notification_listeners");
                boolean allowed = AdbCommandCatalog.enabledServices(listeners).contains(component);
                add("Доступ к уведомлениям: " + (allowed ? "выдано" : "не выдано"), allowed ? SUCCESS : ERROR);
            } else {
                AdbShellResult.Result usage = session.command("appops get com.arlosoft.macrodroid GET_USAGE_STATS");
                add(usage.describe(), usage.success() && usage.output.contains("allow") ? SUCCESS : ERROR);
            }
        }
    }
    public void enableAccessibility(String component) {
        run("Включить специальные возможности: " + component, s -> {
            String current = readSecure("enabled_accessibility_services");
            String merged = AdbCommandCatalog.appendService(current, component);
            require(s.command("settings put secure enabled_accessibility_services " + AdbShellResult.quote(merged)));
            require(s.command("settings put secure accessibility_enabled 1"));
            Set<String> actual = AdbCommandCatalog.enabledServices(readSecure("enabled_accessibility_services"));
            if (!actual.containsAll(AdbCommandCatalog.enabledServices(merged)) || !"1".equals(readSecure("accessibility_enabled")))
                throw new IllegalStateException("Система не подтвердила включение сервиса или изменила прежний список");
            add("Включено: " + component + ". Прежние сервисы сохранены.", SUCCESS); refresh();
        });
    }
    public void developer(Boolean enabled) {
        run(enabled == null ? "Открыть параметры разработчика" : enabled ? "Показать параметры разработчика" : "Скрыть параметры разработчика", s -> {
            if (enabled != null) require(s.command("settings put global development_settings_enabled " + (enabled ? "1" : "0")));
            String value = require(s.command("settings get global development_settings_enabled")).trim();
            if (enabled == null) {
                if (!value.equals("1")) throw new IllegalStateException("Сначала нажмите «Показать» в параметрах разработчика");
                add(s.command("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS").describe(), NORMAL);
            } else if (!value.equals(enabled ? "1" : "0")) throw new IllegalStateException("Система не подтвердила изменение");
            else add(enabled ? "Параметры разработчика показаны" : "Параметры разработчика скрыты", SUCCESS);
        });
    }
    public void persistentAdb(int selected) {
        if (selected < 0 || selected > 2) return;
        run("Постоянный ADB: " + new String[]{"OFF", "ON", "USB"}[selected], s -> {
            try {
                property(s, "persist.service.usb.enable", selected == 2 ? "1" : "0");
                property(s, "persist.sys.usb.adb", selected == 0 ? "off" : "on");
                if (selected != 0) {
                    // MConfig's ON/USB path also disables verification and starts adbd.
                    property(s, "persist.install.verify", "0");
                    try { AdbLocalActions.setProperty("ctl.start", "adbd"); }
                    catch (Exception error) { if (!s.connected()) throw error; require(s.command("setprop ctl.start adbd")); }
                    s.connect();
                } else s.disconnect();
            } finally { refresh(); }
            if (mode != selected) throw new IllegalStateException("Частичное применение: ожидаемый режим не подтверждён. " + properties);
            add("Режим подтверждён: " + new String[]{"OFF", "ON", "USB"}[selected], SUCCESS);
        });
    }
    public void installVerification(boolean enabled) {
        run("Проверка установки приложений: " + (enabled ? "включить" : "отключить"), s -> {
            try { property(s, "persist.install.verify", enabled ? "1" : "0"); }
            finally { refresh(); }
        });
    }
    public void wifi() {
        run("Активировать Wi-Fi", s -> {
            property(s, "persist.service.wifi.ipcp", "false");
            add("Свойство подтверждено. Для применения перезагрузите головное устройство.", NORMAL);
        });
    }
    private void property(AdbConsoleSession s, String key, String value) throws Exception {
        try { AdbLocalActions.setProperty(key, value); }
        catch (Exception failure) {
            if (!s.connected()) throw new IllegalStateException("Нет права изменить " + key + "; ADB не подключён", failure);
            require(s.command("setprop " + key + " " + AdbShellResult.quote(value)));
        }
        String actual = propertyValue(key);
        if (!value.equals(actual)) throw new IllegalStateException(key + ": ожидалось «" + value + "», прочитано «" + actual + "»");
        add(key + " = " + actual, SUCCESS);
    }
    private String propertyValue(String key) throws Exception {
        String value = AdbLocalActions.getProperty(key);
        if (!value.isEmpty()) return value;
        return session.connected() ? require(session.command("getprop " + key)).trim() : "";
    }
    private String readSecure(String key) throws Exception {
        if (session.connected()) return require(session.command("settings get secure " + key)).trim();
        String value = Settings.Secure.getString(getApplication().getContentResolver(), key);
        return value == null ? "null" : value;
    }
    public static String require(AdbShellResult.Result result) {
        if (!result.success()) throw new IllegalStateException(result.describe());
        return result.output;
    }
    private void refresh() throws Exception {
        refreshLocal();
        String enable = propertyValue("persist.service.usb.enable"), adb = propertyValue("persist.sys.usb.adb");
        mode = AdbCommandCatalog.persistentMode(enable, adb);
        properties = "USB: " + (enable.isEmpty() ? "?" : enable) + " · ADB: " + (adb.isEmpty() ? "?" : adb)
                + " · Проверка установки: " + propertyValue("persist.install.verify");
        publish();
    }
    private void refreshLocal() throws Exception {
        PackageManager pm = getApplication().getPackageManager();
        commands = AdbCommandCatalog.entries(pkg -> { try { pm.getPackageInfo(pkg, 0); return true; }
            catch (PackageManager.NameNotFoundException missing) { return false; } }, local.gmc());
        Set<String> enabled = AdbCommandCatalog.enabledServices(readSecure("enabled_accessibility_services"));
        List<AdbCommandCatalog.Entry> available = new ArrayList<>();
        for (ResolveInfo result : pm.queryIntentServices(new Intent("android.accessibilityservice.AccessibilityService"), PackageManager.GET_META_DATA)) {
            ServiceInfo service = result.serviceInfo;
            if (service == null || (service.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || !"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission)) continue;
            String component = AdbCommandCatalog.canonicalComponent(service.packageName + "/" + service.name);
            if (!enabled.contains(component)) available.add(new AdbCommandCatalog.Entry(service.loadLabel(pm).toString(), component));
        }
        services = available;
    }
    @Override protected void onCleared() { session.close(); }
}
