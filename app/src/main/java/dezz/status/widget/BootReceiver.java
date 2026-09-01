/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.climate.ScreenReservationStateStore;
import dezz.status.widget.hud.HudPresentationService;
import dezz.status.widget.dim.DimMenuPanelService;
import dezz.status.widget.instrument.InstrumentDisplayLauncher;
import dezz.status.widget.launcher.MediaAutoResumeController;
import dezz.status.widget.phone.PackageReplaceBleRecoveryGate;
import dezz.status.widget.phone.PhoneConnectionJournal;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    /** Covers the complete ECARX boot/unlock/phase burst while bounding retained Intents. */
    private static final int STARTUP_QUEUE_CAPACITY = 32;
    /**
     * One temporary background-priority lane keeps boot broadcasts off the process main Looper.
     * Zero core threads means it releases its stack after the short startup burst; the queue
     * preserves phase ordering and StartupWorkCoordinator rejects stale generations.
     */
    private static final ThreadPoolExecutor STARTUP_LANE = createStartupLane();

    private static ThreadPoolExecutor createStartupLane() {
        ThreadPoolExecutor lane = new ThreadPoolExecutor(
                0, 1, 15L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(STARTUP_QUEUE_CAPACITY), task -> {
                Thread worker = new Thread(() -> {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (RuntimeException ignored) {
                    }
                    task.run();
                }, "status-boot-lane");
                worker.setDaemon(true);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
        lane.allowCoreThreadTimeOut(true);
        return lane;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Context receiverContext = app;
        // This only installs the file destination; persistent history is loaded lazily when the
        // diagnostics screen/export asks for it, so cold flash I/O cannot stall this receiver.
        PhoneConnectionJournal.initialize(receiverContext);
        Intent received = intent == null ? null : new Intent(intent);
        String receivedAction = received == null || received.getAction() == null
                ? "" : received.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(receivedAction)) {
            // Record this before admitting the new foreground host. The UI may appear immediately,
            // while only its BLE/GATT transport waits for the killed process registration to drain.
            PackageReplaceBleRecoveryGate.mark(receiverContext);
        }
        // Admit media to its dedicated exact-timer lane before submitting shared startup work.
        // Putting timer creation there let unrelated restoration postpone seconds by minutes.
        MediaAutoResumeController.armAtReceiverBoundary(receiverContext, receivedAction);
        PendingResult pending = goAsync();
        try {
            STARTUP_LANE.execute(() -> {
                try {
                    handleReceive(receiverContext, received);
                } finally {
                    pending.finish();
                }
            });
        } catch (RejectedExecutionException rejected) {
            // Never run coordinator commits on BroadcastReceiver's main thread as an overflow
            // fallback. Completing the token avoids an ANR; the action in logcat makes a rare
            // saturated boot burst diagnosable, while coordinator alarms retain durable phases.
            pending.finish();
            Log.e(TAG, "Startup queue full; rejected action="
                    + (received == null ? null : received.getAction()), rejected);
        }
    }

    private void handleReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        dezz.status.widget.diagnostics.ActionRecorder.record(
                dezz.status.widget.diagnostics.ActionRecorder.SOURCE_SERVICE,
                "BROADCAST_RECEIVED",
                dezz.status.widget.diagnostics.ActionRecorder.object(
                        "receiver", getClass().getName(), "action", action));
        if (StartupWorkCoordinator.isPhaseIntent(intent)) {
            int phase = StartupWorkCoordinator.phase(intent);
            long generation = StartupWorkCoordinator.generation(intent);
            if (StartupWorkCoordinator.deferPhaseIfNeeded(context, phase, generation)) return;
            boolean completed = false;
            try {
                if (phase == StartupWorkCoordinator.PHASE_INTEGRATION_HOST) {
                    StartupPerformanceTrace.mark("host_phase_received");
                    StartupWorkCoordinator.openInitializationBarrierForHost(
                            context, generation);
                    boolean credentialRefresh =
                            StartupWorkCoordinator.hasCredentialRefreshPending(
                                    context, generation);
                    boolean surfaceReconcile =
                            StartupWorkCoordinator.hasSurfaceReconcilePending(
                                    context, generation);
                    completed = restoreStatusWidget(
                            context, credentialRefresh, surfaceReconcile);
                    if (completed) {
                        StartupWorkCoordinator.acknowledgeHostRequests(context, generation,
                                credentialRefresh, surfaceReconcile);
                    }
                } else if (phase == StartupWorkCoordinator.PHASE_CLIMATE) {
                    completed = restoreClimateSafely(context);
                } else if (phase == StartupWorkCoordinator.PHASE_MEDIA_PLAN) {
                    MediaAutoResumeController.scheduleAfterBoot(context);
                    completed = true;
                }
            } catch (RuntimeException failure) {
                Log.e(TAG, "Startup phase " + phase + " failed", failure);
            }
            if (completed) {
                StartupWorkCoordinator.markPhaseCompleted(context, phase, generation);
            } else {
                StartupWorkCoordinator.retryPhase(context, phase, generation, 2_000L);
            }
            return;
        }
        if (WidgetServiceStarter.ACTION_RETRY.equals(action)) {
            WidgetServiceStarter.retryFromAlarm(context,
                    intent.getIntExtra(WidgetServiceStarter.EXTRA_RETRY_ATTEMPT, -1),
                    intent.getBooleanExtra(
                            WidgetServiceStarter.EXTRA_VISUAL_SURFACE_ONLY, false));
            return;
        }
        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            // Unlock opens only the Keystore-dependent connector gate. It must not rebuild the
            // Driver, HUD and Climate windows that the same boot token already restored.
            StartupWorkCoordinator.scheduleForLifecycle(context, action);
            WidgetServiceStarter.startVisibleSurfaceImmediatelyWithRetry(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d(TAG, "System lifecycle event, restoring enabled services: "
                    + action);

            // Admit the tiny visual surface before scheduleForLifecycle performs its durable
            // coordinator transaction. LOCKED_BOOT cannot start the credential-backed surface.
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || ACTION_QUICKBOOT_POWERON.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                restoreHudSurfaceImmediately(context, action);
                WidgetServiceStarter.startVisibleSurfaceImmediatelyWithRetry(context);
            }
            if (ACTION_QUICKBOOT_POWERON.equals(action)) {
                WidgetService survivingHost = WidgetService.getInstance();
                if (survivingHost != null) {
                    survivingHost.revalidateAutomaticVisualSurfaceAfterQuickBoot();
                }
            }
            StartupWorkCoordinator.scheduleForLifecycle(context, action);
        }
    }

    /**
     * The independent HUD map surface must not wait for the complete integration graph. Entering
     * Settings used to call HudPresentationService.apply() first and accidentally became the event
     * that made the map appear. Admit the saved autostart HUD at the lifecycle boundary instead;
     * the later coordinator reconcile remains idempotent and owns QuickBoot surface replacement.
     */
    private static void restoreHudSurfaceImmediately(Context context, String action) {
        try {
            Log.i(TAG, "Reconciling autostart HUD at lifecycle boundary: " + action);
            HudPresentationService.reconcileAutomaticLifecycle(context);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore autostart HUD at lifecycle boundary", failure);
        }
        try {
            InstrumentDisplayLauncher.reconcileAutomatic(context);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore instrument panel at lifecycle boundary", failure);
        }
        try {
            DimMenuPanelService.reconcileAutomatic(context);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore DIM menu at lifecycle boundary", failure);
        }
    }

    private static boolean restoreStatusWidget(Context context, boolean forceReconfigure,
                                               boolean reconcileSurfaces) {
        try {
            WidgetService current = WidgetService.getInstance();
            if (current != null) {
                if (forceReconfigure) {
                    current.reconfigureCredentialBackedIntegrationsAfterUnlock();
                }
                if (reconcileSurfaces) {
                    current.reconcileAutomaticLifecycleSurfaces();
                } else {
                    current.resumeAutomaticLifecycleIntegrationsAfterQuiet();
                }
                // The foreground host is already promoted, so deferred Application diagnostics
                // can safely resume after its exact startup barrier opened.
                StatusWidgetApplication.resumeSurfaceOwnedInitialization(context);
                return true;
            }
            // A fresh host applies current credentials and reconstructs its automatic surfaces
            // during its own staged initialization. Its bounded starter retry owns transient FGS
            // rejection, so these request flags can be acknowledged once the attempt is accepted.
            boolean startAccepted = WidgetServiceStarter.startIfNeededWithRetry(context);
            // A newly accepted FGS owns the next notification only after onCreate/startForeground.
            // If no host is required, let an already-rendered Launcher/Settings surface complete
            // its deferred diagnostics without waiting for a service that will never exist.
            if (!startAccepted) {
                StatusWidgetApplication.resumeSurfaceOwnedInitialization(context);
            }
            return true;
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore status widget", failure);
            return false;
        }
    }

    private static boolean restoreClimateSafely(Context context) {
        try {
            Preferences prefs = new Preferences(context);
            // The permanent climate panel has its own lifecycle and does not depend on the main
            // status widget being enabled. apply() selects compact/reserved mode and restores the
            // saved display/geometry after every supported boot sequence.
            if (shouldReconcileClimate(context, prefs)) {
                Log.i(TAG, "Restoring permanent climate panel");
                ClimatePanelService.apply(context);
            }
            return true;
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not restore permanent climate panel", failure);
            return false;
        }
    }

    private static boolean shouldReconcileClimate(Context context, Preferences preferences) {
        return preferences.climatePanelEnabled.get()
                || new ScreenReservationStateStore(context).hasManagedReservation();
    }
}
