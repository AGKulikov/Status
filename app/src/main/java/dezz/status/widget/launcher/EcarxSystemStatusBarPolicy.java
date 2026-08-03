package dezz.status.widget.launcher;

import android.content.Context;
import android.provider.Settings;

import dezz.status.widget.Preferences;
import dezz.status.widget.shell.PrivilegedShell;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies Android's real SystemUI status-bar policy globally on ECARX units.
 *
 * <p>This deliberately changes {@code policy_control}; it does not draw a mask over the
 * clock/Bluetooth area. The original value is retained and restored when the option is disabled.
 */
public final class EcarxSystemStatusBarPolicy {
    private static final String KEY = "policy_control";
    private static final String STATUS_RULE = "immersive.status=*";
    private static final String UNSET = "__unset__";
    private static final String NULL = "__null__";

    public interface Callback {
        void onApplied(boolean success, String message);
    }

    private EcarxSystemStatusBarPolicy() {
    }

    public static void applyStored(Context context) {
        if (new Preferences(context).launcherHideSystemStatusBar.get()) {
            apply(context, true, (success, message) -> {
                // Best effort during process start.
            });
        }
    }

    public static void apply(Context context, boolean enabled, Callback callback) {
        Context appContext = context.getApplicationContext();
        Preferences preferences = new Preferences(appContext);
        String current = readDirect(appContext);
        if (enabled && UNSET.equals(preferences.launcherSystemStatusBarOriginalPolicy.get())) {
            preferences.launcherSystemStatusBarOriginalPolicy.set(current == null ? NULL : current);
        }

        String desired = enabled
                ? withStatusRule(current)
                : originalPolicy(preferences.launcherSystemStatusBarOriginalPolicy.get());

        if (writeDirect(appContext, desired) && equalPolicy(readDirect(appContext), desired)) {
            finish(preferences, enabled, callback, true, "Android SystemUI обновлён");
            return;
        }
        if (!shellSafe(desired)) {
            finish(preferences, enabled, callback, false,
                    "Системная политика содержит неподдерживаемые символы");
            return;
        }

        String command = desired == null
                ? "settings delete global policy_control"
                : "settings put global policy_control '" + desired + "'";
        PrivilegedShell.get(appContext).runCommand(command, (output, error) -> {
            if (error != null) {
                finish(preferences, enabled, callback, false, error);
                return;
            }
            PrivilegedShell.get(appContext).runCommand(
                    "settings get global policy_control",
                    (readOutput, readError) -> {
                        boolean success = readError == null
                                && equalPolicy(normalizeShellValue(readOutput), desired);
                        String message = readError;
                        if (success) {
                            message = "Android SystemUI обновлён";
                        } else if (message == null) {
                            message = "ECARX не применил policy_control";
                        }
                        finish(preferences, enabled, callback, success, message);
                    });
        });
    }

    private static void finish(Preferences preferences,
                               boolean enabled,
                               Callback callback,
                               boolean success,
                               String message) {
        if (success) {
            preferences.launcherHideSystemStatusBar.set(enabled);
            if (!enabled) {
                preferences.launcherSystemStatusBarOriginalPolicy.set(UNSET);
            }
        }
        callback.onApplied(success, message == null ? "" : message);
    }

    private static String readDirect(Context context) {
        try {
            return Settings.Global.getString(context.getContentResolver(), KEY);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean writeDirect(Context context, String value) {
        try {
            return Settings.Global.putString(context.getContentResolver(), KEY, value);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String originalPolicy(String stored) {
        if (stored == null || UNSET.equals(stored) || NULL.equals(stored)) {
            return null;
        }
        return stored;
    }

    static String withStatusRule(String policy) {
        List<String> rules = new ArrayList<>();
        if (policy != null) {
            for (String part : policy.split(":")) {
                String rule = part.trim();
                if (!rule.isEmpty() && !rule.startsWith("immersive.status=")) {
                    rules.add(rule);
                }
            }
        }
        rules.add(STATUS_RULE);
        return String.join(":", rules);
    }

    private static boolean shellSafe(String value) {
        return value == null || value.matches("[A-Za-z0-9_.*=,:/\\\\-]{0,2048}");
    }

    private static String normalizeShellValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private static boolean equalPolicy(String left, String right) {
        String normalizedLeft = left == null || left.trim().isEmpty() ? null : left.trim();
        String normalizedRight = right == null || right.trim().isEmpty() ? null : right.trim();
        return normalizedLeft == null
                ? normalizedRight == null
                : normalizedLeft.equals(normalizedRight);
    }
}
