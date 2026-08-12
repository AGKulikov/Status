/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone.transport.v2;

import dezz.status.widget.phone.transport.AncsProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One platform-neutral ANCS consumer shared by both BLE ownership topologies.
 *
 * <p>The owner adapter must serialize every method on one FIFO.  The core allows exactly one
 * Control Point transaction, validates all asynchronous completions against exact session/request
 * tokens, arms the Notification Source parser before its CCCD is enabled, and treats a malformed
 * or timed-out Data Source stream as terminal because ANCS carries no transaction identifier with
 * which a later response could be safely disambiguated.</p>
 */
public final class AncsConsumerCoreV2 {
    public static final long REQUEST_TIMEOUT_MS = 15_000L;
    public static final long REPLAY_QUIET_MS = 750L;
    public static final int MAX_EARLY_EVENTS = 32;
    public static final int MAX_PENDING_NOTIFICATIONS = 64;
    public static final int MAX_PENDING_APP_NAMES = 32;
    public static final int MAX_APP_NAME_CACHE = 64;

    private static final class SourceFrame {
        final IphoneNotificationEventV2 event;

        SourceFrame(IphoneNotificationEventV2 event) {
            this.event = event;
        }
    }

    private static final class Request {
        final AncsRequestTokenV2 token;
        final IphoneNotificationEventV2 event;
        final String appIdentifier;
        final long observedAtElapsedMillis;
        final AncsProtocol.NotificationAccumulator notificationAccumulator;
        final AncsProtocol.AppNameAccumulator appNameAccumulator;
        boolean writeAccepted;
        boolean suppressed;
        AncsProtocol.NotificationData notificationData;
        String appDisplayName;

        private Request(AncsRequestTokenV2 token, IphoneNotificationEventV2 event,
                        String appIdentifier, long observedAtElapsedMillis) {
            this.token = token;
            this.event = event;
            this.appIdentifier = appIdentifier;
            this.observedAtElapsedMillis = observedAtElapsedMillis;
            this.notificationAccumulator = event == null ? null
                    : new AncsProtocol.NotificationAccumulator(event.uid);
            this.appNameAccumulator = event == null
                    ? new AncsProtocol.AppNameAccumulator(appIdentifier) : null;
        }

        static Request notification(AncsRequestTokenV2 token,
                                    IphoneNotificationEventV2 event) {
            return new Request(token, event, "", event.observedAtElapsedMillis);
        }

        static Request appName(AncsRequestTokenV2 token, String appIdentifier,
                               long observedAtElapsedMillis) {
            return new Request(token, null, appIdentifier, observedAtElapsedMillis);
        }
    }

