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
import ecarx.car.hardware.ECarXCarPropertyValue;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.signal.SignalFilter;

/** Read-only Trip-2 signal candidates. SDK presence does not prove support on the target KX11. */
final class EcarxTrip2Access implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String TAG = "EcarxTrip2Access";

    interface Listener {
        void onTrip2(@NonNull Sample sample);
    }

    static final class Sample {
        final int distanceRaw;
        final int averageSpeedRaw;
        final int speedUnitRaw;
        final long observedAtElapsedNanos;

        Sample(int distanceRaw, int averageSpeedRaw, int speedUnitRaw,
               long observedAtElapsedNanos) {
            this.distanceRaw = distanceRaw;
            this.averageSpeedRaw = averageSpeedRaw;
            this.speedUnitRaw = speedUnitRaw;
            this.observedAtElapsedNanos = observedAtElapsedNanos;
        }
    }

    @NonNull private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    @NonNull private final CarSignalManager.CarSignalEventCallback callback =
            new CarSignalManager.CarSignalEventCallback() {
                @Override
                @SuppressWarnings("rawtypes")
                public void onChangeEvent(ECarXCarPropertyValue ignored) {
                    publishCurrentPair();
                }

                @Override public void onErrorEvent(int propertyId, int areaId) {
                    Log.w(TAG, "Trip 2 signal callback error " + propertyId + "/" + areaId);
                    publishCurrentPair();
                }
            };

    @Nullable private ECarXCarProxy proxy;
    @Nullable private volatile CarSignalManager signals;
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
        publishCurrentPair();
        return latest;
    }

    @Override
    public synchronized void onECarXCarServiceConnected(
            ECarXCar root, CarSignalManager connectedSignals) {
        if (closed || connectedSignals == null) return;
        detachManager();
        signals = connectedSignals;
        if (ensureCallbackForDemand()) publishCurrentPair();
    }

    @Override
    public synchronized void onECarXCarServiceDeath() {
        callbackRegistered = false;
        signals = null;
        publishUnavailable();
    }

    /**
     * Reads one distance/average-speed/unit sample from the same CarSignalManager generation.
     * The rejected PA fields are deliberately absent: they returned lifetime-like values on KX11.
     */
    private void publishCurrentPair() {
        CarSignalManager source = signals;
        if (closed || source == null) return;
        try {
            // One unsupported getter must not erase other fields or disappear from diagnostics.
            int distanceRaw = readSignal(() -> source.getDstTrvld2());
            int averageSpeedRaw = readSignal(() -> source.getVehSpdAvgIndcdVehSpdIndcd());
            int speedUnitRaw = readSignal(() -> source.getVehSpdAvgIndcdVeSpdIndcdUnit());
            // A Binder-death callback can detach the manager while synchronous reads are in
            // flight. Never republish that retired proxy as a fresh Trip 2 sample.
            Sample sample = new Sample(distanceRaw, averageSpeedRaw, speedUnitRaw,
                    SystemClock.elapsedRealtimeNanos());
            synchronized (this) {
                if (closed || signals != source) return;
                latest = sample;
                for (Listener listener : listeners) notifyOne(listener, sample);
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Trip 2 CarSignal read failed", failure);
        }
    }

    private interface IntReader { int read(); }
    private static int readSignal(IntReader reader) {
        try { return reader.read(); } catch (RuntimeException unavailable) { return -1; }
    }

    private void publishUnavailable() {
        Sample unavailable = new Sample(-1, -1, -1, SystemClock.elapsedRealtimeNanos());
        latest = unavailable;
        for (Listener listener : listeners) notifyOne(listener, unavailable);
    }

    private static void notifyOne(@NonNull Listener listener, @NonNull Sample sample) {
        try {
            listener.onTrip2(sample);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Trip 2 listener failed", failure);
        }
    }

    /** The signal callback exists only while at least one visible consumer requests Trip 2. */
    private synchronized boolean ensureCallbackForDemand() {
        CarSignalManager current = signals;
        if (closed || callbackRegistered || current == null || listeners.isEmpty()) return false;
        try {
            SignalFilter filter = new SignalFilter();
            filter.add(CarSignalManager.SignalId_DstTrvld2);
            filter.add(CarSignalManager.SignalId_VehSpdAvgIndcdVehSpdIndcd);
            filter.add(CarSignalManager.SignalId_VehSpdAvgIndcdVeSpdIndcdUnit);
            current.registerCallback(callback, filter);
            callbackRegistered = true;
            return true;
        } catch (Throwable failure) {
            callbackRegistered = false;
            Log.w(TAG, "Trip 2 CarSignal callback registration failed", failure);
            return false;
        }
    }

    private synchronized void suspendCallbackWithoutDemand() {
        if (!listeners.isEmpty() || !callbackRegistered) return;
        CarSignalManager current = signals;
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
        CarSignalManager current = signals;
        signals = null;
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
