/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.drivemode.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.drivemode.car.*;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.media.ButtonAction;

/** One presentation catalog for selector, car tiles, HOME, DIM, driver panels and pickers. */
public final class DriveModeIcons {
    private DriveModeIcons() {}
    public static boolean isDrive(String control) { return "vehicle.drive_mode".equals(control); }
    public static DriveModeDescriptor fromKey(String key) {
        if (key == null || !key.startsWith("drive_")) return null;
        for (DriveModeDescriptor mode : DriveModeCatalog.all()) if (key.equals("drive_" + mode.key)) return mode;
        return null;
    }
    public static Drawable drawable(Context context, int code) {
        DriveModeDescriptor mode = DriveModeCatalog.byCodeOrGeneric(code);
        Drawable icon = ContextCompat.getDrawable(context, mode.iconRes);
        if (icon == null) return null;
        icon = icon.mutate(); icon.clearColorFilter();
        if (!mode.iconIsColored) icon.setTint(ContextCompat.getColor(context, mode.accentRes));
        return icon;
    }
    public static int value(LauncherShortcutStore.Shortcut shortcut, CarControlState state) {
        if (shortcut.command == CarControlCommand.Operation.SET) return (int) shortcut.commandValue;
        return state != null && state.available && state.known ? (int) state.value : 255;
    }
    public static String key(LauncherShortcutStore.Shortcut shortcut, CarControlState state) {
        if ("none".equalsIgnoreCase(shortcut.icon)) return "none";
        return "drive_" + DriveModeCatalog.byCodeOrGeneric(value(shortcut, state)).key;
    }
    public static Integer buttonCode(ButtonAction action) {
        String key;
        switch (action) {
            case COMFORT: key = "comfort"; break;
            case ADAPTIVE: key = "adaptive"; break;
            case ECO: key = "eco"; break;
            case SPORT: key = "dynamic"; break;
            case OFFROAD: key = "offroad"; break;
            case SAND: key = "sand"; break;
            case SNOW: key = "snow"; break;
            case DRIVE_SHOW: case DRIVE_PREV_1: case DRIVE_PREV_2: case DRIVE_PREV_3:
            case DRIVE_NEXT_1: case DRIVE_NEXT_2: case DRIVE_NEXT_3: return 255;
            default: return null;
        }
        for (DriveModeDescriptor mode : DriveModeCatalog.all()) if (mode.key.equals(key)) return mode.code;
        return 255;
    }
}
