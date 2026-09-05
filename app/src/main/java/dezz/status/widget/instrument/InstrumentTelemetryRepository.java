/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;

/**
 * Process-wide, one-subscription telemetry store for the instrument editor and projected panel.
 * Vendor callbacks only assign volatile primitives; there is no Looper hop or allocation here.
 */
public final class InstrumentTelemetryRepository {
    /** A vendor callback may invoke this from a Binder/SDK thread; implementations only wake UI. */
    public interface UpdateListener {
        void onInstrumentTelemetryChanged();
    }

    private static final OwnerDemand[] NO_OWNER_DEMANDS = new OwnerDemand[0];
    private static volatile InstrumentTelemetryRepository instance;

    @NonNull private final CarIntegration integration;
    @NonNull private final Map<UpdateListener, Set<String>> ownerMetrics =
            new IdentityHashMap<>();
    @NonNull private Set<String> subscribedMetrics = Collections.emptySet();
    /** Immutable-by-convention hot-path snapshot rebuilt only when a panel becomes visible. */
    @NonNull private volatile OwnerDemand[] updateOwners = NO_OWNER_DEMANDS;

    private volatile float speed = Float.NaN;
    private volatile float rpm = Float.NaN;
    private volatile float gear = Float.NaN;
    private volatile float odometer = Float.NaN;
    private volatile float fuel = Float.NaN;
    private volatile float fuelRange = Float.NaN;
    private volatile float totalRange = Float.NaN;
    private volatile float ambientTemperature = Float.NaN;
    private volatile float cabinTemperature = Float.NaN;
    private volatile float coolantTemperature = Float.NaN;
    private volatile float battery = Float.NaN;
    private volatile float instantConsumption = Float.NaN;
    private volatile float averageConsumption = Float.NaN;
    private volatile float tripConsumption = Float.NaN;
    @NonNull private final AtomicLong generation = new AtomicLong();
    private volatile long newestSampleElapsedNanos;

    private final CarIntegration.RealtimeTelemetryListener listener =
            this::acceptRealtimeSample;

    private InstrumentTelemetryRepository(@NonNull Context context) {
        integration = CarIntegrations.get(context.getApplicationContext());
    }

