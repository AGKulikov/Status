/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.LinkedHashMap;

/** Same-process correlation only. Never authorizes actions or reads arbitrary external extras. */
public final class DiagnosticIntentTrace {
    private static final String EXTRA = "ru.natro.statuswidget.internal.DIAGNOSTIC_TRACE";
    private static final LinkedHashMap<String, Ticket> TICKETS = new LinkedHashMap<>();
    private static final class Ticket {
        final CausalDiagnostics.Span span;
        final long at = SystemClock.uptimeMillis();
        Ticket(CausalDiagnostics.Span span) { this.span = span; }
    }
    private DiagnosticIntentTrace() {}
    public static Intent attach(Context context, Intent intent) {
        if (!DiagnosticJournal.isEnabled() || intent == null) return intent;
        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
        if (!context.getPackageName().equals(target)) return intent;
        CausalDiagnostics.Span span = CausalDiagnostics.begin("intent-delivery", "target=own_package", 15000);
        synchronized (TICKETS) {
            if (TICKETS.size() >= 64) {
                Ticket evicted = TICKETS.remove(TICKETS.keySet().iterator().next());
                evicted.span.finish("tracking_evicted", "delivery=unknown");
            }
            TICKETS.put(span.id, new Ticket(span));
        }
        intent.putExtra(EXTRA, span.id);
        return intent;
    }
    public static void receive(Intent intent, Runnable body) {
        Ticket ticket = null;
        if (intent != null && DiagnosticJournal.isEnabled()) try {
            String key = intent.getStringExtra(EXTRA);
            synchronized (TICKETS) { ticket = TICKETS.remove(key); }
        } catch (RuntimeException ignored) { /* An untrusted extra never changes dispatch. */ }
        if (ticket == null) { body.run(); return; }
        long age = SystemClock.uptimeMillis() - ticket.at;
        if (age > 15000) { ticket.span.finish("delivery_late", "age_ms=" + age); body.run(); return; }
        CausalDiagnostics.Span trace = ticket.span;
        trace.stage("delivered", "age_ms=" + age);
        try { trace.run(body); trace.finish("receiver_returned", "authorization_and_effect_separate=true"); }
        catch (RuntimeException failure) { trace.fail("receiver_exception", failure); throw failure; }
    }
    static void clear() { synchronized (TICKETS) { TICKETS.clear(); } }
}
