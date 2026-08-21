/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.phone.transport.v2.CarRemoteControlRegistryV1;
import dezz.status.widget.phone.transport.v2.IphoneCarRemoteProtocolV1;

/**
 * Capability-gated bridge between Helper 53 C5 frames and the existing vehicle-control backend.
 * All mutable state and all car callbacks are main-thread confined.
 */
final class CarRemoteControllerV1 {
    interface Sender { void send(byte[] frame); }

    private static final int MAX_COMMANDS_PER_SECOND = 12;
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final long HELLO_COALESCE_MS = 12_000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CarIntegration car;
    private final AudioManager audio;
    private final Sender sender;
    private final Map<String, CarControlDescriptor> catalog = new HashMap<>();
    private final Set<String> subscribedIds = new LinkedHashSet<>();
    private final CarIntegration.ControlStateListener stateListener = this::sendState;

    private boolean sessionOpen;
    private long lastInboundSequence;
    private long outboundSequence;
    private long rateWindowStarted;
    private int rateWindowCommands;
    private long sessionGeneration;
    private long sessionStartedElapsed;
    private int coalescedHellos;

    CarRemoteControllerV1(@NonNull Context context, @NonNull Sender sender) {
        this.context = context.getApplicationContext();
        this.car = CarIntegrations.get(this.context);
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.sender = sender;
    }

    void accept(byte[] rawFrame) {
        IphoneCarRemoteProtocolV1.Frame frame = IphoneCarRemoteProtocolV1.decode(rawFrame);
        if (frame == null) return;
        long receivedAt = SystemClock.elapsedRealtime();
        main.post(() -> acceptOnMain(frame, receivedAt));
    }

    void routeUnavailable() {
        main.post(this::resetSessionOnMain);
    }

