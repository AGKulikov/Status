/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Bounded, thread-safe time association only; never a command queue or acknowledgement. */
final class MediaKeyObservation {
    private static final long WINDOW_MS = 15_000L;
    private static final int MAX_PRESSES = 32;
    private final ArrayDeque<Press> recent = new ArrayDeque<>();
    private long nextSequence;

    static final class Press {
        final long sequence, downTime, firstObserved;
        final int keyCode, deviceId;
        boolean downObserved;
        Press(long sequence, int keyCode, int deviceId, long downTime, long now) {
            this.sequence = sequence;
            this.keyCode = keyCode;
            this.deviceId = deviceId;
            this.downTime = downTime;
            this.firstObserved = now;
        }
    }

    static final class Candidate {
        final long sequence, delayMs;
        final int count;
        Candidate(long sequence, long delayMs, int count) {
            this.sequence = sequence;
            this.delayMs = delayMs;
            this.count = count;
        }
    }

    synchronized Press received(int keyCode, int deviceId, long downTime, long eventTime,
                                int action, int repeat, long now) {
        prune(now);
        Press press = null;
        for (Press value : recent) {
            if (downTime > 0L && value.downTime == downTime && value.keyCode == keyCode
                    && value.deviceId == deviceId) { press = value; break; }
        }
        if (press == null) {
            if (recent.size() == MAX_PRESSES) recent.removeFirst();
            press = new Press(++nextSequence, keyCode, deviceId, downTime, now);
            recent.addLast(press);
        }
        if (action == 0 && repeat == 0 && eventTime > 0 && eventTime <= now) press.downObserved = true;
        return press;
    }

    synchronized Candidate candidate(long now) {
        prune(now);
        Press latest = null;
        int count = 0;
        for (Press press : recent) {
            if (press.downObserved && now >= press.firstObserved) { latest = press; count++; }
        }
        return new Candidate(latest == null ? 0L : latest.sequence,
                latest == null ? -1L : now - latest.firstObserved, count);
    }

    synchronized void clear() { recent.clear(); }

    private void prune(long now) {
        for (Iterator<Press> iterator = recent.iterator(); iterator.hasNext();) {
            if (now - iterator.next().firstObserved > WINDOW_MS) iterator.remove();
        }
    }
}
