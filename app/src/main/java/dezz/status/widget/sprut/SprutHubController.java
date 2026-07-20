/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Output;
import dezz.status.widget.scenario.Result;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

/**
 * Long-lived direct Sprut.hub connector. A connection is considered current only after a complete
 * room/accessory snapshot; events received after that snapshot update the same persistent state
 * reducer used by MQTT and Home Assistant.
 */
public final class SprutHubController {
    private static final String TAG = "SprutHubController";
    private static final int MAX_BUFFERED_EVENTS = 4_096;
    private static final long[] RECONNECT_DELAYS_MS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L};
    private static volatile SprutHubController activeInstance;
    private static volatile String lastDetail = "not started";
    private static volatile boolean lastSynced;

    public enum State { DISABLED, CONNECTING, AUTHENTICATING, SYNCING, ONLINE, ERROR }

    public interface Listener {
        void onStateChanged(@NonNull String scope, @NonNull String id);
        void onConnectionChanged(@NonNull State state, @NonNull String detail);
        void onCatalogChanged(@NonNull SprutCatalog catalog);
        /** A live EVENT_UPDATE changed one characteristic in the authoritative catalog. */
        default void onCharacteristicChanged(@NonNull SprutPath path) {}
    }

    private final Context context;
    private final Preferences prefs;
    private final AutomationStateStore states;
    private final ConnectorValueRegistry values;
    private final HaBrickConfigStore mainConfigs;
    private final PopupItemConfigStore popupConfigs;
    private final SprutHubCatalogStore catalogStore;
    private final Listener listener;
    private final String clientId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "spruthub-controller");
                thread.setDaemon(true);
                return thread;
            });
    private final Object lock = new Object();
    private final Object catalogLock = new Object();
    private final List<JSONObject> bufferedEvents = new ArrayList<>();

    @Nullable private volatile SprutHubRpcClient client;
    @Nullable private volatile CompletableFuture<SprutCatalog> snapshotInFlight;
    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private WifiManager.WifiLock wifiLock;
    private volatile SprutCatalog catalog = SprutCatalog.empty();
    private volatile int hubRevision = Integer.MAX_VALUE;
    private volatile boolean stopped;
    private volatile boolean sessionSynced;
    private String signature = "";
    private volatile long generation;
    /** Guarded by {@link #catalogLock}; zero means events may be applied live. */
    private long loadingSnapshotEpoch;
    private long snapshotEpoch;
    private boolean bufferedEventOverflow;
    private int reconnectAttempt;

    public SprutHubController(@NonNull Context context, @NonNull Preferences prefs,
                              @NonNull AutomationStateStore states,
                              @NonNull ConnectorValueRegistry values,
                              @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.states = states;
        this.values = values;
        this.listener = listener;
        String savedClientId = prefs.sprutClientId.get().trim();
        if (savedClientId.isEmpty()) {
            savedClientId = UUID.randomUUID().toString();
            prefs.sprutClientId.set(savedClientId);
        }
        clientId = savedClientId;
        mainConfigs = new HaBrickConfigStore(prefs);
        popupConfigs = new PopupItemConfigStore(prefs);
        catalogStore = new SprutHubCatalogStore(context);
        loadCachedCatalog();
    }

    public static boolean isSynced() { return lastSynced; }

    @NonNull public static String connectionDetail() { return lastDetail; }

    @Nullable public static SprutHubController active() { return activeInstance; }

    @NonNull public SprutCatalog catalog() { return catalog; }

    /** Re-evaluates only display rule sets that explicitly read another connector. */
    public void reapplyCrossSourceBindings() {
        SprutCatalog current = catalog;
        boolean fresh = sessionSynced;
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            applyMainBinding(item, current, fresh, null);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            if (item.displayRules == null || item.displayRules.usesOwnSource()) continue;
            applyPopupBinding(item, current, fresh, null);
        }
    }

    /** Applies changed settings without disturbing MQTT or Home Assistant connections. */
    public void reconfigure() {
        String next = settingsSignature();
        synchronized (lock) {
            if (Objects.equals(signature, next) && client != null) {
                // Config JSON may have changed even when transport settings did not.
                applyAllBindings(catalog, sessionSynced);
                return;
            }
            signature = next;
            stopped = false;
            generation++;
            closeClientLocked();
            releaseLocks();
            sessionSynced = false;
            lastSynced = false;
            hubRevision = Integer.MAX_VALUE;
            reconnectAttempt = 0;
            values.markConnectorStale(ConnectorType.SPRUTHUB,
                    SourceBinding.DEFAULT_CONNECTOR_ID);
            markBoundStatesStale();
            if (!isConfigured()) {
                if (activeInstance == this) activeInstance = null;
                updateState(State.DISABLED, prefs.sprutEnabled.get()
                        ? "configuration incomplete" : "disabled");
                return;
            }
            if (prefs.sprutKeepAwake.get()) acquireLocks();
            activeInstance = this;
            connectLocked(generation);
        }
    }

    public void stop() {
        synchronized (lock) {
            stopped = true;
            signature = "";
            generation++;
            closeClientLocked();
            releaseLocks();
            sessionSynced = false;
            lastSynced = false;
            values.markConnectorStale(ConnectorType.SPRUTHUB,
                    SourceBinding.DEFAULT_CONNECTOR_ID);
            markBoundStatesStale();
            if (activeInstance == this) activeInstance = null;
        }
        scheduler.shutdownNow();
        updateState(State.DISABLED, "stopped");
    }

    /** Forces a new authoritative accessory snapshot on the current authenticated session. */
    @NonNull
    public CompletableFuture<SprutCatalog> refreshCatalog() {
        SprutHubRpcClient current = client;
        if (current == null || !sessionSynced || !current.isOpen()) {
            CompletableFuture<SprutCatalog> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException(
                    "Sprut.hub has no authenticated current snapshot"));
            return failed;
        }
        updateState(State.SYNCING, "refreshing devices");
        return syncSnapshot(current, generation);
    }

    /** Executes a connector-neutral action binding directly against a writable characteristic. */
    @NonNull
    public CompletableFuture<JSONObject> execute(@NonNull ActionBinding binding) {
        if (binding.connectorType != ConnectorType.SPRUTHUB || !binding.isBound()
                || !SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId)) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("Not a Sprut.hub action"));
            return failed;
        }
        if (!ActionBinding.OPERATION_SET.equals(binding.operation)
                && !ActionBinding.OPERATION_TOGGLE.equals(binding.operation)) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(
                    "Sprut.hub supports only SET and TOGGLE actions"));
            return failed;
        }
        SprutHubRpcClient current = client;
        if (current == null || !sessionSynced || !current.isOpen()) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Sprut.hub has no current snapshot"));
            return failed;
        }
        final SprutPath path;
        try {
            path = SprutPath.parse(binding.resourceId);
        } catch (IllegalArgumentException e) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        SprutCatalog.Characteristic characteristic = catalog.find(path);
        if (characteristic == null || !characteristic.writable()) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Characteristic is not writable: " + path));
            return failed;
        }
        final Object value;
        try {
            value = SprutActionValue.resolve(binding, characteristic);
        } catch (IllegalArgumentException e) {
            CompletableFuture<JSONObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        // SprutActionValue has already resolved the declared format and validated metadata.
        // Preserve that exact Java wrapper on the wire. Re-coercing through valueType of the
        // previous live wrapper breaks legitimate legacy combinations such as format=bool with
        // a stale intValue current value (Boolean true must still become boolValue:true).
        CompletableFuture<JSONObject> request = writeCharacteristic(current, path, value, true);
        // The RPC response only confirms acceptance. A fresh EVENT_UPDATE (or fallback snapshot)
        // confirms physical state; refresh shortly in case this hub doesn't emit the event.
        request.thenRun(() -> {
            try {
                scheduler.schedule(() -> {
                    if (!stopped && current == client) refreshCatalog();
                }, 2, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // The service was stopped after the command response arrived.
            }
        });
        return request;
    }

    /**
     * Writes a typed value to one Sprut.hub characteristic without scheduling a catalog refresh.
     *
     * <p>This is the low-level path for continuous producers such as car telemetry. The caller
     * may inspect {@link #catalog()} before submitting to validate its own saved target signature;
     * this method always repeats the security-critical live checks: the authenticated session
     * must have an authoritative snapshot, the path must still exist, and the characteristic
     * must still be writable. A successful future confirms only that Sprut.hub accepted the RPC.
     * Interactive actions continue to use {@link #execute(ActionBinding)}, which additionally
     * schedules the existing two-second fallback snapshot.</p>
     */
    @NonNull
    public CompletableFuture<JSONObject> writeCharacteristic(@NonNull SprutPath path,
                                                              @NonNull Object value) {
        SprutHubRpcClient current = client;
        // Continuous producers validate and construct the exact Java wrapper themselves. Preserve
        // that wrapper on the wire instead of coercing it through a possibly stale runtime
        // valueType from the characteristic's previous value.
        return writeCharacteristic(current, path, value, true);
    }

    @NonNull
    private CompletableFuture<JSONObject> writeCharacteristic(
            @Nullable SprutHubRpcClient current, @NonNull SprutPath path,
            @NonNull Object value, boolean preserveValueType) {
        if (current == null || current != client || !sessionSynced || !current.isOpen()) {
            return failedFuture(new IOException("Sprut.hub has no current snapshot"));
        }
        SprutCatalog.Characteristic characteristic = catalog.find(path);
        if (characteristic == null) {
            return failedFuture(new IOException("Characteristic does not exist: " + path));
        }
        if (!characteristic.writable()) {
            return failedFuture(new IOException("Characteristic is not writable: " + path));
        }
        if (value == JSONObject.NULL) {
            return failedFuture(new IllegalArgumentException(
                    "Characteristic value must not be null"));
        }
        final JSONObject params;
        try {
            if (preserveValueType) {
                params = hubRevision > 12_500
                        ? SprutProtocolAdapter.buildCharacteristicUpdateParams(path, value)
                        : SprutProtocolAdapter.buildLegacyCharacteristicUpdateParams(path, value);
            } else {
                params = hubRevision > 12_500
                        ? SprutProtocolAdapter.buildCharacteristicUpdateParams(
                                characteristic, value)
                        : SprutProtocolAdapter.buildLegacyCharacteristicUpdateParams(
                                characteristic, value);
            }
        } catch (IllegalArgumentException error) {
            return failedFuture(error);
        }
        return current.call(params, 5_000L);
    }

    private void connectLocked(long expectedGeneration) {
        if (stopped || expectedGeneration != generation) return;
        // A newly opened socket is never allowed to inherit command authority from the previous
        // authenticated session. Only syncSnapshot() promotes it again after a full snapshot.
        sessionSynced = false;
        lastSynced = false;
        updateState(State.CONNECTING, "connecting");
        final SprutHubRpcClient[] owner = new SprutHubRpcClient[1];
        final SprutHubRpcClient next;
        try {
            next = new SprutHubRpcClient(prefs.sprutWebSocketUrl.get(), clientId,
                    new SprutHubRpcClient.Listener() {
                        @Override public void onOpen() {
                            SprutHubRpcClient source = owner[0];
                            if (!isCurrent(source, expectedGeneration)) return;
                            authenticateAndSync(source, expectedGeneration);
                        }

                        @Override public void onEvent(@NonNull JSONObject event) {
                            if (!isCurrent(owner[0], expectedGeneration)) return;
                            handleEventOrBuffer(event);
                        }

                        @Override public void onDisconnected(@NonNull String detail) {
                            SprutHubRpcClient source = owner[0];
                            synchronized (lock) {
                                if (!isCurrent(source, expectedGeneration)) return;
                                sessionSynced = false;
                                lastSynced = false;
                                // Invalidate the snapshot epoch before any in-flight combination
                                // can publish fresh values after this socket has already died.
                                closeClientLocked();
                            }
                            values.markConnectorStale(ConnectorType.SPRUTHUB,
                                    SourceBinding.DEFAULT_CONNECTOR_ID);
                            markBoundStatesStale();
                            updateState(State.ERROR, "disconnected: " + detail);
                            scheduleReconnect(expectedGeneration);
                        }
                    });
        } catch (RuntimeException e) {
            updateState(State.ERROR, "invalid URL: " + safeMessage(e));
            scheduleReconnect(expectedGeneration);
            return;
        }
        owner[0] = next;
        client = next;
        next.connect();
    }

    private boolean isCurrent(@Nullable SprutHubRpcClient candidate, long expectedGeneration) {
        return !stopped && expectedGeneration == generation && candidate != null
                && candidate == client;
    }

    private void authenticateAndSync(@NonNull SprutHubRpcClient current, long expectedGeneration) {
        updateState(State.AUTHENTICATING, "authorizing");
        current.clearSession();
        authenticateModern(current)
                .handle((token, failure) -> {
                    if (failure == null) return CompletableFuture.completedFuture(token);
                    current.clearSession();
                    return authenticateLegacy(current);
                })
                .thenCompose(stage -> stage)
                .thenCompose(token -> {
                    if (!isCurrent(current, expectedGeneration)) {
                        throw new CompletionException(new IOException("Superseded connection"));
                    }
                    current.setSession(token, null);
                    return selectHub(current, token)
                            .thenCompose(ignored -> current.registerClientInfo())
                            .thenApply(ignored -> token);
                })
                .thenCompose(token -> syncSnapshot(current, expectedGeneration))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        failCurrentSession(current, expectedGeneration, failure);
                    }
                });
    }

    @NonNull
    private CompletableFuture<String> authenticateModern(@NonNull SprutHubRpcClient current) {
        return current.call(SprutProtocolAdapter.buildAuthParams())
                .thenCompose(first -> {
                    requireQuestion(first, "auth", "QUESTION_TYPE_EMAIL");
                    return current.call(SprutProtocolAdapter.buildAuthAnswerParams(
                            prefs.sprutEmail.get().trim()));
                })
                .thenCompose(email -> {
                    requireQuestion(email, "answer", "QUESTION_TYPE_PASSWORD");
                    return current.call(SprutProtocolAdapter.buildAuthAnswerParams(
                            prefs.sprutPassword.get()));
                })
                .thenApply(this::extractToken);
    }

    @NonNull
    private CompletableFuture<String> authenticateLegacy(@NonNull SprutHubRpcClient current) {
        return current.call(SprutProtocolAdapter.buildLegacyLoginParams(
                        prefs.sprutEmail.get().trim()))
                .thenCompose(login -> {
                    requireQuestion(login, "login", "QUESTION_TYPE_PASSWORD");
                    return current.call(SprutProtocolAdapter.buildAuthAnswerParams(
                            prefs.sprutPassword.get()));
                })
                .thenApply(this::extractToken);
    }

    @NonNull
    private CompletableFuture<Void> selectHub(@NonNull SprutHubRpcClient current,
                                              @NonNull String token) {
        JSONObject hubList = object("hub", object("list", new JSONObject()));
        return current.call(hubList).handle((response, failure) -> {
            String configured = prefs.sprutHubSerial.get().trim();
            if (failure != null) {
                // The official relay guarantees hub.list. Do not hide a cloud protocol error and
                // then misleadingly report it as room.list/accessory.list during the snapshot.
                if (current.isOfficialCloud()) throw new CompletionException(failure);
                current.setSession(token, configured);
                return (Void) null;
            }
            JSONObject body = commandBody(response, "hub", "list");
            JSONArray hubs = body == null ? null : body.optJSONArray("hubs");
            // The cloud relay may retain an old offline row and a current online row for the
            // same physical serial. Scan duplicate rows instead of stopping at the first match.
            JSONObject selected = SprutHubSelection.select(hubs, configured);
            if (current.isOfficialCloud() && selected == null) {
                throw new CompletionException(new IOException(configured.isEmpty()
                        ? "hub.list: account has no hubs"
                        : "hub.list: configured hub " + configured + " was not found"));
            }
            String selectedSerial = configured;
            if (selected != null) {
                selectedSerial = firstNonBlank(SprutHubSelection.serialOf(selected), configured);
                if (SprutHubSelection.presenceOf(selected)
                        == SprutHubSelection.Presence.OFFLINE) {
                    // Presence is relay metadata, not command authorization. Probe the actual
                    // hub-routed RPC; it gives an authoritative error and also tolerates stale
                    // duplicate rows returned by hub.list.
                    Log.w(TAG, "Selected hub row is marked offline; probing route anyway: serial="
                            + selectedSerial + ", lastSeen=" + selected.opt("lastSeen"));
                    updateState(State.AUTHENTICATING,
                            "hub metadata says offline; probing current route");
                }
                JSONObject version = selected.optJSONObject("version");
                JSONObject currentVersion = version == null ? null : version.optJSONObject("current");
                if (currentVersion != null && currentVersion.has("revision")) {
                    hubRevision = currentVersion.optInt("revision", hubRevision);
                } else if (version != null && version.has("revision")) {
                    // Older hubs expose version.revision without the nested current object.
                    hubRevision = version.optInt("revision", hubRevision);
                } else if (selected.has("revision")) {
                    hubRevision = selected.optInt("revision", hubRevision);
                }
                if (configured.isEmpty() && !selectedSerial.isEmpty()) {
                    prefs.sprutHubSerial.set(selectedSerial);
                }
            }
            // Preserve the token already held by the transport while adding the selected serial.
            current.setSession(token, selectedSerial);
            return (Void) null;
        });
    }

    @NonNull
    private CompletableFuture<SprutCatalog> syncSnapshot(@NonNull SprutHubRpcClient current,
                                                         long expectedGeneration) {
        synchronized (lock) {
            if (!isCurrent(current, expectedGeneration)) {
                return failedFuture(new IOException("Superseded connection"));
            }
            CompletableFuture<SprutCatalog> existing = snapshotInFlight;
            if (existing != null && !existing.isDone()) return existing;

            updateState(State.SYNCING, "loading current device snapshot");
            final long expectedSnapshotEpoch = ++snapshotEpoch;
            synchronized (catalogLock) {
                loadingSnapshotEpoch = expectedSnapshotEpoch;
                bufferedEvents.clear();
                bufferedEventOverflow = false;
            }

            CompletableFuture<JSONObject> rooms = current.call(
                    SprutProtocolAdapter.buildRoomListParams(), 20_000L);
            String primaryExpand = current.isOfficialCloud()
                    ? SprutProtocolAdapter.EXPAND_COMMA : SprutProtocolAdapter.EXPAND_PLUS;
            String fallbackExpand = current.isOfficialCloud()
                    ? SprutProtocolAdapter.EXPAND_PLUS : SprutProtocolAdapter.EXPAND_COMMA;
            CompletableFuture<JSONObject> accessories = current.call(
                            SprutProtocolAdapter.buildAccessoryListParams(primaryExpand), 20_000L)
                    .handle((value, failure) -> failure == null
                            ? CompletableFuture.completedFuture(value)
                            : current.call(SprutProtocolAdapter.buildAccessoryListParams(
                                    fallbackExpand), 20_000L))
                    .thenCompose(stage -> stage);
            CompletableFuture<JSONObject> serviceTypes = current.call(
                            object("service", object("types", new JSONObject())), 20_000L)
                    .exceptionally(ignored -> new JSONObject());

            CompletableFuture<SprutCatalog> result = rooms
                    .thenCombine(accessories, SnapshotParts::new)
                    .thenCombine(serviceTypes, (parts, types) -> {
                        if (!isCurrent(current, expectedGeneration)) {
                            throw new CompletionException(
                                    new IOException("Superseded connection"));
                        }
                        SprutCatalog parsed = SprutProtocolAdapter.parseCatalog(parts.rooms,
                                parts.accessories);
                        synchronized (catalogLock) {
                            if (loadingSnapshotEpoch != expectedSnapshotEpoch) {
                                throw new CompletionException(
                                        new IOException("Superseded Sprut.hub snapshot"));
                            }
                            if (bufferedEventOverflow) {
                                loadingSnapshotEpoch = 0L;
                                bufferedEvents.clear();
                                throw new CompletionException(new IOException(
                                        "Too many Sprut.hub updates during snapshot"));
                            }
                            // Events can arrive after the server began preparing the list response.
                            // Replay them over that list before publishing one authoritative snapshot.
                            for (JSONObject event : bufferedEvents) {
                                int expectedUpdates =
                                        SprutProtocolAdapter.parseEventUpdates(event).size();
                                int appliedUpdates =
                                        SprutProtocolAdapter.applyEventUpdate(parsed, event);
                                if (appliedUpdates != expectedUpdates) {
                                    loadingSnapshotEpoch = 0L;
                                    bufferedEvents.clear();
                                    throw new CompletionException(new IOException(
                                            "Sprut.hub changed devices during snapshot"));
                                }
                            }
                            bufferedEvents.clear();
                            catalog = parsed;
                            sessionSynced = true;
                            loadingSnapshotEpoch = 0L;
                        }
                        JSONObject cached = new JSONObject();
                        try {
                            cached.put("schema", 1);
                            cached.put("savedAt", System.currentTimeMillis());
                            cached.put("hubSerial", prefs.sprutHubSerial.get());
                            cached.put("revision", hubRevision);
                            cached.put("rooms", parts.rooms);
                            cached.put("accessories", parts.accessories);
                            cached.put("serviceTypes", types);
                            catalogStore.save(cached);
                        } catch (JSONException | IOException e) {
                            Log.w(TAG, "Could not persist Sprut.hub catalog", e);
                        }
                        lastSynced = true;
                        reconnectAttempt = 0;
                        replaceRegistrySnapshot(parsed, true);
                        applyAllBindings(parsed, true);
                        listener.onCatalogChanged(parsed);
                        updateState(State.ONLINE,
                                parsed.accessories().size() + " devices synchronized");
                        return parsed;
                    });
            snapshotInFlight = result;
            result.whenComplete((ignored, failure) -> {
                synchronized (lock) {
                    if (snapshotInFlight == result) snapshotInFlight = null;
                }
                if (failure != null) {
                    synchronized (catalogLock) {
                        if (loadingSnapshotEpoch == expectedSnapshotEpoch) {
                            loadingSnapshotEpoch = 0L;
                            bufferedEvents.clear();
                            bufferedEventOverflow = false;
                        }
                    }
                    failCurrentSession(current, expectedGeneration, failure);
                }
            });
            return result;
        }
    }

    private void handleEventOrBuffer(@NonNull JSONObject event) {
        List<SprutProtocolAdapter.EventUpdate> updates =
                SprutProtocolAdapter.parseEventUpdates(event);
        if (updates.isEmpty()) return;
        SprutCatalog current;
        boolean needsRefresh;
        synchronized (catalogLock) {
            if (loadingSnapshotEpoch != 0L) {
                if (bufferedEvents.size() >= MAX_BUFFERED_EVENTS) {
                    bufferedEventOverflow = true;
                } else {
                    try {
                        bufferedEvents.add(new JSONObject(event.toString()));
                    } catch (JSONException malformed) {
                        bufferedEventOverflow = true;
                    }
                }
                return;
            }
            if (!sessionSynced) return;
            current = catalog;
            int applied = SprutProtocolAdapter.applyEventUpdate(current, event);
            needsRefresh = applied != updates.size();
        }
        for (SprutProtocolAdapter.EventUpdate update : updates) {
            SprutCatalog.Characteristic characteristic = current.find(update.path());
            if (characteristic != null) {
                values.upsert(toConnectorValue(current, characteristic, true));
                listener.onCharacteristicChanged(update.path());
            }
            applyBindingsForPath(current, update.path(), true);
        }
        if (needsRefresh && !stopped && current == catalog) {
            // A newly-added device/characteristic is not present in the immutable indexes.
            // A coalesced authoritative refresh discovers it; events during that refresh buffer.
            refreshCatalog();
        }
    }

    private void applyAllBindings(@NonNull SprutCatalog source, boolean fresh) {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            applyMainBinding(item, source, fresh, null);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            applyPopupBinding(item, source, fresh, null);
        }
    }

    private void applyBindingsForPath(@NonNull SprutCatalog source, @NonNull SprutPath changed,
                                      boolean fresh) {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            applyMainBinding(item, source, fresh, changed);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            applyPopupBinding(item, source, fresh, changed);
        }
    }

    private void applyMainBinding(HaBrickConfig item, SprutCatalog source, boolean fresh,
                                  @Nullable SprutPath changed) {
        SourceBinding binding = item.sourceBinding;
        if (!isSprutFamily(binding)) return;
        if (!isSprut(binding)) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        SprutPath path = parsePath(binding.resourceId);
        if (changed != null && (path == null || !changed.equals(path))) return;
        if (path == null) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        SprutCatalog.Characteristic characteristic = source.find(path);
        if (characteristic == null) {
            markTargetStale(AutomationContract.SCOPE_MAIN, item.id);
            return;
        }
        applyMapped(AutomationContract.SCOPE_MAIN, item.id, characteristic, binding,
                item.displayRules, item.actionBinding != null && item.actionBinding.isBound(),
                fresh);
    }

    private void applyPopupBinding(PopupItemConfig item, SprutCatalog source, boolean fresh,
                                   @Nullable SprutPath changed) {
        SourceBinding binding = item.sourceBinding;
        if (!isSprutFamily(binding)) return;
        if (!isSprut(binding)) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        SprutPath path = parsePath(binding.resourceId);
        if (changed != null && (path == null || !changed.equals(path))) return;
        if (path == null) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        SprutCatalog.Characteristic characteristic = source.find(path);
        if (characteristic == null) {
            markTargetStale(AutomationContract.SCOPE_POPUP, item.automationId);
            return;
        }
        applyMapped(AutomationContract.SCOPE_POPUP, item.automationId, characteristic, binding,
                item.displayRules, item.actionBinding != null && item.actionBinding.isBound(),
                fresh);
    }

    private void applyMapped(String scope, String id, SprutCatalog.Characteristic characteristic,
                             SourceBinding binding, @Nullable RuleSet configuredRules,
                             boolean hasAction, boolean fresh) {
        try {
            Input input = values.resolve(binding);
            RuleSet rules = configuredRules == null ? ScenarioPresets.raw() : configuredRules;
            Result result = rules.evaluate(input, values);
            Output output = result.output;
            String text = result.renderedText == null
                    ? String.valueOf(characteristic.currentValue()) : result.renderedText;
            if (!binding.unitSuffix.isEmpty() && !text.endsWith(binding.unitSuffix)) {
                text += binding.unitSuffix;
            } else if (binding.unitSuffix.isEmpty() && !characteristic.unit().isEmpty()
                    && !text.endsWith(characteristic.unit())
                    && SourceBinding.PRESENTATION_TEMPERATURE.equals(binding.presentation)) {
                text += " " + characteristic.unit();
            }
            JSONObject patch = new JSONObject();
            patch.put("text", text);
            patch.put("color", output.textColor == null ? JSONObject.NULL : output.textColor);
            patch.put("icon", output.icon == null ? JSONObject.NULL : output.icon);
            patch.put("background_color", output.backgroundColor == null
                    ? JSONObject.NULL : output.backgroundColor);
            patch.put("visible", output.visible == null || output.visible);
            patch.put("action_enabled", output.actionEnabled == null
                    ? hasAction : hasAction && output.actionEnabled);
            patch.put("fresh", fresh);
            patch.put("source", ConnectorType.SPRUTHUB.jsonName());
            patch.put("updated_at", System.currentTimeMillis());
            states.apply(scope, id, patch);
            listener.onStateChanged(scope, id);
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Could not map Sprut.hub value " + characteristic.path(), e);
        }
    }

    private void markBoundStatesStale() {
        for (HaBrickConfig item : mainConfigs.loadMain()) {
            if (!isSprutFamily(item.sourceBinding)) continue;
            states.markStale(AutomationContract.SCOPE_MAIN, item.id);
            listener.onStateChanged(AutomationContract.SCOPE_MAIN, item.id);
        }
        for (PopupItemConfig item : popupConfigs.load()) {
            if (!isSprutFamily(item.sourceBinding)) continue;
            states.markStale(AutomationContract.SCOPE_POPUP, item.automationId);
            listener.onStateChanged(AutomationContract.SCOPE_POPUP, item.automationId);
        }
    }

    private void markTargetStale(String scope, String id) {
        states.markStale(scope, id);
        listener.onStateChanged(scope, id);
    }

    private void loadCachedCatalog() {
        JSONObject cached = catalogStore.load();
        if (cached == null) return;
        JSONObject rooms = cached.optJSONObject("rooms");
        JSONObject accessories = cached.optJSONObject("accessories");
        if (rooms == null || accessories == null) return;
        try {
            catalog = SprutProtocolAdapter.parseCatalog(rooms, accessories);
            hubRevision = cached.optInt("revision", hubRevision);
            replaceRegistrySnapshot(catalog, false);
            applyAllBindings(catalog, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "Ignored incompatible Sprut.hub cache", e);
        }
    }

    private void scheduleReconnect(long expectedGeneration) {
        int index = Math.min(reconnectAttempt++, RECONNECT_DELAYS_MS.length - 1);
        long base = RECONNECT_DELAYS_MS[index];
        long jitter = (long) (base * (Math.random() * 0.4d - 0.2d));
        try {
            scheduler.schedule(() -> {
                synchronized (lock) {
                    if (stopped || expectedGeneration != generation || !isConfigured()) return;
                    closeClientLocked();
                    connectLocked(expectedGeneration);
                }
            }, Math.max(500L, base + jitter), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Normal when a late disconnect callback races with service shutdown.
        }
    }

    private void failCurrentSession(@NonNull SprutHubRpcClient current,
                                    long expectedGeneration, @NonNull Throwable failure) {
        synchronized (lock) {
            if (!isCurrent(current, expectedGeneration)) return;
            sessionSynced = false;
            lastSynced = false;
            closeClientLocked();
        }
        values.markConnectorStale(ConnectorType.SPRUTHUB,
                SourceBinding.DEFAULT_CONNECTOR_ID);
        markBoundStatesStale();
        updateState(State.ERROR, "sync failed: " + safeMessage(unwrap(failure)));
        scheduleReconnect(expectedGeneration);
    }

    private void closeClientLocked() {
        SprutHubRpcClient old = client;
        client = null;
        snapshotEpoch++;
        snapshotInFlight = null;
        synchronized (catalogLock) {
            loadingSnapshotEpoch = 0L;
            bufferedEvents.clear();
            bufferedEventOverflow = false;
        }
        if (old != null) old.close();
    }

    private boolean isConfigured() {
        return prefs.sprutEnabled.get()
                && !prefs.sprutWebSocketUrl.get().trim().isEmpty()
                && !prefs.sprutEmail.get().trim().isEmpty()
                && !prefs.sprutPassword.get().isEmpty();
    }

    private String settingsSignature() {
        return prefs.sprutEnabled.get() + "|" + prefs.sprutWebSocketUrl.get() + "|"
                + prefs.sprutEmail.get() + "|" + prefs.sprutPassword.get() + "|"
                + prefs.sprutHubSerial.get() + "|" + prefs.sprutKeepAwake.get();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StatusWidgetHA:spruthub");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "StatusWidgetHA:spruthub");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
    }

    private void updateState(State state, String detail) {
        lastDetail = detail == null ? "" : detail;
        if (state != State.ONLINE) lastSynced = false;
        listener.onConnectionChanged(state, lastDetail);
    }

    private void requireQuestion(JSONObject response, String operation, String expected) {
        JSONObject body = commandBody(response, "account", operation);
        JSONObject question = body == null ? null : body.optJSONObject("question");
        String actual = question == null ? "" : question.optString("type", "");
        if (!expected.equals(actual)) {
            throw new CompletionException(new IOException(
                    "Expected " + expected + ", got " + actual));
        }
    }

    private String extractToken(JSONObject response) {
        JSONObject answer = commandBody(response, "account", "answer");
        if (answer == null || !"ACCOUNT_RESPONSE_SUCCESS".equals(
                answer.optString("status", ""))) {
            throw new CompletionException(new IOException("Sprut.hub authentication failed"));
        }
        String token = answer.optString("token", "").trim();
        if (token.isEmpty()) throw new CompletionException(new IOException("Missing auth token"));
        return token;
    }

    @Nullable
    private static JSONObject commandBody(JSONObject response, String group, String operation) {
        JSONObject result = response.optJSONObject("result");
        if (result == null) result = response;
        JSONObject groupNode = result.optJSONObject(group);
        return groupNode == null ? null : groupNode.optJSONObject(operation);
    }

    @Nullable private static SprutPath parsePath(String raw) {
        try { return SprutPath.parse(raw); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static boolean isSprut(@Nullable SourceBinding binding) {
        return isSprutFamily(binding) && binding.isBound()
                && SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId);
    }

    private static boolean isSprutFamily(@Nullable SourceBinding binding) {
        return binding != null && binding.connectorType == ConnectorType.SPRUTHUB;
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) {
                result.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
            return result;
        } catch (JSONException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private void replaceRegistrySnapshot(@NonNull SprutCatalog source, boolean fresh) {
        ArrayList<ConnectorValue> snapshot = new ArrayList<>(source.characteristics().size());
        for (SprutCatalog.Characteristic characteristic : source.characteristics()) {
            snapshot.add(toConnectorValue(source, characteristic, fresh));
        }
        values.replaceSnapshot(ConnectorType.SPRUTHUB, SourceBinding.DEFAULT_CONNECTOR_ID,
                snapshot);
    }

    @NonNull
    private ConnectorValue toConnectorValue(@NonNull SprutCatalog source,
                                            @NonNull SprutCatalog.Characteristic characteristic,
                                            boolean fresh) {
        SprutPath path = characteristic.path();
        SprutCatalog.Accessory accessory = source.findAccessory(path.accessoryId());
        SprutCatalog.Service service = source.findService(path.accessoryId(), path.serviceId());
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("accessory_id", path.accessoryId());
        attributes.put("service_id", path.serviceId());
        attributes.put("characteristic_id", path.characteristicId());
        attributes.put("accessory_name", accessory == null ? "" : accessory.name());
        attributes.put("service_name", service == null ? "" : service.name());
        attributes.put("service_type", characteristic.serviceType());
        attributes.put("characteristic_name", characteristic.name());
        attributes.put("characteristic_type", characteristic.type());
        attributes.put("format", characteristic.format());
        attributes.put("events", characteristic.events());
        attributes.put("visible", characteristic.visible());
        attributes.put("hidden", characteristic.hidden());
        if (characteristic.minValue() != null) {
            attributes.put("min_value", characteristic.minValue());
        }
        if (characteristic.maxValue() != null) {
            attributes.put("max_value", characteristic.maxValue());
        }
        if (characteristic.minStep() != null) {
            attributes.put("min_step", characteristic.minStep());
        }
        boolean online = accessory == null || accessory.online();
        attributes.put("online", online);
        Object raw = characteristic.currentValue();
        return new ConnectorValue(ConnectorType.SPRUTHUB, SourceBinding.DEFAULT_CONNECTOR_ID,
                path.toString(), raw, fresh, online && characteristic.readable() && raw != null,
                characteristic.readable(), characteristic.writable(),
                characteristic.valueType().name(), characteristic.unit(), attributes,
                System.currentTimeMillis());
    }

    private static final class SnapshotParts {
        final JSONObject rooms;
        final JSONObject accessories;
        SnapshotParts(JSONObject rooms, JSONObject accessories) {
            this.rooms = rooms;
            this.accessories = accessories;
        }
    }
}
