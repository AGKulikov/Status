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
import android.util.Log;

import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.climate.ScreenReservationStateStore;
import dezz.status.widget.launcher.MediaAutoResumeController;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
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
                    StartupWorkCoordinator.openInitializationBarrierForHost(
                            context, generation);
                    StatusWidgetApplication.ensureUnlockedRuntimeInitialized(context);
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
                    intent.getIntExtra(WidgetServiceStarter.EXTRA_RETRY_ATTEMPT, -1));
            return;
        }
        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            // Unlock opens only the Keystore-dependent connector gate. It must not rebuild the
            // Driver, HUD and Climate windows that the same boot token already restored.
            StartupWorkCoordinator.scheduleForLifecycle(context, action);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d(TAG, "System lifecycle event, restoring enabled services: "
                    + action);

            // LOCKED_BOOT/BOOT/QUICKBOOT often arrive close together on ECARX. Merge them into
            // one quiet, alarm-backed lane instead of launching four foreground services and
            // every radio/vendor integration from the receiver's main Looper at once.
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                MediaAutoResumeController.captureBootHistorySnapshot(context, action);
            }
            if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || ACTION_QUICKBOOT_POWERON.equals(action)) {
                WidgetService survivingHost = WidgetService.getInstance();
                if (survivingHost != null) survivingHost.enterAutomaticLifecycleQuiet();
            }
            StartupWorkCoordinator.scheduleForLifecycle(context, action);
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
                if (reconcileSurfaces) current.reconcileAutomaticLifecycleSurfaces();
                current.resumeAutomaticLifecycleIntegrationsAfterQuiet();
                return true;
            }
            // A fresh host applies current credentials and reconstructs its automatic surfaces
            // during its own staged initialization. Its bounded starter retry owns transient FGS
            // rejection, so these request flags can be acknowledged once the attempt is accepted.
            WidgetServiceStarter.startIfNeededWithRetry(context);
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
