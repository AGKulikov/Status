/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only flight recorder. Diagnostic deadlines NEVER cancel or retry an application action. */
public final class CausalDiagnostics {
    private static final Object LOCK = new Object();
    private static final int MAX_PENDING = 64, MAX_STATES = 32, MAX_RECENT = 96;
    private static final AtomicLong IDS = new AtomicLong();
    private static final String SESSION = Integer.toHexString(Process.myPid()) + "-"
            + Long.toHexString(SystemClock.elapsedRealtime());
    private static final ThreadLocal<Span> ACTIVE = new ThreadLocal<>();
    private static final LinkedHashMap<String, Span> PENDING = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Observation> STATES = new LinkedHashMap<>();
    private static final ArrayDeque<Observation> RECENT = new ArrayDeque<>();
    private static final ThreadPoolExecutor CAPTURE = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "diagnostics-incident"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static volatile long epoch;
    private static long lastAutomatic = -30_000L, lastManual = -2_000L;
    private static long evictedOperations, rejectedCaptures;
    private static final Span NONE = new Span();
    private CausalDiagnostics() {}

    private static final class Observation {
        final long at;
        final String text;
        Observation(long at, String text) { this.at = at; this.text = text; }
    }

    public static String session() { return SESSION; }
    public static Span current() { Span span = ACTIVE.get(); return span == null ? NONE : span; }

    public static Span begin(String component, String detail, long overdueMs) {
        if (!DiagnosticJournal.isEnabled()) return NONE;
        Span span;
        Span evicted = null;
        synchronized (LOCK) {
            if (!DiagnosticJournal.isEnabled()) return NONE;
            span = new Span(component, detail, overdueMs, current());
            if (PENDING.size() == MAX_PENDING) {
                String first = PENDING.keySet().iterator().next();
                evicted = PENDING.remove(first); evictedOperations++;
            }
            PENDING.put(span.id, span);
        }
        if (evicted != null) emit(evicted, "tracking_evicted", "action_unchanged=true", true);
        emit(span, "queued", detail, false);
        return span;
    }

    public static final class Span {
        public final String id, root, parent;
        private final String component;
        private final long started, overdueMs, generation;
        private volatile long lastStageAt;
        private volatile String stage = "queued", detail = "", thread = "";
        private boolean finished, overdueReported;
        private Span() { id = root = parent = "none"; component = "disabled"; started = overdueMs = generation = -1; }
        private Span(String component, String detail, long overdueMs, Span previous) {
            this.id = SESSION + "/" + IDS.incrementAndGet();
            this.parent = previous.live() ? previous.id : "none";
            this.root = previous.live() ? previous.root : id;
            this.component = safe(component, 48);
            this.started = this.lastStageAt = SystemClock.uptimeMillis();
            this.overdueMs = Math.max(500, overdueMs);
            this.generation = epoch;
            this.detail = safe(detail, 360);
            this.thread = safe(Thread.currentThread().getName(), 80);
        }
        private boolean live() { return this != NONE && generation == epoch && DiagnosticJournal.isEnabled(); }
        public void stage(String name, String detail) {
            if (!live()) return;
            boolean late;
            synchronized (LOCK) {
                late = finished;
                if (!late) {
                    stage = safe(name, 64); this.detail = safe(detail, 360);
                    thread = safe(Thread.currentThread().getName(), 80); lastStageAt = SystemClock.uptimeMillis();
                }
            }
            emit(this, late ? "late_" + name : name, detail, late);
        }
        public void finish(String outcome, String detail) {
            if (!live()) return;
            synchronized (LOCK) {
                if (finished) return;
                finished = true; PENDING.remove(id);
                stage = safe(outcome, 64); this.detail = safe(detail, 360);
                lastStageAt = SystemClock.uptimeMillis();
            }
            emit(this, outcome, detail, false);
        }
        public void fail(String reason, Throwable error) {
            finish("failed", "reason=" + reason + ", error=" + failure(error) + ", effect=unobserved");
            if (live()) capture("operation_failed:" + component + ":" + reason, false);
        }
        /** Explicit propagation; never an inheritable ThreadLocal on shared executor threads. */
        public void run(Runnable action) {
            Span previous = ACTIVE.get();
            if (live()) ACTIVE.set(this); else ACTIVE.remove();
            try { action.run(); }
            finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
        }
        public <T> T call(java.util.function.Supplier<T> action) {
            Span previous = ACTIVE.get();
            if (live()) ACTIVE.set(this); else ACTIVE.remove();
            try { return action.get(); }
            finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
        }
        public Runnable wrap(Runnable action) { return () -> run(action); }
    }

