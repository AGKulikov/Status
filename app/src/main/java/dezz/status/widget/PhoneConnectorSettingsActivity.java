/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.settings.SettingsBackNavigation;
import dezz.status.widget.sprut.SprutActionValue;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutPath;
import dezz.status.widget.sprut.SprutProtocolAdapter;

/**
 * Apple-style, defensive settings surface for the paired iPhone connector.
 *
 * <p>The transport owns Bluetooth/MAP/notification collection. This Activity only persists user
 * intent and asks the running {@link WidgetService} to re-read preferences. ANCS and MAP are
 * authorized by the selected iPhone over Bluetooth, so this surface deliberately requests no
 * Android notification-listener or SMS permission. Every adapter/device read is allowed to fail
 * because vendor Android 9 Bluetooth stacks frequently throw while starting up.</p>
 */
public final class PhoneConnectorSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private MaterialSwitch connectorEnabled;
    private MaterialSwitch notificationsEnabled;
    private MaterialSwitch messagesEnabled;
    private MaterialSwitch includeNotificationText;
    private MaterialSwitch sprutPresenceEnabled;
    private TextView selectedDeviceValue;
    private TextView selectedSprutPathValue;
    private TextView diagnostics;
    @NonNull private String selectedDeviceAddress = "";
    @NonNull private String selectedSprutPath = "";
    @NonNull private SprutCatalog sprutCatalog = SprutCatalog.empty();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        selectedDeviceAddress = clean(preferences.phoneDeviceAddress.get());
        selectedSprutPath = clean(preferences.phoneSprutPresencePath.get());
        View screen = buildScreen();
        setContentView(screen);
        SettingsBackNavigation.install(this, screen);
        reloadSprutCatalog();
        refreshDeviceSummary();
        refreshSprutSummary();
        refreshDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences == null) return;
        reloadSprutCatalog();
        refreshDeviceSummary();
        refreshSprutSummary();
        refreshDiagnostics();
    }

    @NonNull
    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(R.color.settings_background));

        LinearLayout page = column();
        page.setPadding(dp(24), dp(22), dp(24), dp(56));
        scroll.addView(page, matchWrap());

        page.addView(text(getString(R.string.phone_connector_title), 30, Typeface.BOLD),
                matchWrap());
        page.addView(secondary(getString(R.string.phone_connector_subtitle), 15),
                topMargin(5));

        page.addView(sectionTitle(getString(R.string.phone_section_connection)),
                topMargin(24));
        LinearLayout connectionRows = column();
        connectorEnabled = new MaterialSwitch(this);
        connectorEnabled.setChecked(preferences.phoneConnectorEnabled.get());
        addSwitchRow(connectionRows, connectorEnabled,
                R.string.phone_enable_title, R.string.phone_enable_subtitle, false);

        LinearLayout deviceRow = clickableRow(this::chooseBondedDevice);
        LinearLayout deviceLabels = column();
        deviceLabels.addView(text(getString(R.string.phone_device_title), 17, Typeface.NORMAL),
                matchWrap());
        selectedDeviceValue = secondary("", 14);
        deviceLabels.addView(selectedDeviceValue, topMargin(3));
        deviceRow.addView(deviceLabels, weighted());
        TextView disclosure = text("›", 30, Typeface.NORMAL);
        disclosure.setTextColor(color(R.color.settings_tertiary_text));
        deviceRow.addView(disclosure, wrapWrap());
        connectionRows.addView(separator(), separatorParams());
        connectionRows.addView(deviceRow, matchWrap());
        page.addView(card(connectionRows), topMargin(7));

        LinearLayout bluetoothActions = row();
        bluetoothActions.addView(actionButton(
                getString(R.string.phone_choose_device), this::chooseBondedDevice), weighted());
        bluetoothActions.addView(actionButton(
                getString(R.string.phone_open_bluetooth), this::openBluetoothSettings), weighted());
        page.addView(bluetoothActions, topMargin(10));

        page.addView(sectionTitle(getString(R.string.phone_section_data)), topMargin(24));
        LinearLayout dataRows = column();
        notificationsEnabled = new MaterialSwitch(this);
        notificationsEnabled.setChecked(preferences.phoneNotificationsEnabled.get());
        addSwitchRow(dataRows, notificationsEnabled,
                R.string.phone_notifications_title, R.string.phone_notifications_subtitle, false);
        messagesEnabled = new MaterialSwitch(this);
        messagesEnabled.setChecked(preferences.phoneMessagesEnabled.get());
        addSwitchRow(dataRows, messagesEnabled,
                R.string.phone_messages_title, R.string.phone_messages_subtitle, true);
        includeNotificationText = new MaterialSwitch(this);
        includeNotificationText.setChecked(
                preferences.phoneIncludeNotificationText.get());
        addSwitchRow(dataRows, includeNotificationText,
                R.string.phone_text_title, R.string.phone_text_subtitle, true);
        page.addView(card(dataRows), topMargin(7));

        messagesEnabled.setOnCheckedChangeListener((button, checked) ->
                refreshDiagnostics());
        notificationsEnabled.setOnCheckedChangeListener((button, checked) ->
                refreshDiagnostics());

        page.addView(sectionTitle(getString(R.string.phone_section_sprut)), topMargin(24));
        LinearLayout sprutRows = column();
        sprutPresenceEnabled = new MaterialSwitch(this);
        sprutPresenceEnabled.setChecked(preferences.phoneSprutPresenceEnabled.get());
        addSwitchRow(sprutRows, sprutPresenceEnabled,
                R.string.phone_sprut_enable_title, R.string.phone_sprut_enable_subtitle, false);
        LinearLayout pathRow = clickableRow(this::chooseSprutAccessory);
        LinearLayout pathLabels = column();
        pathLabels.addView(text(getString(R.string.phone_sprut_target_title),
                17, Typeface.NORMAL), matchWrap());
        selectedSprutPathValue = secondary("", 14);
        pathLabels.addView(selectedSprutPathValue, topMargin(3));
        pathRow.addView(pathLabels, weighted());
        TextView pathDisclosure = text("›", 30, Typeface.NORMAL);
        pathDisclosure.setTextColor(color(R.color.settings_tertiary_text));
        pathRow.addView(pathDisclosure, wrapWrap());
        sprutRows.addView(separator(), separatorParams());
        sprutRows.addView(pathRow, matchWrap());
        page.addView(card(sprutRows), topMargin(7));

        LinearLayout sprutActions = row();
        sprutActions.addView(actionButton(getString(R.string.phone_sprut_choose),
                this::chooseSprutAccessory), weighted());
        sprutActions.addView(actionButton(getString(R.string.phone_sprut_clear),
                this::clearSprutTarget), weighted());
        page.addView(sprutActions, topMargin(10));

        page.addView(sectionTitle(getString(R.string.phone_section_diagnostics)),
                topMargin(24));
        diagnostics = secondary("", 15);
        diagnostics.setPadding(dp(16), dp(13), dp(16), dp(13));
        LinearLayout diagnosticContent = column();
        diagnosticContent.addView(diagnostics, matchWrap());
        page.addView(card(diagnosticContent), topMargin(7));

        TextView privacy = secondary(getString(R.string.phone_privacy_hint), 13);
        privacy.setPadding(dp(8), 0, dp(8), 0);
        page.addView(privacy, topMargin(16));

        MaterialButton apply = actionButton(getString(R.string.phone_apply), this::save);
        apply.setTextColor(color(android.R.color.white));
        apply.setBackgroundTintList(ColorStateList.valueOf(color(R.color.settings_accent)));
        apply.setMinHeight(dp(52));
        page.addView(apply, topMargin(20));
        return scroll;
    }

    private void addSwitchRow(@NonNull LinearLayout parent, @NonNull MaterialSwitch toggle,
                              int titleRes, int subtitleRes, boolean separated) {
        if (separated) parent.addView(separator(), separatorParams());
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(12), dp(11));
        row.setMinimumHeight(dp(68));
        LinearLayout labels = column();
        labels.addView(text(getString(titleRes), 17, Typeface.NORMAL), matchWrap());
        labels.addView(secondary(getString(subtitleRes), 13), topMargin(2));
        row.addView(labels, weighted());
        toggle.setContentDescription(getString(titleRes));
        row.addView(toggle, wrapWrap());
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        parent.addView(row, matchWrap());
    }

    private void chooseBondedDevice() {
        List<BondedPhone> devices = bondedDevices();
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.phone_no_devices_title)
                    .setMessage(R.string.phone_no_devices_message)
                    .setPositiveButton(R.string.phone_open_bluetooth,
                            (dialog, which) -> openBluetoothSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String[] labels = new String[devices.size()];
        int checked = -1;
        for (int index = 0; index < devices.size(); index++) {
            BondedPhone value = devices.get(index);
            labels[index] = value.name + "\n" + maskedAddress(value.address);
            if (sameAddress(selectedDeviceAddress, value.address)) checked = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.phone_choose_device)
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    selectedDeviceAddress = devices.get(position).address;
                    dialog.dismiss();
                    refreshDeviceSummary();
                    refreshDiagnostics();
                }));
        dialog.show();
    }

    private void refreshDeviceSummary() {
        if (selectedDeviceValue == null) return;
        if (selectedDeviceAddress.isEmpty()) {
            selectedDeviceValue.setText(R.string.phone_no_device);
            return;
        }
        BondedPhone selected = selectedBondedPhone();
        selectedDeviceValue.setText(selected == null
                ? getString(R.string.phone_device_unavailable,
                        maskedAddress(selectedDeviceAddress))
                : selected.name + " · " + maskedAddress(selected.address));
    }

    private void openBluetoothSettings() {
        safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void safeStart(@NonNull Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.system_settings_not_available,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDiagnostics() {
        if (diagnostics == null) return;
        BluetoothState bluetooth = bluetoothState();
        BondedPhone selected = selectedBondedPhone();
        int values = 0;
        int fresh = 0;
        int available = 0;
        boolean phoneConnected = false;
        String ancsStatus = "";
        String mapStatus = "";
        WidgetService service = WidgetService.getInstance();
        if (service != null) {
            for (ConnectorValue value : service.connectorValueSnapshot()) {
                if (value.connectorType != ConnectorType.PHONE) continue;
                values++;
                if (value.fresh) fresh++;
                if (value.available && value.readable) available++;
                if ("connected".equals(value.resourceId)) {
                    phoneConnected = Boolean.TRUE.equals(value.rawValue);
                } else if ("diagnostics.ancs".equals(value.resourceId)) {
                    ancsStatus = clean(value.rawValue == null
                            ? "" : String.valueOf(value.rawValue));
                } else if ("diagnostics.sms".equals(value.resourceId)) {
                    mapStatus = clean(value.rawValue == null
                            ? "" : String.valueOf(value.rawValue));
                }
            }
        }

        StringBuilder result = new StringBuilder();
        result.append(line(bluetooth.supported, getString(R.string.phone_diag_adapter),
                bluetooth.supported
                        ? (bluetooth.enabled
                        ? getString(R.string.phone_diag_enabled)
                        : getString(R.string.phone_diag_disabled))
                        : getString(R.string.phone_diag_not_supported)));
        result.append('\n').append(line(selected != null,
                getString(R.string.phone_diag_device),
                selected != null
                        ? selected.name + " · " + maskedAddress(selected.address)
                        : selectedDeviceAddress.isEmpty()
                        ? getString(R.string.phone_no_device)
                        : getString(R.string.phone_diag_not_bonded)));
        result.append('\n').append(line(phoneConnected,
                getString(R.string.phone_diag_connection),
                phoneConnected
                        ? getString(R.string.phone_diag_connected_selected)
                        : getString(R.string.phone_diag_disconnected_selected)));
        result.append('\n').append(line(!messagesEnabled.isChecked()
                        || "ready".equals(mapStatus),
                getString(R.string.phone_diag_sms),
                !messagesEnabled.isChecked()
                        ? getString(R.string.phone_diag_not_required)
                        : "ready".equals(mapStatus)
                        ? getString(R.string.phone_diag_map_ready)
                        : localizedMapStatus(mapStatus)));
        result.append('\n').append(line(!notificationsEnabled.isChecked()
                        || "ready".equals(ancsStatus),
                getString(R.string.phone_diag_notifications),
                !notificationsEnabled.isChecked()
                        ? getString(R.string.phone_diag_not_required)
                        : "ready".equals(ancsStatus)
                        ? getString(R.string.phone_diag_ancs_receiving)
                        : getString(R.string.phone_diag_ancs_iphone,
                                localizedAncsStatus(ancsStatus))));
        result.append('\n').append(line(fresh > 0,
                getString(R.string.phone_diag_values),
                getString(R.string.phone_diag_values_format, available, fresh, values)));
        diagnostics.setText(result);
    }

    @NonNull
    private String line(boolean ok, @NonNull String title, @NonNull String value) {
        return (ok ? "✓  " : "—  ") + title + ": " + value;
    }

    @NonNull
    private String localizedAncsStatus(@NonNull String status) {
        switch (status) {
            case "disabled":
                return getString(R.string.phone_diag_ancs_disabled);
            case "no_configured_phone":
                return getString(R.string.phone_diag_ancs_no_phone);
            case "not_bonded":
                return getString(R.string.phone_diag_ancs_not_bonded);
            case "bluetooth_off":
                return getString(R.string.phone_diag_ancs_bluetooth);
            case "service_unavailable":
            case "characteristic_unavailable":
                return getString(R.string.phone_diag_ancs_unavailable);
            case "connecting":
            case "discovering":
            case "subscribing":
            case "starting":
            case "retrying":
                return getString(R.string.phone_diag_ancs_waiting);
            case "disconnected":
            case "stopped":
                return getString(R.string.phone_diag_ancs_disconnected);
            default:
                return status.isEmpty()
                        ? getString(R.string.phone_diag_ancs_waiting) : status;
        }
    }

    @NonNull
    private String localizedMapStatus(@NonNull String status) {
        switch (status) {
            case "disabled":
                return getString(R.string.phone_diag_not_required);
            case "no_configured_phone":
                return getString(R.string.phone_diag_ancs_no_phone);
            case "not_bonded":
                return getString(R.string.phone_diag_ancs_not_bonded);
            case "bluetooth_off":
                return getString(R.string.phone_diag_ancs_bluetooth);
            case "profile_unavailable":
            case "service_unavailable":
            case "message_access_unavailable":
                return getString(R.string.phone_diag_map_unavailable);
            case "connecting":
            case "starting":
            case "waiting_for_map":
            case "waiting_for_phone":
            case "disconnected":
            case "stopped":
                return getString(R.string.phone_diag_map_waiting);
            default:
                return status.isEmpty()
                        ? getString(R.string.phone_diag_map_waiting) : status;
        }
    }

    private void reloadSprutCatalog() {
        SprutHubController active = SprutHubController.active();
        if (active != null && !active.catalog().accessories().isEmpty()) {
            sprutCatalog = active.catalog();
            return;
        }
        sprutCatalog = SprutCatalog.empty();
        try {
            JSONObject cached = new SprutHubCatalogStore(this).load();
            JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
            JSONObject accessories = cached == null ? null
                    : cached.optJSONObject("accessories");
            if (rooms != null && accessories != null) {
                sprutCatalog = SprutProtocolAdapter.parseCatalog(rooms, accessories);
            }
        } catch (RuntimeException ignored) {
            sprutCatalog = SprutCatalog.empty();
        }
    }

    private void chooseSprutAccessory() {
        reloadSprutCatalog();
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (SprutCatalog.Accessory accessory : sprutCatalog.accessories()) {
            if (hasWritableBooleanTarget(accessory)) accessories.add(accessory);
        }
        accessories.sort(Comparator
                .comparing((SprutCatalog.Accessory value) ->
                                sprutCatalog.roomNameFor(value),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(value -> first(value.name(), value.model(),
                                "Устройство " + value.id()),
                        String.CASE_INSENSITIVE_ORDER));
        if (accessories.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.phone_sprut_no_targets_title)
                    .setMessage(R.string.phone_sprut_no_targets_message)
                    .setPositiveButton(R.string.phone_sprut_open_settings,
                            (dialog, which) -> startActivity(
                                    new Intent(this, SprutHubSettingsActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        String[] labels = new String[accessories.size()];
        for (int index = 0; index < accessories.size(); index++) {
            SprutCatalog.Accessory accessory = accessories.get(index);
            String room = clean(sprutCatalog.roomNameFor(accessory));
            labels[index] = (room.isEmpty() ? "" : room + " → ")
                    + first(accessory.name(), accessory.model(),
                    "Устройство " + accessory.id());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_sprut_accessory_title)
                .setItems(labels, (dialog, which) ->
                        chooseSprutService(accessories.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseSprutService(@NonNull SprutCatalog.Accessory accessory) {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableBooleanTarget(service)) services.add(service);
        }
        services.sort(Comparator.comparing(value ->
                        first(value.name(), value.type(), "Сервис " + value.id()),
                String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[services.size()];
        for (int index = 0; index < services.size(); index++) {
            SprutCatalog.Service service = services.get(index);
            labels[index] = first(service.name(), service.type(), "Сервис " + service.id())
                    + "\n" + service.type() + " · sId=" + service.id();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_sprut_service_title)
                .setItems(labels, (dialog, which) ->
                        chooseSprutCharacteristic(accessory, services.get(which)))
                .setNegativeButton(R.string.phone_back,
                        (dialog, which) -> chooseSprutAccessory())
                .show();
    }

    private void chooseSprutCharacteristic(@NonNull SprutCatalog.Accessory accessory,
                                           @NonNull SprutCatalog.Service service) {
        List<SprutCatalog.Characteristic> values = new ArrayList<>();
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (isWritableBooleanTarget(characteristic)) values.add(characteristic);
        }
        String[] labels = new String[values.size()];
        for (int index = 0; index < values.size(); index++) {
            SprutCatalog.Characteristic value = values.get(index);
            labels[index] = first(value.name(), value.type(), "Переключатель")
                    + "\n" + value.type() + " · " + value.format()
                    + "\npath: " + value.path().stableId();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_sprut_characteristic_title)
                .setItems(labels, (dialog, which) -> {
                    selectedSprutPath = values.get(which).path().stableId();
                    sprutPresenceEnabled.setChecked(true);
                    refreshSprutSummary();
                })
                .setNegativeButton(R.string.phone_back,
                        (dialog, which) -> chooseSprutService(accessory))
                .show();
    }

    private void clearSprutTarget() {
        selectedSprutPath = "";
        sprutPresenceEnabled.setChecked(false);
        refreshSprutSummary();
    }

    private void refreshSprutSummary() {
        if (selectedSprutPathValue == null) return;
        if (selectedSprutPath.isEmpty()) {
            selectedSprutPathValue.setText(R.string.phone_sprut_not_selected);
            return;
        }
        String description = selectedSprutPath;
        try {
            SprutPath path = SprutPath.parse(selectedSprutPath);
            SprutCatalog.Characteristic characteristic = sprutCatalog.find(path);
            if (characteristic != null) {
                description = first(characteristic.name(), characteristic.type(),
                        selectedSprutPath) + "\npath: " + selectedSprutPath;
            } else {
                description = getString(R.string.phone_sprut_saved_path, selectedSprutPath);
            }
        } catch (IllegalArgumentException ignored) {
            description = getString(R.string.phone_sprut_invalid_path, selectedSprutPath);
        }
        selectedSprutPathValue.setText(description);
    }

    private static boolean hasWritableBooleanTarget(
            @NonNull SprutCatalog.Accessory accessory) {
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableBooleanTarget(service)) return true;
        }
        return false;
    }

    private static boolean hasWritableBooleanTarget(@NonNull SprutCatalog.Service service) {
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (isWritableBooleanTarget(characteristic)) return true;
        }
        return false;
    }

    /** Uses the runtime exporter's exact rule, so every selectable target remains writable. */
    static boolean isWritableBooleanTarget(
            @NonNull SprutCatalog.Characteristic characteristic) {
        return characteristic.writable()
                && SprutActionValue.isBooleanLike(characteristic);
    }

    private void save() {
        if (connectorEnabled.isChecked() && selectedDeviceAddress.isEmpty()) {
            Toast.makeText(this, R.string.phone_choose_required,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (sprutPresenceEnabled.isChecked()) {
            if (selectedSprutPath.isEmpty()) {
                Toast.makeText(this, R.string.phone_sprut_choose_required,
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                SprutPath.parse(selectedSprutPath);
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(this, R.string.phone_sprut_invalid_saved,
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        preferences.phoneConnectorEnabled.set(connectorEnabled.isChecked());
        preferences.phoneDeviceAddress.set(selectedDeviceAddress);
        preferences.phoneNotificationsEnabled.set(notificationsEnabled.isChecked());
        preferences.phoneMessagesEnabled.set(messagesEnabled.isChecked());
        preferences.phoneIncludeNotificationText.set(includeNotificationText.isChecked());
        preferences.phoneSprutPresenceEnabled.set(sprutPresenceEnabled.isChecked());
        preferences.phoneSprutPresencePath.set(selectedSprutPath);

        WidgetService service = WidgetService.getInstance();
        if (service != null) service.applyPreferences();

        Toast.makeText(this, R.string.phone_saved, Toast.LENGTH_LONG).show();
        refreshDiagnostics();
    }

    @SuppressLint("MissingPermission")
    @NonNull
    private List<BondedPhone> bondedDevices() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return Collections.emptyList();
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (paired == null || paired.isEmpty()) return Collections.emptyList();
            List<BondedPhone> result = new ArrayList<>(paired.size());
            for (BluetoothDevice device : paired) {
                if (device == null) continue;
                String address = clean(device.getAddress());
                if (address.isEmpty()) continue;
                // Classify with the real advertised name. Applying the "iPhone" display fallback
                // first would make every unnamed paired headset/accessory look like an iPhone and
                // would let the user bind this connector to the wrong physical device.
                String advertisedName = clean(device.getName());
                if (!looksLikePhone(device, advertisedName)) continue;
                String name = first(advertisedName, getString(R.string.phone_unknown_device));
                result.add(new BondedPhone(name, address));
            }
            result.sort(Comparator
                    .comparing((BondedPhone value) -> value.name,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(value -> value.address));
            return result;
        } catch (RuntimeException deniedOrBrokenAdapter) {
            return Collections.emptyList();
        }
    }

    @Nullable
    private BondedPhone selectedBondedPhone() {
        if (selectedDeviceAddress.isEmpty()) return null;
        for (BondedPhone value : bondedDevices()) {
            if (sameAddress(selectedDeviceAddress, value.address)) return value;
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    @NonNull
    private static BluetoothState bluetoothState() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter == null
                    ? new BluetoothState(false, false)
                    : new BluetoothState(true, adapter.isEnabled());
        } catch (RuntimeException deniedOrBrokenAdapter) {
            return new BluetoothState(false, false);
        }
    }

    @NonNull
    private MaterialCardView card(@NonNull View content) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setCardBackgroundColor(color(R.color.settings_group_background));
        card.setStrokeWidth(0);
        card.addView(content);
        return card;
    }

    @NonNull
    private LinearLayout clickableRow(@NonNull Runnable action) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        row.setMinimumHeight(dp(68));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(ContextCompat.getDrawable(this, R.drawable.settings_row_ripple));
        row.setOnClickListener(view -> action.run());
        return row;
    }

    @NonNull
    private MaterialButton actionButton(@NonNull String label, @NonNull Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(color(R.color.settings_accent));
        button.setBackgroundTintList(ColorStateList.valueOf(
                color(R.color.settings_group_background)));
        button.setStrokeColor(ColorStateList.valueOf(color(R.color.settings_separator)));
        button.setStrokeWidth(dp(1));
        button.setCornerRadius(dp(13));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(46));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    @NonNull
    private TextView sectionTitle(@NonNull String value) {
        TextView view = text(value.toUpperCase(Locale.getDefault()), 13, Typeface.BOLD);
        view.setTextColor(color(R.color.settings_secondary_text));
        view.setPadding(dp(8), 0, dp(8), 0);
        return view;
    }

    @NonNull
    private TextView secondary(@NonNull String value, int size) {
        TextView view = text(value, size, Typeface.NORMAL);
        view.setTextColor(color(R.color.settings_secondary_text));
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    @NonNull
    private TextView text(@NonNull String value, int size, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), style);
        return view;
    }

    @NonNull
    private View separator() {
        View view = new View(this);
        view.setBackgroundColor(color(R.color.settings_separator));
        return view;
    }

    private LinearLayout.LayoutParams separatorParams() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(
                match(), dp(1));
        value.leftMargin = dp(16);
        return value;
    }

    @NonNull
    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    @NonNull
    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        value.setDividerPadding(dp(5));
        return value;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(wrap(), wrap());
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(0, wrap(), 1f);
        value.setMarginEnd(dp(5));
        return value;
    }

    private LinearLayout.LayoutParams topMargin(int valueDp) {
        LinearLayout.LayoutParams value = matchWrap();
        value.topMargin = dp(valueDp);
        return value;
    }

    private int color(int resource) {
        return ContextCompat.getColor(this, resource);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String first(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean sameAddress(@Nullable String first, @Nullable String second) {
        return clean(first).equalsIgnoreCase(clean(second));
    }

    @SuppressLint("MissingPermission")
    private static boolean looksLikePhone(@NonNull BluetoothDevice device,
                                          @NonNull String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("iphone") || normalizedName.contains("айфон")) return true;
        try {
            BluetoothClass bluetoothClass = device.getBluetoothClass();
            return bluetoothClass != null
                    && bluetoothClass.getMajorDeviceClass()
                    == BluetoothClass.Device.Major.PHONE;
        } catch (RuntimeException deniedOrBrokenAdapter) {
            return false;
        }
    }

    /** Shows a stable selected-device suffix without exposing the full paired-device address. */
    @NonNull
    static String maskedAddress(@Nullable String address) {
        String normalized = clean(address).toUpperCase(Locale.ROOT);
        String[] parts = normalized.split(":");
        if (parts.length == 6) {
            return "••:••:••:" + parts[3] + ":" + parts[4] + ":" + parts[5];
        }
        if (normalized.isEmpty()) return "—";
        int suffixStart = Math.max(0, normalized.length() - 5);
        return "••••" + normalized.substring(suffixStart);
    }

    private static final class BondedPhone {
        @NonNull final String name;
        @NonNull final String address;

        BondedPhone(@NonNull String name, @NonNull String address) {
            this.name = name;
            this.address = address;
        }
    }

    private static final class BluetoothState {
        final boolean supported;
        final boolean enabled;

        BluetoothState(boolean supported, boolean enabled) {
            this.supported = supported;
            this.enabled = enabled;
        }
    }
}
