/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Five approved, mixable visual families used by the modular instrument presets. */
public enum InstrumentStyleFamily {
    SLATE_HORIZON("11 · Slate Horizon",
            0xFF03070C, 0xFFF4F7FA, 0xFF48C7F4, 0xFF56697E),
    GLACIER_MAP("12 · Glacier Map",
            0xFF02060B, 0xFFF4F8FC, 0xFF35CFFF, 0xFF2E526E),
    AEROWAVE("13 · Aerowave",
            0xFF03070C, 0xFFF5F7FA, 0xFF53B9FF, 0xFF5D6E84),
    STEEL_VECTOR("14 · Steel Vector",
            0xFF04070B, 0xFFF6F8FB, 0xFF2FC4FF, 0xFF637286),
    CONTINUUM("15 · Continuum",
            0xFF020509, 0xFFF8FAFC, 0xFF35D5FF, 0xFF3F566F);

    @NonNull public final String label;
    public final int backgroundColor;
    public final int primaryColor;
    public final int accentColor;
    public final int secondaryColor;

    InstrumentStyleFamily(@NonNull String label, int backgroundColor, int primaryColor,
                          int accentColor, int secondaryColor) {
        this.label = label;
        this.backgroundColor = backgroundColor;
        this.primaryColor = primaryColor;
        this.accentColor = accentColor;
        this.secondaryColor = secondaryColor;
    }

    @NonNull
    public static InstrumentStyleFamily fromName(@Nullable String raw) {
        if (raw != null) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Old families are deliberately absent from the editor. Keep imported documents
                // readable by mapping them to the nearest one of the five approved families.
                String legacy = raw.trim().toUpperCase(Locale.ROOT);
                if ("NAVIGATION_FIRST".equals(legacy)) return GLACIER_MAP;
                if ("M_SPORT_ARCS".equals(legacy) || "SUPERSPORT".equals(legacy)) {
                    return AEROWAVE;
                }
                if ("MINIMAL_PANORAMA".equals(legacy)
                        || "VIRTUAL_CLASSIC".equals(legacy)) return STEEL_VECTOR;
                if ("EV_FLOW".equals(legacy)) return CONTINUUM;
            }
        }
        return SLATE_HORIZON;
    }
}
