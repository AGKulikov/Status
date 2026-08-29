/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Ten original, mixable instrument families inspired by established automotive conventions. */
public enum InstrumentStyleFamily {
    GRAND_TOURER("Grand Tourer · тёплая классика",
            0xFF080A0D, 0xFFF1E4C6, 0xFFC9A96B, 0xFF5B4931),
    EXECUTIVE_GLASS("Executive Glass · стекло",
            0xFF15171A, 0xFFF4F1E8, 0xFFD6C7A2, 0xFF5D636B),
    M_SPORT_ARCS("Sport Arcs · угловые дуги",
            0xFF05070B, 0xFFF4F7FA, 0xFF43A8FF, 0xFFE84B55),
    VIRTUAL_CLASSIC("Virtual Classic · контраст",
            0xFF07090C, 0xFFE9EEF4, 0xFFFF3038, 0xFF65717D),
    FIVE_DIAL_HERITAGE("Five-Dial · приборные трубы",
            0xFF090909, 0xFFF4F1E8, 0xFFFFC400, 0xFF65615B),
    SUPERSPORT("Supersport · красная зона",
            0xFF050609, 0xFFFFFFFF, 0xFFFF3B30, 0xFFFFB000),
    NAVIGATION_FIRST("Navigation First · карта в центре",
            0xB30A1018, 0xFFF5F8FC, 0xFF49A8FF, 0xFF4BD58A),
    MINIMAL_PANORAMA("Minimal Panorama · чистый экран",
            0xE6101216, 0xFFF8F9FB, 0xFF7AB9FF, 0xFF59626D),
    EV_FLOW("EV Flow · энергетические дуги",
            0xFF061018, 0xFFE8FAFF, 0xFF37D9C7, 0xFF4F8FFF),
    RETRO_MECHANICAL("Retro Mechanical · кремовая шкала",
            0xFF1A1610, 0xFFF2E6C9, 0xFFCB3A2E, 0xFF8D7650);

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
            }
        }
        return GRAND_TOURER;
    }
}
