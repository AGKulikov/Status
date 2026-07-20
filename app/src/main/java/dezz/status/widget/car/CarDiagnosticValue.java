/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Read-only raw vendor value shown locally before any HA telemetry mapping is enabled. */
public final class CarDiagnosticValue {
    public final String id;
    public final String label;
    public final String supportStatus;
    public final String rawValue;
    public final String unitNote;
    /**
     * Validated numeric value supplied by the vendor SDK, or {@code null} when the signal is
     * unsupported, failed to read, or contained a known sentinel/non-finite value.  {@link
     * #rawValue} deliberately remains unchanged so the diagnostics screen can still help identify
     * vendor-specific encodings without downstream integrations having to parse that text.
     */
    @Nullable public final Float numericValue;

    public CarDiagnosticValue(@NonNull String id, @NonNull String label,
                              @NonNull String supportStatus, @NonNull String rawValue,
                              @NonNull String unitNote) {
        this(id, label, supportStatus, rawValue, unitNote, null);
    }

    public CarDiagnosticValue(@NonNull String id, @NonNull String label,
                              @NonNull String supportStatus, @NonNull String rawValue,
                              @NonNull String unitNote, @Nullable Float numericValue) {
        this.id = id;
        this.label = label;
        this.supportStatus = supportStatus;
        this.rawValue = rawValue;
        this.unitNote = unitNote;
        this.numericValue = numericValue;
    }
}
