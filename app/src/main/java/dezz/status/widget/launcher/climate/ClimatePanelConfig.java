/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @NonNull public String backgroundColor = "#141A24";
    public int backgroundAlpha = 218;
    public int cornerRadiusPx = 30;
    @NonNull public String accentColor = "#35B7FF";
    @NonNull public String inactiveColor = "#B7C1CE";
    @NonNull public String textColor = "#FFFFFF";
    public int scalePercent = 100;
    public boolean showTitle = true;
    public boolean useVehicleStateColors = true;
    private final LinkedHashMap<String, Boolean> elements = new LinkedHashMap<>();

    public ClimatePanelConfig() {
        for (Element element : ELEMENTS) elements.put(element.id, true);
    }

    public boolean isElementEnabled(@NonNull String id) {
        Boolean value = elements.get(id);
        return value == null || value;
    }

    public void setElementEnabled(@NonNull String id, boolean enabled) {
        if (isKnownElement(id)) elements.put(id, enabled);
    }

    @NonNull
    public Map<String, Boolean> elementSelections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    @NonNull
    public ClimatePanelConfig copy() {
        ClimatePanelConfig value = new ClimatePanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.accentColor = accentColor;
        value.inactiveColor = inactiveColor;
        value.textColor = textColor;
        value.scalePercent = scalePercent;
        value.showTitle = showTitle;
        value.useVehicleStateColors = useVehicleStateColors;
        value.elements.clear();
        value.elements.putAll(elements);
        return value;
    }

    public void normalize() {
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 96);
        scalePercent = clamp(scalePercent, 60, 160);
        if (!isHexColor(backgroundColor)) backgroundColor = "#141A24";
        if (!isHexColor(accentColor)) accentColor = "#35B7FF";
        if (!isHexColor(inactiveColor)) inactiveColor = "#B7C1CE";
        if (!isHexColor(textColor)) textColor = "#FFFFFF";
        for (Element element : ELEMENTS) {
            if (!elements.containsKey(element.id)) elements.put(element.id, true);
        }
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
