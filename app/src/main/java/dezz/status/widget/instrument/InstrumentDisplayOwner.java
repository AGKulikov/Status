package dezz.status.widget.instrument;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Android 9 `am stack list` snapshot; ambiguity never grants screen ownership. */
final class InstrumentDisplayOwner {
    private static final Pattern STACK = Pattern.compile("(?m)^Stack id=\\d+ [^\\r\\n]*\\bdisplayId=(\\d+)\\b[^\\r\\n]*$");
    private static final Pattern TOP = Pattern.compile("\\bvisible=true\\s+topActivity=ComponentInfo\\{([^}]+)}");
    static boolean owns(String output, int display, String packageName) {
        if (output == null || output.length() > 128 * 1024 || display < 0) return false;
        Matcher headers = STACK.matcher(output);
        int start = -1;
        boolean target = false, found = false;
        String component = packageName + "/dezz.status.widget.instrument.InstrumentPanelActivity";
        while (true) {
            boolean next = headers.find();
            int end = next ? headers.start() : output.length();
            if (start >= 0 && target) {
                Matcher top = TOP.matcher(output.substring(start, end));
                boolean visible = output.substring(start, end).contains("visible=true");
                boolean parsed = false;
                while (top.find()) {
                    parsed = true;
                    if (!component.equals(top.group(1))) return false;
                    found = true;
                }
                if (visible && !parsed) return false;
            }
            if (!next) return found;
            start = headers.end();
            target = Integer.toString(display).equals(headers.group(1));
        }
    }
}
