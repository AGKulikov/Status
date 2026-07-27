/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import dezz.status.widget.Fonts;
import dezz.status.widget.Preferences;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.driver.DriverFavoritesPanelConfig;
import dezz.status.widget.integration.SourceBinding;

/** Versioned, ordered collection of user-created HOME icons. */
public final class LauncherShortcutStore {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_ICON_SIZE_PX = 24;
    public static final int MAX_ICON_SIZE_PX = 320;
    public static final int MAX_DRIVER_PANEL_SHORTCUTS = 10;

    public enum Kind { APP, BUILTIN, RULE, INTENT, CAR, INFO, DIVIDER }

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
        @NonNull public List<Double> commandCycleValues = new ArrayList<>();
        /** app = original application icon; otherwise one of LauncherIconResolver preset keys. */
        @NonNull public String icon = "apps";
        /** False means a connector may refresh the suggested icon; true preserves user choice. */
        public boolean iconCustomized = false;
        /** Optional raw value address used to render live state for a RULE shortcut. */
        @Nullable public SourceBinding stateBinding;
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
        @NonNull public List<Double> longCommandCycleValues = new ArrayList<>();
        @NonNull public String activeBackgroundColor = "#CC374151";
        @NonNull public String activeIconColor = "#FFFFB300";
        public boolean useVehicleStateColor = true;
        public boolean showState = true;
        /**
         * Driver rail only: renders the live climate presentation independently from the
         * shortcut's primary action. This lets the same live tile launch any supported action.
         */
        public boolean liveClimateIcon = false;
        /** Driver climate tile adds AUTO/airflow when enabled; temperature and scale stay basic. */
        public boolean extendedClimateInfo = false;
        /** Extra vertical space between temperature/fan and AUTO/airflow, in source pixels. */
        public int climateDetailsGapPx = 0;
        public int iconSizePx = 54;
        public int dividerThicknessPx = 2;
        /** -1 inherits the panel-wide value; otherwise this button owns its following gap. */
        public int gapAfterPx = -1;
        public int columnSpan = 1;
        public int rowSpan = 1;
        public boolean showTitle = true;
        /** INFO only: independently styled label/value text on the driver rail and Favorites. */
        public int informationLabelTextSizeSp = 11;
        public int informationValueTextSizeSp = 20;
        @NonNull public String informationFontFamily = Fonts.DEFAULT_KEY;
        public boolean informationTextBold = true;
        public boolean informationTextItalic = false;
        /** 0=start/top, 1=center, 2=end/bottom. */
        public int informationHorizontalAlignment = 0;
        public int informationVerticalAlignment = 1;
        public int informationPaddingLeftPx = 10;
        public int informationPaddingTopPx = 7;
        public int informationPaddingRightPx = 10;
        public int informationPaddingBottomPx = 7;
        /** INFO only: empty means a standalone row; equal arbitrary names share one row. */
        @NonNull public String informationGroup = "";
        /** INFO only: 0 = above driver controls, 1 = below driver controls. */
        public int informationPlacement = 0;
        /** Named information-row settings; duplicated on members for backward-compatible JSON. */
        public int informationGroupGapPx = 4;
        public int informationGroupMarginLeftPx = 0;
        public int informationGroupMarginTopPx = 0;
        public int informationGroupMarginRightPx = 0;
        public int informationGroupMarginBottomPx = 0;
        public int informationGroupPaddingLeftPx = 0;
        public int informationGroupPaddingTopPx = 0;
        public int informationGroupPaddingRightPx = 0;
        public int informationGroupPaddingBottomPx = 0;
        /** 0=start/top, 1=center, 2=end/bottom. */
        public int informationGroupHorizontalAlignment = 0;
        public int informationGroupVerticalAlignment = 1;
        /** 0=equal-width cells, 1=content-width cells. */
        public int informationGroupDistribution = 0;
        @NonNull public String informationGroupBackgroundColor = "#00000000";
        public int informationGroupCornerRadiusPx = 0;
        public boolean informationShowValue = true;
        public int informationIconSizePx = 32;
        public int informationIconAlpha = 255;
        public int informationIconOutlineAlpha = 0;
        public int informationIconOutlineWidth = 0;
        /** Status-bar sources can reuse their live icon family, semantic colour and badges. */
        public boolean informationUseStatusIconStyle = false;
        /** Driver Favorites only: dismiss the owning compact panel after either action. */
        public boolean closeFavoritePanelAfterAction = false;
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
            value.commandCycleValues = new ArrayList<>(commandCycleValues);
            value.icon = icon;
            value.iconCustomized = iconCustomized;
            value.stateBinding = stateBinding;
            value.backgroundColor = backgroundColor;
            value.iconColor = iconColor;
            value.textColor = textColor;
            value.hasLongAction = hasLongAction;
            value.longKind = longKind;
            value.longTarget = longTarget;
            value.longPackageName = longPackageName;
            value.longCommand = longCommand;
            value.longCommandValue = longCommandValue;
            value.longCommandCycleValues = new ArrayList<>(longCommandCycleValues);
            value.activeBackgroundColor = activeBackgroundColor;
            value.activeIconColor = activeIconColor;
            value.useVehicleStateColor = useVehicleStateColor;
            value.showState = showState;
            value.liveClimateIcon = liveClimateIcon;
            value.extendedClimateInfo = extendedClimateInfo;
            value.climateDetailsGapPx = climateDetailsGapPx;
            value.iconSizePx = iconSizePx;
            value.dividerThicknessPx = dividerThicknessPx;
            value.gapAfterPx = gapAfterPx;
            value.columnSpan = columnSpan;
            value.rowSpan = rowSpan;
            value.showTitle = showTitle;
            value.informationLabelTextSizeSp = informationLabelTextSizeSp;
            value.informationValueTextSizeSp = informationValueTextSizeSp;
            value.informationFontFamily = informationFontFamily;
            value.informationTextBold = informationTextBold;
            value.informationTextItalic = informationTextItalic;
            value.informationHorizontalAlignment = informationHorizontalAlignment;
            value.informationVerticalAlignment = informationVerticalAlignment;
            value.informationPaddingLeftPx = informationPaddingLeftPx;
            value.informationPaddingTopPx = informationPaddingTopPx;
            value.informationPaddingRightPx = informationPaddingRightPx;
            value.informationPaddingBottomPx = informationPaddingBottomPx;
            value.informationGroup = informationGroup;
            value.informationPlacement = informationPlacement;
            value.informationGroupGapPx = informationGroupGapPx;
            value.informationGroupMarginLeftPx = informationGroupMarginLeftPx;
            value.informationGroupMarginTopPx = informationGroupMarginTopPx;
            value.informationGroupMarginRightPx = informationGroupMarginRightPx;
            value.informationGroupMarginBottomPx = informationGroupMarginBottomPx;
            value.informationGroupPaddingLeftPx = informationGroupPaddingLeftPx;
            value.informationGroupPaddingTopPx = informationGroupPaddingTopPx;
            value.informationGroupPaddingRightPx = informationGroupPaddingRightPx;
            value.informationGroupPaddingBottomPx = informationGroupPaddingBottomPx;
            value.informationGroupHorizontalAlignment = informationGroupHorizontalAlignment;
            value.informationGroupVerticalAlignment = informationGroupVerticalAlignment;
            value.informationGroupDistribution = informationGroupDistribution;
            value.informationGroupBackgroundColor = informationGroupBackgroundColor;
            value.informationGroupCornerRadiusPx = informationGroupCornerRadiusPx;
            value.informationShowValue = informationShowValue;
            value.informationIconSizePx = informationIconSizePx;
            value.informationIconAlpha = informationIconAlpha;
            value.informationIconOutlineAlpha = informationIconOutlineAlpha;
            value.informationIconOutlineWidth = informationIconOutlineWidth;
            value.informationUseStatusIconStyle = informationUseStatusIconStyle;
            value.closeFavoritePanelAfterAction = closeFavoritePanelAfterAction;
            value.enabled = enabled;
            return value;
        }
    }

    public enum Builtin {
        HOME("home", "Домой", "home"),
        BACK("back", "Назад", "back"),
        RECENTS("recents", "Недавние приложения", "apps"),
        STOCK_CLIMATE("stock_climate", "Штатный климат", "climate"),
        ALL_APPS("all_apps", "Все приложения", "apps"),
        FAVORITES("favorites", "Избранное", "work"),
        FAVORITE_ROUTE("favorite_route", "Избранная точка", "navigation"),
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
            if (isDriverFavoritesTarget(key)) return FAVORITES;
            if (isFavoriteRouteTarget(key)) return FAVORITE_ROUTE;
            for (Builtin value : values()) if (value.key.equals(key)) return value;
            return ALL_APPS;
        }
    }

    private static final String DRIVER_FAVORITES_TARGET_PREFIX = "favorites:";
    private static final String FAVORITE_ROUTE_TARGET_PREFIX = "route:";

    @NonNull
    public static String driverFavoritesTarget(@NonNull String panelId) {
        String id = panelId.trim();
        if (id.isEmpty()) id = DriverFavoritesPanelConfig.DEFAULT_ID;
        return DRIVER_FAVORITES_TARGET_PREFIX + id;
    }

    public static boolean isDriverFavoritesTarget(@Nullable String target) {
        return Builtin.FAVORITES.key.equals(target)
                || (target != null && target.startsWith(DRIVER_FAVORITES_TARGET_PREFIX));
    }

    @NonNull
    public static String driverFavoritesPanelId(@Nullable String target) {
        if (target != null && target.startsWith(DRIVER_FAVORITES_TARGET_PREFIX)) {
            String id = target.substring(DRIVER_FAVORITES_TARGET_PREFIX.length()).trim();
            if (!id.isEmpty()) return id;
        }
        return DriverFavoritesPanelConfig.DEFAULT_ID;
    }

    @NonNull
    public static String favoriteRouteTarget(@NonNull String routeId) {
        return FAVORITE_ROUTE_TARGET_PREFIX + routeId.trim();
    }

    public static boolean isFavoriteRouteTarget(@Nullable String target) {
        return target != null && target.startsWith(FAVORITE_ROUTE_TARGET_PREFIX);
    }

    @NonNull
    public static String favoriteRouteId(@Nullable String target) {
        return isFavoriteRouteTarget(target)
                ? target.substring(FAVORITE_ROUTE_TARGET_PREFIX.length()).trim() : "";
    }

    private final Preferences preferences;
    private final Preferences.Str storage;
    private final boolean driverPanel;
    private final boolean driverFavorites;
    private final List<Shortcut> shortcuts = new ArrayList<>();

    public LauncherShortcutStore(@NonNull Preferences preferences) {
        this(preferences, preferences.launcherShortcutsJson, false, false);
    }

    private LauncherShortcutStore(@NonNull Preferences preferences,
                                  @NonNull Preferences.Str storage,
                                  boolean driverPanel,
                                  boolean driverFavorites) {
        this.preferences = preferences;
        this.storage = storage;
        this.driverPanel = driverPanel;
        this.driverFavorites = driverFavorites;
        load();
    }

    @NonNull
    public static LauncherShortcutStore forDriverPanel(@NonNull Preferences preferences) {
        return forDriverPanel(preferences, preferences.activeDriverPanelProfile());
    }

    @NonNull
    public static LauncherShortcutStore forDriverPanel(
            @NonNull Preferences preferences,
            @NonNull Preferences.DriverPanelProfile profile) {
        return new LauncherShortcutStore(preferences, profile.shortcutsJson, true, false);
    }

    @NonNull
    public static LauncherShortcutStore forDriverFavorites(
            @NonNull Preferences preferences) {
        return forDriverFavorites(preferences, DriverFavoritesPanelConfig.DEFAULT_ID);
    }

    @NonNull
    public static LauncherShortcutStore forDriverFavorites(
            @NonNull Preferences preferences, @NonNull String panelId) {
        return new LauncherShortcutStore(preferences,
                preferences.driverFavoritesShortcuts(panelId), false, true);
    }

    public void load() {
        List<Shortcut> previous = new ArrayList<>();
        for (Shortcut shortcut : shortcuts) previous.add(shortcut.copy());
        shortcuts.clear();
        String raw = storage.get();
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
                if (json == null) throw new JSONException("item");
                Shortcut value = fromJson(json);
                if (value == null) throw new JSONException("item");
                shortcuts.add(value);
            }
        } catch (JSONException error) {
            // A partial import or a future schema must never overwrite the user's only copy of
            // actions, RULE bindings and long-press commands with defaults. Keep the last valid
            // in-memory set; on a cold start provide usable defaults without persisting them.
            shortcuts.clear();
            shortcuts.addAll(previous.isEmpty() ? defaults() : previous);
        }
    }

    @NonNull
    public List<Shortcut> all() {
        List<Shortcut> result = new ArrayList<>();
        for (Shortcut shortcut : shortcuts) result.add(shortcut.copy());
        return result;
    }

    /**
     * Adds or replaces one item.
     *
     * @return {@code false} only when a new interactive driver-rail button would exceed the
     * ten-button safety limit. Read-only information tiles and dividers remain unlimited.
     */
    public boolean upsert(@NonNull Shortcut value) {
        for (int index = 0; index < shortcuts.size(); index++) {
            if (shortcuts.get(index).id.equals(value.id)) {
                if (driverPanel && isInteractive(value)
                        && !isInteractive(shortcuts.get(index))
                        && interactiveCount(shortcuts) >= MAX_DRIVER_PANEL_SHORTCUTS) {
                    return false;
                }
                shortcuts.set(index, sanitize(value.copy()));
                save();
                return true;
            }
        }
        if (driverPanel && isInteractive(value)
                && interactiveCount(shortcuts) >= MAX_DRIVER_PANEL_SHORTCUTS) return false;
        shortcuts.add(sanitize(value.copy()));
        save();
        return true;
    }

    private static int interactiveCount(@NonNull List<Shortcut> values) {
        int result = 0;
        for (Shortcut value : values) if (isInteractive(value)) result++;
        return result;
    }

    public static boolean isInteractive(@NonNull Shortcut value) {
        return value.kind != Kind.INFO && value.kind != Kind.DIVIDER;
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

    /** Moves one member left/right inside its named horizontal information row. */
    public void moveInformationGroupItem(@NonNull String id, int delta) {
        if (delta == 0) return;
        Shortcut moving = null;
        for (Shortcut value : shortcuts) {
            if (value.id.equals(id)) {
                moving = value;
                break;
            }
        }
        if (moving == null || moving.kind != Kind.INFO
                || moving.informationGroup.trim().isEmpty()) return;
        List<Integer> memberIndices = new ArrayList<>();
        for (int index = 0; index < shortcuts.size(); index++) {
            Shortcut candidate = shortcuts.get(index);
            if (candidate.kind == Kind.INFO
                    && candidate.informationPlacement == moving.informationPlacement
                    && moving.informationGroup.equals(candidate.informationGroup)) {
                memberIndices.add(index);
            }
        }
        int from = -1;
        for (int index = 0; index < memberIndices.size(); index++) {
            if (shortcuts.get(memberIndices.get(index)).id.equals(id)) {
                from = index;
                break;
            }
        }
        if (from < 0) return;
        int to = Math.max(0, Math.min(memberIndices.size() - 1, from + delta));
        if (from == to) return;
        Collections.swap(shortcuts, memberIndices.get(from), memberIndices.get(to));
        save();
    }

    /** Moves a complete named information row before/after the adjacent row at one placement. */
    public void moveInformationGroup(@NonNull String rawGroup, int placement, int delta) {
        String group = rawGroup.trim();
        if (group.isEmpty() || delta == 0) return;
        List<Integer> slots = new ArrayList<>();
        List<String> rowKeys = new ArrayList<>();
        List<List<Shortcut>> rows = new ArrayList<>();
        for (int index = 0; index < shortcuts.size(); index++) {
            Shortcut value = shortcuts.get(index);
            if (value.kind != Kind.INFO
                    || value.informationPlacement != (placement == 1 ? 1 : 0)) continue;
            slots.add(index);
            String key = value.informationGroup.trim().isEmpty()
                    ? "\u0000" + value.id : value.informationGroup.trim();
            int rowIndex = rowKeys.indexOf(key);
            if (rowIndex < 0) {
                rowKeys.add(key);
                rows.add(new ArrayList<>());
                rowIndex = rows.size() - 1;
            }
            rows.get(rowIndex).add(value);
        }
        int from = rowKeys.indexOf(group);
        if (from < 0) return;
        int to = Math.max(0, Math.min(rows.size() - 1, from + delta));
        if (from == to) return;
        List<Shortcut> moving = rows.remove(from);
        String movingKey = rowKeys.remove(from);
        rows.add(to, moving);
        rowKeys.add(to, movingKey);
        List<Shortcut> flattened = new ArrayList<>();
        for (List<Shortcut> row : rows) flattened.addAll(row);
        for (int index = 0; index < slots.size(); index++) {
            shortcuts.set(slots.get(index), flattened.get(index));
        }
        save();
    }

    private void save() {
        try {
            JSONObject root = new JSONObject().put("version", SCHEMA_VERSION);
            JSONArray items = new JSONArray();
            for (Shortcut value : shortcuts) items.put(toJson(value));
            root.put("items", items);
            storage.set(root.toString());
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
        value.commandCycleValues = sanitizeCycleValues(value.commandCycleValues);
        value.longCommandCycleValues = sanitizeCycleValues(value.longCommandCycleValues);
        value.iconSizePx = Math.max(MIN_ICON_SIZE_PX,
                Math.min(MAX_ICON_SIZE_PX, value.iconSizePx));
        value.climateDetailsGapPx = Math.max(0,
                Math.min(96, value.climateDetailsGapPx));
        value.dividerThicknessPx = Math.max(1, Math.min(20, value.dividerThicknessPx));
        value.gapAfterPx = Math.max(-1, Math.min(80, value.gapAfterPx));
        value.columnSpan = Math.max(1,
                Math.min(LauncherActionsGridConfig.MAX_COLUMNS, value.columnSpan));
        value.rowSpan = Math.max(1,
                Math.min(LauncherActionsGridConfig.MAX_ROWS, value.rowSpan));
        value.informationLabelTextSizeSp = Math.max(8,
                Math.min(72, value.informationLabelTextSizeSp));
        value.informationValueTextSizeSp = Math.max(8,
                Math.min(96, value.informationValueTextSizeSp));
        value.informationFontFamily = Fonts.findByKey(
                value.informationFontFamily).key;
        value.informationHorizontalAlignment = Math.max(0,
                Math.min(2, value.informationHorizontalAlignment));
        value.informationVerticalAlignment = Math.max(0,
                Math.min(2, value.informationVerticalAlignment));
        value.informationPaddingLeftPx = clampInformationPadding(
                value.informationPaddingLeftPx);
        value.informationPaddingTopPx = clampInformationPadding(
                value.informationPaddingTopPx);
        value.informationPaddingRightPx = clampInformationPadding(
                value.informationPaddingRightPx);
        value.informationPaddingBottomPx = clampInformationPadding(
                value.informationPaddingBottomPx);
        value.informationGroup = value.informationGroup == null
                ? "" : value.informationGroup.trim();
        value.informationPlacement = value.informationPlacement == 1 ? 1 : 0;
        value.informationGroupGapPx = clampGroupSpacing(value.informationGroupGapPx);
        value.informationGroupMarginLeftPx =
                clampGroupSpacing(value.informationGroupMarginLeftPx);
        value.informationGroupMarginTopPx =
                clampGroupSpacing(value.informationGroupMarginTopPx);
        value.informationGroupMarginRightPx =
                clampGroupSpacing(value.informationGroupMarginRightPx);
        value.informationGroupMarginBottomPx =
                clampGroupSpacing(value.informationGroupMarginBottomPx);
        value.informationGroupPaddingLeftPx =
                clampGroupSpacing(value.informationGroupPaddingLeftPx);
        value.informationGroupPaddingTopPx =
                clampGroupSpacing(value.informationGroupPaddingTopPx);
        value.informationGroupPaddingRightPx =
                clampGroupSpacing(value.informationGroupPaddingRightPx);
        value.informationGroupPaddingBottomPx =
                clampGroupSpacing(value.informationGroupPaddingBottomPx);
        value.informationGroupHorizontalAlignment = Math.max(0,
                Math.min(2, value.informationGroupHorizontalAlignment));
        value.informationGroupVerticalAlignment = Math.max(0,
                Math.min(2, value.informationGroupVerticalAlignment));
        value.informationGroupDistribution =
                value.informationGroupDistribution == 1 ? 1 : 0;
        value.informationGroupBackgroundColor =
                colorOrTransparent(value.informationGroupBackgroundColor);
        value.informationGroupCornerRadiusPx = Math.max(0,
                Math.min(120, value.informationGroupCornerRadiusPx));
        value.informationIconSizePx = Math.max(12,
                Math.min(MAX_ICON_SIZE_PX, value.informationIconSizePx));
        value.informationIconAlpha = clampByte(value.informationIconAlpha);
        value.informationIconOutlineAlpha = clampByte(
                value.informationIconOutlineAlpha);
        value.informationIconOutlineWidth = Math.max(0,
                Math.min(24, value.informationIconOutlineWidth));
        return value;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int clampInformationPadding(int value) {
        return Math.max(0, Math.min(96, value));
    }

    private static int clampGroupSpacing(int value) {
        return Math.max(0, Math.min(120, value));
    }

    @NonNull
    private static String colorOrTransparent(@Nullable String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                ? clean : "#00000000";
    }

    private static JSONObject toJson(Shortcut value) throws JSONException {
        JSONObject json = new JSONObject()
                .put("id", value.id).put("title", value.title).put("kind", value.kind.name())
                .put("target", value.target).put("packageName", value.packageName)
                .put("command", value.command.name()).put("commandValue", value.commandValue)
                .put("commandCycleValues", cycleValuesToJson(value.commandCycleValues))
                .put("icon", value.icon).put("backgroundColor", value.backgroundColor)
                .put("iconCustomized", value.iconCustomized)
                .put("iconColor", value.iconColor).put("textColor", value.textColor)
                .put("hasLongAction", value.hasLongAction).put("longKind", value.longKind.name())
                .put("longTarget", value.longTarget).put("longPackageName", value.longPackageName)
                .put("longCommand", value.longCommand.name())
                .put("longCommandValue", value.longCommandValue)
                .put("longCommandCycleValues", cycleValuesToJson(value.longCommandCycleValues))
                .put("activeBackgroundColor", value.activeBackgroundColor)
                .put("activeIconColor", value.activeIconColor)
                .put("useVehicleStateColor", value.useVehicleStateColor)
                .put("showState", value.showState)
                .put("liveClimateIcon", value.liveClimateIcon)
                .put("extendedClimateInfo", value.extendedClimateInfo)
                .put("climateDetailsGapPx", value.climateDetailsGapPx)
                .put("iconSizePx", value.iconSizePx)
                .put("dividerThicknessPx", value.dividerThicknessPx)
                .put("gapAfterPx", value.gapAfterPx)
                .put("columnSpan", value.columnSpan)
                .put("rowSpan", value.rowSpan).put("showTitle", value.showTitle)
                .put("informationLabelTextSizeSp", value.informationLabelTextSizeSp)
                .put("informationValueTextSizeSp", value.informationValueTextSizeSp)
                .put("informationFontFamily", value.informationFontFamily)
                .put("informationTextBold", value.informationTextBold)
                .put("informationTextItalic", value.informationTextItalic)
                .put("informationHorizontalAlignment",
                        value.informationHorizontalAlignment)
                .put("informationVerticalAlignment",
                        value.informationVerticalAlignment)
                .put("informationPaddingLeftPx", value.informationPaddingLeftPx)
                .put("informationPaddingTopPx", value.informationPaddingTopPx)
                .put("informationPaddingRightPx", value.informationPaddingRightPx)
                .put("informationPaddingBottomPx", value.informationPaddingBottomPx)
                .put("informationGroup", value.informationGroup)
                .put("informationPlacement", value.informationPlacement)
                .put("informationGroupGapPx", value.informationGroupGapPx)
                .put("informationGroupMarginLeftPx",
                        value.informationGroupMarginLeftPx)
                .put("informationGroupMarginTopPx",
                        value.informationGroupMarginTopPx)
                .put("informationGroupMarginRightPx",
                        value.informationGroupMarginRightPx)
                .put("informationGroupMarginBottomPx",
                        value.informationGroupMarginBottomPx)
                .put("informationGroupPaddingLeftPx",
                        value.informationGroupPaddingLeftPx)
                .put("informationGroupPaddingTopPx",
                        value.informationGroupPaddingTopPx)
                .put("informationGroupPaddingRightPx",
                        value.informationGroupPaddingRightPx)
                .put("informationGroupPaddingBottomPx",
                        value.informationGroupPaddingBottomPx)
                .put("informationGroupHorizontalAlignment",
                        value.informationGroupHorizontalAlignment)
                .put("informationGroupVerticalAlignment",
                        value.informationGroupVerticalAlignment)
                .put("informationGroupDistribution",
                        value.informationGroupDistribution)
                .put("informationGroupBackgroundColor",
                        value.informationGroupBackgroundColor)
                .put("informationGroupCornerRadiusPx",
                        value.informationGroupCornerRadiusPx)
                .put("informationShowValue", value.informationShowValue)
                .put("informationIconSizePx", value.informationIconSizePx)
                .put("informationIconAlpha", value.informationIconAlpha)
                .put("informationIconOutlineAlpha",
                        value.informationIconOutlineAlpha)
                .put("informationIconOutlineWidth",
                        value.informationIconOutlineWidth)
                .put("informationUseStatusIconStyle",
                        value.informationUseStatusIconStyle)
                .put("closeFavoritePanelAfterAction",
                        value.closeFavoritePanelAfterAction)
                .put("enabled", value.enabled);
        if (value.stateBinding != null && value.stateBinding.isBound()) {
            json.put("stateBinding", value.stateBinding.toJson());
        }
        return json;
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
            value.commandCycleValues = cycleValuesFromJson(
                    json.optJSONArray("commandCycleValues"));
            value.icon = json.optString("icon", "apps");
            value.iconCustomized = json.has("iconCustomized")
                    ? json.optBoolean("iconCustomized", false)
                    : value.kind != Kind.RULE || !"devices".equals(value.icon);
            JSONObject stateBinding = json.optJSONObject("stateBinding");
            value.stateBinding = stateBinding == null ? null
                    : SourceBinding.fromJson(stateBinding);
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
            value.longCommandCycleValues = cycleValuesFromJson(
                    json.optJSONArray("longCommandCycleValues"));
            value.activeBackgroundColor = json.optString("activeBackgroundColor", "#CC374151");
            value.activeIconColor = json.optString("activeIconColor", "#FFFFB300");
            value.useVehicleStateColor = json.optBoolean("useVehicleStateColor", true);
            value.showState = json.optBoolean("showState", true);
            value.liveClimateIcon = json.has("liveClimateIcon")
                    ? json.optBoolean("liveClimateIcon", false)
                    : value.kind == Kind.BUILTIN
                    && Builtin.STOCK_CLIMATE.key.equals(value.target);
            value.extendedClimateInfo = json.optBoolean("extendedClimateInfo", false);
            value.climateDetailsGapPx = json.optInt("climateDetailsGapPx", 0);
            value.iconSizePx = json.optInt("iconSizePx", 54);
            value.dividerThicknessPx = json.optInt("dividerThicknessPx", 2);
            value.gapAfterPx = json.optInt("gapAfterPx", -1);
            value.columnSpan = json.optInt("columnSpan", 1);
            value.rowSpan = json.optInt("rowSpan", 1);
            value.showTitle = json.optBoolean("showTitle", true);
            value.informationLabelTextSizeSp =
                    json.optInt("informationLabelTextSizeSp", 11);
            value.informationValueTextSizeSp =
                    json.optInt("informationValueTextSizeSp", 20);
            value.informationFontFamily = json.optString(
                    "informationFontFamily", Fonts.DEFAULT_KEY);
            value.informationTextBold =
                    json.optBoolean("informationTextBold", true);
            value.informationTextItalic =
                    json.optBoolean("informationTextItalic", false);
            value.informationHorizontalAlignment =
                    json.optInt("informationHorizontalAlignment", 0);
            value.informationVerticalAlignment =
                    json.optInt("informationVerticalAlignment", 1);
            value.informationPaddingLeftPx =
                    json.optInt("informationPaddingLeftPx", 10);
            value.informationPaddingTopPx =
                    json.optInt("informationPaddingTopPx", 7);
            value.informationPaddingRightPx =
                    json.optInt("informationPaddingRightPx", 10);
            value.informationPaddingBottomPx =
                    json.optInt("informationPaddingBottomPx", 7);
            value.informationGroup = json.optString("informationGroup", "");
            value.informationPlacement = json.optInt("informationPlacement", 0);
            value.informationGroupGapPx =
                    json.optInt("informationGroupGapPx", 4);
            value.informationGroupMarginLeftPx =
                    json.optInt("informationGroupMarginLeftPx", 0);
            value.informationGroupMarginTopPx =
                    json.optInt("informationGroupMarginTopPx", 0);
            value.informationGroupMarginRightPx =
                    json.optInt("informationGroupMarginRightPx", 0);
            value.informationGroupMarginBottomPx =
                    json.optInt("informationGroupMarginBottomPx", 0);
            value.informationGroupPaddingLeftPx =
                    json.optInt("informationGroupPaddingLeftPx", 0);
            value.informationGroupPaddingTopPx =
                    json.optInt("informationGroupPaddingTopPx", 0);
            value.informationGroupPaddingRightPx =
                    json.optInt("informationGroupPaddingRightPx", 0);
            value.informationGroupPaddingBottomPx =
                    json.optInt("informationGroupPaddingBottomPx", 0);
            value.informationGroupHorizontalAlignment =
                    json.optInt("informationGroupHorizontalAlignment", 0);
            value.informationGroupVerticalAlignment =
                    json.optInt("informationGroupVerticalAlignment", 1);
            value.informationGroupDistribution =
                    json.optInt("informationGroupDistribution", 0);
            value.informationGroupBackgroundColor =
                    json.optString("informationGroupBackgroundColor", "#00000000");
            value.informationGroupCornerRadiusPx =
                    json.optInt("informationGroupCornerRadiusPx", 0);
            value.informationShowValue =
                    json.optBoolean("informationShowValue", true);
            value.informationIconSizePx =
                    json.optInt("informationIconSizePx", 32);
            value.informationIconAlpha =
                    json.optInt("informationIconAlpha", 255);
            value.informationIconOutlineAlpha =
                    json.optInt("informationIconOutlineAlpha", 0);
            value.informationIconOutlineWidth =
                    json.optInt("informationIconOutlineWidth", 0);
            value.informationUseStatusIconStyle =
                    json.optBoolean("informationUseStatusIconStyle", false);
            value.closeFavoritePanelAfterAction =
                    json.optBoolean("closeFavoritePanelAfterAction", false);
            value.enabled = json.optBoolean("enabled", true);
            return sanitize(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @NonNull
    private static List<Double> sanitizeCycleValues(@Nullable List<Double> source) {
        List<Double> result = new ArrayList<>();
        if (source == null) return result;
        for (Double value : source) {
            if (value == null || !Double.isFinite(value) || result.contains(value)) continue;
            result.add(value);
        }
        return result;
    }

    @NonNull
    private static JSONArray cycleValuesToJson(@Nullable List<Double> source) {
        JSONArray result = new JSONArray();
        for (Double value : sanitizeCycleValues(source)) result.put(value);
        return result;
    }

    @NonNull
    private static List<Double> cycleValuesFromJson(@Nullable JSONArray source) {
        List<Double> result = new ArrayList<>();
        if (source == null) return result;
        for (int index = 0; index < source.length(); index++) {
            double value = source.optDouble(index, Double.NaN);
            if (Double.isFinite(value) && !result.contains(value)) result.add(value);
        }
        return result;
    }

    @NonNull
    private List<Shortcut> defaults() {
        if (driverFavorites) return Collections.emptyList();
        if (driverPanel) {
            List<Shortcut> values = new ArrayList<>();
            values.add(driverBuiltin(Builtin.HOME, "Домой"));
            values.add(driverBuiltin(Builtin.BACK, "Назад"));
            Shortcut climate = driverBuiltin(Builtin.STOCK_CLIMATE, "Климат");
            climate.iconSizePx = 76;
            climate.liveClimateIcon = true;
            climate.extendedClimateInfo = false;
            values.add(climate);
            values.add(driverBuiltin(Builtin.ALL_APPS, "Приложения"));
            return values;
        }
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
    private static Shortcut driverBuiltin(@NonNull Builtin action, @NonNull String title) {
        Shortcut value = builtin(action, title);
        value.showTitle = false;
        value.iconSizePx = 54;
        value.backgroundColor = "#00000000";
        // Night driver-mode tint from MonjaroPanel. Persisted shortcuts remain untouched.
        value.iconColor = "#FFE0E5F3";
        return value;
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
