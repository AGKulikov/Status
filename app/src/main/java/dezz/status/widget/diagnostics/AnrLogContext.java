/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Associates Reason/PID lines with an ANR only within the same system logger thread. */
final class AnrLogContext {
    private static final Pattern LINE=Pattern.compile("^\\s*([0-9.]+)\\s+(\\d+)\\s+(\\d+)\\s+[A-Z]\\s+ActivityManager\\s*:\\s?(.*)$");
    private String owner, incident;
    private double until;
    String accept(String line) {
        if(line==null || line.length()>8192)return null;
        Matcher m=LINE.matcher(line);if(!m.matches())return null;
        double at;try{at=Double.parseDouble(m.group(1));}catch(NumberFormatException invalid){return null;}
        String thread=m.group(2)+":"+m.group(3), body=m.group(4);
        String event=SystemProcessEvent.parse(body);
        if(event!=null && event.startsWith("event=anr,")){
            owner=thread;incident=event+", source_wall_ms="+(long)(at*1000);until=at+5;
            return incident+", details=pending";
        }
        if(owner==null || !owner.equals(thread) || at>until || at<until-5)return null;
        if(body.startsWith("ANR in ")){owner=null;return null;}
        if(body.startsWith("Reason:") || body.matches("PID: \\d+") || body.startsWith("Load:"))
            return incident+", system_detail="+body;
        return null;
    }
}
