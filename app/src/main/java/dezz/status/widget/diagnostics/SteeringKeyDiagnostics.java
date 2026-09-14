/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.MediaNotificationListener;

/**
 * Passive copies of the MConfig/Android broadcasts, plus read-only player feedback.
 * No accessibility filter, MediaSession of our own, command dispatch or player selection.
 *
 * Explicit/aborted broadcasts and internal MConfig/system calls can be invisible. Receipt is
 * not proof of a hardware press; nearby session feedback is correlation, never an audio ACK.
 */
public final class SteeringKeyDiagnostics {
    static final String IEDIA_BUTTON = "android.intent.action.IEDIA_BUTTON";
    private static final long HEALTH_MS = 30_000L;
    private static final int MAX_SESSIONS = 16;
    private static volatile SteeringKeyDiagnostics instance;

    private final Context context;
    private final Handler input;
    private final Handler sessions;
    private final MediaKeyObservation observation = new MediaKeyObservation();
    // Separate arrival-time correlation includes delayed/virtual system keys. It must not be
    // presented as a hardware press or merged with the physical-origin-unverified broadcast ID.
    private final MediaKeyObservation systemObservation = new MediaKeyObservation();
    private final MediaKeySystemLog systemLog;
    private final List<Watch> watches = new ArrayList<>();
    private final ComponentName listener;
    private final MediaSessionManager manager;
    private volatile boolean debugEnabled;
    private volatile boolean recording;
    private volatile boolean active;
    private volatile long generation;
    private boolean receiverRegistered;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionListener;
    private long rateWindow;
    private int rateCount;
    private long receivedCount;
    private long droppedCount;
    private volatile String sessionAccess = "not_checked";
    private volatile int sessionCount;

