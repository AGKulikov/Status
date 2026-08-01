/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import dezz.status.widget.Fonts;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;
import java.util.HashSet;
import java.util.Set;

/** Complete, independent appearance and unavailable-state configuration for one HA brick. */
public final class HaBrickConfig {
    public static final int SCHEMA_VERSION = 3;

    public String id;
    /** Connector-neutral value source. Legacy configs bind this to {@link #id}. */
    public SourceBinding sourceBinding;
    /** Optional press action; main-row legacy bricks have no action. */
    public ActionBinding actionBinding;
    /** Local first-match presentation rules. Connectors publish raw values, never colors. */
    public RuleSet displayRules;
    public String name;
    public boolean enabled;
    public int order;
    public int fontSize;
    public String fontFamily;
    public boolean bold;
    public boolean italic;
    public int contentAlpha;
    public String defaultText;
    public String defaultColor;
    public String outlineColor;
    public int outlineAlpha;
    public int outlineWidth;
    public int marginStart;
    public int marginEnd;
    public int paddingLeft;
    public int paddingTop;
    public int paddingRight;
    public int paddingBottom;
    public int adjustY;
    public int maxWidth;
    public boolean marquee;
    public String pendingText;
    public String pendingColor;
    public String staleText;
    public String staleColor;
    public String emptyText;
    public String emptyColor;
    public long staleAfterSeconds;
    public boolean collapseWhenEmpty;
    public final Set<String> hideInPackages = new HashSet<>();
    public boolean inheritGroupHide;
    public boolean hideKeepsSpace;

    @NonNull
    public static HaBrickConfig create(String id, int order) {
        HaBrickConfig c = new HaBrickConfig();
        c.id = AutomationContract.requireSafeId(id);
        c.sourceBinding = SourceBinding.legacy(c.id);
        c.actionBinding = ActionBinding.unbound();
        c.displayRules = ScenarioPresets.raw();
        c.name = id;
        c.enabled = true;
        c.order = Math.max(0, order);
        c.fontSize = 40;
        c.fontFamily = Fonts.DEFAULT_KEY;
        c.bold = false;
        c.italic = false;
        c.contentAlpha = 255;
        c.defaultText = "";
        c.defaultColor = "#FFFFFFFF";
        c.outlineColor = "#FF000000";
        c.outlineAlpha = 170;
        c.outlineWidth = 2;
        c.marginStart = 0;
        c.marginEnd = 0;
        c.paddingLeft = 0;
        c.paddingTop = 0;
        c.paddingRight = 0;
        c.paddingBottom = 0;
        c.adjustY = 0;
        c.maxWidth = 500;
        c.marquee = false;
        c.pendingText = "…";
        c.pendingColor = "#80FFFFFF";
        c.staleText = "…";
        c.staleColor = "#80FFFFFF";
        c.emptyText = "";
        c.emptyColor = "transparent";
        c.staleAfterSeconds = 0;
        c.collapseWhenEmpty = false;
        c.inheritGroupHide = true;
        c.hideKeepsSpace = false;
        return c;
    }

