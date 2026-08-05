/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/** Free, cell-based composition of one CarPlay-style iPhone notification card. */
public final class PhoneNotificationLayoutConfig {
    public static final int SCHEMA_VERSION = 4;
    private static final int FIRST_READABLE_SCHEMA_VERSION = 2;
    /** Fine horizontal grid: at the default 1000 px width one cell is about 21 px. */
    public static final int GRID_COLUMNS = 48;
    public static final int GRID_ROWS = 12;

    public static final String AVATAR = "avatar";
    public static final String BADGE = "badge";
    public static final String TITLE = "title";
    public static final String TIME = "time";
    public static final String APPLICATION = "application";
    public static final String MESSAGE = "message";
    public static final String CHEVRON = "chevron";
    public static final String OVERFLOW_ELLIPSIS = "ellipsis";
    public static final String OVERFLOW_SCROLL = "scroll";
    public static final String TEXT_VERTICAL_TOP = "top";
    public static final String TEXT_VERTICAL_CENTER = "center";

    public static final class Element {
        @NonNull public final String id;
        @NonNull public final String label;
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;
        public boolean visible;
        public int textSizePx;
        @NonNull public String color;
        public boolean bold;
        /** Maximum number of simultaneously visible lines inside this element's rectangle. */
        public int maxLines;
        /** Either {@link #OVERFLOW_ELLIPSIS} or {@link #OVERFLOW_SCROLL}. */
        @NonNull public String overflowMode;
        /** Vertical placement of the visible text block inside its persisted grid rectangle. */
        @NonNull public String verticalAlignment;

        private Element(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }

        @NonNull JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("column", column).put("row", row)
                    .put("columnSpan", columnSpan).put("rowSpan", rowSpan)
                    .put("visible", visible).put("textSizePx", textSizePx)
                    .put("color", color).put("bold", bold)
                    .put("maxLines", maxLines).put("overflowMode", overflowMode)
                    .put("verticalAlignment", verticalAlignment);
        }

        void read(@Nullable JSONObject source) {
            if (source == null) return;
            column = source.optInt("column", column);
            row = source.optInt("row", row);
            columnSpan = source.optInt("columnSpan", columnSpan);
            rowSpan = source.optInt("rowSpan", rowSpan);
            visible = source.optBoolean("visible", visible);
            textSizePx = source.optInt("textSizePx", textSizePx);
            color = safeColor(source.optString("color", color), color);
            bold = source.optBoolean("bold", bold);
            maxLines = source.optInt("maxLines", maxLines);
            overflowMode = safeOverflowMode(source.optString("overflowMode", overflowMode));
            verticalAlignment = safeVerticalAlignment(source.optString(
                    "verticalAlignment", verticalAlignment));
            normalize();
        }