    private final long firstRequestId;
    private AncsSessionTokenV2 session;
    private boolean parserArmed;
    private boolean subscriptionsReady;
    private boolean terminal;
    private long nextRequestId;
    private long replayCount;
    private long replayLastReported;
    private long replayGeneration;
    private final ArrayDeque<SourceFrame> earlyEvents = new ArrayDeque<>();
    private final LinkedHashMap<Long, IphoneNotificationEventV2> pendingNotifications =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> pendingAppNames = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> appNames =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_APP_NAME_CACHE;
                }
            };
    private Request current;

    public AncsConsumerCoreV2() {
        this(1L);
    }

    AncsConsumerCoreV2(long firstRequestId) {
        if (firstRequestId <= 0L || firstRequestId == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "firstRequestId must leave room for a successor");
        }
        this.firstRequestId = firstRequestId;
        this.nextRequestId = firstRequestId;
    }

    /** Opens a fresh parser session; all state from the preceding link is discarded. */
    public synchronized void begin(AncsSessionTokenV2 token) {
        if (token == null) throw new NullPointerException("token");
        reset();
        session = token;
        parserArmed = true;
    }

    public synchronized boolean isActive(AncsSessionTokenV2 token) {
        return matches(token) && !terminal;
    }

    public synchronized boolean isReady(AncsSessionTokenV2 token) {
        return isActive(token) && subscriptionsReady;
    }

    /**
     * Notification Source may become live before Data Source.  Frames are decoded immediately but
     * realtime requests are held until both mandatory CCCDs are confirmed.
     */
    public synchronized List<AncsConsumerEffectV2> notificationSource(
            AncsSessionTokenV2 token, byte[] packet, long observedAtElapsedMillis) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!isActive(token)) return effects;
        if (packet == null || packet.length == 0 || packet.length % 8 != 0
                || observedAtElapsedMillis < 0L) {
            effects.add(AncsConsumerEffectV2.simple(
                    AncsConsumerEffectV2.Type.MALFORMED_SOURCE,
                    "Notification Source must contain complete 8-byte records"));
            return effects;
        }
        for (int offset = 0; offset < packet.length; offset += 8) {
            AncsProtocol.Event decoded = AncsProtocol.parseEvent(packet, offset);
            if (decoded == null) {
                effects.add(AncsConsumerEffectV2.simple(
                        AncsConsumerEffectV2.Type.MALFORMED_SOURCE,
                        "unknown Notification Source EventID"));
                continue;
            }
            IphoneNotificationEventV2 event = new IphoneNotificationEventV2(
                    decoded.eventId, decoded.flags, decoded.categoryId,
                    decoded.categoryCount, decoded.uid, observedAtElapsedMillis);
            if (event.isPreExisting()) {
                recordReplay(effects);
                continue;
            }
            effects.add(AncsConsumerEffectV2.event(event));
            if (event.eventId == IphoneNotificationEventV2.REMOVED) {
                for (Iterator<SourceFrame> iterator = earlyEvents.iterator();
                     iterator.hasNext();) {
                    if (iterator.next().event.uid == event.uid) iterator.remove();
                }
                pendingNotifications.remove(event.uid);
                if (current != null && current.event != null
                        && current.event.uid == event.uid) {
                    current.suppressed = true;
                }
                continue;
            }
            if (!subscriptionsReady) {
                if (earlyEvents.size() >= MAX_EARLY_EVENTS) {
                    SourceFrame dropped = earlyEvents.removeFirst();
                    effects.add(AncsConsumerEffectV2.count(
                            AncsConsumerEffectV2.Type.QUEUE_DROPPED, dropped.event.uid, 0L,
                            "early realtime queue full; oldest UID dropped explicitly"));
                }
                earlyEvents.addLast(new SourceFrame(event));
            } else {
                enqueueNotification(event, effects);
            }
        }
        if (subscriptionsReady) startNext(effects);
        return effects;
    }

    /** Marks NS+DS subscribed and drains realtime frames received between the two CCCD writes. */
    public synchronized List<AncsConsumerEffectV2> subscriptionsReady(
            AncsSessionTokenV2 token) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!isActive(token) || subscriptionsReady) return effects;
        subscriptionsReady = true;
        while (!earlyEvents.isEmpty()) {
            enqueueNotification(earlyEvents.removeFirst().event, effects);
        }
        startNext(effects);
        return effects;
    }

    /** Completes the exact write callback.  Data Source is allowed to win the callback race. */
    public synchronized List<AncsConsumerEffectV2> controlPointWriteResult(
            AncsRequestTokenV2 token, boolean success) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!currentMatches(token)) return effects;
        if (!success) return terminate(effects, "Control Point write rejected");
        current.writeAccepted = true;
        if (responseComplete(current)) finishCurrent(effects);
        return effects;
    }

    /** Appends one Data Source fragment to the sole current request. */
    public synchronized List<AncsConsumerEffectV2> dataSource(
            AncsSessionTokenV2 token, byte[] fragment) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!isActive(token)) return effects;
        if (current == null) {
            return terminate(effects, "Data Source fragment without an active request");
        }
        if (fragment == null || fragment.length == 0) {
            return terminate(effects, "empty Data Source fragment");
        }
        if (current.notificationAccumulator != null) {
            if (!current.notificationAccumulator.append(fragment)
                    || current.notificationAccumulator.isMalformed()) {
                return terminate(effects, "malformed notification attributes: "
                        + current.notificationAccumulator.error());
            }
            current.notificationData = current.notificationAccumulator.complete();
        } else {
            if (!current.appNameAccumulator.append(fragment)
                    || current.appNameAccumulator.isMalformed()) {
                return terminate(effects, "malformed app attributes: "
                        + current.appNameAccumulator.error());
            }
            current.appDisplayName = current.appNameAccumulator.complete();
        }
        if (current.writeAccepted && responseComplete(current)) finishCurrent(effects);
        return effects;
    }

    public synchronized List<AncsConsumerEffectV2> requestDeadline(
            AncsRequestTokenV2 token) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!currentMatches(token)) return effects;
        return terminate(effects, "ANCS Data Source transaction timed out");
    }

    /** Emits the final bounded replay summary only for the newest quiet timer generation. */
    public synchronized List<AncsConsumerEffectV2> replayQuiet(
            AncsSessionTokenV2 token, long generation) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!isActive(token) || generation != replayGeneration) return effects;
        if (replayCount > replayLastReported) {
            effects.add(AncsConsumerEffectV2.count(
                    AncsConsumerEffectV2.Type.REPLAY_SUMMARY, replayCount,
                    replayGeneration, "initial iOS replay suppressed"));
            replayLastReported = replayCount;
        }
        return effects;
    }

    /** Closes the exact session and returns timer-cancellation/final-summary effects. */
    public synchronized List<AncsConsumerEffectV2> close(AncsSessionTokenV2 token) {
        List<AncsConsumerEffectV2> effects = new ArrayList<>();
        if (!matches(token)) return effects;
        if (current != null) {
            effects.add(AncsConsumerEffectV2.request(
                    AncsConsumerEffectV2.Type.CANCEL_REQUEST_DEADLINE,
                    current.token, null, "session closed"));
        }
        if (replayGeneration > 0L) {
            effects.add(AncsConsumerEffectV2.count(
                    AncsConsumerEffectV2.Type.CANCEL_REPLAY_QUIET, replayCount,
                    replayGeneration, "session closed"));
        }
        if (replayCount > replayLastReported) {
            effects.add(AncsConsumerEffectV2.count(
                    AncsConsumerEffectV2.Type.REPLAY_SUMMARY, replayCount,
                    replayGeneration, "session ended; initial replay suppressed"));
        }
        reset();
        return effects;
    }

    private void recordReplay(List<AncsConsumerEffectV2> effects) {
        if (replayCount == Long.MAX_VALUE || replayGeneration == Long.MAX_VALUE) {
            terminate(effects, "ANCS replay cursor exhausted; fresh session required");
            return;
        }
        replayCount++;
        if (isPowerOfTwo(replayCount)) {
            effects.add(AncsConsumerEffectV2.count(
                    AncsConsumerEffectV2.Type.REPLAY_CHECKPOINT, replayCount,
                    replayGeneration, "initial iOS replay suppressed"));
            replayLastReported = replayCount;
        }
        replayGeneration = nextPositive(replayGeneration);
        effects.add(AncsConsumerEffectV2.count(
                AncsConsumerEffectV2.Type.ARM_REPLAY_QUIET, replayCount,
                replayGeneration, "replace replay quiet timer after "
                        + REPLAY_QUIET_MS + "ms"));
    }

    private void enqueueNotification(IphoneNotificationEventV2 event,
                                     List<AncsConsumerEffectV2> effects) {
        pendingNotifications.remove(event.uid);
        if (pendingNotifications.size() >= MAX_PENDING_NOTIFICATIONS) {
            Iterator<Map.Entry<Long, IphoneNotificationEventV2>> iterator =
                    pendingNotifications.entrySet().iterator();
            Map.Entry<Long, IphoneNotificationEventV2> dropped = iterator.next();
            iterator.remove();
            effects.add(AncsConsumerEffectV2.count(
                    AncsConsumerEffectV2.Type.QUEUE_DROPPED, dropped.getKey(), 0L,
                    "notification attribute queue full; oldest UID dropped explicitly"));
        }
        pendingNotifications.put(event.uid, event);
    }

    private void startNext(List<AncsConsumerEffectV2> effects) {
        if (!subscriptionsReady || terminal || current != null) return;
        if (!pendingNotifications.isEmpty()) {
            if (nextRequestId == Long.MAX_VALUE) {
                terminate(effects, "ANCS request cursor exhausted; fresh session required");
                return;
            }
            Iterator<Map.Entry<Long, IphoneNotificationEventV2>> iterator =
                    pendingNotifications.entrySet().iterator();
            IphoneNotificationEventV2 event = iterator.next().getValue();
            iterator.remove();
            AncsRequestTokenV2 token = nextRequest(
                    AncsRequestTokenV2.Kind.NOTIFICATION_ATTRIBUTES);
            current = Request.notification(token, event);
            emitRequest(effects, token,
                    AncsProtocol.notificationAttributeRequest(event.uid));
            return;
        }
        if (!pendingAppNames.isEmpty()) {
            if (nextRequestId == Long.MAX_VALUE) {
                terminate(effects, "ANCS request cursor exhausted; fresh session required");
                return;
            }
            Iterator<Map.Entry<String, Long>> iterator = pendingAppNames.entrySet().iterator();
            Map.Entry<String, Long> entry = iterator.next();
            String appIdentifier = entry.getKey();
            long observedAtElapsedMillis = entry.getValue();
            iterator.remove();
            AncsRequestTokenV2 token = nextRequest(
                    AncsRequestTokenV2.Kind.APP_DISPLAY_NAME);
            current = Request.appName(token, appIdentifier, observedAtElapsedMillis);
            emitRequest(effects, token,
                    AncsProtocol.appDisplayNameRequest(appIdentifier));
        }
    }

    private void emitRequest(List<AncsConsumerEffectV2> effects,
                             AncsRequestTokenV2 token, byte[] value) {
        effects.add(AncsConsumerEffectV2.request(
                AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT, token, value,
                "one serialized ANCS Control Point write"));
        effects.add(AncsConsumerEffectV2.request(
                AncsConsumerEffectV2.Type.ARM_REQUEST_DEADLINE, token, null,
                "replace exact request watchdog after " + REQUEST_TIMEOUT_MS + "ms"));
    }

    private void finishCurrent(List<AncsConsumerEffectV2> effects) {
        Request completed = current;
        effects.add(AncsConsumerEffectV2.request(
                AncsConsumerEffectV2.Type.CANCEL_REQUEST_DEADLINE,
                completed.token, null, "response complete"));
        current = null;
        if (completed.notificationData != null) {
            AncsProtocol.NotificationData data = completed.notificationData;
            String appName = appNames.get(data.appIdentifier);
            if (!completed.suppressed) {
                effects.add(AncsConsumerEffectV2.notification(new IphoneNotificationV2(
                        completed.event.eventId, completed.event.flags,
                        completed.event.categoryId, completed.event.categoryCount,
                        data.uid, data.appIdentifier, appName,
                        data.title, data.subtitle, data.message, data.date,
                        completed.event.observedAtElapsedMillis)));
            }
            if (!completed.suppressed && !data.appIdentifier.isEmpty() && appName == null
                    && !pendingAppNames.containsKey(data.appIdentifier)) {
                if (pendingAppNames.size() >= MAX_PENDING_APP_NAMES) {
                    Iterator<String> iterator = pendingAppNames.keySet().iterator();
                    String dropped = iterator.next();
                    iterator.remove();
                    effects.add(AncsConsumerEffectV2.count(
                            AncsConsumerEffectV2.Type.QUEUE_DROPPED, 0L, 0L,
                            "app-name queue full; dropped " + dropped));
                }
                pendingAppNames.put(data.appIdentifier,
                        completed.event.observedAtElapsedMillis);
            }
        } else if (completed.appDisplayName != null) {
            appNames.put(completed.appIdentifier, completed.appDisplayName);
            effects.add(AncsConsumerEffectV2.appName(new IphoneAppNameV2(
                    completed.appIdentifier, completed.appDisplayName,
                    completed.observedAtElapsedMillis)));
        }
        startNext(effects);
    }

    private List<AncsConsumerEffectV2> terminate(List<AncsConsumerEffectV2> effects,
                                                  String reason) {
        terminal = true;
        pendingNotifications.clear();
        pendingAppNames.clear();
        earlyEvents.clear();
        if (current != null) {
            effects.add(AncsConsumerEffectV2.request(
                    AncsConsumerEffectV2.Type.CANCEL_REQUEST_DEADLINE,
                    current.token, null, "terminal request"));
        }
        current = null;
        effects.add(AncsConsumerEffectV2.simple(
                AncsConsumerEffectV2.Type.TERMINATE_SESSION, reason));
        return effects;
    }

    private AncsRequestTokenV2 nextRequest(AncsRequestTokenV2.Kind kind) {
        long requestId = nextRequestId;
        nextRequestId++;
        return new AncsRequestTokenV2(session, requestId, kind);
    }

    private boolean currentMatches(AncsRequestTokenV2 token) {
        return token != null && current != null && current.token.equals(token)
                && isActive(token.session);
    }

    private boolean matches(AncsSessionTokenV2 token) {
        return token != null && session != null && session.equals(token) && parserArmed;
    }

    private static boolean responseComplete(Request request) {
        return request.notificationData != null || request.appDisplayName != null;
    }

    private void reset() {
        session = null;
        parserArmed = false;
        subscriptionsReady = false;
        terminal = false;
        nextRequestId = firstRequestId;
        replayCount = 0L;
        replayLastReported = 0L;
        replayGeneration = 0L;
        earlyEvents.clear();
        pendingNotifications.clear();
        pendingAppNames.clear();
        appNames.clear();
        current = null;
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0L && (value & value - 1L) == 0L;
    }

    private static long nextPositive(long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("positive cursor exhausted");
        }
        return value + 1L;
    }
}
