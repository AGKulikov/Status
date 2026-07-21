/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

/** Independent configuration of one text/tile cell in the second floating overlay. */
public final class PopupItemConfig {
    public static final int SCHEMA_VERSION = 5;
    /** Legacy single-overlay id. Existing configurations migrate here automatically. */
    public static final String DEFAULT_OVERLAY_ID = "popup";
    public static final String TYPE_HA_DEVICE = "HA_DEVICE";
    public static final String TYPE_HA_TEXT = "HA_TEXT";
    public static final String TYPE_HA_BUTTON = "HA_BUTTON";
    public static final String TYPE_STATIC_TEXT = "STATIC_TEXT";
    public static final String TYPE_BUILTIN = "BUILTIN";

    public String id;
    /** Floating overlay that owns this tile. Tile ids remain globally unique for connectors. */
    public String overlayId;
    /** Runtime state id. Kept separate so a local editor identity never changes MQTT topics. */
    public String automationId;
    /** Connector-neutral value source. Missing legacy data migrates from {@link #automationId}. */
    public SourceBinding sourceBinding;
    /** Connector-neutral press action. Missing legacy data migrates from actionId/payload. */
    public ActionBinding actionBinding;
    /** Local first-match presentation rules. Connectors publish raw values, never colors. */
    public RuleSet displayRules;
    public String type;
    /** Stable builtin.* id used only by TYPE_BUILTIN. */
    public String builtinId;
    public String name;
    public boolean enabled;
    public int order;
    /** -1 means automatic placement. */
    public int row;
    /** -1 means automatic placement. */
    public int column;
    public int rowSpan;
    public int columnSpan;
    /** Local allow-listed icon id. */
    public String icon;
    public int iconSize;
    public String iconColor;
    public int iconAlpha;
    public String iconBackgroundColor;
    public int iconBackgroundAlpha;
    public int iconPadding;
    public int iconCornerRadius;
    /** 0=start, 1=center, 2=end. */
    public int iconAlignment;
    public int iconAdjustX;
    public int iconAdjustY;
    public int iconRotation;
    /** 0=vertical, 1=horizontal. */
    public int orientation;
    public boolean showTitle;
    public boolean showStatus;
    public String title;
    public int titleSize;
    public String titleColor;
    public int titleAlpha;
    public boolean titleBold;
    public String defaultText;
    public int textSize;
    public String defaultTextColor;
    public int textAlpha;
    public boolean textBold;
    public String pendingText;
    public String pendingColor;
    public String staleText;
    public String staleColor;
    public long staleAfterSeconds;
    public String backgroundColor;
    public int backgroundAlpha;
    public String borderColor;
    public int borderAlpha;
    public int borderWidth;
    public int cornerRadius;
    public int padding;
    public int adjustX;
    public int adjustY;
    public String actionId;
    public String actionPayload;
    public boolean confirmationRequired;
    public String confirmationText;
    public boolean autoHideAfterAction;

    @NonNull
    public static PopupItemConfig create(String id, int order) {
        PopupItemConfig c = new PopupItemConfig();
        c.id = AutomationContract.requireSafeId(id);
        c.overlayId = DEFAULT_OVERLAY_ID;
        c.automationId = c.id;
        c.sourceBinding = SourceBinding.legacy(c.automationId);
        c.actionBinding = ActionBinding.unbound();
        c.displayRules = ScenarioPresets.raw();
        c.type = TYPE_HA_DEVICE;
        c.builtinId = "";
        c.name = id;
        c.enabled = true;
        c.order = Math.max(0, order);
        c.row = -1;
        c.column = -1;
        c.rowSpan = 1;
        c.columnSpan = 1;
        c.icon = "gate";
        c.iconSize = 42;
        c.iconColor = "#FFFFFFFF";
        c.iconAlpha = 255;
        c.iconBackgroundColor = "#00000000";
        c.iconBackgroundAlpha = 0;
        c.iconPadding = 0;
        c.iconCornerRadius = 16;
        c.iconAlignment = 1;
        c.iconAdjustX = 0;
        c.iconAdjustY = 0;
        c.iconRotation = 0;
        c.orientation = 0;
        c.showTitle = true;
        c.showStatus = true;
        c.title = id;
        c.titleSize = 18;
        c.titleColor = "#CCFFFFFF";
        c.titleAlpha = 255;
        c.titleBold = false;
        c.defaultText = "";
        c.textSize = 24;
        c.defaultTextColor = "#FFFFFFFF";
        c.textAlpha = 255;
        c.textBold = true;
        c.pendingText = "…";
        c.pendingColor = "#80FFFFFF";
        c.staleText = "…";
        c.staleColor = "#80FFFFFF";
        c.staleAfterSeconds = 0;
        c.backgroundColor = "#FF28282C";
        c.backgroundAlpha = 235;
        c.borderColor = "#00FFFFFF";
        c.borderAlpha = 0;
        c.borderWidth = 0;
        c.cornerRadius = 28;
        c.padding = 12;
        c.adjustX = 0;
        c.adjustY = 0;
        c.actionId = "";
        c.actionPayload = "{}";
        c.confirmationRequired = false;
        c.confirmationText = "Нажмите ещё раз";
        c.autoHideAfterAction = false;
        return c;
    }

