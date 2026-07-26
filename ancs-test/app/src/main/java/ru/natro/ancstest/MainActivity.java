package ru.natro.ancstest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity implements BluetoothDiagnostics.Listener {
    private static final int REQUEST_LOCATION = 1001;
    private static final int MAX_LOG_LINES = 1_200;

    private TextView statusView;
    private TextView selectedView;
    private TextView logView;
    private ScrollView logScroll;
    private ArrayAdapter<String> candidateAdapter;
    private ArrayAdapter<String> notificationAdapter;
    private final List<BluetoothDiagnostics.Candidate> candidates = new ArrayList<>();
    private final List<String> candidateRows = new ArrayList<>();
    private final LinkedHashMap<Long, BluetoothDiagnostics.NotificationItem> notifications =
            new LinkedHashMap<>();
    private final List<String> notificationRows = new ArrayList<>();
    private final ArrayList<String> logLines = new ArrayList<>();

    private BluetoothDiagnostics diagnostics;
    private BluetoothDiagnostics.Candidate selectedCandidate;
    private boolean startScanAfterPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        diagnostics = new BluetoothDiagnostics(this, this);
        appendInstruction();
        diagnostics.publishCapabilities();
        ensureLocationPermission(false);
    }

    @Override
    protected void onDestroy() {
        if (diagnostics != null) diagnostics.close();
        super.onDestroy();
    }

    @Override
    public void onState(String state) {
        statusView.setText(state);
        if (state.contains("READY") || state.contains("ГОТОВО")) {
            statusView.setBackgroundColor(Color.rgb(27, 94, 32));
        } else if (state.contains("FAILED") || state.contains("NO_")
                || state.contains("ERROR") || state.contains("НЕ НАЙДЕН")) {
            statusView.setBackgroundColor(Color.rgb(183, 28, 28));
        } else {
            statusView.setBackgroundColor(Color.rgb(21, 101, 192));
        }
    }

    @Override
    public void onLog(String line) {
        logLines.add(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.remove(0);
        logView.setText(TextUtils.join("\n", logLines));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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
            selectedView.setText("Выбрано: " + selectedCandidate.name
                    + " · " + selectedCandidate.address);
        }
    }

    @Override
    public void onNotification(BluetoothDiagnostics.NotificationItem item) {
        notifications.put(item.uid, item);
        rebuildNotifications();
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
        rebuildNotifications();
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
        startScanAfterPermission = false;
        if (granted && shouldStart) ensureLocationPermission(true);
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
        title.setText("KX11 ANCS TEST v3");
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
        selectedView.setText("PAIR/SECURE = verified; ANCS target = correlation hypothesis");
        selectedView.setTextColor(Color.rgb(207, 216, 220));
        selectedView.setTextSize(13);
        selectedView.setPadding(0, dp(5), 0, dp(4));
        root.addView(selectedView);

        HorizontalScrollView buttonScroll = new HorizontalScrollView(this);
        buttonScroll.setHorizontalScrollBarEnabled(true);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Возможности", view -> diagnostics.publishCapabilities());
        addButton(buttons, "BLE scan", view -> ensureLocationPermission(true));
        addButton(buttons, "Стоп scan", view -> diagnostics.stopScan());
        addButton(buttons, "Ждать iPhone", view -> diagnostics.startIncomingConnectionTest());
        addButton(buttons, "Стоп рекламы", view -> diagnostics.stopAdvertising());
        addButton(buttons, "Подключить target",
                view -> diagnostics.connect(selectedCandidate));
        addButton(buttons, "LE bonding verified", view -> diagnostics.requestBond());
        addButton(buttons, "Обновить GATT", view -> diagnostics.refreshAndReconnect());
        addButton(buttons, "Отключить", view -> diagnostics.disconnect());
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
            selectedView.setText("Выбрано: " + selectedCandidate.name
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
        content.addView(panel("Уведомления iPhone", notificationList), notificationsParams);

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

    private void addButton(LinearLayout parent, String title, View.OnClickListener listener) {
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
            if (startAfterGrant) diagnostics.startScan();
            return;
        }
        startScanAfterPermission = startAfterGrant;
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

    private void rebuildNotifications() {
        notificationRows.clear();
        List<BluetoothDiagnostics.NotificationItem> values =
                new ArrayList<>(notifications.values());
        for (int index = values.size() - 1; index >= 0; index--) {
            notificationRows.add(values.get(index).displayText());
        }
        notificationAdapter.notifyDataSetChanged();
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
        notifications.clear();
        rebuildNotifications();
        logLines.clear();
        logView.setText("");
        appendInstruction();
    }

    private void appendInstruction() {
        onLog("1) Нажмите «Возможности», затем «Ждать iPhone».");
        onLog("2) На iPhone откройте KX11 ANCS Helper — он подключится"
                + " с RequiresANCS и сам отправит PAIR.");
        onLog("3) Если используется LightBlue: подключитесь к service d2d9e4b0…f01"
                + " и в CONTROL d2d9e4b2…f01 запишите ASCII PAIR.");
        onLog("4) Подтвердите запросы iPhone. Затем в SECURE"
                + " d2d9e4b3…f01 запишите ASCII ANCS или прочитайте значение.");
        onLog("5) SECURE ATT OK доказывает шифрование BLE-link."
                + " После ANCS READY отправьте новое уведомление.");
        onLog("6) Входящий peer без команды PAIR не используется и не может заменить verified peer.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