    private static void emit(Span span, String stage, String detail, boolean warning) {
        String text = "trace=" + span.id + ", root=" + span.root + ", parent=" + span.parent
                + ", component=" + span.component + ", stage=" + safe(stage, 64)
                + ", elapsed_ms=" + Math.max(0, SystemClock.uptimeMillis() - span.started)
                + ", thread=" + safe(Thread.currentThread().getName(), 80) + ", " + safe(detail, 600);
        observe("operation:" + span.component, text);
        if (warning) DiagnosticJournal.warn("causal", text);
        else DiagnosticJournal.infoAsync("causal", text);
    }

    /** Only bounded, already emitted state is sampled. No Binder, shell or vehicle getters. */
    static void journalEvent(String component, String message) {
        if (component.equals("causal") || component.startsWith("incident")) return;
        if (component.startsWith("vehicle-buttons") || component.startsWith("button-action")
                || component.startsWith("instrument") || component.startsWith("dim")
                || component.startsWith("media-") || component.startsWith("steering-media")
                || component.startsWith("map-") || component.startsWith("passenger-")
                || component.startsWith("phone-state") || component.startsWith("hud")
                || component.startsWith("watchdog") || component.startsWith("spruthub.session")
                || component.startsWith("scenario") || component.equals("runtime")) observe(component, message);
    }

    public static void observe(String component, String message) {
        if (!DiagnosticJournal.isEnabled()) return;
        long now = SystemClock.uptimeMillis();
        String name = safe(component, 64), text = safe(message, 600);
        synchronized (LOCK) {
            STATES.remove(name);
            if (STATES.size() == MAX_STATES) STATES.remove(STATES.keySet().iterator().next());
            STATES.put(name, new Observation(now, text));
            if (RECENT.size() == MAX_RECENT) RECENT.removeFirst();
            RECENT.addLast(new Observation(now, name + " " + safe(text, 280)));
        }
    }

    /** Invoked on the existing watchdog worker, never on MAIN or a control/input lane. */
    static void poll() {
        if (!DiagnosticJournal.isEnabled()) return;
        SystemProcessDiagnostics.poll();
        long now = SystemClock.uptimeMillis();
        ArrayList<Span> overdue = new ArrayList<>();
        synchronized (LOCK) {
            for (Span span : PENDING.values()) {
                if (!span.finished && !span.overdueReported && now - span.started >= span.overdueMs) {
                    span.overdueReported = true; overdue.add(span);
                }
            }
        }
        for (Span span : overdue) emit(span, "operation_overdue", "last_stage=" + span.stage
                + ", stage_age_ms=" + (now - span.lastStageAt) + ", diagnostic_only=true"
                + ", action_not_cancelled=true", true);
        if (!overdue.isEmpty()) capture("operation_overdue", false);
    }

