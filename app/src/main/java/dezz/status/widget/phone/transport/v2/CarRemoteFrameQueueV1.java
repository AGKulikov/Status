/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Bounded C5 output queue which protects catalog/session frames and coalesces telemetry state.
 *
 * <p>A complete catalog is the capability contract between Android and Helper. Dropping its
 * oldest frames produces a superficially completed, but mostly disabled, Helper UI. State frames
 * are snapshots and may be replaced safely; catalog, result and sync-boundary frames may not.</p>
 */
public final class CarRemoteFrameQueueV1 {
    static final int SOFT_LIMIT = 96;
    static final int HARD_LIMIT = 192;

    public enum OfferResult {
        ENQUEUED,
        REPLACED_STATE,
        EVICTED_STATE,
        REJECTED_INVALID,
        REJECTED_PRESSURE
    }

    private final Deque<byte[]> frames = new ArrayDeque<>();

    public OfferResult offer(byte[] raw) {
        IphoneCarRemoteProtocolV1.Frame incoming = IphoneCarRemoteProtocolV1.decode(raw);
        if (incoming == null) return OfferResult.REJECTED_INVALID;

        boolean replaced = false;
        if (incoming.type == IphoneCarRemoteProtocolV1.Type.STATE) {
            Iterator<byte[]> iterator = frames.iterator();
            while (iterator.hasNext()) {
                IphoneCarRemoteProtocolV1.Frame queued =
                        IphoneCarRemoteProtocolV1.decode(iterator.next());
                if (queued != null && queued.type == IphoneCarRemoteProtocolV1.Type.STATE
                        && queued.controlId == incoming.controlId) {
                    iterator.remove();
                    replaced = true;
                    break;
                }
            }
        }

        boolean evicted = false;
        while (frames.size() >= SOFT_LIMIT && removeOldestState()) evicted = true;
        if (frames.size() >= HARD_LIMIT) {
            // Protected bootstrap/command frames are never silently displaced. This can only be
            // reached after several full bootstrap generations without a draining ATT channel.
            return OfferResult.REJECTED_PRESSURE;
        }
        frames.addLast(raw.clone());
        if (replaced) return OfferResult.REPLACED_STATE;
        return evicted ? OfferResult.EVICTED_STATE : OfferResult.ENQUEUED;
    }

    public byte[] poll() {
        return frames.pollFirst();
    }

    /** Requeues an ATT operation which was synchronously rejected before reaching the wire. */
    public OfferResult offerFirst(byte[] raw) {
        if (IphoneCarRemoteProtocolV1.decode(raw) == null) {
            return OfferResult.REJECTED_INVALID;
        }
        while (frames.size() >= HARD_LIMIT && removeOldestState()) {
            // Protected frames retain priority over replaceable snapshots.
        }
        if (frames.size() >= HARD_LIMIT) return OfferResult.REJECTED_PRESSURE;
        frames.addFirst(raw.clone());
        return OfferResult.ENQUEUED;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public int size() {
        return frames.size();
    }

    public void clear() {
        frames.clear();
    }

    private boolean removeOldestState() {
        Iterator<byte[]> iterator = frames.iterator();
        while (iterator.hasNext()) {
            IphoneCarRemoteProtocolV1.Frame queued =
                    IphoneCarRemoteProtocolV1.decode(iterator.next());
            if (queued != null && queued.type == IphoneCarRemoteProtocolV1.Type.STATE) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
}
