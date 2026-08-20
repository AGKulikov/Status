/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Safety and presentation rules shared by every launcher surface that exposes the trunk. */
public final class TrunkControlSafety {
    public static final String CONTROL_ID = "vehicle.trunk";
    public static final String ICON_CLOSED = "trunk_closed";
    public static final String ICON_OPEN = "trunk_open";

    private TrunkControlSafety() {}

    public static boolean isTrunk(@NonNull String controlId) {
        return CONTROL_ID.equals(controlId);
    }

    /**
     * Converts every UI operation into an idempotent target. A delayed confirmation must never
     * execute a stale TOGGLE and accidentally reverse a state that changed while the dialog was
     * visible.
     */
    @NonNull
    public static CarControlCommand resolve(@NonNull CarControlCommand requested,
                                            @Nullable CarControlState state) {
        if (!isTrunk(requested.controlId)) return requested;
        double target;
        if (requested.operation == CarControlCommand.Operation.SET) {
            target = requested.value >= .5d ? 1d : 0d;
        } else if (state != null && state.available && state.known) {
            target = state.active ? 0d : 1d;
        } else {
            // Unknown generic actions are treated as opening requests: confirm first, then write
            // an exact value instead of blindly toggling an unconfirmed physical state.
            target = 1d;
        }
        return new CarControlCommand(CONTROL_ID, CarControlCommand.Operation.SET, target);
    }

    /**
     * @return true when this method owns the command (dialog shown or safely rejected); false
     * means the caller should execute it immediately, which is the normal closing path.
     */
    public static boolean confirmOpeningIfNeeded(@NonNull Context context,
                                                 @NonNull CarControlCommand resolved,
                                                 @NonNull Runnable approved) {
        if (!isTrunk(resolved.controlId)
                || resolved.operation != CarControlCommand.Operation.SET
                || resolved.value < .5d) return false;
        Context dialogContext = context instanceof Activity
                ? context : context.getApplicationContext();
        try {
            AlertDialog dialog = new AlertDialog.Builder(dialogContext,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Открыть багажник?")
                    .setMessage("Убедитесь, что позади автомобиля достаточно свободного места.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Открыть", (ignored, which) -> approved.run())
                    .create();
            Window window = dialog.getWindow();
            if (!(dialogContext instanceof Activity) && window != null) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
            dialog.show();
        } catch (RuntimeException failure) {
            // Never bypass the confirmation merely because an overlay window could not be made.
            Toast.makeText(context, "Не удалось показать подтверждение открытия багажника",
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    @NonNull
    public static String iconKey(@NonNull String configured,
                                 @Nullable CarControlState state) {
        if (state == null || !state.available || !state.known) return configured;
        return state.active ? ICON_OPEN : ICON_CLOSED;
    }
}
