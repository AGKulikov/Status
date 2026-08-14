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
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Narrow, evidence-based bridge to the exported integration surface in HWGPS 4.5.27.
 *
 * <p>No HWGPS private service is started directly. Find-me uses its transparent/no-history
 * exported activity; route state uses the app's event broadcast and bounded one-shot confirmation
 * requests. There is no polling loop.</p>
 */
public final class HwgpsIntegration {
    public static final String PACKAGE_NAME = "org.astpepper.hwgps";
    public static final String FIND_ME_ACTIVITY =
            "org.astpepper.hwgps.FindMeActivity";
    public static final String STATE_REQUEST_RECEIVER =
            "org.astpepper.hwgps.receivers.GeodataRequestReceiver";
    public static final String ACTION_FIND_ME =
            "org.astpepper.hwgps.action.FIND_ME";
    public static final String ACTION_FIX_STATE = "hwgps.fix.state";
    public static final String ACTION_FIX_STATE_REQUEST = "hwgps.fix.state.request";
    public static final String EXTRA_FIX = "fix";

    public interface Listener {
        void onRouteStateChanged(@NonNull HwgpsRouteStatePolicy.State state);
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
    public static final class RouteStateSubscription {
        private final Context context;
        private final Listener listener;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
        private boolean registered;
        private final Runnable deadlineRunnable = () -> {
            if (!registered) return;
            apply(tracker.evaluate(SystemClock.elapsedRealtime()));
        };
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!registered || intent == null
                        || !ACTION_FIX_STATE.equals(intent.getAction())) return;
                apply(tracker.accept(intent.getStringExtra(EXTRA_FIX),
                        SystemClock.elapsedRealtime()));
            }
        };
        @NonNull private HwgpsRouteStatePolicy.State state =
                HwgpsRouteStatePolicy.State.UNAVAILABLE;

        public RouteStateSubscription(@NonNull Context source, @NonNull Listener listener) {
            this.context = applicationContext(source);
            this.listener = listener;
        }

        public void start() {
            if (registered) return;
            tracker.reset();
            state = HwgpsRouteStatePolicy.State.UNAVAILABLE;
            try {
                ContextCompat.registerReceiver(context, receiver,
                        new IntentFilter(ACTION_FIX_STATE), ContextCompat.RECEIVER_EXPORTED);
                registered = true;
            } catch (RuntimeException ignored) {
                publish(HwgpsRouteStatePolicy.State.UNAVAILABLE);
                return;
            }
            if (!requestSnapshot()) {
                tracker.reset();
                publish(HwgpsRouteStatePolicy.State.UNAVAILABLE);
            }
        }

        public void stop() {
            if (registered) {
                registered = false;
                try { context.unregisterReceiver(receiver); }
                catch (RuntimeException ignored) {}
            }
            mainHandler.removeCallbacks(deadlineRunnable);
            tracker.reset();
            state = tracker.state();
        }

        @NonNull public HwgpsRouteStatePolicy.State state() { return state; }

        private void publish(@NonNull HwgpsRouteStatePolicy.State next) {
            if (state == next) return;
            state = next;
            listener.onRouteStateChanged(next);
        }

        private void apply(@NonNull HwgpsRouteStateTracker.Update update) {
            if (!registered) return;
            mainHandler.removeCallbacks(deadlineRunnable);
            if (update.deadlineAtMs != HwgpsRouteStateTracker.NO_DEADLINE && registered) {
                long delayMs = Math.max(0L,
                        update.deadlineAtMs - SystemClock.elapsedRealtime());
                mainHandler.postDelayed(deadlineRunnable, delayMs);
            }
            if (update.requestSnapshot && !requestSnapshot()) {
                tracker.reset();
                mainHandler.removeCallbacks(deadlineRunnable);
                publish(HwgpsRouteStatePolicy.State.UNAVAILABLE);
                return;
            }
            publish(update.state);
        }

        private boolean requestSnapshot() {
            try {
                context.sendBroadcast(new Intent(ACTION_FIX_STATE_REQUEST).setComponent(
                        new ComponentName(PACKAGE_NAME, STATE_REQUEST_RECEIVER)));
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    @NonNull
    private static Context applicationContext(@NonNull Context source) {
        Context app = source.getApplicationContext();
        return app == null ? source : app;
    }
}