    private void acceptOnMain(IphoneCarRemoteProtocolV1.Frame frame, long receivedAt) {
        if (frame.type == IphoneCarRemoteProtocolV1.Type.HELLO) {
            beginSession(frame.sequence);
            return;
        }
        if (!sessionOpen || frame.type != IphoneCarRemoteProtocolV1.Type.COMMAND) return;
        if (!isNewerSequence(frame.sequence, lastInboundSequence)) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.STALE);
            return;
        }
        lastInboundSequence = frame.sequence;
        long maximumAge = frame.maxAgeDeciseconds * 100L;
        if (SystemClock.elapsedRealtime() - receivedAt > maximumAge) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.TIMEOUT);
            return;
        }
        if (!acceptRate()) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.BUSY);
            return;
        }
        CarRemoteControlRegistryV1.Entry entry =
                CarRemoteControlRegistryV1.forWireId(frame.controlId);
        if (entry == null) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        if (entry.requiresConfirmation
                && (frame.flags & IphoneCarRemoteProtocolV1.FLAG_CONFIRMED) == 0) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.REJECTED);
            return;
        }
        if (!entry.requiresConfirmation
                && (frame.flags & IphoneCarRemoteProtocolV1.FLAG_CONFIRMED) != 0) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        if (entry.media) {
            executeMedia(entry, frame);
        } else {
            executeVehicle(entry, frame);
        }
    }

    private void beginSession(long helloSequence) {
        long now = SystemClock.elapsedRealtime();
        if (sessionOpen && now - sessionStartedElapsed < HELLO_COALESCE_MS) {
            coalescedHellos++;
            if (coalescedHellos == 1) {
                PhoneConnectionJournal.append("car-remote",
                        "повторный HELLO объединён с текущей синхронизацией C5");
            }
            return;
        }
        resetSessionOnMain();
        sessionOpen = true;
        sessionStartedElapsed = now;
        coalescedHellos = 0;
        long exactGeneration = ++sessionGeneration;
        lastInboundSequence = helloSequence;
        outboundSequence = helloSequence ^ 0x5a5a5a5aL;
        PhoneConnectionJournal.append("car-remote",
                "начата синхронизация C5 generation=" + exactGeneration);
        car.requestControlCatalog(descriptors -> {
            if (sessionOpen && sessionGeneration == exactGeneration) publishCatalog(descriptors);
        });
    }

    private void publishCatalog(@NonNull List<CarControlDescriptor> descriptors) {
        if (!sessionOpen) return;
        catalog.clear();
        subscribedIds.clear();
        for (CarControlDescriptor descriptor : descriptors) {
            catalog.put(descriptor.id, descriptor);
        }
        List<CarRemoteControlRegistryV1.Entry> entries =
                new ArrayList<>(CarRemoteControlRegistryV1.all());
        for (int index = 0; index < entries.size(); index++) {
            CarRemoteControlRegistryV1.Entry entry = entries.get(index);
            if (entry.media) {
                sendCatalog(entry, mediaKind(entry), audio != null,
                        index + 1 < entries.size());
                continue;
            }
            CarControlDescriptor descriptor = catalog.get(entry.controlId);
            boolean exposed = descriptor != null
                    && descriptor.availability != CarControlDescriptor.Availability.UNSUPPORTED;
            if (exposed) subscribedIds.add(entry.controlId);
            // CATALOG kind is required even for an unavailable control. Kind zero made the
            // frame invalid in both codecs, so Helper silently lost most of the registry.
            sendCatalog(entry, descriptor == null
                            ? CarControlDescriptor.Kind.ACTION.ordinal() + 1
                            : descriptor.kind.ordinal() + 1,
                    exposed, index + 1 < entries.size());
        }
        sendMediaVolumeState();
        send(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.SYNC_COMPLETE, 0, 0, 0, 0,
                nextOutboundSequence(), 0, 0));
        PhoneConnectionJournal.append("car-remote",
                "каталог C5 отправлен entries=" + entries.size()
                        + ", available=" + subscribedIds.size());
        // Subscribe only after the protected catalog boundary is queued. Some integrations call
        // the listener synchronously; publishing STATE earlier could overtake SYNC_COMPLETE.
        if (!subscribedIds.isEmpty()) {
            car.subscribeControlStates(new LinkedHashSet<>(subscribedIds), stateListener);
        }
    }

    private void sendCatalog(CarRemoteControlRegistryV1.Entry entry, int kind,
                             boolean available, boolean more) {
        int flags = available ? IphoneCarRemoteProtocolV1.FLAG_AVAILABLE : 0;
        if (entry.mechanical) flags |= IphoneCarRemoteProtocolV1.FLAG_MECHANICAL;
        if (entry.requiresConfirmation) {
            flags |= IphoneCarRemoteProtocolV1.FLAG_REQUIRES_CONFIRMATION;
        }
        if (more) flags |= IphoneCarRemoteProtocolV1.FLAG_MORE;
        send(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.CATALOG, entry.wireId, kind, flags, 0,
                nextOutboundSequence(), 0, 0));
    }

    private void sendState(@NonNull CarControlState state) {
        if (!sessionOpen || !subscribedIds.contains(state.controlId)) return;
        CarRemoteControlRegistryV1.Entry entry =
                CarRemoteControlRegistryV1.forControlId(state.controlId);
        if (entry == null) return;
        int flags = state.available ? IphoneCarRemoteProtocolV1.FLAG_AVAILABLE : 0;
        if (state.known) flags |= IphoneCarRemoteProtocolV1.FLAG_KNOWN;
        if (state.active) flags |= IphoneCarRemoteProtocolV1.FLAG_ACTIVE;
        if (entry.mechanical) flags |= IphoneCarRemoteProtocolV1.FLAG_MECHANICAL;
        if (entry.requiresConfirmation) {
            flags |= IphoneCarRemoteProtocolV1.FLAG_REQUIRES_CONFIRMATION;
        }
        int value = Double.isFinite(state.value)
                ? scaledWireValue(state.value, entry.scale) : 0;
        send(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.STATE, entry.wireId, 0, flags, 0,
                nextOutboundSequence(), value, 0));
    }

    private void executeVehicle(CarRemoteControlRegistryV1.Entry entry,
                                IphoneCarRemoteProtocolV1.Frame frame) {
        CarControlDescriptor descriptor = catalog.get(entry.controlId);
        if (descriptor == null
                || descriptor.availability == CarControlDescriptor.Availability.UNSUPPORTED) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.UNSUPPORTED);
            return;
        }
        IphoneCarRemoteProtocolV1.Operation operation =
                IphoneCarRemoteProtocolV1.Operation.fromWire(frame.code);
        CarControlCommand.Operation carOperation;
        if (operation == IphoneCarRemoteProtocolV1.Operation.SET) {
            carOperation = CarControlCommand.Operation.SET;
        } else if (operation == IphoneCarRemoteProtocolV1.Operation.TOGGLE) {
            carOperation = CarControlCommand.Operation.TOGGLE;
        } else if (operation == IphoneCarRemoteProtocolV1.Operation.CYCLE) {
            carOperation = CarControlCommand.Operation.CYCLE;
        } else if (operation == IphoneCarRemoteProtocolV1.Operation.ACTIVATE) {
            carOperation = CarControlCommand.Operation.ACTIVATE;
        } else {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        if (!commandValueMatches(carOperation, frame.value)) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        if (!operationMatches(descriptor.kind, carOperation)) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        double publicValue = frame.value / (double) entry.scale;
        long exactGeneration = sessionGeneration;
        car.executeControl(new CarControlCommand(entry.controlId, carOperation, publicValue),
                (success, message) -> {
                    if (!sessionOpen || sessionGeneration != exactGeneration) return;
                    sendResult(frame, success
                            ? IphoneCarRemoteProtocolV1.Result.OK
                            : IphoneCarRemoteProtocolV1.Result.REJECTED);
                });
    }

    private static boolean operationMatches(CarControlDescriptor.Kind kind,
                                            CarControlCommand.Operation operation) {
        switch (kind) {
            case TOGGLE:
                return operation == CarControlCommand.Operation.SET
                        || operation == CarControlCommand.Operation.TOGGLE;
            case LEVELS:
            case OPTIONS:
                return operation == CarControlCommand.Operation.SET
                        || operation == CarControlCommand.Operation.CYCLE;
            case RANGE:
                return operation == CarControlCommand.Operation.SET;
            case ACTION:
                return operation == CarControlCommand.Operation.ACTIVATE;
            default:
                return false;
        }
    }

    private static boolean commandValueMatches(CarControlCommand.Operation operation,
                                               int wireValue) {
        if (operation == CarControlCommand.Operation.SET) return true;
        if (operation == CarControlCommand.Operation.ACTIVATE) return wireValue == 1;
        return wireValue == 0;
    }

    private void executeMedia(CarRemoteControlRegistryV1.Entry entry,
                              IphoneCarRemoteProtocolV1.Frame frame) {
        if (audio == null) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.UNSUPPORTED);
            return;
        }
        IphoneCarRemoteProtocolV1.Operation operation =
                IphoneCarRemoteProtocolV1.Operation.fromWire(frame.code);
        boolean volume = "media.volume".equals(entry.controlId);
        if ((volume && (operation != IphoneCarRemoteProtocolV1.Operation.SET
                || frame.value < 0 || frame.value > 100 * entry.scale))
                || (!volume && (operation != IphoneCarRemoteProtocolV1.Operation.ACTIVATE
                || frame.value != 1))) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
            return;
        }
        try {
            switch (entry.controlId) {
                case "media.play_pause":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    break;
                case "media.next":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                    break;
                case "media.previous":
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    break;
                case "media.mute":
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                    break;
                case "media.volume":
                    int maximum = Math.max(1,
                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    double requested = frame.value / (double) entry.scale;
                    int target = (int) Math.round(Math.max(0d, Math.min(100d, requested))
                            * maximum / 100d);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target,
                            AudioManager.FLAG_SHOW_UI);
                    sendMediaVolumeState();
                    break;
                default:
                    sendResult(frame, IphoneCarRemoteProtocolV1.Result.INVALID);
                    return;
            }
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.OK);
        } catch (RuntimeException rejected) {
            sendResult(frame, IphoneCarRemoteProtocolV1.Result.REJECTED);
        }
    }

    private void dispatchMediaKey(int keyCode) {
        long time = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void sendMediaVolumeState() {
        if (!sessionOpen || audio == null) return;
        int maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        double percent = current * 100d / maximum;
        int flags = IphoneCarRemoteProtocolV1.FLAG_AVAILABLE
                | IphoneCarRemoteProtocolV1.FLAG_KNOWN
                | (current > 0 ? IphoneCarRemoteProtocolV1.FLAG_ACTIVE : 0);
        send(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.STATE, 54, 0, flags, 0,
                nextOutboundSequence(), scaledWireValue(percent, 100), 0));
    }

    private static int mediaKind(CarRemoteControlRegistryV1.Entry entry) {
        return "media.volume".equals(entry.controlId)
                ? CarControlDescriptor.Kind.RANGE.ordinal() + 1
                : CarControlDescriptor.Kind.ACTION.ordinal() + 1;
    }

    private static int scaledWireValue(double value, int scale) {
        double scaled = Math.rint(value * scale);
        if (scaled <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (scaled >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) scaled;
    }

    private boolean acceptRate() {
        long now = SystemClock.elapsedRealtime();
        if (now - rateWindowStarted >= RATE_WINDOW_MS) {
            rateWindowStarted = now;
            rateWindowCommands = 0;
        }
        return ++rateWindowCommands <= MAX_COMMANDS_PER_SECOND;
    }

    private void sendResult(IphoneCarRemoteProtocolV1.Frame command,
                            IphoneCarRemoteProtocolV1.Result result) {
        int flags = result == IphoneCarRemoteProtocolV1.Result.OK ? 0
                : IphoneCarRemoteProtocolV1.FLAG_ERROR;
        send(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.RESULT, command.controlId, result.wire,
                flags, command.transactionId, nextOutboundSequence(), command.value, 0));
    }

    private void send(IphoneCarRemoteProtocolV1.Frame frame) {
        if (sessionOpen) {
            sender.send(IphoneCarRemoteProtocolV1.encode(frame));
        }
    }

    private long nextOutboundSequence() {
        outboundSequence = (outboundSequence + 1L) & 0xffff_ffffL;
        if (outboundSequence == 0L) outboundSequence = 1L;
        return outboundSequence;
    }

    private static boolean isNewerSequence(long candidate, long previous) {
        long distance = (candidate - previous) & 0xffff_ffffL;
        return distance != 0L && distance < 0x8000_0000L;
    }

    private void resetSessionOnMain() {
        sessionOpen = false;
        car.unsubscribeControlStates(stateListener);
        catalog.clear();
        subscribedIds.clear();
        lastInboundSequence = 0L;
        outboundSequence = 0L;
        rateWindowStarted = 0L;
        rateWindowCommands = 0;
        sessionStartedElapsed = 0L;
        coalescedHellos = 0;
    }
}
