/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User-facing appearance and content selection for the independent HOME climate panel. */
public final class ClimatePanelConfig {
    public static final String POWER = "climate.power";
    public static final String AC = "climate.ac";
    public static final String AUTO = "climate.auto";
    public static final String TEMP_DRIVER = "climate.temp_driver";
    public static final String TEMP_PASSENGER = "climate.temp_passenger";
    public static final String FAN = "climate.fan";
    public static final String SEAT_HEAT_DRIVER = "climate.seat_heat_driver";
    public static final String SEAT_HEAT_PASSENGER = "climate.seat_heat_passenger";
    public static final String SEAT_VENT_DRIVER = "climate.seat_vent_driver";
    public static final String SEAT_VENT_PASSENGER = "climate.seat_vent_passenger";
    public static final String WHEEL_HEAT = "climate.wheel_heat";
    public static final String DEFROST_FRONT = "climate.defrost_front";
    public static final String DEFROST_REAR = "climate.defrost_rear";

    public static final class Element {
        @NonNull public final String id;
        @NonNull public final String label;

        private Element(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Stable presentation order also used when subscribing to the vehicle. */
    public static final List<Element> ELEMENTS = Collections.unmodifiableList(Arrays.asList(
            new Element(POWER, "Питание климата"),
            new Element(AC, "Кондиционер A/C"),
            new Element(AUTO, "Автоматический режим"),
            new Element(TEMP_DRIVER, "Температура водителя"),
            new Element(TEMP_PASSENGER, "Температура пассажира"),
            new Element(FAN, "Скорость вентилятора"),
            new Element(SEAT_HEAT_DRIVER, "Подогрев сиденья водителя"),
            new Element(SEAT_HEAT_PASSENGER, "Подогрев сиденья пассажира"),
            new Element(SEAT_VENT_DRIVER, "Вентиляция сиденья водителя"),
            new Element(SEAT_VENT_PASSENGER, "Вентиляция сиденья пассажира"),
            new Element(WHEEL_HEAT, "Подогрев руля"),
            new Element(DEFROST_FRONT, "Обогрев лобового стекла"),
            new Element(DEFROST_REAR, "Обогрев заднего стекла")));

    @NonNull public String backgroundColor = "#171820";
    public int backgroundAlpha = 224;
    public int cornerRadiusPx = 34;
    public int tileCornerRadiusPx = 24;
    public int tileSpacingPx = 10;
    public int activeTileAlpha = 228;
    public int inactiveTileAlpha = 72;
    @NonNull public String accentColor = "#59A9FF";
    @NonNull public String inactiveColor = "#E4E5EA";
    @NonNull public String textColor = "#FFFFFF";
    public int scalePercent = 100;
    public boolean showTitle = true;
    public boolean useVehicleStateColors = true;
    private final LinkedHashMap<String, Boolean> elements = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> elementScales = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> elementWidths = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> elementHeights = new LinkedHashMap<>();
    private final ArrayList<String> elementOrder = new ArrayList<>();

    public ClimatePanelConfig() {
        for (Element element : ELEMENTS) {
            elements.put(element.id, true);
            elementScales.put(element.id, 100);
            elementWidths.put(element.id, 100);
            elementHeights.put(element.id, 100);
            elementOrder.add(element.id);
        }
    }

    public boolean isElementEnabled(@NonNull String id) {
        Boolean value = elements.get(id);
        return value == null || value;
    }

    public void setElementEnabled(@NonNull String id, boolean enabled) {
        if (isKnownElement(id)) elements.put(id, enabled);
    }

    /** True when the HOME panel contains at least one user-selected climate control. */
    public boolean hasEnabledElements() {
        for (Element element : ELEMENTS) {
            if (isElementEnabled(element.id)) return true;
        }
        return false;
    }

    public int elementScalePercent(@NonNull String id) {
        Integer value = elementScales.get(id);
        return value == null ? 100 : clamp(value, 70, 180);
    }

    public void setElementScalePercent(@NonNull String id, int percent) {
        if (isKnownElement(id)) elementScales.put(id, clamp(percent, 70, 180));
    }

    /** Physical tile width, independent of its icon/text scale. */
    public int elementWidthPercent(@NonNull String id) {
        Integer value = elementWidths.get(id);
        return value == null ? 100 : clamp(value, 55, 240);
    }

    public void setElementWidthPercent(@NonNull String id, int percent) {
        if (isKnownElement(id)) elementWidths.put(id, clamp(percent, 55, 240));
    }

    /** Physical tile height, independent of its icon/text scale. */
    public int elementHeightPercent(@NonNull String id) {
        Integer value = elementHeights.get(id);
        return value == null ? 100 : clamp(value, 65, 200);
    }

    public void setElementHeightPercent(@NonNull String id, int percent) {
        if (isKnownElement(id)) elementHeights.put(id, clamp(percent, 65, 200));
    }

    /** Moves one element in the global visual order; disabled elements retain their position. */
    public boolean moveElement(@NonNull String id, int direction) {
        int from = elementOrder.indexOf(id);
        if (from < 0 || direction == 0) return false;
        int to = Math.max(0, Math.min(elementOrder.size() - 1, from + direction));
        if (from == to) return false;
        elementOrder.remove(from);
        elementOrder.add(to, id);
        return true;
    }

    /** Moves an element to an absolute slot. Used by the visual drag-and-drop editor. */
    public boolean moveElementTo(@NonNull String id, int targetIndex) {
        int from = elementOrder.indexOf(id);
        if (from < 0 || elementOrder.size() < 2) return false;
        int to = Math.max(0, Math.min(elementOrder.size() - 1, targetIndex));
        if (from == to) return false;
        elementOrder.remove(from);
        elementOrder.add(to, id);
        return true;
    }

    public void setElementOrder(@NonNull List<String> ids) {
        elementOrder.clear();
        elementOrder.addAll(ids);
        normalizeOrder();
    }

    @NonNull
    public List<String> elementOrder() {
        return Collections.unmodifiableList(new ArrayList<>(elementOrder));
    }

    @NonNull
    public List<Element> orderedElements() {
        ArrayList<Element> result = new ArrayList<>();
        for (String id : elementOrder) {
            Element element = elementById(id);
            if (element != null) result.add(element);
        }
        return Collections.unmodifiableList(result);
    }

    @NonNull
    public Map<String, Boolean> elementSelections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    @NonNull
    public Map<String, Integer> elementScales() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elementScales));
    }

    @NonNull
    public Map<String, Integer> elementWidths() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elementWidths));
    }

    @NonNull
    public Map<String, Integer> elementHeights() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elementHeights));
    }

    @NonNull
    public ClimatePanelConfig copy() {
        ClimatePanelConfig value = new ClimatePanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.tileCornerRadiusPx = tileCornerRadiusPx;
        value.tileSpacingPx = tileSpacingPx;
        value.activeTileAlpha = activeTileAlpha;
        value.inactiveTileAlpha = inactiveTileAlpha;
        value.accentColor = accentColor;
        value.inactiveColor = inactiveColor;
        value.textColor = textColor;
        value.scalePercent = scalePercent;
        value.showTitle = showTitle;
        value.useVehicleStateColors = useVehicleStateColors;
        value.elements.clear();
        value.elements.putAll(elements);
        value.elementScales.clear();
        value.elementScales.putAll(elementScales);
        value.elementWidths.clear();
        value.elementWidths.putAll(elementWidths);
        value.elementHeights.clear();
        value.elementHeights.putAll(elementHeights);
        value.elementOrder.clear();
        value.elementOrder.addAll(elementOrder);
        return value;
    }

    public void normalize() {
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 96);
        tileCornerRadiusPx = clamp(tileCornerRadiusPx, 4, 64);
        tileSpacingPx = clamp(tileSpacingPx, 0, 40);
        activeTileAlpha = clamp(activeTileAlpha, 24, 255);
        inactiveTileAlpha = clamp(inactiveTileAlpha, 0, 220);
        scalePercent = clamp(scalePercent, 60, 160);
        if (!isHexColor(backgroundColor)) backgroundColor = "#171820";
        if (!isHexColor(accentColor)) accentColor = "#59A9FF";
        if (!isHexColor(inactiveColor)) inactiveColor = "#E4E5EA";
        if (!isHexColor(textColor)) textColor = "#FFFFFF";
        for (Element element : ELEMENTS) {
            if (!elements.containsKey(element.id)) elements.put(element.id, true);
            if (!elementScales.containsKey(element.id)) elementScales.put(element.id, 100);
            if (!elementWidths.containsKey(element.id)) elementWidths.put(element.id, 100);
            if (!elementHeights.containsKey(element.id)) elementHeights.put(element.id, 100);
            elementScales.put(element.id, clamp(elementScales.get(element.id), 70, 180));
            elementWidths.put(element.id, clamp(elementWidths.get(element.id), 55, 240));
            elementHeights.put(element.id, clamp(elementHeights.get(element.id), 65, 200));
        }
        normalizeOrder();
    }

    private void normalizeOrder() {
        Set<String> seen = new HashSet<>();
        ArrayList<String> valid = new ArrayList<>();
        for (String id : elementOrder) {
            if (isKnownElement(id) && seen.add(id)) valid.add(id);
        }
        for (Element element : ELEMENTS) if (seen.add(element.id)) valid.add(element.id);
        elementOrder.clear();
        elementOrder.addAll(valid);
    }

    @androidx.annotation.Nullable
    private static Element elementById(@NonNull String id) {
        for (Element element : ELEMENTS) if (element.id.equals(id)) return element;
        return null;
    }

    private static boolean isKnownElement(@NonNull String id) {
        for (Element element : ELEMENTS) if (element.id.equals(id)) return true;
        return false;
    }

    private static boolean isHexColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
