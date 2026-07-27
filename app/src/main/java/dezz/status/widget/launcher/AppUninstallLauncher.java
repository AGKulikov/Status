/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.shell.PrivilegedShell;

/**
 * Keeps package removal inside the current All Apps surface.
 *
 * <p>The visible confirmation belongs to this app and is itself an overlay, so neither the
 * launcher drawer nor the driver-panel drawer is replaced. On the head unit's normal privileged
 * channel the confirmed package is removed for user 0. If that channel is unavailable, the
 * standard Android Package Installer remains the safe fallback.</p>
 */
public final class AppUninstallLauncher {
    public static final String ACTION_FINISHED =
            "dezz.status.widget.action.APP_UNINSTALL_FINISHED";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_LABEL = "label";

    private AppUninstallLauncher() {
    }

    public static boolean request(@NonNull Context context,
                                  @NonNull LauncherAppCatalog.App app) {
        return request(context, app.packageName, app.label);
    }

    public static boolean request(@NonNull Context context,
                                  @NonNull String packageName,
                                  @NonNull String label) {
        String target = packageName.trim();
        if (target.isEmpty()) return false;
        if (!safePackageName(target)) {
            return launchSystemConfirmation(context, target, label);
        }
        try {
            Context themed = new ContextThemeWrapper(context,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert);
            AtomicBoolean terminal = new AtomicBoolean();
            AlertDialog dialog = new AlertDialog.Builder(themed)
                    .setTitle("Удалить приложение?")
                    .setMessage("Удалить «" + label + "» и его данные?")
                    .setPositiveButton("Удалить", null)
                    .setNegativeButton("Отмена", null)
                    .create();
            Window window = dialog.getWindow();
            boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(context);
            if (window != null && canOverlay) {
                // All Apps is already an application overlay. Put confirmation in the same layer,
                // but add it later so it remains visibly above the unchanged grid.
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            } else if (!(context instanceof Activity)) {
                return launchSystemConfirmation(context, target, label);
            }
            dialog.setOnShowListener(ignored -> {
                Button remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                remove.setOnClickListener(view -> {
                    remove.setEnabled(false);
                    Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    if (cancel != null) cancel.setEnabled(false);
                    dialog.setCancelable(false);
                    dialog.setMessage("Удаление «" + label + "»…");
                    PrivilegedShell.get(context).runCommand(
                            "pm uninstall --user 0 " + target,
                            (output, error) -> {
                                if (commandSucceeded(context, target, output, error)) {
                                    terminal.set(true);
                                    dialog.dismiss();
                                    Toast.makeText(context,
                                            "Приложение «" + label + "» удалено",
                                            Toast.LENGTH_SHORT).show();
                                    notifyFinished(context);
                                    return;
                                }
                                terminal.set(true);
                                dialog.dismiss();
                                if (!launchSystemConfirmation(context, target, label)) {
                                    notifyFinished(context);
                                }
                            });
                });
            });
            dialog.setOnDismissListener(ignored -> {
                // Back, outside tap and the explicit Cancel button all restore the existing
                // drawer. A successful/fallback path owns its own completion signal.
                if (terminal.compareAndSet(false, true)) notifyFinished(context);
            });
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();
            return true;
        } catch (RuntimeException failure) {
            return launchSystemConfirmation(context, target, label);
        }
    }

    private static boolean launchSystemConfirmation(
            @NonNull Context context,
            @NonNull String target,
            @NonNull String label) {
        Intent uninstall = new Intent(context, AppUninstallProxyActivity.class)
                .putExtra(EXTRA_PACKAGE_NAME, target)
                .putExtra(EXTRA_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(uninstall);
            return true;
        } catch (RuntimeException failure) {
            Toast.makeText(context,
                    "Удаление недоступно для «" + label + "»",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static boolean commandSucceeded(
            @NonNull Context context,
            @NonNull String target,
            String output,
            String error) {
        if (error != null) return false;
        String normalized = output == null ? ""
                : output.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("success")) return true;
        try {
            context.getPackageManager().getApplicationInfo(target, 0);
            return false;
        } catch (android.content.pm.PackageManager.NameNotFoundException removed) {
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean safePackageName(@NonNull String value) {
        return value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static void notifyFinished(@NonNull Context context) {
        context.sendBroadcast(new Intent(ACTION_FINISHED)
                .setPackage(context.getPackageName()));
    }
}