    private final Runnable reconcile = this::reconcileOnInput;
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (!active) return;
            systemLog.start();
            emit("stage=coverage, broadcasts_received=" + receivedCount
                    + ", input_rate_dropped=" + droppedCount + ", session_access=" + sessionAccess
                    + ", sessions=" + sessionCount
                    + ", system_log=" + systemLog.coverage()
                    + ", system_dispatch=unobserved, audio_output=unobserved"
                    + ", explicit_or_aborted_broadcasts_may_be_invisible=true");
            input.postDelayed(this, HEALTH_MS);
        }
    };
    private final Runnable refreshSessions = new Runnable() {
        @Override public void run() {
            long expected = generation;
            if (!current(expected)) return;
            try {
                if (manager == null) {
                    sessionAccess = "manager_absent";
                } else {
                    if (sessionListener == null) {
                        sessionListener = controllers -> {
                            if (!current(expected)) return;
                            try { replaceSessions(controllers, expected); }
                            catch (RuntimeException failure) {
                                sessionAccess = "callback_failed_" + failure.getClass().getSimpleName();
                                emit("stage=session_coverage, access=" + sessionAccess);
                            }
                        };
                        manager.addOnActiveSessionsChangedListener(sessionListener, listener, sessions);
                    }
                    replaceSessions(manager.getActiveSessions(listener), expected);
                    sessionAccess = "granted";
                }
            } catch (SecurityException denied) {
                sessionAccess = "notification_access_required";
                clearSessionListener();
                clearSessions();
            } catch (RuntimeException failure) {
                sessionAccess = "failed_" + failure.getClass().getSimpleName();
                clearSessionListener();
                clearSessions();
            }
            if (current(expected)) {
                emit("stage=session_coverage, access=" + sessionAccess + ", sessions=" + sessionCount);
                sessions.postDelayed(this, HEALTH_MS);
            }
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            final long now = SystemClock.uptimeMillis();
            if (!active || intent == null) return;
            String action = intent.getAction();
            if (!IEDIA_BUTTON.equals(action) && !Intent.ACTION_MEDIA_BUTTON.equals(action)) return;
            // The callback runs on its own looper, not main or the player/Binder observer.
            if (now - rateWindow >= 1_000L) { rateWindow = now; rateCount = 0; }
            if (++rateCount > 32) { droppedCount++; return; }
            try {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || !isMediaKey(event.getKeyCode())) return;
                MediaKeyObservation.Press press = observation.received(event.getKeyCode(),
                        event.getDeviceId(), event.getDownTime(), event.getEventTime(),
                        event.getAction(), event.getRepeatCount(), now);
                receivedCount++;
                emit("stage=broadcast_observed, input_sequence=" + press.sequence
                        + ", broadcast=" + action + ", key_code=" + event.getKeyCode()
                        + ", action=" + event.getAction() + ", repeat=" + event.getRepeatCount()
                        + ", scan_code=" + event.getScanCode() + ", device_id=" + event.getDeviceId()
                        + ", source=" + event.getSource() + ", flags=" + event.getFlags()
                        + ", event_uptime_ms=" + event.getEventTime()
                        + ", down_uptime_ms=" + event.getDownTime() + ", observed_uptime_ms=" + now
                        + ", input_delivery_ms=" + delay(now, event.getEventTime())
                        + ", since_first_observation_ms=" + (now - press.firstObserved)
                        + ", down_observed=" + press.downObserved
                        + ", ordered=" + isOrderedBroadcast()
                        + ", physical_origin=unverified, consumed=false, commands_sent=0");
            } catch (RuntimeException invalid) {
                emit("stage=input_rejected, reason=" + invalid.getClass().getSimpleName());
            }
            // Never abort, change a result, forward the Intent, call goAsync, or query a player.
        }
    };

    private SteeringKeyDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        systemLog = new MediaKeySystemLog(this.context, this::systemKeyObserved, this::emit);
        listener = new ComponentName(context, MediaNotificationListener.class);
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        HandlerThread inputThread = new HandlerThread("media-key-observer", Process.THREAD_PRIORITY_BACKGROUND);
        HandlerThread sessionThread = new HandlerThread("media-session-observer", Process.THREAD_PRIORITY_BACKGROUND);
        inputThread.start();
        sessionThread.start();
        input = new Handler(inputThread.getLooper());
        sessions = new Handler(sessionThread.getLooper());
        debugEnabled = DiagnosticJournal.isEnabled();
        ActionRecorder.addRecordingListener(enabled -> {
            recording = enabled;
            scheduleReconcile();
        });
        scheduleReconcile();
    }

    /** Main process only; lifecycle is independent of whether Accessibility is enabled. */
    public static void initialize(Context context) {
        if (instance != null) return;
        synchronized (SteeringKeyDiagnostics.class) {
            if (instance == null) instance = new SteeringKeyDiagnostics(context);
        }
    }

    public static void debugChanged(boolean enabled) {
        SteeringKeyDiagnostics value = instance;
        if (value == null) return;
        value.debugEnabled = enabled;
        value.scheduleReconcile();
    }

    private void scheduleReconcile() {
        input.removeCallbacks(reconcile);
        input.post(reconcile);
    }

    private void reconcileOnInput() {
        boolean wanted = debugEnabled || recording;
        if (wanted == active) return;
        active = wanted;
        long expected = ++generation;
        input.removeCallbacks(health);
        observation.clear();
        systemObservation.clear();
        sessions.removeCallbacks(refreshSessions);
        if (wanted) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(IEDIA_BUTTON);
            filter.addAction(Intent.ACTION_MEDIA_BUTTON);
            // Observe ordered legacy broadcasts AFTER normal consumers, never ahead of MConfig.
            filter.setPriority(-1_000);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, null, input, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter, null, input);
                }
                receiverRegistered = true;
            } catch (RuntimeException failure) {
                emit("stage=input_coverage, receiver_registered=false, reason="
                        + failure.getClass().getSimpleName());
            }
            receivedCount = droppedCount = 0L;
            rateWindow = 0L;
            rateCount = 0;
            emit("stage=capture_started, receiver_registered=" + receiverRegistered
                    + ", mode=passive_broadcast_and_session, accessibility_filter=false"
                    + ", mconfig_settings=unobserved, system_dispatch=unobserved, commands_sent=0");
            input.post(health);
            sessions.post(() -> {
                if (!current(expected)) return;
                clearSessionListener();
                clearSessions();
                refreshSessions.run();
            });
        } else {
            systemLog.stop();
            if (receiverRegistered) {
                try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
                receiverRegistered = false;
            }
            sessions.post(() -> {
                if (generation != expected) return;
                clearSessions();
                clearSessionListener();
            });
        }
    }

    private boolean current(long expected) { return active && generation == expected; }

    private void systemKeyObserved(MediaKeyLogRecord record) {
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        long sequence = 0L;
        // Do not invent a key timestamp from the log reader's delivery time. Only complete
        // KeyEvent fields can share the existing broadcast/session association window.
        if (record.deviceIdPresent && record.downTime > 0 && record.eventTime > 0
                && record.eventTime <= now && now - record.eventTime <= 15_000L) {
            sequence = observation.received(record.keyCode, record.deviceId, record.downTime,
                    record.eventTime, record.action, record.repeat, now).sequence;
        }
        long systemSequence = systemObservation.received(record.keyCode,
                record.deviceIdPresent ? record.deviceId : Integer.MIN_VALUE,
                record.downTime, record.eventTime, record.action, record.repeat, now).sequence;
        emit("stage=system_key_log, input_sequence=" + sequence + ", source_tag=" + record.tag
                + ", system_sequence=" + systemSequence + ", system_stage=" + record.stage
                + ", source_pid=" + record.sourcePid + ", source_tid=" + record.sourceTid
                + ", caller_pid=" + record.callerPid + ", caller_uid=" + record.callerUid
                + ", source_time=" + record.timestamp + ", key_code=" + record.keyCode
                + ", action=" + record.action + ", event_uptime_ms=" + record.eventTime
                + ", down_uptime_ms=" + record.downTime + ", device_id=" + record.deviceId
                + ", device_id_present=" + record.deviceIdPresent + ", repeat=" + record.repeat
                + ", observed_uptime_ms=" + now + ", observed_wall_ms=" + System.currentTimeMillis()
                + ", event_age_ms=" + delay(now, record.eventTime)
                + ", physical_origin=unverified, commands_sent=0, audio_ack=false");
    }

    /** Worker-only: follows all observable sessions without selecting or controlling one. */
    private void replaceSessions(List<MediaController> controllers, long expected) {
        if (!current(expected)) return;
        List<MediaController> next = controllers == null ? new ArrayList<>() : controllers;
        for (int index = watches.size() - 1; index >= 0; index--) {
            Watch watch = watches.get(index);
            boolean present = false;
            for (int i = 0; i < next.size() && i < MAX_SESSIONS; i++) {
                if (watch.controller.getSessionToken().equals(next.get(i).getSessionToken())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                watch.controller.unregisterCallback(watch.callback);
                watches.remove(index);
                emit("stage=session_removed, package=" + watch.packageName);
            }
        }
        for (int i = 0; i < next.size() && i < MAX_SESSIONS; i++) {
            MediaController controller = next.get(i);
            boolean present = false;
            for (Watch watch : watches) {
                if (watch.controller.getSessionToken().equals(controller.getSessionToken())) {
                    present = true;
                    break;
                }
            }
            if (present || !current(expected)) continue;
            Watch watch = new Watch(controller, expected);
            controller.registerCallback(watch.callback, sessions);
            watches.add(watch);
            watch.feedback("session_snapshot", controller.getPlaybackState());
        }
        sessionCount = watches.size();
        if (next.size() > MAX_SESSIONS) emit("stage=session_limit, omitted=" + (next.size() - MAX_SESSIONS));
    }

    private void clearSessions() {
        for (Watch watch : watches) {
            try { watch.controller.unregisterCallback(watch.callback); } catch (RuntimeException ignored) {}
        }
        watches.clear();
        sessionCount = 0;
    }

    private void clearSessionListener() {
        if (manager != null && sessionListener != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessionListener); }
            catch (RuntimeException ignored) {}
        }
        sessionListener = null;
    }

    private final class Watch {
        final MediaController controller;
        final String packageName;
        final long epoch;
        final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                feedback("player_state_observed", state);
            }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                // No track title, artist, media ID, artwork or notification content is logged.
                feedback("metadata_callback_observed", null);
            }
            @Override public void onSessionDestroyed() {
                if (!current(epoch) || !watches.contains(Watch.this)) return;
                feedback("session_destroyed", null);
                sessions.removeCallbacks(refreshSessions);
                sessions.post(refreshSessions);
            }
        };
        Watch(MediaController controller, long epoch) {
            this.controller = controller;
            this.packageName = controller.getPackageName();
            this.epoch = epoch;
        }
        void feedback(String stage, PlaybackState state) {
            if (!current(epoch) || !watches.contains(this)) return;
            long now = SystemClock.uptimeMillis();
            MediaKeyObservation.Candidate candidate = observation.candidate(now);
            MediaKeyObservation.Candidate systemCandidate = systemObservation.candidate(now);
            emit("stage=" + stage + ", package=" + packageName + ", observed_uptime_ms=" + now
                    + ", state=" + (state == null ? -1 : state.getState())
                    + ", state_update_uptime_ms=" + (state == null ? -1 : state.getLastPositionUpdateTime())
                    + ", after_input_sequence=" + candidate.sequence
                    + ", nearby_presses=" + candidate.count
                    + ", since_input_observed_ms=" + candidate.delayMs
                    + ", after_system_sequence=" + systemCandidate.sequence
                    + ", nearby_system_events=" + systemCandidate.count
                    + ", since_system_observed_ms=" + systemCandidate.delayMs
                    + ", association=temporal_only, command_delivery=unobserved, audio_output=unobserved");
        }
    }

    private void emit(String text) {
        if (!active) return;
        DiagnosticJournal.infoAsync("steering-input", text);
        ActionRecorder.recordAsync(ActionRecorder.SOURCE_STEERING_KEY, "MEDIA_PATH_OBSERVATION",
                ActionRecorder.object("trace", text));
    }

    private static long delay(long now, long event) { return event > 0 && event <= now ? now - event : -1L; }

    static boolean isMediaKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_STOP || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_REWIND
                || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
                || code == KeyEvent.KEYCODE_MUTE;
    }
}
