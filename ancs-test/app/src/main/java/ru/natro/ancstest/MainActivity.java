package ru.natro.ancstest;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity
        implements AncsForegroundService.UiListener {
    private static final int REQUEST_LOCATION = 1001;
    private static final int MAX_LOG_LINES = 500;
    private static final int MAX_NOTIFICATION_ROWS = 80;
    private static final long UI_FLUSH_INTERVAL_MS = 250L;

    private TextView statusView;
    private TextView selectedView;
    private TextView logView;
    private ScrollView logScroll;
    private Button autoButton;
    private ArrayAdapter<String> candidateAdapter;
    private ArrayAdapter<String> notificationAdapter;
    private final List<BluetoothDiagnostics.Candidate> candidates = new ArrayList<>();
    private final List<String> candidateRows = new ArrayList<>();
    private final LinkedHashMap<Long, BluetoothDiagnostics.NotificationItem> notifications =
            new LinkedHashMap<>();
    private final List<String> notificationRows = new ArrayList<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean logFlushScheduled;
    private boolean notificationFlushScheduled;
    private final Runnable logFlusher = () -> {
        logFlushScheduled = false;
        if (logView == null) return;
        logView.setText(TextUtils.join("\n", logLines));
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    };
    private final Runnable notificationFlusher = () -> {
        notificationFlushScheduled = false;
        rebuildNotificationsNow();
    };

    private AncsForegroundService service;
    private boolean serviceBound;
    private BluetoothDiagnostics.Candidate selectedCandidate;
    private boolean startScanAfterPermission;
    private boolean startIphoneAfterPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        Intent serviceIntent = AncsForegroundService.startIntent(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        ensureLocationPermission(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(AncsForegroundService.startIntent(this),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) {
            service.unregisterUiListener(this);
            unbindService(serviceConnection);
            serviceBound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(logFlusher);
        ui.removeCallbacks(notificationFlusher);
        super.onDestroy();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            AncsForegroundService.LocalBinder local =
                    (AncsForegroundService.LocalBinder) binder;
            service = local.getService();
            serviceBound = true;
            service.registerUiListener(MainActivity.this);
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED && locationEnabled()) {
                service.onLocationPermissionAvailable();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            service = null;
            onState("СЕРВИС ОТКЛЮЧЁН");
        }
    };

    @Override
    public void onReset() {
        ui.removeCallbacks(notificationFlusher);
        notificationFlushScheduled = false;
        notifications.clear();
        if (notificationAdapter != null) rebuildNotificationsNow();
        ui.removeCallbacks(logFlusher);
        logFlushScheduled = false;
        logLines.clear();
        if (logView != null) logView.setText("");
        appendInstruction();
    }

    @Override
    public void onAutoModeChanged(boolean enabled, String verifiedPeer) {
        if (autoButton != null) {
            autoButton.setText(enabled
                    ? "Автоподключение: ВКЛ"
                    : "Автоподключение: ВЫКЛ");
        }
        selectedView.setText("Сохранённый iPhone: " + verifiedPeer
                + " · музыка/звонки остаются в штатном Classic Bluetooth");
    }

    @Override
    public void onState(String state) {
        statusView.setText(state);
        if (state.contains("READY") || state.contains("ГОТОВО")) {
            statusView.setBackgroundColor(Color.rgb(27, 94, 32));
        } else if (state.contains("FAIL") || state.contains("NO_")
                || state.contains("ERROR") || state.contains("НЕ НАЙДЕН")) {
            statusView.setBackgroundColor(Color.rgb(183, 28, 28));
        } else {
            statusView.setBackgroundColor(Color.rgb(21, 101, 192));
        }
    }

    @Override
    public void onLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
        scheduleLogFlush();
    }

    @Override
    public void onCandidates(List<BluetoothDiagnostics.Candidate> updated) {
        String selectedAddress = selectedCandidate == null ? "" : selectedCandidate.address;
        candidates.clear();
        candidates.addAll(updated);
        candidateRows.clear();
        int selectedPosition = -1;
        for (int index = 0; index < candidates.size(); index++) {
            BluetoothDiagnostics.Candidate candidate = candidates.get(index);
            candidateRows.add(candidate.displayText());
            if (candidate.address.equalsIgnoreCase(selectedAddress)) {
                selectedCandidate = candidate;
                selectedPosition = index;
            }
        }
        candidateAdapter.notifyDataSetChanged();
        if (selectedPosition >= 0) {
            selectedView.setText("Только для диагностики scan: " + selectedCandidate.name
                    + " · " + selectedCandidate.address);
        }
    }

    @Override
    public void onNotification(BluetoothDiagnostics.NotificationItem item) {
        notifications.remove(item.uid);
        notifications.put(item.uid, item);
        trimNotifications();
        scheduleNotificationFlush();
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
        scheduleNotificationFlush();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        onLog("Разрешение геолокации для BLE scan: " + granted);
        boolean shouldStart = startScanAfterPermission;
        boolean shouldStartIphone = startIphoneAfterPermission;
        startScanAfterPermission = false;
        startIphoneAfterPermission = false;
        if (granted && shouldStartIphone) {
            ensureIphonePeripheralPermission();
        } else if (granted && shouldStart) {
            ensureLocationPermission(true);
        }
        if (granted && service != null) service.onLocationPermissionAvailable();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(16, 24, 32));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("KX11 ANCS TEST v9");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setText("ЗАПУСК");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(14), dp(7), dp(14), dp(7));
        statusView.setBackgroundColor(Color.rgb(21, 101, 192));
        header.addView(statusView);
        root.addView(header);

        selectedView = new TextView(this);
        selectedView.setText("Подключение к foreground-service…");
        selectedView.setTextColor(Color.rgb(207, 216, 220));
        selectedView.setTextSize(13);
        selectedView.setPadding(0, dp(5), 0, dp(4));
        root.addView(selectedView);

        HorizontalScrollView buttonScroll = new HorizontalScrollView(this);
        buttonScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        autoButton = addButton(buttons, "Автоподключение: …",
                view -> withService(value ->
                        value.setAutoEnabled(!value.isAutomaticEnabled())));
        addButton(buttons, "Переподключить",
                view -> withService(AncsForegroundService::manualReconnect));
        addButton(buttons, "Helper bootstrap",
                view -> ensureIphonePeripheralPermission());
        addButton(buttons, "Возможности",
                view -> withService(AncsForegroundService::publishCapabilities));
        addButton(buttons, "BLE scan", view -> ensureLocationPermission(true));
        addButton(buttons, "Стоп scan",
                view -> withService(AncsForegroundService::stopScan));
        addButton(buttons, "Старый входящий тест",
                view -> withService(AncsForegroundService::startIncomingConnectionTest));
        addButton(buttons, "Стоп рекламы",
                view -> withService(AncsForegroundService::stopAdvertising));
        addButton(buttons, "Same-peer attach",
                view -> withService(AncsForegroundService::samePeerAttach));
        addButton(buttons, "Явно связать LE",
                view -> withService(AncsForegroundService::requestBond));
        addButton(buttons, "Повторить discovery",
                view -> withService(AncsForegroundService::repeatDiscovery));
        addButton(buttons, "Отключить",
                view -> withService(AncsForegroundService::disconnectManually));
        addButton(buttons, "Копировать лог", view -> copyLog());
        addButton(buttons, "Сохранить лог", view -> saveLog());
        addButton(buttons, "Очистить", view -> clearOutput());
        buttonScroll.addView(buttons);
        root.addView(buttonScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(0, dp(8), 0, 0);

        candidateAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_activated_1, candidateRows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextSize(13);
                row.setTextColor(Color.rgb(33, 33, 33));
                row.setPadding(dp(8), dp(7), dp(8), dp(7));
                row.setMinHeight(dp(58));
                return row;
            }
        };
        ListView candidateList = new ListView(this);
        candidateList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        candidateList.setAdapter(candidateAdapter);
        candidateList.setOnItemClickListener((AdapterView<?> parent, View view,
                                               int position, long id) -> {
            selectedCandidate = candidates.get(position);
            selectedView.setText("Только для диагностики scan: " + selectedCandidate.name
                    + " · " + selectedCandidate.address);
            onLog("Выбрано устройство: " + selectedCandidate.displayText()
                    .replace('\n', ' '));
            if (!selectedCandidate.rawAdvertisement.isEmpty()) {
                onLog("RAW ADV: " + selectedCandidate.rawAdvertisement);
            }
        });
        content.addView(panel("BLE-устройства", candidateList), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        notificationAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, notificationRows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextSize(13);
                row.setTextColor(Color.rgb(33, 33, 33));
                row.setPadding(dp(8), dp(7), dp(8), dp(7));
                return row;
            }
        };
        ListView notificationList = new ListView(this);
        notificationList.setAdapter(notificationAdapter);
        LinearLayout.LayoutParams notificationsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f);
        notificationsParams.setMargins(dp(8), 0, 0, 0);
        content.addView(panel("ANCS / диагностические события", notificationList),
                notificationsParams);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.rgb(30, 30, 30));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f);
        logParams.setMargins(dp(8), 0, 0, 0);
        content.addView(panel("Диагностический журнал", logScroll), logParams);

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private View panel(String headerText, View content) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(7));
        panel.setBackgroundResource(ru.natro.ancstest.R.drawable.panel_background);
        TextView header = new TextView(this);
        header.setText(headerText);
        header.setTextColor(Color.rgb(13, 71, 161));
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(5));
        panel.addView(header);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private Button addButton(LinearLayout parent, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(5), 0);
        parent.addView(button, params);
        return button;
    }

    private interface ServiceAction {
        void run(AncsForegroundService service);
    }

    private void withService(ServiceAction action) {
        AncsForegroundService active = service;
        if (active == null) {
            Toast.makeText(this, "Сервис ещё подключается", Toast.LENGTH_SHORT).show();
            return;
        }
        action.run(active);
    }

    private void ensureLocationPermission(boolean startAfterGrant) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            boolean locationEnabled = locationEnabled();
            onLog("Location services для BLE scan: " + locationEnabled);
            if (!locationEnabled) {
                onState("LOCATION_OFF");
                onLog("BLE scan не запущен: Location services выключены");
                Toast.makeText(this,
                        "Для BLE scan на Android 9 включите геолокацию",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (startAfterGrant) {
                withService(AncsForegroundService::startDiagnosticScan);
            } else if (service != null) {
                service.onLocationPermissionAvailable();
            }
            return;
        }
        startIphoneAfterPermission = false;
        startScanAfterPermission = startAfterGrant;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_LOCATION);
    }

    private void ensureIphonePeripheralPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            boolean locationEnabled = locationEnabled();
            onLog("Location services для GPS-style BLE scan: " + locationEnabled);
            if (!locationEnabled) {
                onState("LOCATION_OFF");
                onLog("Включите геолокацию Android 9 — без неё BLE scan заблокирован");
                Toast.makeText(this,
                        "Для поиска iPhone по BLE включите геолокацию",
                        Toast.LENGTH_LONG).show();
                return;
            }
            withService(AncsForegroundService::startHelperBootstrap);
            return;
        }
        startScanAfterPermission = false;
        startIphoneAfterPermission = true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_LOCATION);
    }

    private boolean locationEnabled() {
        LocationManager location =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) return location.isLocationEnabled();
            return location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void rebuildNotificationsNow() {
        notificationRows.clear();
        List<BluetoothDiagnostics.NotificationItem> values =
                new ArrayList<>(notifications.values());
        for (int index = values.size() - 1; index >= 0; index--) {
            notificationRows.add(values.get(index).displayText());
        }
        notificationAdapter.notifyDataSetChanged();
    }

    private void trimNotifications() {
        while (notifications.size() > MAX_NOTIFICATION_ROWS) {
            Iterator<Long> iterator = notifications.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private void scheduleLogFlush() {
        if (logFlushScheduled) return;
        logFlushScheduled = true;
        ui.postDelayed(logFlusher, UI_FLUSH_INTERVAL_MS);
    }

    private void scheduleNotificationFlush() {
        if (notificationFlushScheduled) return;
        notificationFlushScheduled = true;
        ui.postDelayed(notificationFlusher, UI_FLUSH_INTERVAL_MS);
    }

    private void copyLog() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "KX11 ANCS Test log", TextUtils.join("\n", logLines)));
        Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
    }

    private void saveLog() {
        File directory = getExternalFilesDir(null);
        if (directory == null) directory = getFilesDir();
        File file = new File(directory, "KX11_ANCS_Test_log.txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(TextUtils.join("\n", logLines));
            writer.write('\n');
            Toast.makeText(this, "Лог: " + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            onLog("Журнал сохранён: " + file.getAbsolutePath());
        } catch (IOException failure) {
            onLog("Ошибка сохранения журнала: " + failure);
            Toast.makeText(this, "Не удалось сохранить журнал",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void clearOutput() {
        withService(AncsForegroundService::clearCachedOutput);
    }

    private void appendInstruction() {
        onLog("1) Авто-режим работает в foreground-service и не закрывает BLE"
                + " при выходе из этого экрана.");
        onLog("2) После первого ANCS READY сервис сохраняет verified iPhone."
                + " Далее после загрузки он делает прямой connectGatt(autoConnect=true)"
                + " без scan и без запущенного Helper.");
        onLog("3) Helper v4 нужен только для первой привязки или аварийного fallback:"
                + " нажмите «Helper bootstrap» и включите рекламу на iPhone.");
        onLog("4) В системных настройках отдельное BLE-подключение создавать не надо;"
                + " Classic Bluetooth для музыки/звонков можно оставить.");
        onLog("5) Если ANCS нет в первом discovery ежедневного соединения,"
                + " сервис ждёт Service Changed и не требует D2D PAIR/SECURE.");
        onLog("6) После ANCS READY отправьте на iPhone одно новое уведомление."
                + " Очередь ограничена и замедлена, чтобы ECARX UI не зависал.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
