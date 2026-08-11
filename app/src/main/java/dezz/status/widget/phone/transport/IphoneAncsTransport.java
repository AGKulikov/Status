/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.phone.IphoneHelperTelemetry;

/**
 * Production BLE/ANCS transport for one explicitly verified iPhone.
 *
 * <p>The listener receives only real-time notifications from the current ANCS session. iOS's
 * initial {@link AncsProtocol#EVENT_FLAG_PRE_EXISTING pre-existing} replay is rejected before a
 * Control Point request is queued, and a removal is emitted only for a UID that this transport
 * actually delivered during the same session.</p>
 */
public final class IphoneAncsTransport {
    public static final String LOCAL_LOGICAL_NAME = "Geely_ANCS";
    public static final String REMOTE_LOGICAL_NAME = "iPhone_ANCS";
    /**
     * Generation 4 deliberately replaces the long-lived F02 bootstrap namespace. Android 9 and
     * Core Bluetooth had both cached the old service database; iOS then rejected even an
     * unfiltered B2/B3 discovery with CBError.uuidNotAllowed before the PAIR exchange started.
     */
    private static final UUID DIAGNOSTIC_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID DIAGNOSTIC_CHARACTERISTIC =
            UUID.fromString("d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID SECURE_CHARACTERISTIC =
            UUID.fromString("d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f04");
    /** Dedicated Helper telemetry endpoint on the current verified GATT-server connection. */
    private static final UUID TELEMETRY_CHARACTERISTIC =
            UUID.fromString("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f04");
    /** Stable scan beacon for the iPhone-Central route; F04 itself never rotates. */
    private static final UUID MANAGED_INCOMING_BEACON_SERVICE =
            UUID.fromString("d2d9e4bf-47f1-4e44-a8bb-a932fd5affff");
    private static final int MANAGED_INCOMING_MANUFACTURER_ID = 0xFFFF;
    private static final String MANAGED_INCOMING_NAMESPACE_PREFS =
            "iphone_ancs_dynamic_namespace";
    private static final String MANAGED_INCOMING_NAMESPACE_GENERATION = "generation";
    /** Durable UInt24 lineage committed only after exact-current F04 onServiceAdded SUCCESS. */
    private static final String MANAGED_INCOMING_PUBLICATION_NONCE = "publication_nonce";
    /**
     * iPhone-owned telemetry relay discovered by Android on the already-working ANCS owner.
     * Generation 5 is intentionally separate from Android's generation-4 bootstrap database:
     * Android 9 and Core Bluetooth otherwise reuse the opposite GATT role's stale B4 handle.
     */
    private static final UUID TELEMETRY_RELAY_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f05");
    private static final UUID TELEMETRY_RELAY_CHARACTERISTIC =
            UUID.fromString("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f05");
    private static final UUID GENERIC_ATTRIBUTE_SERVICE =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_STATUS =
            UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb");
    private static final String LOG_TAG = "KX11ANCS";
    private static final int GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS;
    /** Field A/B server callbacks were 15 ms apart; wider windows can join unrelated TCBs. */
    private static final long ANONYMOUS_BONDED_ALIAS_MAX_DELTA_MS = 250L;
    /** ATT write-not-permitted. On ANCS Notification Source this means iOS has not authorized ANCS. */
    private static final int STATUS_WRITE_NOT_PERMITTED = 3;
    private static final int STATUS_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int STATUS_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int STATUS_INSUFFICIENT_KEY_SIZE = 12;
    private static final int STATUS_INSUFFICIENT_ENCRYPTION = 15;
    /** Android/AOSP GATT_AUTH_FAIL (0x89): SMP/encryption could not be completed. */
    private static final int STATUS_GATT_AUTH_FAIL = 0x89;
    /** Android's generic GATT callback failure; a delivered callback is not channel ambiguity. */
    private static final int STATUS_GATT_ERROR = 133;
    /** AOSP GATT_CONN_TERMINATE_LOCAL_HOST: Android retired its local client-role owner. */
    private static final int STATUS_GATT_CONN_TERMINATE_LOCAL_HOST = 22;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    /** A direct attempt after a real advertisement may legitimately take longer on Android 9. */
    private static final long CONNECT_TIMEOUT_MS = 35_000L;
    /**
     * Any GATT client that has connected successfully is Android's durable registration for the
     * peer. BluetoothGatt.connect() re-arms that same client after a loss; the watchdog must never
     * close it merely because the iPhone stayed out of range for a fixed interval.
     */
    private static final long PERSISTENT_RECONNECT_WATCHDOG_MS = 30_000L;
    /** ECARX often emits an ACL loss without saying whether Classic or LE was affected. */
    private static final long AMBIGUOUS_ACL_GRACE_MS = 1_200L;
    private static final long LINK_PROBE_TIMEOUT_MS = 2_500L;
    private static final long GPS_CONNECT_TIMEOUT_MS = 15_000L;
    private static final long GPS_SCAN_TIMEOUT_MS = 30_000L;
    private static final long SAVED_PEER_SCAN_RESTART_MS = 30_000L;
    private static final long SAVED_PEER_SCAN_RESTART_DELAY_MS = 400L;
    /**
     * Package replacement kills the app process and its Binder-owned GATT client while the
     * system Bluetooth process and the iPhone's Classic profiles stay alive. Give Android 9 a
     * short quiescence window before registering the replacement background GATT owner.
     */
    private static final long COLD_BACKGROUND_ATTACH_DELAY_MS = 2_500L;
    private static final long AUTO_ANCS_WAIT_TIMEOUT_MS = 60_000L;
    private static final long GPS_POST_SECURE_DISCOVERY_DELAY_MS = 800L;
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;
    private static final long DESCRIPTOR_WRITE_TIMEOUT_MS = 15_000L;
    /** The first encrypted ANCS CCCD may wait while the user accepts iPhone pairing. */
    private static final long ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS = 90_000L;
    private static final long BATTERY_OPERATION_TIMEOUT_MS = 5_000L;
    private static final long HELPER_TELEMETRY_READ_TIMEOUT_MS = 5_000L;
    /**
     * Notifications are the zero-delay path. A one-second read is the deterministic fallback
     * when iOS coalesces a public battery/CoreTelephony callback while the Helper is backgrounded.
     */
    private static final long HELPER_TELEMETRY_POLL_MS = 1_000L;
    /** Central mode: a tiny B4 notification wakes Core Bluetooth so Helper can push fresh data. */
    private static final long SERVER_TELEMETRY_WAKE_POLL_MS = 5_000L;
    private static final long HELPER_TELEMETRY_BUSY_RETRY_MS = 1_000L;
    private static final long BOND_TIMEOUT_MS = 90_000L;
    private static final long ANCS_REQUEST_GAP_MS = 120L;
    private static final long LIVE_NOTIFICATION_MAX_AGE_MS = 15_000L;
    private static final int MAX_PENDING_ANCS_REQUESTS = 24;
    private static final int MAX_EARLY_NOTIFICATION_SOURCE_FRAMES = 32;
    private static final long ANCS_PERMISSION_RETRY_MS = 5_000L;
    private static final int ANCS_PERMISSION_RETRY_LIMIT = 12;
    private static final long ANCS_SECOND_CCCD_DELAY_MS = 150L;
    private static final long SECURE_TO_CLIENT_CONNECT_DELAY_MS = 400L;
    private static final long DIRECT_FALLBACK_DELAY_MS = 500L;
    /** One exact PAIR/B3/READY tuple gets one opportunistic allocation, never three opens. */
    private static final int INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS = 1;
    /** At most one poisoned client wrapper may be replaced before a full handshake succeeds. */
    private static final int RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS = 1;
    /**
     * An opportunistic attach against the exact live incoming peer must produce a callback
     * promptly. Unlike a cold {@code autoConnect=true} registration, this attempt is bounded and
     * only its client wrapper is unregistered when Android never reports a result.
     */
    private static final long INCOMING_DIRECT_ATTACH_TIMEOUT_MS = 10_000L;
    /** HA1211 managed-route wire frames fit the default ATT MTU: one opcode + 128-bit Q. */
    private static final int MANAGED_PROOF_FRAME_BYTES = 17;
    private static final byte MANAGED_PAIR_OPCODE = 0x50;
    private static final byte MANAGED_LINK_BOUND_OPCODE = 0x4C;
    private static final byte MANAGED_ANCS_SUBSCRIBED_OPCODE = 0x41;
    private static final long CANDIDATE_UI_INTERVAL_MS = 500L;
    private static final int MAX_CANDIDATES = 150;

    public interface Listener {
        void onState(String state);
        /** Reconnect lifecycle is typed and must not depend only on parsing diagnostic text. */
        default void onRetryRequired(String reason) {}
        void onLog(String line);
        void onCandidates(List<Candidate> candidates);
        void onNotification(NotificationItem item);
        void onAppName(String appIdentifier, String displayName);
        void onBatteryCharacteristic(UUID characteristicUuid, byte[] value);
        /** Helper telemetry received from the peer verified on the current application channel. */
        default void onHelperTelemetry(IphoneHelperTelemetry telemetry) {}
        /** Stable BLE identity proved by PAIR plus a second operation on the same live ATT link. */
        default void onVerifiedPeerAddress(String address) {}
    }

    public static final class Candidate {
        public final BluetoothDevice device;
        public final String address;
        public final String name;
        public final int type;
        public final int bondState;
        public final int rssi;
        public final boolean ancsSolicitation;
        public final String rawAdvertisement;
        public final String origin;

        Candidate(BluetoothDevice device, String address, String name, int type,
                  int bondState, int rssi, boolean ancsSolicitation,
                  String rawAdvertisement, String origin) {
            this.device = device;
            this.address = address;
            this.name = name;
            this.type = type;
            this.bondState = bondState;
            this.rssi = rssi;
            this.ancsSolicitation = ancsSolicitation;
            this.rawAdvertisement = rawAdvertisement;
            this.origin = origin;
        }

        public String displayText() {
            StringBuilder value = new StringBuilder();
            if (ancsSolicitation) value.append("[ANCS] ");
            if (bondState == BluetoothDevice.BOND_BONDED) value.append("[BONDED] ");
            value.append(name.isEmpty() ? "(без имени)" : name)
                    .append("\n").append(address)
                    .append(" · type=").append(typeLabel(type));
            if (rssi > -127) value.append(" · RSSI ").append(rssi);
            value.append(" · ").append(origin);
            return value.toString();
        }
    }

    public static final class NotificationItem {
        public final long uid;
        public final int eventId;
        public final int categoryId;
        public final String appIdentifier;
        public final String appName;
        public final String title;
        public final String message;
        public final String date;
        public final long observedAtElapsedMs;

        NotificationItem(long uid, int eventId, int categoryId, String appIdentifier,
                         String appName, String title, String message, String date,
                         long observedAtElapsedMs) {
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
            this.appName = appName;
            this.title = title;
            this.message = message;
            this.date = date;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }

        public String displayText() {
            String source = appName.isEmpty() ? appIdentifier : appName + " · " + appIdentifier;
            StringBuilder result = new StringBuilder(source)
                    .append("\n").append(AncsProtocol.categoryLabel(categoryId));
            if (!title.isEmpty()) result.append(" · ").append(title);
            if (!message.isEmpty()) result.append("\n").append(message);
            if (!date.isEmpty()) result.append("\n").append(date);
            return result.toString();
        }
    }

    private enum DescriptorStage {
        NONE,
        SERVICE_CHANGED,
        HELPER_TELEMETRY,
        DATA_SOURCE,
        NOTIFICATION_SOURCE,
        BATTERY_LEVEL,
        BATTERY_LEVEL_STATUS
    }

    private enum HelperProofWriteStage {
        NONE,
        LINK_BOUND,
        ANCS_SUBSCRIBED
    }

    private enum BatteryStage {
        NOT_STARTED,
        READ_LEVEL_STATUS,
        SUBSCRIBE_LEVEL_STATUS,
        READ_LEVEL,
        SUBSCRIBE_LEVEL,
        COMPLETE
    }

    private enum RequestKind {
        NOTIFICATION,
        APP_NAME
    }

    private static final class Request {
        final RequestKind kind;
        final long uid;
        final int eventId;
        final int categoryId;
        final String appIdentifier;
        long observedAtElapsedMs;

        private Request(RequestKind kind, long uid, int eventId,
                        int categoryId, String appIdentifier, long observedAtElapsedMs) {
            this.kind = kind;
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }

        static Request notification(AncsProtocol.Event event, long observedAtElapsedMs) {
            return new Request(RequestKind.NOTIFICATION, event.uid,
                    event.eventId, event.categoryId, "", observedAtElapsedMs);
        }

        static Request appName(String appIdentifier) {
            return new Request(RequestKind.APP_NAME, -1L, 0, 0, appIdentifier,
                    SystemClock.elapsedRealtime());
        }
    }

    /**
     * Pure session-local admission policy kept separate from Android callbacks so its boundaries
     * can be unit tested. A UID becomes live only after its complete notification has been handed
     * to the listener; consuming a removal makes duplicate removals harmless.
     */
    static final class RealtimeAdmission {
        private final Set<Long> liveSessionUids = new HashSet<>();

        boolean shouldRequest(AncsProtocol.Event event) {
            if (event == null || AncsProtocol.isPreExisting(event)) return false;
            return event.eventId == AncsProtocol.EVENT_ADDED
                    || event.eventId == AncsProtocol.EVENT_MODIFIED;
        }

        void markDelivered(long uid) {
            liveSessionUids.add(uid);
        }

        boolean consumeRemoval(long uid) {
            return liveSessionUids.remove(uid);
        }

        void clear() {
            liveSessionUids.clear();
        }

        boolean contains(long uid) {
            return liveSessionUids.contains(uid);
        }
    }

    /**
     * One physical peer observed by this app's GATT-server role. Android 9 may later deliver a
     * different bonded wrapper for PAIR/B3/READY. The first unambiguous server CONNECTED facade
     * remains the immutable hidden-connect transport target; {@link #device} is the mutable exact
     * bonded authorization facade and is never allowed to overwrite that physical target.
     */
    private static final class GattServerPeer {
        final long sessionGeneration;
        final BluetoothDevice physicalLinkFacade;
        /** Exact current PAIR/B3/READY authorization facade; initially the server facade. */
        BluetoothDevice device;
        long connectedAtElapsedMs;
        long securityEpoch;
        boolean connected;
        /** Server facade disappeared and retained clientIf passed a bounded liveness proof. */
        boolean roleFacadeHandoff;
        /** Server facade disappeared while the exact direct client callback is still pending. */
        boolean roleFacadeHandoffPending;
        boolean linkSecurityChallengeIssued;
        boolean telemetrySubscribed;

        GattServerPeer(long sessionGeneration, BluetoothDevice device) {
            this.sessionGeneration = sessionGeneration;
            this.physicalLinkFacade = device;
            this.device = device;
        }
    }

    /** Immutable authorization captured when READY is committed on the main FIFO. */
    private static final class IncomingReadyAttach {
        final GattServerPeer serverPeer;
        final BluetoothDevice physicalLinkFacade;
        final BluetoothDevice rawFacade;
        final byte[] pairChallenge;
        final long sessionGeneration;
        final long securityEpoch;
        final long publicationToken;
        final boolean firstReadyProof;
        final boolean attachTaskArmed;

        IncomingReadyAttach(GattServerPeer serverPeer,
                            BluetoothDevice physicalLinkFacade,
                            BluetoothDevice rawFacade,
                            byte[] pairChallenge,
                            long sessionGeneration, long securityEpoch,
                            long publicationToken, boolean firstReadyProof,
                            boolean attachTaskArmed) {
            this.serverPeer = serverPeer;
            this.physicalLinkFacade = physicalLinkFacade;
            this.rawFacade = rawFacade;
            this.pairChallenge = pairChallenge.clone();
            this.sessionGeneration = sessionGeneration;
            this.securityEpoch = securityEpoch;
            this.publicationToken = publicationToken;
            this.firstReadyProof = firstReadyProof;
            this.attachTaskArmed = attachTaskArmed;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    /** Apple may emit Notification Source immediately after CCCD; retain it until DS is ready. */
    private final ArrayDeque<byte[]> earlyNotificationSourceFrames = new ArrayDeque<>();
    private final Map<Long, AncsProtocol.Event> events = new HashMap<>();
    private final Map<Long, Long> eventObservedAtElapsedMs = new HashMap<>();
    private final Map<String, String> appNames = new HashMap<>();
    private final Set<Long> queuedNotificationUids = new HashSet<>();
    private final Set<Long> dirtyNotificationUids = new HashSet<>();
    private final Set<String> queuedAppIdentifiers = new HashSet<>();
    private final RealtimeAdmission realtimeAdmission = new RealtimeAdmission();
    private final AncsSessionStateMachine sessionState = new AncsSessionStateMachine();

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGatt gatt;
    private BluetoothDevice activeClientTarget;
    private BluetoothDevice savedPeerScanTarget;
    private BluetoothDevice managedSavedPeer;
    /** Last BLE identity that already passed the selected-phone gate in this live session. */
    private BluetoothDevice managedResolvedPeer;
    private final Object verifiedPeerLock = new Object();
    private BluetoothDevice verifiedPeer;
    private final LinkedHashMap<String, GattServerPeer> gattServerPeers =
            new LinkedHashMap<>();
    private long sessionGeneration;
    /** Every physical incoming link gets a new PAIR/B3/ANCS-READY ownership epoch. */
    private long incomingSecurityEpoch;
    /** At most one bonded request facade may coalesce with an anonymous alias in each epoch. */
    private long incomingPairRequestFacadeBoundEpoch;
    private boolean clientConnectInFlight;
    private boolean activeClientAutoConnect;
    /** True only for the Pie hidden overload carrying opportunistic=true. */
    private boolean activeClientOpportunistic;
    private boolean backgroundAttachAttempted;
    private boolean directFallbackAttempted;
    /** Exact-tuple opportunistic allocations consumed before an incoming observer is obtained. */
    private int incomingClientAttachAttempt;
    private int poisonedWrapperReplacementAttempt;
    /** Closed status=22 clientIf retained only as a transfer identity until fresh F04 is ready. */
    private BluetoothGatt incomingStaleEstablishedOwner;
    /** Status=22 cannot authorize a replacement until a later physical incoming-link epoch. */
    private boolean incomingStaleOwnerAwaitingFreshEpoch;
    /** Fresh B3/ANCS-READY epoch allowed to consume the retained stale owner exactly once. */
    private long incomingStaleOwnerReplacementEpoch;
    /** Suppresses duplicate PAIR/READY callbacks after the one fresh attach was consumed. */
    private long incomingFreshReplacementConsumedEpoch;
    /** Exact never-established wrapper created by the one-shot status=22 replacement. */
    private BluetoothGatt incomingFreshReplacementGatt;
    /** One same-wrapper rediscovery after a real mandatory CCCD status=133 callback. */
    private int mandatoryDescriptorStatus133RetryCount;
    /** Exact bonded BluetoothDevice facade delivered by the current GATT-server link. */
    private BluetoothDevice incomingClientCandidate;
    /** Exact raw B2 callback facade whose PAIR was accepted for this one F04 epoch. */
    private volatile BluetoothDevice incomingPairAcceptedFacade;
    /** Stable server-link record; Android 9 may wrap the same link in a new device per ATT call. */
    private GattServerPeer incomingPairAcceptedServerPeer;
    private volatile long incomingPairAcceptedSessionGeneration;
    private volatile long incomingPairAcceptedSecurityEpoch;
    private volatile long incomingPairAcceptedPublicationToken;
    /** Exact 128-bit Q committed atomically with managed PAIR facade C. */
    private byte[] incomingPairAcceptedChallenge;
    /** One READY may arm exactly one delayed first-attach task for its exact S/E/P/raw tuple. */
    private BluetoothDevice incomingReadyAttachLatchFacade;
    private BluetoothDevice incomingReadyAttachLatchPhysicalFacade;
    private GattServerPeer incomingReadyAttachLatchServerPeer;
    private long incomingReadyAttachLatchSessionGeneration;
    private long incomingReadyAttachLatchSecurityEpoch;
    private long incomingReadyAttachLatchPublicationToken;
    private byte[] incomingReadyAttachLatchChallenge;
    private Runnable incomingReadyAttachTask;
    /** Synchronous capability held only while the captured READY task owns attempt #1. */
    private IncomingReadyAttach activeIncomingFirstAttachAuthorization;
    /** Durable per-tuple bit; attempt counters may reset after full ANCS success, this may not. */
    private boolean incomingFirstAttachIssuedForCurrentTuple;
    /** Capability budgets are reset only with the exact PAIR raw-facade tuple. */
    private boolean incomingOpportunisticAttachAttemptedForCurrentTuple;
    /** One-shot status=22 capability is legal only after attempt #1 crossed the READY barrier. */
    private boolean activeIncomingPostReadyReplacementAuthorization;
    /** Lineage of the one post-READY BluetoothGatt wrapper currently allowed to callback. */
    private BluetoothDevice incomingClientAttemptTransportFacade;
    /** Separate exact bonded PAIR/B3/READY owner for that physical transport attempt. */
    private BluetoothDevice incomingClientAttemptPairFacade;
    /** Stable peer that owns both facades for that exact attempt. */
    private GattServerPeer incomingClientAttemptServerPeer;
    private long incomingClientAttemptSessionGeneration;
    private long incomingClientAttemptSecurityEpoch;
    private long incomingClientAttemptPublicationToken;
    private byte[] incomingClientAttemptChallenge;
    /**
     * True only after Android's F04 database has completed {@code onServiceAdded}. ECARX Android
     * 9 can permanently wedge a clientIf when connectGatt races an unfinished server addService.
     */
    private volatile boolean serverDiagnosticServicePublished;
    /** Exact service object currently submitted to BluetoothGattServer#addService. */
    private volatile BluetoothGattService pendingDiagnosticServicePublication;
    /** Exact service object whose current-token SUCCESS opened the barrier. */
    private volatile BluetoothGattService publishedDiagnosticServicePublication;
    /** Monotonic lifecycle token invalidated by every open/close/failure boundary. */
    private volatile long serverDiagnosticServicePublicationToken;
    /** Token that owns {@link #pendingDiagnosticServicePublication}. */
    private volatile long pendingDiagnosticServicePublicationToken;
    /** Current accepted SUCCESS token; zero while publication is pending/closed. */
    private volatile long publishedDiagnosticServicePublicationToken;
    /** Publication token that produced the current B3 proof. */
    private volatile long secureAttPublicationToken;
    /** Publication token that accepted the current same-owner READY proof. */
    private volatile long incomingAncsReadyPublicationToken;
    /**
     * Same-owner proof written by Helper after RequiresANCS connect + current-link B3. This is
     * intentionally independent from provisional iOS ANCS permission/CCCD authorization.
     */
    private boolean incomingAncsReadyGateOpen;
    /** Prevents duplicate discovery starts for the current connected client owner. */
    private boolean incomingDiscoveryStarted;
    /** Binder callbacks and the main state machine both read this phase-one proof. */
    private volatile boolean secureAttConfirmed;
    private boolean gattClientConnected;
    private boolean activeClientEstablished;
    /** Current incoming security epoch for which retained client liveness was proven. */
    private long activeClientProvenSecurityEpoch;
    private long activeClientGeneration;
    private long activeScanGeneration;
    private boolean iphonePeripheralMode;
    private boolean helperBootstrapMode;
    private boolean iphoneConnectStarted;
    private boolean iphonePairAttempted;
    private boolean iphonePairWritePending;
    private boolean iphoneSecureReadPending;
    private boolean iphoneSecureConfirmed;
    private boolean iphoneHelperTelemetrySubscriptionAttempted;
    private boolean iphoneHelperTelemetrySubscribed;
    private boolean iphoneHelperTelemetryReadPending;
    /** At least one complete battery+network TEL3 frame was transferred from Helper B4. */
    private boolean iphoneHelperValidTelemetryReceived;
    /** True after this client has attempted to prove both ANCS CCCDs to Helper v33. */
    private boolean helperAncsReadyProofAttempted;
    /** Serialized write of ANCS-SUBSCRIBED to the iPhone-owned B4 relay is in flight. */
    private boolean helperAncsReadyProofPending;
    /** Helper acknowledged the post-CCCD proof on the same B4 owner. */
    private boolean helperAncsReadyProofAcknowledged;
    private Runnable helperAncsReadyProofRetry;
    /** One deterministic B4 snapshot is read before any potentially encrypted ANCS CCCD. */
    private boolean iphoneHelperInitialReadAttempted;
    /** Service setup resumes only after that first snapshot read (or its bounded timeout). */
    private boolean iphoneServiceSetupDeferredForHelperRead;
    private boolean iphonePostSecureDiscoveryScheduled;
    private Runnable iphonePostSecureDiscovery;
    private long iphonePostSecureDiscoveryToken;
    /** Prevents optional B4 from re-entering itself while cached ANCS services resume. */
    private boolean iphoneHelperTelemetrySetupBypass;
    private boolean iphoneAncsSeen;
    private boolean closing;
    private boolean retrySignalled;
    private boolean managedReconnectEnabled;
    /** True only for the opt-in route where iPhone initiates a link to Geely_ANCS. */
    private boolean managedIncomingMode;
    private boolean ancsRetryAfterBond;
    private boolean ancsAuthorizationFailureSeen;
    private boolean leBondAttemptObserved;
    private int ancsBondRetryCount;
    private Runnable ancsPermissionRetry;
    private int ancsPermissionRetryCount;
    private boolean scanning;
    private boolean advertising;
    private boolean advertisingDesired;
    private boolean advertisingPending;
    private boolean solicitationAdvertising;
    private boolean gattReady;
    private boolean discoveryPending;
    /** Exact discovery submission lineage; callbacks carry no transaction id on Android 9. */
    private BluetoothGatt activeDiscoveryGatt;
    private long discoveryOperationGeneration;
    private long activeDiscoveryOperationGeneration;
    private long activeDiscoveryClientGeneration;
    private long activeDiscoverySessionGeneration;
    private long activeDiscoverySecurityEpoch;
    private long activeDiscoveryPublicationToken;
    private byte[] activeDiscoveryChallenge;
    private GattServerPeer activeDiscoveryServerPeer;
    private BluetoothDevice activeDiscoveryPhysicalFacade;
    private BluetoothDevice activeDiscoveryPairFacade;
    private long activeDiscoveryDatabaseGeneration;
    /** Lineage retained only while cached services are resumed after a validated callback. */
    private BluetoothGatt acceptedDiscoveryGatt;
    private long acceptedDiscoveryOperationGeneration;
    private long acceptedDiscoveryClientGeneration;
    private long acceptedDiscoverySessionGeneration;
    private long acceptedDiscoverySecurityEpoch;
    private long acceptedDiscoveryPublicationToken;
    private byte[] acceptedDiscoveryChallenge;
    private GattServerPeer acceptedDiscoveryServerPeer;
    private BluetoothDevice acceptedDiscoveryPhysicalFacade;
    private BluetoothDevice acceptedDiscoveryPairFacade;
    private long acceptedDiscoveryDatabaseGeneration;
    /** Changes only when Service Changed invalidates the accepted F05 database. */
    private long managedF05DatabaseGeneration;
    private DescriptorStage descriptorStage = DescriptorStage.NONE;
    /** Exact raw CCCD operation; UUID/stage alone cannot disambiguate a late Android callback. */
    private BluetoothGattDescriptor activeDescriptorWrite;
    private BluetoothGatt activeDescriptorWriteGatt;
    private long descriptorWriteOperationGeneration;
    private long activeDescriptorWriteOperationGeneration;
    private long activeDescriptorWriteClientGeneration;
    private long activeDescriptorWriteSessionGeneration;
    private long activeDescriptorWriteSecurityEpoch;
    private long activeDescriptorWritePublicationToken;
    private byte[] activeDescriptorWriteChallenge;
    private GattServerPeer activeDescriptorWriteServerPeer;
    private BluetoothDevice activeDescriptorWritePhysicalFacade;
    private BluetoothDevice activeDescriptorWritePairFacade;
    private long activeDescriptorWriteDatabaseGeneration;
    /** One serialized F05 proof write slot shared by L/Q and post-CCCD A/Q. */
    private HelperProofWriteStage activeHelperProofWriteStage =
            HelperProofWriteStage.NONE;
    private BluetoothGatt activeHelperProofWriteGatt;
    private BluetoothGattCharacteristic activeHelperProofWriteCharacteristic;
    private long helperProofWriteOperationGeneration;
    private long activeHelperProofWriteOperationGeneration;
    private long activeHelperProofWriteClientGeneration;
    private long activeHelperProofWriteSessionGeneration;
    private long activeHelperProofWriteSecurityEpoch;
    private long activeHelperProofWritePublicationToken;
    private byte[] activeHelperProofWriteChallenge;
    private GattServerPeer activeHelperProofWriteServerPeer;
    private BluetoothDevice activeHelperProofWritePhysicalFacade;
    private BluetoothDevice activeHelperProofWritePairFacade;
    private long activeHelperProofWriteDatabaseGeneration;
    private long activeHelperProofWriteDiscoveryOperationGeneration;
    private Runnable helperProofWriteTimeout;
    /** Exact current F05 L/Q acknowledgement required before any ANCS CCCD. */
    private boolean helperLinkBoundAcknowledged;
    private BluetoothGatt helperLinkBoundGatt;
    private BluetoothGattCharacteristic helperLinkBoundCharacteristic;
    private byte[] helperLinkBoundChallenge;
    private GattServerPeer helperLinkBoundServerPeer;
    private BluetoothDevice helperLinkBoundPhysicalFacade;
    private BluetoothDevice helperLinkBoundPairFacade;
    private long helperLinkBoundClientGeneration;
    private long helperLinkBoundSessionGeneration;
    private long helperLinkBoundSecurityEpoch;
    private long helperLinkBoundPublicationToken;
    private long helperLinkBoundDatabaseGeneration;
    private long helperLinkBoundDiscoveryOperationGeneration;
    private AdvertiseSettings preparedAdvertiseSettings;
    private AdvertiseData preparedAdvertiseData;
    private AdvertiseData preparedScanResponse;
    /** Exact callback object registered for the current publication/nonce tuple. */
    private PublicationAdvertiseCallback activeAdvertiseCallback;
    /** Candidate is derived before addService but remains invalid until the success callback. */
    private int pendingManagedIncomingPublicationNonce;
    /** Exact nonce encoded in the currently advertised manufacturer frame, or zero. */
    private int publishedManagedIncomingPublicationNonce;

    private BluetoothGattCharacteristic notificationSource;
    private BluetoothGattCharacteristic dataSource;
    private BluetoothGattCharacteristic controlPoint;
    private BluetoothGattCharacteristic serviceChanged;
    private BluetoothGattCharacteristic batteryLevel;
    private BluetoothGattCharacteristic batteryLevelStatus;
    private BluetoothGattCharacteristic iphoneSecureCharacteristic;
    private BluetoothGattCharacteristic iphoneTelemetryCharacteristic;
    /** UUIDs currently published by this Android GATT-server generation. */
    private UUID serverDiagnosticService = DIAGNOSTIC_SERVICE;
    private UUID serverDiagnosticCharacteristic = DIAGNOSTIC_CHARACTERISTIC;
    private UUID serverControlCharacteristic = CONTROL_CHARACTERISTIC;
    private UUID serverSecureCharacteristic = SECURE_CHARACTERISTIC;
    private UUID serverTelemetryCharacteristicUuid = TELEMETRY_CHARACTERISTIC;
    private int serverDiagnosticGeneration = 0x2F04;
    /** Android-owned B4 endpoint used only by the iPhone-Central route. */
    private BluetoothGattCharacteristic serverTelemetryCharacteristic;
    private Request activeRequest;
    private AncsProtocol.NotificationAccumulator notificationAccumulator;
    private AncsProtocol.AppNameAccumulator appNameAccumulator;
    private Runnable requestTimeout;
    private Runnable connectTimeout;
    private Runnable discoveryTimeout;
    private Runnable descriptorWriteTimeout;
    private Runnable batteryReadTimeout;
    private Runnable helperTelemetryReadTimeout;
    private Runnable helperTelemetryPoll;
    private Runnable serverTelemetryWakePoll;
    private long lastHelperTelemetrySuccessLogAt;
    @NonNull private String lastLoggedHelperTelemetry = "";
    private Runnable bondTimeout;
    private Runnable secureConnectStart;
    private Runnable nextClientAttempt;
    private Runnable scanTimeout;
    private Runnable ancsBondRetry;
    private Runnable autoAncsWaitTimeout;
    private long autoAncsWaitToken;
    private Runnable coldBackgroundAttachTask;
    private Runnable managedReconnectTask;
    private Runnable ambiguousAclProbeTask;
    private Runnable linkProbeTimeout;
    private BluetoothGatt linkProbeGatt;
    private long linkProbeGeneration;
    /** A timed-out raw read may still callback later; do not reuse this callback channel yet. */
    private BluetoothGatt poisonedRssiProbeGatt;
    /** True when the serialized RSSI operation verifies a server-facade handoff. */
    private boolean linkProbeForServerFacadeHandoff;
    private boolean linkProbeForIncomingSecurityEpoch;
    /** Raw RSSI read from an older epoch is draining; its callback must be discarded. */
    private boolean linkProbeDiscardResult;
    private BluetoothDevice linkProbeServerDevice;
    private long linkProbeSecurityEpoch;
    @NonNull private String linkProbeReason = "";
    /** Exact post-DISCONNECTED probe waiting for an older generic RSSI operation to finish. */
    private boolean serverFacadeProbeQueued;
    private BluetoothGatt queuedServerFacadeProbeGatt;
    private long queuedServerFacadeProbeGeneration;
    private BluetoothDevice queuedServerFacadeProbeDevice;
    private long queuedServerFacadeProbeSecurityEpoch;
    @NonNull private String queuedServerFacadeProbeReason = "";
    private boolean incomingEpochProbeQueued;
    private BluetoothGatt queuedIncomingEpochProbeGatt;
    private long queuedIncomingEpochProbeGeneration;
    private BluetoothDevice queuedIncomingEpochProbeDevice;
    private long queuedIncomingEpochProbeSecurityEpoch;
    @NonNull private String queuedIncomingEpochProbeReason = "";
    private int managedReconnectAttempt;
    private UUID batteryReadPendingUuid;
    private BatteryStage batteryStage = BatteryStage.NOT_STARTED;
    private boolean candidatePublishScheduled;
    private long lastCandidatePublishAt;
    private final Runnable candidatePublisher = () -> {
        candidatePublishScheduled = false;
        lastCandidatePublishAt = android.os.SystemClock.uptimeMillis();
        publishCandidatesNow();
    };

    public IphoneAncsTransport(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
        IntentFilter bondFilter =
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bondFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        this.context.registerReceiver(bondReceiver, bondFilter);
    }

    public void publishCapabilities() {
        boolean feature = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        log("Android API: " + android.os.Build.VERSION.SDK_INT);
        log("FEATURE_BLUETOOTH_LE: " + feature);
        if (adapter == null) {
            state("NO_ADAPTER");
            log("BluetoothAdapter отсутствует");
            return;
        }
        log("Bluetooth включён: " + adapter.isEnabled());
        log("Multiple advertisement: " + adapter.isMultipleAdvertisementSupported());
        log("Offloaded filtering: " + adapter.isOffloadedFilteringSupported());
        log("Offloaded batching: " + adapter.isOffloadedScanBatchingSupported());
        scanner = adapter.getBluetoothLeScanner();
        advertiser = adapter.getBluetoothLeAdvertiser();
        log("BLE scanner: " + (scanner != null));
        log("BLE advertiser: " + (advertiser != null));
        log("Автоматический GPS-style путь использует только публичные BLE API");
        addBondedDevices();
        state(adapter.isEnabled() ? "ГОТОВО К ТЕСТУ" : "BLUETOOTH_OFF");
    }

    public void startScan() {
        if (!ensureAdapter()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            state("SCAN_UNAVAILABLE");
            log("BluetoothLeScanner недоступен");
            return;
        }
        if (scanning) {
            log("Сканирование уже запущено");
            return;
        }
        addBondedDevices();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            scanning = true;
            state("СКАНИРОВАНИЕ BLE");
            log("BLE scan запущен в balanced-режиме без фильтра; "
                    + "AD type 0x15 разбирается вручную");
        } catch (RuntimeException failure) {
            state("SCAN_EXCEPTION");
            log("startScan exception: " + failure);
        }
    }

    /**
     * GPSTether-style bootstrap: the iPhone advertises the diagnostic service and Android owns
     * the BLE central role from the first packet. GATT client and physical link are therefore
     * created by the same connectGatt call instead of trying to attach a client to an already
     * established incoming peripheral-role link.
     */
    public void startIphonePeripheralClientTest() {
        startIphoneHelperFallback();
    }

    /**
     * One-time bootstrap/recovery path. Daily automatic operation should use
     * {@link #connectSavedIphone(String)} and does not require the Helper to be running.
     */
    public void startIphoneHelperFallback() {
        if (!ensureAdapter()) return;
        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        iphonePeripheralMode = true;
        helperBootstrapMode = true;

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            state("GPS-STYLE · SCAN UNAVAILABLE");
            log("BluetoothLeScanner недоступен");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            state("FALLBACK · ОТКРОЙТЕ IPHONE HELPER V4");
            log("v10 bootstrap: Android работает BLE central, как HWGPS/GPSTether");
            log("Фильтр scan: service " + DIAGNOSTIC_SERVICE
                    + "; этот scan нужен только для bootstrap/аварийного восстановления");
            scanTimeout = () -> {
                if (!iphonePeripheralMode || !scanning || iphoneConnectStarted) return;
                stopScan();
                state("IPHONE BLE НЕ НАЙДЕН");
                log("За " + GPS_SCAN_TIMEOUT_MS
                        + " ms реклама KX11-iPhone не найдена. "
                        + "Откройте Helper v4 и нажмите «Рекламировать iPhone по BLE»");
            };
            main.postDelayed(scanTimeout, GPS_SCAN_TIMEOUT_MS);
        } catch (RuntimeException failure) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            state("GPS-STYLE · SCAN EXCEPTION");
            log("startScan exception: " + failure);
        }
    }

    /**
     * Daily path for the selected bonded iPhone.
     *
     * <p>The cold path registers one LE background GATT client against the selected system bond.
     * This lets Android resolve iOS's rotating private address even when background advertising
     * hides the Helper UUID from Android scanners. After that owner has connected once it is
     * retained and re-armed indefinitely across radio loss. GPS-style scan/direct connect remains
     * an unbonded/bootstrap fallback, and two GATT clients are never active at once.</p>
     */
    public boolean connectSavedIphone(String address) {
        closing = false;
        retrySignalled = false;
        managedIncomingMode = false;
        if (!ensureAdapter()) return false;
        if (address == null || address.trim().isEmpty()) return false;
        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException invalidAddress) {
            log("Saved peer address invalid: `" + address + "`");
            return false;
        }
        boolean matchingGatt = gatt != null
                && activeClientTarget != null && sameDevice(activeClientTarget, device)
                && (clientConnectInFlight || gattClientConnected);
        boolean matchingScan = scanning && savedPeerScanTarget != null
                && sameDevice(savedPeerScanTarget, device);
        boolean matchingScheduledAttach = coldBackgroundAttachTask != null
                && managedSavedPeer != null && sameDevice(managedSavedPeer, device);
        if (iphonePeripheralMode && !helperBootstrapMode
                && (matchingGatt || matchingScan || matchingScheduledAttach)) {
            log("Saved-peer GATT уже активен для "
                    + safeAddress(device) + "; дубликат connectGatt не создаю"
                    + " connected=" + gattClientConnected
                    + " inFlight=" + clientConnectInFlight
                    + " scanning=" + matchingScan
                    + " scheduled=" + matchingScheduledAttach);
            state(gattReady
                    ? "ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ"
                    : "АВТО · SAVED PEER УЖЕ ЗАРЕГИСТРИРОВАН");
            return true;
        }

        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        managedReconnectEnabled = true;
        managedReconnectAttempt = 0;
        managedSavedPeer = device;
        managedResolvedPeer = null;
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        if (!claimVerifiedPeer(device)) {
            managedReconnectEnabled = false;
            managedSavedPeer = null;
            iphonePeripheralMode = false;
            state("AUTO · SAVED PEER CONFLICT");
            return false;
        }
        // A bonded iPhone can hide the Helper UUID and local name while iOS is in the background.
        // Register one durable LE background owner against Android's saved bond/IRK instead of
        // waiting for an advertisement that Android is not allowed to see. The GPS-style scan is
        // retained only for an unbonded/bootstrap peer where identity resolution is unavailable.
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            return scheduleColdBackgroundAttach(device,
                    "cold selected-phone attach after process start");
        }
        log("Saved iPhone peer не BOND_BONDED; background RPA resolution недоступен, "
                + "перехожу к Helper scan");
        return startSavedPeerScan(device);
    }

    /**
     * Opt-in reverse route: KX11 is the link-layer peripheral/GATT server and iPhone Helper is
     * the central that initiates the connection with the ANCS-required Core Bluetooth option.
     * Android subsequently registers its ANCS GATT client against the exact incoming peer; this
     * is a second GATT role on the same physical BLE link, not a second radio connection.
     */
    public boolean acceptIphoneCentral(String address) {
        return acceptIphoneCentral(address, address);
    }

    public boolean acceptIphoneCentral(String address, String classicAddress) {
        closing = false;
        retrySignalled = false;
        if (!ensureAdapter()) return false;
        if (address == null || address.trim().isEmpty()) return false;
        final BluetoothDevice selected;
        try {
            selected = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException invalidAddress) {
            log("Saved peer address invalid: `" + address + "`");
            return false;
        }

        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        managedReconnectEnabled = true;
        managedIncomingMode = true;
        log("HA1211 physical-facade Pie opportunistic reverse attach enabled · "
                + "separate pair authorization facade · autoConnect=false "
                + "opportunistic=true · no public fallback");
        managedReconnectAttempt = 0;
        managedSavedPeer = selected;
        boolean dedicatedIdentity = classicAddress != null
                && !address.trim().equalsIgnoreCase(classicAddress.trim());
        managedResolvedPeer = dedicatedIdentity ? selected : null;
        if (managedResolvedPeer != null) {
            // The saved value can be yesterday's resolvable private address.  It is only a hint
            // for diagnostics until this GATT-server generation receives PAIR from the actual
            // incoming link.  Pre-claiming it made every rotated iOS RPA look like a foreign
            // callback and returned ATT status 8 (insufficient authorization) forever.
            log("Reverse route сохранил прежнюю BLE identity только как hint "
                    + safeAddress(managedResolvedPeer)
                    + "; текущий incoming peer будет подтверждён заново через PAIR + SECURE");
        }
        iphonePeripheralMode = false;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        state(LOCAL_LOGICAL_NAME + " · IPHONE CENTRAL MODE");
        log("Выбран обратный маршрут: KX11 peripheral/GATT server, "
                + "iPhone Helper central; Classic Bluetooth не изменяется");
        return startGeelyAncsAdvertising();
    }

    private boolean scheduleColdBackgroundAttach(@NonNull BluetoothDevice device,
                                                  @NonNull String reason) {
        if (closing || !managedReconnectEnabled || helperBootstrapMode) return false;
        if (coldBackgroundAttachTask != null) {
            log("Cold background attach уже запланирован; дубль пропущен");
            return true;
        }
        if (gatt != null || clientConnectInFlight || gattClientConnected || scanning) {
            log("Cold background attach не планируется: BLE owner/scan уже активен");
            return true;
        }
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        state(REMOTE_LOGICAL_NAME + " · COLD START · STACK QUIESCENCE");
        coldBackgroundAttachTask = () -> {
            coldBackgroundAttachTask = null;
            if (closing || !managedReconnectEnabled || helperBootstrapMode
                    || managedSavedPeer == null
                    || !sameDevice(managedSavedPeer, device)
                    || !sessionState.isCurrent(waitGeneration)) return;
            if (!startManagedBackgroundAttach(device, reason)) {
                scheduleManagedReconnect("cold background attach could not start");
            }
        };
        main.postDelayed(coldBackgroundAttachTask, COLD_BACKGROUND_ATTACH_DELAY_MS);
        log("Один cold background GATT owner будет зарегистрирован через "
                + COLD_BACKGROUND_ATTACH_DELAY_MS + " ms · " + reason);
        return true;
    }

    /**
     * Registers the sole long-lived GATT client for the selected bonded iPhone.
     *
     * <p>There is deliberately no connection timeout here. {@code autoConnect=true} is a pending
     * background registration, not a direct attempt: iOS may remain silent for an arbitrary time
     * and Android must keep resolving its RPA from the system bond. The owner is replaced only
     * after an explicit terminal callback/exception, never merely because a timer elapsed.</p>
     */
    private boolean startManagedBackgroundAttach(@NonNull BluetoothDevice selected,
                                                 @NonNull String reason) {
        if (closing || !managedReconnectEnabled || helperBootstrapMode) return false;
        if (!ensureAdapter()) return false;
        if (gatt != null || clientConnectInFlight || gattClientConnected || scanning) {
            log("Background attach не запущен: другая BLE-операция уже активна");
            return true;
        }
        BluetoothDevice target = managedResolvedPeer != null
                ? managedResolvedPeer : selected;
        if (safeBondState(target) != BluetoothDevice.BOND_BONDED) {
            log("Background attach отклонён: target не BOND_BONDED · "
                    + safeAddress(target));
            return startSavedPeerScan(selected);
        }
        if (!claimVerifiedPeer(target)) {
            state("AUTO · SAVED PEER CONFLICT");
            return false;
        }

        cancelAmbiguousAclProbe();
        stopScan();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = true;
        activeClientTarget = target;
        activeClientAutoConnect = true;
        activeClientOpportunistic = false;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        state(REMOTE_LOGICAL_NAME + " · BACKGROUND ATTACH · PERSISTENT");
        log("connectGatt(autoConnect=true, TRANSPORT_LE) · target="
                + safeAddress(target) + " bond=" + bondLabel(safeBondState(target))
                + " · one durable cold-start owner · " + reason);
        try {
            BluetoothGatt created = target.connectGatt(context, true, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            gatt = created;
            if (created == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                activeClientAutoConnect = false;
                log("Background connectGatt вернул null");
                scheduleManagedReconnect("background connectGatt returned null");
            } else {
                log("Background GATT зарегистрирован без закрывающего тайм-аута; "
                        + "жду системное RPA/IRK reconnect-событие");
            }
            return true;
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            activeClientAutoConnect = false;
            gatt = null;
            log("Background connectGatt exception: " + failure);
            scheduleManagedReconnect("background attach exception");
            return true;
        }
    }

    /**
     * Explicit recovery hook for ECARX builds that report ACL loss but omit the GATT callback.
     * Keeping the current transport alive preserves Android's resolved iOS BLE identity; closing
     * and recreating it here is exactly what made recovery depend on toggling car Bluetooth.
     */
    public void requestSavedPeerReconnect(@NonNull String reason) {
        requestSavedPeerReconnect(reason, true);
    }

    /**
     * Recovers the managed link without trusting an ambiguous ECARX ACL broadcast.
     *
     * @param confirmedLeLoss true only when Android explicitly identified the lost transport as
     *                        LE; false causes a non-destructive RSSI liveness probe first
     */
    public void requestSavedPeerReconnect(@NonNull String reason, boolean confirmedLeLoss) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> requestSavedPeerReconnect(reason, confirmedLeLoss));
            return;
        }
        if (closing || !managedReconnectEnabled || managedSavedPeer == null) return;
        if (managedIncomingMode) {
            if (!confirmedLeLoss) {
                log("Неоднозначный/Classic ACL loss не управляет reverse BLE link · "
                        + reason);
                return;
            }
            preserveManagedIncomingPublicationAfterLinkLoss(reason.trim().isEmpty()
                    ? "confirmed incoming iPhone link loss" : reason);
            return;
        }
        BluetoothGatt pendingOwner = gatt;
        if (pendingOwner != null && activeClientAutoConnect && clientConnectInFlight
                && !activeClientEstablished
                && sessionState.is(activeClientGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) {
            // The cold owner is already registered with Android and is waiting for the bonded
            // iPhone to become connectable. ACL broadcasts (including explicit LE loss) describe
            // exactly the absence this owner was created to survive; replacing it would discard
            // the controller's pending RPA/IRK resolution and reintroduce reconnect churn.
            state(REMOTE_LOGICAL_NAME + " · RECOVERING · BACKGROUND WAIT");
            log("Сохраняю ожидающий background GATT owner; ACL event не создаёт новый client · "
                    + reason);
            return;
        }
        if (!confirmedLeLoss && isAncsReady()) {
            scheduleAmbiguousAclProbe(reason.trim().isEmpty()
                    ? "ambiguous selected-phone ACL loss" : reason);
            return;
        }
        BluetoothGatt owner = gatt;
        if (owner != null && activeClientEstablished
                && sessionState.isCurrent(activeClientGeneration)) {
            if (gattClientConnected) {
                restartDiscoveryOnPersistentOwner(owner, activeClientGeneration,
                        reason.trim().isEmpty() ? "selected iPhone link refresh" : reason);
            } else {
                awaitPersistentGattReconnect(owner, activeClientGeneration,
                        reason.trim().isEmpty() ? "selected iPhone ACL link lost" : reason);
            }
        } else {
            scheduleManagedReconnect(reason.trim().isEmpty()
                    ? "selected iPhone ACL link lost" : reason);
        }
        state(REMOTE_LOGICAL_NAME + " · RECOVERING");
    }

    private void scheduleAmbiguousAclProbe(@NonNull String reason) {
        if (ambiguousAclProbeTask != null || linkProbeTimeout != null) {
            log("Неоднозначный ACL loss уже проверяется; дубль игнорирую");
            return;
        }
        BluetoothGatt expected = gatt;
        long expectedGeneration = activeClientGeneration;
        if (expected == null || !isAncsReady()
                || !sessionState.isCurrent(expectedGeneration)) {
            scheduleManagedReconnect(reason + "; live ANCS owner absent");
            state(REMOTE_LOGICAL_NAME + " · RECOVERING");
            return;
        }
        ambiguousAclProbeTask = () -> {
            ambiguousAclProbeTask = null;
            if (closing || expected != gatt
                    || !sessionState.isCurrent(expectedGeneration)) return;
            if (!isAncsReady()) {
                scheduleManagedReconnect(reason + "; ANCS lost during grace");
                state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                return;
            }
            if (isRssiProbeChannelPoisoned(expected)) {
                log("RSSI liveness probe заблокирован: callback channel poisoned");
                poisonRssiProbeChannelAndRearm(expected, expectedGeneration,
                        expected.getDevice(), reason + "; RSSI callback channel poisoned");
                return;
            }
            sessionState.move(expectedGeneration,
                    AncsSessionStateMachine.Phase.VERIFYING_LINK);
            boolean started;
            try {
                started = expected.readRemoteRssi();
            } catch (RuntimeException failure) {
                started = false;
                log("readRemoteRssi liveness probe exception: " + failure);
            }
            if (!started) {
                scheduleManagedReconnect(reason + "; liveness probe rejected");
                state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                return;
            }
            linkProbeGatt = expected;
            linkProbeGeneration = expectedGeneration;
            linkProbeForServerFacadeHandoff = false;
            linkProbeServerDevice = null;
            linkProbeSecurityEpoch = 0L;
            linkProbeReason = reason;
            linkProbeTimeout = () -> {
                if (!ownsGenericLinkProbe(expected, expectedGeneration)) return;
                if (closing || expected != gatt
                        || !sessionState.isCurrent(expectedGeneration)) return;
                log("GATT liveness probe не дал callback за "
                        + LINK_PROBE_TIMEOUT_MS + " ms");
                BluetoothDevice recoveryDevice = queuedServerFacadeProbeDevice != null
                        ? queuedServerFacadeProbeDevice : expected.getDevice();
                poisonRssiProbeChannelAndRearm(expected, expectedGeneration,
                        recoveryDevice, reason + "; liveness probe timeout");
            };
            main.postDelayed(linkProbeTimeout, LINK_PROBE_TIMEOUT_MS);
            log("Тип ACL transport не указан; проверяю живой ANCS GATT, "
                    + "не закрывая его · " + reason);
        };
        main.postDelayed(ambiguousAclProbeTask, AMBIGUOUS_ACL_GRACE_MS);
    }

    private void cancelAmbiguousAclProbe() {
        if (ambiguousAclProbeTask != null) main.removeCallbacks(ambiguousAclProbeTask);
        if (linkProbeTimeout != null) main.removeCallbacks(linkProbeTimeout);
        ambiguousAclProbeTask = null;
        linkProbeTimeout = null;
        linkProbeGatt = null;
        linkProbeGeneration = 0L;
        linkProbeForServerFacadeHandoff = false;
        linkProbeForIncomingSecurityEpoch = false;
        linkProbeDiscardResult = false;
        linkProbeServerDevice = null;
        linkProbeSecurityEpoch = 0L;
        linkProbeReason = "";
        serverFacadeProbeQueued = false;
        queuedServerFacadeProbeGatt = null;
        queuedServerFacadeProbeGeneration = 0L;
        queuedServerFacadeProbeDevice = null;
        queuedServerFacadeProbeSecurityEpoch = 0L;
        queuedServerFacadeProbeReason = "";
        incomingEpochProbeQueued = false;
        queuedIncomingEpochProbeGatt = null;
        queuedIncomingEpochProbeGeneration = 0L;
        queuedIncomingEpochProbeDevice = null;
        queuedIncomingEpochProbeSecurityEpoch = 0L;
        queuedIncomingEpochProbeReason = "";
    }

    private boolean ownsGenericLinkProbe(@NonNull BluetoothGatt expected,
                                         long expectedGeneration) {
        return linkProbeGatt == expected
                && linkProbeGeneration == expectedGeneration
                && !linkProbeForServerFacadeHandoff
                && !linkProbeForIncomingSecurityEpoch
                && !linkProbeDiscardResult;
    }

    private boolean isRssiProbeChannelPoisoned(@NonNull BluetoothGatt expected) {
        return poisonedRssiProbeGatt == expected;
    }

    private void clearRssiProbePoisonAfterGattClosed(@NonNull BluetoothGatt expected) {
        if (poisonedRssiProbeGatt != expected) return;
        poisonedRssiProbeGatt = null;
        log("RSSI callback channel poison очищен только после close GATT wrapper");
    }

    /**
     * A timeout cannot prove that Bluetooth finished the raw read. Clear all logical probe state,
     * poison this callback channel against future reads, and invalidate current-link authorization.
     * The same BluetoothGatt can never probe again: an RSSI callback carries no connection
     * lifecycle id, so even a late callback after reconnect could be mistaken for a newer read.
     * Only closing/replacing the wrapper creates an unambiguous callback-channel boundary.
     */
    private void poisonRssiProbeChannelAndRearm(@NonNull BluetoothGatt expected,
                                                long expectedGeneration,
                                                @NonNull BluetoothDevice device,
                                                @NonNull String reason) {
        if (gatt != expected || !sessionState.isCurrent(expectedGeneration)) {
            cancelAmbiguousAclProbe();
            return;
        }
        GattServerPeer connectedServer = managedIncomingMode
                ? findConnectedServerPeer(device) : null;
        BluetoothDevice exactIncoming = connectedServer == null
                ? null : connectedServer.device;
        boolean canReplaceOnCurrentIncomingLink = exactIncoming != null
                && isSelectedBondedIncomingDevice(exactIncoming)
                && poisonedWrapperReplacementAttempt
                < RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS;
        poisonedRssiProbeGatt = expected;
        cancelAmbiguousAclProbe();
        log("RSSI callback timeout: channel poisoned until GATT wrapper replacement · "
                + reason);
        if (managedIncomingMode && !canStartIncomingClientAttach(
                incomingPairAcceptedFacade,
                publishedDiagnosticServicePublicationToken)) {
            log("Pre-READY RSSI timeout quarantined logically; close/retry deferred until "
                    + "captured READY");
            return;
        }
        if (managedIncomingMode && activeClientOpportunistic) {
            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            boolean physicalLost = resetRetiredObserverAfterServerFacadeLoss(
                    attemptPeer, device,
                    "opportunistic RSSI timeout after server facade loss");
            state(physicalLost
                    ? "OPPORTUNISTIC RSSI FAILED · PHYSICAL LINK LOST"
                    : "OPPORTUNISTIC RSSI CHANNEL FAILED · LINK KEPT · BUDGET SPENT");
            log("Client-only RSSI timeout retired opportunistic observer close-only; inbound "
                    + (physicalLost
                    ? "server facade absent, logical epoch reset"
                    : "server/proofs/F04 kept, no replacement"));
            return;
        }
        // close() is the only safe callback-channel barrier. A late RSSI callback from expected
        // then fails callbackGatt == gatt after a replacement object is installed.
        closeClientGatt(expected);
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        if (managedIncomingMode) {
            if (canReplaceOnCurrentIncomingLink) {
                poisonedWrapperReplacementAttempt++;
                cancelClientAttemptCallbacks();
                activeClientProvenSecurityEpoch = 0L;
                log("Poisoned client wrapper closed; bounded replacement #"
                        + poisonedWrapperReplacementAttempt
                        + " uses still-CONNECTED exact server facade; current PAIR/B3/ANCS-READY "
                        + "epoch is retained and only client liveness proof is reset");
                adoptIncomingClientCandidate(exactIncoming,
                        "bounded replacement after RSSI timeout");
                scheduleIncomingClientAttachRetry("bounded replacement after RSSI timeout");
            } else {
                resetIncomingSecurityAfterClientLoss(device,
                        "poisoned wrapper has no safe replacement link · " + reason);
                preserveManagedIncomingPublicationAfterLinkLoss(
                        "RSSI timeout; wait for next incoming link");
            }
            return;
        }
        scheduleManagedReconnect("poisoned GATT wrapper replaced · " + reason);
        state(REMOTE_LOGICAL_NAME + " · RECOVERING");
    }

    /**
     * A missing mandatory NS/DS descriptor callback poisons that BluetoothGatt callback channel.
     * close() is the only boundary that prevents its late callback from satisfying a successor.
     */
    private void poisonMandatoryDescriptorChannelAndRecover(
            @NonNull BluetoothGatt expected, long expectedClientGeneration,
            long expectedSecurityEpoch, long expectedPublicationToken,
            @NonNull String reason) {
        if (gatt != expected || !sessionState.isCurrent(expectedClientGeneration)) return;
        BluetoothDevice device = expected.getDevice();
        GattServerPeer connectedServer = managedIncomingMode
                ? findConnectedServerPeer(device) : null;
        BluetoothDevice exactIncoming = connectedServer == null
                ? null : connectedServer.device;
        boolean canReplaceOnCurrentIncomingLink = managedIncomingMode
                && gattClientConnected && activeClientEstablished
                && expectedSecurityEpoch == incomingSecurityEpoch
                && isCurrentDiagnosticServicePublicationToken(expectedPublicationToken)
                && secureAttConfirmed && incomingAncsReadyGateOpen
                && secureAttPublicationToken == expectedPublicationToken
                && incomingAncsReadyPublicationToken == expectedPublicationToken
                && exactIncoming != null
                && isSelectedBondedIncomingDevice(exactIncoming)
                && poisonedWrapperReplacementAttempt
                < RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS;
        clearDescriptorWriteOperation();
        if (managedIncomingMode && !canStartIncomingClientAttach(
                incomingPairAcceptedFacade, expectedPublicationToken)) {
            log("Pre-READY descriptor timeout quarantined logically; close/retry deferred "
                    + "until captured READY");
            return;
        }
        if (managedIncomingMode && activeClientOpportunistic) {
            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            boolean physicalLost = resetRetiredObserverAfterServerFacadeLoss(
                    attemptPeer, device,
                    "opportunistic descriptor timeout after server facade loss");
            state(physicalLost
                    ? "OPPORTUNISTIC DESCRIPTOR FAILED · PHYSICAL LINK LOST"
                    : "OPPORTUNISTIC DESCRIPTOR CHANNEL FAILED · LINK KEPT · BUDGET SPENT");
            log("Client-only descriptor timeout retired opportunistic observer close-only; inbound "
                    + (physicalLost
                    ? "server facade absent, logical epoch reset"
                    : "server/proofs/F04 kept, no replacement"));
            return;
        }
        closeClientGatt(expected);
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        if (canReplaceOnCurrentIncomingLink) {
            poisonedWrapperReplacementAttempt++;
            cancelClientAttemptCallbacks();
            activeClientProvenSecurityEpoch = 0L;
            log("Mandatory ANCS CCCD wrapper closed; bounded replacement #"
                    + poisonedWrapperReplacementAttempt
                    + " uses exact connected F04 facade; publication/B3/READY epoch retained · "
                    + reason);
            adoptIncomingClientCandidate(exactIncoming,
                    "bounded replacement after mandatory descriptor timeout");
            scheduleIncomingClientAttachRetry(
                    "bounded replacement after mandatory descriptor timeout");
            return;
        }
        if (managedIncomingMode) {
            resetIncomingSecurityAfterClientLoss(device,
                    "mandatory descriptor timeout has no safe current-F04 replacement · "
                            + reason);
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "mandatory descriptor timeout; wait for next incoming link");
            return;
        }
        scheduleManagedReconnect("mandatory descriptor callback channel poisoned · " + reason);
    }

    /** A discovery timeout is ambiguous; never submit a successor on the same callback wrapper. */
    private void poisonDiscoveryChannelAndRecover(
            @NonNull BluetoothGatt expected, long expectedClientGeneration,
            long expectedSecurityEpoch, long expectedPublicationToken,
            @NonNull String reason) {
        if (gatt != expected || !sessionState.isCurrent(expectedClientGeneration)) return;
        BluetoothDevice device = expected.getDevice();
        GattServerPeer connectedServer = managedIncomingMode
                ? findConnectedServerPeer(device) : null;
        BluetoothDevice exactIncoming = connectedServer == null
                ? null : connectedServer.device;
        boolean canReplaceOnCurrentIncomingLink = managedIncomingMode
                && gattClientConnected && activeClientEstablished
                && expectedSecurityEpoch == incomingSecurityEpoch
                && isCurrentDiagnosticServicePublicationToken(expectedPublicationToken)
                && secureAttConfirmed && incomingAncsReadyGateOpen
                && secureAttPublicationToken == expectedPublicationToken
                && incomingAncsReadyPublicationToken == expectedPublicationToken
                && exactIncoming != null
                && isSelectedBondedIncomingDevice(exactIncoming)
                && poisonedWrapperReplacementAttempt
                < RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS;
        clearDiscoveryLineage();
        if (managedIncomingMode && !canStartIncomingClientAttach(
                incomingPairAcceptedFacade, expectedPublicationToken)) {
            log("Pre-READY discovery timeout quarantined logically; close/retry deferred "
                    + "until captured READY");
            return;
        }
        if (managedIncomingMode && activeClientOpportunistic) {
            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            boolean physicalLost = resetRetiredObserverAfterServerFacadeLoss(
                    attemptPeer, device,
                    "opportunistic discovery timeout after server facade loss");
            state(physicalLost
                    ? "OPPORTUNISTIC DISCOVERY FAILED · PHYSICAL LINK LOST"
                    : "OPPORTUNISTIC DISCOVERY CHANNEL FAILED · LINK KEPT · BUDGET SPENT");
            log("Client-only discovery timeout retired opportunistic observer close-only; inbound "
                    + (physicalLost
                    ? "server facade absent, logical epoch reset"
                    : "server/proofs/F04 kept, no replacement"));
            return;
        }
        closeClientGatt(expected);
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        if (canReplaceOnCurrentIncomingLink) {
            poisonedWrapperReplacementAttempt++;
            cancelClientAttemptCallbacks();
            activeClientProvenSecurityEpoch = 0L;
            log("Discovery callback wrapper closed; bounded replacement #"
                    + poisonedWrapperReplacementAttempt
                    + " uses exact connected F04 facade; publication/B3/READY retained · "
                    + reason);
            adoptIncomingClientCandidate(exactIncoming,
                    "bounded replacement after discovery timeout");
            scheduleIncomingClientAttachRetry("bounded replacement after discovery timeout");
            return;
        }
        if (managedIncomingMode) {
            resetIncomingSecurityAfterClientLoss(device,
                    "discovery timeout has no safe current-F04 replacement · " + reason);
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "discovery timeout; wait for next incoming link");
            return;
        }
        scheduleManagedReconnect("discovery callback channel poisoned · " + reason);
    }

    private boolean ownsServerFacadeHandoffProbe(@NonNull BluetoothGatt expected,
                                                  long expectedGeneration,
                                                  long expectedSecurityEpoch,
                                                  @NonNull BluetoothDevice serverDevice) {
        return linkProbeGatt == expected
                && linkProbeGeneration == expectedGeneration
                && linkProbeForServerFacadeHandoff
                && !linkProbeDiscardResult
                && linkProbeSecurityEpoch == expectedSecurityEpoch
                && linkProbeServerDevice == serverDevice;
    }

    private void cancelServerFacadeHandoffProbeIfOwned(@NonNull BluetoothGatt expected,
                                                        long expectedGeneration,
                                                        long expectedSecurityEpoch,
                                                        @NonNull BluetoothDevice serverDevice) {
        if (ownsServerFacadeHandoffProbe(expected, expectedGeneration,
                expectedSecurityEpoch, serverDevice)) {
            cancelAmbiguousAclProbe();
        }
    }

    private boolean ownsIncomingEpochProbe(@NonNull BluetoothGatt expected,
                                            long expectedGeneration,
                                            long expectedSecurityEpoch,
                                            @NonNull BluetoothDevice serverDevice) {
        return linkProbeGatt == expected
                && linkProbeGeneration == expectedGeneration
                && linkProbeForIncomingSecurityEpoch
                && !linkProbeDiscardResult
                && linkProbeSecurityEpoch == expectedSecurityEpoch
                && linkProbeServerDevice == serverDevice;
    }

    private void cancelIncomingEpochProbeIfOwned(@NonNull BluetoothGatt expected,
                                                  long expectedGeneration,
                                                  long expectedSecurityEpoch,
                                                  @NonNull BluetoothDevice serverDevice) {
        if (ownsIncomingEpochProbe(expected, expectedGeneration,
                expectedSecurityEpoch, serverDevice)) {
            cancelAmbiguousAclProbe();
        }
    }

    private void queueServerFacadeHandoffProbe(@NonNull BluetoothGatt expected,
                                                long expectedGeneration,
                                                long expectedSecurityEpoch,
                                                @NonNull BluetoothDevice serverDevice,
                                                @NonNull String reason) {
        // A post-DISCONNECTED read is stronger than a queued new-epoch read: when it succeeds it
        // proves both retained-client liveness for this epoch and the role-facade handoff. Keep
        // only that one successor so a single raw RSSI operation remains in flight at a time.
        incomingEpochProbeQueued = false;
        queuedIncomingEpochProbeGatt = null;
        queuedIncomingEpochProbeGeneration = 0L;
        queuedIncomingEpochProbeDevice = null;
        queuedIncomingEpochProbeSecurityEpoch = 0L;
        queuedIncomingEpochProbeReason = "";
        serverFacadeProbeQueued = true;
        queuedServerFacadeProbeGatt = expected;
        queuedServerFacadeProbeGeneration = expectedGeneration;
        queuedServerFacadeProbeDevice = serverDevice;
        queuedServerFacadeProbeSecurityEpoch = expectedSecurityEpoch;
        queuedServerFacadeProbeReason = reason;
        log("Server-facade probe поставлен после текущего raw RSSI; "
                + "его pre-DISCONNECTED результат не будет переиспользован");
    }

    /**
     * Finishes any operation that began before server DISCONNECTED, discards its result for
     * handoff purposes, and issues a new post-DISCONNECTED RSSI read. The legacy method name is
     * retained because generic ACL probing was the first caller, but incoming-epoch reads use the
     * same serialization barrier. Returns true whenever a queued request consumed the result.
     */
    private boolean drainQueuedServerFacadeProbeAfterGeneric(
            @NonNull BluetoothGatt completedGatt, long completedGeneration,
            @NonNull String completion) {
        if (!serverFacadeProbeQueued
                || queuedServerFacadeProbeGatt != completedGatt
                || queuedServerFacadeProbeGeneration != completedGeneration
                || queuedServerFacadeProbeDevice == null) return false;
        BluetoothDevice serverDevice = queuedServerFacadeProbeDevice;
        long securityEpoch = queuedServerFacadeProbeSecurityEpoch;
        String reason = queuedServerFacadeProbeReason;
        // This cancel owns the completed generic probe and its exact queued successor. No newer
        // operation has been started yet, so clearing both is atomic on the main looper.
        cancelAmbiguousAclProbe();
        if (closing || gatt != completedGatt || !managedIncomingMode
                || !activeClientEstablished || !gattClientConnected
                || !sessionState.isCurrent(completedGeneration)
                || incomingSecurityEpoch != securityEpoch) return true;
        log("Prior RSSI завершён (" + completion
                + "); запускаю отдельный post-DISCONNECTED handoff probe");
        scheduleServerFacadeHandoffProbe(serverDevice,
                reason + "; new RSSI after generic " + completion);
        return true;
    }

    /** Logically cancels an old raw read but keeps its callback slot until it drains. */
    private void prepareInFlightLinkProbeForFreshEpoch() {
        prepareInFlightLinkProbeForFreshEpoch(true);
    }

    private void prepareInFlightLinkProbeForFreshEpoch(boolean emitDiagnosticLog) {
        if (linkProbeGatt == null) {
            cancelAmbiguousAclProbe();
            return;
        }
        if (ambiguousAclProbeTask != null) main.removeCallbacks(ambiguousAclProbeTask);
        if (linkProbeTimeout != null) main.removeCallbacks(linkProbeTimeout);
        ambiguousAclProbeTask = null;
        linkProbeTimeout = null;
        linkProbeForServerFacadeHandoff = false;
        linkProbeForIncomingSecurityEpoch = false;
        linkProbeDiscardResult = true;
        linkProbeServerDevice = null;
        linkProbeSecurityEpoch = 0L;
        linkProbeReason = "superseded by fresh incoming epoch";
        serverFacadeProbeQueued = false;
        queuedServerFacadeProbeGatt = null;
        queuedServerFacadeProbeGeneration = 0L;
        queuedServerFacadeProbeDevice = null;
        queuedServerFacadeProbeSecurityEpoch = 0L;
        queuedServerFacadeProbeReason = "";
        incomingEpochProbeQueued = false;
        queuedIncomingEpochProbeGatt = null;
        queuedIncomingEpochProbeGeneration = 0L;
        queuedIncomingEpochProbeDevice = null;
        queuedIncomingEpochProbeSecurityEpoch = 0L;
        queuedIncomingEpochProbeReason = "";
        armDiscardedRawProbeDrainTimeout();
        if (emitDiagnosticLog) {
            log("Старый raw RSSI probe логически отменён; его результат будет отброшен "
                    + "до запуска любого successor read");
        }
    }

    private void armDiscardedRawProbeDrainTimeout() {
        BluetoothGatt drainingGatt = linkProbeGatt;
        long drainingGeneration = linkProbeGeneration;
        if (!linkProbeDiscardResult || drainingGatt == null) return;
        if (linkProbeTimeout != null) main.removeCallbacks(linkProbeTimeout);
        linkProbeTimeout = () -> {
            if (!linkProbeDiscardResult || linkProbeGatt != drainingGatt
                    || linkProbeGeneration != drainingGeneration) return;
            BluetoothDevice recoveryDevice = queuedServerFacadeProbeDevice != null
                    ? queuedServerFacadeProbeDevice
                    : queuedIncomingEpochProbeDevice != null
                    ? queuedIncomingEpochProbeDevice : drainingGatt.getDevice();
            poisonRssiProbeChannelAndRearm(drainingGatt, drainingGeneration,
                    recoveryDevice, "discarded raw probe timeout");
        };
        main.postDelayed(linkProbeTimeout, LINK_PROBE_TIMEOUT_MS);
    }

    private void queueIncomingEpochProbeBehindDiscardedRead(
            @NonNull BluetoothGatt expected, long expectedGeneration,
            long expectedSecurityEpoch, @NonNull BluetoothDevice serverDevice,
            @NonNull String reason) {
        incomingEpochProbeQueued = true;
        queuedIncomingEpochProbeGatt = expected;
        queuedIncomingEpochProbeGeneration = expectedGeneration;
        queuedIncomingEpochProbeDevice = serverDevice;
        queuedIncomingEpochProbeSecurityEpoch = expectedSecurityEpoch;
        queuedIncomingEpochProbeReason = reason;
        armDiscardedRawProbeDrainTimeout();
        log("New-epoch client proof ожидает завершения старого raw RSSI callback");
    }

    private void finishDiscardedRawProbeAndStartQueuedEpoch(@NonNull String completion) {
        if (!linkProbeDiscardResult || linkProbeGatt == null) return;
        boolean facadeQueued = serverFacadeProbeQueued;
        BluetoothGatt facadeGatt = queuedServerFacadeProbeGatt;
        long facadeGeneration = queuedServerFacadeProbeGeneration;
        BluetoothDevice facadeDevice = queuedServerFacadeProbeDevice;
        long facadeSecurityEpoch = queuedServerFacadeProbeSecurityEpoch;
        String facadeReason = queuedServerFacadeProbeReason;
        boolean queued = incomingEpochProbeQueued;
        BluetoothGatt expected = queuedIncomingEpochProbeGatt;
        long generation = queuedIncomingEpochProbeGeneration;
        BluetoothDevice serverDevice = queuedIncomingEpochProbeDevice;
        long securityEpoch = queuedIncomingEpochProbeSecurityEpoch;
        String reason = queuedIncomingEpochProbeReason;
        cancelAmbiguousAclProbe();
        if (facadeQueued && facadeGatt != null && facadeDevice != null
                && !closing && gatt == facadeGatt && managedIncomingMode
                && activeClientEstablished && gattClientConnected
                && sessionState.isCurrent(facadeGeneration)
                && incomingSecurityEpoch == facadeSecurityEpoch) {
            log("Старый RSSI result отброшен (" + completion
                    + "); запускаю новый post-DISCONNECTED facade read");
            scheduleServerFacadeHandoffProbe(facadeDevice,
                    facadeReason + "; after discarded " + completion);
            return;
        }
        if (!queued || expected == null || serverDevice == null
                || closing || gatt != expected || !managedIncomingMode
                || !activeClientEstablished || !gattClientConnected
                || !sessionState.isCurrent(generation)
                || incomingSecurityEpoch != securityEpoch) return;
        log("Старый RSSI result отброшен (" + completion
                + "); теперь запускаю новый read для security epoch=" + securityEpoch);
        scheduleIncomingEpochClientLivenessProbe(serverDevice,
                reason + "; after discarded " + completion);
    }

    /**
     * A server DISCONNECTED callback is not enough to distinguish Android's role-facade handoff
     * from real ACL loss. Verify the retained, already-established clientIf without disconnecting
     * it. Only a successful RSSI callback promotes the pending facade to a confirmed handoff.
     */
    private void scheduleServerFacadeHandoffProbe(@NonNull BluetoothDevice serverDevice,
                                                  @NonNull String reason) {
        BluetoothGatt expected = gatt;
        long expectedGeneration = activeClientGeneration;
        long expectedSecurityEpoch = incomingSecurityEpoch;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (!hasCurrentIncomingPostReadyTranscript(
                incomingPairAcceptedFacade, publicationToken)) {
            log("Server-facade RSSI proof quarantined: zero client-role commands before "
                    + "exact current PAIR+B3+READY");
            return;
        }
        if (closing || !managedIncomingMode || expected == null
                || !activeClientEstablished || !gattClientConnected
                || !sessionState.isCurrent(expectedGeneration)
                || findCurrentServerPeer(serverDevice) == null) {
            log("Server-facade liveness probe не стартовал: established owner уже отсутствует");
            return;
        }
        if (isRssiProbeChannelPoisoned(expected)) {
            log("Server-facade probe заблокирован: RSSI channel poisoned");
            poisonRssiProbeChannelAndRearm(expected, expectedGeneration, serverDevice,
                    reason + "; poisoned callback channel");
            return;
        }
        if (linkProbeForServerFacadeHandoff && linkProbeGatt == expected
                && linkProbeServerDevice == serverDevice
                && linkProbeSecurityEpoch == expectedSecurityEpoch) {
            log("Server-facade liveness probe уже выполняется; дубль пропущен");
            return;
        }

        if (linkProbeGatt == expected) {
            // Never reinterpret any read that started before server DISCONNECTED. Its callback
            // says nothing about post-disconnect liveness. Queue a second physical read; this also
            // supersedes a queued/active new-epoch proof because the facade read proves both.
            queueServerFacadeHandoffProbe(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice, reason);
            return;
        }

        cancelAmbiguousAclProbe();
        linkProbeGatt = expected;
        linkProbeGeneration = expectedGeneration;
        linkProbeForServerFacadeHandoff = true;
        linkProbeServerDevice = serverDevice;
        linkProbeSecurityEpoch = expectedSecurityEpoch;
        linkProbeReason = reason;
        boolean started;
        try {
            started = expected.readRemoteRssi();
        } catch (RuntimeException failure) {
            started = false;
            log("Server-facade readRemoteRssi exception: " + failure);
        }
        if (!started) {
            cancelAmbiguousAclProbe();
            failServerFacadeHandoffProbe(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice,
                    reason + "; readRemoteRssi rejected");
            return;
        }
        armServerFacadeHandoffProbeTimeout(expected, expectedGeneration,
                expectedSecurityEpoch, serverDevice, reason);
        state("SERVER FACADE LOST · VERIFYING RETAINED CLIENT");
        log("readRemoteRssi handoff probe started; живой GATT owner не разрывается · "
                + reason);
    }

    private void armServerFacadeHandoffProbeTimeout(@NonNull BluetoothGatt expected,
                                                     long expectedGeneration,
                                                     long expectedSecurityEpoch,
                                                     @NonNull BluetoothDevice serverDevice,
                                                     @NonNull String reason) {
        linkProbeTimeout = () -> {
            if (!ownsServerFacadeHandoffProbe(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice)) return;
            if (closing || gatt != expected
                    || !sessionState.isCurrent(expectedGeneration)
                    || incomingSecurityEpoch != expectedSecurityEpoch) {
                // This runnable still owns the exact old probe, so it may clear it. Never clear a
                // newer probe whose GATT/generation/epoch/kind no longer match these captures.
                cancelServerFacadeHandoffProbeIfOwned(expected, expectedGeneration,
                        expectedSecurityEpoch, serverDevice);
                return;
            }
            log("Server-facade RSSI probe не дал callback за "
                    + LINK_PROBE_TIMEOUT_MS + " ms");
            poisonRssiProbeChannelAndRearm(expected, expectedGeneration, serverDevice,
                    reason + "; liveness probe timeout");
        };
        main.postDelayed(linkProbeTimeout, LINK_PROBE_TIMEOUT_MS);
    }

    private void failServerFacadeHandoffProbe(@NonNull BluetoothGatt expected,
                                               long expectedGeneration,
                                               long expectedSecurityEpoch,
                                               @NonNull BluetoothDevice serverDevice,
                                               @NonNull String reason) {
        if (closing || !managedIncomingMode || gatt != expected
                || !activeClientEstablished
                || !sessionState.isCurrent(expectedGeneration)
                || incomingSecurityEpoch != expectedSecurityEpoch) return;
        gattClientConnected = false;
        recoverEstablishedIncomingClientAfterCallbackLoss(expected,
                "server-facade liveness failed · " + reason, false);
    }

    /** Proves that a retained clientIf is live in the newly-created server security epoch. */
    private void scheduleIncomingEpochClientLivenessProbe(
            @NonNull BluetoothDevice serverDevice, @NonNull String reason) {
        BluetoothGatt expected = gatt;
        long expectedGeneration = activeClientGeneration;
        long expectedSecurityEpoch = incomingSecurityEpoch;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (!hasCurrentIncomingPostReadyTranscript(
                incomingPairAcceptedFacade, publicationToken)) {
            log("New-epoch RSSI proof quarantined: zero client-role commands before exact "
                    + "current PAIR+B3+READY");
            return;
        }
        if (closing || !managedIncomingMode || expected == null
                || !activeClientEstablished || !gattClientConnected
                || activeClientProvenSecurityEpoch == expectedSecurityEpoch
                || !sessionState.isCurrent(expectedGeneration)
                || activeClientTarget == null
                || !sameDevice(activeClientTarget, serverDevice)
                || findCurrentServerPeer(serverDevice) == null) return;
        if (isRssiProbeChannelPoisoned(expected)) {
            log("New-epoch probe заблокирован: RSSI channel poisoned");
            poisonRssiProbeChannelAndRearm(expected, expectedGeneration, serverDevice,
                    reason + "; poisoned callback channel");
            return;
        }

        if (ownsIncomingEpochProbe(expected, expectedGeneration,
                expectedSecurityEpoch, serverDevice)) return;
        if (ownsServerFacadeHandoffProbe(expected, expectedGeneration,
                expectedSecurityEpoch, serverDevice)
                || (serverFacadeProbeQueued
                && queuedServerFacadeProbeGatt == expected
                && queuedServerFacadeProbeGeneration == expectedGeneration
                && queuedServerFacadeProbeSecurityEpoch == expectedSecurityEpoch)) {
            // A current-epoch post-DISCONNECTED facade read is the stronger proof and will open
            // the same discovery gate on success.
            return;
        }
        if (linkProbeGatt != null) {
            if (!linkProbeDiscardResult) {
                prepareInFlightLinkProbeForFreshEpoch();
            }
            if (linkProbeDiscardResult && linkProbeGatt != null) {
                queueIncomingEpochProbeBehindDiscardedRead(expected, expectedGeneration,
                        expectedSecurityEpoch, serverDevice, reason);
                return;
            }
        }
        cancelAmbiguousAclProbe();
        linkProbeGatt = expected;
        linkProbeGeneration = expectedGeneration;
        linkProbeForServerFacadeHandoff = false;
        linkProbeForIncomingSecurityEpoch = true;
        linkProbeDiscardResult = false;
        linkProbeServerDevice = serverDevice;
        linkProbeSecurityEpoch = expectedSecurityEpoch;
        linkProbeReason = reason;
        boolean started;
        try {
            started = expected.readRemoteRssi();
        } catch (RuntimeException failure) {
            started = false;
            log("New-epoch readRemoteRssi exception: " + failure);
        }
        if (!started) {
            cancelIncomingEpochProbeIfOwned(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice);
            failServerFacadeHandoffProbe(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice,
                    reason + "; new-epoch readRemoteRssi rejected");
            return;
        }
        linkProbeTimeout = () -> {
            if (!ownsIncomingEpochProbe(expected, expectedGeneration,
                    expectedSecurityEpoch, serverDevice)) return;
            poisonRssiProbeChannelAndRearm(expected, expectedGeneration, serverDevice,
                    reason + "; new-epoch liveness timeout");
        };
        main.postDelayed(linkProbeTimeout, LINK_PROBE_TIMEOUT_MS);
        state("NEW SERVER EPOCH · VERIFYING RETAINED CLIENT");
        log("Новый post-CONNECTED RSSI read доказывает retained owner для epoch="
                + expectedSecurityEpoch);
    }

    /**
     * Keeps the one successfully established Android GATT client alive across radio loss. This is
     * deliberately independent of the original autoConnect flag: AOSP BluetoothGatt.connect()
     * reuses the same registered client and changes it into the background reconnect owner.
     */
    private void awaitPersistentGattReconnect(@NonNull BluetoothGatt expected,
                                              long expectedGeneration,
                                              @NonNull String reason) {
        if (managedIncomingMode && activeClientOpportunistic) {
            log("Opportunistic reverse clientIf остаётся non-holding: persistent connect() rearm "
                    + "запрещён · " + reason);
            return;
        }
        if (!canIssueManagedIncomingRearm(expected)) {
            log("Persistent reconnect quarantined until the exact post-READY tuple owns it · "
                    + reason);
            return;
        }
        cancelConnectTimeout();
        cancelAmbiguousAclProbe();
        boolean rawOwnerClosed = clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        if (rawOwnerClosed || gatt != expected) {
            scheduleManagedReconnect("ambiguous raw callback owner replaced · " + reason);
            return;
        }
        gattClientConnected = false;
        clientConnectInFlight = true;
        activeClientAutoConnect = true;
        if (!sessionState.move(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
        state(REMOTE_LOGICAL_NAME + " · RECOVERING · PERSISTENT WAIT");
        rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
    }

    /**
     * Reuses the already registered Android GATT owner indefinitely. Closing this object
     * unregisters the background listener; on KX11/Android 9 a fresh scan then often cannot see
     * the bonded iPhone until the user toggles Bluetooth. A watchdog therefore calls connect()
     * on the same owner and re-schedules itself without creating a competing GATT client.
     */
    private void rearmPersistentGattOwner(@NonNull BluetoothGatt expected,
                                         long expectedGeneration,
                                         @NonNull String reason,
                                         boolean immediate) {
        if (managedIncomingMode && activeClientOpportunistic) {
            log("Opportunistic reverse clientIf не может вызвать BluetoothGatt.connect() · "
                    + reason);
            return;
        }
        if (closing || gatt != expected || !clientConnectInFlight
                || !sessionState.is(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
        if (!canIssueManagedIncomingRearm(expected)) {
            log("Persistent GATT rearm quarantined: exact current PAIR+B3+READY "
                    + "attempt tuple is absent · " + reason);
            return;
        }
        if (immediate) {
            boolean accepted;
            try {
                accepted = expected.connect();
            } catch (RuntimeException failure) {
                accepted = false;
                log("Persistent GATT owner connect() exception: " + failure);
            }
            log("Persistent GATT owner re-armed=" + accepted + " · " + reason);
        }
        cancelConnectTimeout();
        connectTimeout = () -> {
            connectTimeout = null;
            if (closing || gatt != expected || !clientConnectInFlight
                    || !sessionState.is(expectedGeneration,
                    AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
            log("iPhone ещё не вернулся за " + PERSISTENT_RECONNECT_WATCHDOG_MS
                    + " ms; сохраняю единственного GATT owner и повторяю connect()");
            rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
        };
        main.postDelayed(connectTimeout, PERSISTENT_RECONNECT_WATCHDOG_MS);
        log("Постоянный GATT owner сохранён без таймера закрытия · " + reason);
    }

    /** Re-discovers changed services on the same connected owner without touching the radio. */
    private void restartDiscoveryOnPersistentOwner(@NonNull BluetoothGatt expected,
                                                   long expectedGeneration,
                                                   @NonNull String reason) {
        if (closing || gatt != expected || !gattClientConnected
                || !activeClientEstablished
                || !sessionState.isCurrent(expectedGeneration)) return;
        if (!canIssueManagedIncomingRearm(expected)) {
            log("Persistent rediscovery quarantined until the exact post-READY tuple owns it · "
                    + reason);
            return;
        }
        if (managedReconnectTask != null) return;
        cancelAmbiguousAclProbe();
        boolean rawOwnerClosed = clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        if (rawOwnerClosed || gatt != expected) {
            incomingDiscoveryStarted = false;
            if (managedIncomingMode) {
                recoverIncomingClientRole("ambiguous raw callback owner replaced · " + reason);
            } else {
                scheduleManagedReconnect("ambiguous raw callback owner replaced · " + reason);
            }
            return;
        }
        state(REMOTE_LOGICAL_NAME + " · RECOVERING · SAME GATT DISCOVERY");
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || gatt != expected || !gattClientConnected
                    || !activeClientEstablished
                    || !sessionState.isCurrent(expectedGeneration)) return;
            log("Повторяю service discovery на том же GATT owner · " + reason);
            discoverServices(expected);
        };
        main.postDelayed(managedReconnectTask, SAVED_PEER_SCAN_RESTART_DELAY_MS);
    }

    private boolean startSavedPeerScan(@NonNull BluetoothDevice device) {
        if (closing || !iphonePeripheralMode || helperBootstrapMode) return false;
        if (gatt != null || clientConnectInFlight || gattClientConnected) {
            log("Identity scan не запущен: GATT connect/session уже активен");
            return true;
        }
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN UNAVAILABLE");
            log("BluetoothLeScanner недоступен для saved-peer reconnect");
            return false;
        }
        String address = safeAddress(device);
        if (address.isEmpty()) {
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN FAILED · EMPTY ADDRESS");
            return false;
        }
        // Do not put the Classic/public address into a hardware scan filter. iOS rotates its BLE
        // private address, and several ECARX firmwares apply the filter before the controller has
        // resolved the bond/IRK. A broad software scan lets Android return the resolved
        // BluetoothDevice and we then apply the selected-phone gate ourselves.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build();
        savedPeerScanTarget = device;
        activeScanGeneration = sessionState.begin(AncsSessionStateMachine.Phase.SCANNING);
        long expectedGeneration = activeScanGeneration;
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            scanning = true;
            state(REMOTE_LOGICAL_NAME + " · IDENTITY SCAN");
            log("GPS-style autoscan: selected=" + address
                    + ", LOW_LATENCY/unfiltered; connectGatt начнётся только после "
                    + "private Helper UUID/selected identity gate");
            BluetoothDevice expected = device;
            scanTimeout = () -> {
                if (closing || helperBootstrapMode || iphoneConnectStarted
                        || !scanning || savedPeerScanTarget == null
                        || !sameDevice(savedPeerScanTarget, expected)
                        || !sessionState.is(expectedGeneration,
                        AncsSessionStateMachine.Phase.SCANNING)) return;
                log("Saved-peer autoscan работает " + SAVED_PEER_SCAN_RESTART_MS
                        + " ms без match; безопасно перерегистрирую один scan");
                stopScan();
                main.postDelayed(() -> {
                    if (!closing && managedReconnectEnabled
                            && iphonePeripheralMode && !helperBootstrapMode
                            && !iphoneConnectStarted) {
                        startSavedPeerScan(expected);
                    }
                }, SAVED_PEER_SCAN_RESTART_DELAY_MS);
            };
            main.postDelayed(scanTimeout, SAVED_PEER_SCAN_RESTART_MS);
            return true;
        } catch (RuntimeException failure) {
            scanning = false;
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN FAILED");
            log("saved-peer startScan exception: " + failure);
            return false;
        }
    }

    public void stopScan() {
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        boolean wasScanning = scanning;
        scanning = false;
        savedPeerScanTarget = null;
        if (!wasScanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (RuntimeException failure) {
            log("stopScan exception: " + failure);
        }
        state("СКАНИРОВАНИЕ ОСТАНОВЛЕНО");
    }

    /**
     * Publishes the stable application-owned Geely_ANCS identity without renaming the system
     * Bluetooth adapter. Classic HFP/A2DP/PBAP therefore keep the stock Geely name.
     */
    private boolean startGeelyAncsAdvertising() {
        if (!ensureAdapter()) return false;
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            log(LOCAL_LOGICAL_NAME + ": BluetoothLeAdvertiser недоступен; "
                    + "продолжаю Android-central recovery");
            return false;
        }
        if (advertising || advertisingPending || advertisingDesired) return true;

        // A production ANCS accessory exposes one stable GATT database. The iPhone-side helper
        // only uses the beacon as a link anchor and never discovers this Android service, so
        // rotating UUIDs cannot improve cache correctness and only creates reconnect deadlocks.
        useStaticDiagnosticNamespace();
        // HA1208 deliberately builds no ADV bytes yet. A candidate nonce is derived immediately
        // before addService, but only the exact-current SUCCESS callback may commit it and build
        // an observable advertisement. A failed/stale callback can therefore expose no token.
        clearPreparedAdvertising();
        solicitationAdvertising = false;
        advertisingDesired = true;
        state(LOCAL_LOGICAL_NAME + " · STARTING");
        log("Публикую стабильный BLE link-anchor " + serverDiagnosticService
                + " как " + LOCAL_LOGICAL_NAME
                + "; fixed generation="
                + String.format(Locale.US, "%04X", serverDiagnosticGeneration)
                + " beacon=" + MANAGED_INCOMING_BEACON_SERVICE
                + "; системное имя Classic-адаптера не меняется");
        openGattServer();
        return gattServer != null;
    }

    /** Derives a retry-stable candidate without advancing the durable publication lineage. */
    private boolean preparePendingManagedIncomingPublicationNonce() {
        if (!managedIncomingMode || !advertisingDesired) return false;
        SharedPreferences preferences = context.getSharedPreferences(
                MANAGED_INCOMING_NAMESPACE_PREFS, Context.MODE_PRIVATE);
        int persisted = preferences.getInt(MANAGED_INCOMING_PUBLICATION_NONCE, 0);
        pendingManagedIncomingPublicationNonce =
                ManagedIncomingPublicationPolicy.nextPublicationNonce(persisted);
        publishedManagedIncomingPublicationNonce = 0;
        log("F04 publication nonce candidate="
                + String.format(Locale.US, "%06X", pendingManagedIncomingPublicationNonce)
                + " persisted=" + String.format(Locale.US, "%06X", persisted)
                + "; commit deferred until exact onServiceAdded SUCCESS");
        return true;
    }

    /**
     * Crash-consistent commit barrier. Advertising is forbidden if durable persistence fails,
     * because reusing an uncommitted incarnation after process death would be false recovery
     * proof for Helper.
     */
    private boolean commitManagedIncomingPublicationNonce(
            @NonNull BluetoothGattService service, long publicationToken) {
        int candidate = pendingManagedIncomingPublicationNonce;
        if (!managedIncomingMode || service != pendingDiagnosticServicePublication
                || publicationToken == 0L
                || publicationToken != pendingDiagnosticServicePublicationToken
                || publicationToken != serverDiagnosticServicePublicationToken
                || !ManagedIncomingPublicationPolicy.isValidPublicationNonce(candidate)) {
            log("F04 publication nonce commit rejected: stale callback lineage"
                    + " · token=" + publicationToken
                    + " candidate=" + String.format(Locale.US, "%06X", candidate));
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                MANAGED_INCOMING_NAMESPACE_PREFS, Context.MODE_PRIVATE);
        boolean committed = preferences.edit()
                .putInt(MANAGED_INCOMING_PUBLICATION_NONCE, candidate)
                .commit();
        if (!committed) {
            log("F04 publication nonce durable commit failed; advertising aborted · candidate="
                    + String.format(Locale.US, "%06X", candidate));
            return false;
        }
        publishedManagedIncomingPublicationNonce = candidate;
        pendingManagedIncomingPublicationNonce = 0;
        log("F04 publication nonce committed="
                + String.format(Locale.US, "%06X", candidate)
                + " for internalToken=" + publicationToken);
        return true;
    }

    /** Builds the exact-budget v2 ADV plus a v1 scan-response fallback after durable commit. */
    private void prepareManagedIncomingAdvertising(int publicationNonce) {
        byte[] publicationFrame = ManagedIncomingPublicationPolicy.publicationNonceFrame(
                serverDiagnosticGeneration, publicationNonce);
        byte[] legacyNamespaceFrame = ManagedIncomingPublicationPolicy.legacyNamespaceFrame(
                serverDiagnosticGeneration);
        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        // Flags (3) + 128-bit service AD (18) + manufacturer AD/company/frame (10) = 31 bytes.
        preparedAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(MANAGED_INCOMING_BEACON_SERVICE))
                .addManufacturerData(MANAGED_INCOMING_MANUFACTURER_ID, publicationFrame)
                .build();
        // Service-data logical name is independent from BluetoothAdapter#getName(). The legacy
        // v1 prefix lets Helper v42 recover generation 2F04 when it ignores manufacturer v2.
        preparedScanResponse = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(MANAGED_INCOMING_BEACON_SERVICE),
                        appendBytes(legacyNamespaceFrame,
                                LOCAL_LOGICAL_NAME.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    /** Allocates one persistent namespace per Android GATT-server publication. */
    private void rotateManagedIncomingDiagnosticNamespace() {
        SharedPreferences preferences = context.getSharedPreferences(
                MANAGED_INCOMING_NAMESPACE_PREFS, Context.MODE_PRIVATE);
        int previous = preferences.getInt(MANAGED_INCOMING_NAMESPACE_GENERATION, 0x2F04);
        int generation = (previous + 1) & 0xFFFF;
        if (generation == 0 || generation == 0xFFFF) generation = 1;
        preferences.edit().putInt(MANAGED_INCOMING_NAMESPACE_GENERATION, generation).apply();
        serverDiagnosticGeneration = generation;
        serverDiagnosticService = managedIncomingUuid(0, generation);
        serverDiagnosticCharacteristic = managedIncomingUuid(1, generation);
        serverControlCharacteristic = managedIncomingUuid(2, generation);
        serverSecureCharacteristic = managedIncomingUuid(3, generation);
        serverTelemetryCharacteristicUuid = managedIncomingUuid(4, generation);
    }

    private void useStaticDiagnosticNamespace() {
        serverDiagnosticGeneration = 0x2F04;
        serverDiagnosticService = DIAGNOSTIC_SERVICE;
        serverDiagnosticCharacteristic = DIAGNOSTIC_CHARACTERISTIC;
        serverControlCharacteristic = CONTROL_CHARACTERISTIC;
        serverSecureCharacteristic = SECURE_CHARACTERISTIC;
        serverTelemetryCharacteristicUuid = TELEMETRY_CHARACTERISTIC;
    }

    private static UUID managedIncomingUuid(int kind, int generation) {
        return UUID.fromString(String.format(Locale.US,
                "d2d9e4b%d-47f1-4e44-a8bb-a932fd5a%04x",
                kind, generation & 0xFFFF));
    }

    private byte[] managedIncomingNamespaceFrame() {
        return ManagedIncomingPublicationPolicy.legacyNamespaceFrame(
                serverDiagnosticGeneration);
    }

    private static byte[] appendBytes(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Legacy comparison test. It uses only a normal public diagnostic advertisement. */
    public void startIncomingConnectionTest() {
        if (!ensureAdapter()) return;
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        // This public diagnostic route is not a continuation of a previous managed reverse
        // publication. A stale mode flag must not replace its prepared F04 advertisement with
        // the HA1208 managed beacon in onServiceAdded.
        managedReconnectEnabled = false;
        managedIncomingMode = false;
        managedSavedPeer = null;
        managedResolvedPeer = null;
        useStaticDiagnosticNamespace();
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            state("ADVERTISER_UNAVAILABLE");
            log("Контроллер/ECARX не предоставляет BluetoothLeAdvertiser");
            return;
        }

        AdvertiseData.Builder primary = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE));
        solicitationAdvertising = false;
        log("Запускаю обычную diagnostic-рекламу через публичный Android API");
        log("На iPhone откройте LightBlue, найдите UUID "
                + DIAGNOSTIC_SERVICE + " и нажмите Connect");
        log("В CONTROL " + CONTROL_CHARACTERISTIC
                + " запишите ASCII PAIR; только после этого peer будет подтверждён");

        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        preparedAdvertiseData = primary.build();
        preparedScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        advertisingDesired = true;
        state("ЗАПУСК GATT SERVER");
        openGattServer();
    }

    public void stopAdvertising() {
        PublicationAdvertiseCallback callback = activeAdvertiseCallback;
        activeAdvertiseCallback = null;
        boolean shouldStopFramework =
                callback != null || advertising || advertisingPending || advertisingDesired;
        advertisingDesired = false;
        advertising = false;
        advertisingPending = false;
        solicitationAdvertising = false;
        clearPreparedAdvertising();
        if (shouldStopFramework && callback != null) {
            try {
                callback.ownerAdvertiser.stopAdvertising(callback);
            } catch (RuntimeException failure) {
                log("stopAdvertising exception: " + failure);
            }
        }
        closeGattServer();
    }

    private void connectToAdvertisingIphone(BluetoothDevice device) {
        if (!iphonePeripheralMode || iphoneConnectStarted || device == null) return;
        iphoneConnectStarted = true;
        stopScan();
        if (!claimVerifiedPeer(device)) {
            iphonePeripheralMode = false;
            state("GPS-STYLE · PEER CONFLICT");
            log("Найденный iPhone не совпал с peer текущей test-session");
            return;
        }
        connectIphonePeripheral(device, GPS_CONNECT_TIMEOUT_MS,
                "ПОДКЛЮЧАЮ IPHONE · HELPER FALLBACK",
                "Helper-filtered scan; Android создаёт bootstrap BLE link первым");
    }

    private void connectToSavedAdvertisingIphone(@NonNull BluetoothDevice device,
                                                  boolean solicitsAncs,
                                                  boolean advertisesHelperService) {
        BluetoothDevice expected = savedPeerScanTarget;
        if (!iphonePeripheralMode || helperBootstrapMode || iphoneConnectStarted
                || expected == null
                || !sessionState.is(activeScanGeneration,
                AncsSessionStateMachine.Phase.SCANNING)) return;
        // The scan callback already passed the selected-phone gate. Re-check it here because iOS
        // commonly rotates from the saved Classic/public address to a bonded BLE private address;
        // requiring sameDevice() a second time discarded the valid resolved peer just before
        // connectGatt and was the reason the phone never reached ANCS discovery.
        if (!matchesManagedSavedPeer(expected, device, solicitsAncs,
                advertisesHelperService)) return;
        iphoneConnectStarted = true;
        stopScan();
        // The scan callback has just passed the selected-phone + private service gate. Promote the
        // current RPA atomically; generic claimVerifiedPeer() intentionally cannot make this
        // protocol-specific decision on its own.
        synchronized (verifiedPeerLock) {
            verifiedPeer = device;
        }
        managedResolvedPeer = device;
        connectIphonePeripheral(device, CONNECT_TIMEOUT_MS,
                "GPS-STYLE · SAVED PEER CONNECTING",
                "selected identity resolved; one direct GATT after advertisement");
    }

    private void connectIphonePeripheral(BluetoothDevice device, long timeoutMs,
                                         String connectingState, String reason) {
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        iphoneConnectStarted = true;
        activeClientTarget = device;
        activeClientAutoConnect = false;
        activeClientOpportunistic = false;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        state(connectingState);
        log("iPhone target: " + safeName(device) + " " + safeAddress(device)
                + " type=" + typeLabel(safeType(device))
                + " bond=" + bondLabel(safeBondState(device)));
        log("connectGatt(autoConnect=false, TRANSPORT_LE) · " + reason);

        try {
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                state("GPS-STYLE · CONNECT RETURNED NULL");
                log("connectGatt вернул null");
                return;
            }
            BluetoothGatt expected = gatt;
            connectTimeout = () -> {
                if (gatt != expected || !clientConnectInFlight
                        || !sessionState.is(expectedGeneration,
                        AncsSessionStateMachine.Phase.DIRECT_CONNECT)) return;
                clientConnectInFlight = false;
                log("Нет callback подключения за " + timeoutMs
                        + " ms · target=" + safeAddress(expected.getDevice())
                        + " autoConnect=false");
                closeClientGatt(expected);
                clearAncsRuntime();
                // state() owns the one serialized recovery transition. Publishing it only after
                // this never-established client is closed avoids closing the same GATT twice.
                state("GPS-STYLE · CONNECT TIMEOUT");
            };
            main.postDelayed(connectTimeout, timeoutMs);
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            state("GPS-STYLE · CONNECT EXCEPTION");
            log("connectGatt exception: " + failure);
        }
    }

    public void connect(Candidate candidate) {
        if (!secureAttConfirmed) {
            log("Same-peer attach отложен: сначала нужен SECURE ATT OK от verified peer");
            return;
        }
        BluetoothGatt current = gatt;
        if (current != null && gattClientConnected) {
            discoverServices(current);
            return;
        }
        if (managedIncomingMode) {
            BluetoothGatt pendingOwner = gatt;
            if (pendingOwner != null) {
                if (gattClientConnected) {
                    log("Повторный discoverServices на текущем same-peer GATT owner");
                    discoverServices(pendingOwner);
                } else if (activeClientEstablished) {
                    awaitIncomingBackgroundOwner(pendingOwner, activeClientGeneration,
                            "ручной same-peer reconnect");
                } else {
                    log("Первичный direct attach уже ожидает callback; ручной дубль не создаю");
                }
                return;
            }
            if (nextClientAttempt != null || clientConnectInFlight) {
                log("Direct same-peer clientIf уже регистрируется или запланирован");
                return;
            }
            startSamePeerAttach(false, "ручная direct-регистрация same-peer clientIf");
            return;
        }
        if (!backgroundAttachAttempted) {
            startSamePeerAttach(true, "ручной запуск");
        } else if (nextClientAttempt != null) {
            log("Единственный direct fallback уже запланирован после ошибки background attach");
        } else {
            log("Ручной повтор заблокирован: fallback запускается только автоматически "
                    + "после timeout/status failure первой попытки");
        }
    }

    public void requestBond() {
        BluetoothDevice device = getVerifiedPeer();
        if (device == null) {
            log("Нет активного verified peer: сначала подключите iPhone BLE");
            return;
        }
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("Активный iPhone BLE peer уже BOND_BONDED");
        } else {
            requestBond(device);
        }
    }

    public void refreshAndReconnect() {
        BluetoothGatt current = gatt;
        if (current != null && gattClientConnected) {
            log("Повторный discoverServices на текущем same-peer GATT client");
            discoverServices(current);
            return;
        }
        if (!secureAttConfirmed) {
            log("Обновление GATT отложено: сначала нужен SECURE ATT OK");
            return;
        }
        connect(null);
    }

    public void disconnect() {
        iphonePeripheralMode = false;
        iphoneConnectStarted = false;
        cancelColdBackgroundAttach();
        cancelAmbiguousAclProbe();
        sessionState.begin(AncsSessionStateMachine.Phase.IDLE);
        clearIphonePeripheralRuntime(true);
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        activeClientEstablished = false;
        activeClientProvenSecurityEpoch = 0L;
        incomingClientCandidate = null;
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        clearIncomingStaleOwnerReplacement();
        incomingAncsReadyGateOpen = false;
        secureAttPublicationToken = 0L;
        incomingAncsReadyPublicationToken = 0L;
        incomingDiscoveryStarted = false;
        BluetoothGatt old = gatt;
        boolean passiveOpportunisticOwner = activeClientOpportunistic;
        gatt = null;
        activeClientOpportunistic = false;
        if (old != null) {
            clearRssiProbePoisonAfterGattClosed(old);
            if (!passiveOpportunisticOwner) {
                try {
                    old.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
        state("ОТКЛЮЧЕНО");
    }

    public void close() {
        closing = true;
        managedReconnectEnabled = false;
        managedIncomingMode = false;
        managedSavedPeer = null;
        managedResolvedPeer = null;
        if (managedReconnectTask != null) main.removeCallbacks(managedReconnectTask);
        managedReconnectTask = null;
        cancelAmbiguousAclProbe();
        stopScan();
        stopAdvertising();
        disconnect();
        sessionState.close();
        resetVerifiedPeerSession();
        main.removeCallbacks(candidatePublisher);
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void addBondedDevices() {
        if (adapter == null) return;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            log("Нет доступа к bonded devices: " + denied);
            return;
        }
        for (BluetoothDevice device : bonded) {
            updateCandidate(device, -127, false, "", "bonded");
        }
    }

    private void resetVerifiedPeerSession() {
        cancelAmbiguousAclProbe();
        cancelServerTelemetryWakePoll();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        clearIphonePeripheralRuntime(true);
        cancelColdBackgroundAttach();
        cancelClientAttemptCallbacks();
        sessionGeneration++;
        incomingSecurityEpoch++;
        incomingPairRequestFacadeBoundEpoch = 0L;
        synchronized (gattServerPeers) {
            gattServerPeers.clear();
        }
        activeClientTarget = null;
        activeClientAutoConnect = false;
        activeClientOpportunistic = false;
        activeClientEstablished = false;
        activeClientProvenSecurityEpoch = 0L;
        backgroundAttachAttempted = false;
        directFallbackAttempted = false;
        incomingClientAttachAttempt = 0;
        poisonedWrapperReplacementAttempt = 0;
        clearIncomingStaleOwnerReplacement();
        mandatoryDescriptorStatus133RetryCount = 0;
        incomingClientCandidate = null;
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        incomingAncsReadyGateOpen = false;
        secureAttPublicationToken = 0L;
        incomingAncsReadyPublicationToken = 0L;
        incomingDiscoveryStarted = false;
        clientConnectInFlight = false;
        gattClientConnected = false;
        log("Новая test-session=" + sessionGeneration
                + "; verified peer и runtime-состояние очищены");
    }

    private void clearIncomingStaleOwnerReplacement() {
        clearIncomingStaleOwnerTransfer();
        incomingFreshReplacementConsumedEpoch = 0L;
    }

    private void clearIncomingStaleOwnerTransfer() {
        incomingStaleEstablishedOwner = null;
        incomingStaleOwnerAwaitingFreshEpoch = false;
        incomingStaleOwnerReplacementEpoch = 0L;
        incomingFreshReplacementGatt = null;
    }

    private boolean hasPendingStaleOwnerReplacementForCurrentEpoch() {
        return incomingStaleOwnerReplacementEpoch != 0L
                && incomingStaleOwnerReplacementEpoch == incomingSecurityEpoch
                && incomingFreshReplacementConsumedEpoch != incomingSecurityEpoch;
    }

    private void clearIphonePeripheralRuntime(boolean clearMode) {
        clearManagedLinkBoundProof();
        cancelHelperTelemetryRecovery();
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
            helperAncsReadyProofRetry = null;
        }
        iphonePairAttempted = false;
        iphonePairWritePending = false;
        iphoneSecureReadPending = false;
        iphoneSecureConfirmed = false;
        iphoneHelperTelemetrySubscriptionAttempted = false;
        iphoneHelperTelemetrySubscribed = false;
        iphoneHelperTelemetryReadPending = false;
        iphoneHelperValidTelemetryReceived = false;
        helperAncsReadyProofAttempted = false;
        helperAncsReadyProofPending = false;
        helperAncsReadyProofAcknowledged = false;
        iphoneHelperInitialReadAttempted = false;
        iphoneServiceSetupDeferredForHelperRead = false;
        cancelIphonePostSecureDiscovery();
        iphoneHelperTelemetrySetupBypass = false;
        iphoneAncsSeen = false;
        ancsRetryAfterBond = false;
        ancsAuthorizationFailureSeen = false;
        leBondAttemptObserved = false;
        ancsBondRetryCount = 0;
        iphoneSecureCharacteristic = null;
        iphoneTelemetryCharacteristic = null;
        if (clearMode) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            iphoneConnectStarted = false;
        }
    }

    private BluetoothDevice getVerifiedPeer() {
        synchronized (verifiedPeerLock) {
            return verifiedPeer;
        }
    }

    public String getVerifiedPeerAddress() {
        return safeAddress(getVerifiedPeer());
    }

    public String getVerifiedPeerName() {
        return safeName(getVerifiedPeer());
    }

    public boolean isAncsReady() {
        if (!gattReady || !gattClientConnected || gatt == null) return false;
        return !managedIncomingMode
                || (hasCurrentManagedLinkBoundProof(gatt)
                && iphoneHelperTelemetrySubscribed
                && iphoneHelperValidTelemetryReceived
                && helperAncsReadyProofAcknowledged);
    }

    /**
     * The first valid PAIR command fixes the peer for the whole test session. A later callback
     * from another device must never replace it.
     */
    private boolean claimVerifiedPeer(BluetoothDevice device) {
        if (device == null) return false;
        synchronized (verifiedPeerLock) {
            if (verifiedPeer == null) {
                verifiedPeer = device;
                return true;
            }
            if (sameDevice(verifiedPeer, device)) return true;
            // A bonded iPhone may reappear under an RPA. The custom incoming service and the
            // following encrypted SECURE characteristic are the primary proof; a unique bonded
            // name is only the tie-breaker that associates the resolved object with the selected
            // Classic phone.
            if (managedReconnectEnabled && managedSavedPeer != null
                    && safeBondState(managedSavedPeer) == BluetoothDevice.BOND_BONDED
                    && safeBondState(device) == BluetoothDevice.BOND_BONDED
                    && uniqueBondedNameMatch(managedSavedPeer, device)) {
                verifiedPeer = device;
                return true;
            }
            return false;
        }
    }

    /**
     * Saves the exact bonded facade as a candidate only. Android 9 may first deliver an anonymous
     * BOND_NONE facade, so neither callback is allowed to allocate a clientIf. The reverse route's
     * first {@code connectGatt} is issued only from the captured post-READY main task.
     */
    private void attachAncsClientToIncomingOwner(BluetoothDevice device) {
        if (!managedIncomingMode || device == null || findConnectedServerPeer(device) == null) {
            return;
        }
        if (!isSelectedBondedIncomingDevice(device)) {
            state("REQUIRES_ANCS LINK · ЖДУ BONDED IDENTITY");
            log("Incoming facade не используется для client attach · objectId="
                    + System.identityHashCode(device)
                    + " address=" + safeAddress(device)
                    + " bond=" + bondLabel(safeBondState(device))
                    + "; жду exact BOND_BONDED facade выбранного iPhone");
            return;
        }
        adoptIncomingClientCandidate(device, "bonded incoming GATT-server callback");
    }

    private boolean isSelectedBondedIncomingDevice(@NonNull BluetoothDevice device) {
        if (safeBondState(device) != BluetoothDevice.BOND_BONDED
                || managedSavedPeer == null) return false;
        return sameDevice(managedSavedPeer, device)
                || sameDevice(managedResolvedPeer, device)
                || (safeBondState(managedSavedPeer) == BluetoothDevice.BOND_BONDED
                && uniqueBondedNameMatch(managedSavedPeer, device));
    }

    private void adoptIncomingClientCandidate(@NonNull BluetoothDevice device,
                                              @NonNull String reason) {
        if (!managedIncomingMode || !isSelectedBondedIncomingDevice(device)
                || findConnectedServerPeer(device) == null) return;
        if (incomingPairAcceptedFacade != null
                && incomingPairAcceptedSessionGeneration == sessionGeneration
                && incomingPairAcceptedSecurityEpoch == incomingSecurityEpoch
                && incomingPairAcceptedFacade != device) {
            log("Incoming candidate rejected: address equality cannot replace current PAIR "
                    + "raw facade · " + reason);
            return;
        }
        BluetoothDevice previous = incomingClientCandidate;
        if (previous != null && !sameDevice(previous, device)) {
            log("Incoming direct candidate conflict: сохраняю первый exact bonded facade "
                    + safeAddress(previous) + ", отклоняю " + safeAddress(device));
            return;
        }
        incomingClientCandidate = device;
        managedResolvedPeer = device;
        state("REQUIRES_ANCS LINK · EXACT CANDIDATE SAVED");
        log("Exact bonded incoming facade сохранён как candidate без clientIf · objectId="
                + System.identityHashCode(device)
                + " address=" + safeAddress(device)
                + "; жду current PAIR + B3 + same-owner ANCS-READY · " + reason);
        if (!isCurrentDiagnosticServicePublicationToken(
                publishedDiagnosticServicePublicationToken)) {
            state("REQUIRES_ANCS LINK · ЖДУ F04 SERVICE BARRIER");
            log("Candidate сохранён; clientIf запрещён до current F04 publication + "
                    + "PAIR/B3/READY · " + reason);
            return;
        }
        maybeStartIncomingClientAttachAfterServicePublished(reason);
    }

    /** Publication/CONNECTED/PAIR paths only retain evidence; READY owns the first attach. */
    private void maybeStartIncomingClientAttachAfterServicePublished(@NonNull String reason) {
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (!managedIncomingMode
                || !isCurrentDiagnosticServicePublicationToken(publicationToken)) return;
        BluetoothDevice candidate = incomingClientCandidate;
        if (candidate == null || !isSelectedBondedIncomingDevice(candidate)
                || findConnectedServerPeer(candidate) == null) return;
        state("REQUIRES_ANCS LINK · ЖДУ PAIR/B3/READY");
        log("Current F04 и exact candidate готовы; zero pre-ready clientIf attempts · "
                + reason);
    }

    private boolean isVerifiedPeer(BluetoothDevice device) {
        return sameDevice(getVerifiedPeer(), device);
    }

    private static boolean sameDevice(BluetoothDevice first, BluetoothDevice second) {
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        String firstAddress = safeAddress(first);
        String secondAddress = safeAddress(second);
        return !firstAddress.isEmpty()
                && firstAddress.equalsIgnoreCase(secondAddress);
    }

    @Nullable
    private Boolean commitPairCommand(BluetoothDevice device, long publicationToken,
                                      @Nullable byte[] pairChallenge) {
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)) {
            return null;
        }
        if (!isVerifiedPeer(device) || findCurrentServerPeer(device) == null) {
            return null;
        }
        boolean firstPairProof = !managedIncomingMode;
        if (managedIncomingMode && isSelectedBondedIncomingDevice(device)) {
            GattServerPeer currentPeer = findCurrentServerPeer(device);
            boolean sameAcceptedLink = currentPeer != null
                    && currentPeer == incomingPairAcceptedServerPeer
                    && incomingPairAcceptedFacade != null
                    && currentPeer.device == incomingPairAcceptedFacade
                    && incomingPairAcceptedSessionGeneration == sessionGeneration
                    && incomingPairAcceptedSecurityEpoch == incomingSecurityEpoch
                    && incomingPairAcceptedPublicationToken == publicationToken;
            if (sameAcceptedLink) {
                if (pairChallenge == null
                        || !Arrays.equals(incomingPairAcceptedChallenge, pairChallenge)) {
                    return null;
                }
                firstPairProof = false;
            } else {
                if (pairChallenge == null
                        || pairChallenge.length != MANAGED_PROOF_FRAME_BYTES - 1) {
                    return null;
                }
                GattServerPeer exactPairPeer = findExactCurrentServerPeer(device);
                if (exactPairPeer == null || !exactPairPeer.connected) return null;
                firstPairProof = true;
                incomingPairAcceptedFacade = device;
                incomingPairAcceptedServerPeer = exactPairPeer;
                incomingPairAcceptedSessionGeneration = sessionGeneration;
                incomingPairAcceptedSecurityEpoch = incomingSecurityEpoch;
                incomingPairAcceptedPublicationToken = publicationToken;
                incomingPairAcceptedChallenge = pairChallenge.clone();
            }
            if (firstPairProof) incomingClientAttachAttempt = 0;
        }
        return Boolean.valueOf(firstPairProof);
    }

    /** UI, logging, bonding and candidate adoption happen only after PAIR ATT success is sent. */
    private void finishPairCommand(BluetoothDevice device, long publicationToken,
                                   boolean firstPairProof) {
        BluetoothDevice transcriptFacade = managedIncomingMode
                ? incomingPairAcceptedFacade : device;
        if (managedIncomingMode && transcriptFacade != null
                && isSelectedBondedIncomingDevice(transcriptFacade)) {
            adoptIncomingClientCandidate(transcriptFacade,
                    "PAIR on stable incoming link record; candidate only");
        }
        state("VERIFIED PEER · CURRENT LINK CHALLENGE");
        log((firstPairProof ? "PAIR принят" : "Duplicate PAIR принят idempotently")
                + ". VERIFIED PEER: " + safeName(transcriptFacade)
                + " " + safeAddress(transcriptFacade)
                + " objectId=" + System.identityHashCode(transcriptFacade)
                + " callbackObjectId=" + System.identityHashCode(device)
                + " serverPeerId=" + System.identityHashCode(incomingPairAcceptedServerPeer)
                + " type=" + typeLabel(safeType(transcriptFacade))
                + " bond=" + bondLabel(safeBondState(transcriptFacade)));
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("PAIR: общий Classic/LE peer уже BOND_BONDED; первая B3 READ запросит "
                    + "security именно текущего LE link. clientIf attempts=0 до "
                    + "same-owner ANCS-READY");
        } else {
            requestBond(device);
            log("connectGatt отложен до подтверждения текущего ATT link");
        }
    }

    /** Commits the current-link proof before ATT success is returned to Core Bluetooth. */
    @Nullable
    private Boolean markSecureAttConfirmed(BluetoothDevice device, long publicationToken) {
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)) return null;
        if (findCurrentServerPeer(device) == null) return null;
        if (managedIncomingMode && !hasCurrentIncomingPairProof(device, publicationToken)) {
            return null;
        }
        synchronized (verifiedPeerLock) {
            if (!sameDevice(verifiedPeer, device)) return null;
            boolean first = !secureAttConfirmed;
            secureAttConfirmed = true;
            secureAttPublicationToken = publicationToken;
            return first;
        }
    }

    private void handleSecureAttSuccess(BluetoothDevice device, String operation,
                                        long publicationToken) {
        Boolean first = markSecureAttConfirmed(device, publicationToken);
        if (first == null) {
            log("SECURE callback проигнорирован: это не verified peer");
            return;
        }
        finishSecureAttSuccess(device, operation, first, publicationToken);
    }

    private void finishSecureAttSuccess(BluetoothDevice device, String operation,
                                        boolean first, long publicationToken) {
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)
                || secureAttPublicationToken != publicationToken) {
            log("SECURE completion проигнорирован: F04 publication token уже сменился");
            return;
        }
        if (!isVerifiedPeer(device)) {
            log("SECURE completion проигнорирован: verified session уже сменился");
            return;
        }
        if (managedIncomingMode) {
            managedResolvedPeer = device;
            listener.onVerifiedPeerAddress(safeAddress(device));
        }
        state("CURRENT LINK OK · SAME-PEER ATTACH");
        log("SECURE ATT OK · " + operation + " · peer=" + safeAddress(device)
                + (first ? " · current-link challenge confirmed" : " · повтор"));
        if (!first) {
            log("Повторный SECURE ATT OK не создаёт новую connectGatt-попытку");
            return;
        }
        if (findCurrentServerPeer(device) == null) {
            state("VERIFIED SERVER LINK LOST");
            log("Same-peer attach отменён: verified GATT-server link уже не активен");
            if (managedIncomingMode) {
                preserveManagedIncomingPublicationAfterLinkLoss(
                        "secure proof completed after server link loss");
            }
            return;
        }
        if (managedIncomingMode) {
            state("REQUIRES_ANCS LINK SECURE · ЖДУ HELPER READY");
            if (incomingClientCandidate == null && isSelectedBondedIncomingDevice(device)) {
                adoptIncomingClientCandidate(device, "B3 current-link proof");
            }
            log("Текущий ATT link прошёл B3; clientIf attempts="
                    + incomingClientAttachAttempt
                    + ", первый attach ждёт ANCS-READY без разрыва");
            return;
        }
        scheduleSecureClientStart();
    }

    private boolean canAcceptAncsReady(BluetoothDevice device, long publicationToken) {
        BluetoothDevice rawFacade = incomingPairAcceptedFacade;
        GattServerPeer exactServerPeer = findExactCurrentServerPeer(rawFacade);
        return AncsRecoveryPolicy.canAcceptAncsReadyProof(
                managedIncomingMode,
                isCurrentDiagnosticServicePublicationToken(publicationToken),
                hasCurrentIncomingPairProof(device, publicationToken),
                secureAttConfirmed,
                secureAttPublicationToken == publicationToken,
                isVerifiedPeer(device),
                exactServerPeer != null && exactServerPeer.connected,
                rawFacade != null && exactServerPeer != null
                        && exactServerPeer.device == rawFacade);
    }

    /**
     * Helper confirms that this encrypted owner was opened with RequiresANCS and passed B3.
     * Provisional iOS ANCS authorization is not part of this same-owner proof.
     */
    @Nullable
    private IncomingReadyAttach commitAncsReady(BluetoothDevice callbackDevice,
                                                 long publicationToken) {
        if (!canAcceptAncsReady(callbackDevice, publicationToken)) {
            return null;
        }
        byte[] pairChallenge = incomingPairAcceptedChallenge;
        if (pairChallenge == null
                || pairChallenge.length != MANAGED_PROOF_FRAME_BYTES - 1) return null;
        BluetoothDevice exactIncomingDevice = incomingPairAcceptedFacade;
        GattServerPeer serverLink = findExactCurrentServerPeer(exactIncomingDevice);
        if (serverLink == null || !serverLink.connected
                || serverLink.sessionGeneration != sessionGeneration
                || serverLink.securityEpoch != incomingSecurityEpoch) {
            return null;
        }
        synchronized (verifiedPeerLock) {
            verifiedPeer = exactIncomingDevice;
        }
        boolean firstReadyProof = !incomingAncsReadyGateOpen
                || incomingAncsReadyPublicationToken != publicationToken;
        incomingAncsReadyGateOpen = true;
        incomingAncsReadyPublicationToken = publicationToken;
        incomingClientCandidate = exactIncomingDevice;
        if (firstReadyProof) incomingClientAttachAttempt = 0;
        BluetoothDevice physicalLinkFacade = serverLink.physicalLinkFacade;
        boolean attachTaskArmed = armIncomingReadyAttachLatch(
                serverLink, physicalLinkFacade, exactIncomingDevice,
                pairChallenge, publicationToken);
        return new IncomingReadyAttach(serverLink, physicalLinkFacade,
                exactIncomingDevice, pairChallenge,
                sessionGeneration, incomingSecurityEpoch, publicationToken,
                firstReadyProof, attachTaskArmed);
    }

    /** UI/listener work is deliberately after the checked ATT response. */
    private void finishAncsReadyCommit(@NonNull IncomingReadyAttach captured) {
        managedResolvedPeer = captured.rawFacade;
        listener.onVerifiedPeerAddress(safeAddress(captured.rawFacade));
        state("ONE REQUIRES_ANCS OWNER · CLIENT ATTACH");
        log("Same-owner ANCS-READY принят после RequiresANCS + B3, без disconnect · "
                + "pairObjectId="
                + System.identityHashCode(captured.rawFacade)
                + " physicalObjectId="
                + System.identityHashCode(captured.physicalLinkFacade)
                + " sameAddress=" + sameDevice(
                captured.rawFacade, captured.physicalLinkFacade)
                + " address=" + safeAddress(captured.rawFacade)
                + "; proof committed, post-READY clientIf task ещё не выполнялся");
    }

    private void scheduleCapturedIncomingAttachAfterReady(
            @NonNull IncomingReadyAttach captured) {
        Runnable task = new Runnable() {
            @Override public void run() {
                if (incomingReadyAttachTask != this
                        || !hasCurrentIncomingReadyAttachLatch(
                        captured.rawFacade, captured.publicationToken)) return;
                // The tuple latch remains consumed after execution. A duplicate READY can never
                // arm another immediate attempt; only the normal bounded retry path may continue.
                incomingReadyAttachTask = null;
                startCapturedIncomingAttachAfterReady(captured);
            }
        };
        incomingReadyAttachTask = task;
        main.postDelayed(task, SECURE_TO_CLIENT_CONNECT_DELAY_MS);
    }

    /** Runs one main turn after READY proof commit and ATT success. */
    private void startCapturedIncomingAttachAfterReady(
            @NonNull IncomingReadyAttach captured) {
        GattServerPeer currentPeer = findExactCurrentServerPeer(captured.rawFacade);
        if (captured.sessionGeneration != sessionGeneration
                || captured.securityEpoch != incomingSecurityEpoch
                || captured.publicationToken
                != publishedDiagnosticServicePublicationToken
                || currentPeer != captured.serverPeer
                || currentPeer == null
                || !currentPeer.connected
                || currentPeer.device != captured.rawFacade
                || currentPeer.physicalLinkFacade != captured.physicalLinkFacade
                || !Arrays.equals(captured.pairChallenge,
                incomingPairAcceptedChallenge)
                || !canStartIncomingClientAttach(
                captured.rawFacade, captured.publicationToken)) {
            log("Post-READY attach task no-op: session/epoch/publication/raw facade "
                    + "сменились или link DISCONNECTED до следующего main turn");
            return;
        }
        activeIncomingFirstAttachAuthorization = captured;
        try {
            if (replaceStaleEstablishedOwnerAfterFreshReady(
                    currentPeer, captured.rawFacade, captured.publicationToken)) {
                return;
            }
            BluetoothGatt stale = gatt;
            if (stale != null && !ownsCurrentIncomingClientAttempt(stale)) {
                log("Post-READY закрывает stale client wrapper перед первым current-epoch attach");
                closeClientGatt(stale);
                clearAncsRuntime();
            }
            startIncomingDirectAttach("captured same-owner ANCS-READY", false);
        } finally {
            activeIncomingFirstAttachAuthorization = null;
        }
    }

    /**
     * Consumes a status=22 stale owner only after the exact still-connected F04 facade, or the next
     * physical F04 link if that facade disappears, has completed current B3 and ANCS-READY. A
     * status=22 delivered after a fresh server CONNECTED may leave the old wrapper quarantined but
     * physically open until this barrier; it is closed here before exactly one direct virtual open
     * is issued against the exact BluetoothDevice facade of the current server callback.
     */
    private boolean replaceStaleEstablishedOwnerAfterFreshReady(
            @NonNull GattServerPeer serverLink,
            @NonNull BluetoothDevice exactIncomingDevice,
            long publicationToken) {
        BluetoothGatt staleOwner = incomingStaleEstablishedOwner;
        boolean staleSlotIsExact = staleOwner != null
                && (gatt == null || gatt == staleOwner);
        boolean exactCurrentFacade = serverLink.securityEpoch == incomingSecurityEpoch
                && serverLink.sessionGeneration == sessionGeneration
                && serverLink.device == exactIncomingDevice
                && findCurrentServerPeer(exactIncomingDevice) == serverLink;
        boolean shouldReplace = AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                hasPendingStaleOwnerReplacementForCurrentEpoch(),
                incomingStaleOwnerReplacementEpoch,
                incomingSecurityEpoch,
                staleSlotIsExact,
                gattClientConnected,
                exactCurrentFacade,
                isCurrentDiagnosticServicePublicationToken(publicationToken)
                        && secureAttConfirmed
                        && secureAttPublicationToken == publicationToken,
                incomingAncsReadyGateOpen
                        && incomingAncsReadyPublicationToken == publicationToken);
        if (!shouldReplace) return false;
        boolean capturedFirstAuthorization = ownsCapturedFirstAttachAuthorization(
                exactIncomingDevice, publicationToken);
        boolean postBarrierReplacementAuthorization = incomingReadyAttachTask == null
                && incomingFirstAttachIssuedForCurrentTuple;
        if (!capturedFirstAuthorization && !postBarrierReplacementAuthorization) {
            log("Status=22 replacement waits: attempt #1 remains owned by the captured "
                    + "post-response READY task");
            return true;
        }

        // Consume first. Re-entrant/repeated READY and PAIR callbacks cannot create a second
        // wrapper even if connectGatt returns null or reports a synchronous failure.
        incomingFreshReplacementConsumedEpoch = incomingSecurityEpoch;
        incomingStaleOwnerAwaitingFreshEpoch = false;
        incomingStaleOwnerReplacementEpoch = 0L;
        incomingStaleEstablishedOwner = null;
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        if (gatt == staleOwner) {
            log("Captured post-READY barrier closes the drain-only status=22 owner");
            closeClientGatt(staleOwner);
        }
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        incomingClientAttachAttempt = 0;
        incomingClientCandidate = exactIncomingDevice;
        managedResolvedPeer = exactIncomingDevice;
        state("FRESH F04 READY · ONE DIRECT ATTACH");
        log("Status=22 recovery consumed once for securityEpoch="
                + incomingSecurityEpoch + " · exact current facade objectId="
                + System.identityHashCode(exactIncomingDevice));
        activeIncomingPostReadyReplacementAuthorization =
                postBarrierReplacementAuthorization;
        try {
            startIncomingDirectAttach(
                    "fresh F04 epoch + B3 + ANCS-READY after status=22", true);
        } finally {
            activeIncomingPostReadyReplacementAuthorization = false;
        }
        return true;
    }

    private void scheduleSecureClientStart() {
        if (secureConnectStart != null || clientConnectInFlight) return;
        if (AncsRecoveryPolicy.replacementConsumedForEpoch(
                incomingFreshReplacementConsumedEpoch, incomingSecurityEpoch)
                && gatt == null) {
            log("ANCS-READY duplicate не повторяет использованный status=22 direct attach");
            return;
        }
        if (managedIncomingMode && nextClientAttempt != null) {
            log("ANCS-READY gate открыт; bounded direct retry уже запланирован");
            return;
        }
        BluetoothGatt current = gatt;
        if (current != null) {
            if (managedIncomingMode) {
                if (gattClientConnected && activeClientEstablished) {
                    maybeStartIncomingAncsDiscovery(current,
                            "ANCS-READY after direct client attach");
                } else if (activeClientEstablished) {
                    awaitIncomingBackgroundOwner(current, activeClientGeneration,
                            "ANCS-READY on previously established owner");
                } else {
                    log("ANCS-READY gate открыт; жду callback первичного direct clientIf");
                }
            } else if (gattClientConnected) {
                discoverServices(current);
            } else {
                awaitIncomingBackgroundOwner(current, activeClientGeneration,
                        "ANCS-READY на уже зарегистрированном owner");
            }
            return;
        }
        secureConnectStart = () -> {
            secureConnectStart = null;
            // AOSP/ESP-IDF use a direct GATT virtual open when adopting an already-connected
            // incoming peer. autoConnect=true only registers for a future advertiser and may
            // never emit a callback while this server-owned ACL is already alive.
            startSamePeerAttach(managedIncomingMode ? false : true,
                    "same-owner ANCS-READY + "
                    + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms");
        };
        main.postDelayed(secureConnectStart, SECURE_TO_CLIENT_CONNECT_DELAY_MS);
        log("Same-peer ANCS client attach запланирован через "
                + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms после ANCS-READY");
    }

    private void maybeStartIncomingAncsDiscovery(@NonNull BluetoothGatt expected,
                                                  @NonNull String reason) {
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (!managedIncomingMode || expected != gatt || !secureAttConfirmed
                || !incomingAncsReadyGateOpen || !gattClientConnected
                || !activeClientEstablished
                || !isCurrentDiagnosticServicePublicationToken(publicationToken)
                || secureAttPublicationToken != publicationToken
                || incomingAncsReadyPublicationToken != publicationToken
                || !sessionState.isCurrent(activeClientGeneration)
                || activeClientProvenSecurityEpoch == 0L
                || activeClientProvenSecurityEpoch != incomingSecurityEpoch) return;
        if (incomingDiscoveryStarted) {
            log("ANCS discovery уже стартовал на текущем direct clientIf · " + reason);
            return;
        }
        // Current-link B3 + same RequiresANCS-owner proof + client liveness succeeded. The iOS
        // system permission may still be provisional; ANCS absence/CCCD denial is handled later
        // without discarding this physical owner.
        incomingDiscoveryStarted = true;
        state("DIRECT CLIENT ATTACHED + ANCS-READY · DISCOVERY");
        log("Оба независимых gate готовы: direct client attached + same-owner ANCS-READY · "
                + reason);
        discoverServices(expected);
    }

    private void startSamePeerAttach(boolean autoConnect, String reason) {
        if (!ensureAdapter()) return;
        if (managedIncomingMode) {
            if (autoConnect) {
                log("Reverse route отклоняет initial autoConnect=true: background open ждёт "
                        + "будущую рекламу и не adopts текущий server-owned ACL");
                return;
            }
            startIncomingDirectAttach(reason, false);
            return;
        }
        if (!secureAttConfirmed) {
            log("connectGatt не запущен: SECURE ATT ещё не подтверждён");
            return;
        }
        if (clientConnectInFlight || gattClientConnected || gatt != null) {
            log("connectGatt уже активен; новая попытка пропущена · " + reason);
            return;
        }
        BluetoothDevice verified = getVerifiedPeer();
        if (verified == null) {
            state("NO VERIFIED PEER");
            log("Same-peer attach отменён: verified peer отсутствует");
            return;
        }
        GattServerPeer serverLink = findConnectedServerPeer(verified);
        if (serverLink == null) {
            state("VERIFIED SERVER LINK LOST");
            log("Same-peer attach отменён: exact verified GATT-server link "
                    + safeAddress(verified) + " не активен");
            return;
        }
        // Do not resolve the address again through bonded-device aliases. Android 9 may return a
        // different BluetoothDevice wrapper for the same iPhone; connectGatt must use the exact
        // object delivered by this live GATT-server connection callback.
        BluetoothDevice device = serverLink.device;
        synchronized (verifiedPeerLock) {
            verifiedPeer = device;
        }
        if (autoConnect) {
            if (backgroundAttachAttempted) {
                log("Повтор autoConnect=true заблокирован");
                return;
            }
            backgroundAttachAttempted = true;
        } else {
            if (!backgroundAttachAttempted) {
                log("Direct fallback запрещён до единственной background attach-попытки");
                return;
            }
            if (directFallbackAttempted) {
                log("Повтор autoConnect=false заблокирован");
                return;
            }
            directFallbackAttempted = true;
        }
        clearAncsRuntime();
        gattClientConnected = false;
        activeClientTarget = device;
        activeClientAutoConnect = autoConnect;
        activeClientOpportunistic = false;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(autoConnect
                ? AncsSessionStateMachine.Phase.BACKGROUND_CONNECT
                : AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        String address = safeAddress(device);
        long linkAgeMs = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                - serverLink.connectedAtElapsedMs);
        state(autoConnect
                ? "SAME-PEER ATTACH · BACKGROUND"
                : "SAME-PEER ATTACH · DIRECT FALLBACK");
        log("connectGatt(autoConnect=" + autoConnect + ", TRANSPORT_LE): "
                + safeName(device) + " " + address + " · " + reason
                + " · bond=" + bondLabel(safeBondState(device))
                + " · type=" + typeLabel(safeType(device))
                + " · objectId=" + System.identityHashCode(device)
                + " · verifiedServerLinkAgeMs=" + linkAgeMs
                + " · EXACT SAME VERIFIED BluetoothDevice");
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                state("CONNECT_GATT_RETURNED_NULL");
                log("connectGatt вернул null");
                if (autoConnect) {
                    scheduleDirectFallback("connectGatt(autoConnect=true) returned null");
                } else {
                    state("V6 ATTEMPTS EXHAUSTED");
                }
            } else {
                BluetoothGatt expected = gatt;
                boolean expectedAutoConnect = autoConnect;
                connectTimeout = () -> {
                    if (gatt != expected || !clientConnectInFlight
                            || !sessionState.isCurrent(expectedGeneration)) return;
                    clientConnectInFlight = false;
                    state("CONNECT_TIMEOUT");
                    log("Нет callback успешного GATT-подключения за "
                            + CONNECT_TIMEOUT_MS + " ms · target="
                            + safeAddress(expected.getDevice())
                            + " autoConnect=" + expectedAutoConnect
                            + " transport=TRANSPORT_LE");
                    closeClientGatt(expected);
                    clearAncsRuntime();
                    if (expectedAutoConnect) {
                        scheduleDirectFallback("background attach timeout");
                    } else {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                };
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            }
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            state("CONNECT_EXCEPTION");
            log("connectGatt exception: " + failure);
            if (autoConnect) {
                scheduleDirectFallback("background attach exception");
            } else {
                state("V6 ATTEMPTS EXHAUSTED");
            }
        }
    }

    /**
     * Attempts an exact-live-link-gated, non-holding attach through Pie's opportunistic GATT
     * overload. The hidden call is made only after the checked READY response barrier and uses
     * {@code autoConnect=false, opportunistic=true}: AOSP otherwise takes the background path
     * before applying the opportunistic flag. The native TCB can still vanish between the Java
     * live-link check and queued GATT_Connect, so the one-call/no-fallback budget remains required.
     */
    private void startIncomingDirectAttach(@NonNull String reason,
                                           boolean oneShotFreshReplacement) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode) return;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)) {
            state("SAME-PEER DIRECT ATTACH · ЖДУ F04 SERVICE BARRIER");
            log("connectGatt отложен без расхода attempt: onServiceAdded(F04) ещё не SUCCESS");
            return;
        }
        BluetoothDevice pairRawFacade = incomingPairAcceptedFacade;
        if (!canStartIncomingClientAttach(pairRawFacade, publicationToken)) {
            state("REQUIRES_ANCS LINK · ЖДУ PAIR/B3/READY");
            log("connectGatt запрещён: exact current publication + PAIR + B3 + READY "
                    + "raw-facade tuple неполон · " + reason);
            return;
        }
        boolean firstAttachAuthorized = ownsCapturedFirstAttachAuthorization(
                pairRawFacade, publicationToken)
                || (oneShotFreshReplacement
                && activeIncomingPostReadyReplacementAuthorization);
        if (!AncsRecoveryPolicy.mayIssueReverseClientCommand(
                true,
                incomingReadyAttachTask != null,
                !incomingFirstAttachIssuedForCurrentTuple,
                firstAttachAuthorized)) {
            log("Attempt #1 rejected: only the captured post-response READY task owns the "
                    + "first clientIf allocation");
            return;
        }
        AncsRecoveryPolicy.ReverseClientOpenAction openAction =
                AncsRecoveryPolicy.reverseClientOpenAction(
                        true,
                        firstAttachAuthorized,
                        incomingOpportunisticAttachAttemptedForCurrentTuple);
        if (openAction != AncsRecoveryPolicy.ReverseClientOpenAction.OPPORTUNISTIC) {
            log("Post-READY opportunistic attach budget spent; no public/direct fallback · "
                    + reason);
            return;
        }
        if (clientConnectInFlight || gattClientConnected || gatt != null) {
            log("Incoming opportunistic clientIf уже активен; дубль пропущен · " + reason);
            return;
        }
        if (!oneShotFreshReplacement && hasPendingStaleOwnerReplacementForCurrentEpoch()) {
            log("Direct attach отложен: status=22 replacement ждёт fresh B3/ANCS-READY");
            return;
        }
        if (!oneShotFreshReplacement
                && AncsRecoveryPolicy.replacementConsumedForEpoch(
                incomingFreshReplacementConsumedEpoch, incomingSecurityEpoch)) {
            log("Direct attach пропущен: status=22 replacement уже consumed в epoch="
                    + incomingSecurityEpoch);
            return;
        }
        BluetoothDevice candidate = incomingClientCandidate;
        if (candidate == null || candidate != pairRawFacade
                || !isSelectedBondedIncomingDevice(candidate)) {
            state("REQUIRES_ANCS LINK · ЖДУ BONDED IDENTITY");
            log("Direct client attach отложен: exact bonded incoming facade отсутствует");
            return;
        }
        GattServerPeer serverLink = findExactCurrentServerPeer(candidate);
        if (serverLink == null || !serverLink.connected
                || serverLink.device != candidate) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "exact incoming link missing before direct client attach");
            return;
        }
        GattServerPeer capturedServerPeer = incomingReadyAttachLatchServerPeer;
        BluetoothDevice physicalTargetFacade =
                incomingReadyAttachLatchPhysicalFacade;
        byte[] pairChallenge = incomingReadyAttachLatchChallenge;
        if (capturedServerPeer == null || physicalTargetFacade == null
                || pairChallenge == null
                || !Arrays.equals(pairChallenge, incomingPairAcceptedChallenge)
                || capturedServerPeer != serverLink
                || serverLink.physicalLinkFacade != physicalTargetFacade
                || findExactPhysicalServerPeer(physicalTargetFacade) != serverLink) {
            state("OPPORTUNISTIC TARGET AMBIGUOUS · LINK KEPT");
            log("Physical target отсутствует/неоднозначен после READY; attempt budget "
                    + "не расходуется · pairObjectId="
                    + System.identityHashCode(candidate));
            return;
        }
        int attemptLimit = INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS;
        if (incomingClientAttachAttempt >= attemptLimit) {
            state("SAME-PEER OPPORTUNISTIC ATTACH · LINK KEPT · BUDGET SPENT");
            log("Post-READY opportunistic clientIf не attached после "
                    + incomingClientAttachAttempt
                    + " попытки; GATT server/reconnect anchor остаётся опубликован");
            return;
        }

        // The hidden call targets the immutable first server CONNECTED object. PAIR/B3/READY
        // remain owned by the separate exact bonded facade captured in this same stable record.
        BluetoothDevice pairFacade = serverLink.device;
        BluetoothDevice device = physicalTargetFacade;
        incomingClientCandidate = pairFacade;
        incomingFirstAttachIssuedForCurrentTuple = true;
        incomingOpportunisticAttachAttemptedForCurrentTuple = true;
        incomingClientAttachAttempt++;
        clearAncsRuntime();
        managedF05DatabaseGeneration++;
        if (managedF05DatabaseGeneration == 0L) managedF05DatabaseGeneration++;
        incomingDiscoveryStarted = false;
        gattClientConnected = false;
        activeClientProvenSecurityEpoch = 0L;
        activeClientTarget = device;
        activeClientAutoConnect = false;
        activeClientOpportunistic = true;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        incomingClientAttemptTransportFacade = device;
        incomingClientAttemptPairFacade = pairFacade;
        incomingClientAttemptServerPeer = serverLink;
        incomingClientAttemptSessionGeneration = sessionGeneration;
        incomingClientAttemptSecurityEpoch = incomingSecurityEpoch;
        incomingClientAttemptPublicationToken = publicationToken;
        incomingClientAttemptChallenge = pairChallenge.clone();
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        long linkAgeMs = Math.max(0L, SystemClock.elapsedRealtime()
                - serverLink.connectedAtElapsedMs);
        state("SAME-PEER OPPORTUNISTIC ATTACH #" + incomingClientAttachAttempt);
        log("Pie connectGatt(autoConnect=false, opportunistic=true, TRANSPORT_LE): "
                + safeName(device) + " " + safeAddress(device)
                + " · physicalObjectId=" + System.identityHashCode(device)
                + " · pairObjectId=" + System.identityHashCode(pairFacade)
                + " · sameAddress=" + sameDevice(device, pairFacade)
                + " · serverLinkAgeMs=" + linkAgeMs
                + " · postReady=true"
                + " · oneShotFreshReplacement=" + oneShotFreshReplacement
                + " · " + reason);
        BluetoothGatt created = connectGattOpportunisticOnPie(device);
        gatt = created;
        if (created == null) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            activeClientAutoConnect = false;
            activeClientOpportunistic = false;
            clearIncomingClientAttemptLineage();
            state("OPPORTUNISTIC GATT UNAVAILABLE · LINK KEPT");
            if (oneShotFreshReplacement) {
                incomingFreshReplacementGatt = null;
                state("FRESH STATUS=22 OPPORTUNISTIC ATTACH FAILED · ЖДУ НОВЫЙ LINK");
            }
            log("Hidden opportunistic clientIf не выделен; public/direct fallback запрещён, "
                    + "текущий inbound GATT server/link сохранён");
            return;
        }
        BluetoothGatt expected = created;
        if (oneShotFreshReplacement) incomingFreshReplacementGatt = expected;
        connectTimeout = () -> {
            if (!ownsCurrentIncomingClientAttempt(expected)
                    || !clientConnectInFlight
                    || activeClientEstablished
                    || !activeClientOpportunistic
                    || !sessionState.is(expectedGeneration,
                    AncsSessionStateMachine.Phase.DIRECT_CONNECT)) return;
            log("Opportunistic clientIf не дал callback за "
                    + INCOMING_DIRECT_ATTACH_TIMEOUT_MS
                    + " ms; unregister close-only exact never-established wrapper · target="
                    + safeAddress(expected.getDevice()));
            boolean exactFreshReplacement = oneShotFreshReplacement
                    && incomingFreshReplacementGatt == expected;
            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
            boolean serverFacadeLostWhilePending = attemptPeer != null
                    && attemptPeer == incomingPairAcceptedServerPeer
                    && !attemptPeer.connected;
            unregisterNeverEstablishedOpportunisticGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            if (exactFreshReplacement) incomingFreshReplacementGatt = null;
            if (serverFacadeLostWhilePending) {
                resetIncomingSecurityAfterClientLoss(expected.getDevice(),
                        "opportunistic timeout after accepted server facade loss");
                state("OPPORTUNISTIC TIMEOUT · PHYSICAL LINK LOST · ЖДУ НОВЫЙ LINK");
                log("Pending handoff не подтвердился; PAIR/B3/READY, bind bit и stale "
                        + "roleFacadeHandoffPending очищены без disconnect/GATT-server close");
                return;
            }
            state(exactFreshReplacement
                    ? "FRESH STATUS=22 OPPORTUNISTIC TIMEOUT · ЖДУ НОВЫЙ LINK"
                    : "OPPORTUNISTIC GATT TIMEOUT · LINK KEPT");
            log("Same-tuple retry/public fallback запрещены; physical inbound link и F04 "
                    + "publication не закрывались");
        };
        main.postDelayed(connectTimeout, INCOMING_DIRECT_ATTACH_TIMEOUT_MS);
    }

    /**
     * Android 9 light-greylist overload. Reflection keeps compilation on the public SDK; exact
     * Pie gating prevents accidental hidden-API use on later releases with different policy.
     */
    @Nullable
    private BluetoothGatt connectGattOpportunisticOnPie(@NonNull BluetoothDevice device) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) {
            log("Opportunistic connectGatt недоступен: требуется API 28, current="
                    + Build.VERSION.SDK_INT);
            return null;
        }
        try {
            Method method = BluetoothDevice.class.getMethod(
                    "connectGatt",
                    Context.class,
                    boolean.class,
                    BluetoothGattCallback.class,
                    int.class,
                    boolean.class,
                    int.class,
                    Handler.class);
            Object result = method.invoke(
                    device,
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    true,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    main);
            if (result == null) {
                log("Pie opportunistic connectGatt returned null");
                return null;
            }
            if (!(result instanceof BluetoothGatt)) {
                log("Pie opportunistic connectGatt returned unexpected type="
                        + result.getClass().getName());
                return null;
            }
            return (BluetoothGatt) result;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getTargetException();
            log("Pie opportunistic connectGatt target failure="
                    + (cause == null ? "unknown" : cause.getClass().getSimpleName()));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            log("Pie opportunistic connectGatt unavailable="
                    + failure.getClass().getSimpleName());
        }
        return null;
    }

    /** Close-only unregister; never calls disconnect and never touches the inbound GATT server. */
    private void unregisterNeverEstablishedOpportunisticGatt(
            @NonNull BluetoothGatt expected) {
        if (gatt != expected || activeClientEstablished || !activeClientOpportunistic
                || !ownsCurrentIncomingClientAttempt(expected)) return;
        closeClientGatt(expected);
    }

    private void scheduleDirectFallback(String reason) {
        if (!secureAttConfirmed || !backgroundAttachAttempted || directFallbackAttempted
                || nextClientAttempt != null) return;
        nextClientAttempt = () -> {
            nextClientAttempt = null;
            startSamePeerAttach(false, "единственный fallback после " + reason);
        };
        main.postDelayed(nextClientAttempt, DIRECT_FALLBACK_DELAY_MS);
        log("Единственный direct fallback autoConnect=false через "
                + DIRECT_FALLBACK_DELAY_MS + " ms · " + reason);
    }

    /**
     * Terminal compatibility sink for older recovery callers. HA1210 never allocates a second
     * wrapper for the same tuple; a fresh incoming link must create a fresh PAIR/B3/READY epoch.
     */
    private void scheduleIncomingClientAttachRetry(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode) return;
        state("OPPORTUNISTIC ATTACH · LINK KEPT · ЖДУ FRESH READY");
        log("Same-tuple clientIf retry запрещён; open budget="
                + incomingClientAttachAttempt + "/" + INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS
                + ", public/direct fallback отсутствует · " + reason);
    }

    /**
     * Recovers by phase: a never-established wrapper gets a bounded direct replacement, while an
     * owner that reached CONNECTED is retained and re-armed with {@link BluetoothGatt#connect()}.
     */
    private void recoverIncomingClientRole(@NonNull String reason) {
        if (closing || !managedIncomingMode) return;
        BluetoothGatt owner = gatt;
        if (owner != null && activeClientOpportunistic
                && (!gattClientConnected || !activeClientEstablished)) {
            retireOpportunisticObserverWithoutLinkMutation(owner,
                    "passive owner callback/link loss · " + reason);
            return;
        }
        if (owner != null && !canIssueManagedIncomingRearm(owner)) {
            log("Managed reverse recovery quarantined: retained wrapper cannot close/rearm "
                    + "before exact current PAIR+B3+READY attempt tuple · " + reason);
            return;
        }
        BluetoothDevice device = getVerifiedPeer();
        if (device == null || findCurrentServerPeer(device) == null) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "client recovery observed physical link loss · " + reason);
            return;
        }
        if (owner != null) {
            if (gattClientConnected && activeClientEstablished) {
                if (incomingAncsReadyGateOpen) {
                    restartDiscoveryOnPersistentOwner(owner, activeClientGeneration, reason);
                } else {
                    log("Established direct clientIf сохранён; recovery ждёт ANCS-READY · "
                            + reason);
                }
            } else if (activeClientEstablished) {
                awaitIncomingBackgroundOwner(owner, activeClientGeneration, reason);
            } else {
                BluetoothDevice failedDevice = owner.getDevice();
                closeClientGatt(owner);
                clearAncsRuntime();
                incomingDiscoveryStarted = false;
                if (findConnectedServerPeer(failedDevice) == null) {
                    resetIncomingSecurityAfterClientLoss(failedDevice,
                            "never-established recovery after server facade loss");
                    preserveManagedIncomingPublicationAfterLinkLoss(
                            "never-established recovery lost physical link");
                } else {
                    scheduleIncomingClientAttachRetry(
                            "never-established direct owner recovery · " + reason);
                }
            }
            return;
        }
        log("Android GATT clientIf отсутствует; создаю bounded direct owner · "
                + reason);
        scheduleIncomingClientAttachRetry(reason);
    }

    /** Keeps one incoming-route clientIf alive across status 133 and ordinary disconnects. */
    private void awaitIncomingBackgroundOwner(@NonNull BluetoothGatt expected,
                                              long expectedGeneration,
                                              @NonNull String reason) {
        if (closing || !managedIncomingMode || gatt != expected
                || !sessionState.isCurrent(expectedGeneration)) return;
        if (activeClientOpportunistic) {
            retireOpportunisticObserverWithoutLinkMutation(expected,
                    "passive owner cannot enter background rearm · " + reason);
            return;
        }
        if (!canIssueManagedIncomingRearm(expected)) {
            log("Managed reverse owner remains inert: rearm/close/retry/RSSI/discovery wait "
                    + "for exact current PAIR+B3+READY tuple · " + reason);
            return;
        }
        if (!activeClientEstablished) {
            log("gatt.connect() запрещён для never-established clientIf; "
                    + "закрываю только wrapper и планирую bounded direct retry · " + reason);
            BluetoothDevice failedDevice = expected.getDevice();
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            if (findConnectedServerPeer(failedDevice) == null) {
                resetIncomingSecurityAfterClientLoss(failedDevice,
                        "never-established rearm after server facade loss");
                preserveManagedIncomingPublicationAfterLinkLoss(
                        "never-established rearm lost physical link");
            } else {
                scheduleIncomingClientAttachRetry(reason);
            }
            return;
        }
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        boolean rawOwnerClosed = clearAncsRuntime();
        incomingDiscoveryStarted = false;
        if (rawOwnerClosed || gatt != expected) {
            recoverIncomingClientRole("ambiguous raw callback owner replaced · " + reason);
            return;
        }
        gattClientConnected = false;
        clientConnectInFlight = true;
        activeClientAutoConnect = true;
        sessionState.move(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        state("SAME-PEER BACKGROUND OWNER · RETAINED");
        log("Повторно вооружаю тот же Android GATT owner; close/connectGatt не вызываются · "
                + reason);
        rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
    }

    /**
     * A failed opportunistic observer is unregistered close-only and is never explicitly rearmed
     * by this app; the next iPhone-Central link establishes a fresh F04/PAIR/B3/READY epoch.
     */
    private boolean retireOpportunisticObserverWithoutLinkMutation(
            @NonNull BluetoothGatt expected, @NonNull String reason) {
        if (!managedIncomingMode || gatt != expected || !activeClientOpportunistic
                || !ownsCurrentIncomingClientAttempt(expected)) return false;
        BluetoothDevice device = expected.getDevice();
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        closeClientGatt(expected);
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        state("OPPORTUNISTIC OWNER RETIRED · LINK KEPT · BUDGET SPENT");
        log("Exact opportunistic wrapper unregistered close-only; inbound server peer, "
                + "PAIR/B3/READY proofs and F04 publication kept; disconnect/GATT-server close "
                + "не вызывались · peer=" + safeAddress(device) + " · " + reason);
        return true;
    }

    /**
     * Once the passive wrapper is close-only retired, a missing server facade means no role still
     * proves the physical link. Clear the logical epoch/bind transcript without issuing any
     * BluetoothGatt disconnect, reconnect or GATT-server close.
     */
    private boolean resetRetiredObserverAfterServerFacadeLoss(
            @Nullable GattServerPeer attemptPeer,
            @NonNull BluetoothDevice transportFacade,
            @NonNull String reason) {
        if (attemptPeer == null
                || attemptPeer != incomingPairAcceptedServerPeer
                || attemptPeer.connected) return false;
        resetIncomingSecurityAfterClientLoss(transportFacade, reason);
        log("Retired opportunistic observer had no CONNECTED server facade; "
                + "PAIR/B3/READY, bind bit and handoff-pending cleared close-only");
        return true;
    }

    private void cancelClientAttemptCallbacks() {
        if (secureConnectStart != null) main.removeCallbacks(secureConnectStart);
        if (nextClientAttempt != null) main.removeCallbacks(nextClientAttempt);
        secureConnectStart = null;
        nextClientAttempt = null;
    }

    private void cancelColdBackgroundAttach() {
        if (coldBackgroundAttachTask != null) {
            main.removeCallbacks(coldBackgroundAttachTask);
        }
        coldBackgroundAttachTask = null;
    }

    private GattServerPeer findConnectedServerPeer(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            return findUniqueServerPeerLocked(device, true, true);
        }
    }

    /** Current physical link, even if Android released only its server-role facade. */
    private GattServerPeer findCurrentServerPeer(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            return findUniqueServerPeerLocked(device, false, true);
        }
    }

    /**
     * Resolves a callback to one stable physical record. Exact physical/authorization object
     * identity wins; address equality is accepted only when it resolves to one unique record.
     */
    @Nullable
    private GattServerPeer findUniqueServerPeerLocked(
            @Nullable BluetoothDevice callbackFacade,
            boolean requireConnected, boolean requireCurrentEpoch) {
        if (callbackFacade == null) return null;
        GattServerPeer exact = null;
        for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
            if (peer.sessionGeneration != sessionGeneration
                    || (requireCurrentEpoch
                    && peer.securityEpoch != incomingSecurityEpoch)
                    || (requireConnected ? !peer.connected
                    : (!peer.connected && !peer.roleFacadeHandoff
                    && !peer.roleFacadeHandoffPending))) continue;
            if (peer.physicalLinkFacade != callbackFacade
                    && peer.device != callbackFacade) continue;
            if (exact != null && exact != peer) return null;
            exact = peer;
        }
        if (exact != null) return exact;

        GattServerPeer addressMatch = null;
        for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
            if (peer.sessionGeneration != sessionGeneration
                    || (requireCurrentEpoch
                    && peer.securityEpoch != incomingSecurityEpoch)
                    || (requireConnected ? !peer.connected
                    : (!peer.connected && !peer.roleFacadeHandoff
                    && !peer.roleFacadeHandoffPending))) continue;
            if (!sameDevice(peer.physicalLinkFacade, callbackFacade)
                    && !sameDevice(peer.device, callbackFacade)) continue;
            if (addressMatch != null && addressMatch != peer) return null;
            addressMatch = peer;
        }
        return addressMatch;
    }

    /** Identity-strict lookup used only for the raw facade authorized by current PAIR. */
    @Nullable
    private GattServerPeer findExactCurrentServerPeer(@Nullable BluetoothDevice rawFacade) {
        if (rawFacade == null) return null;
        synchronized (gattServerPeers) {
            GattServerPeer match = null;
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.securityEpoch == incomingSecurityEpoch
                        && peer.device == rawFacade
                        && (peer.connected || peer.roleFacadeHandoff
                        || peer.roleFacadeHandoffPending)) {
                    if (match != null && match != peer) return null;
                    match = peer;
                }
            }
            return match;
        }
    }

    @Nullable
    private GattServerPeer findExactPhysicalServerPeer(
            @Nullable BluetoothDevice physicalFacade) {
        if (physicalFacade == null) return null;
        synchronized (gattServerPeers) {
            GattServerPeer match = null;
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.securityEpoch == incomingSecurityEpoch
                        && peer.physicalLinkFacade == physicalFacade
                        && (peer.connected || peer.roleFacadeHandoff
                        || peer.roleFacadeHandoffPending)) {
                    if (match != null && match != peer) return null;
                    match = peer;
                }
            }
            return match;
        }
    }

    private void clearIncomingPairProof() {
        clearIncomingReadyAttachLatch();
        incomingFirstAttachIssuedForCurrentTuple = false;
        incomingOpportunisticAttachAttemptedForCurrentTuple = false;
        incomingPairAcceptedFacade = null;
        incomingPairAcceptedServerPeer = null;
        incomingPairAcceptedSessionGeneration = 0L;
        incomingPairAcceptedSecurityEpoch = 0L;
        incomingPairAcceptedPublicationToken = 0L;
        incomingPairAcceptedChallenge = null;
    }

    private void clearIncomingReadyAttachLatch() {
        Runnable task = incomingReadyAttachTask;
        if (task != null) main.removeCallbacks(task);
        incomingReadyAttachTask = null;
        activeIncomingFirstAttachAuthorization = null;
        activeIncomingPostReadyReplacementAuthorization = false;
        incomingReadyAttachLatchFacade = null;
        incomingReadyAttachLatchPhysicalFacade = null;
        incomingReadyAttachLatchServerPeer = null;
        incomingReadyAttachLatchSessionGeneration = 0L;
        incomingReadyAttachLatchSecurityEpoch = 0L;
        incomingReadyAttachLatchPublicationToken = 0L;
        incomingReadyAttachLatchChallenge = null;
    }

    private boolean hasCurrentIncomingReadyAttachLatch(
            @NonNull BluetoothDevice rawFacade, long publicationToken) {
        GattServerPeer serverPeer = incomingReadyAttachLatchServerPeer;
        return incomingReadyAttachLatchFacade == rawFacade
                && incomingReadyAttachLatchPhysicalFacade != null
                && incomingReadyAttachLatchChallenge != null
                && Arrays.equals(incomingReadyAttachLatchChallenge,
                incomingPairAcceptedChallenge)
                && serverPeer != null
                && serverPeer == incomingPairAcceptedServerPeer
                && serverPeer.device == rawFacade
                && serverPeer.physicalLinkFacade
                == incomingReadyAttachLatchPhysicalFacade
                && incomingReadyAttachLatchSessionGeneration == sessionGeneration
                && incomingReadyAttachLatchSecurityEpoch == incomingSecurityEpoch
                && incomingReadyAttachLatchPublicationToken == publicationToken;
    }

    /** Returns false for an idempotent duplicate READY of the already armed/consumed tuple. */
    private boolean armIncomingReadyAttachLatch(
            @NonNull GattServerPeer serverPeer,
            @NonNull BluetoothDevice physicalLinkFacade,
            @NonNull BluetoothDevice rawFacade, @NonNull byte[] pairChallenge,
            long publicationToken) {
        if (hasCurrentIncomingReadyAttachLatch(rawFacade, publicationToken)) return false;
        clearIncomingReadyAttachLatch();
        incomingReadyAttachLatchServerPeer = serverPeer;
        incomingReadyAttachLatchPhysicalFacade = physicalLinkFacade;
        incomingReadyAttachLatchFacade = rawFacade;
        incomingReadyAttachLatchSessionGeneration = sessionGeneration;
        incomingReadyAttachLatchSecurityEpoch = incomingSecurityEpoch;
        incomingReadyAttachLatchPublicationToken = publicationToken;
        incomingReadyAttachLatchChallenge = pairChallenge.clone();
        return true;
    }

    private void clearIncomingClientAttemptLineage() {
        incomingClientAttemptTransportFacade = null;
        incomingClientAttemptPairFacade = null;
        incomingClientAttemptServerPeer = null;
        incomingClientAttemptSessionGeneration = 0L;
        incomingClientAttemptSecurityEpoch = 0L;
        incomingClientAttemptPublicationToken = 0L;
        incomingClientAttemptChallenge = null;
        managedF05DatabaseGeneration = 0L;
    }

    /** Address equality never transfers the raw PAIR facade to a different framework wrapper. */
    private void invalidateIncomingTupleForRawFacadeChange(
            @NonNull BluetoothDevice observedFacade, @NonNull String reason) {
        BluetoothDevice pairFacade = incomingPairAcceptedFacade;
        if (pairFacade == null || pairFacade == observedFacade
                || !sameDevice(pairFacade, observedFacade)
                || incomingPairAcceptedSessionGeneration != sessionGeneration
                || incomingPairAcceptedSecurityEpoch != incomingSecurityEpoch) return;
        cancelClientAttemptCallbacks();
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        clearAncsRuntime();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        secureAttPublicationToken = 0L;
        incomingAncsReadyGateOpen = false;
        incomingAncsReadyPublicationToken = 0L;
        incomingClientCandidate = null;
        incomingClientAttachAttempt = 0;
        activeClientProvenSecurityEpoch = 0L;
        incomingDiscoveryStarted = false;
        log("Exact PAIR raw facade invalidated; address-equal wrapper cannot inherit tuple · "
                + reason + " · pairObjectId=" + System.identityHashCode(pairFacade)
                + " observedObjectId=" + System.identityHashCode(observedFacade));
    }

    private boolean hasCurrentIncomingPairProof(@Nullable BluetoothDevice callbackDevice,
                                                long publicationToken) {
        BluetoothDevice rawFacade = incomingPairAcceptedFacade;
        GattServerPeer acceptedPeer = incomingPairAcceptedServerPeer;
        GattServerPeer callbackPeer = callbackDevice == null
                ? null : findCurrentServerPeer(callbackDevice);
        return rawFacade != null
                && (!managedIncomingMode || incomingPairAcceptedChallenge != null)
                && callbackDevice != null
                && AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                managedIncomingMode,
                isSelectedBondedIncomingDevice(callbackDevice),
                acceptedPeer != null,
                callbackPeer == acceptedPeer,
                acceptedPeer != null
                        && acceptedPeer.device == rawFacade
                        && findExactCurrentServerPeer(rawFacade) == acceptedPeer,
                sessionGeneration,
                incomingPairAcceptedSessionGeneration,
                incomingSecurityEpoch,
                incomingPairAcceptedSecurityEpoch,
                publicationToken,
                incomingPairAcceptedPublicationToken,
                isCurrentDiagnosticServicePublicationToken(publicationToken));
    }

    private boolean canStartIncomingClientAttach(@Nullable BluetoothDevice rawFacade,
                                                  long publicationToken) {
        if (managedIncomingMode && incomingPairAcceptedChallenge == null) return false;
        if (incomingReadyAttachTask != null) return false;
        GattServerPeer serverPeer = findExactCurrentServerPeer(rawFacade);
        return AncsRecoveryPolicy.canStartReverseClientAttach(
                managedIncomingMode,
                isCurrentDiagnosticServicePublicationToken(publicationToken),
                sessionGeneration,
                incomingPairAcceptedSessionGeneration,
                incomingSecurityEpoch,
                incomingPairAcceptedSecurityEpoch,
                publicationToken,
                incomingPairAcceptedPublicationToken,
                rawFacade != null && rawFacade == incomingPairAcceptedFacade,
                serverPeer != null && serverPeer.connected,
                secureAttConfirmed,
                secureAttPublicationToken,
                incomingAncsReadyGateOpen,
                incomingAncsReadyPublicationToken);
    }

    /** Proof-only gate for post-READY recovery after the server facade itself was released. */
    private boolean hasCurrentIncomingPostReadyTranscript(
            @Nullable BluetoothDevice rawFacade, long publicationToken) {
        return incomingReadyAttachTask == null
                && hasCurrentIncomingPairProof(rawFacade, publicationToken)
                && secureAttConfirmed
                && secureAttPublicationToken == publicationToken
                && incomingAncsReadyGateOpen
                && incomingAncsReadyPublicationToken == publicationToken;
    }

    private boolean ownsCurrentIncomingClientAttempt(@NonNull BluetoothGatt callbackGatt) {
        if (incomingReadyAttachTask != null) return false;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        BluetoothDevice callbackDevice = callbackGatt.getDevice();
        BluetoothDevice transportFacade = incomingClientAttemptTransportFacade;
        BluetoothDevice pairFacade = incomingClientAttemptPairFacade;
        GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
        boolean exactAttemptOwner = transportFacade != null
                && pairFacade != null
                && attemptPeer != null
                && incomingClientAttemptChallenge != null
                && Arrays.equals(incomingClientAttemptChallenge,
                incomingPairAcceptedChallenge)
                && callbackDevice == transportFacade
                && attemptPeer == incomingPairAcceptedServerPeer
                && attemptPeer.physicalLinkFacade == transportFacade
                && attemptPeer.device == pairFacade
                && findExactPhysicalServerPeer(transportFacade) == attemptPeer
                && findExactCurrentServerPeer(pairFacade) == attemptPeer;
        boolean currentPairProof = exactAttemptOwner
                && pairFacade == incomingPairAcceptedFacade
                && incomingPairAcceptedSessionGeneration == sessionGeneration
                && incomingPairAcceptedSecurityEpoch == incomingSecurityEpoch
                && incomingPairAcceptedPublicationToken == publicationToken;
        boolean currentSecureProof = secureAttConfirmed
                && secureAttPublicationToken == publicationToken;
        boolean currentReadyProof = incomingAncsReadyGateOpen
                && incomingAncsReadyPublicationToken == publicationToken;
        return AncsRecoveryPolicy.acceptsReverseClientCallback(
                callbackGatt == gatt,
                exactAttemptOwner,
                sessionGeneration,
                incomingClientAttemptSessionGeneration,
                incomingSecurityEpoch,
                incomingClientAttemptSecurityEpoch,
                publicationToken,
                incomingClientAttemptPublicationToken,
                isCurrentDiagnosticServicePublicationToken(publicationToken),
                currentPairProof,
                currentSecureProof,
                currentReadyProof);
    }

    private boolean ownsManagedAttemptLineage(
            @NonNull BluetoothGatt owner, @Nullable byte[] challenge,
            @Nullable GattServerPeer serverPeer,
            @Nullable BluetoothDevice physicalFacade,
            @Nullable BluetoothDevice pairFacade,
            long capturedSessionGeneration, long capturedSecurityEpoch,
            long capturedPublicationToken, long capturedClientGeneration) {
        return ownsCurrentIncomingClientAttempt(owner)
                && challenge != null
                && Arrays.equals(challenge, incomingClientAttemptChallenge)
                && serverPeer != null
                && serverPeer == incomingClientAttemptServerPeer
                && physicalFacade != null
                && physicalFacade == incomingClientAttemptTransportFacade
                && pairFacade != null
                && pairFacade == incomingClientAttemptPairFacade
                && capturedSessionGeneration == sessionGeneration
                && capturedSessionGeneration == incomingClientAttemptSessionGeneration
                && capturedSecurityEpoch == incomingSecurityEpoch
                && capturedSecurityEpoch == incomingClientAttemptSecurityEpoch
                && capturedPublicationToken == publishedDiagnosticServicePublicationToken
                && capturedPublicationToken == incomingClientAttemptPublicationToken
                && capturedClientGeneration == activeClientGeneration
                && sessionState.isCurrent(capturedClientGeneration);
    }

    /**
     * Shared post-READY barrier for every managed reverse client command, including closing a
     * stale wrapper. A callback from an old wrapper is not authority to cross the barrier.
     */
    private boolean canIssueManagedIncomingTupleCommand() {
        if (!managedIncomingMode) return true;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        BluetoothDevice rawFacade = incomingPairAcceptedFacade;
        boolean capturedFirstAuthorization = rawFacade != null
                && ownsCapturedFirstAttachAuthorization(rawFacade, publicationToken);
        return AncsRecoveryPolicy.mayIssueReverseClientCommand(
                rawFacade != null
                        && canStartIncomingClientAttach(rawFacade, publicationToken),
                incomingReadyAttachTask != null,
                !incomingFirstAttachIssuedForCurrentTuple,
                capturedFirstAuthorization);
    }

    /** The only gate allowed to reach BluetoothGatt.connect() in the managed reverse route. */
    private boolean canIssueManagedIncomingRearm(@NonNull BluetoothGatt expected) {
        return !managedIncomingMode
                || (ownsCurrentIncomingClientAttempt(expected)
                && canIssueManagedIncomingTupleCommand());
    }

    private boolean ownsCapturedFirstAttachAuthorization(
            @NonNull BluetoothDevice rawFacade, long publicationToken) {
        IncomingReadyAttach captured = activeIncomingFirstAttachAuthorization;
        return captured != null
                && captured.rawFacade == rawFacade
                && captured.serverPeer == incomingReadyAttachLatchServerPeer
                && captured.serverPeer == incomingPairAcceptedServerPeer
                && captured.physicalLinkFacade
                == incomingReadyAttachLatchPhysicalFacade
                && captured.serverPeer.device == rawFacade
                && captured.serverPeer.physicalLinkFacade
                == captured.physicalLinkFacade
                && Arrays.equals(captured.pairChallenge,
                incomingPairAcceptedChallenge)
                && captured.sessionGeneration == sessionGeneration
                && captured.securityEpoch == incomingSecurityEpoch
                && captured.publicationToken == publicationToken
                && hasCurrentIncomingReadyAttachLatch(rawFacade, publicationToken);
    }

    /** Stale retained-wrapper callbacks are drain-only and cannot mutate a fresh reverse epoch. */
    private boolean acceptsCurrentManagedIncomingCallback(
            @NonNull BluetoothGatt callbackGatt, @NonNull String operation) {
        if (!managedIncomingMode || ownsCurrentIncomingClientAttempt(callbackGatt)) return true;
        log("Stale managed reverse " + operation
                + " callback quarantined/no-op: attempt S/E/P/raw tuple is not current");
        return false;
    }

    /**
     * A durable wrapper from the preceding epoch may remain only as a quarantined handoff slot.
     * Its unversioned callbacks are ignored until captured READY closes/replaces it; they never
     * prove CONNECTED, reset retries, issue RSSI, or mutate the current tuple.
     */
    private boolean isQuarantinedRetainedEstablishedOwner(
            @NonNull BluetoothGatt callbackGatt) {
        return managedIncomingMode
                && callbackGatt == gatt
                && activeClientEstablished;
    }

    /**
     * Records the Android-9 callback inversion where a fresh server-role CONNECTED arrives just
     * before status=22 retires the preceding client role. This is deliberately drain-only: no
     * close, connect, retry, RSSI or discovery command may precede current PAIR+B3+READY.
     */
    private void latchQuarantinedRetainedStatus22(@NonNull BluetoothGatt expected) {
        BluetoothDevice device = expected.getDevice();
        prepareInFlightLinkProbeForFreshEpoch();
        GattServerPeer exactConnectedFacade = findConnectedServerPeer(device);
        boolean currentFacadeAlreadyArrived =
                AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                        exactConnectedFacade != null,
                        exactConnectedFacade != null
                                && isSelectedBondedIncomingDevice(
                                exactConnectedFacade.device),
                        exactConnectedFacade != null
                                && exactConnectedFacade.securityEpoch
                                == incomingSecurityEpoch,
                        isCurrentDiagnosticServicePublicationToken(
                                publishedDiagnosticServicePublicationToken));
        incomingStaleEstablishedOwner = expected;
        incomingStaleOwnerAwaitingFreshEpoch = true;
        incomingStaleOwnerReplacementEpoch = currentFacadeAlreadyArrived
                ? incomingSecurityEpoch : 0L;
        incomingFreshReplacementGatt = null;
        clientConnectInFlight = false;
        gattClientConnected = false;
        state("STATUS=22 · QUARANTINED UNTIL FRESH B3 + READY");
        log("Fresh-server/client status=22 inversion latched without client command; "
                + (currentFacadeAlreadyArrived
                ? "exact current F04 epoch will consume one captured post-READY replacement"
                : "next exact F04 epoch will own replacement"));
    }

    /** Coalesces the one Android-9 anonymous alias without letting it remain a ghost owner. */
    private int retireCurrentAnonymousAliasesExcept(@NonNull BluetoothDevice exactFacade) {
        int retired = 0;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.securityEpoch != incomingSecurityEpoch
                        || !peer.connected
                        || peer.device == exactFacade
                        || safeBondState(peer.device) != BluetoothDevice.BOND_NONE) continue;
                peer.connected = false;
                peer.roleFacadeHandoff = false;
                peer.roleFacadeHandoffPending = false;
                peer.linkSecurityChallengeIssued = false;
                peer.telemetrySubscribed = false;
                retired++;
            }
        }
        return retired;
    }

    /**
     * ECARX Android 9 can report the incoming link first as one anonymous BOND_NONE facade and
     * later deliver the exact bonded facade only with the current B2 PAIR request. The request can
     * coalesce those two framework objects because its characteristic already proved the exact
     * current F04 publication. No B3 or READY proof is granted here.
     */
    private AncsRecoveryPolicy.PairFacadeBindDecision bindExactPairRequestFacadeIfSafe(
            @NonNull BluetoothDevice device, long publicationToken) {
        BluetoothDevice claimed = getVerifiedPeer();
        boolean conflictingVerifiedPeer = claimed != null && !sameDevice(claimed, device);
        GattServerPeer soleAnonymousAlias = null;
        GattServerPeer matchingCurrentPeer = null;
        GattServerPeer redundantMatchingPeer = null;
        int anonymousAliasCount = 0;
        int currentServerRecordCount = 0;
        int matchingCurrentPeerCount = 0;
        boolean conflictingCurrentPeer = false;
        boolean currentPeerPresent = false;
        boolean exactCurrentRawFacade = false;
        AncsRecoveryPolicy.PhysicalFacadeTopologyDecision topologyDecision;
        AncsRecoveryPolicy.PairFacadeBindDecision decision;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.securityEpoch != incomingSecurityEpoch
                        || (!peer.connected && !peer.roleFacadeHandoff
                        && !peer.roleFacadeHandoffPending)) continue;
                currentServerRecordCount++;
                boolean matchesAuthorization = sameDevice(peer.device, device);
                boolean matchesPhysical = sameDevice(
                        peer.physicalLinkFacade, device);
                if (matchesAuthorization || matchesPhysical) {
                    currentPeerPresent = true;
                    matchingCurrentPeerCount++;
                    if (matchingCurrentPeer != null && matchingCurrentPeer != peer) {
                        redundantMatchingPeer = peer;
                    } else {
                        matchingCurrentPeer = peer;
                    }
                    if (peer.device == device) exactCurrentRawFacade = true;
                }
                if (peer.connected
                        && safeBondState(peer.device) == BluetoothDevice.BOND_NONE) {
                    anonymousAliasCount++;
                    soleAnonymousAlias = peer;
                } else if (!matchesAuthorization && !matchesPhysical) {
                    conflictingCurrentPeer = true;
                }
            }
            if (currentServerRecordCount == 2
                    && soleAnonymousAlias != null
                    && matchingCurrentPeer != null
                    && soleAnonymousAlias != matchingCurrentPeer
                    && (matchingCurrentPeer.connectedAtElapsedMs
                    - soleAnonymousAlias.connectedAtElapsedMs < 0L
                    || matchingCurrentPeer.connectedAtElapsedMs
                    - soleAnonymousAlias.connectedAtElapsedMs
                    > ANONYMOUS_BONDED_ALIAS_MAX_DELTA_MS)) {
                conflictingCurrentPeer = true;
            }
            topologyDecision = AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                    currentServerRecordCount, anonymousAliasCount,
                    matchingCurrentPeerCount, conflictingCurrentPeer);
            decision = topologyDecision
                    == AncsRecoveryPolicy.PhysicalFacadeTopologyDecision.REJECT
                    ? AncsRecoveryPolicy.PairFacadeBindDecision
                    .REJECT_ANONYMOUS_ALIAS_COUNT
                    : AncsRecoveryPolicy.pairFacadeBindDecision(
                    currentPeerPresent,
                    exactCurrentRawFacade,
                    managedIncomingMode,
                    isCurrentDiagnosticServicePublicationToken(publicationToken),
                    isSelectedBondedIncomingDevice(device),
                    conflictingVerifiedPeer,
                    conflictingCurrentPeer,
                    incomingPairRequestFacadeBoundEpoch == incomingSecurityEpoch,
                    anonymousAliasCount);
        }
        boolean freshRequestBind = AncsRecoveryPolicy.beginsFreshSecurityEpoch(decision);
        if (freshRequestBind) {
            GattServerPeer peer = soleAnonymousAlias != null
                    ? soleAnonymousAlias : matchingCurrentPeer;
            boolean pairRequestIsFirstCurrentAttEvidence =
                    topologyDecision == AncsRecoveryPolicy
                            .PhysicalFacadeTopologyDecision.CREATE_FROM_PAIR_ATT;
            if (peer == null && pairRequestIsFirstCurrentAttEvidence) {
                peer = new GattServerPeer(sessionGeneration, device);
                peer.connectedAtElapsedMs = SystemClock.elapsedRealtime();
            }
            if (peer == null || currentServerRecordCount > 2
                    || (!pairRequestIsFirstCurrentAttEvidence && !peer.connected)) {
                log("PAIR facade coalesce fail-closed: physical server record count="
                        + currentServerRecordCount);
                return AncsRecoveryPolicy.PairFacadeBindDecision
                        .REJECT_ANONYMOUS_ALIAS_COUNT;
            }
            // Capture A before beginFresh... clears the authorization transcript. The immutable
            // target is carried only by this one unambiguous server record.
            BluetoothDevice physicalLinkFacade = peer.physicalLinkFacade;
            long connectedAtElapsedMs = peer.connectedAtElapsedMs;
            // Synchronous by contract: ATT success must not reach Helper before stale B3/READY
            // proofs and the old one-shot challenge are invalidated for this request-owned epoch.
            beginFreshIncomingSecurityEpoch(device,
                    "exact current-F04 PAIR request facade recovery", false);
            synchronized (gattServerPeers) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.physicalLinkFacade != physicalLinkFacade) {
                    log("PAIR facade coalesce aborted: captured physical record changed");
                    return AncsRecoveryPolicy.PairFacadeBindDecision
                            .REJECT_ANONYMOUS_ALIAS_COUNT;
                }
                GattServerPeer redundantPeer = soleAnonymousAlias != null
                        && matchingCurrentPeer != null
                        && matchingCurrentPeer != peer
                        ? matchingCurrentPeer : redundantMatchingPeer;
                if (redundantPeer != null) {
                    Iterator<Map.Entry<String, GattServerPeer>> iterator =
                            gattServerPeers.entrySet().iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().getValue() == redundantPeer) {
                            iterator.remove();
                        }
                    }
                    redundantPeer.connected = false;
                    redundantPeer.roleFacadeHandoff = false;
                    redundantPeer.roleFacadeHandoffPending = false;
                    redundantPeer.linkSecurityChallengeIssued = false;
                    redundantPeer.telemetrySubscribed = false;
                }
                if (pairRequestIsFirstCurrentAttEvidence) {
                    gattServerPeers.put(deviceKey(device), peer);
                }
                peer.device = device;
                peer.connectedAtElapsedMs = connectedAtElapsedMs;
                peer.securityEpoch = incomingSecurityEpoch;
                peer.connected = true;
                peer.roleFacadeHandoff = false;
                peer.roleFacadeHandoffPending = false;
                peer.linkSecurityChallengeIssued = false;
                peer.telemetrySubscribed = false;
                incomingPairRequestFacadeBoundEpoch = incomingSecurityEpoch;
            }
            log("HA1211 PAIR facade coalesced onto immutable physical link · physicalObjectId="
                    + System.identityHashCode(physicalLinkFacade)
                    + " pairObjectId=" + System.identityHashCode(device)
                    + " sameAddress=" + sameDevice(physicalLinkFacade, device)
                    + " · A/B heuristic deltaMs="
                    + (matchingCurrentPeer == null || soleAnonymousAlias == null
                    ? 0L : matchingCurrentPeer.connectedAtElapsedMs
                    - soleAnonymousAlias.connectedAtElapsedMs));
        } else if (decision == AncsRecoveryPolicy.PairFacadeBindDecision.ALREADY_CURRENT
                && matchingCurrentPeer != null && matchingCurrentPeer.device == device) {
            retireCurrentAnonymousAliasesExcept(device);
        }
        return decision;
    }

    /**
     * Runs the complete PAIR admission transaction on the main-owned transport state. The ATT
     * response is sent from this transaction only after a missing request facade has opened a
     * fresh epoch and synchronously cleared every old B3/READY proof.
     */
    private void handlePairWriteRequestOnMain(
            @NonNull BluetoothDevice device, int requestId,
            @Nullable BluetoothGattCharacteristic characteristic,
            boolean preparedWrite, boolean responseNeeded, int offset,
            @Nullable byte[] value) {
        int status = BluetoothGatt.GATT_SUCCESS;
        String rejection = null;
        Boolean firstPairProof = null;
        long publicationToken = currentDiagnosticServicePublicationToken(characteristic);
        String command = asciiCommand(value);
        byte[] managedPairChallenge = managedIncomingMode
                ? managedProofChallenge(value, MANAGED_PAIR_OPCODE) : null;
        if (preparedWrite) {
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            rejection = "prepared PAIR write is unsupported";
        } else if (offset != 0) {
            status = BluetoothGatt.GATT_INVALID_OFFSET;
            rejection = "PAIR offset=" + offset;
        } else if (!responseNeeded) {
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            rejection = "PAIR requires a checked ATT write response";
        } else if (managedIncomingMode && managedPairChallenge == null) {
            status = BluetoothGatt.GATT_FAILURE;
            rejection = "managed CONTROL requires exact 17-byte binary P/Q";
        } else if (!managedIncomingMode && !"PAIR".equals(command)) {
            status = BluetoothGatt.GATT_FAILURE;
            rejection = "CONTROL command is not ASCII PAIR · command=`" + command + "`";
        } else if (publicationToken == 0L) {
            status = STATUS_INSUFFICIENT_AUTHORIZATION;
            rejection = "stale/pending F04 publication token";
        } else {
            // The explicit LightBlue diagnostic route already has its exact current callback
            // facade and needs no selected-phone alias reconciliation. Managed recovery always
            // passes through the full pure-policy guard set below.
            AncsRecoveryPolicy.PairFacadeBindDecision facadeDecision =
                    !managedIncomingMode && findCurrentServerPeer(device) != null
                    ? AncsRecoveryPolicy.PairFacadeBindDecision.ALREADY_CURRENT
                    : managedIncomingMode
                    && hasCurrentIncomingPairProof(device, publicationToken)
                    ? AncsRecoveryPolicy.PairFacadeBindDecision.ALREADY_CURRENT
                    : bindExactPairRequestFacadeIfSafe(device, publicationToken);
            if (facadeDecision
                    != AncsRecoveryPolicy.PairFacadeBindDecision.ALREADY_CURRENT
                    && facadeDecision
                    != AncsRecoveryPolicy.PairFacadeBindDecision
                    .BIND_EXACT_REQUEST_FRESH_EPOCH
                    && facadeDecision
                    != AncsRecoveryPolicy.PairFacadeBindDecision
                    .BIND_SOLE_ANONYMOUS_ALIAS) {
                status = STATUS_INSUFFICIENT_AUTHORIZATION;
                rejection = "current peer reconciliation=" + facadeDecision.name();
            } else if (!claimVerifiedPeer(device)) {
                status = STATUS_INSUFFICIENT_AUTHORIZATION;
                rejection = "verified peer conflict after current-facade proof";
            }
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // Keep the ATT-critical section minimal: commit only the exact tuple. Candidate/UI,
            // logs and bonding work run after the checked response below.
            firstPairProof = commitPairCommand(
                    device, publicationToken, managedPairChallenge);
            if (firstPairProof == null) {
                status = STATUS_INSUFFICIENT_AUTHORIZATION;
                rejection = "exact raw PAIR tuple vanished before commit";
            }
        }
        boolean responseSent = responseNeeded
                && sendGattServerResponse(device, requestId, status, 0, null);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("PAIR ATT REJECT POST-RESPONSE · reason=" + rejection
                    + " · status=" + status
                    + " · responseSent=" + responseSent
                    + " · peer=" + safeAddress(device)
                    + " · publicationToken=" + publicationToken
                    + " · epoch=" + incomingSecurityEpoch);
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS && !responseSent) {
            if (Boolean.TRUE.equals(firstPairProof)) {
                rollbackNonIdempotentPairTranscript();
                log("PAIR ATT response не отправлен; полный PAIR/B3/READY transcript и "
                        + "delayed attach latch откатаны, PAIR остаётся retryable");
            } else {
                log("Duplicate PAIR response failed; уже принятый exact transcript сохранён");
            }
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS && firstPairProof != null) {
            finishPairCommand(device, publicationToken, firstPairProof.booleanValue());
        }
    }

    /** Full B3 READ admission/commit/response transaction on the server callback FIFO. */
    private void handleSecureReadRequestOnMain(
            @NonNull BluetoothDevice device, int requestId, int offset,
            @Nullable BluetoothGattCharacteristic characteristic) {
        long publicationToken = currentDiagnosticServicePublicationToken(characteristic);
        if (publicationToken == 0L) {
            sendGattServerResponse(device, requestId,
                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
            log("SECURE READ отклонён: stale/pending F04 publication token · "
                    + safeAddress(device));
            return;
        }
        if (!isVerifiedPeer(device)
                || (managedIncomingMode
                && !hasCurrentIncomingPairProof(device, publicationToken))) {
            sendGattServerResponse(device, requestId,
                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
            log("SECURE READ отклонён: current exact PAIR tuple отсутствует · "
                    + safeAddress(device));
            return;
        }
        if (issueCurrentLinkSecurityChallenge(device)) {
            boolean sent = sendGattServerResponse(device, requestId,
                    STATUS_INSUFFICIENT_AUTHENTICATION, 0, null);
            if (!sent) resetCurrentLinkSecurityChallenge(device);
            log("CURRENT LINK SECURITY CHALLENGE · первая B3 READ получила ATT status=5"
                    + (sent ? "" : " (response failed; challenge rearmed)")
                    + " · peer=" + safeAddress(device));
            return;
        }
        Boolean first = markSecureAttConfirmed(device, publicationToken);
        if (first == null) {
            sendGattServerResponse(device, requestId,
                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
            log("SECURE READ потерял current exact PAIR tuple до ответа");
            return;
        }
        boolean sent = sendGattReadResponse(device, requestId, offset,
                "SECURE ATT OK".getBytes(StandardCharsets.UTF_8));
        if (!sent) {
            if (first.booleanValue()) rollbackSecureAttProof(publicationToken);
            log("SECURE READ response не отправлен; B3 proof остаётся retryable");
            return;
        }
        finishSecureAttSuccess(device, "READ", first.booleanValue(), publicationToken);
    }

    /** Full B3 WRITE admission/commit/response transaction on the server callback FIFO. */
    private void handleSecureWriteRequestOnMain(
            @NonNull BluetoothDevice device, int requestId,
            @Nullable BluetoothGattCharacteristic characteristic,
            boolean preparedWrite, boolean responseNeeded, int offset,
            @Nullable byte[] value) {
        int status = BluetoothGatt.GATT_SUCCESS;
        long publicationToken = currentDiagnosticServicePublicationToken(characteristic);
        if (preparedWrite) {
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (offset != 0) {
            status = BluetoothGatt.GATT_INVALID_OFFSET;
        } else if (!"ANCS".equals(asciiCommand(value))) {
            status = BluetoothGatt.GATT_FAILURE;
        } else if (!AncsRecoveryPolicy.allowsB3WriteProof(managedIncomingMode)) {
            // B3 is deliberately a READ challenge in the managed route: the first READ returns
            // ATT status=5 and only its encrypted retry may commit proof. A plain WRITE must never
            // bypass that transition.
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (publicationToken == 0L
                || !isVerifiedPeer(device)
                || (managedIncomingMode
                && !hasCurrentIncomingPairProof(device, publicationToken))) {
            status = STATUS_INSUFFICIENT_AUTHORIZATION;
        }
        Boolean first = null;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            first = markSecureAttConfirmed(device, publicationToken);
            if (first == null) status = STATUS_INSUFFICIENT_AUTHORIZATION;
        }
        boolean responseSent = !responseNeeded
                || sendGattServerResponse(device, requestId, status, 0, null);
        if (status != BluetoothGatt.GATT_SUCCESS || !responseSent || first == null) {
            if (!responseSent && first != null && first.booleanValue()) {
                rollbackSecureAttProof(publicationToken);
            }
            log("SECURE ANCS WRITE "
                    + (status == BluetoothGatt.GATT_SUCCESS && responseSent
                    ? "accepted" : "rejected/retryable")
                    + " · status=" + status + " peer=" + safeAddress(device));
            return;
        }
        finishSecureAttSuccess(device, "WRITE", first.booleanValue(), publicationToken);
    }

    /** READY proof commit and ATT response are atomic with respect to link callbacks on main. */
    private void handleAncsReadyWriteRequestOnMain(
            @NonNull BluetoothDevice device, int requestId,
            @Nullable BluetoothGattCharacteristic characteristic,
            boolean preparedWrite, boolean responseNeeded, int offset,
            @Nullable byte[] value) {
        int status = BluetoothGatt.GATT_SUCCESS;
        long publicationToken = currentDiagnosticServicePublicationToken(characteristic);
        if (preparedWrite) {
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (offset != 0) {
            status = BluetoothGatt.GATT_INVALID_OFFSET;
        } else if (!responseNeeded) {
            // READY is an ownership barrier, not a fire-and-forget hint. Without a checked ATT
            // response Helper cannot know that Android committed this exact tuple.
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        } else if (!"ANCS-READY".equals(asciiCommand(value))) {
            status = BluetoothGatt.GATT_FAILURE;
        } else if (!canAcceptAncsReady(device, publicationToken)) {
            status = STATUS_INSUFFICIENT_AUTHORIZATION;
        }

        IncomingReadyAttach captured = null;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // Commit only the exact proof/latch tuple before the response. Logging, listener
            // callbacks and all client-role commands remain outside this ATT-critical section.
            captured = commitAncsReady(device, publicationToken);
            if (captured == null) status = STATUS_INSUFFICIENT_AUTHORIZATION;
        }
        boolean responseSent = responseNeeded
                && sendGattServerResponse(device, requestId, status, 0, null);
        if (status != BluetoothGatt.GATT_SUCCESS || !responseSent || captured == null) {
            if (!responseSent && captured != null) {
                if (captured.attachTaskArmed
                        && hasCurrentIncomingReadyAttachLatch(
                        captured.rawFacade, captured.publicationToken)) {
                    clearIncomingReadyAttachLatch();
                }
                if (captured.firstReadyProof
                        && captured.sessionGeneration == sessionGeneration
                        && captured.securityEpoch == incomingSecurityEpoch
                        && captured.publicationToken
                        == incomingAncsReadyPublicationToken) {
                    incomingAncsReadyGateOpen = false;
                    incomingAncsReadyPublicationToken = 0L;
                    incomingClientAttachAttempt = 0;
                }
                state("REQUIRES_ANCS LINK SECURE · ЖДУ HELPER READY RETRY");
                log("ANCS-READY ATT response failed; proof/latch rolled back, attach not posted");
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                log("ANCS-READY отклонён в main FIFO · status=" + status
                        + " peer=" + safeAddress(device));
            }
            return;
        }

        if (captured.attachTaskArmed) {
            // A main.post alone is not an ATT transport barrier. Give Fluoride/Core Bluetooth one
            // bounded response-drain interval; any DISCONNECTED or token rollover during it wins
            // and makes the captured identity tuple a no-op before any connectGatt is issued.
            scheduleCapturedIncomingAttachAfterReady(captured);
        }
        // Slow UI/listener diagnostics are deliberately after both checked ATT success and the
        // captured delayed-task post, so they cannot prevent the transport barrier from arming.
        finishAncsReadyCommit(captured);
        if (!captured.attachTaskArmed) {
            log("Duplicate ANCS-READY acknowledged idempotently; delayed first-attach latch "
                    + "already scheduled/consumed for this exact tuple");
            return;
        }
        log("Post-READY exact attach armed after response barrier · delayMs="
                + SECURE_TO_CLIENT_CONNECT_DELAY_MS);
    }

    private void rollbackSecureAttProof(long publicationToken) {
        if (secureAttPublicationToken != publicationToken) return;
        synchronized (verifiedPeerLock) {
            secureAttConfirmed = false;
        }
        secureAttPublicationToken = 0L;
    }

    /** A failed first PAIR response invalidates every proof/task derived from that transcript. */
    private void rollbackNonIdempotentPairTranscript() {
        cancelClientAttemptCallbacks();
        incomingPairRequestFacadeBoundEpoch = 0L;
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        secureAttPublicationToken = 0L;
        incomingAncsReadyGateOpen = false;
        incomingAncsReadyPublicationToken = 0L;
        incomingClientCandidate = null;
        incomingClientAttachAttempt = 0;
        activeClientProvenSecurityEpoch = 0L;
        incomingDiscoveryStarted = false;
    }

    /**
     * A pre-adoption bonded DISCONNECTED callback closes the whole alias set of that one incoming
     * epoch. Otherwise an anonymous facade can remain falsely connected forever and suppress the
     * next physical-link epoch after a hot APK replacement.
     */
    private int retirePreAdoptionServerAliases(@NonNull BluetoothDevice disconnectedDevice) {
        boolean verifiedPeerPresent = getVerifiedPeer() != null;
        boolean establishedHandoff = establishedClientOwnsPhysicalLink(disconnectedDevice);
        boolean pendingHandoff = pendingExactClientAttach(disconnectedDevice);
        if (!AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                managedIncomingMode,
                isSelectedBondedIncomingDevice(disconnectedDevice),
                verifiedPeerPresent,
                establishedHandoff,
                pendingHandoff)) return 0;
        int retired = 0;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.securityEpoch != incomingSecurityEpoch
                        || !peer.connected) continue;
                peer.connected = false;
                peer.roleFacadeHandoff = false;
                peer.roleFacadeHandoffPending = false;
                peer.linkSecurityChallengeIssued = false;
                peer.telemetrySubscribed = false;
                retired++;
            }
        }
        if (retired > 0) {
            cancelServerTelemetryWakePoll();
            incomingClientCandidate = null;
        }
        return retired;
    }

    private boolean hasConnectedServerPeer() {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration && peer.connected) return true;
            }
        }
        return false;
    }

    /**
     * Returns true exactly once for each physical incoming GATT link.  The first B3 read receives
     * ATT insufficient-authentication so Core Bluetooth can restore/start LE security even when
     * Android 9 incorrectly reports the shared Classic device as already BOND_BONDED.  B3 itself
     * deliberately has plain framework permissions: otherwise Fluoride rejects the request with
     * code 12 before this callback and the application can never break that stale-key loop.
     */
    private boolean issueCurrentLinkSecurityChallenge(BluetoothDevice device) {
        GattServerPeer acceptedPeer = incomingPairAcceptedServerPeer;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.securityEpoch != incomingSecurityEpoch
                        || (!peer.connected && !peer.roleFacadeHandoff
                        && !peer.roleFacadeHandoffPending)) continue;
                if (managedIncomingMode) {
                    if (acceptedPeer == null || peer != acceptedPeer
                            || peer.device != incomingPairAcceptedFacade
                            || !sameDevice(peer.device, device)) continue;
                } else if (!sameDevice(peer.device, device)) {
                    continue;
                }
                if (AncsRecoveryPolicy.b3ReadAction(peer.linkSecurityChallengeIssued)
                        != AncsRecoveryPolicy.B3ReadAction.RETURN_ATT_STATUS_5) return false;
                peer.linkSecurityChallengeIssued = true;
                return true;
            }
        }
        // A read should follow the connection callback, but challenge an unexpectedly early Binder
        // request rather than accepting a link that has not reached the current session registry.
        return true;
    }

    private void resetCurrentLinkSecurityChallenge(BluetoothDevice device) {
        GattServerPeer acceptedPeer = incomingPairAcceptedServerPeer;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.securityEpoch == incomingSecurityEpoch
                        && peer == acceptedPeer
                        && peer.device == incomingPairAcceptedFacade
                        && sameDevice(peer.device, device)) {
                    peer.linkSecurityChallengeIssued = false;
                    return;
                }
            }
        }
    }

    private boolean recordGattServerPeer(BluetoothDevice device, int status, int newState) {
        if (device == null) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        String key = deviceKey(device);
        boolean freshConnection = false;
        boolean establishedHandoffCandidate = newState == BluetoothProfile.STATE_DISCONNECTED
                && establishedClientOwnsPhysicalLink(device);
        boolean pendingHandoff = newState == BluetoothProfile.STATE_DISCONNECTED
                && (establishedHandoffCandidate || pendingExactClientAttach(device));
        boolean ambiguousCallback = false;
        boolean acceptedPhysicalRecordDisconnected = false;
        GattServerPeer callbackPeer = null;
        synchronized (gattServerPeers) {
            Set<GattServerPeer> records = new HashSet<>(gattServerPeers.values());
            GattServerPeer exactMatch = null;
            GattServerPeer addressMatch = null;
            int exactMatchCount = 0;
            int addressMatchCount = 0;
            for (GattServerPeer existing : records) {
                if (existing.sessionGeneration != sessionGeneration
                        || existing.securityEpoch != incomingSecurityEpoch) continue;
                boolean live = existing.connected || existing.roleFacadeHandoff
                        || existing.roleFacadeHandoffPending;
                if (!live) continue;
                if (existing.physicalLinkFacade == device
                        || existing.device == device) {
                    exactMatch = existing;
                    exactMatchCount++;
                } else if (sameDevice(existing.physicalLinkFacade, device)
                        || sameDevice(existing.device, device)) {
                    addressMatch = existing;
                    addressMatchCount++;
                }
            }
            if (exactMatchCount == 1) {
                callbackPeer = exactMatch;
            } else if (exactMatchCount > 1) {
                ambiguousCallback = true;
            } else if (addressMatchCount == 1) {
                callbackPeer = addressMatch;
            } else if (addressMatchCount > 1) {
                ambiguousCallback = true;
            }

            boolean anotherFacadeOwnsCurrentLink = false;
            for (GattServerPeer existing : records) {
                if (existing.sessionGeneration == sessionGeneration
                        && existing.securityEpoch == incomingSecurityEpoch
                        && existing.connected && existing != callbackPeer) {
                    anotherFacadeOwnsCurrentLink = true;
                    break;
                }
            }
            if (callbackPeer == null && !ambiguousCallback
                    && status == GATT_SUCCESS
                    && newState == BluetoothProfile.STATE_CONNECTED) {
                // Before PAIR, A anonymous and B bonded remain distinct records. Only the exact
                // current-F04 PAIR transaction may merge the safe A+B topology; doing it here
                // could attach a selected B to an unrelated simultaneous anonymous link A.
                callbackPeer = new GattServerPeer(sessionGeneration, device);
                gattServerPeers.put(key, callbackPeer);
            }
            if (callbackPeer == null) ambiguousCallback = true;

            GattServerPeer peer = callbackPeer;
            if (!ambiguousCallback && peer != null) {
                BluetoothDevice pairFacade = incomingPairAcceptedFacade;
                boolean acceptedPairOtherFacade = pairFacade != null
                        && pairFacade != device
                        && incomingPairAcceptedSessionGeneration == sessionGeneration
                        && incomingPairAcceptedSecurityEpoch == incomingSecurityEpoch;
                // Before PAIR, a unique selected bonded callback may become B. After PAIR, C is
                // immutable authorization for the tuple; A/B/D callbacks only update liveness.
                if (!acceptedPairOtherFacade
                        && isSelectedBondedIncomingDevice(device)) {
                    peer.device = device;
                }
                if (status == GATT_SUCCESS
                        && newState == BluetoothProfile.STATE_CONNECTED) {
                    freshConnection = !peer.connected
                            && (!anotherFacadeOwnsCurrentLink
                            || acceptedPairOtherFacade);
                    if (!peer.connected) {
                        peer.connectedAtElapsedMs = now;
                        peer.linkSecurityChallengeIssued = false;
                    }
                    peer.connected = true;
                    peer.roleFacadeHandoff = false;
                    peer.roleFacadeHandoffPending = false;
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Never claim that the server facade is connected after its own callback says
                    // DISCONNECTED. A separately tracked client-role handoff may still own the ACL.
                    peer.connected = false;
                    // Even a CONNECTED clientIf is only a handoff candidate until one bounded,
                    // non-destructive liveness probe succeeds. ECARX may omit the later client loss
                    // callback, so treating wrapper state alone as proof would retain stale B3/READY.
                    peer.roleFacadeHandoff = false;
                    peer.roleFacadeHandoffPending = pendingHandoff;
                    peer.telemetrySubscribed = false;
                    if (!pendingHandoff) {
                        peer.linkSecurityChallengeIssued = false;
                    }
                    acceptedPhysicalRecordDisconnected = managedIncomingMode
                            && peer == incomingPairAcceptedServerPeer
                            && !pendingHandoff;
                }
            }
        }
        if (ambiguousCallback) {
            log("GATT-server callback fail-closed: physical/auth facade maps to zero or "
                    + "multiple current records · objectId="
                    + System.identityHashCode(device)
                    + " address=" + safeAddress(device));
            return false;
        }
        if (acceptedPhysicalRecordDisconnected) {
            resetIncomingSecurityAfterClientLoss(device,
                    "accepted physical GATT-server record DISCONNECTED");
            log("HA1211 physical/auth disconnect invalidated PAIR/B3/READY and attempt · "
                    + "physicalObjectId="
                    + System.identityHashCode(callbackPeer.physicalLinkFacade)
                    + " callbackObjectId=" + System.identityHashCode(device)
                    + " sameAddress="
                    + sameDevice(callbackPeer.physicalLinkFacade, device));
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED
                && !hasServerTelemetrySubscribers()) {
            cancelServerTelemetryWakePoll();
        }
        if (establishedHandoffCandidate) {
            log("GATT-server facade DISCONNECTED; CONNECTED clientIf требует bounded "
                    + "RSSI liveness proof перед подтверждением role handoff");
        } else if (pendingHandoff) {
            log("GATT-server facade DISCONNECTED; exact direct callback pending, "
                    + "physical-loss decision deferred to client callback");
        }
        return freshConnection;
    }

    private void bindServerPeerToCurrentSecurityEpoch(@NonNull BluetoothDevice device) {
        synchronized (gattServerPeers) {
            GattServerPeer peer = findUniqueServerPeerLocked(device, false, false);
            if (peer == null || peer.sessionGeneration != sessionGeneration) return;
            if (peer.securityEpoch != incomingSecurityEpoch) {
                peer.securityEpoch = incomingSecurityEpoch;
                peer.linkSecurityChallengeIssued = false;
            }
        }
    }

    /** Starts a clean PAIR/B3/ANCS-READY epoch without discarding an established GATT clientIf. */
    private void beginFreshIncomingSecurityEpoch(@NonNull BluetoothDevice device,
                                                 @NonNull String reason) {
        beginFreshIncomingSecurityEpoch(device, reason, true);
    }

    private void beginFreshIncomingSecurityEpoch(@NonNull BluetoothDevice device,
                                                 @NonNull String reason,
                                                 boolean emitDiagnosticLogs) {
        if (!managedIncomingMode) return;
        BluetoothGatt staleOpportunisticObserver = gatt;
        if (staleOpportunisticObserver != null && activeClientOpportunistic) {
            // An opportunistic wrapper is authorized for exactly one S/E/P/raw tuple. It cannot inherit
            // the fresh server epoch and must not block that epoch's one post-READY allocation.
            cancelConnectTimeout();
            closeClientGatt(staleOpportunisticObserver);
            if (emitDiagnosticLogs) {
                log("Fresh incoming epoch close-only retired prior opportunistic wrapper; "
                        + "BluetoothGatt.disconnect/GATT-server close not issued");
            }
        }
        // Keep one raw controller operation serialized: a read already submitted to Bluetooth
        // cannot be physically cancelled. Mark it discard-only, then queue the current-epoch read
        // after its callback/timeout instead of issuing a competing read.
        prepareInFlightLinkProbeForFreshEpoch(emitDiagnosticLogs);
        incomingSecurityEpoch++;
        incomingPairRequestFacadeBoundEpoch = 0L;
        if (incomingStaleOwnerAwaitingFreshEpoch
                && incomingStaleEstablishedOwner != null) {
            incomingStaleOwnerReplacementEpoch = incomingSecurityEpoch;
            if (emitDiagnosticLogs) {
                log("Fresh incoming epoch=" + incomingSecurityEpoch
                        + " принял status=22 stale owner; replacement ждёт B3/ANCS-READY");
            }
        }
        activeClientProvenSecurityEpoch = 0L;
        poisonedWrapperReplacementAttempt = 0;
        mandatoryDescriptorStatus133RetryCount = 0;
        cancelClientAttemptCallbacks();
        cancelBondTimeout();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        incomingAncsReadyGateOpen = false;
        secureAttPublicationToken = 0L;
        incomingAncsReadyPublicationToken = 0L;
        incomingDiscoveryStarted = false;
        incomingClientAttachAttempt = 0;
        incomingClientCandidate = null;
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();

        BluetoothGatt owner = gatt;
        boolean establishedOwner = owner != null && activeClientEstablished;
        if (owner != null && !establishedOwner) {
            cancelConnectTimeout();
            if (emitDiagnosticLogs) {
                log("Fresh epoch leaves stale never-established wrapper inert until a new "
                        + "post-READY task may close/replace it");
            }
        }
        clearAncsRuntimeWithoutClientCommands(emitDiagnosticLogs);
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration) continue;
                peer.roleFacadeHandoff = false;
                peer.roleFacadeHandoffPending = false;
                peer.telemetrySubscribed = false;
                peer.linkSecurityChallengeIssued = false;
            }
        }
        if (establishedOwner && emitDiagnosticLogs) {
            log("Fresh epoch does not rearm/close/RSSI-probe retained client owner before "
                    + "current PAIR+B3+READY");
        }
        if (emitDiagnosticLogs) {
            log("Fresh incoming security epoch=" + incomingSecurityEpoch
                    + " · peer=" + safeAddress(device)
                    + " · PAIR/B3/ANCS-READY reset · " + reason);
        }
    }

    /** Invalidates all server proofs only after the established client confirms physical loss. */
    private boolean resetIncomingSecurityAfterClientLoss(@NonNull BluetoothDevice device,
                                                          @NonNull String reason) {
        if (!managedIncomingMode) return false;
        BluetoothGatt failedOpportunisticObserver = gatt;
        if (failedOpportunisticObserver != null && activeClientOpportunistic) {
            cancelConnectTimeout();
            closeClientGatt(failedOpportunisticObserver);
        }
        cancelAmbiguousAclProbe();
        clearIncomingStaleOwnerTransfer();
        incomingSecurityEpoch++;
        incomingPairRequestFacadeBoundEpoch = 0L;
        activeClientProvenSecurityEpoch = 0L;
        cancelClientAttemptCallbacks();
        cancelBondTimeout();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        incomingAncsReadyGateOpen = false;
        secureAttPublicationToken = 0L;
        incomingAncsReadyPublicationToken = 0L;
        incomingDiscoveryStarted = false;
        incomingClientAttachAttempt = 0;
        incomingClientCandidate = null;
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration) continue;
                peer.connected = false;
                peer.roleFacadeHandoff = false;
                peer.roleFacadeHandoffPending = false;
                peer.linkSecurityChallengeIssued = false;
                peer.telemetrySubscribed = false;
            }
        }
        cancelServerTelemetryWakePoll();
        boolean rawOwnerClosed = clearAncsRuntime();
        log("Confirmed client physical loss: security epoch=" + incomingSecurityEpoch
                + " · peer=" + safeAddress(device)
                + " · stale B3/ANCS-READY invalidated · " + reason);
        return rawOwnerClosed;
    }

    /**
     * A client-role failure does not prove ACL loss while the exact current F04 server facade is
     * still CONNECTED. Retain that authority and replace only an ambiguous closed wrapper;
     * otherwise invalidate the link epoch and wait for the next incoming connection. Status=22
     * is stricter: its retired client wrapper closes immediately, while the independent server
     * facade/ACL and a one-shot fresh-epoch transfer token remain intact.
     */
    private void recoverEstablishedIncomingClientAfterCallbackLoss(
            @NonNull BluetoothGatt expected, @NonNull String reason,
            boolean replaceAfterFreshSecurity) {
        if (!managedIncomingMode) return;
        BluetoothDevice device = expected.getDevice();
        if (activeClientOpportunistic && gatt == expected) {
            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
            retireOpportunisticObserverWithoutLinkMutation(expected,
                    "established passive observer lost callback/link · " + reason);
            resetRetiredObserverAfterServerFacadeLoss(attemptPeer, device,
                    "established opportunistic observer lost after server facade loss · "
                            + reason);
            return;
        }
        if (replaceAfterFreshSecurity) {
            // Any old/new-epoch RSSI callback is now discard-only. It must never reach the
            // generic recovery path and revive this retired wrapper with BluetoothGatt.connect().
            cancelAmbiguousAclProbe();
            GattServerPeer exactConnectedFacade = findConnectedServerPeer(device);
            boolean currentFacadeAlreadyArrived =
                    AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                            exactConnectedFacade != null,
                            exactConnectedFacade != null
                                    && isSelectedBondedIncomingDevice(
                                    exactConnectedFacade.device),
                            exactConnectedFacade != null
                                    && exactConnectedFacade.securityEpoch
                                    == incomingSecurityEpoch,
                            isCurrentDiagnosticServicePublicationToken(
                                    publishedDiagnosticServicePublicationToken));
            boolean rawOwnerClosed;
            if (currentFacadeAlreadyArrived) {
                // Callback inversion seen on ECARX: the next server-role CONNECTED callback can
                // reach the app just before status=22 retires the old client role. That facade is
                // already the fresh epoch; never erase it or wait for a connection after it.
                cancelClientAttemptCallbacks();
                rawOwnerClosed = clearAncsRuntime();
                incomingDiscoveryStarted = false;
            } else {
                rawOwnerClosed = resetIncomingSecurityAfterClientLoss(
                        device, reason + " · local-host clientIf retired");
            }
            // status=22 is the stack's final word for this local client role. Closing its Java
            // wrapper cannot close the independent GATT-server/ACL role, and makes every late
            // callback fail the callbackGatt != gatt ownership gate before it can mutate state.
            if (!rawOwnerClosed && gatt == expected) {
                closeClientGatt(expected);
            }
            incomingStaleEstablishedOwner = expected;
            // Keep this transfer latch armed until B3/READY actually consumes it. If the facade
            // seen above was the tail of the old link, its DISCONNECTED followed by a genuinely
            // fresh CONNECTED callback simply rebinds replacementEpoch in beginFresh...().
            incomingStaleOwnerAwaitingFreshEpoch = true;
            incomingStaleOwnerReplacementEpoch = currentFacadeAlreadyArrived
                    ? incomingSecurityEpoch : 0L;
            incomingFreshReplacementGatt = null;
            clientConnectInFlight = false;
            gattClientConnected = false;
            state("STATUS=22 · ЖДУ FRESH F04 + B3 + READY");
            log("Established clientIf status=22 больше не rearm через gatt.connect(); "
                    + "stale wrapper "
                    + "закрыт немедленно без изменения GATT-server/ACL"
                    + (currentFacadeAlreadyArrived
                    ? "; fresh exact F04 facade уже пришёл до client callback"
                    : "; следующий physical incoming epoch получит ровно один exact attach"));
            if (currentFacadeAlreadyArrived
                    && secureAttConfirmed && incomingAncsReadyGateOpen) {
                replaceStaleEstablishedOwnerAfterFreshReady(
                        exactConnectedFacade, exactConnectedFacade.device,
                        publishedDiagnosticServicePublicationToken);
            }
            return;
        }
        if (incomingStaleOwnerAwaitingFreshEpoch
                && incomingStaleEstablishedOwner == expected) {
            log("Поздний RSSI/client recovery проигнорирован: status=22 owner ждёт "
                    + "fresh F04 + B3/READY и не может быть rearm через gatt.connect()");
            return;
        }
        GattServerPeer connectedFacade = findConnectedServerPeer(device);
        if (connectedFacade != null
                && isCurrentDiagnosticServicePublicationToken(
                publishedDiagnosticServicePublicationToken)) {
            boolean rawOwnerClosed = clearAncsRuntime();
            incomingDiscoveryStarted = false;
            if (rawOwnerClosed || gatt != expected) {
                if (poisonedWrapperReplacementAttempt
                        >= RSSI_POISONED_WRAPPER_REPLACEMENT_MAX_ATTEMPTS) {
                    log("Established client wrapper replacement budget exhausted; "
                            + "current virtual owner is not reused · " + reason);
                    resetIncomingSecurityAfterClientLoss(device,
                            "ambiguous callback wrapper replacement budget exhausted");
                    preserveManagedIncomingPublicationAfterLinkLoss(
                            "wait for next physical incoming link after wrapper poison");
                    return;
                }
                poisonedWrapperReplacementAttempt++;
                cancelClientAttemptCallbacks();
                incomingClientCandidate = connectedFacade.device;
                log("Established client callback lost while exact F04 facade remains; "
                        + "ambiguous wrapper closed and bounded direct replacement #"
                        + poisonedWrapperReplacementAttempt + " scheduled · "
                        + reason);
                scheduleIncomingClientAttachRetry(
                        "exact F04 facade retained after client callback loss · " + reason);
            } else {
                awaitIncomingBackgroundOwner(expected, activeClientGeneration, reason);
            }
            return;
        }
        boolean rawOwnerClosed = resetIncomingSecurityAfterClientLoss(device, reason);
        if (rawOwnerClosed || gatt != expected) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "raw callback owner closed after confirmed physical loss · " + reason);
            return;
        }
        awaitIncomingBackgroundOwner(expected, activeClientGeneration, reason);
    }

    private boolean confirmPendingServerFacadeHandoff(@NonNull BluetoothDevice device) {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration != sessionGeneration
                        || peer.securityEpoch != incomingSecurityEpoch
                        || (peer.physicalLinkFacade != device
                        && peer.device != device
                        && !sameDevice(peer.physicalLinkFacade, device)
                        && !sameDevice(peer.device, device))) continue;
                if (peer.roleFacadeHandoff) return true;
                if (!peer.roleFacadeHandoffPending) continue;
                peer.roleFacadeHandoffPending = false;
                peer.roleFacadeHandoff = true;
                log("Pending server-facade handoff подтверждён client liveness proof");
                return true;
            }
        }
        return false;
    }

    private boolean hasServerTelemetrySubscribers() {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isServerTelemetrySubscribed(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed
                        && (peer.physicalLinkFacade == device
                        || peer.device == device
                        || sameDevice(peer.physicalLinkFacade, device)
                        || sameDevice(peer.device, device))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Android 9 may release only the GATT-server facade when the app registers its client role on
     * the exact same LE peer. The controller link is still owned by the client registration, so
     * dropping the peer/CCCD here would silently stop Helper background telemetry wake-ups.
     */
    private boolean exactClientTargetsDevice(BluetoothDevice device) {
        GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
        return managedIncomingMode
                && attemptPeer != null
                && incomingClientCandidate == attemptPeer.device
                && incomingClientAttemptPairFacade == attemptPeer.device
                && activeClientTarget == attemptPeer.physicalLinkFacade
                && incomingClientAttemptTransportFacade
                == attemptPeer.physicalLinkFacade
                && (device == attemptPeer.physicalLinkFacade
                || device == attemptPeer.device
                || sameDevice(device, attemptPeer.physicalLinkFacade)
                || sameDevice(device, attemptPeer.device))
                && gatt != null;
    }

    private boolean establishedClientOwnsPhysicalLink(BluetoothDevice device) {
        return exactClientTargetsDevice(device)
                && activeClientEstablished && gattClientConnected;
    }

    private boolean pendingExactClientAttach(BluetoothDevice device) {
        return exactClientTargetsDevice(device)
                && !activeClientEstablished && clientConnectInFlight;
    }

    private void setServerTelemetrySubscription(BluetoothDevice device, boolean enabled,
                                                long publicationToken) {
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)
                || !isVerifiedPeer(device) || findCurrentServerPeer(device) == null) {
            log("B4 CCCD completion ignored: stale F04 publication/peer · peer="
                    + safeAddress(device) + " token=" + publicationToken);
            return;
        }
        boolean found = false;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : new HashSet<>(gattServerPeers.values())) {
                if (peer.sessionGeneration != sessionGeneration
                        || !peer.connected
                        || (peer.physicalLinkFacade != device
                        && peer.device != device
                        && !sameDevice(peer.physicalLinkFacade, device)
                        && !sameDevice(peer.device, device))) continue;
                peer.telemetrySubscribed = enabled;
                found = true;
                break;
            }
        }
        BluetoothGattCharacteristic telemetry = serverTelemetryCharacteristic;
        if (telemetry != null) {
            BluetoothGattDescriptor cccd = telemetry.getDescriptor(
                    AncsProtocol.CLIENT_CONFIGURATION);
            if (cccd != null) {
                cccd.setValue(enabled
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            }
        }
        log("B4 wake-poll subscription=" + enabled
                + " peer=" + safeAddress(device) + " registered=" + found);
        if (enabled && found) {
            scheduleServerTelemetryWakePoll(250L);
        } else if (!hasServerTelemetrySubscribers()) {
            cancelServerTelemetryWakePoll();
        }
    }

    private void cancelServerTelemetryWakePoll() {
        if (serverTelemetryWakePoll != null) {
            main.removeCallbacks(serverTelemetryWakePoll);
        }
        serverTelemetryWakePoll = null;
    }

    private void scheduleServerTelemetryWakePoll(long delayMs) {
        cancelServerTelemetryWakePoll();
        if (!hasServerTelemetrySubscribers()) return;
        serverTelemetryWakePoll = () -> {
            serverTelemetryWakePoll = null;
            sendServerTelemetryWakePoll();
        };
        main.postDelayed(serverTelemetryWakePoll, delayMs);
    }

    /**
     * The notification contains no phone data. Its only purpose is to give bluetooth-central a
     * Core Bluetooth event in the background; Helper then samples public iOS state and writes the
     * authenticated eight-byte B4 frame back with response on this same connection.
     */
    private void sendServerTelemetryWakePoll() {
        BluetoothGattServer server = gattServer;
        BluetoothGattCharacteristic telemetry = serverTelemetryCharacteristic;
        if (server == null || telemetry == null || !hasServerTelemetrySubscribers()) return;

        List<BluetoothDevice> targets = new ArrayList<>();
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed) {
                    targets.add(peer.device);
                }
            }
        }
        telemetry.setValue(new byte[]{0x01});
        for (BluetoothDevice target : targets) {
            if (!isVerifiedPeer(target)) continue;
            boolean accepted;
            try {
                accepted = server.notifyCharacteristicChanged(target, telemetry, false);
            } catch (RuntimeException failure) {
                accepted = false;
                log("B4 wake-poll notify exception: " + failure);
            }
            if (!accepted) {
                log("B4 wake-poll notify rejected · peer=" + safeAddress(target));
            }
        }
        if (hasServerTelemetrySubscribers()) {
            scheduleServerTelemetryWakePoll(SERVER_TELEMETRY_WAKE_POLL_MS);
        }
    }

    private void handleServerFacadeDisconnected(BluetoothDevice device) {
        BluetoothGatt staleStatus22Owner = incomingStaleEstablishedOwner;
        if (incomingStaleOwnerAwaitingFreshEpoch && staleStatus22Owner != null
                && sameDevice(staleStatus22Owner.getDevice(), device)) {
            state("STATUS=22 · OLD FACADE LOST · ЖДУ FRESH CONNECTED");
            log("Server facade DISCONNECTED не сбрасывает pending status=22 recovery; "
                    + "следующий CONNECTED перенесёт replacement на свой epoch");
            return;
        }
        if (establishedClientOwnsPhysicalLink(device)) {
            state("SERVER FACADE LOST · VERIFYING CLIENT HANDOFF");
            log("GATT-server callback released after exact-device client registration: "
                    + safeAddress(device)
                    + "; retained Android clientIf проверяется non-destructive RSSI probe"
                    + " connected=" + gattClientConnected
                    + " inFlight=" + clientConnectInFlight);
            scheduleServerFacadeHandoffProbe(device,
                    "GATT-server facade DISCONNECTED on established clientIf");
            return;
        }
        if (pendingExactClientAttach(device)) {
            state("SERVER FACADE LOST · ЖДУ DIRECT CLIENT CALLBACK");
            log("Server facade помечен DISCONNECTED; never-established wrapper сохранён "
                    + "только до bounded callback/timeout, proof epoch пока не переносится");
            return;
        }
        BluetoothGatt passiveObserver = gatt;
        if (passiveObserver != null && activeClientOpportunistic
                && (passiveObserver.getDevice() == device
                || sameDevice(passiveObserver.getDevice(), device))) {
            if (!retireOpportunisticObserverWithoutLinkMutation(passiveObserver,
                    "GATT-server facade DISCONNECTED")) {
                // Defensive close-only fallback for a lineage already invalidated by the server
                // callback. Do not issue a physical disconnect from an opportunistic observer.
                closeClientGatt(passiveObserver);
            }
            resetIncomingSecurityAfterClientLoss(device,
                    "independent GATT-server facade DISCONNECTED proof");
            state("INCOMING LINK LOST · ЖДУ FRESH F04/PAIR/B3/READY");
            return;
        }
        cancelClientAttemptCallbacks();
        state(managedReconnectEnabled
                ? REMOTE_LOGICAL_NAME + " · INCOMING LINK LOST"
                : "VERIFIED SERVER LINK DISCONNECTED");
        log("VERIFIED GATT SERVER LINK disconnected: " + safeAddress(device)
                + "; pending same-peer client attach остановлен");
        if (activeClientTarget != null && sameDevice(activeClientTarget, device)) {
            BluetoothGatt current = gatt;
            boolean passiveOpportunisticOwner = activeClientOpportunistic;
            gatt = null;
            activeClientOpportunistic = false;
            gattClientConnected = false;
            clientConnectInFlight = false;
            activeClientTarget = null;
            clearAncsRuntime();
            if (current != null) {
                if (!passiveOpportunisticOwner) {
                    try {
                        current.disconnect();
                    } catch (RuntimeException ignored) {
                    }
                }
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (managedReconnectEnabled) {
            preserveManagedIncomingPublicationAfterLinkLoss("server callback disconnect");
        }
    }

    /** Clears only per-link state; the published service identity remains stable for reconnect. */
    private void preserveManagedIncomingPublicationAfterLinkLoss(@NonNull String reason) {
        if (!managedIncomingMode) return;
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        BluetoothGatt oldClient = gatt;
        gatt = null;
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        activeClientAutoConnect = false;
        activeClientOpportunistic = false;
        activeClientEstablished = false;
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (RuntimeException ignored) {
            }
        }
        resetVerifiedPeerSession();
        managedIncomingMode = true;
        state(LOCAL_LOGICAL_NAME + " · ADVERTISING · ЖДУ RECONNECT");
        log("Обычный разрыв: GATT server, реклама и namespace "
                + String.format(Locale.US, "%04X", serverDiagnosticGeneration)
                + " publication=" + String.format(Locale.US, "%06X",
                publishedManagedIncomingPublicationNonce)
                + " сохранены; новый Central link пройдёт PAIR/B3 заново · " + reason);
    }

    private static String deviceKey(BluetoothDevice device) {
        String address = safeAddress(device);
        return address.isEmpty()
                ? "identity:" + System.identityHashCode(device)
                : "address:" + address.toUpperCase(Locale.US);
    }

    /** Invalidates callbacks from every earlier GATT-server publication lifecycle. */
    private void invalidateDiagnosticServicePublication() {
        // RSSI callbacks carry no publication token. Turn any already-submitted read into a
        // discard-only slot before P rolls over so it cannot prove handoff/current epoch later.
        prepareInFlightLinkProbeForFreshEpoch();
        BluetoothGatt ambiguousRawOwner = gatt;
        boolean abandonsDiscovery = ambiguousRawOwner != null && discoveryPending
                && activeDiscoveryGatt == ambiguousRawOwner
                && activeDiscoveryOperationGeneration != 0L;
        boolean abandonsDescriptor = ambiguousRawOwner != null
                && activeDescriptorWriteGatt == ambiguousRawOwner
                && activeDescriptorWriteOperationGeneration != 0L;
        boolean abandonsHelperProof = ambiguousRawOwner != null
                && activeHelperProofWriteGatt == ambiguousRawOwner
                && activeHelperProofWriteOperationGeneration != 0L;
        boolean abandonsHelperRead = managedIncomingMode
                && ambiguousRawOwner != null
                && iphoneHelperTelemetryReadPending;
        if (abandonsDiscovery || abandonsDescriptor || abandonsHelperProof
                || abandonsHelperRead) {
            log("F04 publication invalidated with raw callback in flight; closing wrapper "
                    + "before publication/token rollover");
            closeClientGatt(ambiguousRawOwner);
            clearAncsRuntime();
        }
        serverDiagnosticServicePublished = false;
        pendingDiagnosticServicePublication = null;
        publishedDiagnosticServicePublication = null;
        pendingDiagnosticServicePublicationToken = 0L;
        publishedDiagnosticServicePublicationToken = 0L;
        pendingManagedIncomingPublicationNonce = 0;
        publishedManagedIncomingPublicationNonce = 0;
        clearManagedLinkBoundProof();
        clearIncomingPairProof();
        clearIncomingClientAttemptLineage();
        secureAttPublicationToken = 0L;
        incomingAncsReadyPublicationToken = 0L;
        serverDiagnosticServicePublicationToken++;
    }

    /** Returns the accepted token only for a characteristic of the exact current F04 object. */
    private long currentDiagnosticServicePublicationToken(
            @Nullable BluetoothGattCharacteristic characteristic) {
        long token = publishedDiagnosticServicePublicationToken;
        BluetoothGattService published = publishedDiagnosticServicePublication;
        if (!serverDiagnosticServicePublished || token == 0L
                || token != serverDiagnosticServicePublicationToken
                || published == null || characteristic == null
                || characteristic.getService() != published) return 0L;
        return token;
    }

    private boolean isCurrentDiagnosticServicePublicationToken(long token) {
        return serverDiagnosticServicePublished && token != 0L
                && token == serverDiagnosticServicePublicationToken
                && token == publishedDiagnosticServicePublicationToken
                && publishedDiagnosticServicePublication != null;
    }

    private void openGattServer() {
        if (gattServer != null) return;
        // Publication is asynchronous. No clientIf may be registered until onServiceAdded
        // confirms that the F04 database is live in the system Bluetooth process.
        invalidateDiagnosticServicePublication();
        try {
            gattServer = manager.openGattServer(context, gattServerCallback);
        } catch (RuntimeException failure) {
            log("openGattServer exception: " + failure);
            gattServer = null;
        }
        if (gattServer == null) {
            log("openGattServer вернул null");
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "openGattServer returned null");
            state("GATT_SERVER_UNAVAILABLE");
            return;
        }
        if (managedIncomingMode && !preparePendingManagedIncomingPublicationNonce()) {
            advertisingDesired = false;
            clearPreparedAdvertising();
            closeGattServer();
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "F04 publication nonce prepare failed");
            state("F04_PUBLICATION_NONCE_PREPARE_FAILED");
            return;
        }
        BluetoothGattService service = new BluetoothGattService(
                serverDiagnosticService, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic information = new BluetoothGattCharacteristic(
                serverDiagnosticCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        information.setValue((LOCAL_LOGICAL_NAME + "/3")
                .getBytes(StandardCharsets.UTF_8));

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                serverControlCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattCharacteristic secure = new BluetoothGattCharacteristic(
                serverSecureCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        secure.setValue("SECURE ATT OK".getBytes(StandardCharsets.UTF_8));

        BluetoothGattCharacteristic telemetry = new BluetoothGattCharacteristic(
                serverTelemetryCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        telemetry.setValue("TEL3;-;-;X;-;0".getBytes(StandardCharsets.UTF_8));
        BluetoothGattDescriptor telemetryCccd = new BluetoothGattDescriptor(
                AncsProtocol.CLIENT_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE);
        telemetryCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        boolean telemetryDescriptorAdded = telemetry.addDescriptor(telemetryCccd);
        serverTelemetryCharacteristic = telemetry;

        boolean informationAdded = service.addCharacteristic(information);
        boolean controlAdded = service.addCharacteristic(control);
        boolean secureAdded = service.addCharacteristic(secure);
        boolean telemetryAdded = service.addCharacteristic(telemetry);
        log("Diagnostic characteristics: INFO=" + informationAdded
                + " CONTROL=" + controlAdded + " SECURE=" + secureAdded
                + " TELEMETRY=" + telemetryAdded
                + " TELEMETRY_CCCD=" + telemetryDescriptorAdded);
        if (!informationAdded || !controlAdded || !secureAdded || !telemetryAdded
                || !telemetryDescriptorAdded) {
            advertisingDesired = false;
            clearPreparedAdvertising();
            closeGattServer();
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "GATT characteristic add failed");
            state("GATT_CHARACTERISTIC_ADD_FAILED");
            return;
        }
        pendingDiagnosticServicePublication = service;
        pendingDiagnosticServicePublicationToken = serverDiagnosticServicePublicationToken;
        boolean accepted = gattServer.addService(service);
        log("GATT server открыт; add diagnostic service=" + accepted);
        if (!accepted) {
            advertisingDesired = false;
            clearPreparedAdvertising();
            closeGattServer();
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "GATT addService start failed");
            state("GATT_SERVICE_ADD_START_FAILED");
        } else {
            state("ЖДУ ДОБАВЛЕНИЯ GATT SERVICE");
        }
    }

    private void startPreparedAdvertising() {
        if (!advertisingDesired || advertiser == null
                || preparedAdvertiseSettings == null
                || preparedAdvertiseData == null
                || preparedScanResponse == null) {
            log("Запуск рекламы отменён: состояние уже изменилось");
            return;
        }
        long publicationToken = publishedDiagnosticServicePublicationToken;
        int publicationNonce = publishedManagedIncomingPublicationNonce;
        if (!isCurrentDiagnosticServicePublicationToken(publicationToken)
                || managedIncomingMode
                && !ManagedIncomingPublicationPolicy.isValidPublicationNonce(
                publicationNonce)) {
            advertisingDesired = false;
            log("Запуск рекламы отменён: stale F04 publication/nonce · token="
                    + publicationToken + " nonce="
                    + String.format(Locale.US, "%06X", publicationNonce));
            clearPreparedAdvertising();
            closeGattServer();
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "stale F04 publication/nonce before advertising");
            return;
        }
        BluetoothLeAdvertiser ownerAdvertiser = advertiser;
        PublicationAdvertiseCallback callback = new PublicationAdvertiseCallback(
                ownerAdvertiser, publicationToken, publicationNonce);
        activeAdvertiseCallback = callback;
        advertisingPending = true;
        try {
            ownerAdvertiser.startAdvertising(preparedAdvertiseSettings, preparedAdvertiseData,
                    preparedScanResponse, callback);
            state(solicitationAdvertising
                    ? "SOLICITATION REQUESTED · ЗАПУСК РЕКЛАМЫ"
                    : "ЗАПУСК DIAGNOSTIC-РЕКЛАМЫ");
        } catch (RuntimeException failure) {
            advertisingPending = false;
            advertisingDesired = false;
            log("startAdvertising exception: " + failure);
            clearPreparedAdvertising();
            closeGattServer();
            scheduleManagedIncomingPublicationRestartIfNeeded(
                    "startAdvertising exception");
            state("ADVERTISE_EXCEPTION");
        }
    }

    /** Arms the reverse-route restart before generic retry-state routing can consume it. */
    private void scheduleManagedIncomingPublicationRestartIfNeeded(@NonNull String reason) {
        if (managedReconnectEnabled && managedIncomingMode && managedSavedPeer != null) {
            scheduleManagedIncomingRestart(reason);
        }
    }

    private void clearPreparedAdvertising() {
        preparedAdvertiseSettings = null;
        preparedAdvertiseData = null;
        preparedScanResponse = null;
    }

    private void closeGattServer() {
        PublicationAdvertiseCallback callback = activeAdvertiseCallback;
        activeAdvertiseCallback = null;
        if (callback != null) {
            try {
                callback.ownerAdvertiser.stopAdvertising(callback);
            } catch (RuntimeException failure) {
                log("closeGattServer stopAdvertising exception: " + failure);
            }
        }
        advertising = false;
        advertisingPending = false;
        invalidateDiagnosticServicePublication();
        cancelServerTelemetryWakePoll();
        serverTelemetryCharacteristic = null;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                peer.telemetrySubscribed = false;
            }
        }
        BluetoothGattServer old = gattServer;
        gattServer = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private long armDiscoveryOperation(@NonNull BluetoothGatt owner,
                                       long clientGeneration,
                                       long publicationToken) {
        if (managedIncomingMode) {
            clearManagedLinkBoundProof();
            cancelHelperTelemetryRecovery();
            iphoneHelperTelemetrySubscriptionAttempted = false;
            iphoneHelperTelemetrySubscribed = false;
            iphoneHelperValidTelemetryReceived = false;
            helperAncsReadyProofAttempted = false;
            helperAncsReadyProofPending = false;
            helperAncsReadyProofAcknowledged = false;
            if (helperAncsReadyProofRetry != null) {
                main.removeCallbacks(helperAncsReadyProofRetry);
                helperAncsReadyProofRetry = null;
            }
            notificationSource = null;
            dataSource = null;
            controlPoint = null;
            earlyNotificationSourceFrames.clear();
            gattReady = false;
        }
        discoveryOperationGeneration++;
        if (discoveryOperationGeneration == 0L) discoveryOperationGeneration++;
        activeDiscoveryGatt = owner;
        activeDiscoveryOperationGeneration = discoveryOperationGeneration;
        activeDiscoveryClientGeneration = clientGeneration;
        activeDiscoverySessionGeneration = sessionGeneration;
        activeDiscoverySecurityEpoch = incomingSecurityEpoch;
        activeDiscoveryPublicationToken = publicationToken;
        activeDiscoveryChallenge = managedIncomingMode && incomingClientAttemptChallenge != null
                ? incomingClientAttemptChallenge.clone() : null;
        activeDiscoveryServerPeer = managedIncomingMode
                ? incomingClientAttemptServerPeer : null;
        activeDiscoveryPhysicalFacade = managedIncomingMode
                ? incomingClientAttemptTransportFacade : null;
        activeDiscoveryPairFacade = managedIncomingMode
                ? incomingClientAttemptPairFacade : null;
        activeDiscoveryDatabaseGeneration = managedIncomingMode
                ? managedF05DatabaseGeneration : 0L;
        clearAcceptedDiscoveryLineage();
        return activeDiscoveryOperationGeneration;
    }

    private boolean ownsDiscoveryOperation(@NonNull BluetoothGatt owner,
                                           long operationGeneration) {
        if (!discoveryPending || operationGeneration == 0L
                || activeDiscoveryGatt != owner
                || activeDiscoveryOperationGeneration != operationGeneration
                || activeDiscoveryClientGeneration != activeClientGeneration
                || !sessionState.isCurrent(activeDiscoveryClientGeneration)) return false;
        return !managedIncomingMode || (ownsManagedAttemptLineage(
                owner, activeDiscoveryChallenge, activeDiscoveryServerPeer,
                activeDiscoveryPhysicalFacade, activeDiscoveryPairFacade,
                activeDiscoverySessionGeneration, activeDiscoverySecurityEpoch,
                activeDiscoveryPublicationToken, activeDiscoveryClientGeneration)
                && activeDiscoveryDatabaseGeneration == managedF05DatabaseGeneration
                && activeClientProvenSecurityEpoch == activeDiscoverySecurityEpoch
                && isCurrentDiagnosticServicePublicationToken(
                activeDiscoveryPublicationToken)
                && secureAttPublicationToken == activeDiscoveryPublicationToken
                && incomingAncsReadyPublicationToken == activeDiscoveryPublicationToken);
    }

    private void acceptDiscoveryLineage() {
        acceptedDiscoveryGatt = activeDiscoveryGatt;
        acceptedDiscoveryOperationGeneration = activeDiscoveryOperationGeneration;
        acceptedDiscoveryClientGeneration = activeDiscoveryClientGeneration;
        acceptedDiscoverySessionGeneration = activeDiscoverySessionGeneration;
        acceptedDiscoverySecurityEpoch = activeDiscoverySecurityEpoch;
        acceptedDiscoveryPublicationToken = activeDiscoveryPublicationToken;
        acceptedDiscoveryChallenge = activeDiscoveryChallenge == null
                ? null : activeDiscoveryChallenge.clone();
        acceptedDiscoveryServerPeer = activeDiscoveryServerPeer;
        acceptedDiscoveryPhysicalFacade = activeDiscoveryPhysicalFacade;
        acceptedDiscoveryPairFacade = activeDiscoveryPairFacade;
        acceptedDiscoveryDatabaseGeneration = activeDiscoveryDatabaseGeneration;
        clearActiveDiscoveryLineage();
    }

    private boolean ownsAcceptedDiscoveryLineage(@NonNull BluetoothGatt owner) {
        if (acceptedDiscoveryGatt != owner
                || acceptedDiscoveryOperationGeneration == 0L
                || acceptedDiscoveryClientGeneration != activeClientGeneration
                || !sessionState.isCurrent(acceptedDiscoveryClientGeneration)) return false;
        return !managedIncomingMode
                || (ownsManagedAttemptLineage(
                owner, acceptedDiscoveryChallenge, acceptedDiscoveryServerPeer,
                acceptedDiscoveryPhysicalFacade, acceptedDiscoveryPairFacade,
                acceptedDiscoverySessionGeneration, acceptedDiscoverySecurityEpoch,
                acceptedDiscoveryPublicationToken, acceptedDiscoveryClientGeneration)
                && acceptedDiscoveryDatabaseGeneration == managedF05DatabaseGeneration
                && activeClientProvenSecurityEpoch == acceptedDiscoverySecurityEpoch
                && isCurrentDiagnosticServicePublicationToken(
                acceptedDiscoveryPublicationToken)
                && secureAttPublicationToken == acceptedDiscoveryPublicationToken
                && incomingAncsReadyPublicationToken
                == acceptedDiscoveryPublicationToken);
    }

    private void clearActiveDiscoveryLineage() {
        activeDiscoveryGatt = null;
        activeDiscoveryOperationGeneration = 0L;
        activeDiscoveryClientGeneration = 0L;
        activeDiscoverySessionGeneration = 0L;
        activeDiscoverySecurityEpoch = 0L;
        activeDiscoveryPublicationToken = 0L;
        activeDiscoveryChallenge = null;
        activeDiscoveryServerPeer = null;
        activeDiscoveryPhysicalFacade = null;
        activeDiscoveryPairFacade = null;
        activeDiscoveryDatabaseGeneration = 0L;
    }

    private void clearAcceptedDiscoveryLineage() {
        acceptedDiscoveryGatt = null;
        acceptedDiscoveryOperationGeneration = 0L;
        acceptedDiscoveryClientGeneration = 0L;
        acceptedDiscoverySessionGeneration = 0L;
        acceptedDiscoverySecurityEpoch = 0L;
        acceptedDiscoveryPublicationToken = 0L;
        acceptedDiscoveryChallenge = null;
        acceptedDiscoveryServerPeer = null;
        acceptedDiscoveryPhysicalFacade = null;
        acceptedDiscoveryPairFacade = null;
        acceptedDiscoveryDatabaseGeneration = 0L;
    }

    private void clearDiscoveryLineage() {
        clearActiveDiscoveryLineage();
        clearAcceptedDiscoveryLineage();
    }

    private void discoverServices(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt) return;
        long expectedGeneration = activeClientGeneration;
        long expectedSecurityEpoch = incomingSecurityEpoch;
        long publicationToken = publishedDiagnosticServicePublicationToken;
        if (managedIncomingMode) {
            boolean currentPublicationProof =
                    isCurrentDiagnosticServicePublicationToken(publicationToken)
                    && secureAttConfirmed
                    && incomingAncsReadyGateOpen
                    && secureAttPublicationToken == publicationToken
                    && incomingAncsReadyPublicationToken == publicationToken;
            boolean currentClientProof = gattClientConnected && activeClientEstablished
                    && sessionState.isCurrent(expectedGeneration)
                    && activeClientProvenSecurityEpoch != 0L
                    && activeClientProvenSecurityEpoch == incomingSecurityEpoch;
            if (!currentPublicationProof || !currentClientProof) {
                log("discoverServices заблокирован: нужен current F04 token + B3/READY "
                        + "+ client generation/security epoch; queued/stale вызов отброшен");
                return;
            }
        }
        if (!sessionState.isCurrent(expectedGeneration)) return;
        if (managedIncomingMode
                && (activeDescriptorWriteOperationGeneration != 0L
                || activeHelperProofWriteOperationGeneration != 0L
                || iphoneHelperTelemetryReadPending
                || activeRequest != null
                || batteryReadPendingUuid != null)) {
            log("discoverServices deferred: managed raw GATT callback "
                    + "slot is still active");
            return;
        }
        if (discoveryPending) {
            log("discoverServices уже выполняется");
            return;
        }
        discoveryPending = true;
        long discoveryOperation = armDiscoveryOperation(
                callbackGatt, expectedGeneration, publicationToken);
        boolean accepted;
        try {
            accepted = callbackGatt.discoverServices();
        } catch (RuntimeException failure) {
            accepted = false;
            log("discoverServices exception: " + failure);
        }
        if (!accepted) {
            discoveryPending = false;
            if (activeDiscoveryOperationGeneration == discoveryOperation) {
                clearActiveDiscoveryLineage();
            }
            if (managedIncomingMode) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "initial discovery did not start");
                return;
            }
            state("DISCOVERY_START_FAILED");
            return;
        }
        sessionState.move(expectedGeneration, AncsSessionStateMachine.Phase.DISCOVERING);
        BluetoothGatt expected = callbackGatt;
        discoveryTimeout = () -> {
            if (gatt != expected || !discoveryPending
                    || activeDiscoveryGatt != expected
                    || activeDiscoveryOperationGeneration != discoveryOperation) return;
            discoveryPending = false;
            clearActiveDiscoveryLineage();
            state("DISCOVERY_TIMEOUT");
            log("onServicesDiscovered не получен за "
                    + DISCOVERY_TIMEOUT_MS + " ms");
            poisonDiscoveryChannelAndRecover(expected, expectedGeneration,
                    expectedSecurityEpoch, publicationToken,
                    "onServicesDiscovered timeout");
        };
        main.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
        state("GATT DISCOVERY");
        log("discoverServices accepted=" + accepted);
    }

    /**
     * Performs a tiny encrypted exchange with the iPhone app before looking for ANCS. This both
     * identifies the peer and gives SMP a reason to create/restore the LE bond on the exact link
     * that Android opened as central.
     */
    private boolean startIphonePeripheralSecurity(BluetoothGatt callbackGatt) {
        BluetoothGattService diagnostic = callbackGatt.getService(DIAGNOSTIC_SERVICE);
        if (diagnostic == null) {
            state("GPS-LINK · TEST SERVICE НЕ НАЙДЕН");
            log("Подключение состоялось, но service " + DIAGNOSTIC_SERVICE
                    + " отсутствует. Убедитесь, что запущен Helper v4");
            return true;
        }

        iphoneSecureCharacteristic =
                diagnostic.getCharacteristic(SECURE_CHARACTERISTIC);
        BluetoothGattCharacteristic pair =
                diagnostic.getCharacteristic(CONTROL_CHARACTERISTIC);
        if (iphoneSecureCharacteristic == null) {
            state("GPS-LINK · SECURE CHAR НЕ НАЙДЕН");
            log("Helper не опубликовал SECURE " + SECURE_CHARACTERISTIC);
            return true;
        }
        if (iphonePairWritePending || iphoneSecureReadPending) return true;

        if (!iphonePairAttempted && pair != null) {
            iphonePairAttempted = true;
            pair.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            pair.setValue("PAIR".getBytes(StandardCharsets.UTF_8));
            boolean started;
            try {
                started = callbackGatt.writeCharacteristic(pair);
            } catch (RuntimeException failure) {
                started = false;
                log("GPS-style PAIR write exception: " + failure);
            }
            iphonePairWritePending = started;
            log("GPS-style WRITE PAIR started=" + started);
            if (started) {
                state("GPS-LINK · ПОДТВЕРЖДАЮ IPHONE");
                return true;
            }
        }

        readIphoneSecure(callbackGatt);
        return true;
    }

    private void readIphoneSecure(BluetoothGatt callbackGatt) {
        if (!iphonePeripheralMode || callbackGatt != gatt
                || !gattClientConnected || iphoneSecureReadPending
                || iphoneSecureConfirmed || iphoneSecureCharacteristic == null) {
            return;
        }
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(iphoneSecureCharacteristic);
        } catch (RuntimeException failure) {
            started = false;
            log("GPS-style SECURE read exception: " + failure);
        }
        iphoneSecureReadPending = started;
        log("GPS-style READ SECURE started=" + started
                + " bond=" + bondLabel(safeBondState(callbackGatt.getDevice())));
        if (started) {
            state("GPS-LINK · ПРОВЕРЯЮ ШИФРОВАНИЕ");
        } else {
            state("GPS-LINK · SECURE READ START FAILED");
        }
    }

    private void scheduleIphonePostSecureDiscovery(BluetoothGatt callbackGatt) {
        if (!canRunIphonePostSecureDiscovery(callbackGatt)
                || iphonePostSecureDiscoveryScheduled) return;
        long expectedClientGeneration = activeClientGeneration;
        long scheduledToken = ++iphonePostSecureDiscoveryToken;
        iphonePostSecureDiscoveryScheduled = true;
        iphonePostSecureDiscovery = () -> {
            if (scheduledToken != iphonePostSecureDiscoveryToken) return;
            // The latch represents one queued runnable, never the whole connection lifetime.
            // Release it before a busy retry so the successor can be scheduled exactly once.
            iphonePostSecureDiscovery = null;
            iphonePostSecureDiscoveryScheduled = false;
            if (expectedClientGeneration != activeClientGeneration
                    || !sessionState.isCurrent(expectedClientGeneration)
                    || !canRunIphonePostSecureDiscovery(callbackGatt)
                    || gattReady || iphoneAncsSeen) return;
            if (hasSerializedGattOperationInFlight()) {
                log("Post-secure discovery ждёт свободный serialized GATT channel");
                scheduleIphonePostSecureDiscovery(callbackGatt);
                return;
            }
            log("SECURE IPHONE OK; повторяю полный discovery и ищу ANCS 7905…");
            discoverServices(callbackGatt);
        };
        main.postDelayed(iphonePostSecureDiscovery, GPS_POST_SECURE_DISCOVERY_DELAY_MS);
    }

    private boolean canRunIphonePostSecureDiscovery(BluetoothGatt callbackGatt) {
        if (!iphonePeripheralMode || callbackGatt != gatt || !gattClientConnected
                || !activeClientEstablished
                || activeClientTarget == null
                || !sameDevice(activeClientTarget, callbackGatt.getDevice())
                || !sessionState.isCurrent(activeClientGeneration)) return false;
        if (helperBootstrapMode) return iphoneSecureConfirmed;
        return safeBondState(callbackGatt.getDevice()) == BluetoothDevice.BOND_BONDED;
    }

    private boolean hasSerializedGattOperationInFlight() {
        return discoveryPending || activeDiscoveryOperationGeneration != 0L
                || descriptorStage != DescriptorStage.NONE
                || activeDescriptorWriteOperationGeneration != 0L
                || activeHelperProofWriteOperationGeneration != 0L
                || activeRequest != null || iphonePairWritePending
                || iphoneSecureReadPending || iphoneHelperTelemetryReadPending
                || helperAncsReadyProofPending || batteryReadPendingUuid != null;
    }

    private void cancelIphonePostSecureDiscovery() {
        if (iphonePostSecureDiscovery != null) {
            main.removeCallbacks(iphonePostSecureDiscovery);
        }
        iphonePostSecureDiscoveryToken++;
        iphonePostSecureDiscovery = null;
        iphonePostSecureDiscoveryScheduled = false;
    }

    private void scheduleAutoAncsWaitTimeout(BluetoothGatt expected) {
        scheduleAutoAncsWaitTimeout(expected, AUTO_ANCS_WAIT_TIMEOUT_MS);
    }

    private void scheduleAutoAncsWaitTimeout(BluetoothGatt expected, long delayMs) {
        if (helperBootstrapMode || expected == null
                || !canRunIphonePostSecureDiscovery(expected)
                || autoAncsWaitTimeout != null) {
            return;
        }
        long expectedClientGeneration = activeClientGeneration;
        long scheduledToken = ++autoAncsWaitToken;
        autoAncsWaitTimeout = () -> {
            if (scheduledToken != autoAncsWaitToken) return;
            autoAncsWaitTimeout = null;
            if (helperBootstrapMode || expectedClientGeneration != activeClientGeneration
                    || !sessionState.isCurrent(expectedClientGeneration)
                    || !canRunIphonePostSecureDiscovery(expected) || gattReady) {
                return;
            }
            if (hasSerializedGattOperationInFlight()) {
                log("ANCS wait watchdog ждёт свободный serialized GATT channel; "
                        + "B4/descriptor/request не прерываются");
                scheduleAutoAncsWaitTimeout(expected, HELPER_TELEMETRY_BUSY_RETRY_MS);
                return;
            }
            state("AUTO LINK OK · ANCS REDISCOVERY");
            log("ANCS/Service Changed пока не опубликованы за "
                    + AUTO_ANCS_WAIT_TIMEOUT_MS
                    + " ms; сохраняю живой encrypted link и повторяю discovery");
            discoverServices(expected);
        };
        main.postDelayed(autoAncsWaitTimeout, Math.max(1L, delayMs));
        log("ANCS wait watchdog=" + delayMs
                + " ms; Helper fallback внутри active daily link не запускается");
    }

    private void cancelAutoAncsWaitTimeout() {
        if (autoAncsWaitTimeout != null) main.removeCallbacks(autoAncsWaitTimeout);
        autoAncsWaitToken++;
        autoAncsWaitTimeout = null;
    }

    private void handleServicesDiscoveredCallback(BluetoothGatt callbackGatt, int status) {
        if (!acceptsCurrentManagedIncomingCallback(
                callbackGatt, "onServicesDiscovered")) return;
        long operationGeneration = activeDiscoveryOperationGeneration;
        boolean exactRawSlot = callbackGatt == gatt && discoveryPending
                && activeDiscoveryGatt == callbackGatt && operationGeneration != 0L;
        if (!exactRawSlot) {
            log("Игнорирую late/stale onServicesDiscovered: exact discovery op/epoch "
                    + "не совпал");
            return;
        }
        if (!ownsDiscoveryOperation(callbackGatt, operationGeneration)) {
            cancelDiscoveryTimeout();
            discoveryPending = false;
            clearActiveDiscoveryLineage();
            incomingDiscoveryStarted = false;
            log("Discard-only onServicesDiscovered drained: старый epoch/publication "
                    + "не может мутировать текущую ANCS session");
            maybeStartIncomingAncsDiscovery(callbackGatt,
                    "stale discovery callback slot drained");
            return;
        }
        cancelDiscoveryTimeout();
        discoveryPending = false;
        acceptDiscoveryLineage();
        processDiscoveredServices(callbackGatt, status);
    }

    private void continueDiscoveredServiceSetup(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt || !ownsAcceptedDiscoveryLineage(callbackGatt)) {
            log("Игнорирую stale resume cached services: discovery lineage/epoch не совпал");
            return;
        }
        processDiscoveredServices(callbackGatt, GATT_SUCCESS);
    }

    private void processDiscoveredServices(BluetoothGatt callbackGatt, int status) {
        log("onServicesDiscovered status=" + status);
        if (status != GATT_SUCCESS) {
            clearAcceptedDiscoveryLineage();
            if (managedIncomingMode) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "accepted discovery status=" + status);
                return;
            }
            state("DISCOVERY_FAILED_" + status);
            return;
        }
        List<BluetoothGattService> services = callbackGatt.getServices();
        log("GATT services: " + services.size());
        for (BluetoothGattService service : services) {
            log("SERVICE " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  CHAR " + characteristic.getUuid()
                        + " props=0x" + Integer.toHexString(characteristic.getProperties()));
            }
        }
        if (helperTelemetryClientEnabled()) {
            BluetoothGattService relay = callbackGatt.getService(TELEMETRY_RELAY_SERVICE);
            BluetoothGattService helper = callbackGatt.getService(DIAGNOSTIC_SERVICE);
            iphoneSecureCharacteristic = iphonePeripheralMode && helper != null
                    ? helper.getCharacteristic(SECURE_CHARACTERISTIC) : null;
            BluetoothGattCharacteristic discoveredTelemetry = relay == null ? null
                    : relay.getCharacteristic(TELEMETRY_RELAY_CHARACTERISTIC);
            if (discoveredTelemetry == null && helper != null) {
                discoveredTelemetry = helper.getCharacteristic(TELEMETRY_CHARACTERISTIC);
            }
            BluetoothGattCharacteristic previousTelemetry =
                    iphoneTelemetryCharacteristic;
            UUID previousTelemetryUuid = previousTelemetry == null ? null
                    : previousTelemetry.getUuid();
            UUID discoveredTelemetryUuid = discoveredTelemetry == null ? null
                    : discoveredTelemetry.getUuid();
            if (!Objects.equals(previousTelemetryUuid, discoveredTelemetryUuid)) {
                iphoneHelperTelemetrySubscriptionAttempted = false;
                iphoneHelperTelemetrySubscribed = false;
            }
            iphoneTelemetryCharacteristic = discoveredTelemetry;
            log("Helper telemetry endpoint="
                    + (TELEMETRY_RELAY_CHARACTERISTIC.equals(discoveredTelemetryUuid)
                    ? "B4 relay generation 5 on ANCS owner"
                    : iphoneTelemetryCharacteristic != null
                    ? "bootstrap B4 generation 4" : "legacy TEL2/B3"));

            if (managedIncomingMode) {
                if (previousTelemetry != null && previousTelemetry != discoveredTelemetry) {
                    clearManagedLinkBoundProof();
                    iphoneHelperTelemetrySubscriptionAttempted = false;
                    iphoneHelperTelemetrySubscribed = false;
                    log("F05 wrapper identity changed; LINK-BOUND proof invalidated");
                }
                boolean exactF05 = relay != null && discoveredTelemetry != null
                        && TELEMETRY_RELAY_CHARACTERISTIC.equals(
                        discoveredTelemetry.getUuid())
                        && acceptedDiscoveryChallenge != null
                        && Arrays.equals(acceptedDiscoveryChallenge,
                        incomingClientAttemptChallenge);
                if (!exactF05) {
                    terminalManagedLinkBindingFailure(callbackGatt,
                            "accepted discovery missing exact F05/B4 + Q lineage");
                    return;
                }
                if (!hasCurrentManagedLinkBoundProof(callbackGatt)) {
                    if (!iphoneHelperTelemetrySubscribed) {
                        if (!iphoneHelperTelemetrySubscriptionAttempted
                                && descriptorStage == DescriptorStage.NONE) {
                            startOptionalHelperTelemetrySubscription(callbackGatt);
                        }
                        return;
                    }
                    startManagedLinkBoundProof(callbackGatt);
                    return;
                }
            }

            // Helper v9+ deliberately makes B4 readable before ANCS authorization. Read one
            // atomic snapshot before touching an encrypted ANCS CCCD: otherwise a pending
            // pairing callback can occupy Android's serialized GATT queue for up to 90 seconds
            // and make battery/network appear absent despite a healthy Helper service.
            if (iphoneTelemetryCharacteristic != null
                    && !managedIncomingMode
                    && !iphoneHelperInitialReadAttempted && !gattReady) {
                iphoneHelperInitialReadAttempted = true;
                iphoneServiceSetupDeferredForHelperRead = true;
                if (startHelperTelemetryRead(callbackGatt)) {
                    log("Helper B4 initial snapshot started before ANCS subscriptions");
                    return;
                }
                iphoneServiceSetupDeferredForHelperRead = false;
                log("Helper B4 initial snapshot could not start; continuing ANCS setup");
            }

            // Enable the unencrypted B4 notification before any encrypted ANCS CCCD can occupy
            // Android 9's serialized GATT queue. Battery percentage, cable state and radio type
            // therefore stay live even while the first ANCS authorization is still pending.
            if (iphoneTelemetryCharacteristic != null
                    && iphonePeripheralMode
                    && !iphoneHelperTelemetrySetupBypass
                    && !iphoneHelperTelemetrySubscribed
                    && !iphoneHelperTelemetrySubscriptionAttempted
                    && descriptorStage == DescriptorStage.NONE && !gattReady) {
                if (startOptionalHelperTelemetrySubscription(callbackGatt)) {
                    log("Helper B4 realtime subscription started before ANCS subscriptions");
                    return;
                }
            }
        }
        // HA1122 exposed BAS even while iOS had not published ANCS yet. Prepare the optional
        // battery work immediately, then serialize it behind any Service Changed/ANCS CCCD.
        prepareBatteryBootstrap(callbackGatt);

        BluetoothGattService ancs = callbackGatt.getService(AncsProtocol.SERVICE);
        if (ancs != null) {
            cancelAutoAncsWaitTimeout();
            iphoneAncsSeen = true;
            if (gattReady) {
                log("ANCS уже READY; проверяю появившийся Helper TEL3 без перезапуска ANCS");
                if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                    scheduleHelperTelemetryRecovery(callbackGatt,
                            HELPER_TELEMETRY_BUSY_RETRY_MS);
                    sendNextRequest();
                }
                return;
            }
            if (descriptorStage != DescriptorStage.NONE) {
                log("ANCS-подписка уже выполняется: " + descriptorStage);
                return;
            }
            if (managedIncomingMode
                    && !hasCurrentManagedLinkBoundProof(callbackGatt)) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "ANCS discovered before exact LINK-BOUND Q acknowledgement");
                return;
            }
            if (iphonePeripheralMode && !iphoneSecureConfirmed) {
                state("ANCS НАЙДЕН · SECURE TEST ПРОПУЩЕН");
                log("ANCS 7905… опубликован уже в первом discovery. "
                        + "PAIR/SECURE D2D…B3 не выполняются");
            }

            notificationSource = ancs.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE);
            dataSource = ancs.getCharacteristic(AncsProtocol.DATA_SOURCE);
            controlPoint = ancs.getCharacteristic(AncsProtocol.CONTROL_POINT);
            if (notificationSource == null || dataSource == null || controlPoint == null) {
                state("ANCS_INCOMPLETE");
                log("ANCS найден, но обязательные для теста характеристики отсутствуют"
                        + " NS=" + (notificationSource != null)
                        + " DS=" + (dataSource != null)
                        + " CP=" + (controlPoint != null));
                return;
            }
            earlyNotificationSourceFrames.clear();
            state("ANCS-FIRST · ПОДПИСКА NOTIFICATION SOURCE");
            log("ANCS найден. Сначала включаю обязательную Notification Source, "
                    + "затем Data Source; ранние события буферизуются");
            descriptorStage = DescriptorStage.NOTIFICATION_SOURCE;
            sessionState.move(activeClientGeneration,
                    AncsSessionStateMachine.Phase.SUBSCRIBING);
            if (!subscribe(callbackGatt, notificationSource, false)) {
                descriptorStage = DescriptorStage.NONE;
            }
            return;
        }

        if (iphonePeripheralMode && helperBootstrapMode && !iphoneSecureConfirmed) {
            log("ANCS в первом discovery отсутствует; только теперь запускаю "
                    + "fallback SECURE test Helper");
            startIphonePeripheralSecurity(callbackGatt);
            return;
        }

        if (managedIncomingMode) {
            state("SAME-OWNER LINK · ЖДУ SERVICE CHANGED / ANCS");
            log("ANCS пока не опубликован после RequiresANCS + B3 + same-owner READY; "
                    + "current BluetoothGatt owner/epoch сохранены, polling/reconnect не нужны");
            subscribeServiceChangedIfAvailable(callbackGatt);
            if (descriptorStage == DescriptorStage.NONE) sendNextRequest();
            return;
        }

        if (iphonePeripheralMode && helperBootstrapMode && iphoneSecureConfirmed) {
            state("GPS-LINK OK · POST-SECURE ANCS REDISCOVERY");
            log("Cached pre-secure services не содержат ANCS; планирую один serialized "
                    + "post-secure discovery на том же owner");
            scheduleIphonePostSecureDiscovery(callbackGatt);
            return;
        }

        if (iphonePeripheralMode && !helperBootstrapMode) {
            state("AUTO LINK OK · ЖДУ SERVICE CHANGED / ANCS");
            log("Helper B4 уже доступен на daily link; ANCS может появиться позднее через "
                    + "Service Changed. PAIR/SECURE bootstrap не запускаю");
            scheduleAutoAncsWaitTimeout(callbackGatt);
        } else {
            state(iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS НЕ ОПУБЛИКОВАН"
                    : "CONNECTED · ANCS НЕ НАЙДЕН");
        }
        log("Сервис ANCS 7905… отсутствует на этом BLE link"
                + (iphonePeripheralMode
                ? " после прямого Android-central подключения"
                : ""));
        // ANCS may be published only after the current ACL becomes encrypted. Service Changed is
        // the protocol signal for that transition; polling discoverServices once per second only
        // re-reads Android 9's same cache and can overwrite the useful beginning of diagnostics.
        subscribeServiceChangedIfAvailable(callbackGatt);
        sendNextRequest();
    }

    private void subscribeServiceChangedIfAvailable(BluetoothGatt callbackGatt) {
        BluetoothGattService generic = callbackGatt.getService(GENERIC_ATTRIBUTE_SERVICE);
        serviceChanged = generic == null ? null : generic.getCharacteristic(SERVICE_CHANGED);
        if (serviceChanged == null) {
            log("Service Changed 0x2A05 отсутствует; остаюсь ждать ANCS");
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ANCS/2A05 ПОКА НЕТ"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS/2A05 НЕТ"
                    : "ЖДУ ANCS НА SAME-PEER LINK");
            return;
        }
        descriptorStage = DescriptorStage.SERVICE_CHANGED;
        if (!subscribe(callbackGatt, serviceChanged, true)) {
            descriptorStage = DescriptorStage.NONE;
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ЖДУ ANCS"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS НЕ ОПУБЛИКОВАН"
                    : "ЖДУ ANCS НА SAME-PEER LINK");
        }
    }

    /** Arms one exact descriptor callback slot before the raw write reaches Bluetooth. */
    private long armDescriptorWriteOperation(@NonNull BluetoothGatt owner,
                                             @NonNull BluetoothGattDescriptor descriptor) {
        descriptorWriteOperationGeneration++;
        if (descriptorWriteOperationGeneration == 0L) descriptorWriteOperationGeneration++;
        activeDescriptorWriteOperationGeneration = descriptorWriteOperationGeneration;
        activeDescriptorWrite = descriptor;
        activeDescriptorWriteGatt = owner;
        activeDescriptorWriteClientGeneration = activeClientGeneration;
        activeDescriptorWriteSessionGeneration = sessionGeneration;
        activeDescriptorWriteSecurityEpoch = incomingSecurityEpoch;
        activeDescriptorWritePublicationToken = managedIncomingMode
                ? publishedDiagnosticServicePublicationToken : 0L;
        activeDescriptorWriteChallenge = managedIncomingMode
                && acceptedDiscoveryChallenge != null
                ? acceptedDiscoveryChallenge.clone() : null;
        activeDescriptorWriteServerPeer = managedIncomingMode
                ? acceptedDiscoveryServerPeer : null;
        activeDescriptorWritePhysicalFacade = managedIncomingMode
                ? acceptedDiscoveryPhysicalFacade : null;
        activeDescriptorWritePairFacade = managedIncomingMode
                ? acceptedDiscoveryPairFacade : null;
        activeDescriptorWriteDatabaseGeneration = managedIncomingMode
                ? acceptedDiscoveryDatabaseGeneration : 0L;
        return activeDescriptorWriteOperationGeneration;
    }

    private boolean ownsDescriptorWriteOperation(@NonNull BluetoothGatt owner,
                                                  @NonNull BluetoothGattDescriptor descriptor,
                                                  long operationGeneration) {
        if (operationGeneration == 0L
                || activeDescriptorWriteOperationGeneration != operationGeneration
                || activeDescriptorWriteGatt != owner || activeDescriptorWrite != descriptor
                || activeDescriptorWriteClientGeneration != activeClientGeneration
                || !sessionState.isCurrent(activeDescriptorWriteClientGeneration)) return false;
        return !managedIncomingMode
                || (ownsManagedAttemptLineage(
                owner, activeDescriptorWriteChallenge,
                activeDescriptorWriteServerPeer,
                activeDescriptorWritePhysicalFacade,
                activeDescriptorWritePairFacade,
                activeDescriptorWriteSessionGeneration,
                activeDescriptorWriteSecurityEpoch,
                activeDescriptorWritePublicationToken,
                activeDescriptorWriteClientGeneration)
                && activeDescriptorWriteDatabaseGeneration
                == managedF05DatabaseGeneration
                && ownsAcceptedDiscoveryLineage(owner)
                && isCurrentDiagnosticServicePublicationToken(
                activeDescriptorWritePublicationToken)
                && secureAttPublicationToken == activeDescriptorWritePublicationToken
                && incomingAncsReadyPublicationToken
                == activeDescriptorWritePublicationToken);
    }

    private void clearDescriptorWriteOperation() {
        activeDescriptorWrite = null;
        activeDescriptorWriteGatt = null;
        activeDescriptorWriteOperationGeneration = 0L;
        activeDescriptorWriteClientGeneration = 0L;
        activeDescriptorWriteSessionGeneration = 0L;
        activeDescriptorWriteSecurityEpoch = 0L;
        activeDescriptorWritePublicationToken = 0L;
        activeDescriptorWriteChallenge = null;
        activeDescriptorWriteServerPeer = null;
        activeDescriptorWritePhysicalFacade = null;
        activeDescriptorWritePairFacade = null;
        activeDescriptorWriteDatabaseGeneration = 0L;
    }

    private boolean subscribe(BluetoothGatt callbackGatt,
                              BluetoothGattCharacteristic characteristic,
                              boolean indication) {
        if (managedIncomingMode
                && (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE
                || descriptorStage == DescriptorStage.DATA_SOURCE)
                && !hasCurrentManagedLinkBoundProof(callbackGatt)) {
            descriptorStage = DescriptorStage.NONE;
            terminalManagedLinkBindingFailure(callbackGatt,
                    "ANCS CCCD attempted without exact LINK-BOUND Q proof");
            return false;
        }
        boolean optional = descriptorStage == DescriptorStage.SERVICE_CHANGED;
        optional = optional || descriptorStage == DescriptorStage.HELPER_TELEMETRY
                && !managedIncomingMode;
        String optionalName = descriptorStage == DescriptorStage.HELPER_TELEMETRY
                ? "Helper TEL3" : "Service Changed";
        boolean local;
        try {
            local = callbackGatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException failure) {
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName
                        + " local subscription exception: " + failure);
            } else {
                state("SUBSCRIBE_EXCEPTION");
                log("setCharacteristicNotification exception: " + failure);
            }
            return false;
        }
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        log("setCharacteristicNotification " + shortUuid(characteristic.getUuid())
                + "=" + local + "; CCCD=" + (cccd != null));
        if (!local || cccd == null) {
            if (optional) {
                descriptorStage = DescriptorStage.NONE;
                log("Optional " + optionalName
                        + " unavailable locally; ANCS link remains alive");
            } else {
                state("SUBSCRIBE_LOCAL_FAILED");
            }
            return false;
        }
        cccd.setValue(indication
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        long descriptorOperation = armDescriptorWriteOperation(callbackGatt, cccd);
        boolean started;
        try {
            started = callbackGatt.writeDescriptor(cccd);
        } catch (RuntimeException failure) {
            if (ownsDescriptorWriteOperation(callbackGatt, cccd, descriptorOperation)) {
                clearDescriptorWriteOperation();
            }
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName + " CCCD exception: " + failure);
            } else {
                state("CCCD_WRITE_EXCEPTION");
                log("writeDescriptor exception: " + failure);
            }
            return false;
        }
        log("writeDescriptor " + shortUuid(characteristic.getUuid())
                + " started=" + started);
        if (!started) {
            if (ownsDescriptorWriteOperation(callbackGatt, cccd, descriptorOperation)) {
                clearDescriptorWriteOperation();
            }
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName
                        + " CCCD was rejected; ANCS link remains alive");
            } else {
                state("CCCD_START_FAILED");
            }
        } else {
            scheduleDescriptorWriteTimeout(callbackGatt, cccd, descriptorOperation,
                    descriptorStage, characteristic.getUuid());
        }
        return started;
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt,
                                       BluetoothGattDescriptor descriptor, int status) {
        if (callbackGatt != gatt) return;
        if (!acceptsCurrentManagedIncomingCallback(callbackGatt, "onDescriptorWrite")) return;
        long operationGeneration = activeDescriptorWriteOperationGeneration;
        if (descriptor == null || !ownsDescriptorWriteOperation(
                callbackGatt, descriptor, operationGeneration)) {
            log("Игнорирую late/stale onDescriptorWrite: exact descriptor/op owner не совпал");
            return;
        }
        UUID characteristicUuid = descriptor.getCharacteristic() == null
                ? null : descriptor.getCharacteristic().getUuid();
        if (!descriptorMatchesStage(descriptorStage, characteristicUuid)) {
            log("Игнорирую устаревший onDescriptorWrite "
                    + shortUuid(characteristicUuid) + " stage=" + descriptorStage);
            return;
        }
        cancelDescriptorWriteTimeout();
        clearDescriptorWriteOperation();
        log("onDescriptorWrite " + shortUuid(characteristicUuid)
                + " status=" + status + " stage=" + descriptorStage);
        if (isBatteryDescriptorStage(descriptorStage)) {
            DescriptorStage completedStage = descriptorStage;
            descriptorStage = DescriptorStage.NONE;
            if (status == GATT_SUCCESS) {
                log("BAS notification subscription enabled · " + completedStage);
            } else {
                log("BAS optional CCCD skipped · " + completedStage
                        + " status=" + status);
            }
            sendNextRequest();
            return;
        }
        if (descriptorStage == DescriptorStage.HELPER_TELEMETRY) {
            descriptorStage = DescriptorStage.NONE;
            iphoneHelperTelemetrySubscribed = status == GATT_SUCCESS;
            if (status != GATT_SUCCESS) {
                iphoneHelperTelemetrySubscriptionAttempted = false;
            }
            if (managedIncomingMode && status != GATT_SUCCESS) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "mandatory F05/B4 CCCD status=" + status);
                return;
            }
            log(status == GATT_SUCCESS
                    ? "Helper telemetry notification subscription enabled"
                    : "Helper telemetry optional CCCD skipped · status=" + status);
            continueAfterHelperTelemetrySubscription(callbackGatt);
            return;
        }
        if (status != GATT_SUCCESS) {
            DescriptorStage failedStage = descriptorStage;
            descriptorStage = DescriptorStage.NONE;
            if (failedStage == DescriptorStage.SERVICE_CHANGED) {
                log("Optional Service Changed CCCD skipped, status=" + status
                        + "; mandatory ANCS path remains active");
                state(iphonePeripheralMode && !helperBootstrapMode
                        ? "AUTO LINK OK · ЖДУ ANCS"
                        : "ЖДУ ANCS БЕЗ SERVICE CHANGED");
                sendNextRequest();
                return;
            }
            boolean mandatoryAncsStage = failedStage == DescriptorStage.NOTIFICATION_SOURCE
                    || failedStage == DescriptorStage.DATA_SOURCE;
            if (managedIncomingMode && mandatoryAncsStage
                    && status == STATUS_GATT_ERROR) {
                gattReady = false;
                scheduleMandatoryDescriptorStatus133Retry(callbackGatt, failedStage);
                return;
            }
            boolean iphonePermissionDenied =
                    failedStage == DescriptorStage.NOTIFICATION_SOURCE
                            && status == STATUS_WRITE_NOT_PERMITTED;
            if (iphonePermissionDenied) {
                ancsAuthorizationFailureSeen = true;
                state("ANCS НЕ РАЗРЕШЕН · ВКЛЮЧИТЕ УВЕДОМЛЕНИЯ НА IPHONE");
                log("Notification Source CCCD отклонён ATT status=3: "
                        + "iOS не разрешил ANCS этому RequiresANCS owner. "
                        + "Физический link и BluetoothGatt owner сохраняются");
                if (managedIncomingMode) {
                    waitForIncomingAncsAuthorizationEvent(callbackGatt,
                            "Notification Source CCCD status=3");
                    return;
                }
                scheduleAncsPermissionRetry(callbackGatt);
                return;
            }
            if (isAuthorizationError(status)) {
                ancsRetryAfterBond = true;
                ancsAuthorizationFailureSeen = true;
                int bondState = safeBondState(callbackGatt.getDevice());
                state("ANCS AUTH FAIL 0x"
                        + Integer.toHexString(status).toUpperCase(Locale.US)
                        + " · НУЖЕН LE BOND");
                log("ANCS CCCD " + failedStage + " требует authorization; status="
                        + status + " (0x"
                        + Integer.toHexString(status).toUpperCase(Locale.US)
                        + "), bond=" + bondLabel(bondState));
                if (managedIncomingMode) {
                    waitForIncomingAncsAuthorizationEvent(callbackGatt,
                            "ANCS CCCD " + failedStage + " status=" + status);
                    return;
                }
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    scheduleAncsRetryAfterBond(callbackGatt,
                            "CCCD вернул auth error уже после BOND_BONDED");
                } else if (!leBondAttemptObserved) {
                    log("Системный стек ещё не сообщил BONDING; "
                            + "запускаю одну явную LE bond-попытку");
                    requestBond(callbackGatt.getDevice());
                } else {
                    log("LE bond уже запускался; автоматический цикл pairing не повторяю");
                }
            } else {
                state("CCCD_FAILED_" + status);
            }
            return;
        }

        if (descriptorStage == DescriptorStage.SERVICE_CHANGED) {
            descriptorStage = DescriptorStage.NONE;
            log("Service Changed indication включена");
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ЖДУ SERVICE CHANGED / ANCS"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ЖДУ SERVICE CHANGED"
                    : "ЖДУ SERVICE CHANGED / ANCS");
            sendNextRequest();
        } else if (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE) {
            state("NOTIFICATION SOURCE OK · ПОДПИСКА DATA SOURCE");
            log("Notification Source CCCD включён; сериализованно включаю Data Source");
            descriptorStage = DescriptorStage.DATA_SOURCE;
            main.postDelayed(() -> {
                if (callbackGatt != gatt || !gattClientConnected
                        || descriptorStage != DescriptorStage.DATA_SOURCE) return;
                if (!subscribe(callbackGatt, dataSource, false)) {
                    descriptorStage = DescriptorStage.NONE;
                }
            }, ANCS_SECOND_CCCD_DELAY_MS);
        } else if (descriptorStage == DescriptorStage.DATA_SOURCE) {
            descriptorStage = DescriptorStage.NONE;
            gattReady = true;
            poisonedWrapperReplacementAttempt = 0;
            mandatoryDescriptorStatus133RetryCount = 0;
            ancsPermissionRetryCount = 0;
            if (ancsPermissionRetry != null) {
                main.removeCallbacks(ancsPermissionRetry);
                ancsPermissionRetry = null;
            }
            sessionState.move(activeClientGeneration, AncsSessionStateMachine.Phase.READY);
            cancelAutoAncsWaitTimeout();
            flushEarlyNotificationSourceFrames();
            state(managedIncomingMode
                    ? "ANCS CCCD OK · ЖДУ B4 ДАННЫЕ"
                    : "ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
            if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                finishAncsReadySetup(callbackGatt);
            }
        }
    }

    /**
     * The current owner already passed PAIR/B3 and same-owner ANCS-READY. A provisional iOS
     * permission result must therefore not create a disconnect/reconnect or discovery polling
     * loop. Keep the clientIf and wait for the protocol's Service Changed indication.
     */
    private void waitForIncomingAncsAuthorizationEvent(@NonNull BluetoothGatt expected,
                                                        @NonNull String reason) {
        if (expected != gatt || !gattClientConnected || !managedIncomingMode) return;
        ancsRetryAfterBond = false;
        if (ancsBondRetry != null) main.removeCallbacks(ancsBondRetry);
        if (ancsPermissionRetry != null) main.removeCallbacks(ancsPermissionRetry);
        ancsBondRetry = null;
        ancsPermissionRetry = null;
        state("SAME-OWNER LINK · ЖДУ IPHONE ANCS AUTHORIZATION EVENT");
        log("ANCS authorization пока provisional; exact BluetoothGatt owner и current epoch "
                + "сохранены, polling/reconnect не запускаются · " + reason);
        subscribeServiceChangedIfAvailable(expected);
        if (descriptorStage == DescriptorStage.NONE) sendNextRequest();
    }

    /** A real status=133 callback safely permits one same-owner rediscovery, never green state. */
    private void scheduleMandatoryDescriptorStatus133Retry(
            @NonNull BluetoothGatt expected, @NonNull DescriptorStage failedStage) {
        if (mandatoryDescriptorStatus133RetryCount >= 1) {
            state("ANCS CCCD STATUS 133 · LINK СОХРАНЁН");
            log("Mandatory CCCD status=133 retry уже использован; owner остаётся активным");
            return;
        }
        mandatoryDescriptorStatus133RetryCount++;
        long expectedGeneration = activeClientGeneration;
        long expectedSecurityEpoch = incomingSecurityEpoch;
        long expectedPublicationToken = publishedDiagnosticServicePublicationToken;
        state("ANCS CCCD STATUS 133 · RETRY #1");
        main.postDelayed(() -> {
            if (expected != gatt || !gattClientConnected || !activeClientEstablished
                    || gattReady || !sessionState.isCurrent(expectedGeneration)
                    || incomingSecurityEpoch != expectedSecurityEpoch
                    || !isCurrentDiagnosticServicePublicationToken(
                    expectedPublicationToken)
                    || secureAttPublicationToken != expectedPublicationToken
                    || incomingAncsReadyPublicationToken != expectedPublicationToken) return;
            resetBatteryBootstrap();
            log("Повторяю discovery один раз после real mandatory CCCD status=133 · "
                    + failedStage);
            discoverServices(expected);
        }, 800L);
    }

    private void flushEarlyNotificationSourceFrames() {
        while (gattReady && !earlyNotificationSourceFrames.isEmpty()) {
            byte[] frame = earlyNotificationSourceFrames.pollFirst();
            if (frame != null) handleNotificationSource(frame);
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt callbackGatt,
                                             BluetoothGattCharacteristic characteristic,
                                             @Nullable byte[] callbackValue) {
        if (callbackGatt != gatt) return;
        if (!acceptsCurrentManagedIncomingCallback(
                callbackGatt, "onCharacteristicChanged")) return;
        UUID uuid = characteristic.getUuid();
        if (managedIncomingMode
                && TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                && (!hasCurrentManagedLinkBoundProof(callbackGatt)
                || characteristic != helperLinkBoundCharacteristic)) {
            log("Pre-bind F05/B4 notification quarantined · bytes="
                    + (callbackValue == null ? 0 : callbackValue.length));
            return;
        }
        if (managedIncomingMode
                && (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)
                || AncsProtocol.DATA_SOURCE.equals(uuid))
                && (!hasCurrentManagedLinkBoundProof(callbackGatt)
                || AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)
                && characteristic != notificationSource
                || AncsProtocol.DATA_SOURCE.equals(uuid)
                && characteristic != dataSource)) {
            log("Pre-bind ANCS notification quarantined · bytes="
                    + (callbackValue == null ? 0 : callbackValue.length));
            return;
        }
        characteristic.setValue(callbackValue);
        byte[] value = callbackValue;
        log("onCharacteristicChanged " + shortUuid(uuid)
                + " bytes=" + AdvertisementParser.hex(value, 80));
        if (BATTERY_LEVEL.equals(uuid) || BATTERY_LEVEL_STATUS.equals(uuid)) {
            if (gattClientConnected && value != null) {
                listener.onBatteryCharacteristic(uuid, value.clone());
            }
            return;
        }
        if ((TELEMETRY_CHARACTERISTIC.equals(uuid)
                || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                || SECURE_CHARACTERISTIC.equals(uuid))
                && helperTelemetryClientEnabled()) {
            IphoneHelperTelemetry telemetry = IphoneHelperTelemetry.parse(value);
            if (telemetry != null) {
                acceptHelperTelemetryFrame(callbackGatt, telemetry, "notification");
                scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_POLL_MS);
            } else {
                log("Helper notification ignored: malformed TEL2/TEL3 frame");
            }
            return;
        }
        if (SERVICE_CHANGED.equals(uuid)) {
            if (helperTelemetryClientEnabled() && !helperBootstrapMode) {
                if (managedIncomingMode) {
                    boolean f05RawOperationInFlight =
                            descriptorStage == DescriptorStage.HELPER_TELEMETRY
                            && activeDescriptorWriteGatt == callbackGatt
                            && activeDescriptorWriteOperationGeneration != 0L
                            || activeHelperProofWriteGatt == callbackGatt
                            && activeHelperProofWriteOperationGeneration != 0L
                            || iphoneHelperTelemetryReadPending;
                    if (f05RawOperationInFlight) {
                        terminalManagedLinkBindingFailure(callbackGatt,
                                "Service Changed while old F05 raw callback slot in flight");
                        return;
                    }
                    managedF05DatabaseGeneration++;
                    if (managedF05DatabaseGeneration == 0L) {
                        managedF05DatabaseGeneration++;
                    }
                    clearManagedLinkBoundProof();
                    iphoneHelperTelemetrySubscriptionAttempted = false;
                    iphoneHelperTelemetrySubscribed = false;
                    iphoneHelperValidTelemetryReceived = false;
                    helperAncsReadyProofAttempted = false;
                    helperAncsReadyProofPending = false;
                    helperAncsReadyProofAcknowledged = false;
                    log("Service Changed invalidated F05 handles/LINK-BOUND Q · dbGeneration="
                            + managedF05DatabaseGeneration);
                }
                log("Рабочий ANCS GATT получил Service Changed; "
                        + "переоткрываю services на том же owner");
                restartDiscoveryOnPersistentOwner(callbackGatt, activeClientGeneration,
                        "SERVICE CHANGED indication");
                return;
            }
            log("Получен Service Changed; сбрасываю старые ANCS handles/очередь "
                    + "и повторяю discovery");
            clearAncsRuntime();
            main.postDelayed(() -> discoverServices(callbackGatt), 400L);
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)) {
            if (gattReady) {
                handleNotificationSource(value);
            } else if (value != null
                    && (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE
                    || descriptorStage == DescriptorStage.DATA_SOURCE)) {
                if (earlyNotificationSourceFrames.size()
                        >= MAX_EARLY_NOTIFICATION_SOURCE_FRAMES) {
                    earlyNotificationSourceFrames.pollFirst();
                }
                earlyNotificationSourceFrames.addLast(value.clone());
                log("Буферизую ранний Notification Source до обеих CCCD · pending="
                        + earlyNotificationSourceFrames.size());
            } else {
                log("Notification Source пришёл вне актуальной ANCS-подписки");
            }
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid)) {
            if (gattReady) {
                handleDataSource(value);
            } else {
                log("Игнорирую Data Source до актуального ANCS READY");
            }
        }
    }

    private static boolean descriptorMatchesStage(DescriptorStage stage, UUID uuid) {
        if (stage == null || uuid == null) return false;
        switch (stage) {
            case SERVICE_CHANGED:
                return SERVICE_CHANGED.equals(uuid);
            case HELPER_TELEMETRY:
                return TELEMETRY_CHARACTERISTIC.equals(uuid)
                        || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                        || SECURE_CHARACTERISTIC.equals(uuid);
            case DATA_SOURCE:
                return AncsProtocol.DATA_SOURCE.equals(uuid);
            case NOTIFICATION_SOURCE:
                return AncsProtocol.NOTIFICATION_SOURCE.equals(uuid);
            case BATTERY_LEVEL:
                return BATTERY_LEVEL.equals(uuid);
            case BATTERY_LEVEL_STATUS:
                return BATTERY_LEVEL_STATUS.equals(uuid);
            case NONE:
            default:
                return false;
        }
    }

    private static boolean isBatteryDescriptorStage(DescriptorStage stage) {
        return stage == DescriptorStage.BATTERY_LEVEL
                || stage == DescriptorStage.BATTERY_LEVEL_STATUS;
    }

    private long armHelperProofWriteOperation(
            @NonNull BluetoothGatt owner,
            @NonNull BluetoothGattCharacteristic characteristic,
            @NonNull byte[] challenge,
            @NonNull HelperProofWriteStage stage) {
        helperProofWriteOperationGeneration++;
        if (helperProofWriteOperationGeneration == 0L) {
            helperProofWriteOperationGeneration++;
        }
        activeHelperProofWriteStage = stage;
        activeHelperProofWriteGatt = owner;
        activeHelperProofWriteCharacteristic = characteristic;
        activeHelperProofWriteOperationGeneration =
                helperProofWriteOperationGeneration;
        activeHelperProofWriteClientGeneration = activeClientGeneration;
        activeHelperProofWriteSessionGeneration = sessionGeneration;
        activeHelperProofWriteSecurityEpoch = incomingSecurityEpoch;
        activeHelperProofWritePublicationToken =
                publishedDiagnosticServicePublicationToken;
        activeHelperProofWriteChallenge = challenge.clone();
        activeHelperProofWriteServerPeer = acceptedDiscoveryServerPeer;
        activeHelperProofWritePhysicalFacade = acceptedDiscoveryPhysicalFacade;
        activeHelperProofWritePairFacade = acceptedDiscoveryPairFacade;
        activeHelperProofWriteDatabaseGeneration =
                acceptedDiscoveryDatabaseGeneration;
        activeHelperProofWriteDiscoveryOperationGeneration =
                acceptedDiscoveryOperationGeneration;
        return activeHelperProofWriteOperationGeneration;
    }

    private boolean ownsHelperProofWriteOperation(
            @NonNull BluetoothGatt owner,
            @NonNull BluetoothGattCharacteristic characteristic,
            @NonNull HelperProofWriteStage stage,
            long operationGeneration) {
        return managedIncomingMode
                && operationGeneration != 0L
                && activeHelperProofWriteOperationGeneration == operationGeneration
                && activeHelperProofWriteStage == stage
                && activeHelperProofWriteGatt == owner
                && activeHelperProofWriteCharacteristic == characteristic
                && activeHelperProofWriteDiscoveryOperationGeneration
                == acceptedDiscoveryOperationGeneration
                && activeHelperProofWriteDatabaseGeneration
                == managedF05DatabaseGeneration
                && ownsAcceptedDiscoveryLineage(owner)
                && ownsManagedAttemptLineage(
                owner, activeHelperProofWriteChallenge,
                activeHelperProofWriteServerPeer,
                activeHelperProofWritePhysicalFacade,
                activeHelperProofWritePairFacade,
                activeHelperProofWriteSessionGeneration,
                activeHelperProofWriteSecurityEpoch,
                activeHelperProofWritePublicationToken,
                activeHelperProofWriteClientGeneration);
    }

    private void clearHelperProofWriteOperation() {
        if (helperProofWriteTimeout != null) {
            main.removeCallbacks(helperProofWriteTimeout);
        }
        helperProofWriteTimeout = null;
        activeHelperProofWriteStage = HelperProofWriteStage.NONE;
        activeHelperProofWriteGatt = null;
        activeHelperProofWriteCharacteristic = null;
        activeHelperProofWriteOperationGeneration = 0L;
        activeHelperProofWriteClientGeneration = 0L;
        activeHelperProofWriteSessionGeneration = 0L;
        activeHelperProofWriteSecurityEpoch = 0L;
        activeHelperProofWritePublicationToken = 0L;
        activeHelperProofWriteChallenge = null;
        activeHelperProofWriteServerPeer = null;
        activeHelperProofWritePhysicalFacade = null;
        activeHelperProofWritePairFacade = null;
        activeHelperProofWriteDatabaseGeneration = 0L;
        activeHelperProofWriteDiscoveryOperationGeneration = 0L;
    }

    private void clearManagedLinkBoundProof() {
        clearHelperProofWriteOperation();
        helperLinkBoundAcknowledged = false;
        helperLinkBoundGatt = null;
        helperLinkBoundCharacteristic = null;
        helperLinkBoundChallenge = null;
        helperLinkBoundServerPeer = null;
        helperLinkBoundPhysicalFacade = null;
        helperLinkBoundPairFacade = null;
        helperLinkBoundClientGeneration = 0L;
        helperLinkBoundSessionGeneration = 0L;
        helperLinkBoundSecurityEpoch = 0L;
        helperLinkBoundPublicationToken = 0L;
        helperLinkBoundDatabaseGeneration = 0L;
        helperLinkBoundDiscoveryOperationGeneration = 0L;
    }

    private boolean hasCurrentManagedLinkBoundProof(
            @NonNull BluetoothGatt owner) {
        return helperLinkBoundAcknowledged
                && helperLinkBoundGatt == owner
                && helperLinkBoundCharacteristic != null
                && helperLinkBoundCharacteristic == iphoneTelemetryCharacteristic
                && TELEMETRY_RELAY_CHARACTERISTIC.equals(
                helperLinkBoundCharacteristic.getUuid())
                && helperLinkBoundDatabaseGeneration == managedF05DatabaseGeneration
                && helperLinkBoundDiscoveryOperationGeneration
                == acceptedDiscoveryOperationGeneration
                && ownsAcceptedDiscoveryLineage(owner)
                && ownsManagedAttemptLineage(
                owner, helperLinkBoundChallenge, helperLinkBoundServerPeer,
                helperLinkBoundPhysicalFacade, helperLinkBoundPairFacade,
                helperLinkBoundSessionGeneration, helperLinkBoundSecurityEpoch,
                helperLinkBoundPublicationToken, helperLinkBoundClientGeneration);
    }

    private void terminalManagedLinkBindingFailure(
            @NonNull BluetoothGatt expected, @NonNull String reason) {
        if (!managedIncomingMode || gatt != expected) return;
        GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
        BluetoothDevice transportFacade = expected.getDevice();
        clearManagedLinkBoundProof();
        if (gatt == expected) {
            cancelConnectTimeout();
            cancelClientAttemptCallbacks();
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
        }
        boolean physicalLost = resetRetiredObserverAfterServerFacadeLoss(
                attemptPeer, transportFacade,
                "mandatory F05 LINK-BOUND failed after server facade loss · "
                        + reason);
        state(physicalLost
                ? "LINK-BOUND FAILED · PHYSICAL LINK LOST"
                : "LINK-BOUND FAILED · CLIENT CLOSED · LINK KEPT");
        log("HA1211 mandatory F05 proof terminal close-only; no retry/public fallback · "
                + reason);
    }

    private boolean startManagedLinkBoundProof(
            @NonNull BluetoothGatt callbackGatt) {
        if (!managedIncomingMode || callbackGatt != gatt
                || !ownsAcceptedDiscoveryLineage(callbackGatt)
                || !iphoneHelperTelemetrySubscribed
                || hasCurrentManagedLinkBoundProof(callbackGatt)
                || activeHelperProofWriteStage != HelperProofWriteStage.NONE) {
            return false;
        }
        BluetoothGattCharacteristic telemetry = iphoneTelemetryCharacteristic;
        byte[] challenge = acceptedDiscoveryChallenge;
        if (telemetry == null
                || !TELEMETRY_RELAY_CHARACTERISTIC.equals(telemetry.getUuid())
                || challenge == null
                || challenge.length != MANAGED_PROOF_FRAME_BYTES - 1
                || (telemetry.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            terminalManagedLinkBindingFailure(callbackGatt,
                    "F05/B4 missing exact WRITE_WITH_RESPONSE capability");
            return false;
        }
        telemetry.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        telemetry.setValue(managedProofFrame(MANAGED_LINK_BOUND_OPCODE, challenge));
        long operationGeneration = armHelperProofWriteOperation(
                callbackGatt, telemetry, challenge,
                HelperProofWriteStage.LINK_BOUND);
        boolean started;
        try {
            started = callbackGatt.writeCharacteristic(telemetry);
        } catch (RuntimeException failure) {
            started = false;
            log("LINK-BOUND Q write exception: " + failure);
        }
        if (!started) {
            if (ownsHelperProofWriteOperation(callbackGatt, telemetry,
                    HelperProofWriteStage.LINK_BOUND, operationGeneration)) {
                clearHelperProofWriteOperation();
            }
            terminalManagedLinkBindingFailure(callbackGatt,
                    "LINK-BOUND Q write did not start");
            return false;
        }
        helperProofWriteTimeout = () -> {
            if (!ownsHelperProofWriteOperation(callbackGatt, telemetry,
                    HelperProofWriteStage.LINK_BOUND, operationGeneration)) return;
            clearHelperProofWriteOperation();
            terminalManagedLinkBindingFailure(callbackGatt,
                    "LINK-BOUND Q write callback timeout");
        };
        main.postDelayed(helperProofWriteTimeout, 4_000L);
        state("F05 B4 CCCD OK · LINK-BOUND Q");
        log("HA1211 LINK-BOUND Q write-with-response started · op="
                + operationGeneration + " · physicalObjectId="
                + System.identityHashCode(activeHelperProofWritePhysicalFacade)
                + " · pairObjectId="
                + System.identityHashCode(activeHelperProofWritePairFacade));
        return true;
    }

    private boolean handleManagedHelperProofWriteCallback(
            @NonNull BluetoothGatt callbackGatt,
            @NonNull BluetoothGattCharacteristic characteristic,
            int status) {
        if (!managedIncomingMode
                || !TELEMETRY_RELAY_CHARACTERISTIC.equals(characteristic.getUuid())
                || activeHelperProofWriteStage == HelperProofWriteStage.NONE) {
            return false;
        }
        HelperProofWriteStage stage = activeHelperProofWriteStage;
        long operationGeneration = activeHelperProofWriteOperationGeneration;
        if (!ownsHelperProofWriteOperation(
                callbackGatt, characteristic, stage, operationGeneration)) {
            log("Late/stale F05 proof write callback quarantined · stage=" + stage
                    + " op=" + operationGeneration);
            return true;
        }
        if (status != GATT_SUCCESS) {
            if (stage == HelperProofWriteStage.ANCS_SUBSCRIBED) {
                helperAncsReadyProofPending = false;
                helperAncsReadyProofAttempted = false;
            }
            clearHelperProofWriteOperation();
            terminalManagedLinkBindingFailure(callbackGatt,
                    stage + " Q write status=" + status);
            return true;
        }
        if (stage == HelperProofWriteStage.LINK_BOUND) {
            BluetoothGattCharacteristic proofCharacteristic =
                    activeHelperProofWriteCharacteristic;
            byte[] proofChallenge = activeHelperProofWriteChallenge.clone();
            GattServerPeer proofPeer = activeHelperProofWriteServerPeer;
            BluetoothDevice proofPhysical = activeHelperProofWritePhysicalFacade;
            BluetoothDevice proofPair = activeHelperProofWritePairFacade;
            long proofClientGeneration = activeHelperProofWriteClientGeneration;
            long proofSessionGeneration = activeHelperProofWriteSessionGeneration;
            long proofSecurityEpoch = activeHelperProofWriteSecurityEpoch;
            long proofPublicationToken = activeHelperProofWritePublicationToken;
            long proofDatabaseGeneration = activeHelperProofWriteDatabaseGeneration;
            long proofDiscoveryOperation =
                    activeHelperProofWriteDiscoveryOperationGeneration;
            clearHelperProofWriteOperation();
            helperLinkBoundAcknowledged = true;
            helperLinkBoundGatt = callbackGatt;
            helperLinkBoundCharacteristic = proofCharacteristic;
            helperLinkBoundChallenge = proofChallenge;
            helperLinkBoundServerPeer = proofPeer;
            helperLinkBoundPhysicalFacade = proofPhysical;
            helperLinkBoundPairFacade = proofPair;
            helperLinkBoundClientGeneration = proofClientGeneration;
            helperLinkBoundSessionGeneration = proofSessionGeneration;
            helperLinkBoundSecurityEpoch = proofSecurityEpoch;
            helperLinkBoundPublicationToken = proofPublicationToken;
            helperLinkBoundDatabaseGeneration = proofDatabaseGeneration;
            helperLinkBoundDiscoveryOperationGeneration = proofDiscoveryOperation;
            log("HA1211 LINK-BOUND Q acknowledged on exact F05 owner · op="
                    + operationGeneration + " · ANCS discovery continuation allowed");
            state("LINK-BOUND OK · ANCS ALLOWED");
            continueDiscoveredServiceSetup(callbackGatt);
            return true;
        }
        helperAncsReadyProofPending = false;
        helperAncsReadyProofAcknowledged = true;
        clearHelperProofWriteOperation();
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
            helperAncsReadyProofRetry = null;
        }
        log("Helper подтвердил binary ANCS-SUBSCRIBED Q после обеих ANCS CCCD");
        state("ANCS READY · B4 VERIFIED · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
        finishAncsReadySetup(callbackGatt);
        return true;
    }

    /**
     * Helper v8 publishes one atomic TEL3 snapshot on B4. Older Helper v7 builds can still notify
     * the split TEL2 frames on B3. Both endpoints share the already-working Android-central ANCS
     * link, so telemetry never needs a second BLE connection.
     */
    private boolean startOptionalHelperTelemetrySubscription(BluetoothGatt callbackGatt) {
        BluetoothGattCharacteristic telemetry = helperTelemetryEndpoint();
        if (!helperTelemetryClientEnabled() || callbackGatt != gatt || telemetry == null
                || iphoneHelperTelemetrySubscribed
                || iphoneHelperTelemetrySubscriptionAttempted
                || descriptorStage != DescriptorStage.NONE) return false;
        if ((telemetry.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            if (managedIncomingMode) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "F05/B4 lacks mandatory NOTIFY");
                return false;
            }
            log("Helper telemetry endpoint has no NOTIFY; periodic encrypted READ remains active");
            iphoneHelperTelemetrySubscriptionAttempted = true;
            scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            return false;
        }
        iphoneHelperTelemetrySubscriptionAttempted = true;
        descriptorStage = DescriptorStage.HELPER_TELEMETRY;
        boolean started = subscribe(callbackGatt, telemetry, false);
        if (!started) {
            descriptorStage = DescriptorStage.NONE;
            iphoneHelperTelemetrySubscriptionAttempted = false;
            log(managedIncomingMode
                    ? "Mandatory F05/B4 subscription did not start"
                    : "Helper telemetry optional subscription did not start");
            if (managedIncomingMode) {
                terminalManagedLinkBindingFailure(callbackGatt,
                        "mandatory F05/B4 CCCD did not start");
                return false;
            }
            scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
        } else {
            state(gattReady ? "ANCS READY · ВКЛЮЧАЮ TEL3" : "GPS-LINK · ВКЛЮЧАЮ TEL3");
        }
        return started;
    }

    private BluetoothGattCharacteristic helperTelemetryEndpoint() {
        return iphoneTelemetryCharacteristic != null
                ? iphoneTelemetryCharacteristic : iphoneSecureCharacteristic;
    }

    /** Both client routes terminate on an iPhone-owned GATT database. */
    private boolean helperTelemetryClientEnabled() {
        return iphonePeripheralMode || managedIncomingMode;
    }

    private void continueAfterHelperTelemetrySubscription(BluetoothGatt callbackGatt) {
        if (managedIncomingMode && !hasCurrentManagedLinkBoundProof(callbackGatt)) {
            startManagedLinkBoundProof(callbackGatt);
            return;
        }
        scheduleHelperTelemetryRecovery(callbackGatt, 200L);
        AncsRecoveryPolicy.PostTelemetryAction action =
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        gattReady,
                        callbackGatt == gatt && ownsAcceptedDiscoveryLineage(callbackGatt),
                        iphonePeripheralMode,
                        canRunIphonePostSecureDiscovery(callbackGatt));
        if (action == AncsRecoveryPolicy.PostTelemetryAction.FINISH_READY) {
            finishAncsReadySetup(callbackGatt);
            return;
        }
        if (action == AncsRecoveryPolicy.PostTelemetryAction.CONTINUE_ACCEPTED_SERVICES) {
            // B4 is optional and already completed its raw descriptor callback. Resume the exact
            // accepted service snapshot, bypassing only another B4 attempt, so cached ANCS can
            // advance Notification Source -> Data Source without a competing discovery call.
            iphoneHelperTelemetrySetupBypass = true;
            try {
                continueDiscoveredServiceSetup(callbackGatt);
            } finally {
                iphoneHelperTelemetrySetupBypass = false;
            }
            return;
        }
        if (action == AncsRecoveryPolicy.PostTelemetryAction.REDISCOVER_CURRENT_OWNER) {
            scheduleIphonePostSecureDiscovery(callbackGatt);
        } else {
            log("Helper B4 завершён; ANCS continuation ждёт accepted discovery/SECURE proof");
        }
    }

    /** Records a real B4 payload, not merely service discovery or a CCCD callback. */
    private boolean acceptHelperTelemetryFrame(BluetoothGatt callbackGatt,
                                               @NonNull IphoneHelperTelemetry telemetry,
                                               String source) {
        if (managedIncomingMode
                && !hasCurrentManagedLinkBoundProof(callbackGatt)) {
            log("Pre-bind F05/B4 payload dropped before parse/listener effects");
            return false;
        }
        boolean validForReady = telemetry.kind == IphoneHelperTelemetry.Kind.SNAPSHOT
                && telemetry.batteryLevel != null
                && !telemetry.networkType.trim().isEmpty();
        boolean firstFrame = validForReady && !iphoneHelperValidTelemetryReceived;
        if (validForReady) iphoneHelperValidTelemetryReceived = true;
        listener.onHelperTelemetry(telemetry);
        if (shouldLogHelperTelemetry(telemetry)) {
            log("Helper B4 " + source + " accepted: kind=" + telemetry.kind
                    + " battery=" + telemetry.batteryLevel
                    + " externalPower=" + telemetry.externalPower
                    + " chargeState=" + telemetry.chargeState
                    + " network=" + (telemetry.networkType.isEmpty()
                    ? "unknown" : telemetry.networkType)
                    + " locked=" + telemetry.phoneLocked
                    + " seq=" + telemetry.sequence);
        }
        if (!validForReady) {
            log("Helper B4 payload принят для диагностики, но READY запрещён: нужны "
                    + "валидные battery + network в одном SNAPSHOT");
            return false;
        }
        if (firstFrame) {
            log("Helper B4 battery+network proof confirmed");
        }
        if (!managedIncomingMode || !gattReady || helperAncsReadyProofAcknowledged) {
            return false;
        }
        return startHelperAncsReadyProof(callbackGatt);
    }

    private void finishAncsReadySetup(BluetoothGatt callbackGatt) {
        if (managedIncomingMode && !iphoneHelperValidTelemetryReceived) {
            log("Обе ANCS CCCD включены, но READY ждёт валидные battery + network B4");
            scheduleHelperTelemetryRecovery(callbackGatt, 200L);
        }
        if (startHelperAncsReadyProof(callbackGatt)) return;
        prepareBatteryBootstrap(callbackGatt);
        log("Обе ANCS-подписки включены; Helper telemetry="
                + (iphoneHelperTelemetrySubscribed ? "READY" : "UNAVAILABLE")
                + "; atomic B4 READ and BAS diagnostics use the serialized GATT queue");
        sendNextRequest();
    }

    /**
     * Completes the reverse-route proof on the same ATT owner. Core Bluetooth's didConnect and
     * an unencrypted B4 read only prove a BLE link; the ANCS session is usable only after both
     * Notification Source and Data Source CCCDs are enabled. The iPhone UI is therefore allowed
     * to turn green only after this post-CCCD write succeeds.
     */
    private boolean startHelperAncsReadyProof(BluetoothGatt callbackGatt) {
        if (!managedIncomingMode || callbackGatt != gatt || !gattReady
                || !hasCurrentManagedLinkBoundProof(callbackGatt)
                || !iphoneHelperTelemetrySubscribed
                || !iphoneHelperValidTelemetryReceived
                || helperAncsReadyProofAcknowledged
                || helperAncsReadyProofAttempted || helperAncsReadyProofPending
                || activeHelperProofWriteStage != HelperProofWriteStage.NONE
                || discoveryPending || descriptorStage != DescriptorStage.NONE
                || activeRequest != null || iphoneHelperTelemetryReadPending
                || batteryReadPendingUuid != null) return false;
        BluetoothGattCharacteristic telemetry = iphoneTelemetryCharacteristic;
        byte[] challenge = helperLinkBoundChallenge;
        if (telemetry == null
                || !TELEMETRY_RELAY_CHARACTERISTIC.equals(telemetry.getUuid())
                || challenge == null
                || challenge.length != MANAGED_PROOF_FRAME_BYTES - 1) return false;
        helperAncsReadyProofAttempted = true;
        if ((telemetry.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            terminalManagedLinkBindingFailure(callbackGatt,
                    "F05/B4 does not accept binary ANCS-SUBSCRIBED Q");
            return false;
        }
        telemetry.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        telemetry.setValue(managedProofFrame(
                MANAGED_ANCS_SUBSCRIBED_OPCODE, challenge));
        long operationGeneration = armHelperProofWriteOperation(
                callbackGatt, telemetry, challenge,
                HelperProofWriteStage.ANCS_SUBSCRIBED);
        boolean started;
        try {
            started = callbackGatt.writeCharacteristic(telemetry);
        } catch (RuntimeException failure) {
            started = false;
            log("binary ANCS-SUBSCRIBED Q write exception: " + failure);
        }
        helperAncsReadyProofPending = started;
        if (!started) {
            helperAncsReadyProofAttempted = false;
            if (ownsHelperProofWriteOperation(callbackGatt, telemetry,
                    HelperProofWriteStage.ANCS_SUBSCRIBED,
                    operationGeneration)) {
                clearHelperProofWriteOperation();
            }
            terminalManagedLinkBindingFailure(callbackGatt,
                    "binary ANCS-SUBSCRIBED Q write did not start");
            return false;
        }
        log("binary ANCS-SUBSCRIBED Q proof started=" + started
                + " after both ANCS CCCD + B4 CCCD + valid battery/network payload");
        helperProofWriteTimeout = () -> {
            if (!ownsHelperProofWriteOperation(callbackGatt, telemetry,
                    HelperProofWriteStage.ANCS_SUBSCRIBED,
                    operationGeneration)) return;
            helperAncsReadyProofPending = false;
            helperAncsReadyProofAttempted = false;
            clearHelperProofWriteOperation();
            terminalManagedLinkBindingFailure(callbackGatt,
                    "binary ANCS-SUBSCRIBED Q write callback timeout");
        };
        main.postDelayed(helperProofWriteTimeout, 4_000L);
        return true;
    }

    private void scheduleHelperAncsReadyProofRetry(BluetoothGatt expectedGatt, String reason) {
        if (helperAncsReadyProofAcknowledged || expectedGatt == null) return;
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
        }
        helperAncsReadyProofRetry = () -> {
            helperAncsReadyProofRetry = null;
            if (expectedGatt != gatt || !gattClientConnected || !gattReady
                    || helperAncsReadyProofAcknowledged) return;
            if (startHelperAncsReadyProof(expectedGatt)) return;
            scheduleHelperTelemetryRecovery(expectedGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            scheduleHelperAncsReadyProofRetry(expectedGatt, "GATT queue still busy");
        };
        log("ANCS-SUBSCRIBED retry через 1 с · " + reason);
        main.postDelayed(helperAncsReadyProofRetry, 1_000L);
    }

    /**
     * Keeps Helper telemetry alive independently of notification delivery. Notifications are the
     * low-latency path; a B4 read is the deterministic snapshot/recovery path. If the
     * Helper service was published after ANCS discovery, the same GATT owner periodically repeats
     * service discovery instead of opening a competing connection.
     */
    private void scheduleHelperTelemetryRecovery(BluetoothGatt expectedGatt, long delayMs) {
        if (!helperTelemetryClientEnabled() || expectedGatt == null || expectedGatt != gatt
                || !gattClientConnected
                || managedIncomingMode
                && !hasCurrentManagedLinkBoundProof(expectedGatt)) return;
        if (helperTelemetryPoll != null) main.removeCallbacks(helperTelemetryPoll);
        helperTelemetryPoll = () -> {
            helperTelemetryPoll = null;
            if (!helperTelemetryClientEnabled()
                    || expectedGatt != gatt || !gattClientConnected
                    || managedIncomingMode
                    && !hasCurrentManagedLinkBoundProof(expectedGatt)) return;

            BluetoothGattCharacteristic endpoint = helperTelemetryEndpoint();
            boolean busy = discoveryPending || descriptorStage != DescriptorStage.NONE
                    || activeHelperProofWriteOperationGeneration != 0L
                    || activeRequest != null || batteryReadPendingUuid != null
                    || iphoneHelperTelemetryReadPending;
            if (endpoint == null) {
                log("Helper F05/B4 пока не найден; жду Service Changed на существующем owner");
                return;
            }

            boolean legacyWithoutNotify = iphoneTelemetryCharacteristic == null
                    && (endpoint.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            if (legacyWithoutNotify) {
                log("Legacy Helper B3 не передаёт telemetry; жду F05 через Service Changed");
                return;
            }

            if (!iphoneHelperTelemetrySubscribed
                    && !iphoneHelperTelemetrySubscriptionAttempted
                    && !busy
                    && (endpoint.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                if (startOptionalHelperTelemetrySubscription(expectedGatt)) return;
            }

            if (!busy && startHelperTelemetryRead(expectedGatt)) return;
            scheduleHelperTelemetryRecovery(expectedGatt,
                    busy ? HELPER_TELEMETRY_BUSY_RETRY_MS : HELPER_TELEMETRY_POLL_MS);
        };
        main.postDelayed(helperTelemetryPoll, Math.max(1L, delayMs));
    }

    private boolean startHelperTelemetryRead(BluetoothGatt callbackGatt) {
        BluetoothGattCharacteristic telemetry = iphoneTelemetryCharacteristic;
        if (callbackGatt != gatt || telemetry == null || iphoneHelperTelemetryReadPending
                || managedIncomingMode
                && !hasCurrentManagedLinkBoundProof(callbackGatt)
                || activeHelperProofWriteOperationGeneration != 0L
                || discoveryPending || descriptorStage != DescriptorStage.NONE
                || activeRequest != null || batteryReadPendingUuid != null
                || (telemetry.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(telemetry);
        } catch (RuntimeException failure) {
            started = false;
            log("Helper B4 atomic read exception: " + failure);
        }
        if (!started) {
            log("Helper B4 atomic read did not start");
            return false;
        }
        iphoneHelperTelemetryReadPending = true;
        scheduleHelperTelemetryReadTimeout(callbackGatt);
        return true;
    }

    private boolean shouldLogHelperTelemetry(@NonNull IphoneHelperTelemetry telemetry) {
        String fingerprint = telemetry.batteryLevel + "|" + telemetry.externalPower + "|"
                + telemetry.chargeState + "|" + telemetry.networkType + "|"
                + telemetry.phoneLocked;
        long now = SystemClock.elapsedRealtime();
        boolean changed = !Objects.equals(lastLoggedHelperTelemetry, fingerprint);
        if (!changed && now - lastHelperTelemetrySuccessLogAt < 30_000L) return false;
        lastLoggedHelperTelemetry = fingerprint;
        lastHelperTelemetrySuccessLogAt = now;
        return true;
    }

    private void scheduleHelperTelemetryReadTimeout(BluetoothGatt expectedGatt) {
        cancelHelperTelemetryReadTimeout();
        helperTelemetryReadTimeout = () -> {
            helperTelemetryReadTimeout = null;
            if (expectedGatt != gatt || !iphoneHelperTelemetryReadPending) return;
            iphoneHelperTelemetryReadPending = false;
            boolean resumeServiceSetup = iphoneServiceSetupDeferredForHelperRead;
            iphoneServiceSetupDeferredForHelperRead = false;
            if (managedIncomingMode) {
                terminalManagedLinkBindingFailure(expectedGatt,
                        "post-bind F05/B4 read callback timeout");
                return;
            }
            log("Helper B4 read callback timeout; ANCS remains active");
            if (resumeServiceSetup) {
                continueDiscoveredServiceSetup(expectedGatt);
                return;
            }
            scheduleHelperTelemetryRecovery(expectedGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            sendNextRequest();
        };
        main.postDelayed(helperTelemetryReadTimeout, HELPER_TELEMETRY_READ_TIMEOUT_MS);
    }

    private void cancelHelperTelemetryReadTimeout() {
        if (helperTelemetryReadTimeout != null) {
            main.removeCallbacks(helperTelemetryReadTimeout);
        }
        helperTelemetryReadTimeout = null;
    }

    private void cancelHelperTelemetryRecovery() {
        if (helperTelemetryPoll != null) main.removeCallbacks(helperTelemetryPoll);
        helperTelemetryPoll = null;
        cancelHelperTelemetryReadTimeout();
        iphoneHelperTelemetryReadPending = false;
    }

    private void prepareBatteryBootstrap(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt || batteryStage != BatteryStage.NOT_STARTED) return;
        BluetoothGattService service = callbackGatt.getService(BATTERY_SERVICE);
        batteryLevel = service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
        batteryLevelStatus =
                service == null ? null : service.getCharacteristic(BATTERY_LEVEL_STATUS);
        if (batteryLevel == null && batteryLevelStatus == null) {
            batteryStage = BatteryStage.COMPLETE;
            log("BAS 0x180F отсутствует; видимый процент ожидается только от Helper TEL3");
            return;
        }
        // Battery Level Status is retained only as an optional percentage source. Its charging
        // bits are ignored by the controller; TEL3 from iPhone Helper is authoritative.
        batteryStage = BatteryStage.READ_LEVEL_STATUS;
        log("BAS percentage probe: level=" + (batteryLevel != null)
                + " levelStatus=" + (batteryLevelStatus != null));
    }

    private void resetBatteryBootstrap() {
        cancelBatteryReadTimeout();
        batteryLevel = null;
        batteryLevelStatus = null;
        batteryReadPendingUuid = null;
        batteryStage = BatteryStage.NOT_STARTED;
    }

    /**
     * BAS is optional and uses the same Android GATT transaction gate as ANCS. A queued ANCS
     * request always wins; battery reads/subscriptions resume only while Control Point is idle.
     */
    private void advanceBatteryBootstrapIfIdle() {
        BluetoothGatt callbackGatt = gatt;
        if (!gattClientConnected || callbackGatt == null || activeRequest != null
                || !requests.isEmpty() || batteryReadPendingUuid != null
                || iphoneHelperTelemetryReadPending
                || activeHelperProofWriteOperationGeneration != 0L
                || descriptorStage != DescriptorStage.NONE) {
            return;
        }
        while (true) {
            switch (batteryStage) {
                case READ_LEVEL_STATUS:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL_STATUS;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevelStatus)) return;
                    break;
                case SUBSCRIBE_LEVEL_STATUS:
                    batteryStage = BatteryStage.READ_LEVEL;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevelStatus,
                            DescriptorStage.BATTERY_LEVEL_STATUS)) return;
                    break;
                case READ_LEVEL:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevel)) return;
                    break;
                case SUBSCRIBE_LEVEL:
                    batteryStage = BatteryStage.COMPLETE;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevel,
                            DescriptorStage.BATTERY_LEVEL)) return;
                    break;
                case NOT_STARTED:
                case COMPLETE:
                default:
                    return;
            }
        }
    }

    private boolean startOptionalBatteryRead(BluetoothGatt callbackGatt,
                                             BluetoothGattCharacteristic characteristic) {
        if (characteristic == null
                || (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        UUID uuid = characteristic.getUuid();
        batteryReadPendingUuid = uuid;
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(characteristic);
        } catch (RuntimeException failure) {
            started = false;
            log("BAS read exception " + shortUuid(uuid) + ": " + failure);
        }
        if (!started) {
            batteryReadPendingUuid = null;
            log("BAS read not started · " + shortUuid(uuid));
            return false;
        }
        scheduleBatteryReadTimeout(callbackGatt, uuid);
        log("BAS read started · " + shortUuid(uuid));
        return true;
    }

    private boolean startOptionalBatterySubscription(
            BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic,
            DescriptorStage stage) {
        if (characteristic == null) return false;
        int properties = characteristic.getProperties();
        boolean indicate =
                (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        boolean notify =
                (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        if (!notify && !indicate) return false;
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (cccd == null) return false;
        boolean local;
        try {
            local = callbackGatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException failure) {
            log("BAS setCharacteristicNotification exception · "
                    + shortUuid(characteristic.getUuid()) + ": " + failure);
            return false;
        }
        if (!local) return false;
        cccd.setValue(indicate
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        descriptorStage = stage;
        long descriptorOperation = armDescriptorWriteOperation(callbackGatt, cccd);
        boolean started;
        try {
            started = callbackGatt.writeDescriptor(cccd);
        } catch (RuntimeException failure) {
            started = false;
            log("BAS CCCD exception · " + stage + ": " + failure);
        }
        if (!started) {
            if (ownsDescriptorWriteOperation(callbackGatt, cccd, descriptorOperation)) {
                clearDescriptorWriteOperation();
            }
            descriptorStage = DescriptorStage.NONE;
            return false;
        }
        scheduleBatteryDescriptorTimeout(callbackGatt, cccd, descriptorOperation,
                stage, characteristic.getUuid());
        log("BAS CCCD started · " + stage);
        return true;
    }

    private void handleNotificationSource(byte[] value) {
        if (value == null) return;
        int accepted = 0;
        int dropped = 0;
        int preExistingDropped = 0;
        int removalsSuppressed = 0;
        for (int offset = 0; offset + 8 <= value.length; offset += 8) {
            AncsProtocol.Event event = AncsProtocol.parseEvent(value, offset);
            if (event == null) continue;
            long observedAtElapsedMs = SystemClock.elapsedRealtime();
            if (event.eventId == AncsProtocol.EVENT_REMOVED) {
                events.remove(event.uid);
                eventObservedAtElapsedMs.remove(event.uid);
                dirtyNotificationUids.remove(event.uid);
                cancelQueuedNotificationRequests(event.uid);
                if (realtimeAdmission.consumeRemoval(event.uid)) {
                    listener.onNotification(new NotificationItem(event.uid, event.eventId,
                            event.categoryId, "", "", "Удалено", "", "",
                            observedAtElapsedMs));
                    accepted++;
                } else {
                    removalsSuppressed++;
                }
            } else if (!realtimeAdmission.shouldRequest(event)) {
                preExistingDropped++;
            } else if (activeRequest != null
                    && activeRequest.kind == RequestKind.NOTIFICATION
                    && activeRequest.uid == event.uid) {
                // The response currently in flight may already have been formed by iOS. Mark
                // this UID dirty so that response is discarded and exactly one fresh request is
                // sent for the newest event.
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                dirtyNotificationUids.add(event.uid);
                accepted++;
            } else if (queuedNotificationUids.contains(event.uid)) {
                // Keep one queued Control Point request, but refresh both its metadata and
                // monotonic age to the latest Modified event.
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                updateQueuedNotificationAge(event.uid, observedAtElapsedMs);
                accepted++;
            } else if (requests.size() < MAX_PENDING_ANCS_REQUESTS) {
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                queuedNotificationUids.add(event.uid);
                requests.add(Request.notification(event, observedAtElapsedMs));
                accepted++;
            } else {
                dropped++;
            }
        }
        log("ANCS Notification Source: accepted=" + accepted
                + " dropped=" + dropped
                + " preExistingDropped=" + preExistingDropped
                + " removalsSuppressed=" + removalsSuppressed
                + " queue=" + requests.size()
                + " (только real-time; pre-existing replay не запрашивается)");
        sendNextRequest();
    }

    private void updateQueuedNotificationAge(long uid, long observedAtElapsedMs) {
        for (Request request : requests) {
            if (request.kind == RequestKind.NOTIFICATION && request.uid == uid) {
                request.observedAtElapsedMs = observedAtElapsedMs;
                return;
            }
        }
    }

    private void cancelQueuedNotificationRequests(long uid) {
        Iterator<Request> iterator = requests.iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (request.kind == RequestKind.NOTIFICATION && request.uid == uid) {
                iterator.remove();
                queuedNotificationUids.remove(uid);
            }
        }
    }

    private void sendNextRequest() {
        if (gatt == null || activeRequest != null) return;
        if (batteryReadPendingUuid != null || iphoneHelperTelemetryReadPending
                || descriptorStage != DescriptorStage.NONE) return;
        if (!gattReady || controlPoint == null) {
            advanceBatteryBootstrapIfIdle();
            return;
        }
        while (activeRequest == null) {
            Request candidate = requests.poll();
            if (candidate == null) {
                advanceBatteryBootstrapIfIdle();
                return;
            }
            if (candidate.kind == RequestKind.NOTIFICATION) {
                queuedNotificationUids.remove(candidate.uid);
                if (isExpiredNotification(candidate.observedAtElapsedMs)) {
                    discardNotificationState(candidate.uid);
                    log("ANCS notification UID " + candidate.uid
                            + " отброшен до Control Point: старше "
                            + LIVE_NOTIFICATION_MAX_AGE_MS + " ms");
                    continue;
                }
            }
            activeRequest = candidate;
        }
        byte[] payload;
        if (activeRequest.kind == RequestKind.NOTIFICATION) {
            notificationAccumulator =
                    new AncsProtocol.NotificationAccumulator(activeRequest.uid);
            appNameAccumulator = null;
            payload = AncsProtocol.notificationAttributeRequest(activeRequest.uid);
        } else {
            appNameAccumulator =
                    new AncsProtocol.AppNameAccumulator(activeRequest.appIdentifier);
            notificationAccumulator = null;
            payload = AncsProtocol.appDisplayNameRequest(activeRequest.appIdentifier);
        }
        controlPoint.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        controlPoint.setValue(payload);
        boolean started;
        try {
            started = gatt.writeCharacteristic(controlPoint);
        } catch (RuntimeException failure) {
            log("Control Point write exception: " + failure);
            finishRequest("write_exception");
            return;
        }
        log("Control Point " + activeRequest.kind + " started=" + started
                + " payload=" + AdvertisementParser.hex(payload, 80));
        if (!started) {
            finishRequest("write_not_started");
            return;
        }
        Request expected = activeRequest;
        requestTimeout = () -> {
            if (activeRequest == expected) {
                log("ANCS Data Source timeout: " + expected.kind);
                abortAncsRequestStream("timeout");
            }
        };
        main.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
    }

    private void handleDataSource(byte[] fragment) {
        if (activeRequest == null) {
            log("Data Source fragment без активного запроса");
            return;
        }
        if (activeRequest.kind == RequestKind.NOTIFICATION) {
            if (!notificationAccumulator.append(fragment)) {
                log("Malformed Notification response after "
                        + notificationAccumulator.size() + " bytes: "
                        + notificationAccumulator.error());
                abortAncsRequestStream("notification_malformed");
                return;
            }
            log("Notification Data Source accumulated="
                    + notificationAccumulator.size() + " bytes");
            AncsProtocol.NotificationData result = notificationAccumulator.complete();
            if (result == null) return;
            if (dirtyNotificationUids.remove(result.uid)) {
                AncsProtocol.Event latestEvent = events.get(result.uid);
                Long latestObservedAt = eventObservedAtElapsedMs.get(result.uid);
                if (latestEvent != null && latestObservedAt != null
                        && !isExpiredNotification(latestObservedAt)) {
                    enqueuePriorityRequest(Request.notification(latestEvent, latestObservedAt));
                    log("Notification attributes discarded: UID " + result.uid
                            + " изменился во время запроса; запланирован один fresh refresh");
                    finishRequest("refresh_queued");
                } else {
                    discardNotificationState(result.uid);
                    log("Notification refresh UID " + result.uid
                            + " просрочен и отброшен");
                    finishRequest("expired_refresh");
                }
                return;
            }
            AncsProtocol.Event event = events.remove(result.uid);
            Long observedAtElapsedMs = eventObservedAtElapsedMs.remove(result.uid);
            if (event == null) {
                log("Notification attributes discarded: UID " + result.uid
                        + " больше не live в текущей ANCS-сессии");
                finishRequest("removed_before_delivery");
                return;
            }
            if (observedAtElapsedMs == null
                    || isExpiredNotification(observedAtElapsedMs)) {
                log("Notification attributes discarded: UID " + result.uid
                        + " старше real-time TTL " + LIVE_NOTIFICATION_MAX_AGE_MS + " ms");
                finishRequest("expired_before_delivery");
                return;
            }
            String appName = value(appNames, result.appIdentifier);
            listener.onNotification(new NotificationItem(result.uid,
                    event.eventId, event.categoryId,
                    result.appIdentifier, appName, result.title, result.message, result.date,
                    observedAtElapsedMs));
            realtimeAdmission.markDelivered(result.uid);
            log("Notification attributes: app=" + result.appIdentifier
                    + " title=" + result.title);
            if (!result.appIdentifier.isEmpty()
                    && !appNames.containsKey(result.appIdentifier)
                    && queuedAppIdentifiers.add(result.appIdentifier)) {
                enqueuePriorityRequest(Request.appName(result.appIdentifier));
            }
            finishRequest("complete");
        } else {
            if (!appNameAccumulator.append(fragment)) {
                log("Malformed App response after " + appNameAccumulator.size()
                        + " bytes: " + appNameAccumulator.error());
                abortAncsRequestStream("app_malformed");
                return;
            }
            log("App Data Source accumulated=" + appNameAccumulator.size() + " bytes");
            String displayName = appNameAccumulator.complete();
            if (displayName == null) return;
            appNames.put(activeRequest.appIdentifier, displayName);
            listener.onAppName(activeRequest.appIdentifier, displayName);
            log("App DisplayName: " + activeRequest.appIdentifier + " → " + displayName);
            finishRequest("complete");
        }
    }

    private void finishRequest(String reason) {
        if (requestTimeout != null) main.removeCallbacks(requestTimeout);
        log("ANCS request finished: " + reason);
        Request finished = activeRequest;
        if (finished != null && finished.kind == RequestKind.NOTIFICATION
                && !"complete".equals(reason)
                && !"refresh_queued".equals(reason)) {
            discardNotificationState(finished.uid);
        } else if (finished != null && finished.kind == RequestKind.APP_NAME) {
            queuedAppIdentifiers.remove(finished.appIdentifier);
        }
        activeRequest = null;
        notificationAccumulator = null;
        appNameAccumulator = null;
        requestTimeout = null;
        long delay = reason.contains("timeout") || reason.contains("malformed")
                ? 350L : ANCS_REQUEST_GAP_MS;
        main.postDelayed(this::sendNextRequest, delay);
    }

    private boolean isExpiredNotification(long observedAtElapsedMs) {
        return observedAtElapsedMs <= 0L
                || SystemClock.elapsedRealtime() - observedAtElapsedMs
                > LIVE_NOTIFICATION_MAX_AGE_MS;
    }

    private void discardNotificationState(long uid) {
        events.remove(uid);
        eventObservedAtElapsedMs.remove(uid);
        queuedNotificationUids.remove(uid);
        dirtyNotificationUids.remove(uid);
    }

    /**
     * App-name and dirty-refresh requests must run before older queued work, but the bounded
     * queue is a hard memory/back-pressure limit. Evicting a tail request also clears all of its
     * deduplication state so a later real-time event is not suppressed by a request that no longer
     * exists.
     */
    private void enqueuePriorityRequest(Request request) {
        while (requests.size() >= MAX_PENDING_ANCS_REQUESTS) {
            Request evicted = requests.pollLast();
            if (evicted == null) break;
            if (evicted.kind == RequestKind.NOTIFICATION) {
                discardNotificationState(evicted.uid);
            } else {
                queuedAppIdentifiers.remove(evicted.appIdentifier);
            }
            log("ANCS queue full: evicted tail " + evicted.kind);
        }
        if (request.kind == RequestKind.NOTIFICATION) {
            queuedNotificationUids.add(request.uid);
        }
        requests.addFirst(request);
    }

    /**
     * A timed-out or malformed Data Source response has no transaction identifier beyond its
     * command prefix. Continuing with the next queued request would let a late fragment from the
     * failed response corrupt the next accumulator, so fail the whole ANCS session closed and let
     * the owning controller reopen it.
     */
    private void abortAncsRequestStream(String reason) {
        log("ANCS Data Source stream desynchronized: " + reason
                + "; queued requests are discarded before reconnect");
        clearAncsRuntime();
        state("ANCS DATA DESYNC · RECONNECT · " + reason);
    }

    private void requestBond(BluetoothDevice device) {
        if (device == null) return;
        if (!isVerifiedPeer(device)) {
            log("Bonding отклонён: устройство не является verified peer текущей сессии");
            return;
        }
        int state = safeBondState(device);
        if (state == BluetoothDevice.BOND_BONDED) {
            log("Verified peer уже BOND_BONDED");
            return;
        }
        if (state == BluetoothDevice.BOND_BONDING) {
            log("LE bonding verified peer уже выполняется");
            scheduleBondTimeout(device);
            return;
        }
        boolean started = false;
        try {
            started = device.createBond();
        } catch (RuntimeException failure) {
            log("createBond exception: " + failure);
        }
        log("createBond() public API=" + started);
        if (started) {
            leBondAttemptObserved = true;
            scheduleBondTimeout(device);
        }
        state(started ? "BONDING · ПОДТВЕРДИТЕ НА IPHONE" : "BOND_START_FAILED");
    }

    private void scheduleAncsPermissionRetry(@NonNull BluetoothGatt expected) {
        if (expected != gatt || !gattClientConnected || gattReady
                || ancsPermissionRetry != null) return;
        if (ancsPermissionRetryCount >= ANCS_PERMISSION_RETRY_LIMIT) {
            state("ANCS НЕ РАЗРЕШЕН НА IPHONE · LINK СОХРАНЁН");
            log("ANCS permission всё ещё отсутствует; автоматические проверки остановлены, "
                    + "но тот же BluetoothGatt owner остаётся активным");
            return;
        }
        ancsPermissionRetryCount++;
        int attempt = ancsPermissionRetryCount;
        ancsPermissionRetry = () -> {
            ancsPermissionRetry = null;
            if (expected != gatt || !gattClientConnected || gattReady) return;
            descriptorStage = DescriptorStage.NONE;
            resetBatteryBootstrap();
            log("Повторяю ANCS discovery на том же owner после ожидания iPhone permission · #"
                    + attempt);
            discoverServices(expected);
        };
        main.postDelayed(ancsPermissionRetry, ANCS_PERMISSION_RETRY_MS);
        log("Проверка iPhone ANCS permission #" + attempt + " через "
                + ANCS_PERMISSION_RETRY_MS + " ms; link не закрывается");
    }

    private void scheduleAncsRetryAfterBond(BluetoothGatt expected, String reason) {
        if (!ancsRetryAfterBond || expected == null || expected != gatt
                || !gattClientConnected || ancsBondRetry != null) {
            return;
        }
        if (ancsBondRetryCount >= 1) {
            ancsRetryAfterBond = false;
            state("ANCS AUTH FAILED ПОСЛЕ BOND");
            log("Повтор ANCS-подписки уже использован; новый цикл не запускаю");
            return;
        }
        ancsBondRetryCount++;
        ancsBondRetry = () -> {
            ancsBondRetry = null;
            if (gatt != expected || !gattClientConnected) return;
            ancsRetryAfterBond = false;
            descriptorStage = DescriptorStage.NONE;
            // discoverServices() may replace characteristic wrapper objects on Android 9.
            // Refresh BAS handles together with ANCS after the encrypted-link retry.
            resetBatteryBootstrap();
            log("Повторяю discovery/ANCS-подписку после bond · " + reason);
            discoverServices(expected);
        };
        main.postDelayed(ancsBondRetry, 800L);
        log("ANCS retry #" + ancsBondRetryCount + " запланирован через 800 ms · " + reason);
    }

    private boolean clearAncsRuntime() {
        return clearAncsRuntime(true, true);
    }

    /** Fresh pre-READY epochs invalidate logical lineage but issue no client-role command. */
    private boolean clearAncsRuntimeWithoutClientCommands() {
        return clearAncsRuntimeWithoutClientCommands(true);
    }

    private boolean clearAncsRuntimeWithoutClientCommands(boolean emitDiagnosticLogs) {
        return clearAncsRuntime(false, emitDiagnosticLogs);
    }

    private boolean clearAncsRuntime(boolean allowClientWrapperClose,
                                     boolean emitDiagnosticLogs) {
        BluetoothGatt ambiguousRawOwner = gatt;
        boolean abandonsDiscovery = ambiguousRawOwner != null && discoveryPending
                && activeDiscoveryGatt == ambiguousRawOwner
                && activeDiscoveryOperationGeneration != 0L;
        boolean abandonsDescriptor = ambiguousRawOwner != null
                && activeDescriptorWriteGatt == ambiguousRawOwner
                && activeDescriptorWriteOperationGeneration != 0L;
        boolean abandonsHelperProof = ambiguousRawOwner != null
                && activeHelperProofWriteGatt == ambiguousRawOwner
                && activeHelperProofWriteOperationGeneration != 0L;
        boolean abandonsHelperRead = managedIncomingMode
                && ambiguousRawOwner != null
                && iphoneHelperTelemetryReadPending;
        if (allowClientWrapperClose && (abandonsDiscovery || abandonsDescriptor
                || abandonsHelperProof || abandonsHelperRead)) {
            log("Raw GATT callback slot abandoned by runtime/epoch reset; closing wrapper "
                    + "before any successor · discovery=" + abandonsDiscovery
                    + " descriptor=" + abandonsDescriptor
                    + " helperProof=" + abandonsHelperProof
                    + " helperRead=" + abandonsHelperRead);
            closeClientGatt(ambiguousRawOwner);
        } else if (!allowClientWrapperClose && emitDiagnosticLogs
                && (abandonsDiscovery || abandonsDescriptor
                || abandonsHelperProof || abandonsHelperRead)) {
            log("Pre-READY epoch quarantined stale raw callback lineage; client wrapper close "
                    + "deferred until captured READY");
        }
        if (requestTimeout != null) main.removeCallbacks(requestTimeout);
        if (ancsBondRetry != null) main.removeCallbacks(ancsBondRetry);
        if (ancsPermissionRetry != null) main.removeCallbacks(ancsPermissionRetry);
        cancelAutoAncsWaitTimeout();
        cancelIphonePostSecureDiscovery();
        cancelConnectTimeout();
        cancelDiscoveryTimeout();
        cancelDescriptorWriteTimeout();
        clearDescriptorWriteOperation();
        clearManagedLinkBoundProof();
        cancelHelperTelemetryRecovery();
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
            helperAncsReadyProofRetry = null;
        }
        resetBatteryBootstrap();
        cancelBondTimeout();
        requestTimeout = null;
        ancsBondRetry = null;
        ancsPermissionRetry = null;
        ancsPermissionRetryCount = 0;
        requests.clear();
        earlyNotificationSourceFrames.clear();
        events.clear();
        eventObservedAtElapsedMs.clear();
        queuedNotificationUids.clear();
        dirtyNotificationUids.clear();
        queuedAppIdentifiers.clear();
        realtimeAdmission.clear();
        activeRequest = null;
        notificationAccumulator = null;
        appNameAccumulator = null;
        notificationSource = null;
        dataSource = null;
        controlPoint = null;
        serviceChanged = null;
        iphoneSecureCharacteristic = null;
        iphoneTelemetryCharacteristic = null;
        iphoneHelperTelemetrySubscriptionAttempted = false;
        iphoneHelperTelemetrySubscribed = false;
        iphoneHelperTelemetryReadPending = false;
        iphoneHelperValidTelemetryReceived = false;
        helperAncsReadyProofAttempted = false;
        helperAncsReadyProofPending = false;
        helperAncsReadyProofAcknowledged = false;
        iphoneHelperInitialReadAttempted = false;
        iphoneServiceSetupDeferredForHelperRead = false;
        iphoneHelperTelemetrySetupBypass = false;
        descriptorStage = DescriptorStage.NONE;
        gattReady = false;
        discoveryPending = false;
        clearDiscoveryLineage();
        return allowClientWrapperClose && (abandonsDiscovery || abandonsDescriptor
                || abandonsHelperProof || abandonsHelperRead);
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = null;
    }

    private void cancelDiscoveryTimeout() {
        if (discoveryTimeout != null) main.removeCallbacks(discoveryTimeout);
        discoveryTimeout = null;
    }

    private void scheduleDescriptorWriteTimeout(BluetoothGatt expectedGatt,
                                                BluetoothGattDescriptor expectedDescriptor,
                                                long expectedOperationGeneration,
                                                DescriptorStage expectedStage,
                                                UUID expectedCharacteristic) {
        cancelDescriptorWriteTimeout();
        long expectedClientGeneration = activeDescriptorWriteClientGeneration;
        long expectedSecurityEpoch = activeDescriptorWriteSecurityEpoch;
        long expectedPublicationToken = activeDescriptorWritePublicationToken;
        descriptorWriteTimeout = () -> {
            descriptorWriteTimeout = null;
            if (!ownsDescriptorWriteOperation(expectedGatt, expectedDescriptor,
                    expectedOperationGeneration)
                    || descriptorStage != expectedStage
                    || !descriptorMatchesStage(expectedStage, expectedCharacteristic)) {
                return;
            }
            clearDescriptorWriteOperation();
            descriptorStage = DescriptorStage.NONE;
            if (expectedStage == DescriptorStage.SERVICE_CHANGED) {
                log("Optional Service Changed CCCD callback timeout poisoned the raw "
                        + "descriptor channel; replacing wrapper before any successor");
                poisonMandatoryDescriptorChannelAndRecover(expectedGatt,
                        expectedClientGeneration, expectedSecurityEpoch,
                        expectedPublicationToken, "optional Service Changed CCCD timeout");
                return;
            }
            if (expectedStage == DescriptorStage.HELPER_TELEMETRY) {
                iphoneHelperTelemetrySubscribed = false;
                iphoneHelperTelemetrySubscriptionAttempted = false;
                if (managedIncomingMode) {
                    log("Mandatory F05/B4 CCCD callback timeout poisoned the raw "
                            + "descriptor channel");
                    terminalManagedLinkBindingFailure(expectedGatt,
                            "mandatory F05/B4 CCCD callback timeout");
                    return;
                }
                log("Helper telemetry optional CCCD callback timeout poisoned the raw "
                        + "descriptor channel; replacing wrapper before any successor");
                poisonMandatoryDescriptorChannelAndRecover(expectedGatt,
                        expectedClientGeneration, expectedSecurityEpoch,
                        expectedPublicationToken, "optional Helper B4 CCCD timeout");
                return;
            }
            gattReady = false;
            log("onDescriptorWrite не получен за "
                    + ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS
                    + " ms · stage=" + expectedStage
                    + " characteristic=" + shortUuid(expectedCharacteristic));
            state("CCCD_WRITE_TIMEOUT · " + expectedStage);
            poisonMandatoryDescriptorChannelAndRecover(expectedGatt,
                    expectedClientGeneration, expectedSecurityEpoch,
                    expectedPublicationToken,
                    "stage=" + expectedStage
                            + " characteristic=" + shortUuid(expectedCharacteristic));
        };
        long timeout = (expectedStage == DescriptorStage.SERVICE_CHANGED
                || expectedStage == DescriptorStage.HELPER_TELEMETRY)
                ? DESCRIPTOR_WRITE_TIMEOUT_MS : ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS;
        main.postDelayed(descriptorWriteTimeout, timeout);
    }

    private void scheduleBatteryDescriptorTimeout(BluetoothGatt expectedGatt,
                                                  BluetoothGattDescriptor expectedDescriptor,
                                                  long expectedOperationGeneration,
                                                  DescriptorStage expectedStage,
                                                  UUID expectedCharacteristic) {
        cancelDescriptorWriteTimeout();
        long expectedClientGeneration = activeDescriptorWriteClientGeneration;
        long expectedSecurityEpoch = activeDescriptorWriteSecurityEpoch;
        long expectedPublicationToken = activeDescriptorWritePublicationToken;
        descriptorWriteTimeout = () -> {
            descriptorWriteTimeout = null;
            if (!ownsDescriptorWriteOperation(expectedGatt, expectedDescriptor,
                    expectedOperationGeneration)
                    || descriptorStage != expectedStage
                    || !descriptorMatchesStage(expectedStage, expectedCharacteristic)) {
                return;
            }
            clearDescriptorWriteOperation();
            descriptorStage = DescriptorStage.NONE;
            log("BAS CCCD callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + expectedStage
                    + "; raw descriptor channel poisoned, wrapper replacement required");
            poisonMandatoryDescriptorChannelAndRecover(expectedGatt,
                    expectedClientGeneration, expectedSecurityEpoch,
                    expectedPublicationToken, "optional BAS CCCD timeout · " + expectedStage);
        };
        main.postDelayed(descriptorWriteTimeout, BATTERY_OPERATION_TIMEOUT_MS);
    }

    private void cancelDescriptorWriteTimeout() {
        if (descriptorWriteTimeout != null) main.removeCallbacks(descriptorWriteTimeout);
        descriptorWriteTimeout = null;
    }

    private void scheduleBatteryReadTimeout(BluetoothGatt expectedGatt, UUID expectedUuid) {
        cancelBatteryReadTimeout();
        batteryReadTimeout = () -> {
            batteryReadTimeout = null;
            if (gatt != expectedGatt || !expectedUuid.equals(batteryReadPendingUuid)) return;
            batteryReadPendingUuid = null;
            log("BAS read callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + shortUuid(expectedUuid)
                    + "; optional operation skipped, ANCS stays READY");
            sendNextRequest();
        };
        main.postDelayed(batteryReadTimeout, BATTERY_OPERATION_TIMEOUT_MS);
    }

    private void cancelBatteryReadTimeout() {
        if (batteryReadTimeout != null) main.removeCallbacks(batteryReadTimeout);
        batteryReadTimeout = null;
    }

    private void scheduleBondTimeout(BluetoothDevice expectedDevice) {
        cancelBondTimeout();
        bondTimeout = () -> {
            bondTimeout = null;
            if (!isVerifiedPeer(expectedDevice)
                    || safeBondState(expectedDevice) == BluetoothDevice.BOND_BONDED) {
                return;
            }
            ancsRetryAfterBond = false;
            log("LE bonding не завершился за " + BOND_TIMEOUT_MS
                    + " ms · peer=" + safeAddress(expectedDevice));
            state("LE BOND TIMEOUT");
        };
        main.postDelayed(bondTimeout, BOND_TIMEOUT_MS);
    }

    private void cancelBondTimeout() {
        if (bondTimeout != null) main.removeCallbacks(bondTimeout);
        bondTimeout = null;
    }

    private void closeClientGatt(BluetoothGatt callbackGatt) {
        if (callbackGatt == null) return;
        if (activeDescriptorWriteGatt == callbackGatt) {
            cancelDescriptorWriteTimeout();
            clearDescriptorWriteOperation();
            descriptorStage = DescriptorStage.NONE;
        }
        if (gatt == callbackGatt) {
            clearManagedLinkBoundProof();
            if (linkProbeGatt == callbackGatt) cancelAmbiguousAclProbe();
            clearRssiProbePoisonAfterGattClosed(callbackGatt);
            gatt = null;
            gattClientConnected = false;
            clientConnectInFlight = false;
            activeClientTarget = null;
            activeClientAutoConnect = false;
            activeClientOpportunistic = false;
            activeClientEstablished = false;
            activeClientProvenSecurityEpoch = 0L;
            incomingDiscoveryStarted = false;
            clearIncomingClientAttemptLineage();
        }
        try {
            callbackGatt.close();
        } catch (RuntimeException ignored) {
        }
    }

    private boolean ensureAdapter() {
        if (adapter == null) {
            state("NO_ADAPTER");
            return false;
        }
        if (!adapter.isEnabled()) {
            state("BLUETOOTH_OFF");
            log("Включите Bluetooth штатным интерфейсом");
            return false;
        }
        return true;
    }

    private void updateCandidate(BluetoothDevice device, int rssi,
                                 boolean ancsSolicitation, String raw, String origin) {
        String address = safeAddress(device);
        if (address.isEmpty()) address = "unknown-" + System.identityHashCode(device);
        String name = safeName(device);
        Candidate old = candidates.get(address);
        if (old == null && candidates.size() >= MAX_CANDIDATES) {
            String removable = null;
            for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
                if (entry.getValue().bondState != BluetoothDevice.BOND_BONDED) {
                    removable = entry.getKey();
                    break;
                }
            }
            if (removable != null) candidates.remove(removable);
        }
        Candidate candidate = new Candidate(device, address,
                name.isEmpty() && old != null ? old.name : name,
                safeType(device), safeBondState(device),
                rssi <= -127 && old != null ? old.rssi : rssi,
                ancsSolicitation || old != null && old.ancsSolicitation,
                raw.isEmpty() && old != null ? old.rawAdvertisement : raw,
                old != null && "bonded".equals(old.origin) ? old.origin : origin);
        candidates.put(address, candidate);
        publishCandidates();
    }

    private void publishCandidates() {
        long now = android.os.SystemClock.uptimeMillis();
        long remaining = CANDIDATE_UI_INTERVAL_MS - (now - lastCandidatePublishAt);
        if (remaining <= 0 && !candidatePublishScheduled) {
            lastCandidatePublishAt = now;
            publishCandidatesNow();
            return;
        }
        if (candidatePublishScheduled) return;
        candidatePublishScheduled = true;
        main.postDelayed(candidatePublisher, Math.max(1L, remaining));
    }

    private void publishCandidatesNow() {
        List<Candidate> snapshot = new ArrayList<>(candidates.values());
        snapshot.sort(Comparator
                .comparing((Candidate value) -> !value.ancsSolicitation)
                .thenComparing(value -> value.bondState != BluetoothDevice.BOND_BONDED)
                .thenComparing((Candidate value) -> value.rssi, Comparator.reverseOrder()));
        listener.onCandidates(snapshot);
    }

    private void state(String value) {
        if (value != null && value.contains("ANCS READY")) {
            managedReconnectAttempt = 0;
            incomingClientAttachAttempt = 0;
            if (managedReconnectTask != null) main.removeCallbacks(managedReconnectTask);
            managedReconnectTask = null;
        }
        if (!closing && managedReconnectEnabled && managedSavedPeer != null
                && requiresControllerRetry(value)) {
            scheduleManagedReconnect(value);
            String recovery = REMOTE_LOGICAL_NAME + " · RECOVERING";
            listener.onState(recovery);
            log("STATE: " + recovery + " · reason=" + value);
            return;
        }
        if (!closing && !retrySignalled && requiresControllerRetry(value)) {
            retrySignalled = true;
            // Deliver the typed lifecycle signal first. The controller closes this transport and
            // advances its session barrier while processing it, so a later diagnostic-state
            // callback cannot accidentally become the only owner of reconnection.
            listener.onRetryRequired(value);
        }
        listener.onState(value);
        log("STATE: " + value);
    }

    /**
     * Keeps transient status 133/discovery/CCCD failures inside one serialized transport owner.
     * The outer controller is notified only for unrecoverable setup failures.
     */
    private void scheduleManagedReconnect(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || managedSavedPeer == null
                || managedReconnectTask != null || coldBackgroundAttachTask != null) return;

        if (managedIncomingMode) {
            BluetoothGatt reverseOwner = gatt;
            if (reverseOwner != null && !canIssueManagedIncomingRearm(reverseOwner)) {
                log("Managed reverse reconnect request quarantined before exact current "
                        + "PAIR+B3+READY attempt tuple · " + reason);
                return;
            }
            BluetoothDevice verified = getVerifiedPeer();
            if (verified != null && findCurrentServerPeer(verified) != null) {
                recoverIncomingClientRole(reason);
            } else {
                preserveManagedIncomingPublicationAfterLinkLoss(reason);
            }
            return;
        }

        BluetoothGatt establishedOwner = gatt;
        if (establishedOwner != null && activeClientEstablished
                && sessionState.isCurrent(activeClientGeneration)) {
            if (gattClientConnected) {
                restartDiscoveryOnPersistentOwner(establishedOwner, activeClientGeneration,
                        reason);
            } else {
                awaitPersistentGattReconnect(establishedOwner, activeClientGeneration, reason);
            }
            return;
        }

        // A client that never established cannot be retained. Close only that explicitly failed
        // attempt, then register a fresh background owner against the same bonded identity.
        // Established owners take the early branch above and are never destroyed here.
        BluetoothDevice activeResolved = activeClientTarget != null
                ? activeClientTarget : gatt == null ? null : gatt.getDevice();
        if (activeResolved != null) managedResolvedPeer = activeResolved;

        cancelAmbiguousAclProbe();
        stopScan();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        BluetoothGatt previous = gatt;
        boolean passiveOpportunisticOwner = activeClientOpportunistic;
        gatt = null;
        activeClientOpportunistic = false;
        if (previous != null) {
            if (!passiveOpportunisticOwner) {
                try {
                    previous.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }

        int attempt = managedReconnectAttempt++;
        long delay = AncsReconnectPolicy.retryDelayMillis(attempt);
        BluetoothDevice expected = managedSavedPeer;
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || !managedReconnectEnabled || expected != managedSavedPeer
                    || !sessionState.isCurrent(waitGeneration)) return;
            iphonePeripheralMode = true;
            helperBootstrapMode = false;
            iphoneConnectStarted = false;
            boolean started = safeBondState(expected) == BluetoothDevice.BOND_BONDED
                    ? startManagedBackgroundAttach(expected,
                    "cold-owner retry #" + (attempt + 1) + " after " + reason)
                    : startSavedPeerScan(expected);
            if (!started) {
                scheduleManagedReconnect("background attach/fallback scan could not start");
            }
        };
        main.postDelayed(managedReconnectTask, delay);
        log("Одна serialized cold-owner recovery " + REMOTE_LOGICAL_NAME
                + " #" + (attempt + 1) + " через " + delay + " ms · " + reason);
    }

    /** Re-publishes Geely_ANCS after a failed/lost incoming route without touching Classic. */
    private void scheduleManagedIncomingRestart(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode
                || managedSavedPeer == null || managedReconnectTask != null) return;

        cancelAmbiguousAclProbe();
        stopScan();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        BluetoothGatt previous = gatt;
        boolean passiveOpportunisticOwner = activeClientOpportunistic;
        gatt = null;
        activeClientOpportunistic = false;
        if (previous != null) {
            if (!passiveOpportunisticOwner) {
                try {
                    previous.disconnect();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }
        BluetoothDevice resolvedPeer = managedResolvedPeer;
        stopAdvertising();
        resetVerifiedPeerSession();
        managedIncomingMode = true;
        managedResolvedPeer = resolvedPeer;
        if (resolvedPeer != null) {
            log("Новая Geely_ANCS session не pre-claim'ит старый RPA "
                    + safeAddress(resolvedPeer) + "; жду PAIR от текущего incoming callback");
        }
        iphonePeripheralMode = false;
        helperBootstrapMode = false;

        int attempt = managedReconnectAttempt++;
        long delay = AncsReconnectPolicy.retryDelayMillis(attempt);
        BluetoothDevice expected = managedSavedPeer;
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || !managedReconnectEnabled || !managedIncomingMode
                    || expected != managedSavedPeer
                    || !sessionState.isCurrent(waitGeneration)) return;
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            if (!startGeelyAncsAdvertising()) {
                scheduleManagedIncomingRestart("Geely_ANCS advertising could not restart");
            }
        };
        main.postDelayed(managedReconnectTask, delay);
        log("Одна serialized Geely_ANCS recovery #" + (attempt + 1)
                + " через " + delay + " ms · " + reason);
    }

    private static boolean requiresControllerRetry(@Nullable String value) {
        if (value == null) return false;
        return value.contains("CONNECT RETURNED NULL")
                || value.contains("CONNECT TIMEOUT")
                || value.contains("CONNECT EXCEPTION")
                || value.contains("SAVED PEER SCAN UNAVAILABLE")
                || value.contains("SAVED PEER SCAN FAILED")
                || value.contains("SAVED PEER CONFLICT")
                || value.contains("PEER CONFLICT")
                || value.contains("CONNECTION FAILED")
                || value.contains("GPS-STYLE FAILED")
                || value.contains("IPHONE DISCONNECTED")
                || value.contains("SERVICE CHANGED · RECONNECT")
                || value.contains("DISCOVERY_FAILED_")
                || value.contains("DISCOVERY_START_FAILED")
                || value.contains("DISCOVERY_TIMEOUT")
                || value.contains("ANCS_INCOMPLETE")
                || value.contains("SUBSCRIBE_EXCEPTION")
                || value.contains("SUBSCRIBE_LOCAL_FAILED")
                || value.contains("CCCD_START_FAILED")
                || value.contains("CCCD_WRITE_EXCEPTION")
                || value.contains("CCCD_WRITE_TIMEOUT")
                || value.contains("CCCD_FAILED_")
                || value.contains("ANCS DATA DESYNC")
                || value.contains("ANCS WAIT TIMEOUT")
                || value.contains("SECURE READ FAILED")
                || value.contains("BOND_START_FAILED")
                || value.contains("LE BOND TIMEOUT")
                || value.contains("LE BOND FAILED")
                || value.contains("ATTEMPTS EXHAUSTED")
                || value.contains("PAIRING FAILED")
                || value.contains("ADVERTISE_FAILED_")
                || value.contains("ADVERTISE_EXCEPTION")
                || value.contains("GATT_SERVER_UNAVAILABLE")
                || value.contains("GATT_SERVICE_ADD_FAILED_")
                || value.contains("GATT_SERVICE_ADD_START_FAILED")
                || value.contains("GATT_CHARACTERISTIC_ADD_FAILED")
                || value.contains("AUTH FAILED ПОСЛЕ BOND");
    }

    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + "  " + message;
        Log.i(LOG_TAG, line);
        listener.onLog(line);
    }

    private static boolean isAuthorizationError(int status) {
        return status == STATUS_INSUFFICIENT_AUTHENTICATION
                || status == STATUS_INSUFFICIENT_AUTHORIZATION
                || status == STATUS_INSUFFICIENT_KEY_SIZE
                || status == STATUS_INSUFFICIENT_ENCRYPTION
                || status == STATUS_GATT_AUTH_FAIL;
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) return "null";
        String value = uuid.toString();
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            String value = device.getAddress();
            return value == null ? "" : value;
        } catch (RuntimeException denied) {
            return "";
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String value = device.getName();
            return value == null ? "" : value;
        } catch (RuntimeException denied) {
            return "";
        }
    }

    private static int safeType(BluetoothDevice device) {
        try {
            return device.getType();
        } catch (RuntimeException denied) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    private static int safeBondState(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (RuntimeException denied) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private static String typeLabel(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "classic";
            case BluetoothDevice.DEVICE_TYPE_LE: return "LE";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "dual";
            default: return "unknown";
        }
    }

    private static String bondLabel(int bond) {
        switch (bond) {
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            default: return "NONE";
        }
    }

    private static String value(Map<String, String> values, String key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }

    private static boolean advertisesService(ScanRecord record, UUID serviceUuid) {
        if (record == null) return false;
        List<ParcelUuid> values = record.getServiceUuids();
        if (values == null) return false;
        ParcelUuid expected = new ParcelUuid(serviceUuid);
        return values.contains(expected);
    }

    private boolean matchesManagedSavedPeer(@NonNull BluetoothDevice selected,
                                            @NonNull BluetoothDevice observed,
                                            boolean solicitsAncs,
                                            boolean advertisesHelperService) {
        return AncsReconnectPolicy.candidateMayBeSelected(
                safeAddress(selected), safeAddress(observed),
                safeBondState(selected) == BluetoothDevice.BOND_BONDED,
                safeBondState(observed) == BluetoothDevice.BOND_BONDED,
                solicitsAncs, uniqueBondedNameMatch(selected, observed),
                sameDevice(managedResolvedPeer, observed), advertisesHelperService,
                REMOTE_LOGICAL_NAME.equalsIgnoreCase(safeName(selected).trim()));
    }

    /**
     * Name is only a supporting tie-breaker after both bond and ANCS-service checks. It is never
     * accepted as the identity by itself.
     */
    private boolean uniqueBondedNameMatch(@NonNull BluetoothDevice selected,
                                          @NonNull BluetoothDevice observed) {
        String selectedName = safeName(selected).trim();
        String observedName = safeName(observed).trim();
        if (selectedName.isEmpty() || !selectedName.equals(observedName)
                || adapter == null) return false;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (RuntimeException denied) {
            return false;
        }
        int matchingNames = 0;
        if (bonded != null) {
            for (BluetoothDevice candidate : bonded) {
                if (selectedName.equals(safeName(candidate).trim())) matchingNames++;
            }
        }
        return matchingNames == 1;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> {
                ScanRecord record = result.getScanRecord();
                byte[] raw = record == null ? null : record.getBytes();
                AdvertisementParser.Parsed parsed = AdvertisementParser.parse(raw);
                boolean solicitsAncs = parsed.solicits(AncsProtocol.SERVICE);
                boolean helperService = advertisesService(record, DIAGNOSTIC_SERVICE);
                updateCandidate(result.getDevice(), result.getRssi(), solicitsAncs,
                        parsed.hex, helperService ? "iPhone_ANCS Helper service"
                                : solicitsAncs ? "ANCS solicitation" : "scan");
                BluetoothDevice savedTarget = savedPeerScanTarget;
                if (iphonePeripheralMode && !helperBootstrapMode
                        && savedTarget != null && !iphoneConnectStarted
                        && matchesManagedSavedPeer(
                        savedTarget, result.getDevice(), solicitsAncs, helperService)) {
                    log("Identity-resolved saved-peer match: RSSI=" + result.getRssi()
                            + " selected=" + safeAddress(savedTarget)
                            + " observed=" + safeAddress(result.getDevice())
                            + " helperService=" + helperService
                            + " ancsSolicitation=" + solicitsAncs
                            + " bond=" + bondLabel(safeBondState(result.getDevice())));
                    connectToSavedAdvertisingIphone(result.getDevice(), solicitsAncs,
                            helperService);
                    return;
                }
                if (iphonePeripheralMode
                        && helperBootstrapMode && helperService
                        && !iphoneConnectStarted) {
                    log("GPS-style scan match: KX11-iPhone RSSI=" + result.getRssi()
                            + " address=" + safeAddress(result.getDevice()));
                    connectToAdvertisingIphone(result.getDevice());
                    return;
                }
                if (solicitsAncs) {
                    log("Найдена ANCS solicitation: " + safeAddress(result.getDevice())
                            + " raw=" + parsed.hex);
                }
            });
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(0, result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                if (scanTimeout != null) main.removeCallbacks(scanTimeout);
                scanTimeout = null;
                boolean savedPeerScan = savedPeerScanTarget != null
                        && iphonePeripheralMode && !helperBootstrapMode;
                scanning = false;
                savedPeerScanTarget = null;
                state(savedPeerScan
                        ? "AUTO · SAVED PEER SCAN FAILED_" + errorCode
                        : "SCAN_FAILED_" + errorCode);
                log("onScanFailed " + errorCode + ": " + scanError(errorCode));
            });
        }
    };

    /** Per-start callback; identity is part of the publication ownership tuple. */
    private final class PublicationAdvertiseCallback extends AdvertiseCallback {
        private final BluetoothLeAdvertiser ownerAdvertiser;
        private final long publicationToken;
        private final int publicationNonce;
        /** The framework start outcome is terminal; duplicate delivery is observation-only. */
        private boolean startOutcomeHandled;

        PublicationAdvertiseCallback(@NonNull BluetoothLeAdvertiser ownerAdvertiser,
                                     long publicationToken, int publicationNonce) {
            this.ownerAdvertiser = ownerAdvertiser;
            this.publicationToken = publicationToken;
            this.publicationNonce = publicationNonce;
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            main.post(() -> handleAdvertiseStartSuccess(this, settingsInEffect));
        }

        @Override
        public void onStartFailure(int errorCode) {
            main.post(() -> handleAdvertiseStartFailure(this, errorCode));
        }
    }

    private ManagedIncomingPublicationPolicy.AdvertisingCallbackAction
    advertiseCallbackAction(PublicationAdvertiseCallback callback, boolean success) {
        return ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                activeAdvertiseCallback == callback,
                callback.publicationToken,
                callback.publicationNonce,
                publishedDiagnosticServicePublicationToken,
                publishedManagedIncomingPublicationNonce,
                callback.startOutcomeHandled,
                success);
    }

    private void handleAdvertiseStartSuccess(PublicationAdvertiseCallback callback,
                                             AdvertiseSettings settingsInEffect) {
        ManagedIncomingPublicationPolicy.AdvertisingCallbackAction action =
                advertiseCallbackAction(callback, true);
        if (action == ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.OBSERVE_STALE) {
            // Callback-scoped stop retires only the old start; it cannot stop a newer callback.
            try {
                callback.ownerAdvertiser.stopAdvertising(callback);
            } catch (RuntimeException ignored) {
            }
            log("Stale advertise onStartSuccess observed/stopped without current mutation"
                    + " · token=" + callback.publicationToken
                    + " nonce=" + String.format(Locale.US, "%06X",
                    callback.publicationNonce));
            return;
        }
        if (action
                == ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.IGNORE_DUPLICATE) {
            log("Duplicate current advertise onStartSuccess ignored · token="
                    + callback.publicationToken);
            return;
        }
        callback.startOutcomeHandled = true;
        advertisingPending = false;
        advertising = true;
        state(solicitationAdvertising
                ? "ANCS SOLICITATION REQUESTED"
                : managedReconnectEnabled
                ? LOCAL_LOGICAL_NAME + " · ADVERTISING"
                : "DIAGNOSTIC ADV ACTIVE");
        log("onStartSuccess mode=" + settingsInEffect.getMode()
                + " tx=" + settingsInEffect.getTxPowerLevel()
                + " connectable=" + settingsInEffect.isConnectable()
                + " token=" + callback.publicationToken
                + " nonce=" + String.format(Locale.US, "%06X",
                callback.publicationNonce));
        if (solicitationAdvertising) {
            log("Callback подтверждает запуск рекламы, но не AD type 0x15. "
                    + "Проверьте эфир вторым BLE-сканером");
        }
    }

    private void handleAdvertiseStartFailure(PublicationAdvertiseCallback callback,
                                             int errorCode) {
        ManagedIncomingPublicationPolicy.AdvertisingCallbackAction action =
                advertiseCallbackAction(callback, false);
        if (action == ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.OBSERVE_STALE) {
            log("Stale advertise onStartFailure observed without current mutation"
                    + " · token=" + callback.publicationToken
                    + " nonce=" + String.format(Locale.US, "%06X",
                    callback.publicationNonce)
                    + " error=" + errorCode);
            return;
        }
        if (action
                == ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.IGNORE_DUPLICATE) {
            log("Duplicate current advertise onStartFailure ignored · token="
                    + callback.publicationToken + " error=" + errorCode);
            return;
        }
        callback.startOutcomeHandled = true;
        activeAdvertiseCallback = null;
        advertising = false;
        advertisingPending = false;
        advertisingDesired = false;
        solicitationAdvertising = false;
        log("onStartFailure " + errorCode + ": " + advertiseError(errorCode)
                + " · current token=" + callback.publicationToken
                + " nonce=" + String.format(Locale.US, "%06X",
                callback.publicationNonce));
        clearPreparedAdvertising();
        closeGattServer();
        scheduleManagedIncomingPublicationRestartIfNeeded(
                "advertising failed " + errorCode);
        state("ADVERTISE_FAILED_" + errorCode);
        if (!managedIncomingMode && managedReconnectEnabled && !scanning
                && !clientConnectInFlight && !gattClientConnected) {
            scheduleManagedReconnect("advertising failed " + errorCode);
        }
    }

    private boolean sendGattServerResponse(BluetoothDevice device, int requestId,
                                           int status, int offset, byte[] value) {
        BluetoothGattServer server = gattServer;
        if (server == null) return false;
        try {
            boolean sent = server.sendResponse(device, requestId, status, offset, value);
            if (!sent) {
                main.post(() -> log("GATT server sendResponse=false status=" + status
                        + " peer=" + safeAddress(device)));
            }
            return sent;
        } catch (RuntimeException failure) {
            main.post(() -> log("GATT server sendResponse exception: " + failure));
            return false;
        }
    }

    private boolean sendGattReadResponse(BluetoothDevice device, int requestId,
                                         int offset, byte[] fullValue) {
        if (offset < 0 || offset > fullValue.length) {
            sendGattServerResponse(device, requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET, 0, null);
            return false;
        }
        byte[] response = offset == fullValue.length
                ? new byte[0]
                : AdvertisementParser.copyOfRange(fullValue, offset, fullValue.length);
        return sendGattServerResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, offset, response);
    }

    private static String asciiCommand(byte[] value) {
        if (value == null) return "";
        return new String(value, StandardCharsets.UTF_8)
                .trim()
                .toUpperCase(Locale.US);
    }

    @Nullable
    private static byte[] managedProofChallenge(@Nullable byte[] value, byte opcode) {
        if (value == null || value.length != MANAGED_PROOF_FRAME_BYTES
                || value[0] != opcode) return null;
        return Arrays.copyOfRange(value, 1, value.length);
    }

    private static byte[] managedProofFrame(byte opcode, @NonNull byte[] challenge) {
        if (challenge.length != MANAGED_PROOF_FRAME_BYTES - 1) {
            throw new IllegalArgumentException("managed proof challenge must be 16 bytes");
        }
        byte[] frame = new byte[MANAGED_PROOF_FRAME_BYTES];
        frame[0] = opcode;
        System.arraycopy(challenge, 0, frame, 1, challenge.length);
        return frame;
    }

    private final BluetoothGattServerCallback gattServerCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    main.post(() -> {
                        log("GATT server service added status=" + status
                                + " uuid=" + service.getUuid());
                        if (!serverDiagnosticService.equals(service.getUuid())) return;
                        BluetoothGattService pending = pendingDiagnosticServicePublication;
                        long pendingToken = pendingDiagnosticServicePublicationToken;
                        if (service != pending || pendingToken == 0L
                                || pendingToken != serverDiagnosticServicePublicationToken) {
                            log("Игнорирую stale onServiceAdded от закрытой F04 publication · "
                                    + "objectId=" + System.identityHashCode(service)
                                    + " token=" + pendingToken);
                            return;
                        }
                        if (status != GATT_SUCCESS) {
                            advertisingDesired = false;
                            clearPreparedAdvertising();
                            closeGattServer();
                            scheduleManagedIncomingPublicationRestartIfNeeded(
                                    "onServiceAdded failed " + status);
                            state("GATT_SERVICE_ADD_FAILED_" + status);
                            return;
                        }
                        if (managedIncomingMode) {
                            if (!commitManagedIncomingPublicationNonce(service, pendingToken)) {
                                advertisingDesired = false;
                                clearPreparedAdvertising();
                                closeGattServer();
                                scheduleManagedIncomingPublicationRestartIfNeeded(
                                        "F04 publication nonce commit failed");
                                state("F04_PUBLICATION_NONCE_COMMIT_FAILED");
                                return;
                            }
                            try {
                                prepareManagedIncomingAdvertising(
                                        publishedManagedIncomingPublicationNonce);
                            } catch (RuntimeException failure) {
                                advertisingDesired = false;
                                log("Committed F04 publication could not build beacon: "
                                        + failure);
                                clearPreparedAdvertising();
                                closeGattServer();
                                scheduleManagedIncomingPublicationRestartIfNeeded(
                                        "F04 publication beacon build failed");
                                state("F04_PUBLICATION_BEACON_BUILD_FAILED");
                                return;
                            }
                        }
                        publishedDiagnosticServicePublication = service;
                        publishedDiagnosticServicePublicationToken = pendingToken;
                        pendingDiagnosticServicePublication = null;
                        pendingDiagnosticServicePublicationToken = 0L;
                        serverDiagnosticServicePublished = true;
                        startPreparedAdvertising();
                        maybeStartIncomingClientAttachAfterServicePublished(
                                "onServiceAdded SUCCESS");
                    });
                }

                @Override
                public void onConnectionStateChange(BluetoothDevice device,
                                                    int status, int newState) {
                    main.post(() -> {
                        boolean freshIncomingLink = recordGattServerPeer(
                                device, status, newState);
                        if (status == GATT_SUCCESS
                                && newState == BluetoothProfile.STATE_CONNECTED) {
                            if (managedIncomingMode && freshIncomingLink) {
                                beginFreshIncomingSecurityEpoch(device,
                                        "new GATT-server CONNECTED callback");
                            }
                            bindServerPeerToCurrentSecurityEpoch(device);
                            if (managedIncomingMode && freshIncomingLink
                                    && activeClientEstablished && gattClientConnected) {
                                log("Fresh incoming epoch defers retained-client RSSI proof until "
                                        + "current PAIR+B3+READY");
                            }
                        }
                        GattServerPeer diagnosticPeer =
                                findCurrentServerPeer(device);
                        BluetoothDevice diagnosticPhysical = diagnosticPeer == null
                                ? device : diagnosticPeer.physicalLinkFacade;
                        BluetoothDevice diagnosticPair = diagnosticPeer == null
                                ? device : diagnosticPeer.device;
                        log("GATT SERVER LINK: session=" + sessionGeneration
                                + " securityEpoch=" + incomingSecurityEpoch
                                + " peer=" + safeAddress(device)
                                + " objectId=" + System.identityHashCode(device)
                                + " physicalObjectId="
                                + System.identityHashCode(diagnosticPhysical)
                                + " pairObjectId="
                                + System.identityHashCode(diagnosticPair)
                                + " sameAddress="
                                + sameDevice(diagnosticPhysical, diagnosticPair)
                                + " status=" + status + " newState=" + newState
                                + " type=" + typeLabel(safeType(device))
                                + " bond=" + bondLabel(safeBondState(device)));
                        updateCandidate(device, -127, false, "", "gatt-server-link");
                        if (status == GATT_SUCCESS
                                && newState == BluetoothProfile.STATE_CONNECTED) {
                            state(managedReconnectEnabled
                                    ? REMOTE_LOGICAL_NAME + " · INCOMING LINK"
                                    : "GATT SERVER LINK · В LIGHTBLUE ЗАПИШИТЕ PAIR");
                            log(managedReconnectEnabled
                                    ? REMOTE_LOGICAL_NAME
                                    + " подключился к стабильному link-anchor "
                                    + LOCAL_LOGICAL_NAME
                                    + "; жду PAIR/B3 current-link proof"
                                    : "Peer станет verified только после ASCII PAIR в CONTROL "
                                    + serverControlCharacteristic);
                            if (managedIncomingMode) {
                                attachAncsClientToIncomingOwner(device);
                            } else {
                                log("Diagnostic link ждёт явный PAIR/B3 challenge");
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && managedIncomingMode
                                && (establishedClientOwnsPhysicalLink(device)
                                || pendingExactClientAttach(device))) {
                            // Client attach can complete before Helper writes PAIR. Liveness of
                            // that exact candidate is a transport question and must be checked
                            // independently from the still-closed ownership/security gates.
                            handleServerFacadeDisconnected(device);
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && isVerifiedPeer(device)) {
                            handleServerFacadeDisconnected(device);
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && managedIncomingMode && getVerifiedPeer() == null) {
                            // One physical iPhone link can surface as anonymous and bonded facade
                            // objects. Retire the complete pre-adoption alias set so the anonymous
                            // object cannot suppress the next fresh epoch after an APK hot update.
                            int retiredAliases = retirePreAdoptionServerAliases(device);
                            // Keep the stable advertiser alive. The iPhone owns reconnect and will
                            // return to this same anchor; rotating the UUID deadlocked v35.
                            log("Incoming link закрылся до adoption; стабильная реклама сохранена"
                                    + " · retiredCurrentEpochAliases=" + retiredAliases);
                        }
                    });
                }

                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device,
                                                        int requestId, int offset,
                                                        BluetoothGattCharacteristic characteristic) {
                    UUID uuid = characteristic == null ? null : characteristic.getUuid();
                    if (serverSecureCharacteristic.equals(uuid)) {
                        // Full B3 admission, proof commit and ATT response share the exact FIFO
                        // used by CONNECTED/DISCONNECTED and PAIR.
                        main.post(() -> handleSecureReadRequestOnMain(
                                device, requestId, offset, characteristic));
                        return;
                    }
                    main.post(() -> log("GATT SERVER READ raw: session="
                            + sessionGeneration
                            + " peer=" + safeAddress(device)
                            + " requestId=" + requestId
                            + " offset=" + offset
                            + " uuid=" + uuid
                            + " type=" + typeLabel(safeType(device))
                            + " bond=" + bondLabel(safeBondState(device))));
                    if (serverDiagnosticCharacteristic.equals(uuid)) {
                        sendGattReadResponse(device, requestId, offset,
                                (LOCAL_LOGICAL_NAME + "/3")
                                        .getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (serverTelemetryCharacteristicUuid.equals(uuid)) {
                        long publicationToken =
                                currentDiagnosticServicePublicationToken(characteristic);
                        if (publicationToken == 0L || !isVerifiedPeer(device)) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                            return;
                        }
                        sendGattReadResponse(device, requestId, offset,
                                "TEL3;-;-;X;-;0".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    sendGattServerResponse(device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                }

                @Override
                public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                                    int offset,
                                                    BluetoothGattDescriptor descriptor) {
                    UUID descriptorUuid = descriptor == null ? null : descriptor.getUuid();
                    BluetoothGattCharacteristic characteristic = descriptor == null
                            ? null : descriptor.getCharacteristic();
                    UUID characteristicUuid = characteristic == null
                            ? null : characteristic.getUuid();
                    long publicationToken =
                            currentDiagnosticServicePublicationToken(characteristic);
                    if (!AncsProtocol.CLIENT_CONFIGURATION.equals(descriptorUuid)
                            || !serverTelemetryCharacteristicUuid.equals(characteristicUuid)) {
                        sendGattServerResponse(device, requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                        return;
                    }
                    if (publicationToken == 0L || !isVerifiedPeer(device)) {
                        sendGattServerResponse(device, requestId,
                                STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                        return;
                    }
                    byte[] value = isServerTelemetrySubscribed(device)
                            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    sendGattReadResponse(device, requestId, offset, value);
                }

                @Override
                public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                     BluetoothGattDescriptor descriptor,
                                                     boolean preparedWrite,
                                                     boolean responseNeeded,
                                                     int offset, byte[] value) {
                    UUID descriptorUuid = descriptor == null ? null : descriptor.getUuid();
                    BluetoothGattCharacteristic characteristic = descriptor == null
                            ? null : descriptor.getCharacteristic();
                    UUID characteristicUuid = characteristic == null
                            ? null : characteristic.getUuid();
                    long publicationToken =
                            currentDiagnosticServicePublicationToken(characteristic);
                    byte[] rawValue = value == null ? null : value.clone();
                    boolean enable = Arrays.equals(rawValue,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean disable = Arrays.equals(rawValue,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    int status = BluetoothGatt.GATT_SUCCESS;
                    if (!AncsProtocol.CLIENT_CONFIGURATION.equals(descriptorUuid)
                            || !serverTelemetryCharacteristicUuid.equals(characteristicUuid)) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (preparedWrite) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (offset != 0) {
                        status = BluetoothGatt.GATT_INVALID_OFFSET;
                    } else if (publicationToken == 0L) {
                        status = STATUS_INSUFFICIENT_AUTHORIZATION;
                    } else if (!isVerifiedPeer(device)) {
                        status = STATUS_INSUFFICIENT_AUTHORIZATION;
                    } else if (!enable && !disable) {
                        status = BluetoothGatt.GATT_FAILURE;
                    }
                    if (responseNeeded) {
                        sendGattServerResponse(device, requestId, status, 0, null);
                    }
                    final int result = status;
                    main.post(() -> {
                        log("GATT SERVER B4 CCCD write: status=" + result
                                + " enable=" + enable
                                + " peer=" + safeAddress(device));
                        if (result == BluetoothGatt.GATT_SUCCESS) {
                            setServerTelemetrySubscription(device, enable, publicationToken);
                        }
                    });
                }

                @Override
                public void onNotificationSent(BluetoothDevice device, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        main.post(() -> log("B4 wake-poll notification status=" + status
                                + " peer=" + safeAddress(device)));
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device, int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite, boolean responseNeeded,
                        int offset, byte[] value) {
                    UUID uuid = characteristic == null ? null : characteristic.getUuid();
                    byte[] rawValue = value == null ? null : value.clone();
                    String rawCommand = asciiCommand(rawValue);
                    if (serverControlCharacteristic.equals(uuid)) {
                        // The main-owned transaction validates and commits the exact PAIR tuple,
                        // sends checked ATT success, then performs logs/UI/bonding work.
                        Runnable pairTransaction = () -> handlePairWriteRequestOnMain(
                                device, requestId, characteristic, preparedWrite,
                                responseNeeded, offset, rawValue);
                        // onConnectionStateChange uses this same unconditional main queue. Keep
                        // Binder callback arrival FIFO so a queued DISCONNECTED cannot run after
                        // and retire a newer PAIR-owned facade from the same callback stream.
                        main.post(pairTransaction);
                        return;
                    }
                    if (serverSecureCharacteristic.equals(uuid)
                            && "ANCS-READY".equals(rawCommand)) {
                        main.post(() -> handleAncsReadyWriteRequestOnMain(
                                device, requestId, characteristic, preparedWrite,
                                responseNeeded, offset, rawValue));
                        return;
                    }
                    if (serverSecureCharacteristic.equals(uuid)
                            && "ANCS".equals(rawCommand)) {
                        main.post(() -> handleSecureWriteRequestOnMain(
                                device, requestId, characteristic, preparedWrite,
                                responseNeeded, offset, rawValue));
                        return;
                    }
                    IphoneHelperTelemetry diagnosticTelemetry =
                            IphoneHelperTelemetry.parse(rawValue);
                    main.post(() -> log(diagnosticTelemetry == null
                            ? "GATT SERVER WRITE raw: session=" + sessionGeneration
                            + " peer=" + safeAddress(device)
                            + " requestId=" + requestId
                            + " offset=" + offset
                            + " prepared=" + preparedWrite
                            + " responseNeeded=" + responseNeeded
                            + " uuid=" + uuid
                            + " len=" + (rawValue == null ? 0 : rawValue.length)
                            + " hex=" + AdvertisementParser.hex(rawValue, 80)
                            + " ascii=`" + asciiCommand(rawValue) + "`"
                            + " type=" + typeLabel(safeType(device))
                            + " bond=" + bondLabel(safeBondState(device))
                            : "GATT SERVER WRITE TELEMETRY: kind=" + diagnosticTelemetry.kind
                            + " seq=" + diagnosticTelemetry.sequence
                            + " peer=" + safeAddress(device)));
                    int status = BluetoothGatt.GATT_SUCCESS;
                    Runnable successAction = null;
                    long publicationToken =
                            currentDiagnosticServicePublicationToken(characteristic);

                    if (preparedWrite) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (offset != 0) {
                        status = BluetoothGatt.GATT_INVALID_OFFSET;
                    } else if (serverSecureCharacteristic.equals(uuid)
                            || serverTelemetryCharacteristicUuid.equals(uuid)) {
                        String command = asciiCommand(value);
                        IphoneHelperTelemetry telemetry = IphoneHelperTelemetry.parse(value);
                        if (publicationToken == 0L) {
                            status = STATUS_INSUFFICIENT_AUTHORIZATION;
                            main.post(() -> log("B3/B4 WRITE отклонён: stale/pending F04 "
                                    + "publication token · " + safeAddress(device)));
                        } else if (!isVerifiedPeer(device)) {
                            status = STATUS_INSUFFICIENT_AUTHORIZATION;
                            main.post(() -> log("SECURE WRITE отклонён: peer не verified · "
                                    + safeAddress(device)));
                        } else if (telemetry != null) {
                            successAction = () -> {
                                if (!isCurrentDiagnosticServicePublicationToken(publicationToken)
                                        || !isVerifiedPeer(device)
                                        || findCurrentServerPeer(device) == null) {
                                    log("Helper telemetry completion ignored: stale F04 "
                                            + "publication/peer · peer="
                                            + safeAddress(device)
                                            + " token=" + publicationToken);
                                    return;
                                }
                                listener.onHelperTelemetry(telemetry);
                                log("Helper telemetry accepted: kind=" + telemetry.kind
                                        + " seq=" + telemetry.sequence);
                            };
                        } else if (serverTelemetryCharacteristicUuid.equals(uuid)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("TELEMETRY write rejected: malformed TEL3/TEL2"));
                        } else if (!"ANCS".equals(command)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("SECURE command отклонена: `" + command
                                    + "`; ожидается ASCII ANCS, ANCS-READY "
                                    + "или TEL2/TEL3"));
                        }
                    } else {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    }

                    if (responseNeeded) {
                        sendGattServerResponse(device, requestId, status, 0, null);
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS && successAction != null) {
                        main.post(successAction);
                    }
                }
            };

    private void handleIphonePeripheralConnectionState(BluetoothGatt callbackGatt,
                                                       int status, int newState) {
        long callbackGeneration = activeClientGeneration;
        if (!sessionState.isCurrent(callbackGeneration)) {
            log("Игнорирую GATT callback устаревшей session generation");
            closeClientGatt(callbackGatt);
            return;
        }
        if (managedReconnectEnabled && callbackGatt.getDevice() != null) {
            managedResolvedPeer = callbackGatt.getDevice();
        }
        boolean establishedOwner = activeClientEstablished;
        if (status != GATT_SUCCESS) {
            if (establishedOwner) {
                log("Established GATT callback status=" + status
                        + "; сохраняю owner и жду системный reconnect");
                awaitPersistentGattReconnect(callbackGatt, callbackGeneration,
                        "established GATT status=" + status);
                return;
            }
            cancelConnectTimeout();
            clientConnectInFlight = false;
            gattClientConnected = false;
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            if (status == 19 && ancsAuthorizationFailureSeen) {
                state("ANCS PAIRING FAILED · IPHONE CLOSED LINK");
                log("iPhone закрыл BLE link (status=19/0x13) после неуспешной "
                        + "ANCS authorization/SMP");
            } else {
                state("GPS-STYLE FAILED · status=" + status);
                log("Прямое Android-central подключение завершилось ошибкой " + status);
                if (managedReconnectEnabled) {
                    scheduleManagedReconnect("direct GATT status=" + status);
                }
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            cancelConnectTimeout();
            cancelAmbiguousAclProbe();
            clientConnectInFlight = false;
            gattClientConnected = true;
            activeClientEstablished = true;
            state(activeClientAutoConnect
                    ? "IPHONE BLE CONNECTED · BACKGROUND"
                    : "IPHONE BLE CONNECTED · DIRECT");
            log("Android создал единственный BLE link; начинаю GATT discovery");
            discoverServices(callbackGatt);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            cancelConnectTimeout();
            cancelAmbiguousAclProbe();
            clientConnectInFlight = false;
            gattClientConnected = false;
            if (establishedOwner) {
                log("Established GATT link disconnected normally; "
                        + "не закрываю зарегистрированный owner");
                awaitPersistentGattReconnect(callbackGatt, callbackGeneration,
                        "normal established GATT disconnect");
                return;
            }
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            state("GPS-STYLE · IPHONE DISCONNECTED");
            log("Первичный direct GATT не установился; возвращаюсь к Helper scan");
            if (managedReconnectEnabled) {
                scheduleManagedReconnect("initial direct GATT disconnected");
            }
        }
    }

    /**
     * Pie's hidden overload delivers every shared client callback through {@code main}. Running
     * those callbacks inline preserves Handler FIFO against an already queued operation timeout;
     * legacy Binder-thread callbacks still get exactly one hop onto the transport looper.
     */
    private void dispatchGattCallback(@NonNull Runnable callback) {
        AncsRecoveryPolicy.GattCallbackDispatchAction dispatchAction =
                AncsRecoveryPolicy.gattCallbackDispatchAction(
                        Looper.myLooper() == main.getLooper());
        if (dispatchAction == AncsRecoveryPolicy.GattCallbackDispatchAction.INLINE) {
            callback.run();
        } else {
            main.post(callback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt,
                                            int status, int newState) {
            // The HA1210 hidden overload already dispatches on `main`. Re-posting that callback
            // can place its state commit behind the previously queued 10-second timeout even when
            // Fluoride delivered CONNECTED first. Dispatch inline on main; only foreign-loop
            // legacy callbacks are serialized through the handler.
            Runnable dispatch = () -> {
                if (callbackGatt != gatt) {
                    if (managedIncomingMode) {
                        if (!canIssueManagedIncomingTupleCommand()) {
                            log("Stale reverse client callback quarantined/no-op; wrapper close "
                                    + "waits for the exact captured post-READY command barrier");
                            return;
                        }
                        log("Stale reverse client callback ignored after post-READY barrier: "
                                + "wrapper is not active owner");
                        closeClientGatt(callbackGatt);
                    }
                    return;
                }
                if (managedIncomingMode
                        && !ownsCurrentIncomingClientAttempt(callbackGatt)) {
                    if (isQuarantinedRetainedEstablishedOwner(callbackGatt)) {
                        if (status == STATUS_GATT_CONN_TERMINATE_LOCAL_HOST) {
                            latchQuarantinedRetainedStatus22(callbackGatt);
                            return;
                        }
                        log("Retained established-owner callback quarantined/no-op until exact "
                                + "current PAIR+B3+READY captured handoff");
                        return;
                    }
                    if (!incomingAncsReadyGateOpen || incomingReadyAttachTask != null) {
                        log("Stale reverse client callback quarantined/no-op; close is deferred "
                                + "until the captured post-READY transport barrier");
                        return;
                    }
                    // Even callbackGatt==gatt is not sufficient after a fresh epoch: the shared
                    // callback has no epoch parameter. Only the captured S/E/P/raw-facade attempt
                    // lineage can mutate owner state or consume retry budget.
                    log("Stale reverse client callback closed/no-op: attempt tuple no longer "
                            + "matches current session/epoch/publication/PAIR raw facade");
                    cancelConnectTimeout();
                    closeClientGatt(callbackGatt);
                    return;
                }
                log("onConnectionStateChange status=" + status
                        + " newState=" + newState
                        + " device=" + safeAddress(callbackGatt.getDevice())
                        + " physicalObjectId="
                        + System.identityHashCode(callbackGatt.getDevice())
                        + " pairObjectId="
                        + System.identityHashCode(incomingClientAttemptPairFacade)
                        + " sameAddress=" + sameDevice(
                        callbackGatt.getDevice(), incomingClientAttemptPairFacade)
                        + " autoConnect=" + activeClientAutoConnect
                        + " opportunistic=" + activeClientOpportunistic
                        + " transport=TRANSPORT_LE");
                if (iphonePeripheralMode) {
                    handleIphonePeripheralConnectionState(callbackGatt, status, newState);
                    return;
                }
                if (status != GATT_SUCCESS) {
                    boolean attachWasInFlight = clientConnectInFlight;
                    boolean freshReplacementAttempt =
                            callbackGatt == incomingFreshReplacementGatt;
                    boolean failedBackgroundAttach =
                            attachWasInFlight && activeClientAutoConnect;
                    cancelConnectTimeout();
                    gattClientConnected = false;
                    if (managedIncomingMode) {
                        if (!activeClientEstablished) {
                            // A failed opportunistic registration is terminal for this exact tuple.
                            // Unregister close-only and wait for a fresh inbound security epoch.
                            boolean passiveAttempt = activeClientOpportunistic;
                            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
                            clientConnectInFlight = false;
                            closeClientGatt(callbackGatt);
                            clearAncsRuntime();
                            incomingDiscoveryStarted = false;
                            if (passiveAttempt) {
                                if (freshReplacementAttempt) incomingFreshReplacementGatt = null;
                                boolean physicalLost =
                                        resetRetiredObserverAfterServerFacadeLoss(
                                        attemptPeer, callbackGatt.getDevice(),
                                        "opportunistic status=" + status
                                                + " after server facade loss");
                                state(physicalLost
                                        ? "OPPORTUNISTIC FAILED · PHYSICAL LINK LOST"
                                        : "OPPORTUNISTIC ATTACH FAILED · LINK KEPT · "
                                        + "BUDGET SPENT");
                                log("Exact opportunistic wrapper closed-only; inbound server/proofs/F04 "
                                        + (physicalLost ? "logical epoch reset"
                                        : "kept")
                                        + ", no public fallback/retry · status=" + status);
                                return;
                            }
                            if (findConnectedServerPeer(callbackGatt.getDevice()) == null) {
                                resetIncomingSecurityAfterClientLoss(
                                        callbackGatt.getDevice(),
                                        "never-established status=" + status
                                                + " after server facade loss");
                                preserveManagedIncomingPublicationAfterLinkLoss(
                                        "direct attach failed after server facade loss");
                            } else {
                                scheduleIncomingClientAttachRetry(
                                        "initial direct attach status=" + status);
                            }
                        } else {
                            // The opportunistic branch retires close-only inside this helper;
                            // non-opportunistic legacy routes may retain/re-arm their owner.
                            recoverEstablishedIncomingClientAfterCallbackLoss(callbackGatt,
                                    "established same-peer GATT status=" + status,
                                    status == STATUS_GATT_CONN_TERMINATE_LOCAL_HOST);
                        }
                        return;
                    }
                    clientConnectInFlight = false;
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
                    state("GATT CONNECTION FAILED · status=" + status);
                    if (failedBackgroundAttach) {
                        scheduleDirectFallback("background attach status=" + status);
                    } else if (attachWasInFlight) {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    boolean freshReplacementAttempt =
                            callbackGatt == incomingFreshReplacementGatt;
                    cancelConnectTimeout();
                    clientConnectInFlight = false;
                    gattClientConnected = true;
                    activeClientEstablished = true;
                    if (managedIncomingMode) {
                        if (freshReplacementAttempt) {
                            incomingFreshReplacementGatt = null;
                            log("One-shot status=22 replacement получил CONNECTED; "
                                    + "epoch остаётся consumed до следующего fresh CONNECTED");
                        }
                        boolean callbackConfirmedHandoff =
                                confirmPendingServerFacadeHandoff(callbackGatt.getDevice());
                        activeClientProvenSecurityEpoch = incomingSecurityEpoch;
                        if (linkProbeGatt == callbackGatt) {
                            // Bluetooth has already accepted a fresh client CONNECTED callback for
                            // this epoch. Ignore any older RSSI result, but retain its physical
                            // callback slot so a later facade probe cannot overlap the raw read.
                            prepareInFlightLinkProbeForFreshEpoch();
                            log("Fresh client CONNECTED callback подтвердил current epoch; "
                                    + "старый RSSI result будет отброшен");
                        } else if (callbackConfirmedHandoff) {
                            log("Fresh client CONNECTED callback подтвердил handoff "
                                    + "без RSSI probe");
                        }
                        state(incomingAncsReadyGateOpen
                                ? "SAME-PEER DIRECT CLIENT ATTACHED · READY GATE OPEN"
                                : "SAME-PEER DIRECT CLIENT ATTACHED · ЖДУ ANCS-READY");
                        log("Direct GATT clientIf attached к exact bonded incoming peer; "
                                + (incomingAncsReadyGateOpen
                                ? "same-owner ANCS-READY уже получен"
                                : "service discovery намеренно не запускается до ANCS-READY"));
                        maybeStartIncomingAncsDiscovery(callbackGatt,
                                "onConnectionStateChange CONNECTED");
                    } else {
                        state("SAME-PEER GATT CONNECTED");
                        log("GATT client зарегистрирован на exact verified peer; "
                                + "discoverServices сразу, без requestMtu");
                        discoverServices(callbackGatt);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean attachWasInFlight = clientConnectInFlight;
                    boolean freshReplacementAttempt =
                            callbackGatt == incomingFreshReplacementGatt;
                    boolean failedBackgroundAttach =
                            attachWasInFlight && activeClientAutoConnect;
                    boolean establishedOwner = activeClientEstablished;
                    cancelConnectTimeout();
                    gattClientConnected = false;
                    if (managedIncomingMode) {
                        if (establishedOwner) {
                            recoverEstablishedIncomingClientAfterCallbackLoss(callbackGatt,
                                    "established same-peer GATT disconnected", false);
                        } else {
                            boolean passiveAttempt = activeClientOpportunistic;
                            GattServerPeer attemptPeer = incomingClientAttemptServerPeer;
                            clientConnectInFlight = false;
                            closeClientGatt(callbackGatt);
                            clearAncsRuntime();
                            incomingDiscoveryStarted = false;
                            if (passiveAttempt) {
                                if (freshReplacementAttempt) incomingFreshReplacementGatt = null;
                                boolean physicalLost =
                                        resetRetiredObserverAfterServerFacadeLoss(
                                        attemptPeer, callbackGatt.getDevice(),
                                        "opportunistic client DISCONNECTED after server "
                                                + "facade loss");
                                state(physicalLost
                                        ? "OPPORTUNISTIC DISCONNECTED · PHYSICAL LINK LOST"
                                        : "OPPORTUNISTIC DISCONNECTED · LINK KEPT · "
                                        + "BUDGET SPENT");
                                log("Exact opportunistic wrapper closed-only; inbound server/proofs/F04 "
                                        + (physicalLost ? "logical epoch reset"
                                        : "kept")
                                        + ", no public fallback/retry");
                                return;
                            }
                            if (findConnectedServerPeer(callbackGatt.getDevice()) == null) {
                                resetIncomingSecurityAfterClientLoss(
                                        callbackGatt.getDevice(),
                                        "never-established disconnect after server facade loss");
                                preserveManagedIncomingPublicationAfterLinkLoss(
                                        "direct attach disconnected after server facade loss");
                            } else {
                                scheduleIncomingClientAttachRetry(
                                        "initial direct attach disconnected");
                            }
                        }
                        return;
                    }
                    clientConnectInFlight = false;
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
                    state("GATT DISCONNECTED · status=" + status);
                    if (failedBackgroundAttach) {
                        scheduleDirectFallback("background attach disconnected");
                    } else if (attachWasInFlight) {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                }
            };
            dispatchGattCallback(dispatch);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            dispatchGattCallback(
                    () -> handleServicesDiscoveredCallback(callbackGatt, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            dispatchGattCallback(
                    () -> handleDescriptorWrite(callbackGatt, descriptor, status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] copy = characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            dispatchGattCallback(() -> handleCharacteristicChanged(
                    callbackGatt, characteristic, copy));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            dispatchGattCallback(() -> {
                if (callbackGatt != gatt) return;
                if (!acceptsCurrentManagedIncomingCallback(
                        callbackGatt, "onCharacteristicWrite")) return;
                log("onCharacteristicWrite " + shortUuid(characteristic.getUuid())
                        + " status=" + status);
                if (handleManagedHelperProofWriteCallback(
                        callbackGatt, characteristic, status)) return;
                if (iphonePeripheralMode
                        && CONTROL_CHARACTERISTIC.equals(characteristic.getUuid())) {
                    iphonePairWritePending = false;
                    if (status == GATT_SUCCESS) {
                        log("GPS-style PAIR принят iPhone helper");
                    } else {
                        log("GPS-style PAIR write status=" + status
                                + "; всё равно проверяю SECURE");
                    }
                    readIphoneSecure(callbackGatt);
                    return;
                }
                if (status != GATT_SUCCESS && activeRequest != null) {
                    if (isAuthorizationError(status)) requestBond(getVerifiedPeer());
                    finishRequest("write_status_" + status);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            byte[] copy = characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            dispatchGattCallback(() -> {
                if (callbackGatt != gatt) return;
                if (!acceptsCurrentManagedIncomingCallback(
                        callbackGatt, "onCharacteristicRead")) return;
                UUID uuid = characteristic.getUuid();
                if (managedIncomingMode
                        && TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                        && (!hasCurrentManagedLinkBoundProof(callbackGatt)
                        || characteristic != helperLinkBoundCharacteristic)) {
                    if (iphoneHelperTelemetryReadPending) {
                        cancelHelperTelemetryReadTimeout();
                        iphoneHelperTelemetryReadPending = false;
                        iphoneServiceSetupDeferredForHelperRead = false;
                    }
                    log("Pre-bind F05/B4 read callback quarantined; zero parse/listener/UI "
                            + "side effects");
                    return;
                }
                if ((TELEMETRY_CHARACTERISTIC.equals(uuid)
                        || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid))
                        && iphoneHelperTelemetryReadPending) {
                    cancelHelperTelemetryReadTimeout();
                    iphoneHelperTelemetryReadPending = false;
                    boolean resumeServiceSetup = iphoneServiceSetupDeferredForHelperRead;
                    iphoneServiceSetupDeferredForHelperRead = false;
                    IphoneHelperTelemetry telemetry = status == GATT_SUCCESS
                            ? IphoneHelperTelemetry.parse(copy) : null;
                    boolean proofStarted = false;
                    if (telemetry != null) {
                        proofStarted = acceptHelperTelemetryFrame(
                                callbackGatt, telemetry, "atomic read");
                    } else {
                        log("Helper B4 atomic read unavailable · status=" + status
                                + " value=" + AdvertisementParser.hex(copy, 80));
                    }
                    if (resumeServiceSetup) {
                        continueDiscoveredServiceSetup(callbackGatt);
                        return;
                    }
                    if (proofStarted) return;
                    scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_POLL_MS);
                    sendNextRequest();
                    return;
                }
                if ((BATTERY_LEVEL.equals(uuid) || BATTERY_LEVEL_STATUS.equals(uuid))
                        && uuid.equals(batteryReadPendingUuid)) {
                    cancelBatteryReadTimeout();
                    batteryReadPendingUuid = null;
                    if (status == GATT_SUCCESS && copy != null) {
                        listener.onBatteryCharacteristic(uuid, copy);
                        log("BAS read complete · " + shortUuid(uuid)
                                + " value=" + AdvertisementParser.hex(copy, 16));
                    } else {
                        log("BAS optional read skipped · " + shortUuid(uuid)
                                + " status=" + status);
                    }
                    sendNextRequest();
                    return;
                }
                if (!iphonePeripheralMode
                        || !SECURE_CHARACTERISTIC.equals(uuid)) {
                    return;
                }
                iphoneSecureReadPending = false;
                String text = copy == null
                        ? ""
                        : new String(copy, StandardCharsets.UTF_8);
                log("GPS-style READ SECURE status=" + status
                        + " value=`" + text + "`"
                        + " bond=" + bondLabel(safeBondState(callbackGatt.getDevice())));
                if (status == GATT_SUCCESS) {
                    iphoneSecureConfirmed = true;
                    state("SECURE IPHONE OK · ИЩУ ANCS");
                    if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                        scheduleIphonePostSecureDiscovery(callbackGatt);
                    }
                } else if (isAuthorizationError(status)) {
                    state("GPS-LINK · НУЖЕН LE BOND");
                    log("SECURE требует шифрование; запускаю bonding на текущем BLE link");
                    if (!leBondAttemptObserved) {
                        requestBond(callbackGatt.getDevice());
                    } else {
                        log("LE bond уже запускался; повтор SECURE pairing не запускаю");
                    }
                } else {
                    state("GPS-LINK · SECURE READ FAILED " + status);
                }
            });
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt callbackGatt, int rssi, int status) {
            dispatchGattCallback(() -> {
                if (callbackGatt != linkProbeGatt) return;
                if (managedIncomingMode
                        && !ownsCurrentIncomingClientAttempt(callbackGatt)) {
                    if (linkProbeDiscardResult) {
                        finishDiscardedRawProbeAndStartQueuedEpoch(
                                "RSSI callback rejected: physical/pair S/E/P tuple is stale");
                    } else {
                        cancelAmbiguousAclProbe();
                    }
                    return;
                }
                long generation = linkProbeGeneration;
                boolean serverFacadeProbe = linkProbeForServerFacadeHandoff;
                boolean incomingEpochProbe = linkProbeForIncomingSecurityEpoch;
                boolean discardResult = linkProbeDiscardResult;
                BluetoothDevice serverDevice = linkProbeServerDevice;
                long securityEpoch = linkProbeSecurityEpoch;
                String probeReason = linkProbeReason;
                if (discardResult) {
                    // This callback belongs to the one raw operation submitted before a newer
                    // security event. Its status/RSSI can only drain the slot; it cannot prove the
                    // new epoch or a server-facade handoff.
                    finishDiscardedRawProbeAndStartQueuedEpoch(
                            "old callback status=" + status);
                    return;
                }
                if (drainQueuedServerFacadeProbeAfterGeneric(callbackGatt, generation,
                        "prior callback status=" + status)) return;
                if (callbackGatt != gatt || !sessionState.isCurrent(generation)) {
                    if (serverFacadeProbe && serverDevice != null) {
                        cancelServerFacadeHandoffProbeIfOwned(callbackGatt, generation,
                                securityEpoch, serverDevice);
                    } else if (incomingEpochProbe && serverDevice != null) {
                        cancelIncomingEpochProbeIfOwned(callbackGatt, generation,
                                securityEpoch, serverDevice);
                    } else if (ownsGenericLinkProbe(callbackGatt, generation)) {
                        cancelAmbiguousAclProbe();
                    }
                    return;
                }
                if (serverFacadeProbe) {
                    if (serverDevice == null) {
                        cancelAmbiguousAclProbe();
                        return;
                    }
                    if (incomingSecurityEpoch != securityEpoch) {
                        cancelServerFacadeHandoffProbeIfOwned(callbackGatt, generation,
                                securityEpoch, serverDevice);
                        return;
                    }
                    cancelServerFacadeHandoffProbeIfOwned(callbackGatt, generation,
                            securityEpoch, serverDevice);
                    if (status == GATT_SUCCESS && gattClientConnected
                            && activeClientEstablished
                            && confirmPendingServerFacadeHandoff(serverDevice)) {
                        activeClientProvenSecurityEpoch = securityEpoch;
                        if (gattReady) {
                            sessionState.move(generation,
                                    AncsSessionStateMachine.Phase.READY);
                        }
                        state("SAME PHYSICAL LINK · RSSI HANDOFF CONFIRMED");
                        log("Server-facade handoff liveness OK, RSSI=" + rssi
                                + "; retained GATT owner не разрывается");
                        maybeStartIncomingAncsDiscovery(callbackGatt,
                                "post-DISCONNECTED facade liveness");
                    } else {
                        log("Server-facade handoff liveness failed status=" + status);
                        failServerFacadeHandoffProbe(callbackGatt, generation,
                                securityEpoch, serverDevice,
                                probeReason + "; RSSI status=" + status);
                    }
                    return;
                }
                if (incomingEpochProbe) {
                    if (serverDevice == null) {
                        cancelAmbiguousAclProbe();
                        return;
                    }
                    if (incomingSecurityEpoch != securityEpoch) {
                        cancelIncomingEpochProbeIfOwned(callbackGatt, generation,
                                securityEpoch, serverDevice);
                        return;
                    }
                    cancelIncomingEpochProbeIfOwned(callbackGatt, generation,
                            securityEpoch, serverDevice);
                    if (status == GATT_SUCCESS && gattClientConnected
                            && activeClientEstablished) {
                        activeClientProvenSecurityEpoch = securityEpoch;
                        log("New-epoch retained-client liveness OK, RSSI=" + rssi
                                + " · securityEpoch=" + securityEpoch);
                        maybeStartIncomingAncsDiscovery(callbackGatt,
                                "new security epoch liveness");
                    } else {
                        log("New-epoch retained-client liveness failed status=" + status);
                        failServerFacadeHandoffProbe(callbackGatt, generation,
                                securityEpoch, serverDevice,
                                probeReason + "; new-epoch RSSI status=" + status);
                    }
                    return;
                }
                if (!ownsGenericLinkProbe(callbackGatt, generation)) return;
                cancelAmbiguousAclProbe();
                if (status == GATT_SUCCESS && gattClientConnected && gattReady) {
                    sessionState.move(generation, AncsSessionStateMachine.Phase.READY);
                    log("GATT liveness probe OK, RSSI=" + rssi
                            + "; неоднозначный ACL loss не затронул ANCS");
                } else {
                    log("GATT liveness probe failed status=" + status);
                    scheduleManagedReconnect("ambiguous ACL liveness probe failed status="
                            + status);
                    state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                }
            });
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;
            if (!isVerifiedPeer(device)) {
                log("BOND event проигнорирован для non-verified peer: "
                        + safeAddress(device));
                return;
            }
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                int variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                int key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1);
                leBondAttemptObserved = true;
                state("PAIRING REQUEST · " + pairingVariantLabel(variant));
                log("ACTION_PAIRING_REQUEST peer=" + safeAddress(device)
                        + " variant=" + variant + " (" + pairingVariantLabel(variant) + ")"
                        + (key >= 0 ? " key=" + String.format(Locale.US, "%06d", key) : ""));
                log("Подтвердите системный запрос на магнитоле и iPhone; "
                        + "приложение не перехватывает broadcast");
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            log("BOND " + safeAddress(device) + ": "
                    + bondLabel(previous) + " → " + bondLabel(state)
                    + (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING
                    ? " · системный bond завершён без BOND_BONDED"
                    : ""));
            updateCandidate(device, -127, false, "", "bond event");
            if (state == BluetoothDevice.BOND_NONE && managedIncomingMode) {
                GattServerPeer acceptedPeer = incomingPairAcceptedServerPeer;
                if (acceptedPeer != null
                        && findCurrentServerPeer(device) == acceptedPeer) {
                    // The stable server record makes Binder wrapper churn safe, but it must not
                    // let a transcript survive actual identity loss. A later BOND_BONDED event
                    // can only save a candidate; it cannot resurrect this PAIR/B3/READY epoch.
                    beginFreshIncomingSecurityEpoch(device,
                            "selected incoming peer lost BOND_BONDED identity", true);
                    bindServerPeerToCurrentSecurityEpoch(device);
                    log("BOND_NONE invalidated stable PAIR/B3/READY transcript; "
                            + "fresh PAIR and status-5/encrypted B3 required");
                }
            }
            if (state == BluetoothDevice.BOND_BONDING) {
                leBondAttemptObserved = true;
                scheduleBondTimeout(device);
                state(iphonePeripheralMode
                        ? "GPS-LINK · LE BONDING"
                        : "VERIFIED PEER · LE BONDING");
            } else if (state == BluetoothDevice.BOND_BONDED) {
                cancelBondTimeout();
                state(iphonePeripheralMode
                        ? "GPS-LINK · LE BOND BONDED"
                        : "VERIFIED PEER · LE BOND BONDED");
                BluetoothGatt current = gatt;
                if (iphonePeripheralMode) {
                    if (iphoneAncsSeen) {
                        log("BOND_BONDED подтверждён на ANCS-first link");
                        if (ancsRetryAfterBond && gattClientConnected && current != null) {
                            scheduleAncsRetryAfterBond(current,
                                    "получен BOND_BONDED");
                        }
                    } else {
                        log("BOND_BONDED подтверждён на fallback GPS-style link; "
                                + "повторяю encrypted SECURE read");
                        if (gattClientConnected && current != null) {
                            main.postDelayed(() -> {
                                if (gatt == current && gattClientConnected) {
                                    readIphoneSecure(current);
                                }
                            }, 800L);
                        }
                    }
                } else {
                    if (managedIncomingMode && findCurrentServerPeer(device) != null
                            && isSelectedBondedIncomingDevice(device)) {
                        adoptIncomingClientCandidate(device,
                                "BOND_BONDED on current incoming link");
                    }
                    log("BOND_BONDED подтверждён; candidate сохранён, clientIf и discovery "
                            + "ждут exact current PAIR + status-5/encrypted B3 + ANCS-READY");
                }
                if (!iphonePeripheralMode && gattClientConnected && current != null) {
                    main.postDelayed(() -> {
                        if (gatt == current && gattClientConnected) {
                            discoverServices(current);
                        }
                    }, 800L);
                }
            } else if (previous == BluetoothDevice.BOND_BONDING) {
                cancelBondTimeout();
                ancsRetryAfterBond = false;
                state(iphoneAncsSeen
                        ? "ANCS · LE BOND FAILED"
                        : "VERIFIED PEER · LE BOND FAILED");
                log("LE bonding завершился неуспешно");
            }
        }
    };

    private static String pairingVariantLabel(int variant) {
        switch (variant) {
            case 0: return "PIN";
            case 1: return "PASSKEY";
            case 2: return "PASSKEY CONFIRMATION";
            case 3: return "CONSENT";
            case 4: return "DISPLAY PASSKEY";
            case 5: return "DISPLAY PIN";
            case 6: return "OOB CONSENT";
            case 7: return "PIN 16 DIGITS";
            default: return "UNKNOWN " + variant;
        }
    }

    private static String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED: return "already started";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "application registration failed";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR: return "internal error";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED: return "feature unsupported";
            // Added to the public SDK after Android 9; the platform callback value is stable.
            case 5:
                return "out of hardware resources";
            default: return "unknown";
        }
    }

    private static String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "feature unsupported";
            default: return "unknown";
        }
    }
}