    /** A user mark bypasses the automatic cooldown, but repeated taps remain bounded. */
    public static String capture(String reason, boolean manual) {
        if (!DiagnosticJournal.isEnabled()) return "debug_disabled";
        final long requestedAt = SystemClock.uptimeMillis(), generation;
        final String id = SESSION + "/incident-" + IDS.incrementAndGet();
        synchronized (LOCK) {
            long previous = manual ? lastManual : lastAutomatic;
            if (requestedAt - previous < (manual ? 2_000L : 30_000L)) return "rate_limited";
            if (manual) lastManual = requestedAt; else lastAutomatic = requestedAt;
            generation = epoch;
        }
        DiagnosticJournal.infoAsync("incident-request", "incident=" + id + ", reason=" + safe(reason, 120)
                + ", requested_uptime_ms=" + requestedAt + ", automatic=" + !manual);
        try {
            CAPTURE.execute(() -> {
                if (generation != epoch || !DiagnosticJournal.isEnabled()) return;
                long now = SystemClock.uptimeMillis();
                String prefix = "incident=" + id + ", reason=" + safe(reason, 120)
                        + ", requested_uptime_ms=" + requestedAt + ", captured_uptime_ms=" + now
                        + ", capture_wait_ms=" + (now - requestedAt) + ", pid=" + Process.myPid();
                DiagnosticJournal.recordIncident("incident-state", prefix + "\n" + snapshot());
                try {
                    String dump = ThreadDumpFormatter.format(Looper.getMainLooper().getThread(), Thread.getAllStackTraces());
                    DiagnosticJournal.recordIncident("incident-threads", "incident=" + id + "\n" + dump);
                } catch (RuntimeException unavailable) {
                    DiagnosticJournal.recordIncident("incident-threads", "incident=" + id + ", error=" + failure(unavailable));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            synchronized (LOCK) { rejectedCaptures++; }
            DiagnosticJournal.warn("incident-request", "incident=" + id + ", capture_queue_full=true");
            return "queue_full";
        }
        return id;
    }

    public static String snapshot() {
        long now = SystemClock.uptimeMillis();
        StringBuilder text = new StringBuilder("source=last_observed; missing_or_old_state_is_unknown; "
                + "shell_ack_is_not_physical_effect\n");
        synchronized (LOCK) {
            text.append("pending=").append(PENDING.size()).append(", tracking_evicted=").append(evictedOperations)
                    .append(", capture_queued=").append(CAPTURE.getQueue().size()).append(", capture_active=").append(CAPTURE.getActiveCount())
                    .append(", capture_rejected=").append(rejectedCaptures).append('\n');
            int count = 0;
            for (Span span : PENDING.values()) {
                if (++count > 16) { text.append("pending_list_truncated=true\n"); break; }
                text.append("PENDING trace=").append(span.id).append(" root=").append(span.root)
                        .append(" component=").append(span.component).append(" phase=").append(span.stage)
                        .append(" age_ms=").append(now - span.started).append(" thread=").append(span.thread)
                        .append(' ').append(safe(span.detail, 160)).append('\n');
            }
            for (Map.Entry<String, Observation> entry : STATES.entrySet()) {
                if (text.length() > 8_000) { text.append("state_list_truncated=true\n"); break; }
                Observation state = entry.getValue();
                text.append("STATE ").append(entry.getKey()).append(" age_ms=").append(now - state.at)
                        .append(' ').append(safe(state.text, 250)).append('\n');
            }
            int skip = Math.max(0, RECENT.size() - 20), index = 0;
            for (Observation event : RECENT) {
                if (index++ < skip) continue;
                if (text.length() > 12_000) { text.append("recent_list_truncated=true\n"); break; }
                text.append("RECENT uptime_ms=").append(event.at).append(' ').append(event.text).append('\n');
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return text.append("heap_used_mb=").append((runtime.totalMemory()-runtime.freeMemory())/1_048_576L)
                .append(", heap_max_mb=").append(runtime.maxMemory()/1_048_576L)
                .append(", journal=").append(DiagnosticJournal.queueState()).toString();
    }

    static void debugChanged(boolean enabled) {
        if (enabled) return;
        synchronized (LOCK) { epoch++; PENDING.clear(); STATES.clear(); RECENT.clear(); }
        CAPTURE.getQueue().clear();
        SystemProcessDiagnostics.disabled();
    }

    public static String failure(Throwable error) {
        if (error == null) return "none";
        StringBuilder result = new StringBuilder();
        for (int i=0; error!=null && i<4; i++, error=error.getCause()) {
            if (i>0) result.append("->");
            result.append(error.getClass().getSimpleName());
        }
        return result.toString(); // No exception message, URL, command, credential or user data.
    }
    private static String safe(String text, int limit) {
        String value = text == null ? "" : text;
        if (value.length() > limit) value = value.substring(0, limit);
        return DiagnosticJournal.redact(value).replace('\n',' ').replace('\r',' ').replace('\t',' ');
    }
}
