/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.ECarXCarProxy;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.AvailabilitySts;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.signal.SignalFilter;
import ecarx.car.hardware.vehicle.CarPAEventCallback;
import ecarx.car.hardware.vehicle.ECarXCarPhevManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.PATypes;

/** Read-only source for the two PA fields which back the stock “Trip 2” screen. */
final class EcarxTrip2Access implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String TAG = "EcarxTrip2Access";

    interface Listener {
        void onTrip2(@NonNull Sample sample);
    }

    static final class Sample {
        final int distanceRaw;
        final int durationRaw;
        final int distanceFormat;
        final int durationFormat;
        final int distanceStatus;
        final int durationStatus;
        final long observedAtElapsedNanos;

        Sample(int distanceRaw, int durationRaw,
               int distanceFormat, int durationFormat, int distanceStatus,
               int durationStatus, long observedAtElapsedNanos) {
            this.distanceRaw = distanceRaw;
            this.durationRaw = durationRaw;
            this.distanceFormat = distanceFormat;
            this.durationFormat = durationFormat;
            this.distanceStatus = distanceStatus;
            this.durationStatus = durationStatus;
            this.observedAtElapsedNanos = observedAtElapsedNanos;
        }
    }

    @NonNull private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    @NonNull private final CarPAEventCallback callback = new CarPAEventCallback() {
        @Override public void onPA_TS_OdometerTripMeter2(
                PATypes.PA_TS_OdometerTripMeter2 ignored) {
            publishCurrentPair();
        }

        @Override public void onPA_TS_EDT_time2(PATypes.PA_TS_EDT_time2 ignored) {
            publishCurrentPair();
        }
    };

    @Nullable private ECarXCarProxy proxy;
    @Nullable private volatile ECarXCarPhevManager manager;
    @Nullable private volatile Sample latest;
    private volatile boolean callbackRegistered;
    private volatile boolean closed;

    EcarxTrip2Access(@NonNull Context context) {
        try {
            Context application = context.getApplicationContext();
            proxy = new ECarXCarProxy(application == null ? context : application, this);
            proxy.initECarXCar();
        } catch (Throwable failure) {
            Log.w(TAG, "Could not initialise Trip 2 access", failure);
            close();
        }
    }

    void addListener(@NonNull Listener listener) {
        if (closed) return;
        boolean added = listeners.add(listener);
        boolean seededFromManager = added && ensureCallbackForDemand();
        if (seededFromManager) publishCurrentPair();
        Sample current = latest;
        if (!seededFromManager && current != null) notifyOne(listener, current);
    }

    void removeListener(@Nullable Listener listener) {
        if (listener != null && listeners.remove(listener) && listeners.isEmpty()) {
            suspendCallbackWithoutDemand();
        }
    }

    @Nullable
    Sample latestSample() {
        // Diagnostics is an explicit one-shot demand and runs off the UI thread.
        if (listeners.isEmpty()) publishCurrentPair();
        return latest;
    }

    @Override
    public synchronized void onECarXCarServiceConnected(
            ECarXCar root, CarSignalManager ignoredSignals) {
        if (closed || root == null) return;
        detachManager();
        try {
            Object service = root.getCarManager(ECarXCar.PA_SERVICE);
            if (!(service instanceof ECarXCarSetManager)) {
                throw new IllegalStateException("PA_SERVICE != ECarXCarSetManager");
            }
            ECarXCarPhevManager next = ((ECarXCarSetManager) service)
                    .getECarXCarPhevManager();
            if (next == null) throw new IllegalStateException("PHEV manager=null");
            manager = next;
            if (ensureCallbackForDemand()) publishCurrentPair();
        } catch (Throwable failure) {
            detachManager();
            Log.w(TAG, "Trip 2 PA manager is unavailable", failure);
        }
    }

    @Override
    public synchronized void onECarXCarServiceDeath() {
        callbackRegistered = false;
        manager = null;
        latest = null;
    }

    private void publishCurrentPair() {
        ECarXCarPhevManager source = manager;
        if (closed || source == null) return;
        try {
            PATypes.PA_TS_OdometerTripMeter2 distance =
                    source.getPA_TS_OdometerTripMeter2();
            PATypes.PA_TS_EDT_time2 duration = source.getPA_TS_EDT_time2();
            if (!active(distance) || !active(duration)) return;
            int distanceRaw = distance.getData();
            int durationRaw = duration.getData();
            if (distanceRaw < 0 || durationRaw < 0) return;
            // A binder-death callback can detach the manager while these two synchronous reads
            // are in flight. Never republish that retired proxy as a fresh Trip 2 sample.
            if (closed || manager != source) return;
            Sample sample = new Sample(distanceRaw, durationRaw,
                    distance.getFormat(), duration.getFormat(),
                    distance.getStatus(), duration.getStatus(),
                    SystemClock.elapsedRealtimeNanos());
            latest = sample;
            for (Listener listener : listeners) notifyOne(listener, sample);
        } catch (Throwable failure) {
            Log.w(TAG, "Trip 2 PA read failed", failure);
        }
    }

    private static boolean active(@Nullable PATypes.PA_IntBase value) {
        return value != null && value.getAvailability() == AvailabilitySts.Active;
    }

    private static void notifyOne(@NonNull Listener listener, @NonNull Sample sample) {
        try {
            listener.onTrip2(sample);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Trip 2 listener failed", failure);
        }
    }

    /** The PA callback exists only while at least one visible consumer requests Trip 2. */
    private synchronized boolean ensureCallbackForDemand() {
        ECarXCarPhevManager current = manager;
        if (closed || callbackRegistered || current == null || listeners.isEmpty()) return false;
        try {
            SignalFilter filter = new SignalFilter();
            filter.add(ECarXCarPhevManager.ManagerId_patsodometertripmeter2);
            filter.add(ECarXCarPhevManager.ManagerId_patsedttime2);
            current.registerCallback(callback, filter);
            callbackRegistered = true;
            return true;
        } catch (Throwable failure) {
            callbackRegistered = false;
            Log.w(TAG, "Trip 2 PA callback registration failed", failure);
            return false;
        }
    }

    private synchronized void suspendCallbackWithoutDemand() {
        if (!listeners.isEmpty() || !callbackRegistered) return;
        ECarXCarPhevManager current = manager;
        if (current != null) {
            try {
                current.unregisterCallback(callback);
            } catch (Throwable failure) {
                Log.d(TAG, "Trip 2 idle callback cleanup failed", failure);
            }
        }
        callbackRegistered = false;
    }

    private synchronized void detachManager() {
        ECarXCarPhevManager current = manager;
        manager = null;
        latest = null;
        if (callbackRegistered && current != null) {
            try {
                current.unregisterCallback(callback);
            } catch (Throwable failure) {
                Log.d(TAG, "Trip 2 callback cleanup failed", failure);
            }
        }
        callbackRegistered = false;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        listeners.clear();
        detachManager();
        ECarXCarProxy current = proxy;
        proxy = null;
        if (current != null) {
            try {
                current.cleanup();
            } catch (Throwable failure) {
                Log.d(TAG, "Trip 2 proxy cleanup failed", failure);
            }
        }
    }
}
