/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import dezz.status.widget.Preferences;
import dezz.status.widget.car.CarControlCommand;

/** Versioned, ordered collection of user-created HOME icons. */
public final class LauncherShortcutStore {
    public static final int SCHEMA_VERSION = 1;

    public enum Kind { APP, BUILTIN, RULE, INTENT, CAR }

    public static final class Shortcut {
        @NonNull public String id = UUID.randomUUID().toString();
        @NonNull public String title = "Новая иконка";
        @NonNull public Kind kind = Kind.BUILTIN;
        /** Flattened ComponentName, built-in action key, or Intent action. */
        @NonNull public String target = Builtin.ALL_APPS.key;
        /** Optional package restriction for an INTENT shortcut. */
        @NonNull public String packageName = "";
        /** CAR command kept separate from the stable control ID in target. */
        @NonNull public CarControlCommand.Operation command = CarControlCommand.Operation.TOGGLE;
        public double commandValue = 0;
        /** app = original application icon; otherwise one of LauncherIconResolver preset keys. */
        @NonNull public String icon = "apps";
        /** False means a connector may refresh the suggested icon; true preserves user choice. */
        public boolean iconCustomized = false;
        @NonNull public String backgroundColor = "#B5222733";
        /** "none" preserves an app icon; otherwise an Android color string. */
        @NonNull public String iconColor = "#FFFFFFFF";
        @NonNull public String textColor = "#FFFFFFFF";
        public boolean hasLongAction = false;
        @NonNull public Kind longKind = Kind.BUILTIN;
        @NonNull public String longTarget = "";
        @NonNull public String longPackageName = "";
        @NonNull public CarControlCommand.Operation longCommand = CarControlCommand.Operation.TOGGLE;
        public double longCommandValue = 0;
        @NonNull public String activeBackgroundColor = "#CC374151";
        @NonNull public String activeIconColor = "#FFFFB300";
        public boolean useVehicleStateColor = true;
        public boolean showState = true;
        public int iconSizePx = 54;
        public int columnSpan = 1;
        public int rowSpan = 1;
        public boolean showTitle = true;
        public boolean enabled = true;

        @NonNull
        public Shortcut copy() {
            Shortcut value = new Shortcut();
            value.id = id;
            value.title = title;
            value.kind = kind;
            value.target = target;
            value.packageName = packageName;
            value.command = command;
            value.commandValue = commandValue;
            value.icon = icon;
            value.iconCustomized = iconCustomized;
            value.backgroundColor = backgroundColor;
            value.iconColor = iconColor;
            value.textColor = textColor;
            value.hasLongAction = hasLongAction;
            value.longKind = longKind;
            value.longTarget = longTarget;
            value.longPackageName = longPackageName;
            value.longCommand = longCommand;
            value.longCommandValue = longCommandValue;
            value.activeBackgroundColor = activeBackgroundColor;
            value.activeIconColor = activeIconColor;
            value.useVehicleStateColor = useVehicleStateColor;
            value.showState = showState;
            value.iconSizePx = iconSizePx;
            value.columnSpan = columnSpan;
            value.rowSpan = rowSpan;
            value.showTitle = showTitle;
            value.enabled = enabled;
            return value;
        }
    }

    public enum Builtin {
        ALL_APPS("all_apps", "Все приложения", "apps"),
        MAPS_WINDOW("maps_window", "Карты в окне", "navigation"),
        MAPS_FULL("maps_full", "Карты на весь экран", "navigation"),
        NAVIGATOR_WINDOW("navigator_window", "Навигатор в окне", "navigation"),
        NAVIGATOR_FULL("navigator_full", "Навигатор на весь экран", "navigation"),
        MEDIA_PLAY_PAUSE("media_play_pause", "Пауза / Играть", "media"),
        MEDIA_PREVIOUS("media_previous", "Предыдущий трек", "media_previous"),
        MEDIA_NEXT("media_next", "Следующий трек", "media_next"),
        EDIT_HOME("edit_home", "Изменить HOME", "edit"),
        HOME_SETTINGS("home_settings", "Настройки HOME", "settings"),
        WIDGET_SETTINGS("widget_settings", "Настройки Status Widget", "settings"),
        POPUP_SETTINGS("popup_settings", "Плавающие оверлеи", "devices"),
        AUTOMATION_SETTINGS("automation_settings", "Устройства умного дома", "devices"),
        SCENARIOS("scenarios", "Визуальные сценарии", "scenario"),
        INTENT_SCENARIOS("intent_scenarios", "Intent-сценарии", "scenario"),
        NOTIFICATION_ACCESS("notification_access", "Доступ к уведомлениям", "notification");

