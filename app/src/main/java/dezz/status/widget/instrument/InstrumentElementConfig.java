/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Mutable editor model for one dashboard element. */
public final class InstrumentElementConfig {
    @NonNull public String id;
    @NonNull public InstrumentElementType type;
    @NonNull public InstrumentStyleFamily style;
    public int x;
    public int y;
    public int width;
    public int height;
    public int zIndex;
    public boolean enabled = true;
    public int responseMillis = 65;
    public int opacityPercent = 100;
    @NonNull public JSONObject options = new JSONObject();

    public InstrumentElementConfig(@NonNull String id, @NonNull InstrumentElementType type,
                                   @NonNull InstrumentStyleFamily style) {
        this.id = safeId(id);
        this.type = type;
        this.style = style;
        width = type.defaultWidth;
        height = type.defaultHeight;
    }

    @NonNull
    public InstrumentElementConfig copy() {
        InstrumentElementConfig value = new InstrumentElementConfig(id, type, style);
        value.x = x;
        value.y = y;
        value.width = width;
        value.height = height;
        value.zIndex = zIndex;
        value.enabled = enabled;
        value.responseMillis = responseMillis;
        value.opacityPercent = opacityPercent;
        try {
            value.options = new JSONObject(options.toString());
        } catch (JSONException ignored) {
            value.options = new JSONObject();
        }
        return value;
    }

    public void normalize(int columns, int rows) {
        id = safeId(id);
        width = clamp(width, 2, columns);
        height = clamp(height, 2, rows);
        x = clamp(x, 0, Math.max(0, columns - width));
        y = clamp(y, 0, Math.max(0, rows - height));
        zIndex = clamp(zIndex, -10_000, 10_000);
        responseMillis = clamp(responseMillis, 0, 500);
        opacityPercent = clamp(opacityPercent, 10, 100);
        if (options == null || options.toString().length() > 32_768) options = new JSONObject();
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("type", type.name())
                .put("style", style.name())
                .put("x", x).put("y", y)
                .put("width", width).put("height", height)
                .put("zIndex", zIndex)
                .put("enabled", enabled)
                .put("responseMillis", responseMillis)
                .put("opacityPercent", opacityPercent)
                .put("options", options);
    }

    @NonNull
    public static InstrumentElementConfig fromJson(@NonNull JSONObject json,
                                                   int columns, int rows) {
        InstrumentElementType type = InstrumentElementType.fromName(json.optString("type"));
        if (type == null) type = InstrumentElementType.DIGITAL_SPEEDOMETER;
        InstrumentElementConfig value = new InstrumentElementConfig(
                json.optString("id", "instrument_" + type.name().toLowerCase()), type,
                InstrumentStyleFamily.fromName(json.optString("style")));
        value.x = json.optInt("x", 0);
        value.y = json.optInt("y", 0);
        value.width = json.optInt("width", type.defaultWidth);
        value.height = json.optInt("height", type.defaultHeight);
        value.zIndex = json.optInt("zIndex", 0);
        value.enabled = json.optBoolean("enabled", true);
        value.responseMillis = json.optInt("responseMillis", 65);
        value.opacityPercent = json.optInt("opacityPercent", 100);
        JSONObject options = json.optJSONObject("options");
        value.options = options == null ? new JSONObject() : options;
        value.normalize(columns, rows);
        return value;
    }

    @NonNull
    private static String safeId(@NonNull String raw) {
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "instrument_element" : cleaned.substring(
                0, Math.min(96, cleaned.length()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
