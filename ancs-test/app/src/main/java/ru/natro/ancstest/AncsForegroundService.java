package ru.natro.ancstest;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Date;

/**
 * The only owner of {@link BluetoothDiagnostics}. The service remains alive when the Activity is
 * closed, keeps the ANCS GATT link, and exposes cached state to a newly bound Activity.
 */
public final class AncsForegroundService extends Service
        implements BluetoothDiagnostics.Listener {
    public static final String ACTION_START =
            "ru.natro.ancstest.action.START";
    public static final String ACTION_AUTO_ON =
            "ru.natro.ancstest.action.AUTO_ON";
    public static final String ACTION_AUTO_OFF =
            "ru.natro.ancstest.action.AUTO_OFF";
    public static final String ACTION_RECONNECT =
            "ru.natro.ancstest.action.RECONNECT";

    private static final String PREFS = "ancs_automatic";
    private static final String PREF_AUTO_ENABLED = "auto_enabled";
    private static final String PREF_VERIFIED_ADDRESS = "verified_address";
    private static final String PREF_VERIFIED_NAME = "verified_name";
    private static final String CHANNEL_ID = "kx11_ancs_connection";
    private static final int FOREGROUND_NOTIFICATION_ID = 9011;
    private static final int MAX_LOG_LINES = 1_200;
    private static final int MAX_NOTIFICATION_ITEMS = 120;
    private static final long HELPER_FALLBACK_DELAY_MS = 400L;
    private static final long STARTUP_BLUETOOTH_SETTLE_MS = 3_000L;
    private static final long STABLE_READY_RESET_MS = 50_000L;

    public interface UiListener {
        void onReset();
        void onState(String state);
        void onLog(String line);
        void onCandidates(List<BluetoothDiagnostics.Candidate> candidates);
        void onNotification(BluetoothDiagnostics.NotificationItem item);
        void onAppName(String appIdentifier, String displayName);
        void onAutoModeChanged(boolean enabled, String verifiedPeer);
    }

    private enum AutoPhase {
        IDLE,
        DIRECT_SAVED_PEER,
        HELPER_FILTERED_SCAN,
        READY,
        WAIT_RETRY
    }

    public final class LocalBinder extends Binder {
        public AncsForegroundService getService() {
            return AncsForegroundService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<UiListener> uiListeners = new LinkedHashSet<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final LinkedHashMap<Long, BluetoothDiagnostics.NotificationItem> notifications =
            new LinkedHashMap<>();
    private final ReconnectBackoff reconnectBackoff = new ReconnectBackoff();

    private BluetoothDiagnostics diagnostics;
    private List<BluetoothDiagnostics.Candidate> candidates = new ArrayList<>();
    private String currentState = "СЕРВИС ЗАПУСКАЕТСЯ";
    private boolean autoEnabled;
    private boolean automaticAttemptActive;
    private boolean helperFallbackUsed;
    private AutoPhase autoPhase = AutoPhase.IDLE;
    private Runnable reconnectRunnable;
    private Runnable startupRunnable;
    private Runnable stableReadyRunnable;
    private int automaticGeneration;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        autoEnabled = isAutoEnabled(this);
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(currentState));
        diagnostics = new BluetoothDiagnostics(this, this);
        registerReceiver(bluetoothStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        diagnostics.publishCapabilities();
        appendServiceLog("Foreground Service создан; auto=" + autoEnabled
                + "; Activity не владеет BLE-соединением");
        if (autoEnabled) {
            scheduleStartupAutomatic("запуск foreground-service рядом со штатным A2DP/HFP");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_AUTO_ON.equals(action)) {
            setAutoEnabled(true);
        } else if (ACTION_AUTO_OFF.equals(action)) {
            setAutoEnabled(false);
        } else if (ACTION_RECONNECT.equals(action)) {
            manualReconnect();
        } else if (autoEnabled) {
            ensureAutomaticConnection();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Deliberately keep BluetoothDiagnostics and its GATT link alive.
        return true;
    }

    @Override
    public void onDestroy() {
        cancelReconnect();
        cancelStartup();
        cancelStableReadyReset();
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (diagnostics != null) {
            diagnostics.close();
            diagnostics = null;
        }
        uiListeners.clear();
        super.onDestroy();
    }

    public static boolean isAutoEnabled(Context context) {
        return preferences(context).getBoolean(PREF_AUTO_ENABLED, true);
    }

    public static Intent startIntent(Context context) {
        return new Intent(context, AncsForegroundService.class).setAction(ACTION_START);
    }

    public void registerUiListener(UiListener listener) {
        if (listener == null) return;
        uiListeners.add(listener);
        listener.onReset();
        listener.onAutoModeChanged(autoEnabled, verifiedPeerLabel());
        listener.onState(currentState);
        listener.onCandidates(new ArrayList<>(candidates));
        for (BluetoothDiagnostics.NotificationItem item : notifications.values()) {
            listener.onNotification(item);
        }
        for (String line : logLines) listener.onLog(line);
    }

    public void unregisterUiListener(UiListener listener) {
        uiListeners.remove(listener);
    }

    public void setAutoEnabled(boolean enabled) {
        if (autoEnabled == enabled) {
            appendServiceLog("Автоподключение уже "
                    + (enabled ? "включено" : "выключено")
                    + "; повторная команда идемпотентна");
            notifyAutoMode();
            if (enabled) ensureAutomaticConnection();
            return;
        }
        if (!commitAutoEnabled(enabled, "setAutoEnabled")) {
            setServiceState("ОШИБКА СОХРАНЕНИЯ АВТОПОДКЛЮЧЕНИЯ");
            notifyAutoMode();
            return;
        }
        autoEnabled = enabled;
        notifyAutoMode();
        appendServiceLog("Автоподключение " + (enabled ? "ВКЛЮЧЕНО" : "ВЫКЛЮЧЕНО"));
        if (enabled) {
            cancelStartup();
            cancelStableReadyReset();
            reconnectBackoff.reset();
            helperFallbackUsed = false;
            startAutomaticConnection(true);
        } else {
            automaticAttemptActive = false;
            autoPhase = AutoPhase.IDLE;
            automaticGeneration++;
            cancelReconnect();
            cancelStartup();
            cancelStableReadyReset();
            if (diagnostics != null) {
                diagnostics.stopScan();
                diagnostics.stopAdvertising();
                diagnostics.disconnect();
            }
            setServiceState("АВТО ВЫКЛЮЧЕНО");
        }
    }

    public boolean isAutomaticEnabled() {
        return autoEnabled;
    }

    public String getVerifiedPeerLabel() {
        return verifiedPeerLabel();
    }

    public void manualReconnect() {
        if (!autoEnabled) {
            setAutoEnabled(true);
            return;
        }
        reconnectBackoff.reset();
        helperFallbackUsed = false;
        cancelStartup();
        cancelStableReadyReset();
        automaticGeneration++;
        automaticAttemptActive = false;
        autoPhase = AutoPhase.IDLE;
        cancelReconnect();
        if (diagnostics != null) diagnostics.disconnect();
        appendServiceLog("Ручное переподключение: сначала saved peer autoConnect=true");
        startAutomaticConnection(true);
    }

    public void onLocationPermissionAvailable() {
        if (autoEnabled && (autoPhase == AutoPhase.IDLE
                || autoPhase == AutoPhase.WAIT_RETRY)
                && startupRunnable == null && reconnectRunnable == null) {
            appendServiceLog("Геолокация доступна; автоматическое подключение можно продолжить");
            startAutomaticConnection(true);
        }
    }

    public void publishCapabilities() {
        if (diagnostics != null) diagnostics.publishCapabilities();
    }

    public void startDiagnosticScan() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.startScan();
    }

    public void stopScan() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.stopScan();
    }

    public void startHelperBootstrap() {
        if (!autoEnabled) {
            if (!commitAutoEnabled(true, "Helper bootstrap")) {
                setServiceState("ОШИБКА СОХРАНЕНИЯ АВТОПОДКЛЮЧЕНИЯ");
                return;
            }
            autoEnabled = true;
            notifyAutoMode();
        }
        automaticGeneration++;
        cancelReconnect();
        cancelStartup();
        cancelStableReadyReset();
        reconnectBackoff.reset();
        automaticAttemptActive = true;
        autoPhase = AutoPhase.HELPER_FILTERED_SCAN;
        helperFallbackUsed = true;
        if (!canUseBleScan()) {
            automaticAttemptActive = false;
            autoPhase = AutoPhase.IDLE;
            setServiceState("НУЖНА ГЕОЛОКАЦИЯ ДЛЯ HELPER");
            appendServiceLog("Helper bootstrap не запущен: Android 9 блокирует BLE scan");
            return;
        }
        appendServiceLog("Ручной Helper bootstrap относится к автоматике; "
                + "peer будет сохранён после ANCS READY");
        if (diagnostics != null) diagnostics.startIphoneHelperFallback();
    }

    public void startIncomingConnectionTest() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.startIncomingConnectionTest();
    }

    public void stopAdvertising() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.stopAdvertising();
    }

    public void samePeerAttach() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.connect(null);
    }

    public void requestBond() {
        if (diagnostics != null) diagnostics.requestBond();
    }

    public void repeatDiscovery() {
        if (diagnostics != null) diagnostics.refreshAndReconnect();
    }

    public void disconnectManually() {
        beginManualDiagnostic();
        if (diagnostics != null) diagnostics.disconnect();
    }

    public void clearCachedOutput() {
        logLines.clear();
        notifications.clear();
        for (UiListener listener : listenerSnapshot()) listener.onReset();
        appendServiceLog("Кеш журнала и уведомлений очищен");
    }

    public List<String> getLogLinesSnapshot() {
        return new ArrayList<>(logLines);
    }

    @Override
    public void onState(String state) {
        currentState = state == null ? "" : state;
        updateForegroundNotification();
        for (UiListener listener : listenerSnapshot()) listener.onState(currentState);
        handleAutomaticState(currentState);
    }

    @Override
    public void onLog(String line) {
        appendLogToCache(line);
        for (UiListener listener : listenerSnapshot()) listener.onLog(line);
    }

    @Override
    public void onCandidates(List<BluetoothDiagnostics.Candidate> updated) {
        candidates = updated == null ? new ArrayList<>() : new ArrayList<>(updated);
        for (UiListener listener : listenerSnapshot()) {
            listener.onCandidates(new ArrayList<>(candidates));
        }
    }

    @Override
    public void onNotification(BluetoothDiagnostics.NotificationItem item) {
        if (item == null) return;
        notifications.remove(item.uid);
        notifications.put(item.uid, item);
        trimNotifications();
        for (UiListener listener : listenerSnapshot()) listener.onNotification(item);
    }

    @Override
    public void onAppName(String appIdentifier, String displayName) {
        for (Map.Entry<Long, BluetoothDiagnostics.NotificationItem> entry
                : new ArrayList<>(notifications.entrySet())) {
            BluetoothDiagnostics.NotificationItem old = entry.getValue();
            if (!appIdentifier.equals(old.appIdentifier)) continue;
            notifications.put(entry.getKey(), new BluetoothDiagnostics.NotificationItem(
                    old.uid, old.eventId, old.categoryId, old.appIdentifier,
                    displayName, old.title, old.message, old.date));
        }
        for (UiListener listener : listenerSnapshot()) {
            listener.onAppName(appIdentifier, displayName);
        }
    }

    private void ensureAutomaticConnection() {
        if (!autoEnabled || autoPhase == AutoPhase.READY
                || autoPhase == AutoPhase.DIRECT_SAVED_PEER
                || autoPhase == AutoPhase.HELPER_FILTERED_SCAN
                || reconnectRunnable != null || startupRunnable != null) {
            return;
        }
        startAutomaticConnection(false);
    }

    private void startAutomaticConnection(boolean userRequested) {
        if (!autoEnabled || diagnostics == null) return;
        automaticGeneration++;
        cancelReconnect();
        cancelStableReadyReset();
        automaticAttemptActive = true;
        String address = savedAddress();
        if (!address.isEmpty()) {
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                appendServiceLog("Сохранённый Bluetooth address имеет неверный формат; "
                        + "только в этом случае запись удаляется");
                if (!clearSavedPeer()) {
                    automaticAttemptActive = false;
                    scheduleReconnect("не удалось удалить повреждённый saved peer");
                    return;
                }
                startHelperFallback("повреждённый сохранённый адрес");
                return;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                automaticAttemptActive = false;
                autoPhase = AutoPhase.IDLE;
                setServiceState("АВТО · ЖДУ ВКЛЮЧЕНИЯ BLUETOOTH");
                appendServiceLog("Saved peer сохранён; Bluetooth OFF, жду ACTION_STATE_CHANGED");
                return;
            }
            autoPhase = AutoPhase.DIRECT_SAVED_PEER;
            appendServiceLog("Автоподключение: direct connectGatt(autoConnect=true) "
                    + "к сохранённому peer "
                    + address + "; Helper и BLE scan не используются");
            if (!diagnostics.connectSavedIphone(address)) {
                if (autoPhase == AutoPhase.DIRECT_SAVED_PEER) {
                    appendServiceLog("Временный direct connect start failure; saved peer сохранён");
                    startHelperFallback("direct connect не запустился");
                }
            }
            return;
        }
        if (userRequested) {
            appendServiceLog("Verified iPhone ещё не сохранён; нужен одноразовый bootstrap Helper");
        }
        startHelperFallback("первичная привязка");
    }

    private void startHelperFallback(String reason) {
        if (!autoEnabled || diagnostics == null) return;
        if (helperFallbackUsed) {
            scheduleReconnect("Helper fallback уже использован в этой сессии · " + reason);
            return;
        }
        if (!canUseBleScan()) {
            automaticAttemptActive = false;
            autoPhase = AutoPhase.IDLE;
            setServiceState("ОТКРОЙТЕ ПРИЛОЖЕНИЕ · НУЖНА ГЕОЛОКАЦИЯ");
            appendServiceLog("Для одноразового Helper fallback нужен BLE scan: "
                    + "разрешите геолокацию и включите Location services");
            return;
        }
        // The one-shot budget is consumed only when a real filtered scan can start. A missing
        // runtime permission must not prevent the first bootstrap after the user grants it.
        helperFallbackUsed = true;
        final int generation = automaticGeneration;
        autoPhase = AutoPhase.HELPER_FILTERED_SCAN;
        setServiceState("FALLBACK · ОТКРОЙТЕ IPHONE HELPER");
        appendServiceLog("Один filtered scan fallback через Helper v4 · " + reason);
        main.postDelayed(() -> {
            if (!autoEnabled || generation != automaticGeneration
                    || autoPhase != AutoPhase.HELPER_FILTERED_SCAN
                    || diagnostics == null) {
                return;
            }
            diagnostics.startIphoneHelperFallback();
        }, HELPER_FALLBACK_DELAY_MS);
    }

    private void handleAutomaticState(String state) {
        if (!autoEnabled || !automaticAttemptActive || state == null) return;
        if (state.contains("ANCS READY")) {
            automaticAttemptActive = true;
            autoPhase = AutoPhase.READY;
            saveVerifiedPeerAfterReady();
            scheduleStableReadyReset();
            return;
        }
        if (state.contains("AUTO · ЖДУ SAVED PEER")) {
            cancelStableReadyReset();
            automaticGeneration++;
            autoPhase = AutoPhase.DIRECT_SAVED_PEER;
            appendServiceLog("Long-lived autoConnect остаётся зарегистрирован; "
                    + "новый connectGatt не создаётся");
            return;
        }
        if (!isTerminalAutomaticState(state)) return;

        cancelStableReadyReset();
        if (state.contains("SERVICE CHANGED · RECONNECT")) {
            scheduleReconnect(state);
            return;
        }
        if (autoPhase == AutoPhase.DIRECT_SAVED_PEER && !helperFallbackUsed) {
            appendServiceLog("Прямой saved-peer connect не восстановил ANCS: " + state);
            startHelperFallback("direct saved-peer failure");
        } else {
            scheduleReconnect(state);
        }
    }

    private void scheduleReconnect(String reason) {
        if (!autoEnabled || reconnectRunnable != null) return;
        cancelStableReadyReset();
        automaticAttemptActive = false;
        autoPhase = AutoPhase.WAIT_RETRY;
        long delay = reconnectBackoff.nextDelayMs();
        int generation = ++automaticGeneration;
        setServiceState("АВТОПОВТОР ЧЕРЕЗ " + Math.max(1L, delay / 1_000L) + " С");
        appendServiceLog("Reconnect backoff=" + delay + " ms · " + reason
                + ". Следующая попытка снова начнётся с saved peer без Helper scan");
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (!autoEnabled || generation != automaticGeneration) return;
            startAutomaticConnection(false);
        };
        main.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = null;
    }

    private void scheduleStartupAutomatic(String reason) {
        if (!autoEnabled || startupRunnable != null) return;
        int generation = ++automaticGeneration;
        setServiceState("АВТОСТАРТ ЧЕРЕЗ 3 С");
        appendServiceLog("Жду " + STARTUP_BLUETOOTH_SETTLE_MS
                + " ms перед BLE attach: " + reason);
        startupRunnable = () -> {
            startupRunnable = null;
            if (!autoEnabled || generation != automaticGeneration) return;
            startAutomaticConnection(false);
        };
        main.postDelayed(startupRunnable, STARTUP_BLUETOOTH_SETTLE_MS);
    }

    private void cancelStartup() {
        if (startupRunnable != null) main.removeCallbacks(startupRunnable);
        startupRunnable = null;
    }

    private void scheduleStableReadyReset() {
        cancelStableReadyReset();
        final int generation = automaticGeneration;
        stableReadyRunnable = () -> {
            stableReadyRunnable = null;
            if (!autoEnabled || generation != automaticGeneration
                    || autoPhase != AutoPhase.READY
                    || !automaticAttemptActive || diagnostics == null
                    || !diagnostics.isAncsReady()) {
                return;
            }
            reconnectBackoff.reset();
            helperFallbackUsed = false;
            appendServiceLog("ANCS READY стабилен " + STABLE_READY_RESET_MS
                    + " ms; reconnect backoff сброшен");
        };
        main.postDelayed(stableReadyRunnable, STABLE_READY_RESET_MS);
        appendServiceLog("Reconnect backoff будет сброшен только после "
                + STABLE_READY_RESET_MS + " ms стабильного ANCS READY");
    }

    private void cancelStableReadyReset() {
        if (stableReadyRunnable != null) main.removeCallbacks(stableReadyRunnable);
        stableReadyRunnable = null;
    }

    private void beginManualDiagnostic() {
        automaticGeneration++;
        automaticAttemptActive = false;
        autoPhase = AutoPhase.IDLE;
        cancelReconnect();
        cancelStartup();
        cancelStableReadyReset();
    }

    private void saveVerifiedPeerAfterReady() {
        if (diagnostics == null) return;
        String address = diagnostics.getVerifiedPeerAddress();
        if (address.isEmpty()) {
            appendServiceLog("ANCS READY получен, но адрес peer пуст; сохранение пропущено");
            return;
        }
        String name = diagnostics.getVerifiedPeerName();
        boolean committed = preferences(this).edit()
                .putString(PREF_VERIFIED_ADDRESS, address)
                .putString(PREF_VERIFIED_NAME, name)
                .commit();
        if (committed) {
            appendServiceLog("SharedPreferences commit peer=true · сохранён только после "
                    + "ANCS READY: " + peerLabel(name, address));
            notifyAutoMode();
        } else {
            appendServiceLog("ОШИБКА SharedPreferences commit peer=false; "
                    + "ANCS работает сейчас, но peer не переживёт перезапуск");
        }
    }

    private boolean clearSavedPeer() {
        boolean committed = preferences(this).edit()
                .remove(PREF_VERIFIED_ADDRESS)
                .remove(PREF_VERIFIED_NAME)
                .commit();
        appendServiceLog("SharedPreferences commit clear peer=" + committed);
        if (committed) notifyAutoMode();
        return committed;
    }

    private boolean commitAutoEnabled(boolean enabled, String reason) {
        boolean committed = preferences(this).edit()
                .putBoolean(PREF_AUTO_ENABLED, enabled)
                .commit();
        appendServiceLog("SharedPreferences commit auto=" + committed
                + " · value=" + enabled + " · " + reason);
        return committed;
    }

    private String savedAddress() {
        return preferences(this).getString(PREF_VERIFIED_ADDRESS, "").trim();
    }

    private String verifiedPeerLabel() {
        SharedPreferences values = preferences(this);
        return peerLabel(values.getString(PREF_VERIFIED_NAME, ""),
                values.getString(PREF_VERIFIED_ADDRESS, ""));
    }

    private static String peerLabel(String name, String address) {
        String safeName = name == null ? "" : name.trim();
        String safeAddress = address == null ? "" : address.trim();
        if (safeAddress.isEmpty()) return "не сохранён";
        return safeName.isEmpty() ? safeAddress : safeName + " · " + safeAddress;
    }

    private boolean canUseBleScan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        LocationManager location =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
            return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean isTerminalAutomaticState(String state) {
        return state.contains("CONNECT RETURNED NULL")
                || state.contains("CONNECT TIMEOUT")
                || state.contains("CONNECT EXCEPTION")
                || state.contains("SAVED PEER CONFLICT")
                || state.contains("PEER CONFLICT")
                || state.contains("GPS-STYLE FAILED")
                || state.contains("IPHONE DISCONNECTED")
                || state.contains("IPHONE BLE НЕ НАЙДЕН")
                || state.contains("SCAN_FAILED_")
                || state.contains("SCAN UNAVAILABLE")
                || state.contains("SCAN EXCEPTION")
                || state.contains("PAIRING FAILED")
                || state.contains("LE BOND FAILED")
                || state.contains("DISCOVERY_FAILED_")
                || state.contains("DISCOVERY_START_FAILED")
                || state.contains("DISCOVERY_TIMEOUT")
                || state.contains("TEST SERVICE НЕ НАЙДЕН")
                || state.contains("SECURE CHAR НЕ НАЙДЕН")
                || state.contains("SECURE READ START FAILED")
                || state.contains("SECURE READ FAILED")
                || state.contains("BOND_START_FAILED")
                || state.contains("ANCS AUTH FAILED ПОСЛЕ BOND")
                || state.contains("ANCS_INCOMPLETE")
                || state.contains("SUBSCRIBE_EXCEPTION")
                || state.contains("SUBSCRIBE_LOCAL_FAILED")
                || state.contains("CCCD_START_FAILED")
                || state.contains("CCCD_WRITE_EXCEPTION")
                || state.contains("CCCD_FAILED_")
                || state.contains("SERVICE CHANGED · RECONNECT")
                || state.contains("ANCS WAIT TIMEOUT");
    }

    private void setServiceState(String state) {
        currentState = state;
        updateForegroundNotification();
        for (UiListener listener : listenerSnapshot()) listener.onState(state);
    }

    private void notifyAutoMode() {
        String peer = verifiedPeerLabel();
        for (UiListener listener : listenerSnapshot()) {
            listener.onAutoModeChanged(autoEnabled, peer);
        }
    }

    private void appendServiceLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + "  SERVICE · " + message;
        appendLogToCache(line);
        for (UiListener listener : listenerSnapshot()) listener.onLog(line);
    }

    private void appendLogToCache(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
    }

    private void trimNotifications() {
        while (notifications.size() > MAX_NOTIFICATION_ITEMS) {
            Iterator<Long> iterator = notifications.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private List<UiListener> listenerSnapshot() {
        return new ArrayList<>(uiListeners);
    }

    private void createNotificationChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "KX11 ANCS", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Автоматическое BLE/ANCS-подключение iPhone");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String state) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bluetooth_service)
                .setContentTitle("KX11 ANCS")
                .setContentText(state)
                .setContentIntent(contentIntent)
                .setOngoing(autoEnabled)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateForegroundNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(currentState));
        }
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR);
            appendServiceLog("Bluetooth adapter state=" + state);
            if (state == BluetoothAdapter.STATE_ON && autoEnabled) {
                helperFallbackUsed = false;
                cancelReconnect();
                cancelStartup();
                cancelStableReadyReset();
                scheduleStartupAutomatic("Bluetooth STATE_ON; даю штатным профилям восстановиться");
            } else if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                automaticGeneration++;
                automaticAttemptActive = false;
                autoPhase = AutoPhase.IDLE;
                cancelReconnect();
                cancelStartup();
                cancelStableReadyReset();
                if (diagnostics != null) {
                    diagnostics.stopScan();
                    diagnostics.stopAdvertising();
                    diagnostics.disconnect();
                }
                if (autoEnabled) {
                    setServiceState("АВТО · ЖДУ ВКЛЮЧЕНИЯ BLUETOOTH");
                }
            }
        }
    };

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
