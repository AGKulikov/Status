/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** Optional presentation overrides produced by one matching rule. */
public final class Output {
    public static final String VALUE_PLACEHOLDER = "{value}";
    private static final int MAX_TEXT_TEMPLATE_CHARS = 1_024;
    private static final int MAX_STYLE_VALUE_CHARS = 128;

    /** Null leaves the current text unchanged. */
    public final String textTemplate;
    public final String textColor;
    public final String icon;
    public final String backgroundColor;
    /** Null leaves visibility unchanged. */
    public final Boolean visible;
    /** Null leaves connector-derived action availability unchanged. */
    public final Boolean actionEnabled;

    public Output(String textTemplate, String textColor, String icon, String backgroundColor,
                  Boolean visible, Boolean actionEnabled) {
        this.textTemplate = optionalText(textTemplate, "textTemplate",
                MAX_TEXT_TEMPLATE_CHARS, false);
        this.textColor = optionalText(textColor, "textColor", MAX_STYLE_VALUE_CHARS, true);
        this.icon = optionalText(icon, "icon", MAX_STYLE_VALUE_CHARS, true);
        this.backgroundColor = optionalText(backgroundColor, "backgroundColor",
                MAX_STYLE_VALUE_CHARS, true);
        this.visible = visible;
        this.actionEnabled = actionEnabled;
    }

    public static Output none() {
        return new Output(null, null, null, null, null, null);
    }

    /** Replaces only the literal {@code {value}} token; no expressions are evaluated. */
    public String renderText(Input input) {
        if (textTemplate == null) return null;
        return textTemplate.replace(VALUE_PLACEHOLDER, RuleValues.display(input.rawValue));
    }

    public static Output fromJson(JSONObject object) {
        if (object == null) return none();
        return new Output(optionalString(object, "textTemplate"),
                optionalString(object, "textColor"), optionalString(object, "icon"),
                optionalString(object, "backgroundColor"), optionalBoolean(object, "visible"),
                optionalBoolean(object, "actionEnabled"));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        if (textTemplate != null) object.put("textTemplate", textTemplate);
        if (textColor != null) object.put("textColor", textColor);
        if (icon != null) object.put("icon", icon);
        if (backgroundColor != null) object.put("backgroundColor", backgroundColor);
        if (visible != null) object.put("visible", visible);
        if (actionEnabled != null) object.put("actionEnabled", actionEnabled);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Output)) return false;
        Output output = (Output) other;
        return Objects.equals(textTemplate, output.textTemplate)
                && Objects.equals(textColor, output.textColor)
                && Objects.equals(icon, output.icon)
                && Objects.equals(backgroundColor, output.backgroundColor)
                && Objects.equals(visible, output.visible)
                && Objects.equals(actionEnabled, output.actionEnabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(textTemplate, textColor, icon, backgroundColor, visible,
                actionEnabled);
    }

    private static String optionalString(JSONObject object, String key) {
        return !object.has(key) || object.isNull(key) ? null : object.optString(key, null);
    }

    private static Boolean optionalBoolean(JSONObject object, String key) {
        return !object.has(key) || object.isNull(key) ? null : object.optBoolean(key);
    }

    private static String optionalText(String raw, String field, int maxLength, boolean trim) {
        if (raw == null) return null;
        String value = trim ? raw.trim() : raw;
        if (value.length() > maxLength || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid scenario " + field);
        }
        return value;
    }
}