    @NonNull
    public static PopupItemConfig fromJson(@NonNull JSONObject o, int fallbackOrder) {
        PopupItemConfig c = create(o.optString("id", "popup_" + fallbackOrder), fallbackOrder);
        c.overlayId = AutomationContract.requireSafeId(
                o.optString("overlayId", DEFAULT_OVERLAY_ID));
        c.automationId = AutomationContract.requireSafeId(
                o.optString("automationId", c.automationId));
        c.type = normalizeType(o.optString("type", c.type));
        c.builtinId = o.optString("builtinId", c.builtinId).trim();
        if (!c.builtinId.isEmpty()) AutomationContract.requireSafeId(c.builtinId);
        c.name = o.optString("name", c.name);
        c.enabled = o.optBoolean("enabled", c.enabled);
        c.order = Math.max(0, o.optInt("order", c.order));
        c.row = clamp(o.optInt("row", c.row), -1, 100);
        c.column = clamp(o.optInt("column", c.column), -1, 100);
        c.rowSpan = clamp(o.optInt("rowSpan", c.rowSpan), 1, 100);
        c.columnSpan = clamp(o.optInt("columnSpan", c.columnSpan), 1, 100);
        c.icon = o.optString("icon", c.icon);
        c.iconSize = clamp(o.optInt("iconSize", c.iconSize), 0, 500);
        c.iconColor = o.optString("iconColor", c.iconColor);
        c.iconAlpha = clamp(o.optInt("iconAlpha", c.iconAlpha), 0, 255);
        c.iconBackgroundColor = o.optString("iconBackgroundColor", c.iconBackgroundColor);
        c.iconBackgroundAlpha = clamp(o.optInt("iconBackgroundAlpha", c.iconBackgroundAlpha), 0, 255);
        c.iconPadding = clamp(o.optInt("iconPadding", c.iconPadding), 0, 500);
        c.iconCornerRadius = clamp(o.optInt("iconCornerRadius", c.iconCornerRadius), 0, 500);
        c.iconAlignment = clamp(o.optInt("iconAlignment", c.iconAlignment), 0, 2);
        c.iconAdjustX = clamp(o.optInt("iconAdjustX", c.iconAdjustX), -1000, 1000);
        c.iconAdjustY = clamp(o.optInt("iconAdjustY", c.iconAdjustY), -1000, 1000);
        c.iconRotation = clamp(o.optInt("iconRotation", c.iconRotation), -3600, 3600);
        c.orientation = clamp(o.optInt("orientation", c.orientation), 0, 1);
        c.showTitle = o.optBoolean("showTitle", c.showTitle);
        c.showStatus = o.optBoolean("showStatus", c.showStatus);
        c.title = o.optString("title", c.title);
        c.titleSize = clamp(o.optInt("titleSize", c.titleSize), 8, 200);
        c.titleColor = o.optString("titleColor", c.titleColor);
        c.titleAlpha = clamp(o.optInt("titleAlpha", c.titleAlpha), 0, 255);
        c.titleBold = o.optBoolean("titleBold", c.titleBold);
        c.defaultText = o.optString("defaultText", c.defaultText);
        c.textSize = clamp(o.optInt("textSize", c.textSize), 8, 300);
        c.defaultTextColor = o.optString("defaultTextColor", c.defaultTextColor);
        c.textAlpha = clamp(o.optInt("textAlpha", c.textAlpha), 0, 255);
        c.textBold = o.optBoolean("textBold", c.textBold);
        // Schema-1 used one unavailable value for both startup and stale states.
        String oldUnavailableText = o.optString("unavailableText", c.pendingText);
        String oldUnavailableColor = o.optString("unavailableColor", c.pendingColor);
        c.pendingText = o.optString("pendingText", oldUnavailableText);
        c.pendingColor = o.optString("pendingColor", oldUnavailableColor);
        c.staleText = o.optString("staleText", oldUnavailableText);
        c.staleColor = o.optString("staleColor", oldUnavailableColor);
        c.staleAfterSeconds = Math.max(0, o.optLong("staleAfterSeconds", c.staleAfterSeconds));
        c.backgroundColor = o.optString("backgroundColor", c.backgroundColor);
        c.backgroundAlpha = clamp(o.optInt("backgroundAlpha", c.backgroundAlpha), 0, 255);
        c.borderColor = o.optString("borderColor", c.borderColor);
        c.borderAlpha = clamp(o.optInt("borderAlpha", c.borderAlpha), 0, 255);
        c.borderWidth = clamp(o.optInt("borderWidth", c.borderWidth), 0, 100);
        c.cornerRadius = clamp(o.optInt("cornerRadius", c.cornerRadius), 0, 500);
        c.padding = clamp(o.optInt("padding", c.padding), 0, 500);
        c.adjustX = clamp(o.optInt("adjustX", c.adjustX), -1000, 1000);
        c.adjustY = clamp(o.optInt("adjustY", c.adjustY), -1000, 1000);
        c.actionId = o.optString("actionId", c.actionId).trim();
        if (!c.actionId.isEmpty()) AutomationContract.requireSafeId(c.actionId);
        c.actionPayload = o.optString("actionPayload", c.actionPayload);
        if (c.actionPayload.length() > 8192) {
            throw new IllegalArgumentException("Action payload is too large");
        }
        c.confirmationRequired = o.optBoolean("confirmationRequired", c.confirmationRequired);
        c.confirmationText = o.optString("confirmationText", c.confirmationText);
        c.autoHideAfterAction = o.optBoolean("autoHideAfterAction", c.autoHideAfterAction);
        JSONObject source = o.optJSONObject("sourceBinding");
        c.sourceBinding = source == null ? SourceBinding.legacy(c.automationId)
                : SourceBinding.fromJson(source);
        JSONObject action = o.optJSONObject("actionBinding");
        c.actionBinding = action == null ? ActionBinding.legacy(c.actionId, c.actionPayload)
                : ActionBinding.fromJson(action);
        JSONObject rules = o.optJSONObject("displayRules");
        c.displayRules = rules == null ? defaultRules(c.sourceBinding) : RuleSet.fromJson(rules);
        return c;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA_VERSION);
        o.put("id", id).put("overlayId", overlayId)
                .put("automationId", automationId).put("type", type)
                .put("builtinId", builtinId).put("name", name)
                .put("enabled", enabled).put("order", order);
        o.put("sourceBinding", sourceBinding == null
                ? SourceBinding.legacy(automationId).toJson() : sourceBinding.toJson());
        o.put("actionBinding", actionBinding == null
                ? ActionBinding.legacy(actionId, actionPayload).toJson()
                : actionBinding.toJson());
        o.put("displayRules", (displayRules == null ? defaultRules(sourceBinding) : displayRules)
                .toJson());
        o.put("row", row).put("column", column).put("rowSpan", rowSpan)
                .put("columnSpan", columnSpan);
        o.put("icon", icon).put("iconSize", iconSize).put("iconColor", iconColor)
                .put("iconAlpha", iconAlpha).put("iconBackgroundColor", iconBackgroundColor)
                .put("iconBackgroundAlpha", iconBackgroundAlpha).put("iconPadding", iconPadding)
                .put("iconCornerRadius", iconCornerRadius).put("iconAlignment", iconAlignment)
                .put("iconAdjustX", iconAdjustX).put("iconAdjustY", iconAdjustY)
                .put("iconRotation", iconRotation).put("orientation", orientation)
                .put("showTitle", showTitle).put("showStatus", showStatus);
        o.put("title", title).put("titleSize", titleSize).put("titleColor", titleColor)
                .put("titleAlpha", titleAlpha).put("titleBold", titleBold);
        o.put("defaultText", defaultText).put("textSize", textSize)
                .put("defaultTextColor", defaultTextColor).put("textAlpha", textAlpha)
                .put("textBold", textBold);
        o.put("pendingText", pendingText).put("pendingColor", pendingColor)
                .put("staleText", staleText).put("staleColor", staleColor)
                .put("staleAfterSeconds", staleAfterSeconds);
        o.put("backgroundColor", backgroundColor).put("backgroundAlpha", backgroundAlpha)
                .put("borderColor", borderColor).put("borderAlpha", borderAlpha)
                .put("borderWidth", borderWidth).put("cornerRadius", cornerRadius)
                .put("padding", padding).put("adjustX", adjustX).put("adjustY", adjustY);
        o.put("actionId", actionId).put("actionPayload", actionPayload)
                .put("confirmationRequired", confirmationRequired)
                .put("confirmationText", confirmationText)
                .put("autoHideAfterAction", autoHideAfterAction);
        return o;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @NonNull
    private static RuleSet defaultRules(SourceBinding binding) {
        String presentation = binding == null ? SourceBinding.PRESENTATION_RAW
                : binding.presentation;
        if (SourceBinding.PRESENTATION_COVER.equals(presentation)) return ScenarioPresets.cover();
        if (SourceBinding.PRESENTATION_BOOLEAN.equals(presentation)) {
            return ScenarioPresets.booleanState();
        }
        if (SourceBinding.PRESENTATION_TEMPERATURE.equals(presentation)) {
            return ScenarioPresets.temperature();
        }
        return ScenarioPresets.raw();
    }

    private static String normalizeType(String value) {
        if (TYPE_HA_TEXT.equals(value) || TYPE_HA_BUTTON.equals(value)
                || TYPE_STATIC_TEXT.equals(value) || TYPE_BUILTIN.equals(value)) return value;
        return TYPE_HA_DEVICE;
    }
}
