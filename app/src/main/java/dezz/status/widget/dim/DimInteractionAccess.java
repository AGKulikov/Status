/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.Context;
import android.os.SystemClock;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Process owner: the vendor factory allocates a receiver on EVERY create(), not a singleton. */
public final class DimInteractionAccess {
    private static Object interaction;
    private static Class<?> type;
    private static long retryAfter;
    private static int attempts;

    private DimInteractionAccess() {}

    public static synchronized Object menu(Context context) throws Exception {
        if (interaction == null) {
            long now = SystemClock.elapsedRealtime();
            if (now < retryAfter) throw new IllegalStateException("DIM initialization cooling down");
            // A constructor may register resources before throwing. Never spin on partial failure.
            retryAfter = now + 60_000L;
            attempts++;
            try {
                type = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
                Context app = context.getApplicationContext();
                if (app == null) throw new IllegalStateException("Application context unavailable");
                interaction = type.getMethod("create", Context.class).invoke(null, app);
                if (interaction == null) throw new IllegalStateException("DIM interaction missing");
                DiagnosticJournal.infoAsync("dim-subscription", "shared_owner_created attempts=" + attempts);
            } catch (Exception | LinkageError failure) {
                DiagnosticJournal.warn("dim-subscription", "shared_owner_failed attempts=" + attempts
                        + ", error=" + failure.getClass().getSimpleName());
                throw failure;
            }
        }
        // Keep the owner through transient menu/read failures. The SDK owns its reconnect path.
        // Consumers unregister only their own callbacks, never this process-wide connection.
        return type.getMethod("getDimMenuInteraction").invoke(interaction);
    }
}