        @NonNull public final String key;
        @NonNull public final String label;
        @NonNull public final String icon;

        Builtin(String key, String label, String icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }

        public static Builtin fromKey(String key) {
            for (Builtin value : values()) if (value.key.equals(key)) return value;
            return ALL_APPS;
        }
    }

    private final Preferences preferences;
    private final List<Shortcut> shortcuts = new ArrayList<>();

    public LauncherShortcutStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
        load();
    }

    public void load() {
        shortcuts.clear();
        String raw = preferences.launcherShortcutsJson.get();
        if (raw == null || raw.trim().isEmpty()) {
            shortcuts.addAll(defaults());
            save();
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("items");
            if (root.optInt("version", 0) != SCHEMA_VERSION || items == null) throw new JSONException("schema");
            for (int index = 0; index < items.length(); index++) {
                JSONObject json = items.optJSONObject(index);
                if (json == null) continue;
                Shortcut value = fromJson(json);
                if (value != null) shortcuts.add(value);
            }
        } catch (JSONException error) {
            shortcuts.clear();
            shortcuts.addAll(defaults());
            save();
        }
    }

    @NonNull
    public List<Shortcut> all() {
        List<Shortcut> result = new ArrayList<>();
        for (Shortcut shortcut : shortcuts) result.add(shortcut.copy());
        return result;
    }

    public void upsert(@NonNull Shortcut value) {
        for (int index = 0; index < shortcuts.size(); index++) {
            if (shortcuts.get(index).id.equals(value.id)) {
                shortcuts.set(index, sanitize(value.copy()));
                save();
                return;
            }
        }
        shortcuts.add(sanitize(value.copy()));
        save();
    }

    public void remove(@NonNull String id) {
        shortcuts.removeIf(value -> value.id.equals(id));
        save();
    }

    public void move(@NonNull String id, int delta) {
        int from = -1;
        for (int i = 0; i < shortcuts.size(); i++) if (shortcuts.get(i).id.equals(id)) from = i;
        if (from < 0) return;
        int to = Math.max(0, Math.min(shortcuts.size() - 1, from + delta));
        if (from == to) return;
        Collections.swap(shortcuts, from, to);
        save();
    }

    private void save() {
        try {
            JSONObject root = new JSONObject().put("version", SCHEMA_VERSION);
            JSONArray items = new JSONArray();
            for (Shortcut value : shortcuts) items.put(toJson(value));
            root.put("items", items);
            preferences.launcherShortcutsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private static Shortcut sanitize(@NonNull Shortcut value) {
        value.title = value.title == null || value.title.trim().isEmpty() ? "Иконка" : value.title.trim();
        value.target = value.target == null ? "" : value.target.trim();
        value.packageName = value.packageName == null ? "" : value.packageName.trim();
        value.longTarget = value.longTarget == null ? "" : value.longTarget.trim();
        value.longPackageName = value.longPackageName == null ? "" : value.longPackageName.trim();
        if (value.command == null) value.command = CarControlCommand.Operation.TOGGLE;
        if (value.longCommand == null) value.longCommand = CarControlCommand.Operation.TOGGLE;
        value.activeBackgroundColor = value.activeBackgroundColor == null
                ? "#CC374151" : value.activeBackgroundColor.trim();
        value.activeIconColor = value.activeIconColor == null
                ? "#FFFFB300" : value.activeIconColor.trim();
        if (!Double.isFinite(value.commandValue)) value.commandValue = 0;
        if (!Double.isFinite(value.longCommandValue)) value.longCommandValue = 0;
        value.iconSizePx = Math.max(24, Math.min(180, value.iconSizePx));
        value.columnSpan = Math.max(1, Math.min(4, value.columnSpan));
        value.rowSpan = Math.max(1, Math.min(4, value.rowSpan));
        return value;
    }

    private static JSONObject toJson(Shortcut value) throws JSONException {
        return new JSONObject()
                .put("id", value.id).put("title", value.title).put("kind", value.kind.name())
                .put("target", value.target).put("packageName", value.packageName)
                .put("command", value.command.name()).put("commandValue", value.commandValue)
                .put("icon", value.icon).put("backgroundColor", value.backgroundColor)
                .put("iconCustomized", value.iconCustomized)
                .put("iconColor", value.iconColor).put("textColor", value.textColor)
                .put("hasLongAction", value.hasLongAction).put("longKind", value.longKind.name())
                .put("longTarget", value.longTarget).put("longPackageName", value.longPackageName)
                .put("longCommand", value.longCommand.name())
                .put("longCommandValue", value.longCommandValue)
                .put("activeBackgroundColor", value.activeBackgroundColor)
                .put("activeIconColor", value.activeIconColor)
                .put("useVehicleStateColor", value.useVehicleStateColor)
                .put("showState", value.showState)
                .put("iconSizePx", value.iconSizePx).put("columnSpan", value.columnSpan)
                .put("rowSpan", value.rowSpan).put("showTitle", value.showTitle)
                .put("enabled", value.enabled);
    }

    private static Shortcut fromJson(JSONObject json) {
        try {
            Shortcut value = new Shortcut();
            value.id = json.optString("id", UUID.randomUUID().toString());
            value.title = json.optString("title", "Иконка");
            value.kind = Kind.valueOf(json.optString("kind", Kind.BUILTIN.name()));
            value.target = json.optString("target", Builtin.ALL_APPS.key);
            value.packageName = json.optString("packageName", "");
            value.command = CarControlCommand.Operation.valueOf(json.optString(
                    "command", CarControlCommand.Operation.TOGGLE.name()));
            value.commandValue = json.optDouble("commandValue", 0);
            value.icon = json.optString("icon", "apps");
            value.iconCustomized = json.has("iconCustomized")
                    ? json.optBoolean("iconCustomized", false)
                    : value.kind != Kind.RULE || !"devices".equals(value.icon);
            value.backgroundColor = json.optString("backgroundColor", "#B5222733");
            value.iconColor = json.optString("iconColor", "#FFFFFFFF");
            value.textColor = json.optString("textColor", "#FFFFFFFF");
            value.hasLongAction = json.optBoolean("hasLongAction", false);
            value.longKind = Kind.valueOf(json.optString("longKind", Kind.BUILTIN.name()));
            value.longTarget = json.optString("longTarget", "");
            value.longPackageName = json.optString("longPackageName", "");
            value.longCommand = CarControlCommand.Operation.valueOf(json.optString(
                    "longCommand", CarControlCommand.Operation.TOGGLE.name()));
            value.longCommandValue = json.optDouble("longCommandValue", 0);
            value.activeBackgroundColor = json.optString("activeBackgroundColor", "#CC374151");
            value.activeIconColor = json.optString("activeIconColor", "#FFFFB300");
            value.useVehicleStateColor = json.optBoolean("useVehicleStateColor", true);
            value.showState = json.optBoolean("showState", true);
            value.iconSizePx = json.optInt("iconSizePx", 54);
            value.columnSpan = json.optInt("columnSpan", 1);
            value.rowSpan = json.optInt("rowSpan", 1);
            value.showTitle = json.optBoolean("showTitle", true);
            value.enabled = json.optBoolean("enabled", true);
            return sanitize(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @NonNull
    private static List<Shortcut> defaults() {
        List<Shortcut> values = new ArrayList<>();
        values.add(builtin(Builtin.MAPS_WINDOW, "Карты"));
        values.add(builtin(Builtin.NAVIGATOR_WINDOW, "Навигатор"));
        values.add(builtin(Builtin.ALL_APPS, "Приложения"));
        values.add(builtin(Builtin.AUTOMATION_SETTINGS, "Умный дом"));
        values.add(builtin(Builtin.EDIT_HOME, "Компоновка"));
        values.add(builtin(Builtin.HOME_SETTINGS, "Настройки"));
        return values;
    }

    @NonNull
    private static Shortcut builtin(@NonNull Builtin action, @NonNull String title) {
        Shortcut value = new Shortcut();
        value.title = title;
        value.kind = Kind.BUILTIN;
        value.target = action.key;
        value.icon = action.icon;
        return value;
    }
}
