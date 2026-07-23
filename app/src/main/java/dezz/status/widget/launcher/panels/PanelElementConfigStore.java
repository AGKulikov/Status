/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.LauncherLayoutStore;

/**
 * Versioned configuration for functional elements inside the simple HOME panels. Outer panel
 * rectangles remain in {@link LauncherLayoutStore}; media and climate keep their richer stores.
 */
public final class PanelElementConfigStore {
    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 200;

    public static final String APPS_HEADING = "heading";
    public static final String APPS_GRID = "grid";
    public static final String CLOCK_TIME = "time";
    public static final String CLOCK_DATE = "date";
    public static final String NAV_ARRIVAL = "arrival";
    public static final String NAV_DURATION = "duration";
    public static final String NAV_DISTANCE = "distance";
    public static final String NAV_MANEUVER_IMAGE = "maneuver_image";
    public static final String NAV_MANEUVER_DISTANCE = "maneuver_distance";
    public static final String NAV_MANEUVER = "maneuver";
    public static final String NAV_TRIP_INFO = "trip_info";
    public static final String NAV_COMBINED = "navigation_combined";
    public static final String NAV_SPEED_LIMIT = "speed_limit";
    public static final String NAV_TRAFFIC_LIGHT = "traffic_light";
    public static final String NAV_LANES_IMAGE = "lanes_image";
    public static final String NAV_LANE_INFO = "lane_info";
    public static final String NAV_JAM_PROGRESS = "jam_progress";
    public static final String NAV_RAINBOW_IMAGE = "rainbow_image";
    public static final String NAV_INACTIVE = "inactive";
    public static final String ACTION_TILES = "tiles";
    public static final String ACTION_ADD = "add";

    public static final class Definition {
        @NonNull public final String id;
        @NonNull public final String label;
        public final boolean enabledByDefault;

        Definition(@NonNull String id, @NonNull String label) {
            this(id, label, true);
        }

        Definition(@NonNull String id, @NonNull String label, boolean enabledByDefault) {
            this.id = id;
            this.label = label;
            this.enabledByDefault = enabledByDefault;
        }
    }

    public static final class Element {
        @NonNull public final String id;
        public boolean enabled;
        public int order;
        public int scalePercent;

        Element(@NonNull String id, boolean enabled, int order, int scalePercent) {
            this.id = id;
            this.enabled = enabled;
            this.order = order;
            this.scalePercent = clampScale(scalePercent);
        }

        @NonNull Element copy() {
            return new Element(id, enabled, order, scalePercent);
        }
    }

    public static final class Panel {
        @NonNull public final String id;
        @NonNull private final List<Element> elements;

        Panel(@NonNull String id, @NonNull List<Element> elements) {
            this.id = id;
            this.elements = elements;
            normalize();
        }

        @NonNull public List<Element> all() {
            List<Element> copy = new ArrayList<>();
            for (Element element : elements) copy.add(element.copy());
            copy.sort(Comparator.comparingInt(value -> value.order));
            return copy;
        }

        @NonNull public List<Element> enabled() {
            List<Element> result = new ArrayList<>();
            for (Element element : all()) if (element.enabled) result.add(element);
            return result;
        }

        @Nullable public Element find(@NonNull String elementId) {
            for (Element element : elements) if (element.id.equals(elementId)) return element;
            return null;
        }

        public boolean isEnabled(@NonNull String elementId) {
            Element value = find(elementId);
            return value != null && value.enabled;
        }

        public int scale(@NonNull String elementId) {
            Element value = find(elementId);
            return value == null ? 100 : clampScale(value.scalePercent);
        }

        public void setEnabled(@NonNull String elementId, boolean enabled) {
            Element value = find(elementId);
            if (value != null) value.enabled = enabled;
        }

        public void setScale(@NonNull String elementId, int scalePercent) {
            Element value = find(elementId);
            if (value != null) value.scalePercent = clampScale(scalePercent);
        }

