/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Behavioral inventory of MConfig+ v46.1, implemented without redistributing its code/assets. */
public final class AdbCommandCatalog {
    public static final String[][] GPS = {
        {"GPS Tether", "com.ryanandbrenda.gpstether"},
        {"GPS Connector", "de.pilablu.gpsconnector"},
        {"UsbGps4Droid MOD", "usb.gps"},
        {"UsbGps4Droid", "org.broeuschmeul.android.gps.usb.provider"},
        {"Bluetooth GNSS MOD", "bt.gps"},
        {"Bluetooth GNSS", "com.clearevo.bluetooth_gnss"},
        {"GNSS Client", "dezz.gnssshare.client"},
        {"GNSSme", "ru.monjaro.gnssme"},
        {"Hardware GPS", "org.astpepper.hwgps"}
    };
    public static final String CHECK_MOCK = "appops query-op android:mock_location allow";
    private AdbCommandCatalog() {}
    public static final class Entry {
        public final String title, command;
        public Entry(String title, String command) { this.title = title; this.command = command; }
        @Override public String toString() { return title; }
    }
    public static List<Entry> entries(Predicate<String> installed, boolean gmc) {
        List<Entry> result = new ArrayList<>();
        result.add(new Entry("", "")); result.add(new Entry("Connect", "connect"));
        result.add(new Entry("", ""));
        result.add(new Entry("Natro", grants("ru.natro.statuswidget", "dezz.status.widget.MediaNotificationListener")));
        result.add(new Entry("MConfig+", grants("plus.monjaro", "plus.monjaro.NotificationListener")));
        result.add(new Entry("Macrodroid", "pm grant com.arlosoft.macrodroid android.permission.WRITE_SECURE_SETTINGS && appops set com.arlosoft.macrodroid android:get_usage_stats allow && appops set com.arlosoft.macrodroid SYSTEM_ALERT_WINDOW allow"));
        result.add(new Entry("", ""));
        List<String> gps = new ArrayList<>();
        for (String[] app : GPS) if (installed.test(app[1])) {
            gps.add(app[1]); result.add(new Entry(app[0], "appops set " + app[1]
                    + " android:mock_location allow; \\\n" + CHECK_MOCK));
        }
        if (!gps.isEmpty()) {
            result.add(new Entry("CHECK mock_location", CHECK_MOCK));
            StringBuilder reset = new StringBuilder();
            for (String pkg : gps) reset.append("appops set ").append(pkg).append(" android:mock_location deny; \\\n");
            result.add(new Entry("RESET mock_location", reset + CHECK_MOCK));
            result.add(new Entry("", ""));
        }
        result.add(new Entry(gmc ? "Штатная боковая панель" : "Боковая панель GMC", gmc ? "od" : "pi"));
        result.add(new Entry("Исправить кнопку WeChat", "wechat"));
        result.add(new Entry("Режим AutoHold", "ah"));
        result.add(new Entry("", ""));
        return result;
    }
    public static String grants(String pkg, String listener) {
        return "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS && appops set " + pkg
                + " SYSTEM_ALERT_WINDOW allow && cmd notification allow_listener " + pkg + "/" + listener;
    }
    public static String canonicalComponent(String component) {
        String[] parts = component.trim().split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z][A-Za-z0-9_.]*")
                || !parts[1].matches("[A-Za-z.][A-Za-z0-9_.$]*")) return "";
        return parts[0] + "/" + (parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1]);
    }
    public static Set<String> enabledServices(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String component : value.trim().split(":")) {
            String normalized = canonicalComponent(component);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }
    public static String appendService(String value, String component) {
        String normalized = canonicalComponent(component);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Некорректный сервис");
        Set<String> existing = enabledServices(value);
        existing.add(normalized);
        return String.join(":", existing);
    }
    /** -1 means unknown/inconsistent; do not display it as OFF. */
    public static int persistentMode(String enable, String adb) {
        if ("off".equals(adb)) return 0;
        if ("on".equals(adb) && "0".equals(enable)) return 1;
        if ("on".equals(adb) && "1".equals(enable)) return 2;
        return -1;
    }
}
