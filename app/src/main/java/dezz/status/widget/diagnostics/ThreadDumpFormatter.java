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
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            String name = entry.getKey().getName();
            if (name.startsWith("steering-media-") || name.equals("status-input-journal")) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        ordered.putAll(stacks);
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : ordered.entrySet()) {
            if (++count > 24) break;
            Thread thread = entry.getKey();
            String header = "THREAD " + thread.getName() + " state=" + thread.getState() + '\n';
            if (result.length() + header.length() > 14_000) break;
            result.append(header);
            StackTraceElement[] stack = entry.getValue();
            for (int i = 0; i < stack.length && i < 48; i++) {
                String line = "  at " + stack[i] + '\n';
                if (result.length() + line.length() > 14_000) return result.toString();
                result.append(line);
            }
        }
        return result.toString();
    }
}
