package dezz.status.hudlab;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/* loaded from: classes4.dex */
final class ClusterSignalSnapshot {
    final long capturedAtElapsedMs;
    final String phase;
    final Map<String, String> values;

    ClusterSignalSnapshot(String phase, long capturedAtElapsedMs, Map<String, String> values) {
        this.phase = phase;
        this.capturedAtElapsedMs = capturedAtElapsedMs;
        this.values = Collections.unmodifiableMap(new LinkedHashMap(values));
    }

    String formatAgainst(ClusterSignalSnapshot baseline) {
        StringBuilder out = new StringBuilder();
        long relativeMs = baseline == null ? this.capturedAtElapsedMs : this.capturedAtElapsedMs - baseline.capturedAtElapsedMs;
        out.append(this.phase).append(" · t=").append(relativeMs >= 0 ? "+" : "").append(relativeMs).append(" ms");
        for (Map.Entry<String, String> entry : this.values.entrySet()) {
            String before = baseline == null ? null : baseline.values.get(entry.getKey());
            boolean changed = (before == null || before.equals(entry.getValue())) ? false : true;
            out.append("\n  ").append(changed ? "★ " : "  ").append(entry.getKey()).append('=').append(entry.getValue());
            if (changed) {
                out.append("  (до: ").append(before).append(')');
            }
        }
        return out.toString();
    }
}