    @NonNull
    public static InstrumentTelemetryRepository get(@NonNull Context context) {
        InstrumentTelemetryRepository local = instance;
        if (local == null) {
            synchronized (InstrumentTelemetryRepository.class) {
                local = instance;
                if (local == null) {
                    local = new InstrumentTelemetryRepository(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Start or expand the one physical ECARX subscription for a visible panel/editor.
     * Only metrics used by enabled elements are registered with the vendor SDK.
     */
    public synchronized void acquire(@NonNull UpdateListener owner,
                                     @NonNull Set<String> metricIds) {
        ownerMetrics.put(owner, immutableCopy(metricIds));
        rebuildUpdateListenersLocked();
        reconcileSubscriptionLocked();
    }

    /** Change demand after live editing without creating a second physical subscription. */
    public synchronized void updateMetrics(@NonNull UpdateListener owner,
                                           @NonNull Set<String> metricIds) {
        if (!ownerMetrics.containsKey(owner)) return;
        Set<String> next = immutableCopy(metricIds);
        if (next.equals(ownerMetrics.get(owner))) return;
        ownerMetrics.put(owner, next);
        rebuildUpdateListenersLocked();
        reconcileSubscriptionLocked();
    }

    /** Stop exactly this owner and tear down ECARX listeners after the final visible surface. */
    public synchronized void release(@NonNull UpdateListener owner) {
        if (ownerMetrics.remove(owner) == null) return;
        rebuildUpdateListenersLocked();
        reconcileSubscriptionLocked();
    }

    private void rebuildUpdateListenersLocked() {
        OwnerDemand[] next = new OwnerDemand[ownerMetrics.size()];
        int index = 0;
        for (Map.Entry<UpdateListener, Set<String>> entry : ownerMetrics.entrySet()) {
            next[index++] = new OwnerDemand(entry.getKey(), entry.getValue());
        }
        updateOwners = next;
    }

    private void reconcileSubscriptionLocked() {
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (Set<String> demand : ownerMetrics.values()) union.addAll(demand);
        Set<String> next = immutableCopy(union);
        if (next.equals(subscribedMetrics)) return;
        subscribedMetrics = next;
        if (next.isEmpty()) integration.unsubscribeRealtimeTelemetry(listener);
        else integration.subscribeRealtimeTelemetry(next, listener);
    }

    @NonNull
    private static Set<String> immutableCopy(@NonNull Set<String> values) {
        if (values.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private void acceptRealtimeSample(@NonNull String metricId, float value,
                                      long observedAtElapsedNanos) {
        float normalized = InstrumentValuePolicy.normalize(metricId, value);
        boolean changed;
        switch (metricId) {
            case "ISensor.speed":
                changed = InstrumentValuePolicy.differs(speed, normalized); speed = normalized; break;
            case "ISensor.rpm":
                changed = InstrumentValuePolicy.differs(rpm, normalized); rpm = normalized; break;
            case "ISensor.gear":
                changed = InstrumentValuePolicy.differs(gear, normalized); gear = normalized; break;
            case "ISensor.odometer":
                changed = InstrumentValuePolicy.differs(odometer, normalized); odometer = normalized; break;
            case "ISensor.fuel_level":
                changed = InstrumentValuePolicy.differs(fuel, normalized); fuel = normalized; break;
            case "ISensor.range_fuel":
                changed = InstrumentValuePolicy.differs(fuelRange, normalized);
                fuelRange = normalized; break;
            case "ISensor.range_total":
                changed = InstrumentValuePolicy.differs(totalRange, normalized);
                totalRange = normalized; break;
            case "ISensor.ambient_temp":
                changed = InstrumentValuePolicy.differs(ambientTemperature, normalized);
                ambientTemperature = normalized; break;
            case "ISensor.indoor_temp":
                changed = InstrumentValuePolicy.differs(cabinTemperature, normalized);
                cabinTemperature = normalized; break;
            case "ISensor.coolant_temp":
                changed = InstrumentValuePolicy.differs(coolantTemperature, normalized);
                coolantTemperature = normalized; break;
            case "ISensor.ev_battery_level":
                changed = InstrumentValuePolicy.differs(battery, normalized); battery = normalized; break;
            case "ISensor.instant_fuel_consumption":
                changed = InstrumentValuePolicy.differs(instantConsumption, normalized);
                instantConsumption = normalized; break;
            case "ISensor.avg_fuel_consumption":
                changed = InstrumentValuePolicy.differs(averageConsumption, normalized);
                averageConsumption = normalized; break;
            case "ISensor.avg_fuel_consumption_ignition":
                changed = InstrumentValuePolicy.differs(tripConsumption, normalized);
                tripConsumption = normalized; break;
            default: return;
        }
        newestSampleElapsedNanos = observedAtElapsedNanos;
        if (!changed) return;
        generation.incrementAndGet();
        OwnerDemand[] owners = updateOwners;
        for (int index = 0; index < owners.length; index++) {
            OwnerDemand owner = owners[index];
            if (!owner.metricIds.contains(metricId)) continue;
            try {
                owner.listener.onInstrumentTelemetryChanged();
            } catch (RuntimeException ignored) {
                // Never let an editor/activity lifecycle race escape into the ECARX callback.
            }
        }
    }

    public long generation() {
        return generation.get();
    }

    private static final class OwnerDemand {
        @NonNull final UpdateListener listener;
        @NonNull final Set<String> metricIds;

        OwnerDemand(@NonNull UpdateListener listener, @NonNull Set<String> metricIds) {
            this.listener = listener;
            this.metricIds = metricIds;
        }
    }

    /** Copy one internally consistent-enough primitive snapshot for the current display frame. */
    public void snapshot(@NonNull Frame out) {
        // Read generation before and after. A rare concurrent callback retries once, which is
        // cheaper than locking the ECARX Binder thread or allocating an immutable object.
        long before;
        long after;
        int attempt = 0;
        do {
            before = generation.get();
            out.speed = speed;
            out.rpm = rpm;
            out.gear = gear;
            out.odometer = odometer;
            out.fuel = fuel;
            out.fuelRange = fuelRange;
            out.totalRange = totalRange;
            out.ambientTemperature = ambientTemperature;
            out.cabinTemperature = cabinTemperature;
            out.coolantTemperature = coolantTemperature;
            out.battery = battery;
            out.instantConsumption = instantConsumption;
            out.averageConsumption = averageConsumption;
            out.tripConsumption = tripConsumption;
            out.newestSampleElapsedNanos = newestSampleElapsedNanos;
            after = generation.get();
        } while (before != after && attempt++ == 0);
        out.generation = after;
    }

    /** Mutable renderer-owned frame; reusing it guarantees zero per-VSync allocations. */
    public static final class Frame {
        public float speed;
        public float rpm;
        public float gear;
        public float odometer;
        public float fuel;
        public float fuelRange;
        public float totalRange;
        public float ambientTemperature;
        public float cabinTemperature;
        public float coolantTemperature;
        public float battery;
        public float instantConsumption;
        public float averageConsumption;
        public float tripConsumption;
        public long generation;
        public long newestSampleElapsedNanos;
    }
}
