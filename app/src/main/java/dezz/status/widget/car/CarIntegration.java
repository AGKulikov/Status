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

package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.BrickType;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Abstraction over a vendor car SDK feeding data to car-specific bricks.
 * <p>
 * Each product flavor (Gradle dimension {@code car}) supplies exactly one implementation via
 * {@code dezz.status.widget.car.CarIntegrationFactory} in its own source set — main code must
 * never reference vendor classes. The contract:
 * <ul>
 *   <li>All callbacks are delivered on the main thread.</li>
 *   <li>{@link #isBrickSupported} is cheap and callable any time (settings UI uses it to decide
 *       which bricks to offer); it must return {@code false} on vehicles where the underlying
 *       SDK or the specific sensor is unavailable, and never throw.</li>
 *   <li>{@link #subscribe} replaces any previous listener for the same brick; implementations
 *       should push the latest known value immediately when one is available, so a freshly shown
 *       brick doesn't sit empty until the sensor's next change event.</li>
 *   <li>Vendor-side failures are contained: implementations log and stay silent instead of
 *       crashing the widget.</li>
 * </ul>
 */
public interface CarIntegration {

    /** Receives values for a subscribed brick. Called on the main thread. */
    interface ValueListener {
        void onValue(@NonNull BrickType type, float value);
    }

    interface DiagnosticsListener {
        void onDiagnostics(@NonNull List<CarDiagnosticValue> values);
    }

    /** Immutable, validated numeric sample from the vehicle SDK. */
    final class TelemetryValue {
        @NonNull public final String id;
        @NonNull public final String label;
        public final double value;
        /** Compact machine-facing unit (for example {@code °C} or {@code raw}). */
        @NonNull public final String unit;
        public final long observedAtMillis;

        public TelemetryValue(@NonNull String id, @NonNull String label, double value,
                              @NonNull String unit, long observedAtMillis) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Vehicle telemetry must be finite");
            }
            this.id = id;
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.observedAtMillis = observedAtMillis;
        }

        /** Preserve the short decimal representation supplied by float-based vendor SDKs. */
        public TelemetryValue(@NonNull String id, @NonNull String label, float value,
                              @NonNull String unit, long observedAtMillis) {
            this(id, label, Double.parseDouble(Float.toString(value)), unit, observedAtMillis);
        }
    }

    /** Receives validated vehicle telemetry on the main thread. */
    interface TelemetryListener {
        void onTelemetry(@NonNull TelemetryValue value);
    }

    /** Receives all telemetry metrics exposed by this vehicle connector on the main thread. */
    interface TelemetryCatalogListener {
        void onCatalog(@NonNull List<CarTelemetryDescriptor> values);
    }

    /** Receives the vehicle's visual control catalog on the main thread. */
    interface ControlCatalogListener {
        void onCatalog(@NonNull List<CarControlDescriptor> controls);
    }

    /** Receives confirmed vehicle-function state on the main thread. */
    interface ControlStateListener {
        void onControlState(@NonNull CarControlState state);
    }

    /** Completion of a command request. Stateful values are confirmed by read-back. */
    interface ControlCommandListener {
        void onResult(boolean success, @Nullable String message);
    }

    /** Whether this vehicle can feed the given brick right now. */
    boolean isBrickSupported(@NonNull BrickType type);

    /**
     * Register a callback (main thread) invoked whenever the answer of {@link #isBrickSupported}
     * may have changed — typically when the vendor platform service finishes its asynchronous
     * connect after boot. The widget re-applies brick visibility in response. Pass {@code null}
     * to clear. Implementations with static support (e.g. {@link NoCarIntegration}) may ignore it.
     */
    void setAvailabilityChangedListener(@androidx.annotation.Nullable Runnable listener);

    /** Start delivering values for the brick. Replaces any existing subscription for it. */
    void subscribe(@NonNull BrickType type, @NonNull ValueListener listener);

    /** Stop delivering values for the brick. No-op when not subscribed. */
    void unsubscribe(@NonNull BrickType type);

    /**
     * Subscribe to the requested vehicle metrics. This channel is independent from the
     * {@link BrickType} subscriptions above, delivers a supported metric's latest cached value
     * first, and then streams vendor change events. Unknown IDs are ignored. An empty set causes
     * no vendor registrations or reads. Registering the same listener again replaces only that
     * listener's previous telemetry subscription.
     */
    void subscribeTelemetry(@NonNull Set<String> metricIds,
                            @NonNull TelemetryListener listener);

    /** Stop a telemetry subscription without disturbing the widget's brick subscriptions. */
    void unsubscribeTelemetry(@NonNull TelemetryListener listener);

    /**
     * Return the stable, connector-neutral metric catalog. Implementations do not need a live
     * vehicle connection to describe the values they know how to read.
     */
    default void requestTelemetryCatalog(@NonNull TelemetryCatalogListener listener) {
        listener.onCatalog(Collections.emptyList());
    }

    /** Asynchronous, read-only diagnostic snapshot; callback is delivered on the main thread. */
    default void requestDiagnostics(@NonNull DiagnosticsListener listener) {
        listener.onDiagnostics(Collections.emptyList());
    }

    /**
     * Build the catalog of vehicle functions known to this flavor. Implementations may probe the
     * vendor service asynchronously; unknown-at-boot entries can be returned with
     * {@link CarControlDescriptor.Availability#UNKNOWN} so the editor remains usable.
     */
    default void requestControlCatalog(@NonNull ControlCatalogListener listener) {
        listener.onCatalog(Collections.emptyList());
    }

    /** Replace this listener's requested control IDs and seed their current values. */
    default void subscribeControlStates(@NonNull Set<String> controlIds,
                                        @NonNull ControlStateListener listener) {
    }

    /** Stop only this listener's vehicle-control subscription. */
    default void unsubscribeControlStates(@NonNull ControlStateListener listener) {
    }

    /** Execute off the UI thread; persistent values use read-back, pulse actions use SDK accept. */
    default void executeControl(@NonNull CarControlCommand command,
                                @NonNull ControlCommandListener listener) {
        listener.onResult(false, "Функции автомобиля недоступны в этой сборке");
    }

    /** Release all subscriptions and vendor resources. The instance is not reusable afterwards. */
    void shutdown();
}
