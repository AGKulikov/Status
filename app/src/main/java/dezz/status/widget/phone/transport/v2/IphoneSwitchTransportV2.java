/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;

/**
 * Exact adapter facade consumed by {@code BleRoleSwitchCoordinator.EffectsPort}.
 *
 * <p>Callbacks always echo the complete coordinator descriptor. Implementations must dispatch
 * completion later on their serialized executor; synchronous re-entry from an EffectsPort call is
 * forbidden.</p>
 */
public interface IphoneSwitchTransportV2 extends IphoneTransportV2 {
    enum FreezeResult {
        FROZEN_WITH_REMOTE_CONTROL,
        FROZEN_NO_REMOTE_OWNER,
        FAILED
    }

    interface FreezeCompletion {
        void onFrozen(Owner exactOwner, FreezeResult result);
    }

    interface ControlCompletion {
        void onComplete(ControlTransmit exactTransmit, ControlTransmitResult result);
    }

    interface RetryDue {
        void onDue(ControlTransmit exactTransmit);
    }

    interface RestorationDrainCompletion {
        void onPrepared(Owner exactOwner, boolean success);

        void onLocalTerminal(Owner exactOwner);
    }

    /**
     * Binds a fresh-process persisted source solely for fail-closed drain. Implementations must
     * not start, scan, connect, publish, or allocate a framework BLE owner from this call.
     */
    void prepareRestorationDrain(Owner source, RestorationDrainCompletion completion);

    /** Radio-off is terminal for this activation; radio-on always uses a fresh route epoch. */
    void radioOff(BleRouteEpoch epoch);

    /** Freezes source ingress for the exact process/epoch/generation/role owner. */
    void freezeIngress(Owner source, FreezeCompletion completion);

    /** Begins one exact asynchronous C/A write-with-response or indication attempt. */
    void transmitControl(ControlTransmit transmit, ControlCompletion completion);

    /** Arms one bounded retry without extending {@link ControlTransmit#stopDeadlineMillis()}. */
    void scheduleControlRetry(ControlTransmit transmit, RetryDue callback);

    void cancelControlRetry(ControlTransmit transmit);

    /** Begins deterministic client-first teardown only after the coordinator authorizes it. */
    void beginConfirmedModeSwitchStop(Owner source);

    /** Returns route aggregate 0/1; a mismatched owner must never be reported as zero. */
    int appOwnedOwnerCount(Owner source);
}
