/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Narrow, evidence-based bridge to the exported integration surface in HWGPS 4.5.27.
 *
 * <p>No HWGPS private service is started directly. Find-me uses its transparent/no-history
 * exported activity. DR availability follows the exact fix-state set accepted by HWGPS' own
 * Find-me widget, delivered through its event broadcast and a bounded initial snapshot handshake.
 * There is no navigation-app dependency and no polling loop.</p>
 */
public final class HwgpsIntegration {
    private static final String JOURNAL_COMPONENT = "hwgps.dr";
    private static final int INITIAL_SNAPSHOT_MAX_ATTEMPTS = 3;
    private static final long[] INITIAL_SNAPSHOT_RETRY_DELAYS_MS = {1_500L, 3_000L, 5_000L};
    /** HWGPS emits a short notFixed edge after many valid DR fixes; it is not a route loss. */
    private static final long DR_INACTIVE_CONFIRM_MS = 2_500L;
    public static final String PACKAGE_NAME = "org.astpepper.hwgps";
    public static final String FIND_ME_ACTIVITY =
            "org.astpepper.hwgps.FindMeActivity";
    public static final String STATE_REQUEST_RECEIVER =
            "org.astpepper.hwgps.receivers.GeodataRequestReceiver";
    public static final String ACTION_FIND_ME =
            "org.astpepper.hwgps.action.FIND_ME";
    /** Verified external status contract: result extra {@code dr_active}. */
    public static final String ACTION_FIND_ME_STATUS =
            "org.astpepper.hwgps.action.FIND_ME_STATUS";
    public static final String EXTRA_DR_ACTIVE = "dr_active";
    public static final String ACTION_FIX_STATE = "hwgps.fix.state";
    public static final String ACTION_FIX_STATE_REQUEST = "hwgps.fix.state.request";
    public static final String EXTRA_FIX = "fix";

    public interface Listener {
        void onDrStateChanged(@NonNull HwgpsDrStatePolicy.State state);
    }

    private HwgpsIntegration() {}

    /** Launches only HWGPS' transparent, no-history command activity. */
    public static boolean requestFindMe(@NonNull Context source) {
        Context context = applicationContext(source);
        ComponentName component = new ComponentName(PACKAGE_NAME, FIND_ME_ACTIVITY);
        try {
            context.getPackageManager().getActivityInfo(component, 0);
            Intent command = new Intent(ACTION_FIND_ME)
                    .setComponent(component)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(command);
            return true;
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException
                 | SecurityException | IllegalStateException error) {
            return false;
        }
    }

