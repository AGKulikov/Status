/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dezz.status.widget.integration.ActionBinding;

/**
 * One edge-triggered Android broadcast action mapped to one immutable connector command.
 *
 * <p>This model is deliberately separate from {@link Scenario}. A regular scenario is a
 * continuously evaluated projection of connector state and may be evaluated many times; putting
 * a remote command in that projection could repeat a side effect whenever any source changes.
 * An intent action rule is instead consumed exactly once for each accepted broadcast event.</p>
 */
public final class IntentActionRule {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_INTENT_ACTION_CHARS = 256;
    private static final int MAX_LABEL_CHARS = 512;
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern QUALIFIED_ACTION = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern TRIGGER_TOKEN = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern RULE_FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SECURE_ACTION_SUFFIX = Pattern.compile(
            "^(.+)\\.x([a-f0-9]{32})$");
    private static final String APP_ACTION_PREFIX = "ru.natro.statuswidget.";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public final int schema;
    @NonNull public final String id;
    public final boolean enabled;
    /** Exact action including its random bearer suffix, for example {@code sh.car.gate.x...}. */
    @NonNull public final String intentAction;
    /** Random secret required by the fixed cold-start endpoint; never included in runtime logs. */
    @NonNull public final String triggerToken;
    /** Stored command; received Intent extras are never allowed to alter it. */
    @NonNull public final ActionBinding command;
    /** Informational labels only. Stable routing always uses {@link ActionBinding#resourceId}. */
    @NonNull public final String accessoryLabel;
    @NonNull public final String serviceLabel;
    @NonNull public final String characteristicLabel;

    public IntentActionRule(String id, boolean enabled, String intentAction, String triggerToken,
                            @NonNull ActionBinding command, String accessoryLabel,
                            String serviceLabel, String characteristicLabel) {
        this.schema = SCHEMA_VERSION;
        this.id = validateId(id);
        this.enabled = enabled;
        this.intentAction = validateIntentAction(intentAction);
        this.triggerToken = validateTriggerToken(triggerToken);
        this.command = Objects.requireNonNull(command, "command");
        if (!command.isBound()) {
            throw new IllegalArgumentException("Intent action command is not bound");
        }
        this.accessoryLabel = label(accessoryLabel, "accessoryLabel");
        this.serviceLabel = label(serviceLabel, "serviceLabel");
        this.characteristicLabel = label(characteristicLabel, "characteristicLabel");
    }

    @NonNull
    public static IntentActionRule fromJson(@NonNull JSONObject object) {
        Objects.requireNonNull(object, "object");
        int schema = object.optInt("schema", SCHEMA_VERSION);
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported intent action schema: " + schema);
        }
        JSONObject command = object.optJSONObject("command");
        if (command == null) {
            throw new IllegalArgumentException("Intent action command is missing");
        }
        return new IntentActionRule(object.optString("id", ""),
                object.optBoolean("enabled", true), object.optString("intentAction", ""),
                object.optString("triggerToken", ""), ActionBinding.fromJson(command),
                object.optString("accessoryLabel", ""),
                object.optString("serviceLabel", ""),
                object.optString("characteristicLabel", ""));
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schema", schema);
        object.put("id", id);
        object.put("enabled", enabled);
        object.put("intentAction", intentAction);
        object.put("triggerToken", triggerToken);
        object.put("command", command.toJson());
        if (!accessoryLabel.isEmpty()) object.put("accessoryLabel", accessoryLabel);
        if (!serviceLabel.isEmpty()) object.put("serviceLabel", serviceLabel);
        if (!characteristicLabel.isEmpty()) {
            object.put("characteristicLabel", characteristicLabel);
        }
        return object;
    }

    /**
     * Validates and normalizes an exact dynamic-receiver action.
     *
     * <p>The application action is intentionally not derived from an incoming Intent. Requiring
     * a qualified, preconfigured name prevents accidental matches with short/common actions.
     * Android framework actions and this app's fixed HA compatibility endpoint are reserved so a
     * user rule cannot shadow an unrelated receiver.</p>
     */
    @NonNull
    public static String validateIntentAction(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_INTENT_ACTION_CHARS
                || value.indexOf('\u0000') >= 0 || !QUALIFIED_ACTION.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid qualified Intent action");
        }
        Matcher suffix = SECURE_ACTION_SUFFIX.matcher(value);
        if (!suffix.matches()) {
            throw new IllegalArgumentException(
                    "Intent action must end with an application-generated secret suffix");
        }
        String prefix = suffix.group(1);
        if (!QUALIFIED_ACTION.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid Intent action prefix: " + prefix);
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        if (lower.startsWith("android.") || lower.startsWith("com.android.")
                || prefix.startsWith(APP_ACTION_PREFIX + "HA_")
                || prefix.equals(APP_ACTION_PREFIX + "SCENARIO_TRIGGER")) {
            throw new IllegalArgumentException("Reserved Intent action");
        }
        return value;
    }

    /** Adds an unguessable bearer suffix while keeping the user's readable action prefix. */
    @NonNull
    public static String secureIntentAction(String rawPrefix, String rawToken) {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim();
        Matcher existing = SECURE_ACTION_SUFFIX.matcher(prefix);
        if (existing.matches()) prefix = existing.group(1);
        String token = validateTriggerToken(rawToken);
        return validateIntentAction(prefix + ".x" + token);
    }

    /** Returns the editable human prefix from a previously validated secure action. */
    @NonNull
    public static String intentActionPrefix(String secureAction) {
        String value = validateIntentAction(secureAction);
        Matcher matcher = SECURE_ACTION_SUFFIX.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Missing secure action suffix");
        return matcher.group(1);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof IntentActionRule)) return false;
        IntentActionRule rule = (IntentActionRule) other;
        return schema == rule.schema && enabled == rule.enabled && id.equals(rule.id)
                && intentAction.equals(rule.intentAction) && command.equals(rule.command)
                && triggerToken.equals(rule.triggerToken)
                && accessoryLabel.equals(rule.accessoryLabel)
                && serviceLabel.equals(rule.serviceLabel)
                && characteristicLabel.equals(rule.characteristicLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, id, enabled, intentAction, triggerToken, command, accessoryLabel,
                serviceLabel, characteristicLabel);
    }

    /** Generates 128 unpredictable bits encoded without punctuation for easy copy/paste. */
    @NonNull
    public static String newTriggerToken() {
        byte[] random = new byte[16];
        SECURE_RANDOM.nextBytes(random);
        return hex(random);
    }

    /** Constant-time check used when a cold-start receiver hands a selected rule to the service. */
    public boolean matchesTriggerToken(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!TRIGGER_TOKEN.matcher(value).matches()) return false;
        return MessageDigest.isEqual(triggerToken.getBytes(StandardCharsets.US_ASCII),
                value.getBytes(StandardCharsets.US_ASCII));
    }

    /** Canonical digest passed only across the exported-receiver/non-exported-service boundary. */
    @NonNull
    public String executionFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(toJson().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Could not fingerprint Intent rule", impossible);
        }
    }

    /** Constant-time comparison prevents an edited/reused id from executing another command. */
    public boolean matchesExecutionFingerprint(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!RULE_FINGERPRINT.matcher(value).matches()) return false;
        return MessageDigest.isEqual(executionFingerprint().getBytes(StandardCharsets.US_ASCII),
                value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static String validateId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid intent action rule id: " + raw);
        }
        return value;
    }

    private static String validateTriggerToken(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!TRIGGER_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid intent action trigger token");
        }
        return value;
    }

    private static String label(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_LABEL_CHARS || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid intent action " + field);
        }
        return value;
    }
}
