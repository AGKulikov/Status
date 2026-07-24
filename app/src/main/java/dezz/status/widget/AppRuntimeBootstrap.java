/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.climate.ScreenReservationStateStore;
import dezz.status.widget.shell.PrivilegedShell;

/**
 * Runtime startup shared by both the unified settings root and the legacy status-row editor.
 *
 * <p>The application icon now opens {@link SettingsHubActivity}, but opening a different screen
 * must not silently skip the recovery work that historically lived in {@link MainActivity}:
 * restarting an enabled overlay after process death, reconciling the global climate reservation,
 * surfacing a crash report once and granting head-unit permissions through the existing trusted
 * loopback shell when the vendor Settings application cannot do it.</p>
 */
public final class AppRuntimeBootstrap {
    private static final String TAG = "AppRuntimeBootstrap";

    private AppRuntimeBootstrap() {
    }

    public static void run(@NonNull AppCompatActivity activity,
                           @NonNull Preferences preferences) {
        reconcileServices(activity, preferences);

        maybeShowCrashReport(activity);
        tryAutoGrant(activity.getApplicationContext(), preferences);
    }

    /**
     * Re-applies process-independent runtime state after returning from a system permission screen.
     *
     * <p>Starting an already-running foreground service is idempotent, while the climate apply
     * action deliberately asks its live service to retry overlay/reservation setup. This matters
     * when overlay or location access was granted without recreating the settings Activity.</p>
     */
    static void reconcileServices(@NonNull Context context,
                                  @NonNull Preferences preferences) {
        Context appContext = context.getApplicationContext();
        if (preferences.widgetEnabled.get()
                && Permissions.allPermissionsGranted(appContext)
                && !WidgetService.isRunning()) {
            try {
                ContextCompat.startForegroundService(appContext,
                        new Intent(appContext, WidgetService.class));
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not start enabled widget service", error);
            }
        }

        if (preferences.climatePanelEnabled.get()
                || new ScreenReservationStateStore(appContext).hasManagedReservation()) {
            try {
                ClimatePanelService.apply(appContext);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not reconcile climate panel service", error);
            }
        }
    }

    private static void tryAutoGrant(@NonNull Context appContext,
                                     @NonNull Preferences preferences) {
        PrivilegedShell.Request.Builder request =
                PrivilegedShell.Request.forPackage(appContext.getPackageName());
        boolean any = false;

        if (!Permissions.checkOverlayPermission(appContext)) {
            request.withOverlay();
            any = true;
        }
        if (!Permissions.checkForMissingForegroundPermissions(appContext).isEmpty()) {
            request.withForegroundLocation();
            any = true;
        }
        if (!Permissions.isBackgroundLocationGranted(appContext)) {
            request.withBackgroundLocation();
            any = true;
        }
        if (!Permissions.isUsageAccessGranted(appContext)) {
            request.withUsageAccess();
            any = true;
        }
        boolean mediaPresent = BrickType.parseOrder(preferences.brickOrder.get())
                .contains(BrickType.MEDIA);
        if (mediaPresent && !Permissions.isNotificationAccessGranted(appContext)) {
            request.withNotificationListener(PrivilegedShell.notificationListenerComponent(
                    appContext.getPackageName(), MediaNotificationListener.class));
            any = true;
        }
        String accessibilityComponent = PrivilegedShell.accessibilityServiceComponent(
                appContext.getPackageName(), WidgetAccessibilityService.class);
        if (!Permissions.isAccessibilityServiceEnabled(appContext, accessibilityComponent)) {
            request.withAccessibility(accessibilityComponent);
            any = true;
        }
        if (!any) return;

        PrivilegedShell.get(appContext).ensurePrivileges(request.build(), result -> {
            if (!result.transportAvailable) return;
            if (result.anyGranted()) {
                Toast.makeText(appContext,
                        appContext.getString(R.string.privileged_grant_success,
                                joinPermissionLabels(appContext, result.grantedKinds)),
                        Toast.LENGTH_LONG).show();
                reconcileServices(appContext, preferences);
            }
            if (result.anyFailed()) {
                Toast.makeText(appContext,
                        appContext.getString(R.string.privileged_grant_failed,
                                joinPermissionLabels(appContext, result.failedKinds)),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @NonNull
    private static String joinPermissionLabels(
            @NonNull Context context,
            @NonNull List<PrivilegedShell.PermissionKind> kinds) {
        List<String> labels = new ArrayList<>(kinds.size());
        for (PrivilegedShell.PermissionKind kind : kinds) {
            labels.add(context.getString(permissionLabelRes(kind)));
        }
        return TextUtils.join(", ", labels);
    }

    private static int permissionLabelRes(@NonNull PrivilegedShell.PermissionKind kind) {
        switch (kind) {
            case OVERLAY: return R.string.permission_label_overlay;
            case FOREGROUND_LOCATION: return R.string.permission_label_foreground_location;
            case BACKGROUND_LOCATION: return R.string.permission_label_background_location;
            case USAGE_ACCESS: return R.string.permission_label_usage_access;
            case NOTIFICATION: return R.string.permission_label_notification;
            case ACCESSIBILITY: return R.string.permission_label_accessibility;
            default: throw new IllegalArgumentException("Unknown permission kind " + kind);
        }
    }

    private static void maybeShowCrashReport(@NonNull AppCompatActivity activity) {
        File crashFile = new File(activity.getCacheDir(), StatusWidgetApplication.CRASH_FILE);
        if (!crashFile.exists() || !crashFile.canRead()) return;

        String content;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(crashFile), StandardCharsets.UTF_8))) {
            StringBuilder value = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) value.append(line).append('\n');
            content = value.toString();
        } catch (IOException error) {
            //noinspection ResultOfMethodCallIgnored
            crashFile.delete();
            return;
        }
        // Consume immediately so Back/outside dismissal cannot show the same report forever.
        //noinspection ResultOfMethodCallIgnored
        crashFile.delete();

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.crash_report_title))
                .setMessage(content)
                .setNeutralButton(activity.getString(R.string.crash_report_copy),
                        (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "Status Widget crash", content));
                    }
                    Toast.makeText(activity, R.string.crash_report_copied,
                            Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(activity.getString(R.string.crash_report_share),
                        (dialog, which) -> shareCrashReport(activity, content))
                .setNegativeButton(activity.getString(R.string.crash_report_dismiss), null)
                .show();
    }

    private static void shareCrashReport(@NonNull AppCompatActivity activity,
                                         @NonNull String content) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Status Widget crash")
                    .putExtra(Intent.EXTRA_TEXT, content);
            activity.startActivity(Intent.createChooser(send,
                    activity.getString(R.string.crash_report_chooser)));
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to share crash report", error);
        }
    }
}
