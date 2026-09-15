/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

public final class InstrumentOemPolicy {
    private InstrumentOemPolicy() {}
    /** A missing NaviMode never grants authority to suppress the stock overlay. */
    public static boolean suppressWhiteBar(boolean enabled, int naviMode) {
        return enabled && naviMode == 3;
    }
    public static boolean appOpMatches(String output, boolean denied) {
        if (output == null) return false;
        return java.util.regex.Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*"
                + (denied ? "deny" : "allow") + "(?:;|\\s|$)").matcher(output).find();
    }
}
