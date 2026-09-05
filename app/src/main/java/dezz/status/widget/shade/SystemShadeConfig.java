/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Versioned, display-independent layout for Natro's replacement system shade. */
public final class SystemShadeConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int LOGICAL_WIDTH = 1760;
    public static final int LOGICAL_HEIGHT = 720;

    public enum Kind {
        CLOCK("clock", "Часы"),
        DATE("date", "Дата"),
        MEDIA("media", "Медиаплеер"),
        VOLUME("volume", "Громкость"),
        BRIGHTNESS("brightness", "Яркость"),
        ACTIONS("actions", "Кнопки и действия");

        @NonNull public final String id;
        @NonNull public final String title;

        Kind(@NonNull String id, @NonNull String title) {
            this.id = id;
            this.title = title;
        }

        @NonNull public static Kind fromId(String id) {
            for (Kind value : values()) if (value.id.equals(id)) return value;
            return CLOCK;
        }
    }

    public static final class Element {
        @NonNull public Kind kind;
        public int x;
        public int y;
        public int width;
        public int height;
        public boolean visible = true;
        public int textSizeSp = 24;
        public int iconSizePx = 42;
        public int paddingPx = 12;
        public int gapPx = 8;
        public int columns = 3;
        public int opacityPercent = 92;
        public int cornerRadiusPx = 20;
        @NonNull public String backgroundColor = "#CC171D26";
        @NonNull public String textColor = "#FFFFFFFF";
        @NonNull public String accentColor = "#FF1478FF";

        public Element(@NonNull Kind kind, int x, int y, int width, int height) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @NonNull public Element copy() {
            Element value = new Element(kind, x, y, width, height);
            value.visible = visible;
            value.textSizeSp = textSizeSp;
            value.iconSizePx = iconSizePx;
            value.paddingPx = paddingPx;
            value.gapPx = gapPx;
            value.columns = columns;
            value.opacityPercent = opacityPercent;
            value.cornerRadiusPx = cornerRadiusPx;
            value.backgroundColor = backgroundColor;
            value.textColor = textColor;
            value.accentColor = accentColor;
            return value;
        }
    }

    public int displayId = 0;
    public int panelHeightPx = 620;
    public int gestureHandleHeightPx = 34;
    public int openThresholdPx = 72;
    public int closeThresholdPx = 72;
    public int animationDurationMs = 220;
    public int editorSnapPx = 10;
    public int scrimOpacityPercent = 48;
    public int panelOpacityPercent = 98;
    public int panelCornerRadiusPx = 0;
    @NonNull public String panelColor = "#FF0A0D12";
    public boolean closeAfterAction;
    public boolean hapticFeedback = true;
    @NonNull public final List<Element> elements = new ArrayList<>();

    public SystemShadeConfig() {
        elements.add(element(Kind.BRIGHTNESS, 36, 54, 420, 92, 19));
        elements.add(element(Kind.VOLUME, 36, 158, 420, 92, 19));
        Element actions = element(Kind.ACTIONS, 486, 54, 570, 500, 17);
        actions.columns = 3;
        actions.iconSizePx = 44;
        elements.add(actions);
        Element clock = element(Kind.CLOCK, 1090, 54, 620, 150, 64);
        clock.backgroundColor = "#00000000";
        clock.textSizeSp = 64;
        elements.add(clock);
        Element date = element(Kind.DATE, 1090, 194, 620, 62, 21);
        date.backgroundColor = "#00000000";
        date.textSizeSp = 21;
        elements.add(date);
        Element media = element(Kind.MEDIA, 1090, 278, 620, 276, 22);
        media.iconSizePx = 38;
        elements.add(media);
    }

    @NonNull
    private static Element element(@NonNull Kind kind, int x, int y, int width, int height,
                                   int textSizeSp) {
        Element value = new Element(kind, x, y, width, height);
        value.textSizeSp = textSizeSp;
        return value;
    }

    @NonNull public Element element(@NonNull Kind kind) {
        for (Element value : elements) if (value.kind == kind) return value;
        Element fallback = element(kind, 0, 0, 320, 100, 20);
        elements.add(fallback);
        return fallback;
    }

    @NonNull public List<Element> snapshot() {
        List<Element> result = new ArrayList<>();
        for (Element value : elements) result.add(value.copy());
        return Collections.unmodifiableList(result);
    }

    public void normalize() {
        displayId = clamp(displayId, 0, 32);
        panelHeightPx = clamp(panelHeightPx, 240, LOGICAL_HEIGHT);
        gestureHandleHeightPx = clamp(gestureHandleHeightPx, 12, 96);
        openThresholdPx = clamp(openThresholdPx, 24, 260);
        closeThresholdPx = clamp(closeThresholdPx, 24, 260);
        animationDurationMs = clamp(animationDurationMs, 80, 600);
        editorSnapPx = clamp(editorSnapPx, 1, 80);
        scrimOpacityPercent = clamp(scrimOpacityPercent, 0, 90);
        panelOpacityPercent = clamp(panelOpacityPercent, 20, 100);
        panelCornerRadiusPx = clamp(panelCornerRadiusPx, 0, 120);
        panelColor = color(panelColor, "#FF0A0D12");
        for (Kind kind : Kind.values()) element(kind);
        for (Element value : elements) normalize(value, panelHeightPx);
    }

    private static void normalize(@NonNull Element value, int panelHeight) {
        value.width = clamp(value.width, 80, LOGICAL_WIDTH);
        value.height = clamp(value.height, 48, panelHeight);
        value.x = clamp(value.x, 0, Math.max(0, LOGICAL_WIDTH - value.width));
        value.y = clamp(value.y, 0, Math.max(0, panelHeight - value.height));
        value.textSizeSp = clamp(value.textSizeSp, 10, 120);
        value.iconSizePx = clamp(value.iconSizePx, 16, 180);
        value.paddingPx = clamp(value.paddingPx, 0, 100);
        value.gapPx = clamp(value.gapPx, 0, 80);
        value.columns = clamp(value.columns, 1, 8);
        value.opacityPercent = clamp(value.opacityPercent, 0, 100);
        value.cornerRadiusPx = clamp(value.cornerRadiusPx, 0, 120);
        value.backgroundColor = color(value.backgroundColor, "#CC171D26");
        value.textColor = color(value.textColor, "#FFFFFFFF");
        value.accentColor = color(value.accentColor, "#FF1478FF");
    }

    @NonNull public String toJson() {
        normalize();
        try {
            JSONObject root = new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("displayId", displayId)
                    .put("panelHeightPx", panelHeightPx)
                    .put("gestureHandleHeightPx", gestureHandleHeightPx)
                    .put("openThresholdPx", openThresholdPx)
                    .put("closeThresholdPx", closeThresholdPx)
                    .put("animationDurationMs", animationDurationMs)
                    .put("editorSnapPx", editorSnapPx)
                    .put("scrimOpacityPercent", scrimOpacityPercent)
                    .put("panelOpacityPercent", panelOpacityPercent)
                    .put("panelCornerRadiusPx", panelCornerRadiusPx)
                    .put("panelColor", panelColor)
                    .put("closeAfterAction", closeAfterAction)
                    .put("hapticFeedback", hapticFeedback);
            JSONArray values = new JSONArray();
            for (Element value : elements) {
                values.put(new JSONObject()
                        .put("kind", value.kind.id)
                        .put("x", value.x).put("y", value.y)
                        .put("width", value.width).put("height", value.height)
                        .put("visible", value.visible)
                        .put("textSizeSp", value.textSizeSp)
                        .put("iconSizePx", value.iconSizePx)
                        .put("paddingPx", value.paddingPx)
                        .put("gapPx", value.gapPx)
                        .put("columns", value.columns)
                        .put("opacityPercent", value.opacityPercent)
                        .put("cornerRadiusPx", value.cornerRadiusPx)
                        .put("backgroundColor", value.backgroundColor)
                        .put("textColor", value.textColor)
                        .put("accentColor", value.accentColor));
            }
            return root.put("elements", values).toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    @NonNull public static SystemShadeConfig fromJson(String raw) {
        SystemShadeConfig value = new SystemShadeConfig();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return value;
            value.displayId = root.optInt("displayId", value.displayId);
            value.panelHeightPx = root.optInt("panelHeightPx", value.panelHeightPx);
            value.gestureHandleHeightPx = root.optInt(
                    "gestureHandleHeightPx", value.gestureHandleHeightPx);
            value.openThresholdPx = root.optInt("openThresholdPx", value.openThresholdPx);
            value.closeThresholdPx = root.optInt("closeThresholdPx", value.closeThresholdPx);
            value.animationDurationMs = root.optInt(
                    "animationDurationMs", value.animationDurationMs);
            value.editorSnapPx = root.optInt("editorSnapPx", value.editorSnapPx);
            value.scrimOpacityPercent = root.optInt(
                    "scrimOpacityPercent", value.scrimOpacityPercent);
            value.panelOpacityPercent = root.optInt(
                    "panelOpacityPercent", value.panelOpacityPercent);
            value.panelCornerRadiusPx = root.optInt(
                    "panelCornerRadiusPx", value.panelCornerRadiusPx);
            value.panelColor = root.optString("panelColor", value.panelColor);
            value.closeAfterAction = root.optBoolean(
                    "closeAfterAction", value.closeAfterAction);
            value.hapticFeedback = root.optBoolean("hapticFeedback", value.hapticFeedback);
            JSONArray values = root.optJSONArray("elements");
            if (values != null) {
                value.elements.clear();
                for (int index = 0; index < values.length(); index++) {
                    JSONObject json = values.optJSONObject(index);
                    if (json == null) continue;
                    Kind kind = Kind.fromId(json.optString("kind", ""));
                    Element element = new Element(kind,
                            json.optInt("x", 0), json.optInt("y", 0),
                            json.optInt("width", 320), json.optInt("height", 100));
                    element.visible = json.optBoolean("visible", true);
                    element.textSizeSp = json.optInt("textSizeSp", element.textSizeSp);
                    element.iconSizePx = json.optInt("iconSizePx", element.iconSizePx);
                    element.paddingPx = json.optInt("paddingPx", element.paddingPx);
                    element.gapPx = json.optInt("gapPx", element.gapPx);
                    element.columns = json.optInt("columns", element.columns);
                    element.opacityPercent = json.optInt(
                            "opacityPercent", element.opacityPercent);
                    element.cornerRadiusPx = json.optInt(
                            "cornerRadiusPx", element.cornerRadiusPx);
                    element.backgroundColor = json.optString(
                            "backgroundColor", element.backgroundColor);
                    element.textColor = json.optString("textColor", element.textColor);
                    element.accentColor = json.optString("accentColor", element.accentColor);
                    value.elements.add(element);
                }
            }
        } catch (JSONException ignored) {
            return new SystemShadeConfig();
        }
        value.normalize();
        return value;
    }

    @NonNull public SystemShadeConfig copy() {
        return fromJson(toJson());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @NonNull private static String color(String value, @NonNull String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?") ? clean : fallback;
    }
}
