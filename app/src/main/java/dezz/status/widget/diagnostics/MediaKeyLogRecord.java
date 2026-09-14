/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts key fields only. Never persists arbitrary system messages, track titles or text input. */
final class MediaKeyLogRecord {
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+)\\s+(\\d{1,9})\\s+(\\d{1,9})\\s+[VDIWEF]\\s+([^:]+):\\s*(.*)$");
    private static final Pattern KEY = Pattern.compile("\\bkeyCode\\s*[=:]\\s*(KEYCODE_[A-Z_]+|[0-9]+)\\b");
    private static final Pattern ACTION = Pattern.compile("\\baction\\s*[=:]\\s*(ACTION_DOWN|ACTION_UP|0|1)\\b");
    final String timestamp, tag, stage;
    final int keyCode, action, deviceId, repeat, sourcePid, sourceTid, callerPid, callerUid;
    final boolean deviceIdPresent;
    final long eventTime, downTime;

    private MediaKeyLogRecord(String timestamp, String tag, int pid, int tid,
                              int keyCode, int action, String body) {
        this.timestamp = timestamp; this.tag = tag; this.keyCode = keyCode; this.action = action;
        sourcePid = pid; sourceTid = tid;
        long device = number(body, "deviceId", Long.MIN_VALUE);
        deviceIdPresent = device >= Integer.MIN_VALUE && device <= Integer.MAX_VALUE;
        deviceId = deviceIdPresent ? (int) device : -1;
        repeat = (int) number(body, "repeatCount", 0);
        eventTime = number(body, "eventTime", -1);
        downTime = number(body, "downTime", -1);
        callerPid = (int) number(body, "pid", -1);
        callerUid = (int) number(body, "uid", -1);
        stage = stage(tag, body);
    }

    static MediaKeyLogRecord parse(String line) {
        if (line == null || line.length() > 4096) return null;
        Matcher header = HEADER.matcher(line);
        if (!header.matches()) return null;
        String tag = header.group(4).trim(), body = header.group(5);
        if (!allowedTag(tag)) return null;
        Matcher key = KEY.matcher(body), action = ACTION.matcher(body);
        if (!key.find() || !action.find()) return null;
        int code = keyCode(key.group(1));
        if (code < 0) return null;
        String a = action.group(1);
        return new MediaKeyLogRecord(header.group(1), tag,
                Integer.parseInt(header.group(2)), Integer.parseInt(header.group(3)), code,
                "0".equals(a) || "ACTION_DOWN".equals(a) ? 0 : 1, body);
    }

    /** Literal Android 9 stages only. Unknown vendor text stays unknown, never an invented ACK. */
    private static String stage(String tag, String body) {
        if (!"MediaSessionService".equals(tag)) return "key_record";
        if (body.startsWith("dispatchMediaKeyEvent,")) return "system_entry";
        if (body.startsWith("The media key listener is timed-out for ")) return "listener_timeout";
        if (body.startsWith("Failed to send ")) return "send_failed";
        if (body.startsWith("Send ") && body.endsWith(" to the media key listener")) return "listener_forward";
        if (body.startsWith("Sending ")) {
            if (body.contains(" to the last known PendingIntent ")
                    || body.contains(" to the restored intent ")) return "broadcast_send";
            if (body.contains(" to ")) return "session_send";
        }
        return "key_record";
    }

    static boolean allowedTag(String tag) {
        return "MConfig".equals(tag) || "KeysConfigReceiver".equals(tag)
                || "InputService".equals(tag) || "KeyPolicyImpl".equals(tag)
                || "XSFInputService".equals(tag) || "ECarXCarHardKeyService".equals(tag)
                || "MediaSessionService".equals(tag) || "MediaSessionRecord".equals(tag)
                || "AudioService".equals(tag);
    }

    private static int keyCode(String raw) {
        switch (raw) {
            case "KEYCODE_MEDIA_PLAY_PAUSE": case "85": return 85;
            case "KEYCODE_MEDIA_STOP": case "86": return 86;
            case "KEYCODE_MEDIA_NEXT": case "87": return 87;
            case "KEYCODE_MEDIA_PREVIOUS": case "88": return 88;
            case "KEYCODE_MEDIA_REWIND": case "89": return 89;
            case "KEYCODE_MEDIA_FAST_FORWARD": case "90": return 90;
            case "KEYCODE_MEDIA_PLAY": case "126": return 126;
            case "KEYCODE_MEDIA_PAUSE": case "127": return 127;
            default: return -1;
        }
    }

    private static long number(String body, String field, long fallback) {
        Matcher match = Pattern.compile("\\b" + field + "\\s*[=:]\\s*(-?[0-9]+)\\b").matcher(body);
        if (!match.find()) return fallback;
        try { return Long.parseLong(match.group(1)); }
        catch (NumberFormatException invalid) { return fallback; }
    }
}
