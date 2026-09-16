/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.media.VehicleButtonController;
import dezz.status.widget.phone.PhoneNotificationFilter;
import java.io.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** MConfig aliases adapted to Natro-owned controllers/preferences, never its private settings. */
public final class AdbLocalActions {
    public static final String HELP = "root / adb root — запрос root у adbd и проверка UID\n"
            + "unroot / adb unroot — вернуть adbd в shell\nid -u — текущий UID\nsu 0 id -u — отдельная проверка su\n\n"
            + "connect / s — подключить; k — отключить; devices — статус; type — new\n"
            + "pi / od — геометрия HOME для панели GMC / штатной\nwechat — установить пустой пакет совместимости WeChat\n"
            + "ah — AutoHold: sh / su; ahss — Snap / Stream\nahoff — выключить собственное чтение AutoHold\n"
            + "fadb — запуск adbd при старте Natro\n360 — способ открытия камеры\n3 — трёхпальцевые жесты\n"
            + "cn — уведомления о звонках iPhone\nbt — повторы подключения выбранного телефона к штатному Bluetooth\n"
            + "tl0 / tl1 — светофоры HUD\n\nНастройки Natro не изменяют настройки MConfig. Новые жесты и чтение AutoHold по умолчанию выключены. "
            + "Изменение свойства не гарантирует физический эффект на любой прошивке.";
    private final Context context;
    private final Preferences prefs;
    private static final AtomicBoolean restored = new AtomicBoolean();
    public static volatile boolean trafficLightsEnabled = true;
    public AdbLocalActions(Context context) {
        this.context = context.getApplicationContext(); prefs = new Preferences(this.context);
        trafficLightsEnabled = prefs.adbTrafficLights.get();
    }
    public boolean gmc() { return prefs.adbGmcSidebar.get(); }
    public static String getProperty(String key) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, ""); }
        catch (Exception denied) { return ""; }
    }
    public static void setProperty(String key, String value) throws Exception {
        Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, key, value);
    }
    public String execute(String command, AdbConsoleSession session) throws Exception {
        switch (command) {
            case "pi": case "od":
                prefs.adbGmcSidebar.set(command.equals("pi"));
                prefs.adbHomeHotspotEnabled.set(true);
                AdbGestureController.refreshActive();
                return (gmc() ? "Боковая панель GMC: HOME Y=130" : "Штатная боковая панель: HOME Y=50")
                        + ". Применяется к зоне HOME Natro при включённых панели водителя и специальных возможностях.";
            case "wechat": return wechat(session);
            case "fadb":
                boolean force = !prefs.adbForceStart.get(); prefs.adbForceStart.set(force);
                return "Force ADB AutoStart " + (force ? "enabled — запрос ctl.start=adbd при запуске; право системы проверяется отдельно" : "disabled");
            case "360":
                VehicleButtonController buttons = VehicleButtonController.get(context);
                if (!buttons.ready()) throw new IllegalStateException("Контроллер кнопок загружается; повторите команду после загрузки");
                boolean broadcast = !buttons.bool("camera.use_broadcast"); buttons.put("camera.use_broadcast", broadcast);
                return broadcast ? "360 = 2 (broadcast)" : "360 = 1 (интерфейс автомобиля)";
            case "3":
                boolean gestures = !prefs.adbThreeFinger.get();
                if (gestures && WidgetAccessibilityService.getInstance() == null)
                    throw new IllegalStateException("Сначала включите специальные возможности Natro");
                AdbGestureController.setEnabled(gestures);
                return gestures ? "3-finger gestures enabled — регистрация ECARX подтверждена" : "3-finger gestures disabled";
            case "cn":
                Set<Integer> categories = new java.util.LinkedHashSet<>(PhoneNotificationFilter.parseCategoryIds(prefs.phoneNotificationCategoryIds.get()));
                boolean calls = !categories.contains(1); if (calls) categories.add(1); else categories.remove(1);
                java.util.List<String> ids = new java.util.ArrayList<>(); for (Integer id : categories) ids.add(String.valueOf(id));
                prefs.phoneNotificationCategoryIds.set(String.join(",", ids));
                return calls ? "Call notification enabled (ANCS: входящие звонки)" : "Call notification disabled (ANCS: входящие звонки)";
            case "bt":
                boolean reconnect = !prefs.adbBluetoothReconnect.get(); prefs.adbBluetoothReconnect.set(reconnect);
                return "BT auto-reconnect " + (reconnect ? "enabled" : "disabled") + ": штатный профиль выбранного телефона; ANCS и привязки не сбрасываются";
            case "tl0": case "tl1":
                trafficLightsEnabled = command.equals("tl1"); prefs.adbTrafficLights.set(trafficLightsEnabled);
                return "Traffic lights = " + command.substring(2);
            case "ah": prefs.adbAutoHoldUseShell.set(!prefs.adbAutoHoldUseShell.get()); break;
            case "ahss": prefs.adbAutoHoldSnapshot.set(!prefs.adbAutoHoldSnapshot.get()); break;
            case "ahoff": prefs.adbAutoHoldCapture.set(false); AutoHoldAdbReader.reconcile(context); return "Чтение AutoHold Natro выключено";
            default: throw new IllegalArgumentException("Неизвестная служебная команда");
        }
        prefs.adbAutoHoldCapture.set(true); AutoHoldAdbReader.reconcile(context);
        return "AH " + (prefs.adbAutoHoldUseShell.get() ? "sh" : "su") + " · "
                + (prefs.adbAutoHoldSnapshot.get() ? "Snap" : "Stream")
                + ". Чтение запрошено; это не команда включения тормоза. Статус захвата — в диагностике ADB.";
    }
    private String wechat(AdbConsoleSession session) throws Exception {
        try { context.getPackageManager().getPackageInfo("com.tencent.mm", 0); return "Already Fixed: com.tencent.mm уже установлен; пакет не изменён"; }
        catch (PackageManager.NameNotFoundException missing) { /* install only our empty package */ }
        File apk = new File(context.getCacheDir(), "natro-wechat-compat.apk");
        try {
            try (InputStream source = context.getAssets().open("natro-wechat-compat.apk"); OutputStream out = new FileOutputStream(apk)) {
                byte[] bytes = new byte[8192]; int count; while ((count = source.read(bytes)) >= 0) out.write(bytes, 0, count);
            }
            String uid = AdbConsoleModel.require(session.command("id -u")).trim();
            String prefix = uid.equals("0") ? "" : "su 0 ";
            AdbConsoleModel.require(session.command(prefix + "chmod 644 " + AdbShellResult.quote(apk.getAbsolutePath())));
            String output = AdbConsoleModel.require(session.command(prefix + "pm install -r " + AdbShellResult.quote(apk.getAbsolutePath())));
            if (!output.contains("Success")) throw new IOException(output);
            AdbConsoleModel.require(session.command("pm path com.tencent.mm"));
            // Exact process from the reference, only after confirmed installation; no wildcard
            // package deletion, VDEX modification, replacement of an installed WeChat, or reboot.
            AdbShellResult.Result restart = session.command(prefix + "pkill -f ecarx.xsf.inputservice");
            return "Пакет совместимости Natro установлен. Перезапуск XSFInputService: " + restart.describe();
        } finally { if (apk.exists() && !apk.delete()) apk.deleteOnExit(); }
    }
    /** Once per process, after an ordinary lifecycle restore; no work if the user did not opt in. */
    public static void restore(Context context) {
        if (!restored.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        Thread restore = new Thread(() -> {
            Preferences prefs = new Preferences(app);
            trafficLightsEnabled = prefs.adbTrafficLights.get();
            if (prefs.adbForceStart.get()) try { setProperty("ctl.start", "adbd");
                dezz.status.widget.diagnostics.DiagnosticJournal.infoAsync("adb", "Force ADB start requested; init.svc.adbd=" + getProperty("init.svc.adbd"));
            } catch (Exception denied) { dezz.status.widget.diagnostics.DiagnosticJournal.warn("adb", "Force ADB start denied: " + denied.getClass().getSimpleName()); }
            if (prefs.adbAutoHoldCapture.get()) AutoHoldAdbReader.reconcile(app);
        }, "natro-adb-restore"); restore.start();
    }
}
