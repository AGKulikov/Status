/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Always reserves the first stack for main, even in a process with hundreds of workers. */
final class ThreadDumpFormatter {
    private ThreadDumpFormatter() {}

    static String format(Thread main, Map<Thread, StackTraceElement[]> stacks) {
        Map<Thread, StackTraceElement[]> ordered = new LinkedHashMap<>();
        StackTraceElement[] mainStack = stacks.get(main);
        ordered.put(main, mainStack == null ? main.getStackTrace() : mainStack);
        // Reserve the framework writer before arbitrary Binder/pool workers. A main thread in
        // QueuedWork.waitToFinish is not enough: its executor's stack must survive the dump cap.
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            String name = entry.getKey().getName();
            if (name.equals("queued-work") || name.startsWith("QueuedWork")) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            String name = entry.getKey().getName();
            if (name.startsWith("steering-media-") || name.startsWith("media-key-observer")
                    || name.startsWith("media-session-observer") || name.equals("status-input-journal")
                    || name.equals("status-journal-writer") || name.equals("media-key-system-log")
                    || name.equals("status-action-journal") || name.startsWith("diagnostics-")
                    || containsWriterFrame(entry.getValue())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        ordered.putAll(stacks);
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : ordered.entrySet()) {
            if (++count > 24) break;
            Thread thread = entry.getKey();
            String name = thread.getName();
            if (name.length() > 100) name = name.substring(0, 100);
            String header = "THREAD " + name + " state=" + thread.getState() + '\n';
            if (result.length() + header.length() > 14_000) break;
            result.append(header);
            int stackStart = result.length();
            int remaining = Math.min(24, ordered.size()) - count;
            int stackBudget = Math.min(3_200, 14_000 - result.length() - remaining * 160);
            StackTraceElement[] stack = entry.getValue();
            for (int i = 0; i < stack.length && i < 48; i++) {
                String line = "  at " + stack[i] + '\n';
                if (result.length() - stackStart + line.length() > stackBudget) break;
                result.append(line);
            }
        }
        return result.toString();
    }

    private static boolean containsWriterFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String owner = frame.getClassName();
            if (owner.equals("android.app.QueuedWork")
                    || owner.startsWith("android.app.SharedPreferencesImpl")
                    || owner.endsWith(".ActionRecorder") || owner.endsWith(".DiagnosticJournal")) {
                return true;
            }
        }
        return false;
    }
}
