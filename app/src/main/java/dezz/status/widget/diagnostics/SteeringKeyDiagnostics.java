/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.view.KeyEvent;
import java.util.concurrent.atomic.AtomicLong;

/** Records media-key metadata only; ordinary keyboard input and typed text are excluded. */
public final class SteeringKeyDiagnostics {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private SteeringKeyDiagnostics() {}

    public static long received(KeyEvent event, long callbackUptime) {
        if (!DiagnosticJournal.isEnabled() || !isMediaKey(event.getKeyCode())) return 0L;
        long sequence = SEQUENCE.incrementAndGet();
        DiagnosticJournal.infoAsync("steering-input", "input_sequence=" + sequence
                + ", key_code=" + event.getKeyCode() + ", action=" + event.getAction()
                + ", repeat=" + event.getRepeatCount() + ", scan_code=" + event.getScanCode()
                + ", device_id=" + event.getDeviceId() + ", source=" + event.getSource()
                + ", flags=" + event.getFlags() + ", event=" + event.getEventTime()
                + ", down=" + event.getDownTime() + ", callback=" + callbackUptime
                + ", input_delay_ms=" + Math.max(0L, callbackUptime - event.getEventTime()));
        return sequence;
    }

    public static void result(long sequence, boolean consumed, String reason) {
        if (sequence == 0L) return;
        DiagnosticJournal.infoAsync("steering-input", "input_sequence=" + sequence
                + ", consumed=" + consumed + ", reason=" + reason);
    }

    private static boolean isMediaKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_STOP
                || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || code == KeyEvent.KEYCODE_MEDIA_REWIND
                || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE
                || code == KeyEvent.KEYCODE_MUTE;
    }
}
