/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

/** Read-only raw vendor value shown locally before any HA telemetry mapping is enabled. */
public final class CarDiagnosticValue {
    public final String id;
    public final String label;
    public final String supportStatus;
    public final String rawValue;
    public final String unitNote;

    public CarDiagnosticValue(@NonNull String id, @NonNull String label,
                              @NonNull String supportStatus, @NonNull String rawValue,
                              @NonNull String unitNote) {
        this.id = id;
        this.label = label;
        this.supportStatus = supportStatus;
        this.rawValue = rawValue;
        this.unitNote = unitNote;
    }
}