        void normalize() {
            columnSpan = clamp(columnSpan, 1, GRID_COLUMNS);
            rowSpan = clamp(rowSpan, 1, GRID_ROWS);
            column = clamp(column, 0, GRID_COLUMNS - columnSpan);
            row = clamp(row, 0, GRID_ROWS - rowSpan);
            textSizePx = clamp(textSizePx, 8, 160);
            maxLines = clamp(maxLines, 1, 8);
            color = safeColor(color, "#FFFFFFFF");
            overflowMode = safeOverflowMode(overflowMode);
            verticalAlignment = safeVerticalAlignment(verticalAlignment);
        }
    }

    @NonNull public final String overlayId;
    @NonNull public String backgroundColor = "#FF29292D";
    public int backgroundAlpha = 244;
    /** Radius used by the Apple continuous-card path (persisted key kept compatible). */
    public int cornerRadiusPx = 38;
    public int borderWidthPx = 0;
    @NonNull public String borderColor = "#FFFFFFFF";
    /** Contact avatar stays circular independently from the small application badge. */
    public int avatarCornerRadiusPx = 120;
    /** Radius used by the iOS app-icon continuous mask. */
    public int iconCornerRadiusPx = 12;
    /** Re-applied on every render so reopening the editor cannot fall back to CENTER_CROP. */
    public boolean iconPreserveAspectRatio = true;
    @NonNull public String avatarColor = "#FF5AC466";
    public final Element avatar = element(AVATAR, "Аватар", 1, 1, 5, 7,
            true, 42, "#FFFFFFFF", true);
    public final Element badge = element(BADGE, "Значок приложения", 5, 7, 2, 3,
            true, 20, "#FFFFFFFF", false);
    public final Element title = element(TITLE, "Отправитель", 8, 2, 14, 3,
            true, 30, "#FFFFFFFF", true);
    public final Element time = element(TIME, "Время", 23, 2, 7, 3,
            true, 23, "#FFD1D1D6", false);
    public final Element application = element(APPLICATION, "Приложение", 8, 5, 27, 3,
            true, 25, "#FFFFFFFF", true);
    public final Element message = element(MESSAGE, "Текст", 8, 8, 34, 3,
            false, 22, "#FFD1D1D6", false);
    public final Element chevron = element(CHEVRON, "Стрелка", 45, 3, 2, 6,
            true, 46, "#FFFFFFFF", true);

    private PhoneNotificationLayoutConfig(@NonNull String overlayId) {
        this.overlayId = overlayId;
    }

    @NonNull
    public static PhoneNotificationLayoutConfig carPlay(@NonNull String overlayId) {
        PhoneNotificationLayoutConfig value = new PhoneNotificationLayoutConfig(overlayId);
        if (!PhoneNotificationAutomation.isIconOverlayId(overlayId)) {
            value.avatar.visible = false;
            value.badge.visible = false;
            value.title.column = 2;
            value.title.columnSpan = 20;
            value.application.column = 2;
            value.application.columnSpan = 33;
            value.message.column = 2;
            value.message.columnSpan = 40;
        }
        value.normalize();
        return value;
    }

    @NonNull
    public static PhoneNotificationLayoutConfig fromJson(
            @NonNull String overlayId, @Nullable JSONObject source) {
        PhoneNotificationLayoutConfig value = carPlay(overlayId);
        int schema = source == null ? SCHEMA_VERSION
                : source.optInt("schemaVersion", SCHEMA_VERSION);
        if (source == null || schema < FIRST_READABLE_SCHEMA_VERSION
                || schema > SCHEMA_VERSION) {
            return value;
        }
        value.backgroundColor = safeColor(source.optString(
                "backgroundColor", value.backgroundColor), value.backgroundColor);
        value.backgroundAlpha = source.optInt("backgroundAlpha", value.backgroundAlpha);
        value.cornerRadiusPx = source.optInt("cornerRadiusPx", value.cornerRadiusPx);
        value.borderWidthPx = source.optInt("borderWidthPx", value.borderWidthPx);
        value.borderColor = safeColor(source.optString(
                "borderColor", value.borderColor), value.borderColor);
        value.avatarCornerRadiusPx = source.optInt(
                "avatarCornerRadiusPx", value.avatarCornerRadiusPx);
        value.iconCornerRadiusPx = source.optInt(
                "iconCornerRadiusPx", value.iconCornerRadiusPx);
        value.iconPreserveAspectRatio = source.optBoolean(
                "iconPreserveAspectRatio", value.iconPreserveAspectRatio);
        value.avatarColor = safeColor(source.optString(
                "avatarColor", value.avatarColor), value.avatarColor);
        JSONObject elements = source.optJSONObject("elements");
        for (Element element : value.elements()) {
            element.read(elements == null ? null : elements.optJSONObject(element.id));
        }
        value.normalize();
        return value;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        normalize();
        JSONObject elements = new JSONObject();
        for (Element element : elements()) elements.put(element.id, element.toJson());
        return new JSONObject().put("schemaVersion", SCHEMA_VERSION)
                .put("overlayId", overlayId).put("backgroundColor", backgroundColor)
                .put("backgroundAlpha", backgroundAlpha)
                .put("cornerRadiusPx", cornerRadiusPx)
                .put("borderWidthPx", borderWidthPx)
                .put("borderColor", borderColor)
                .put("avatarCornerRadiusPx", avatarCornerRadiusPx)
                .put("iconCornerRadiusPx", iconCornerRadiusPx)
                .put("iconPreserveAspectRatio", iconPreserveAspectRatio)
                .put("avatarColor", avatarColor).put("elements", elements);
    }

    /** Copies appearance and typography while retaining the target layout and visibility. */
    public void copyStyleFrom(@NonNull PhoneNotificationLayoutConfig source) {
        backgroundColor = source.backgroundColor;
        backgroundAlpha = source.backgroundAlpha;
        cornerRadiusPx = source.cornerRadiusPx;
        borderWidthPx = source.borderWidthPx;
        borderColor = source.borderColor;
        avatarCornerRadiusPx = source.avatarCornerRadiusPx;
        iconCornerRadiusPx = source.iconCornerRadiusPx;
        iconPreserveAspectRatio = source.iconPreserveAspectRatio;
        avatarColor = source.avatarColor;
        for (Element target : elements()) {
            Element origin = source.element(target.id);
            if (origin == null) continue;
            target.textSizePx = origin.textSizePx;
            target.color = origin.color;
            target.bold = origin.bold;
            target.maxLines = origin.maxLines;
            target.overflowMode = origin.overflowMode;
            target.verticalAlignment = origin.verticalAlignment;
        }
        normalize();
    }

    @NonNull
    public List<Element> elements() {
        return Arrays.asList(avatar, badge, title, time, application, message, chevron);
    }

    @Nullable
    public Element element(@NonNull String id) {
        for (Element element : elements()) if (element.id.equals(id)) return element;
        return null;
    }

    public void normalize() {
        backgroundColor = safeColor(backgroundColor, "#FF29292D");
        borderColor = safeColor(borderColor, "#FFFFFFFF");
        avatarColor = safeColor(avatarColor, "#FF5AC466");
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 240);
        borderWidthPx = clamp(borderWidthPx, 0, 40);
        avatarCornerRadiusPx = clamp(avatarCornerRadiusPx, 0, 240);
        iconCornerRadiusPx = clamp(iconCornerRadiusPx, 0, 240);
        for (Element element : elements()) element.normalize();
    }

    @NonNull
    private static Element element(String id, String label, int column, int row,
                                   int columnSpan, int rowSpan, boolean visible,
                                   int textSizePx, String color, boolean bold) {
        Element value = new Element(id, label);
        value.column = column;
        value.row = row;
        value.columnSpan = columnSpan;
        value.rowSpan = rowSpan;
        value.visible = visible;
        value.textSizePx = textSizePx;
        value.color = color;
        value.bold = bold;
        value.maxLines = MESSAGE.equals(id) ? 2 : 1;
        value.overflowMode = OVERFLOW_ELLIPSIS;
        value.verticalAlignment = TEXT_VERTICAL_CENTER;
        return value;
    }

    public static boolean isTextElement(@NonNull String id) {
        return TITLE.equals(id) || TIME.equals(id)
                || APPLICATION.equals(id) || MESSAGE.equals(id);
    }

    @NonNull
    private static String safeOverflowMode(@Nullable String raw) {
        return OVERFLOW_SCROLL.equals(raw) ? OVERFLOW_SCROLL : OVERFLOW_ELLIPSIS;
    }

    @NonNull
    private static String safeVerticalAlignment(@Nullable String raw) {
        return TEXT_VERTICAL_TOP.equals(raw) ? TEXT_VERTICAL_TOP : TEXT_VERTICAL_CENTER;
    }

    private static String safeColor(@Nullable String raw, @NonNull String fallback) {
        String value = raw == null ? "" : raw.trim();
        return value.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?") ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
