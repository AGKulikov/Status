/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Auditable registry for the one-time move from panel-oriented HOME settings to one flat screen.
 *
 * <p>No user value is renamed or discarded. Global values remain on the Launcher screen; rich
 * element controls are reached by tapping that element in explicit layout mode. The old
 * activities remain as contextual editors, but are no longer top-level Launcher subsections.</p>
 */
public final class LauncherSettingsMigrationRegistry {
    public static final int SCHEMA_VERSION = 1;

    public static final class Entry {
        @NonNull public final String oldDestinationId;
        @NonNull public final String purpose;
        @NonNull public final String newLocation;
        @NonNull public final List<String> storageKeys;

        private Entry(@NonNull String oldDestinationId,
                      @NonNull String purpose,
                      @NonNull String newLocation,
                      @NonNull String... storageKeys) {
            this.oldDestinationId = oldDestinationId;
            this.purpose = purpose;
            this.newLocation = newLocation;
            this.storageKeys = Collections.unmodifiableList(Arrays.asList(storageKeys));
        }
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            entry("home_behavior", "Общее поведение HOME",
                    "Настройки → Лаунчер",
                    "launcherBackgroundColor", "launcherShowGrid", "launcherSnapPx",
                    "launcherImmersive", "launcherHomeOpensWindowedNavigator",
                    "launcherClockVisible"),
            entry("home_layout", "Общая геометрия и слои",
                    "Настройки → Лаунчер → Режим компоновки",
                    "launcherLayoutJson", "launcherGlobalElementsJson",
                    "launcherBackdropsJson", "launcherHorizontalGroupsJson"),
            entry("panel_apps", "Приложения и их оформление",
                    "Тап по приложению в режиме компоновки",
                    "launcherFavoritePackages", "launcherFavoriteAppsAppearanceJson",
                    "launcherAppsVisible", "launcherAppsColumns", "launcherPanelElementsJson"),
            entry("all_apps", "Общий экран «Все приложения»",
                    "Настройки → Лаунчер",
                    "launcherAllAppsColumns", "launcherAllAppsIconScalePercent",
                    "launcherAllAppsGapPx", "launcherAllAppsHiddenComponents",
                    "launcherSystemAppsDefaultApplied"),
            entry("panel_media", "Медиа и маршрутизация команд",
                    "Настройки → Лаунчер и тап по медиавиджету",
                    "launcherMediaVisible", "launcherMediaConfigJson",
                    "launcherMediaAutoResumeEnabled", "launcherMediaAutoResumeDelaySeconds",
                    "launcherMediaFixedPlayerEnabled", "launcherMediaFixedPlayerPackage"),
            entry("panel_navigation", "Навигационные элементы",
                    "Тап по навигационному виджету в режиме компоновки",
                    "launcherNavigationVisible", "launcherNavigationConfigJson",
                    "launcherPanelElementsJson"),
            entry("panel_routes", "Избранные маршруты",
                    "Тап по маршруту в режиме компоновки",
                    "launcherFavoriteRoutesJson", "launcherFavoriteRoutesVisible",
                    "launcherFavoriteRoutesColumns", "launcherCombinedNavigationMigrated"),
            entry("panel_climate", "Климатические элементы HOME",
                    "Тап по климатическому виджету в режиме компоновки",
                    "launcherClimateVisible", "launcherClimateConfigJson"),
            entry("panel_vehicle", "Данные автомобиля",
                    "Тап по автомобильному виджету в режиме компоновки",
                    "launcherVehicleInfoVisible", "launcherVehicleInfoConfigJson"),
            entry("panel_information", "Информационные статусы",
                    "Тап по информационному виджету в режиме компоновки",
                    "launcherInformationVisible", "launcherInformationConfigJson"),
            entry("panel_actions", "Кнопки и действия",
                    "Тап по кнопке в режиме компоновки",
                    "launcherActionsVisible", "launcherShortcutsJson",
                    "launcherActionsGridJson", "launcherActionsColumns")
    ));

    private LauncherSettingsMigrationRegistry() {
    }

    @NonNull
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Ordered and duplicate-free list used for the recoverable pre-migration snapshot. */
    @NonNull
    public static List<String> storageKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : ENTRIES) keys.addAll(entry.storageKeys);
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }

    @NonNull
    private static Entry entry(@NonNull String id,
                               @NonNull String purpose,
                               @NonNull String location,
                               @NonNull String... keys) {
        return new Entry(id, purpose, location, keys);
    }
}
