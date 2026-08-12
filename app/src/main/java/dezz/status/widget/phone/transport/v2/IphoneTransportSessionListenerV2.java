/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/**
 * Adapter-facing listener with an explicit durable identity commit barrier.
 *
 * <p>A newly observed Helper installation id is only a proposal.  The adapter must not send an
 * ATT success response, advance its reducer, or expose the peer as authenticated until the
 * completion returns {@code true}.  Completions are asynchronous and may arrive after the raw
 * GATT callback, so adapters must fence them by their exact route epoch and owner.</p>
 */
public interface IphoneTransportSessionListenerV2 extends IphoneTransportListenerV2 {
    interface HelperIdentityCompletion {
        void onAccepted(boolean accepted);
    }

    void offerHelperInstallationId(
            String helperInstallationId,
            HelperIdentityCompletion completion
    );
}
