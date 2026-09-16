/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/**
 * Command vocabulary checked against DezzK/monjaro-selector v1.0.0 KnobReceiver.
 * A multiplier is a mode-list step count, not a physical click count.
 * ISSHOWING is feedback, deliberately not an assignable command.
 */
public enum DriveSelectorButtonPreset {
    SHOW(ButtonAction.DRIVE_SHOW, "SHOW", 0),
    PREV_1(ButtonAction.DRIVE_PREV_1, "PREV_1", -1),
    PREV_2(ButtonAction.DRIVE_PREV_2, "PREV_2", -2),
    PREV_3(ButtonAction.DRIVE_PREV_3, "PREV_3", -3),
    NEXT_1(ButtonAction.DRIVE_NEXT_1, "NEXT_1", 1),
    NEXT_2(ButtonAction.DRIVE_NEXT_2, "NEXT_2", 2),
    NEXT_3(ButtonAction.DRIVE_NEXT_3, "NEXT_3", 3);

    public static final String REFERENCE_PACKAGE = "dezz.monjaro.drive_modes";
    public final String title, referenceAction;
    public final int steps;
    public final ButtonAction action;

    DriveSelectorButtonPreset(ButtonAction action, String suffix, int steps) {
        this.action = action;
        this.title = action.title;
        this.referenceAction = REFERENCE_PACKAGE + "." + suffix;
        this.steps = steps;
    }

    /** Lossless compatibility representation; never edit existing user intent extras. */
    public ButtonBinding referenceBinding(ButtonBinding original) {
        return new ButtonBinding(ButtonAction.BROADCAST.id, original.application,
                referenceAction, REFERENCE_PACKAGE, original.shortcutJson);
    }

    public static DriveSelectorButtonPreset fromBinding(ButtonBinding binding) {
        DriveSelectorButtonPreset nativePreset = fromAction(binding.action);
        if (nativePreset != null) return nativePreset;
        if (binding.action != ButtonAction.BROADCAST
                || !REFERENCE_PACKAGE.equals(binding.packageName)) return null;
        for (DriveSelectorButtonPreset preset : values())
            if (preset.referenceAction.equals(binding.command)) return preset;
        return null;
    }

    public static DriveSelectorButtonPreset fromAction(ButtonAction action) {
        for (DriveSelectorButtonPreset preset : values()) if (preset.action == action) return preset;
        return null;
    }

    @Override public String toString() { return title; }
}
