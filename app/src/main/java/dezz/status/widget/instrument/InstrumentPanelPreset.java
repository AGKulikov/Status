/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.util.Locale;

/** The five approved starting compositions; every created item remains an ordinary module. */
public enum InstrumentPanelPreset {
    SLATE_HORIZON("11", "Slate Horizon · аналоговый", InstrumentStyleFamily.SLATE_HORIZON),
    GLACIER_MAP("12", "Glacier Map · цифровой", InstrumentStyleFamily.GLACIER_MAP),
    AEROWAVE("13", "Aerowave · гибридный", InstrumentStyleFamily.AEROWAVE),
    STEEL_VECTOR("14", "Steel Vector · цифровой", InstrumentStyleFamily.STEEL_VECTOR),
    CONTINUUM("15", "Continuum · флагманский", InstrumentStyleFamily.CONTINUUM);

    @NonNull public final String id;
    @NonNull public final String label;
    @NonNull public final InstrumentStyleFamily style;

    InstrumentPanelPreset(@NonNull String id, @NonNull String label,
                          @NonNull InstrumentStyleFamily style) {
        this.id = id;
        this.label = label;
        this.style = style;
    }

    @NonNull
    public InstrumentPanelConfig create() {
        InstrumentPanelConfig config = new InstrumentPanelConfig();
        config.presetId = id;
        config.defaultStyle = style;
        config.backgroundBottomColor = defaultBottomColor();
        config.blackZonePercent = 46;
        switch (this) {
            case SLATE_HORIZON:
                add(config, analog("tachometer", InstrumentElementType.ANALOG_TACHOMETER,
                        1, 2, 13, 13, 4, style, true));
                add(config, map("map", 14, 1, 20, 16, style));
                add(config, analog("speedometer", InstrumentElementType.ANALOG_SPEEDOMETER,
                        34, 2, 13, 13, 4, style, true));
                add(config, navigation("navigation", 15, 2, 10, 6, 8, style));
                add(config, info("information", 2, 5, 11, 8, 9, style, false));
                break;
            case GLACIER_MAP:
                add(config, map("map", 8, 1, 32, 16, style));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        2, 5, 10, 7, 5, style, false));
                add(config, digital("tachometer", InstrumentElementType.DIGITAL_TACHOMETER,
                        2, 12, 10, 3, 5, style, false));
                add(config, navigation("navigation", 36, 3, 11, 7, 8, style));
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            case AEROWAVE:
                add(config, analog("tachometer", InstrumentElementType.ANALOG_TACHOMETER,
                        1, 3, 12, 12, 4, style, true));
                add(config, map("map", 12, 1, 24, 16, style));
                add(config, analog("speedometer", InstrumentElementType.ANALOG_SPEEDOMETER,
                        35, 3, 12, 12, 4, style, true));
                add(config, navigation("navigation", 17, 2, 14, 5, 8, style));
                add(config, info("information", 36, 5, 10, 8, 9, style, false));
                break;
            case STEEL_VECTOR:
                add(config, map("map", 13, 1, 22, 16, style));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        2, 5, 10, 7, 5, style, false));
                add(config, digital("tachometer", InstrumentElementType.DIGITAL_TACHOMETER,
                        10, 5, 4, 9, 5, style, false));
                add(config, navigation("navigation", 35, 4, 12, 7, 8, style));
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            case CONTINUUM:
                add(config, map("map", 12, 1, 24, 16, style));
                add(config, analog("tachometer", InstrumentElementType.ANALOG_TACHOMETER,
                        1, 2, 10, 13, 4, style, true));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        2, 5, 10, 7, 7, style, false));
                add(config, navigation("navigation", 35, 4, 12, 7, 8, style));
                // It shares the left zone deliberately: disable speed and enable this module to
                // reproduce the user-requested information-first Continuum composition.
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            default:
                break;
        }
        config.normalize();
        return config;
    }

    @NonNull
    public String defaultBottomColor() {
        switch (this) {
            case GLACIER_MAP: return "#FF19334A";
            case AEROWAVE: return "#FF1C2C41";
            case STEEL_VECTOR: return "#FF203246";
            case CONTINUUM: return "#FF152A3F";
            case SLATE_HORIZON:
            default: return "#FF1A2C40";
        }
    }

    @NonNull
    public static InstrumentPanelPreset fromId(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (InstrumentPanelPreset preset : values()) {
            if (preset.id.equals(value) || preset.name().equals(value)) return preset;
        }
        return SLATE_HORIZON;
    }

    private static void add(InstrumentPanelConfig config, InstrumentElementConfig element) {
        config.elements.add(element);
    }

    private static InstrumentElementConfig map(String id, int x, int y, int width, int height,
                                               InstrumentStyleFamily style) {
        return element(id, InstrumentElementType.NAV_MAP, x, y, width, height, 0, style, true);
    }

    private static InstrumentElementConfig analog(String id, InstrumentElementType type,
                                                  int x, int y, int width, int height, int z,
                                                  InstrumentStyleFamily style,
                                                  boolean showValue) {
        InstrumentElementConfig result = element(id, type, x, y, width, height, z, style, true);
        option(result, "showFace", true);
        option(result, "showScale", true);
        option(result, "showScaleLabels", true);
        option(result, "showNeedle", true);
        option(result, "showValue", showValue);
        option(result, "showUnit", true);
        return result;
    }

    private static InstrumentElementConfig digital(String id, InstrumentElementType type,
                                                   int x, int y, int width, int height, int z,
                                                   InstrumentStyleFamily style,
                                                   boolean showFace) {
        InstrumentElementConfig result = element(id, type, x, y, width, height, z, style, true);
        option(result, "showFace", showFace);
        option(result, "showUnit", true);
        option(result, "showProgress", true);
        return result;
    }

    private static InstrumentElementConfig navigation(String id, int x, int y,
                                                      int width, int height, int z,
                                                      InstrumentStyleFamily style) {
        InstrumentElementConfig result = element(id, InstrumentElementType.NAVIGATION_INFO,
                x, y, width, height, z, style, true);
        option(result, "showFace", false);
        option(result, "showStreet", true);
        option(result, "showArrival", true);
        return result;
    }

    private static InstrumentElementConfig info(String id, int x, int y,
                                                int width, int height, int z,
                                                InstrumentStyleFamily style, boolean enabled) {
        InstrumentElementConfig result = element(id, InstrumentElementType.INFO_BLOCK,
                x, y, width, height, z, style, enabled);
        option(result, "showFace", false);
        option(result, "row1", InstrumentInfoMetric.RANGE.name());
        option(result, "row2", InstrumentInfoMetric.AVERAGE_CONSUMPTION.name());
        option(result, "row3", InstrumentInfoMetric.AMBIENT_TEMPERATURE.name());
        return result;
    }

    private static InstrumentElementConfig element(String id, InstrumentElementType type,
                                                   int x, int y, int width, int height, int z,
                                                   InstrumentStyleFamily style, boolean enabled) {
        InstrumentElementConfig result = new InstrumentElementConfig(id, type, style);
        result.x = x;
        result.y = y;
        result.width = width;
        result.height = height;
        result.zIndex = z;
        result.enabled = enabled;
        return result;
    }

    private static void option(InstrumentElementConfig element, String key, Object value) {
        try {
            element.options.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