    @NonNull
    public static HaBrickConfig fromJson(@NonNull JSONObject o, int fallbackOrder) {
        HaBrickConfig c = create(o.optString("id", "brick_" + fallbackOrder), fallbackOrder);
        c.name = o.optString("name", c.name);
        c.enabled = o.optBoolean("enabled", c.enabled);
        c.order = nonNegative(o.optInt("order", c.order));
        c.fontSize = clamp(o.optInt("fontSize", c.fontSize), 10, 500);
        c.fontFamily = o.optString("fontFamily", c.fontFamily);
        c.bold = o.optBoolean("bold", c.bold);
        c.italic = o.optBoolean("italic", c.italic);
        c.contentAlpha = clamp(o.optInt("contentAlpha", c.contentAlpha), 0, 255);
        c.defaultText = o.optString("defaultText", c.defaultText);
        c.defaultColor = o.optString("defaultColor", c.defaultColor);
        c.outlineColor = o.optString("outlineColor", c.outlineColor);
        c.outlineAlpha = clamp(o.optInt("outlineAlpha", c.outlineAlpha), 0, 255);
        c.outlineWidth = clamp(o.optInt("outlineWidth", c.outlineWidth), 0, 20);
        c.marginStart = clamp(o.optInt("marginStart", c.marginStart), 0, 1000);
        c.marginEnd = clamp(o.optInt("marginEnd", c.marginEnd), 0, 1000);
        c.paddingLeft = clamp(o.optInt("paddingLeft", c.paddingLeft), 0, 1000);
        c.paddingTop = clamp(o.optInt("paddingTop", c.paddingTop), 0, 1000);
        c.paddingRight = clamp(o.optInt("paddingRight", c.paddingRight), 0, 1000);
        c.paddingBottom = clamp(o.optInt("paddingBottom", c.paddingBottom), 0, 1000);
        c.adjustY = clamp(o.optInt("adjustY", c.adjustY), -1000, 1000);
        c.maxWidth = clamp(o.optInt("maxWidth", c.maxWidth), 0, 4000);
        c.marquee = o.optBoolean("marquee", c.marquee);
        c.pendingText = o.optString("pendingText",
                o.optString("unavailableText", c.pendingText));
        c.pendingColor = o.optString("pendingColor",
                o.optString("unavailableColor", c.pendingColor));
        c.staleText = o.optString("staleText",
                o.optString("unavailableText", c.staleText));
        c.staleColor = o.optString("staleColor",
                o.optString("unavailableColor", c.staleColor));
        c.emptyText = o.optString("emptyText", c.emptyText);
        c.emptyColor = o.optString("emptyColor", c.emptyColor);
        c.staleAfterSeconds = Math.max(0L, o.optLong("staleAfterSeconds", c.staleAfterSeconds));
        c.collapseWhenEmpty = o.optBoolean("collapseWhenEmpty", c.collapseWhenEmpty);
        c.inheritGroupHide = o.optBoolean("inheritGroupHide", c.inheritGroupHide);
        c.hideKeepsSpace = o.optBoolean("hideKeepsSpace", c.hideKeepsSpace);
        JSONArray hidden = o.optJSONArray("hideInPackages");
        if (hidden != null) {
            for (int i = 0; i < hidden.length(); i++) {
                String packageName = hidden.optString(i, "").trim();
                if (!packageName.isEmpty() && packageName.length() <= 255) {
                    c.hideInPackages.add(packageName);
                }
            }
        }
        JSONObject source = o.optJSONObject("sourceBinding");
        c.sourceBinding = source == null ? SourceBinding.legacy(c.id)
                : SourceBinding.fromJson(source);
        JSONObject action = o.optJSONObject("actionBinding");
        c.actionBinding = action == null ? ActionBinding.unbound()
                : ActionBinding.fromJson(action);
        JSONObject rules = o.optJSONObject("displayRules");
        c.displayRules = rules == null ? defaultRules(c.sourceBinding) : RuleSet.fromJson(rules);
        return c;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("schema", SCHEMA_VERSION);
        o.put("id", id);
        o.put("sourceBinding", sourceBinding == null
                ? SourceBinding.legacy(id).toJson() : sourceBinding.toJson());
        o.put("actionBinding", actionBinding == null
                ? ActionBinding.unbound().toJson() : actionBinding.toJson());
        o.put("displayRules", (displayRules == null ? defaultRules(sourceBinding) : displayRules)
                .toJson());
        o.put("name", name);
        o.put("enabled", enabled);
        o.put("order", order);
        o.put("fontSize", fontSize);
        o.put("fontFamily", fontFamily);
        o.put("bold", bold);
        o.put("italic", italic);
        o.put("contentAlpha", contentAlpha);
        o.put("defaultText", defaultText);
        o.put("defaultColor", defaultColor);
        o.put("outlineColor", outlineColor);
        o.put("outlineAlpha", outlineAlpha);
        o.put("outlineWidth", outlineWidth);
        o.put("marginStart", marginStart);
        o.put("marginEnd", marginEnd);
        o.put("paddingLeft", paddingLeft);
        o.put("paddingTop", paddingTop);
        o.put("paddingRight", paddingRight);
        o.put("paddingBottom", paddingBottom);
        o.put("adjustY", adjustY);
        o.put("maxWidth", maxWidth);
        o.put("marquee", marquee);
        o.put("pendingText", pendingText);
        o.put("pendingColor", pendingColor);
        o.put("staleText", staleText);
        o.put("staleColor", staleColor);
        o.put("emptyText", emptyText);
        o.put("emptyColor", emptyColor);
        o.put("staleAfterSeconds", staleAfterSeconds);
        o.put("collapseWhenEmpty", collapseWhenEmpty);
        o.put("inheritGroupHide", inheritGroupHide);
        o.put("hideKeepsSpace", hideKeepsSpace);
        JSONArray hidden = new JSONArray();
        for (String packageName : hideInPackages) hidden.put(packageName);
        o.put("hideInPackages", hidden);
        return o;
    }

    private static int nonNegative(int value) { return Math.max(0, value); }

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
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
