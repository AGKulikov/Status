/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import dezz.status.widget.phone.transport.v2.BleRouteToken;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Android-P/ECARX implementation of the Route-B reverse-owner seam.
 *
 * <p>The hidden opportunistic overload is isolated in this class. There is exactly one invocation
 * for the exact {@link BluetoothDevice} object captured by the server callback, no public-GATT
 * fallback, and no retry. Unsupported platforms fail closed. Retirement is close-only: this class
 * never calls {@link BluetoothGatt#disconnect()}.</p>
 */
public final class PieReverseGattObserverV2 implements ReverseGattObserverV2 {
    private static final int ANDROID_P_API = 28;

    private static final class Observation {
        final BleRouteToken token;
        final BluetoothDevice physicalFacade;
        final BluetoothGattCallback delegate;
        final Listener listener;
        BluetoothGatt gatt;
        boolean observed;
        boolean invocationReturned;
        boolean pendingConnection;
        int pendingStatus;
        int pendingState;
        boolean retiringWhenRegistrationProven;

        Observation(BleRouteToken token, BluetoothDevice physicalFacade,
                    BluetoothGattCallback delegate, Listener listener) {
            this.token = token;
            this.physicalFacade = physicalFacade;
            this.delegate = delegate;
            this.listener = listener;
        }
    }

    private final Context context;
    private final Handler main;
    private final BluetoothManager manager;
    private Observation current;

    public PieReverseGattObserverV2(Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        this.manager = (BluetoothManager) this.context.getSystemService(
                Context.BLUETOOTH_SERVICE);
    }

    @Override public void observe(BleRouteToken token,
                                  BluetoothDevice capturedInboundPhysicalFacade,
                                  BluetoothGattCallback callback,
                                  Listener listener) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(capturedInboundPhysicalFacade,
                "capturedInboundPhysicalFacade");
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(listener, "listener");
        dispatchMain(() -> observeOnMain(token, capturedInboundPhysicalFacade,
                callback, listener));
    }

    private void observeOnMain(BleRouteToken token, BluetoothDevice physicalFacade,
                               BluetoothGattCallback callback, Listener listener) {
        if (Build.VERSION.SDK_INT != ANDROID_P_API) {
            postUnavailable(listener, token, "opportunistic observer requires Android P");
            return;
        }
        if (current != null) {
            postUnavailable(listener, token, "one reverse observer owner already exists");
            return;
        }
        Observation candidate = new Observation(token, physicalFacade, callback, listener);
        current = candidate;
        attemptObservation(candidate);
    }

    private void attemptObservation(Observation candidate) {
        if (current != candidate) return;
        if (manager == null || manager.getAdapter() == null
                || !manager.getAdapter().isEnabled()) {
            failCandidate(candidate, "Bluetooth radio is off");
            return;
        }
        if (!ProcessGattRegistrationGateV2.tryAcquire(candidate)) {
            ProcessGattRegistrationGateV2.whenFree(candidate,
                    () -> dispatchMain(() -> attemptObservation(candidate)));
            return;
        }
        BluetoothGattCallback bridge = bridge(candidate);
        try {
            Method hidden = BluetoothDevice.class.getMethod(
                    "connectGatt", Context.class, boolean.class,
                    BluetoothGattCallback.class, int.class, boolean.class,
                    int.class, Handler.class);
            Object value = hidden.invoke(candidate.physicalFacade, context, false, bridge,
                    BluetoothDevice.TRANSPORT_LE, true,
                    BluetoothDevice.PHY_LE_1M_MASK, main);
            if (!(value instanceof BluetoothGatt)) {
                failCandidate(candidate, "hidden observer returned no GATT owner");
                return;
            }
            BluetoothGatt returned = (BluetoothGatt) value;
            if (current != candidate) {
                closeOnly(returned);
                ProcessGattRegistrationGateV2.release(candidate);
                return;
            }
            if ((candidate.gatt != null && candidate.gatt != returned)
                    || returned.getDevice() != candidate.physicalFacade) {
                BluetoothGatt callbackGatt = candidate.gatt;
                current = null;
                closeOnly(callbackGatt);
                if (returned != callbackGatt) closeOnly(returned);
                ProcessGattRegistrationGateV2.release(candidate);
                postUnavailable(candidate.listener, candidate.token,
                        "hidden observer returned a different physical facade/wrapper");
                return;
            }
            candidate.gatt = returned;
            candidate.invocationReturned = true;
            if (candidate.pendingConnection) {
                int status = candidate.pendingStatus;
                int state = candidate.pendingState;
                candidate.pendingConnection = false;
                deliverConnection(candidate, returned, status, state);
            }
        } catch (NoSuchMethodException | IllegalAccessException
                | InvocationTargetException | RuntimeException error) {
            failCandidate(candidate,
                    "hidden opportunistic registration unavailable: "
                            + error.getClass().getSimpleName());
        }
    }

    @Override public void closeOnly(BleRouteToken token, BluetoothGatt gatt) {
        Objects.requireNonNull(token, "token");
        dispatchMain(() -> {
            Observation exact = current;
            if (exact == null || !exact.token.equals(token) || exact.gatt != gatt) return;
            current = null;
            ProcessGattRegistrationGateV2.cancelWaiter(exact);
            closeOnly(gatt);
            ProcessGattRegistrationGateV2.release(exact);
        });
    }

    @Override public void cancel(BleRouteToken token) {
        Objects.requireNonNull(token, "token");
        dispatchMain(() -> {
            Observation exact = current;
            if (exact == null || !exact.token.equals(token) || exact.observed) return;
            ProcessGattRegistrationGateV2.cancelWaiter(exact);
            if (!ProcessGattRegistrationGateV2.owns(exact)) {
                current = null;
                closeOnly(exact.gatt);
                return;
            }
            // A returned Java wrapper may still have private clientIf==0. Retain the exact
            // registration lease until its first callback proves close() can unregister it.
            exact.retiringWhenRegistrationProven = true;
        });
    }

    private BluetoothGattCallback bridge(Observation candidate) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                           int newState) {
                dispatchMain(() -> {
                    if (current == candidate && candidate.gatt != null
                            && candidate.gatt != gatt) {
                        current = null;
                        closeOnly(candidate.gatt);
                        closeOnly(gatt);
                        ProcessGattRegistrationGateV2.release(candidate);
                        candidate.listener.onUnavailable(candidate.token,
                                "reverse callback used a different GATT wrapper");
                        return;
                    }
                    if (!isCurrent(candidate, gatt)) return;
                    if (!candidate.invocationReturned) {
                        candidate.pendingConnection = true;
                        candidate.pendingStatus = status;
                        candidate.pendingState = newState;
                        return;
                    }
                    deliverConnection(candidate, gatt, status, newState);
                });
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                dispatchMain(() -> {
                    if (isCurrent(candidate, gatt)) {
                        candidate.delegate.onServicesDiscovered(gatt, status);
                    }
                });
            }

            @Override public void onDescriptorWrite(BluetoothGatt gatt,
                                                     BluetoothGattDescriptor descriptor,
                                                     int status) {
                dispatchMain(() -> {
                    if (isCurrent(candidate, gatt)) {
                        candidate.delegate.onDescriptorWrite(gatt, descriptor, status);
                    }
                });
            }

            @Override public void onCharacteristicWrite(
                    BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                dispatchMain(() -> {
                    if (isCurrent(candidate, gatt)) {
                        candidate.delegate.onCharacteristicWrite(gatt, characteristic, status);
                    }
                });
            }

            @Override public void onCharacteristicChanged(
                    BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                byte[] value = characteristic == null || characteristic.getValue() == null
                        ? null : characteristic.getValue().clone();
                dispatchMain(() -> {
                    if (!isCurrent(candidate, gatt) || characteristic == null) return;
                    characteristic.setValue(value);
                    candidate.delegate.onCharacteristicChanged(gatt, characteristic);
                });
            }

            @Override public void onCharacteristicChanged(
                    BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                    byte[] value) {
                byte[] exact = value == null ? null : value.clone();
                dispatchMain(() -> {
                    if (isCurrent(candidate, gatt)) {
                        candidate.delegate.onCharacteristicChanged(gatt, characteristic, exact);
                    }
                });
            }
        };
    }

    private boolean isCurrent(Observation candidate, BluetoothGatt callbackGatt) {
        if (current != candidate || !ProcessGattRegistrationGateV2.owns(candidate)) {
            return false;
        }
        if (candidate.gatt == null) candidate.gatt = callbackGatt;
        return candidate.gatt == callbackGatt;
    }

    private void deliverConnection(Observation candidate, BluetoothGatt gatt,
                                   int status, int newState) {
        if (!isCurrent(candidate, gatt)) return;
        boolean sameCapturedInboundPhysicalFacade = gatt != null
                && gatt.getDevice() == candidate.physicalFacade;
        boolean exactlyOneOwner = ProcessGattRegistrationGateV2.owns(candidate);
        if (!sameCapturedInboundPhysicalFacade || !exactlyOneOwner) {
            current = null;
            closeOnly(gatt);
            ProcessGattRegistrationGateV2.release(candidate);
            candidate.listener.onUnavailable(candidate.token,
                    "reverse callback did not retain the captured physical facade");
            return;
        }
        if (candidate.retiringWhenRegistrationProven) {
            current = null;
            closeOnly(gatt);
            ProcessGattRegistrationGateV2.release(candidate);
            candidate.listener.onUnavailable(candidate.token,
                    "quarantined reverse registration retired after exact callback");
            return;
        }
        if (!candidate.observed && status == BluetoothGatt.GATT_SUCCESS
                && newState == BluetoothProfile.STATE_CONNECTED) {
            candidate.observed = true;
            candidate.listener.onObserved(candidate.token, gatt,
                    sameCapturedInboundPhysicalFacade, exactlyOneOwner);
        } else if (!candidate.observed
                && newState == BluetoothProfile.STATE_DISCONNECTED) {
            current = null;
            closeOnly(gatt);
            ProcessGattRegistrationGateV2.release(candidate);
            candidate.listener.onUnavailable(candidate.token,
                    "reverse owner disconnected before observation");
            return;
        }
        candidate.delegate.onConnectionStateChange(gatt, status, newState);
    }

    private void failCandidate(Observation candidate, String detail) {
        if (current != candidate) return;
        current = null;
        ProcessGattRegistrationGateV2.cancelWaiter(candidate);
        closeOnly(candidate.gatt);
        ProcessGattRegistrationGateV2.release(candidate);
        postUnavailable(candidate.listener, candidate.token, detail);
    }

    private void postUnavailable(Listener listener, BleRouteToken token, String detail) {
        main.post(() -> listener.onUnavailable(token, detail));
    }

    private static void closeOnly(BluetoothGatt gatt) {
        try {
            if (gatt != null) gatt.close();
        } catch (RuntimeException ignored) {
            // No disconnect fallback is permitted for the captured physical facade.
        }
    }

    /** Inline on main prevents a queued watchdog from overtaking its callback body. */
    private void dispatchMain(Runnable callbackBody) {
        if (Looper.myLooper() == main.getLooper()) {
            callbackBody.run();
        } else {
            main.post(callbackBody);
        }
    }
}