    /** Event-driven subscription owned by a scenario resolver only while the source is in use. */
    public static final class DrStateSubscription {
        private final Context context;
        private final Listener listener;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private boolean registered;
        @NonNull private HwgpsDrStatePolicy.State state =
                HwgpsDrStatePolicy.State.UNAVAILABLE;
        private int initialSnapshotAttempts;
        private boolean initialSnapshotResponseSeen;
        @NonNull private HwgpsDrStatePolicy.State pendingState =
                HwgpsDrStatePolicy.State.UNAVAILABLE;
        private final Runnable confirmInactive = () -> {
            if (!registered || state != HwgpsDrStatePolicy.State.DR_ACTIVE
                    || pendingState == HwgpsDrStatePolicy.State.DR_ACTIVE) return;
            HwgpsDrStatePolicy.State confirmed = pendingState;
            pendingState = HwgpsDrStatePolicy.State.UNAVAILABLE;
            publish(confirmed);
        };
        private final Runnable initialSnapshotRetry = () -> {
            if (!registered || initialSnapshotResponseSeen) return;
            if (initialSnapshotAttempts >= INITIAL_SNAPSHOT_MAX_ATTEMPTS) {
                DiagnosticJournal.warn(JOURNAL_COMPONENT,
                        "HWGPS did not answer the bounded initial state handshake");
                return;
            }
            requestInitialSnapshot();
        };
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!registered || intent == null) return;
                if (ACTION_FIX_STATE.equals(intent.getAction())) {
                    String raw = intent.getStringExtra(EXTRA_FIX);
                    if (!initialSnapshotResponseSeen) {
                        initialSnapshotResponseSeen = true;
                        mainHandler.removeCallbacks(initialSnapshotRetry);
                    }
                    acceptObservedState(HwgpsDrStatePolicy.classify(raw));
                }
            }
        };

        public DrStateSubscription(@NonNull Context source, @NonNull Listener listener) {
            this.context = applicationContext(source);
            this.listener = listener;
        }

        public void start() {
            if (registered) return;
            state = HwgpsDrStatePolicy.State.UNAVAILABLE;
            initialSnapshotAttempts = 0;
            initialSnapshotResponseSeen = false;
            try {
                IntentFilter filter = new IntentFilter(ACTION_FIX_STATE);
                ContextCompat.registerReceiver(context, receiver,
                        filter, ContextCompat.RECEIVER_EXPORTED);
                registered = true;
            } catch (RuntimeException ignored) {
                publish(HwgpsDrStatePolicy.State.UNAVAILABLE);
                return;
            }
            DiagnosticJournal.info(JOURNAL_COMPONENT, "DR subscription started");
            requestInitialSnapshot();
        }

        public void stop() {
            if (registered) {
                registered = false;
                try { context.unregisterReceiver(receiver); }
                catch (RuntimeException ignored) {}
            }
            mainHandler.removeCallbacks(initialSnapshotRetry);
            mainHandler.removeCallbacks(confirmInactive);
            state = HwgpsDrStatePolicy.State.UNAVAILABLE;
            pendingState = HwgpsDrStatePolicy.State.UNAVAILABLE;
            initialSnapshotAttempts = 0;
            initialSnapshotResponseSeen = false;
        }

        @NonNull public HwgpsDrStatePolicy.State state() { return state; }

        private void acceptObservedState(@NonNull HwgpsDrStatePolicy.State next) {
            if (next == HwgpsDrStatePolicy.State.DR_ACTIVE) {
                mainHandler.removeCallbacks(confirmInactive);
                pendingState = HwgpsDrStatePolicy.State.UNAVAILABLE;
                publish(next);
                return;
            }
            if (state != HwgpsDrStatePolicy.State.DR_ACTIVE) {
                publish(next);
                return;
            }
            pendingState = next;
            mainHandler.removeCallbacks(confirmInactive);
            mainHandler.postDelayed(confirmInactive, DR_INACTIVE_CONFIRM_MS);
        }

        private void publish(@NonNull HwgpsDrStatePolicy.State next) {
            if (state == next) return;
            DiagnosticJournal.info(JOURNAL_COMPONENT,
                    "DR scenario state " + state + " -> " + next);
            state = next;
            listener.onDrStateChanged(next);
        }

        private boolean requestSnapshot() {
            try {
                // Target the exact exported receiver verified in HWGPS 4.5.27. This avoids
                // implicit-broadcast delivery differences on the Android 9 head unit.
                context.sendBroadcast(new Intent(ACTION_FIX_STATE_REQUEST).setComponent(
                        new ComponentName(PACKAGE_NAME, STATE_REQUEST_RECEIVER)));
                DiagnosticJournal.debug(JOURNAL_COMPONENT, "requested current HWGPS fix state");
                return true;
            } catch (RuntimeException ignored) {
                DiagnosticJournal.warn(JOURNAL_COMPONENT,
                        "current-state request was rejected: "
                                + ignored.getClass().getSimpleName());
                return false;
            }
        }

        private void requestInitialSnapshot() {
            if (!registered || initialSnapshotResponseSeen
                    || initialSnapshotAttempts >= INITIAL_SNAPSHOT_MAX_ATTEMPTS) return;
            int attempt = ++initialSnapshotAttempts;
            requestSnapshot();
            mainHandler.removeCallbacks(initialSnapshotRetry);
            if (initialSnapshotResponseSeen) return;
            mainHandler.postDelayed(initialSnapshotRetry,
                    INITIAL_SNAPSHOT_RETRY_DELAYS_MS[attempt - 1]);
        }

    }

    @NonNull
    private static Context applicationContext(@NonNull Context source) {
        Context app = source.getApplicationContext();
        return app == null ? source : app;
    }
}
