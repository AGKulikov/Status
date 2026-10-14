/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

public final class InstrumentOemPolicy {
    private InstrumentOemPolicy() {}
    /** A missing NaviMode never grants authority to suppress the stock overlay. */
    public static boolean suppressWhiteBar(boolean enabled, int naviMode) {
        return enabled && naviMode == 3;
    }
    /** Android 9 hides MODE_IGNORED windows without rejecting addWindow as MODE_ERRORED can. */
    public static String whiteBarAppOp(boolean suppressed) {
        return suppressed ? "ignore" : "allow";
    }
    public static boolean restoreNavigation(boolean enabled, int naviMode,
            boolean panelReady, boolean ownForeground, boolean navigationIdle) {
        return enabled && naviMode == 1 && panelReady && ownForeground && navigationIdle;
    }
    public static String appOpState(String output) {
        if (output == null) return "no_output";
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*(allow|deny|ignore|default|foreground|errored)(?:;|\\s|$)").matcher(output);
        if (match.find()) return match.group(1);
        if (output.toLowerCase(java.util.Locale.ROOT).contains("permission")) return "permission_error";
        return "not_reported";
    }
    public static boolean appOpMatches(String output, boolean denied) {
        if (output == null) return false;
        return java.util.regex.Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*"
                + whiteBarAppOp(denied) + "(?:;|\\s|$)").matcher(output).find();
    }
}
