/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import java.util.regex.*;
/** Extracts only allowlisted process lifecycle facts, never raw Intent/reason/exception text. */
final class SystemProcessEvent {
    private static final String NAME="(?:ru\\.natro\\.statuswidget|com\\.ecarx\\.(?:dimmenu|hud)|ecarx\\.xsf\\.[a-zA-Z0-9_.]+)(?::[a-zA-Z0-9_.]+)?";
    private static final Pattern START=Pattern.compile("\\bStart proc (\\d+):("+NAME+")(?:/|\\s)");
    private static final Pattern DIED=Pattern.compile("\\bProcess ("+NAME+") \\(pid (\\d+)\\) has died");
    private static final Pattern KILL=Pattern.compile("\\bKilling (\\d+):("+NAME+")(?:/|\\s)");
    private static final Pattern ANR=Pattern.compile("\\bANR in ("+NAME+")(?:\\s|$|\\()");
    static String parse(String line) {
        if(line==null||line.length()>4096)return null;
        Matcher match=START.matcher(line);if(match.find())return "event=process_start, package="+match.group(2)+", pid="+match.group(1);
        match=DIED.matcher(line);if(match.find())return "event=process_died, package="+match.group(1)+", pid="+match.group(2);
        match=KILL.matcher(line);if(match.find())return "event=process_killed, package="+match.group(2)+", pid="+match.group(1)+", reason=not_collected";
        match=ANR.matcher(line);if(match.find())return "event=anr, package="+match.group(1)+", cause=unproven";
        return null;
    }
}
