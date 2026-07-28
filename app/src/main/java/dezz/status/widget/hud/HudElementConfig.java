/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.integration.SourceBinding;

/** Mutable editor model for one independently addressable HUD element. */
public final class HudElementConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_FORMAT_CHARS = 256;
    public static final int MAX_OPTIONS_CHARS = 32_768;

    @NonNull public String id;
    @NonNull public HudElementType type;
    @NonNull public String title;
    public int x;
    public int y;
    public int width;
    public int height;
    public int zIndex;
    public boolean enabled = true;
    @NonNull public String automationId;
    @Nullable public SourceBinding sourceBinding;
    @NonNull public String telemetryMetricId = "";
    @NonNull public String textFormat = "%s";
    @NonNull public String unit = "";
    @NonNull public String textColor = "#FFFFFFFF";
    @NonNull public String unitColor = "#CCFFFFFF";
    @NonNull public String backgroundColor = "#00000000";
    /** Decorative settings are used only by {@link HudElementType#BACKDROP}. */
    public int backgroundOpacityPercent = 72;
    public int cornerRadiusPx = 18;
    @NonNull public String borderColor = "#FFFFFFFF";
    public int borderOpacityPercent;
    public int borderWidthPx;
    public int fontSizeSp = 34;
    public int fontWeight = 600;
    /** LEFT, CENTER or RIGHT. */
    @NonNull public String alignment = "CENTER";
    public boolean wrapText;
    public int brightness = 100;
    /** Forward-compatible type-specific options retained by import/export. */
    @NonNull public JSONObject options = new JSONObject();

    public HudElementConfig(@NonNull String id, @NonNull HudElementType type) {
        this.id = AutomationContract.requireSafeId(id);
        this.type = type;
        title = type.label;
        automationId = id;
        telemetryMetricId = type.defaultMetricId;
        width = type.defaultWidth;
        height = type.defaultHeight;
    }

    @NonNull
    public static HudElementConfig create(@NonNull HudElementType type, int ordinal,
                                          int gridColumns, int gridRows) {
        String base = "hud_" + type.name().toLowerCase(java.util.Locale.ROOT);
        String id = ordinal <= 1 ? base : base + "_" + ordinal;
        HudElementConfig result = new HudElementConfig(id, type);
        result.x = Math.max(0, Math.min(gridColumns - result.width,
                ((ordinal - 1) * 3) % Math.max(1, gridColumns)));
        result.y = Math.max(0, Math.min(gridRows - result.height,
                ((ordinal - 1) * 2) % Math.max(1, gridRows)));
        result.applyTypeDefaults();
        result.normalize(gridColumns, gridRows);
        return result;
    }

    public void applyTypeDefaults() {
        try {
            switch (type) {
                case BACKDROP:
                    backgroundColor = "#FF121923";
                    backgroundOpacityPercent = 72;
                    cornerRadiusPx = 18;
                    borderColor = "#FFFFFFFF";
                    borderOpacityPercent = 0;
                    borderWidthPx = 0;
                    break;
                case HORIZONTAL_GROUP:
                    options.put("memberIds", new org.json.JSONArray());
                    options.put("gapPx", 0);
                    options.put("paddingLeftPx", 0);
                    options.put("paddingTopPx", 0);
                    options.put("paddingRightPx", 0);
                    options.put("paddingBottomPx", 0);
                    options.put("marginLeftPx", 0);
                    options.put("marginTopPx", 0);
                    options.put("marginRightPx", 0);
                    options.put("marginBottomPx", 0);
                    options.put("horizontalAlignment", 0);
                    options.put("verticalAlignment", 1);
                    options.put("distribution", 0);
                    break;
                case CLOCK:
                    options.put("clockMode", "SYSTEM");
                    break;
                case NAV_MANEUVER_ARROW:
                case NAV_COMBINED:
                    options.put("arrowAnimation", true);
                    options.put("arrowLayout", "LEFT");
                    options.put("hideWhenInactive", true);
                    break;
                case NAV_LANES:
                    options.put("laneDistancePosition", "BOTTOM");
                    options.put("laneThresholdMeters", 700);
                    options.put("hideWhenInactive", true);
                    break;
                case NAV_SPEED_LIMIT:
                    options.put("whiteSign", true);
                    options.put("routeOnly", false);
                    options.put("overspeedDelta", 10);
                    options.put("overspeedBlink", true);
                    break;
                case NAV_TRAFFIC_LIGHTS:
                    options.put("style", "CAPSULE");
                    options.put("orientation", "VERTICAL");
                    options.put("countdownSide", "BOTTOM");
                    options.put("showFrame", true);
                    options.put("arrowAnimation", true);
                    break;
                case NAV_TRIP_PROGRESS:
                    options.put("progressMode", "COMBINED");
                    options.put("orientation", "HORIZONTAL");
                    break;
                case NAV_JAM_PROGRESS:
                    options.put("orientation", "HORIZONTAL");
                    break;
                case TURN_SIGNAL_LEFT:
                case TURN_SIGNAL_RIGHT:
                    options.put("animated", true);
                    options.put("blinkFrequencyMs", 500);
                    options.put("graphicStyle", "CHEVRON");
                    options.put("hideWhenInactive", true);
                    break;
                case GEAR:
                    options.put("gearMode", "FULL");
                    options.put("letterOnly", false);
                    options.put("numberOnly", false);
                    options.put("letterColor", "#FFFFFFFF");
                    options.put("numberColor", "#FFFFFFFF");
                    options.put("hideDelaySeconds", 0);
                    break;
                case FUEL_LEVEL:
                case FUEL_RANGE:
                    options.put("yellowThreshold", 20d);
                    options.put("redThreshold", 10d);
                    options.put("hideAboveThreshold", false);
                    break;
                case FUEL_REFILL:
                    options.put("tankCapacityLitres", 64d);
                    options.put("automaticCapacity", true);
                    options.put("onlyInPark", true);
                    break;
                case TIRE_PRESSURE_FRONT_LEFT:
                case TIRE_PRESSURE_FRONT_RIGHT:
                case TIRE_PRESSURE_REAR_LEFT:
                case TIRE_PRESSURE_REAR_RIGHT:
                    options.put("lowThreshold", 2.0d);
                    options.put("blinkBelowThreshold", true);
                    options.put("hideAboveThreshold", false);
                    break;
                default:
                    break;
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void normalize(int gridColumns, int gridRows) {
        id = AutomationContract.requireSafeId(id);
        automationId = AutomationContract.requireSafeId(
                automationId == null || automationId.trim().isEmpty() ? id : automationId);
        title = bounded(title, MAX_TITLE_CHARS, type.label);
        telemetryMetricId = bounded(telemetryMetricId, 256, "");
        textFormat = bounded(textFormat, MAX_FORMAT_CHARS, "%s");
        unit = bounded(unit, 64, "");
        textColor = bounded(textColor, 32, "#FFFFFFFF");
        unitColor = bounded(unitColor, 32, "#CCFFFFFF");
        if (type == HudElementType.BACKDROP) {
            backgroundColor = bounded(backgroundColor, 32, "#FF121923");
            backgroundOpacityPercent = clamp(backgroundOpacityPercent, 0, 100);
            cornerRadiusPx = clamp(cornerRadiusPx, 0, 500);
            borderColor = bounded(borderColor, 32, "#FFFFFFFF");
            borderOpacityPercent = clamp(borderOpacityPercent, 0, 100);
            borderWidthPx = clamp(borderWidthPx, 0, 100);
        } else {
            // A widget frame is only geometry. Decorative surfaces are independent BACKDROP
            // elements and can never be coupled to the widget's content.
            backgroundColor = "#00000000";
            backgroundOpacityPercent = 0;
            cornerRadiusPx = 0;
            borderColor = "#00000000";
            borderOpacityPercent = 0;
            borderWidthPx = 0;
        }
        alignment = normalizeAlignment(alignment);
        gridColumns = clamp(gridColumns, 4, 200);
        gridRows = clamp(gridRows, 2, 100);
        width = clamp(width, 1, gridColumns);
        height = clamp(height, 1, gridRows);
        x = clamp(x, 0, Math.max(0, gridColumns - width));
        y = clamp(y, 0, Math.max(0, gridRows - height));
        zIndex = clamp(zIndex, -10_000, 10_000);
        fontSizeSp = clamp(fontSizeSp, 8, 240);
        fontWeight = clamp(fontWeight, 100, 900);
        brightness = clamp(brightness, 0, 100);
        if (options == null || options.toString().length() > MAX_OPTIONS_CHARS) {
            options = new JSONObject();
        }
        if (type == HudElementType.HORIZONTAL_GROUP) {
            HudHorizontalGroup.normalizeOptions(this);
        }
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("schema", SCHEMA_VERSION);
        out.put("id", id);
        out.put("type", type.name());
        out.put("title", title);
        out.put("x", x).put("y", y).put("width", width).put("height", height);
        out.put("zIndex", zIndex).put("enabled", enabled);
        out.put("automationId", automationId);
        if (sourceBinding != null && sourceBinding.isBound()) {
            out.put("sourceBinding", sourceBinding.toJson());
        }
        out.put("telemetryMetricId", telemetryMetricId);
        out.put("textFormat", textFormat).put("unit", unit);
        out.put("textColor", textColor).put("unitColor", unitColor);
        out.put("backgroundColor", backgroundColor);
        out.put("backgroundOpacityPercent", backgroundOpacityPercent);
        out.put("cornerRadiusPx", cornerRadiusPx);
        out.put("borderColor", borderColor);
        out.put("borderOpacityPercent", borderOpacityPercent);
        out.put("borderWidthPx", borderWidthPx);
        out.put("fontSizeSp", fontSizeSp).put("fontWeight", fontWeight);
        out.put("alignment", alignment).put("wrapText", wrapText);
        out.put("brightness", brightness);
        out.put("options", new JSONObject(options.toString()));
        return out;
    }

    @NonNull
    public static HudElementConfig fromJson(@NonNull JSONObject source,
                                            int gridColumns, int gridRows) {
        HudElementType type = HudElementType.fromName(source.optString("type", ""));
        if (type == null) throw new IllegalArgumentException("Unknown HUD element type");
        HudElementConfig out = new HudElementConfig(source.optString("id", ""), type);
        out.title = source.optString("title", type.label);
        out.x = source.optInt("x", 0);
        out.y = source.optInt("y", 0);
        out.width = source.optInt("width", type.defaultWidth);
        out.height = source.optInt("height", type.defaultHeight);
        out.zIndex = source.optInt("zIndex", 0);
        out.enabled = source.optBoolean("enabled", true);
        out.automationId = source.optString("automationId", out.id);
        JSONObject binding = source.optJSONObject("sourceBinding");
        if (binding != null) out.sourceBinding = SourceBinding.fromJson(binding);
        out.telemetryMetricId = source.optString("telemetryMetricId", type.defaultMetricId);
        out.textFormat = source.optString("textFormat", "%s");
        out.unit = source.optString("unit", "");
        out.textColor = source.optString("textColor", "#FFFFFFFF");
        out.unitColor = source.optString("unitColor", "#CCFFFFFF");
        out.backgroundColor = source.optString("backgroundColor", "#00000000");
        out.backgroundOpacityPercent = source.optInt("backgroundOpacityPercent", 72);
        out.cornerRadiusPx = source.optInt("cornerRadiusPx", 18);
        out.borderColor = source.optString("borderColor", "#FFFFFFFF");
        out.borderOpacityPercent = source.optInt("borderOpacityPercent", 0);
        out.borderWidthPx = source.optInt("borderWidthPx", 0);
        out.fontSizeSp = source.optInt("fontSizeSp", 34);
        out.fontWeight = source.optInt("fontWeight", 600);
        out.alignment = source.optString("alignment", "CENTER");
        out.wrapText = source.optBoolean("wrapText", false);
        out.brightness = source.optInt("brightness", 100);
        JSONObject options = source.optJSONObject("options");
        if (options != null && options.toString().length() <= MAX_OPTIONS_CHARS) {
            try {
                out.options = new JSONObject(options.toString());
            } catch (JSONException ignored) {
                out.options = new JSONObject();
            }
        }
        out.normalize(gridColumns, gridRows);
        return out;
    }

    @NonNull
    public HudElementConfig copy() {
        try {
            return fromJson(toJson(), 200, 100);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @NonNull
    private static String normalizeAlignment(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return "LEFT".equals(value) || "RIGHT".equals(value) ? value : "CENTER";
    }

    @NonNull
    private static String bounded(@Nullable String raw, int maximum,
                                  @NonNull String fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.indexOf('\u0000') >= 0 || value.length() > maximum) return fallback;
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
