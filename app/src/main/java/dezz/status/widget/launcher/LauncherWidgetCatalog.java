/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.media.MediaPanelConfig;
import dezz.status.widget.launcher.navigation.NavigationPanelConfig;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;

/**
 * One flat source of every item that can be added to HOME.
 *
 * <p>The catalog intentionally contains concrete widgets rather than old panel names. It is used
 * by both the persistent add button and the add button shown in explicit layout mode, so those
 * entry points can never drift into different menus again.</p>
 */
public final class LauncherWidgetCatalog {
    public enum Kind {
        BACKDROP,
        HORIZONTAL_GROUP,
        RESTORE,
        SHORTCUT,
        FAVORITE_APP,
        FAVORITE_ROUTE,
        SIMPLE,
        MEDIA,
        NAVIGATION,
        CLIMATE,
        INFORMATION,
        VEHICLE
    }

    public static final class Entry {
        @NonNull public final Kind kind;
        @NonNull public final String label;
        @NonNull public final String panelId;
        @NonNull public final String elementId;

        private Entry(@NonNull Kind kind, @NonNull String label,
                      @NonNull String panelId, @NonNull String elementId) {
            this.kind = kind;
            this.label = label;
            this.panelId = panelId;
            this.elementId = elementId;
        }
    }

    private LauncherWidgetCatalog() {
    }

    @NonNull
    public static List<Entry> available(
            boolean hasRemoved,
            @NonNull PanelElementConfigStore.Panel clock,
            @NonNull MediaPanelConfig media,
            @NonNull NavigationPanelConfig navigation,
            @NonNull ClimatePanelConfig climate) {
        ArrayList<Entry> result = new ArrayList<>();
        result.add(entry(Kind.BACKDROP, "Подложка", "", ""));
        result.add(entry(Kind.HORIZONTAL_GROUP, "Горизонтальный ряд", "", ""));
        if (hasRemoved) {
            result.add(entry(Kind.RESTORE, "Вернуть удалённый виджет", "", ""));
        }
        result.add(entry(Kind.SHORTCUT, "Кнопка приложения, автомобиля или действие",
                LauncherLayoutStore.ACTIONS, ""));
        result.add(entry(Kind.FAVORITE_APP, "Приложение", LauncherLayoutStore.APPS, ""));
        result.add(entry(Kind.FAVORITE_ROUTE, "Избранный маршрут",
                LauncherLayoutStore.NAVIGATION, ""));

        for (PanelElementConfigStore.Definition definition
                : PanelElementConfigStore.definitions(LauncherLayoutStore.CLOCK)) {
            if (!clock.isEnabled(definition.id)) {
                result.add(entry(Kind.SIMPLE, definition.label,
                        LauncherLayoutStore.CLOCK, definition.id));
            }
        }
        for (MediaPanelConfig.Spec spec : MediaPanelConfig.SPECS) {
            if (!media.element(spec.id).enabled) {
                result.add(entry(Kind.MEDIA, "Медиа · " + spec.label,
                        LauncherLayoutStore.MEDIA, spec.id));
            }
        }
        for (NavigationPanelConfig.Spec spec : NavigationPanelConfig.SPECS) {
            if (!navigation.element(spec.id).enabled) {
                result.add(entry(Kind.NAVIGATION, "Навигация · " + spec.label,
                        LauncherLayoutStore.NAVIGATION, spec.id));
            }
        }
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            if (!climate.isElementEnabled(element.id)) {
                result.add(entry(Kind.CLIMATE, "Климат · " + element.label,
                        LauncherLayoutStore.CLIMATE, element.id));
            }
        }
        result.add(entry(Kind.INFORMATION, "Информационный статус",
                LauncherLayoutStore.INFORMATION, ""));
        result.add(entry(Kind.VEHICLE, "Данные автомобиля или умного дома",
                LauncherLayoutStore.VEHICLE_INFO, ""));
        return Collections.unmodifiableList(result);
    }

    @NonNull
    private static Entry entry(@NonNull Kind kind, @NonNull String label,
                               @NonNull String panelId, @NonNull String elementId) {
        return new Entry(kind, label, panelId, elementId);
    }
}
