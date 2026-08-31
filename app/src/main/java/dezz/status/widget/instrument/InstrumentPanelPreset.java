/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** The five approved starting compositions; every created item remains an ordinary module. */
public enum InstrumentPanelPreset {
    SLATE_HORIZON("11", "Slate Horizon · аналоговый", InstrumentStyleFamily.SLATE_HORIZON),
    GLACIER_MAP("12", "Glacier Map · цифровой", InstrumentStyleFamily.GLACIER_MAP),
    AEROWAVE("13", "Aerowave · гибридный", InstrumentStyleFamily.AEROWAVE),
    STEEL_VECTOR("14", "Steel Vector · цифровой", InstrumentStyleFamily.STEEL_VECTOR),
    CONTINUUM("15", "Continuum · флагманский", InstrumentStyleFamily.CONTINUUM);

    /** Revision 2 replaces the first generic approximation with the approved 11–15 geometry. */
    public static final int LAYOUT_REVISION = 2;

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
        config.presetLayoutRevision = LAYOUT_REVISION;
        config.defaultStyle = style;
        config.backgroundBottomColor = defaultBottomColor();
        config.blackZonePercent = 46;
        switch (this) {
            case SLATE_HORIZON:
                add(config, analog("tachometer", InstrumentElementType.ANALOG_TACHOMETER,
                        1, 2, 13, 12, 4, style, false));
                add(config, map("map", 6, 1, 36, 16, style));
                add(config, analog("speedometer", InstrumentElementType.ANALOG_SPEEDOMETER,
                        34, 2, 13, 12, 4, style, true));
                add(config, navigation("navigation", 15, 2, 9, 6, 8, style));
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            case GLACIER_MAP:
                add(config, map("map", 1, 1, 44, 16, style));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        2, 4, 10, 7, 5, style, false));
                add(config, ruler("tachometer", InstrumentElementType.DIGITAL_TACHOMETER,
                        2, 9, 11, 4, 6, style, "HORIZONTAL_RULER"));
                add(config, navigation("navigation", 34, 3, 12, 7, 8, style));
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            case AEROWAVE:
                add(config, arcAnalog("tachometer", InstrumentElementType.ANALOG_TACHOMETER,
                        1, 3, 14, 12, 4, style, true, 135d, 180d));
                add(config, map("map", 5, 1, 38, 16, style));
                add(config, arcAnalog("speedometer", InstrumentElementType.ANALOG_SPEEDOMETER,
                        33, 3, 14, 12, 4, style, true, 225d, 180d));
                add(config, navigation("navigation", 17, 2, 14, 5, 8, style));
                add(config, info("information", 35, 5, 11, 8, 9, style, false));
                break;
            case STEEL_VECTOR:
                add(config, map("map", 14, 1, 18, 16, style));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        2, 5, 10, 7, 5, style, false));
                add(config, ruler("tachometer", InstrumentElementType.DIGITAL_TACHOMETER,
                        12, 4, 5, 10, 6, style, "VERTICAL_RULER"));
                add(config, navigation("navigation", 34, 3, 12, 8, 8, style));
                add(config, info("information", 2, 4, 11, 9, 9, style, false));
                break;
            case CONTINUUM:
                add(config, map("map", 8, 1, 34, 16, style));
                add(config, continuumTachometer("tachometer", 1, 3, 13, 12, 4, style));
                add(config, digital("speedometer", InstrumentElementType.DIGITAL_SPEEDOMETER,
                        3, 5, 10, 7, 7, style, false));
                add(config, navigation("navigation", 34, 4, 12, 7, 8, style));
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

    /**
     * Upgrades the generic 2.5.7 approximation once while preserving module visibility and every
     * user-added element. Geometry and preset-owned presentation keys follow the approved sheet.
     */
    static void upgradeLegacyLayout(@NonNull InstrumentPanelConfig config) {
        if (config.presetLayoutRevision >= LAYOUT_REVISION) return;
        InstrumentPanelConfig approved = fromId(config.presetId).create();
        Set<String> consumed = new HashSet<>();
        for (InstrumentElementConfig fresh : approved.elements) {
            InstrumentElementConfig old = find(config, fresh.id);
            if (old == null) continue;
            consumed.add(old.id);
            fresh.enabled = old.enabled;
            fresh.responseMillis = old.responseMillis;
            fresh.opacityPercent = old.opacityPercent;
            fresh.style = old.style;
            preserveUserGeometry(config.presetId, old, fresh);
            mergeUserOptions(old, fresh, approved.presetId);
        }
        for (InstrumentElementConfig old : config.elements) {
            if (!consumed.contains(old.id)) approved.elements.add(old.copy());
        }
        config.elements.clear();
        config.elements.addAll(approved.elements);
        config.defaultStyle = approved.defaultStyle;
        config.presetLayoutRevision = LAYOUT_REVISION;
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
        InstrumentElementConfig result = element(
                id, InstrumentElementType.NAV_MAP, x, y, width, height, 0, style, true);
        option(result, "fadeEdges", true);
        option(result, "fadePercent", 16);
        return result;
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

    private static InstrumentElementConfig ruler(String id, InstrumentElementType type,
                                                  int x, int y, int width, int height, int z,
                                                  InstrumentStyleFamily style,
                                                  String presentation) {
        InstrumentElementConfig result = digital(id, type, x, y, width, height, z, style, false);
        option(result, "presentation", presentation);
        option(result, "showProgress", false);
        option(result, "showUnit", false);
        return result;
    }

    private static InstrumentElementConfig arcAnalog(
            String id, InstrumentElementType type, int x, int y, int width, int height, int z,
            InstrumentStyleFamily style, boolean showValue, double start, double sweep) {
        InstrumentElementConfig result = analog(
                id, type, x, y, width, height, z, style, showValue);
        option(result, "showFace", false);
        // The open-arc faces encode the value in their illuminated arc. A radial needle belonged
        // to the retired generic approximation and visibly changes the approved silhouette.
        option(result, "showNeedle", false);
        option(result, "arcStartDegrees", start);
        option(result, "arcSweepDegrees", sweep);
        return result;
    }

    private static InstrumentElementConfig continuumTachometer(
            String id, int x, int y, int width, int height, int z,
            InstrumentStyleFamily style) {
        InstrumentElementConfig result = arcAnalog(id, InstrumentElementType.ANALOG_TACHOMETER,
                x, y, width, height, z, style, false, 145d, 220d);
        option(result, "showNeedle", false);
        option(result, "showUnit", false);
        return result;
    }

    private static InstrumentElementConfig navigation(String id, int x, int y,
                                                      int width, int height, int z,
                                                      InstrumentStyleFamily style) {
        InstrumentElementConfig result = element(id, InstrumentElementType.NAVIGATION_INFO,
                x, y, width, height, z, style, true);
        option(result, "showFace", true);
        option(result, "showDistance", true);
        option(result, "showEta", true);
        option(result, "showDuration", true);
        option(result, "showRouteProgress", true);
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

    private static InstrumentElementConfig find(InstrumentPanelConfig config, String id) {
        for (InstrumentElementConfig element : config.elements) {
            if (id.equals(element.id)) return element;
        }
        return null;
    }

    private static void mergeUserOptions(InstrumentElementConfig old,
                                         InstrumentElementConfig fresh,
                                         String presetId) {
        Iterator<String> keys = old.options.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("presentation".equals(key) || "arcStartDegrees".equals(key)
                    || "arcSweepDegrees".equals(key) || "fadeEdges".equals(key)
                    || "fadePercent".equals(key)) continue;
            // These false values are part of the corrected preset silhouette, not optional art.
            if ("tachometer".equals(old.id)
                    && ("11".equals(presetId) || "15".equals(presetId))
                    && ("showValue".equals(key) || "showUnit".equals(key)
                    || "15".equals(presetId)
                    && ("showFace".equals(key) || "showNeedle".equals(key)))) continue;
            if ("13".equals(presetId) && "showNeedle".equals(key)) continue;
            Object value = old.options.opt(key);
            if (value != null && value != JSONObject.NULL) option(fresh, key, value);
        }
    }

    /** Replace only untouched 2.5.7 starter coordinates; real editor work must survive. */
    private static void preserveUserGeometry(String presetId,
                                             InstrumentElementConfig old,
                                             InstrumentElementConfig fresh) {
        fresh.zIndex = old.zIndex;
        int[] legacy = legacyGeometry(presetId, old.id);
        boolean untouchedStarter = legacy != null
                && old.x == legacy[0] && old.y == legacy[1]
                && old.width == legacy[2] && old.height == legacy[3];
        if (untouchedStarter) return;
        fresh.x = old.x;
        fresh.y = old.y;
        fresh.width = old.width;
        fresh.height = old.height;
    }

    @Nullable
    private static int[] legacyGeometry(String presetId, String elementId) {
        if ("11".equals(presetId)) {
            if ("tachometer".equals(elementId)) return box(1, 2, 13, 13);
            if ("map".equals(elementId)) return box(14, 1, 20, 16);
            if ("speedometer".equals(elementId)) return box(34, 2, 13, 13);
            if ("navigation".equals(elementId)) return box(15, 2, 10, 6);
            if ("information".equals(elementId)) return box(2, 5, 11, 8);
        } else if ("12".equals(presetId)) {
            if ("map".equals(elementId)) return box(8, 1, 32, 16);
            if ("speedometer".equals(elementId)) return box(2, 5, 10, 7);
            if ("tachometer".equals(elementId)) return box(2, 12, 10, 3);
            if ("navigation".equals(elementId)) return box(36, 3, 11, 7);
            if ("information".equals(elementId)) return box(2, 4, 11, 9);
        } else if ("13".equals(presetId)) {
            if ("tachometer".equals(elementId)) return box(1, 3, 12, 12);
            if ("map".equals(elementId)) return box(12, 1, 24, 16);
            if ("speedometer".equals(elementId)) return box(35, 3, 12, 12);
            if ("navigation".equals(elementId)) return box(17, 2, 14, 5);
            if ("information".equals(elementId)) return box(36, 5, 10, 8);
        } else if ("14".equals(presetId)) {
            if ("map".equals(elementId)) return box(13, 1, 22, 16);
            if ("speedometer".equals(elementId)) return box(2, 5, 10, 7);
            if ("tachometer".equals(elementId)) return box(10, 5, 4, 9);
            if ("navigation".equals(elementId)) return box(35, 4, 12, 7);
            if ("information".equals(elementId)) return box(2, 4, 11, 9);
        } else if ("15".equals(presetId)) {
            if ("map".equals(elementId)) return box(12, 1, 24, 16);
            if ("tachometer".equals(elementId)) return box(1, 2, 10, 13);
            if ("speedometer".equals(elementId)) return box(2, 5, 10, 7);
            if ("navigation".equals(elementId)) return box(35, 4, 12, 7);
            if ("information".equals(elementId)) return box(2, 4, 11, 9);
        }
        return null;
    }

    private static int[] box(int x, int y, int width, int height) {
        return new int[]{x, y, width, height};
    }
}
