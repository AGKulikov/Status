/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** MConfig's typed command grammar. Colons inside values and explicit flags are preserved. */
public final class ButtonIntentSpec {
    public final String target, packageName;
    public final int flags;
    public final Map<String, Object> extras;
    private ButtonIntentSpec(String target, String packageName, int flags, Map<String, Object> extras) {
        this.target = target; this.packageName = packageName; this.flags = flags;
        this.extras = Collections.unmodifiableMap(extras);
    }
    public static ButtonIntentSpec parse(String command, String packageName, boolean activity) {
        String[] items = (command == null ? "" : command).split(",", -1);
        String target = items[0].trim();
        if (target.isEmpty()) throw new IllegalArgumentException("Не указана команда");
        LinkedHashMap<String, Object> extras = new LinkedHashMap<>();
        int flags = activity ? 0x10000000 : 0x11000000;
        for (int i = 1; i < items.length; i++) {
            String[] part = items[i].trim().split(":", 3);
            if (part.length < 3) continue;
            try {
                switch (part[0]) {
                    case "es": extras.put(part[1], part[2]); break;
                    case "ei": extras.put(part[1], Integer.parseInt(part[2])); break;
                    case "ez": extras.put(part[1], Boolean.parseBoolean(part[2])); break;
                    // ef overrides flags; it is NOT a floating-point extra.
                    case "ef": flags = (int) Long.parseLong(part[2]); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { /* Reference skips invalid numeric extras. */ }
        }
        return new ButtonIntentSpec(target, packageName == null ? "" : packageName.trim(), flags, extras);
    }
}