        public void move(@NonNull String elementId, int delta) {
            List<Element> ordered = orderedMutable();
            int from = -1;
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).id.equals(elementId)) { from = index; break; }
            }
            if (from < 0) return;
            int direction = delta < 0 ? -1 : 1;
            int to = from + direction;
            // Hidden elements stay available in the Add dialog but do not create invisible
            // intermediate steps while the user reorders the active list.
            while (to >= 0 && to < ordered.size()
                    && ordered.get(from).enabled && !ordered.get(to).enabled) {
                to += direction;
            }
            if (to < 0 || to >= ordered.size()) return;
            if (to == from) return;
            Collections.swap(ordered, from, to);
            for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
            normalize();
        }

        @NonNull public Panel copy() {
            List<Element> values = new ArrayList<>();
            for (Element element : elements) values.add(element.copy());
            return new Panel(id, values);
        }

        void normalize() {
            List<Element> ordered = orderedMutable();
            for (int index = 0; index < ordered.size(); index++) {
                Element element = ordered.get(index);
                element.order = index;
                element.scalePercent = clampScale(element.scalePercent);
            }
            elements.clear();
            elements.addAll(ordered);
        }

        @NonNull private List<Element> orderedMutable() {
            List<Element> ordered = new ArrayList<>(elements);
            ordered.sort(Comparator.comparingInt(value -> value.order));
            return ordered;
        }
    }

    private static final Map<String, List<Definition>> DEFINITIONS = definitions();
    private final Preferences preferences;

    public PanelElementConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull public Panel load(@NonNull String panelId) {
        Panel result = defaults(panelId);
        String raw = preferences.launcherPanelElementsJson.get();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return result;
            JSONObject panels = root.optJSONObject("panels");
            JSONObject panel = panels == null ? null : panels.optJSONObject(panelId);
            JSONArray elements = panel == null ? null : panel.optJSONArray("elements");
            if (elements == null) return result;
            Set<String> restored = new HashSet<>();
            for (int index = 0; index < elements.length(); index++) {
                JSONObject value = elements.optJSONObject(index);
                if (value == null) continue;
                Element target = result.find(value.optString("id", ""));
                if (target == null) continue;
                restored.add(target.id);
                target.enabled = value.optBoolean("enabled", target.enabled);
                target.order = value.optInt("order", target.order);
                target.scalePercent = value.optInt("scale", target.scalePercent);
            }
            disableMissingSavedDefinitions(result, panelId, restored);
            result.normalize();
        } catch (JSONException ignored) {
            // Keep usable defaults when imported JSON is incomplete or belongs to a newer build.
        }
        return result;
    }

    public void save(@NonNull Panel value) {
        value.normalize();
        try {
            JSONObject root;
            String raw = preferences.launcherPanelElementsJson.get();
            try { root = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw); }
            catch (JSONException ignored) { root = new JSONObject(); }
            root.put("version", SCHEMA_VERSION);
            JSONObject panels = root.optJSONObject("panels");
            if (panels == null) panels = new JSONObject();
            JSONObject panel = new JSONObject();
            JSONArray elements = new JSONArray();
            for (Element element : value.all()) {
                JSONObject encoded = new JSONObject();
                encoded.put("id", element.id);
                encoded.put("enabled", element.enabled);
                encoded.put("order", element.order);
                encoded.put("scale", element.scalePercent);
                elements.put(encoded);
            }
            panel.put("elements", elements);
            panels.put(value.id, panel);
            root.put("panels", panels);
            preferences.launcherPanelElementsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    public void reset(@NonNull String panelId) {
        save(defaults(panelId));
    }

    @NonNull public static List<Definition> definitions(@NonNull String panelId) {
        List<Definition> values = DEFINITIONS.get(panelId);
        return values == null ? Collections.emptyList() : new ArrayList<>(values);
    }

    @Nullable public static Definition definition(@NonNull String panelId,
            @NonNull String elementId) {
        for (Definition value : definitions(panelId)) if (value.id.equals(elementId)) return value;
        return null;
    }

    /**
     * A saved navigation panel represents a hand-tuned, size-constrained layout. New rows are
     * appended disabled so an update cannot silently overflow its old pixel rectangle. A fresh
     * install still receives the product defaults above.
     */
    static void disableMissingSavedDefinitions(@NonNull Panel panel,
            @NonNull String panelId, @NonNull Set<String> restoredIds) {
        if (!LauncherLayoutStore.NAVIGATION.equals(panelId)) return;
        for (Definition definition : definitions(panelId)) {
            if (!restoredIds.contains(definition.id)) panel.setEnabled(definition.id, false);
        }
    }

    @NonNull static Panel defaults(@NonNull String panelId) {
        List<Element> elements = new ArrayList<>();
        int order = 0;
        for (Definition definition : definitions(panelId)) {
            elements.add(new Element(definition.id, definition.enabledByDefault, order++, 100));
        }
        return new Panel(panelId, elements);
    }

    private static int clampScale(int value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    @NonNull private static Map<String, List<Definition>> definitions() {
        LinkedHashMap<String, List<Definition>> result = new LinkedHashMap<>();
        result.put(LauncherLayoutStore.APPS, list(
                new Definition(APPS_HEADING, "Заголовок «Избранное»"),
                new Definition(APPS_GRID, "Сетка приложений")));
        result.put(LauncherLayoutStore.CLOCK, list(
                new Definition(CLOCK_TIME, "Время"),
                new Definition(CLOCK_DATE, "Дата")));
        result.put(LauncherLayoutStore.NAVIGATION, list(
                new Definition(NAV_ARRIVAL, "Время прибытия"),
                new Definition(NAV_DURATION, "Оставшееся время"),
                new Definition(NAV_DISTANCE, "Оставшееся расстояние"),
                new Definition(NAV_MANEUVER_IMAGE, "Стрелка следующего манёвра", false),
                new Definition(NAV_MANEUVER_DISTANCE, "Расстояние до манёвра", false),
                new Definition(NAV_MANEUVER, "Информация о манёвре"),
                new Definition(NAV_TRIP_INFO, "Сводка поездки", false),
                new Definition(NAV_COMBINED, "Манёвр: стрелка + расстояние + текст", false),
                new Definition(NAV_SPEED_LIMIT, "Ограничение скорости"),
                new Definition(NAV_TRAFFIC_LIGHT, "Светофор и обратный отсчёт"),
                new Definition(NAV_LANES_IMAGE, "Графика полос движения", false),
                new Definition(NAV_LANE_INFO, "Полосы / съезд и расстояние", false),
                new Definition(NAV_JAM_PROGRESS, "Графика прогресса / пробок", false),
                new Definition(NAV_RAINBOW_IMAGE, "Графика Rainbow", false),
                new Definition(NAV_INACTIVE,
                        "Запасное сообщение, если избранные маршруты не настроены")));
        result.put(LauncherLayoutStore.ACTIONS, list(
                new Definition(ACTION_TILES, "Пользовательские плитки"),
                new Definition(ACTION_ADD, "Плитка «Добавить»")));
        return result;
    }

    @SafeVarargs
    @NonNull private static <T> List<T> list(@NonNull T... values) {
        List<T> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
    }
}
