package dezz.status.widget.launcher;

import android.content.Context;
import android.provider.Settings;

import dezz.status.widget.Preferences;
import dezz.status.widget.shell.PrivilegedShell;

/**
 * One-way cleanup for builds that previously changed Android's global status-bar policy.
 *
 * <p>The launcher option now hides only Status Widget's TIME and BLUETOOTH views. A prior build
 * may have persisted the original {@code policy_control} before adding an immersive-status rule;
 * restore that exact value once, then forget the migration marker. No current user action writes
 * SystemUI policy.</p>
 */
public final class EcarxSystemStatusBarPolicy {
    private static final String KEY = "policy_control";
    private static final String UNSET = "__unset__";
    private static final String NULL = "__null__";

    private EcarxSystemStatusBarPolicy() {
    }

    public static void applyStored(Context context) {
        Context appContext = context.getApplicationContext();
        Preferences preferences = new Preferences(appContext);
        String stored = preferences.launcherSystemStatusBarOriginalPolicy.get();
        if (stored == null || UNSET.equals(stored)) return;
        String desired = originalPolicy(stored);

        if (writeDirect(appContext, desired) && equalPolicy(readDirect(appContext), desired)) {
            preferences.launcherSystemStatusBarOriginalPolicy.set(UNSET);
            return;
        }
        if (!shellSafe(desired)) return;

        String command = desired == null
                ? "settings delete global policy_control"
                : "settings put global policy_control '" + desired + "'";
        PrivilegedShell.get(appContext).runCommand(command, (output, error) -> {
            if (error != null) return;
            PrivilegedShell.get(appContext).runCommand(
                    "settings get global policy_control",
                    (readOutput, readError) -> {
                        boolean success = readError == null
                                && equalPolicy(normalizeShellValue(readOutput), desired);
                        if (success) preferences.launcherSystemStatusBarOriginalPolicy.set(UNSET);
                    });
        });
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
