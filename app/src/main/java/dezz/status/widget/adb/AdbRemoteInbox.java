/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
/** Bounded, process-local handoff from the paired LAN client to the visible terminal. */
public final class AdbRemoteInbox {
    private static final AtomicReference<String> draft = new AtomicReference<>();
    private static final List<String> journal = new ArrayList<>();
    public static void draft(String value) { draft.set(value); }
    public static String takeDraft() { return draft.getAndSet(null); }
    public static synchronized void log(String value) {
        journal.add(value.length() > 32768 ? value.substring(0, 32768) + "\n…" : value);
        while (journal.size() > 20) journal.remove(0);
    }
    public static synchronized List<String> drain() { List<String> values = new ArrayList<>(journal); journal.clear(); return values; }
    private AdbRemoteInbox() {}
}
