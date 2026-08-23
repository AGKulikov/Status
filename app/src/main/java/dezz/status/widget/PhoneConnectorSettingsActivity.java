/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.phone.PhoneAppCatalog;
import dezz.status.widget.phone.PhoneAppIconStore;
import dezz.status.widget.phone.PhoneBleRole;
import dezz.status.widget.phone.PhoneConnectionJournal;
import dezz.status.widget.phone.PhoneLowBatteryAlertPolicy;
import dezz.status.widget.phone.PhoneNotificationFilter;
import dezz.status.widget.phone.PhoneStatusBarPolicy;
import dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2;
import dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;
import dezz.status.widget.settings.SettingsColorValue;
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
    private static final String PHONE_MIRROR_CHANNEL_ID = "phone_mirror";
    private static final int REQUEST_ICON_STORAGE = 11135;
    /** Deliberate diagnostics-only escape hatch; normal settings expose production Route A. */
    static final String EXTRA_EXPERIMENTAL_ROUTE_B = "kx11_experimental_route_b";

    private Preferences preferences;
    private MaterialSwitch connectorEnabled;
    private MaterialSwitch iphoneCentralRole;
    private TextView iphoneBleRoleSubtitle;
    private AlertDialog leEnrollmentSasDialog;
    private long leEnrollmentSasGeneration;
    private TextView leEnrollmentStatus;
    private MaterialSwitch notificationsEnabled;
    private MaterialSwitch messagesEnabled;
    private MaterialSwitch includeNotificationText;
    private MaterialSwitch lowBatteryAlertEnabled;
    private MaterialSwitch sprutPresenceEnabled;
    private MaterialSwitch sprutAncsPresenceEnabled;
    private TextView selectedDeviceValue;
    private TextView selectedNotificationCategoriesValue;
    private TextView notificationAppFilterModeValue;
    private TextView selectedNotificationAppsValue;
    private TextView selectedStatusItemsValue;
    private TextView selectedNotificationFieldsValue;
    private TextView notificationDurationValue;
    private TextView lowBatteryThresholdValue;
    private TextView lowBatteryThreshold2Value;
    private MaterialButton notificationColorButton;
    private MaterialButton lowBatteryColorButton;
    private MaterialButton lowBatteryColor2Button;
    private TextView selectedSprutPathValue;
    private TextView selectedSprutAncsPathValue;
    private TextView diagnostics;
    private TextView connectionJournal;
    private NestedScrollView connectionJournalScroll;
    private float connectionJournalLastTouchY;
    private final Handler diagnosticsHandler = new Handler(Looper.getMainLooper());
    private boolean diagnosticsPolling;
    private final Runnable diagnosticsPoll = new Runnable() {
        @Override public void run() {
            if (!diagnosticsPolling) return;
            refreshDiagnostics();
            diagnosticsHandler.postDelayed(this, 500L);
        }
    };
    @NonNull private String selectedDeviceAddress = "";
    @NonNull private String selectedSprutPath = "";
    @NonNull private String selectedSprutAncsPath = "";
    @NonNull private final Set<String> selectedStatusItems = new LinkedHashSet<>();
    @NonNull private final Set<String> selectedNotificationFields = new LinkedHashSet<>();
    @NonNull private final Set<Integer> selectedNotificationCategories = new LinkedHashSet<>();
    @NonNull private final Set<String> selectedNotificationApps = new LinkedHashSet<>();
    private int notificationAppFilterMode = PhoneNotificationFilter.MODE_ALL;
    private int notificationDurationSeconds = 10;
    private int lowBatteryThreshold = 20;
    private int lowBatteryThreshold2 = 10;
    private boolean experimentalRouteB;
    @NonNull private String notificationTickerColor = "#FFFFFFFF";
    @NonNull private String lowBatteryAlertColor = "#FFFF453A";
    @NonNull private String lowBatteryAlertColor2 = "#FFFF2D55";
    @NonNull private SprutCatalog sprutCatalog = SprutCatalog.empty();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        experimentalRouteB = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_EXPERIMENTAL_ROUTE_B, false);
        preferences = new Preferences(this);
        preferences.phoneBleExperimentalRouteBEnabled.set(experimentalRouteB);
        if (!experimentalRouteB
                && preferences.phoneBleRole.get() != PhoneBleRole.IPHONE_PERIPHERAL) {
            preferences.phoneBleRole.set(PhoneBleRole.IPHONE_PERIPHERAL);
        }
        PhoneConnectionJournal.initialize(this);
        selectedDeviceAddress = clean(preferences.phoneDeviceAddress.get());
        selectedSprutPath = clean(preferences.phoneSprutPresencePath.get());
        selectedSprutAncsPath = clean(preferences.phoneSprutAncsPresencePath.get());
        selectedStatusItems.addAll(PhoneStatusBarPolicy.parseIds(
                preferences.phoneStatusBarItems.get(),
                PhoneStatusBarPolicy.statusIds()));
        selectedNotificationFields.addAll(PhoneStatusBarPolicy.parseIds(
                preferences.phoneStatusBarNotificationFields.get(),
                PhoneStatusBarPolicy.notificationFieldIds()));
        selectedNotificationCategories.addAll(PhoneNotificationFilter.parseCategoryIds(
                preferences.phoneNotificationCategoryIds.get()));
        selectedNotificationApps.addAll(PhoneNotificationFilter.parseAppKeys(
                preferences.phoneNotificationAppFilterKeys.get()));
        notificationAppFilterMode = PhoneNotificationFilter.normalizeMode(
                preferences.phoneNotificationAppFilterMode.get());
        notificationDurationSeconds = boundedNotificationDuration(
                preferences.phoneStatusBarNotificationSeconds.get());
        lowBatteryThreshold = PhoneLowBatteryAlertPolicy.boundedThreshold(
                preferences.phoneLowBatteryAlertThreshold.get());
        lowBatteryThreshold2 = PhoneLowBatteryAlertPolicy.boundedThreshold(
                preferences.phoneLowBatteryAlertThreshold2.get());
        notificationTickerColor = validColorOr(
                preferences.phoneStatusBarNotificationColor.get(), "#FFFFFFFF");
        lowBatteryAlertColor = validColorOr(
                preferences.phoneLowBatteryAlertColor.get(), "#FFFF453A");
        lowBatteryAlertColor2 = validColorOr(
                preferences.phoneLowBatteryAlertColor2.get(), "#FFFF2D55");
        View screen = buildScreen();
        setContentView(screen);
        SettingsBackNavigation.install(this, screen);
        reloadSprutCatalog();
        refreshDeviceSummary();
        refreshSprutSummary();
        refreshDiagnostics();
        ensureIconStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences == null) return;
        reloadSprutCatalog();
        refreshDeviceSummary();
        refreshSprutSummary();
        refreshDiagnostics();
        diagnosticsPolling = true;
        diagnosticsHandler.removeCallbacks(diagnosticsPoll);
        diagnosticsHandler.postDelayed(diagnosticsPoll, 500L);
    }

    @Override
    protected void onPause() {
        diagnosticsPolling = false;
        diagnosticsHandler.removeCallbacks(diagnosticsPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            WidgetService service = WidgetService.getInstance();
            if (service != null) service.cancelPhoneLeEnrollment();
        }
        dismissEnrollmentSasDialog();
        super.onDestroy();
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

        if (experimentalRouteB) {
            iphoneCentralRole = new MaterialSwitch(this);
            iphoneCentralRole.setChecked(PhoneBleRole.isIphoneCentral(
                    preferences.phoneBleRole.get()));
            addPhoneBleRoleRow(connectionRows);
            iphoneCentralRole.setOnCheckedChangeListener((button, checked) -> {
                refreshPhoneBleRoleSummary();
                refreshDiagnostics();
            });
        }

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

        leEnrollmentStatus = secondary(leEnrollmentSummary(), 13);
        leEnrollmentStatus.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout enrollmentStatusCard = column();
        enrollmentStatusCard.addView(leEnrollmentStatus, matchWrap());
        page.addView(card(enrollmentStatusCard), topMargin(12));
        page.addView(actionButton(getString(R.string.phone_le_enrollment_start),
                this::startLeEnrollment), topMargin(8));
        page.addView(actionButton(getString(R.string.phone_le_enrollment_forget),
                this::confirmForgetLeEnrollment), topMargin(8));

        page.addView(sectionTitle("Live Activity · APNs"), topMargin(24));
        TextView apnsHint = secondary(
                "Push-to-start отправляет магнитола только пока выбранный iPhone подключён "
                        + "по Bluetooth. Ключ импортируется после установки и не входит в APK.",
                13);
        apnsHint.setPadding(dp(8), 0, dp(8), 0);
        page.addView(apnsHint, topMargin(7));
        page.addView(actionButton("Настроить защищённый APNs-ключ", () ->
                startActivity(new Intent(this, LiveActivityApnsSettingsActivity.class))),
                topMargin(9));

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

        selectedNotificationCategoriesValue = addDisclosureRow(dataRows,
                R.string.phone_filter_categories_title, this::chooseNotificationCategories,
                true);
        notificationAppFilterModeValue = addDisclosureRow(dataRows,
                R.string.phone_filter_app_mode_title, this::chooseNotificationAppFilterMode,
                true);
        selectedNotificationAppsValue = addDisclosureRow(dataRows,
                R.string.phone_filter_apps_title, this::chooseNotificationApps,
                true);
        page.addView(card(dataRows), topMargin(7));
        refreshFilterSummaries();

        messagesEnabled.setOnCheckedChangeListener((button, checked) ->
                refreshDiagnostics());
        notificationsEnabled.setOnCheckedChangeListener((button, checked) ->
                refreshDiagnostics());

        page.addView(sectionTitle(getString(R.string.phone_section_status_bar)),
                topMargin(24));
        LinearLayout statusBarRows = column();
        LinearLayout statusItemsRow = clickableRow(this::chooseStatusItems);
        LinearLayout statusItemsLabels = column();
        statusItemsLabels.addView(text(getString(R.string.phone_status_items_title),
                17, Typeface.NORMAL), matchWrap());
        selectedStatusItemsValue = secondary("", 14);
        statusItemsLabels.addView(selectedStatusItemsValue, topMargin(3));
        statusItemsRow.addView(statusItemsLabels, weighted());
        TextView statusItemsDisclosure = text("›", 30, Typeface.NORMAL);
        statusItemsDisclosure.setTextColor(color(R.color.settings_tertiary_text));
        statusItemsRow.addView(statusItemsDisclosure, wrapWrap());
        statusBarRows.addView(statusItemsRow, matchWrap());
        page.addView(card(statusBarRows), topMargin(7));
        TextView statusItemsHint =
                secondary(getString(R.string.phone_status_items_hint), 13);
        statusItemsHint.setPadding(dp(8), 0, dp(8), 0);
        page.addView(statusItemsHint, topMargin(8));
        page.addView(actionButton("Показ уведомлений — в разделе «Автоматизации»",
                () -> startActivity(new Intent(this,
                        PhoneNotificationAutomationSettingsActivity.class))), topMargin(10));
        refreshStatusBarSummaries();

        page.addView(sectionTitle(getString(R.string.phone_section_alerts)), topMargin(24));
        LinearLayout alertRows = column();
        lowBatteryAlertEnabled = new MaterialSwitch(this);
        lowBatteryAlertEnabled.setChecked(preferences.phoneLowBatteryAlertEnabled.get());
        addSwitchRow(alertRows, lowBatteryAlertEnabled,
                R.string.phone_low_battery_enable_title,
                R.string.phone_low_battery_enable_subtitle, false);
        lowBatteryAlertEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && connectorEnabled != null) connectorEnabled.setChecked(true);
        });
        lowBatteryThresholdValue = addDisclosureRow(alertRows,
                R.string.phone_low_battery_threshold_1_title,
                () -> chooseLowBatteryThreshold(false), true);
        lowBatteryThreshold2Value = addDisclosureRow(alertRows,
                R.string.phone_low_battery_threshold_2_title,
                () -> chooseLowBatteryThreshold(true), true);
        page.addView(card(alertRows), topMargin(7));

        lowBatteryColorButton = actionButton("", this::chooseLowBatteryColor);
        AppleColorPickerDialog.decorateButton(lowBatteryColorButton,
                getString(R.string.phone_low_battery_color_title), lowBatteryAlertColor);
        page.addView(lowBatteryColorButton, topMargin(10));
        lowBatteryColor2Button = actionButton("", this::chooseLowBatteryColor2);
        AppleColorPickerDialog.decorateButton(lowBatteryColor2Button,
                getString(R.string.phone_low_battery_color_2_title), lowBatteryAlertColor2);
        page.addView(lowBatteryColor2Button, topMargin(8));
        TextView lowBatteryHint =
                secondary(getString(R.string.phone_low_battery_hint), 13);
        lowBatteryHint.setPadding(dp(8), 0, dp(8), 0);
        page.addView(lowBatteryHint, topMargin(8));
        refreshAlertSummaries();

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

        LinearLayout sprutAncsRows = column();
        sprutAncsPresenceEnabled = new MaterialSwitch(this);
        sprutAncsPresenceEnabled.setChecked(
                preferences.phoneSprutAncsPresenceEnabled.get());
        addSwitchRow(sprutAncsRows, sprutAncsPresenceEnabled,
                R.string.phone_sprut_ancs_enable_title,
                R.string.phone_sprut_ancs_enable_subtitle, false);
        sprutAncsPresenceEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked && connectorEnabled != null) connectorEnabled.setChecked(true);
        });
        LinearLayout ancsPathRow = clickableRow(this::chooseAncsSprutAccessory);
        LinearLayout ancsPathLabels = column();
        ancsPathLabels.addView(text(getString(R.string.phone_sprut_ancs_target_title),
                17, Typeface.NORMAL), matchWrap());
        selectedSprutAncsPathValue = secondary("", 14);
        ancsPathLabels.addView(selectedSprutAncsPathValue, topMargin(3));
        ancsPathRow.addView(ancsPathLabels, weighted());
        TextView ancsPathDisclosure = text("›", 30, Typeface.NORMAL);
        ancsPathDisclosure.setTextColor(color(R.color.settings_tertiary_text));
        ancsPathRow.addView(ancsPathDisclosure, wrapWrap());
        sprutAncsRows.addView(separator(), separatorParams());
        sprutAncsRows.addView(ancsPathRow, matchWrap());
        page.addView(card(sprutAncsRows), topMargin(12));

        LinearLayout sprutAncsActions = row();
        sprutAncsActions.addView(actionButton(
                getString(R.string.phone_sprut_ancs_choose),
                this::chooseAncsSprutAccessory), weighted());
        sprutAncsActions.addView(actionButton(getString(R.string.phone_sprut_clear),
                this::clearAncsSprutTarget), weighted());
        page.addView(sprutAncsActions, topMargin(10));

        page.addView(sectionTitle(getString(R.string.phone_section_diagnostics)),
                topMargin(24));
        diagnostics = secondary("", 15);
        diagnostics.setPadding(dp(16), dp(13), dp(16), dp(13));
        LinearLayout diagnosticContent = column();
        diagnosticContent.addView(diagnostics, matchWrap());
        page.addView(card(diagnosticContent), topMargin(7));
        page.addView(actionButton(getString(R.string.phone_test_ancs),
                this::testAncsConnection), topMargin(10));
        page.addView(actionButton(getString(R.string.phone_notification_settings),
                this::openPhoneNotificationSettings), topMargin(8));

        page.addView(sectionTitle("Журнал подключения к телефону"), topMargin(24));
        connectionJournal = secondary("", 12);
        connectionJournal.setTypeface(Typeface.MONOSPACE);
        connectionJournal.setTextIsSelectable(true);
        connectionJournal.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout journalContent = column();
        connectionJournalScroll = new NestedScrollView(this);
        connectionJournalScroll.setFillViewport(true);
        connectionJournalScroll.setNestedScrollingEnabled(true);
        installConnectionJournalTouchRouting();
        connectionJournalScroll.addView(connectionJournal, matchWrap());
        journalContent.addView(connectionJournalScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        page.addView(card(journalContent), topMargin(7));
        LinearLayout journalActions = row();
        journalActions.addView(actionButton("Обновить", this::refreshConnectionJournal),
                weighted());
        journalActions.addView(actionButton("Очистить", this::clearConnectionJournal),
                weighted());
        journalActions.addView(actionButton("Экспорт", this::exportConnectionJournal),
                weighted());
        page.addView(journalActions, topMargin(8));

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

    /** A two-state switch whose label names the iPhone role, not the Android GATT role. */
    private void addPhoneBleRoleRow(@NonNull LinearLayout parent) {
        parent.addView(separator(), separatorParams());
        LinearLayout roleRow = row();
        roleRow.setGravity(Gravity.CENTER_VERTICAL);
        roleRow.setPadding(dp(16), dp(11), dp(12), dp(11));
        roleRow.setMinimumHeight(dp(76));
        LinearLayout labels = column();
        labels.addView(text(getString(R.string.phone_ble_role_title),
                17, Typeface.NORMAL), matchWrap());
        iphoneBleRoleSubtitle = secondary("", 13);
        labels.addView(iphoneBleRoleSubtitle, topMargin(2));
        roleRow.addView(labels, weighted());
        iphoneCentralRole.setContentDescription(
                getString(R.string.phone_ble_role_title));
        roleRow.addView(iphoneCentralRole, wrapWrap());
        roleRow.setOnClickListener(view ->
                iphoneCentralRole.setChecked(!iphoneCentralRole.isChecked()));
        parent.addView(roleRow, matchWrap());
        refreshPhoneBleRoleSummary();
    }

    private void refreshPhoneBleRoleSummary() {
        if (iphoneBleRoleSubtitle == null) return;
        iphoneBleRoleSubtitle.setText(getString(checked(iphoneCentralRole, false)
                ? R.string.phone_ble_role_central
                : R.string.phone_ble_role_peripheral));
    }

    @NonNull
    private TextView addDisclosureRow(@NonNull LinearLayout parent, int titleRes,
                                      @NonNull Runnable action, boolean separated) {
        if (separated) parent.addView(separator(), separatorParams());
        LinearLayout disclosureRow = clickableRow(action);
        LinearLayout labels = column();
        labels.addView(text(getString(titleRes), 17, Typeface.NORMAL), matchWrap());
        TextView value = secondary("", 14);
        labels.addView(value, topMargin(3));
        disclosureRow.addView(labels, weighted());
        TextView disclosure = text("›", 30, Typeface.NORMAL);
        disclosure.setTextColor(color(R.color.settings_tertiary_text));
        disclosureRow.addView(disclosure, wrapWrap());
        parent.addView(disclosureRow, matchWrap());
        return value;
    }

    private void chooseStatusItems() {
        List<PhoneStatusBarPolicy.StatusItem> items =
                PhoneStatusBarPolicy.statusItems();
        String[] labels = new String[items.size()];
        boolean[] checked = new boolean[items.size()];
        Set<String> working = new LinkedHashSet<>(selectedStatusItems);
        for (int index = 0; index < items.size(); index++) {
            PhoneStatusBarPolicy.StatusItem item = items.get(index);
            labels[index] = item.label;
            checked[index] = working.contains(item.id);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_status_items_choose)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                    String id = items.get(which).id;
                    if (selected) {
                        working.add(id);
                    } else {
                        working.remove(id);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    selectedStatusItems.clear();
                    selectedStatusItems.addAll(PhoneStatusBarPolicy.parseIds(
                            PhoneStatusBarPolicy.serializeIds(
                                    working, PhoneStatusBarPolicy.statusIds()),
                            PhoneStatusBarPolicy.statusIds()));
                    refreshStatusBarSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseNotificationFields() {
        List<PhoneStatusBarPolicy.NotificationField> fields =
                PhoneStatusBarPolicy.notificationFields();
        String[] labels = new String[fields.size()];
        boolean[] checked = new boolean[fields.size()];
        Set<String> working = new LinkedHashSet<>(selectedNotificationFields);
        for (int index = 0; index < fields.size(); index++) {
            PhoneStatusBarPolicy.NotificationField field = fields.get(index);
            labels[index] = field.label;
            checked[index] = working.contains(field.id);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_status_notification_fields_choose)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                    String id = fields.get(which).id;
                    if (selected) {
                        working.add(id);
                    } else {
                        working.remove(id);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    selectedNotificationFields.clear();
                    selectedNotificationFields.addAll(PhoneStatusBarPolicy.parseIds(
                            PhoneStatusBarPolicy.serializeIds(
                                    working,
                                    PhoneStatusBarPolicy.notificationFieldIds()),
                            PhoneStatusBarPolicy.notificationFieldIds()));
                    refreshStatusBarSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseNotificationCategories() {
        List<PhoneNotificationFilter.Category> categories =
                PhoneNotificationFilter.categories();
        String[] labels = new String[categories.size()];
        boolean[] checked = new boolean[categories.size()];
        Set<Integer> working = new LinkedHashSet<>(selectedNotificationCategories);
        for (int index = 0; index < categories.size(); index++) {
            PhoneNotificationFilter.Category category = categories.get(index);
            labels[index] = category.label;
            checked[index] = working.contains(category.id);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_filter_categories_choose)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                    int id = categories.get(which).id;
                    if (selected) {
                        working.add(id);
                    } else {
                        working.remove(id);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    selectedNotificationCategories.clear();
                    selectedNotificationCategories.addAll(
                            PhoneNotificationFilter.parseCategoryIds(
                                    PhoneNotificationFilter.serializeCategoryIds(working)));
                    refreshFilterSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseNotificationAppFilterMode() {
        String[] labels = {
                getString(R.string.phone_filter_app_mode_all),
                getString(R.string.phone_filter_app_mode_only),
                getString(R.string.phone_filter_app_mode_except)
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.phone_filter_app_mode_choose)
                .setSingleChoiceItems(labels,
                        PhoneNotificationFilter.normalizeMode(notificationAppFilterMode), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    notificationAppFilterMode =
                            PhoneNotificationFilter.normalizeMode(position);
                    dialog.dismiss();
                    refreshFilterSummaries();
                }));
        dialog.show();
    }

    private void chooseNotificationApps() {
        List<AppFilterChoice> apps = availableAppFilters();
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        Set<String> working = new LinkedHashSet<>(selectedNotificationApps);
        for (int index = 0; index < apps.size(); index++) {
            AppFilterChoice app = apps.get(index);
            labels[index] = app.label;
            checked[index] = working.contains(app.key);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_filter_apps_choose)
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                    String key = apps.get(which).key;
                    if (selected) {
                        working.add(key);
                    } else {
                        working.remove(key);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    selectedNotificationApps.clear();
                    selectedNotificationApps.addAll(PhoneNotificationFilter.parseAppKeys(
                            PhoneNotificationFilter.serializeAppKeys(working)));
                    refreshFilterSummaries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private List<AppFilterChoice> availableAppFilters() {
        LinkedHashMap<String, AppFilterChoice> result = new LinkedHashMap<>();
        for (PhoneAppCatalog.FilterApp app : PhoneAppCatalog.filterApps()) {
            result.put(app.key, new AppFilterChoice(app.key, app.label));
        }
        for (PhoneAppIconStore.App app : PhoneAppIconStore.get(this).catalog()) {
            String key = PhoneAppCatalog.filterKey(app.identifier);
            if (!key.isEmpty()) {
                result.put(key, new AppFilterChoice(
                        key, app.name + " · " + app.identifier));
            }
        }
        WidgetService service = WidgetService.getInstance();
        if (service != null) {
            for (ConnectorValue value : service.connectorValueSnapshot()) {
                if (value.connectorType != ConnectorType.PHONE) continue;
                if ("notifications.items".equals(value.resourceId)
                        && value.rawValue instanceof List<?>) {
                    for (Object item : (List<?>) value.rawValue) {
                        if (item instanceof Map<?, ?>) {
                            addObservedAppFilter(result, (Map<?, ?>) item);
                        }
                    }
                } else if ("diagnostics.last_app".equals(value.resourceId)
                        && value.rawValue instanceof Map<?, ?>) {
                    addObservedAppFilter(result, (Map<?, ?>) value.rawValue);
                }
            }
        }
        for (String key : selectedNotificationApps) {
            if (!result.containsKey(key)) {
                result.put(key, new AppFilterChoice(key,
                        PhoneAppCatalog.displayNameFallback(key) + " · " + key));
            }
        }
        List<AppFilterChoice> sorted = new ArrayList<>(result.values());
        sorted.sort(Comparator.comparing(value -> value.label, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    private void ensureIconStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                this, "android.permission.WRITE_EXTERNAL_STORAGE")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            PhoneAppIconStore.get(this).promoteToExternalStorage();
            return;
        }
        requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        }, REQUEST_ICON_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = false;
        for (int result : grantResults) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        if (requestCode != REQUEST_ICON_STORAGE) return;
        if (granted) {
            PhoneAppIconStore.get(this).promoteToExternalStorage();
            Toast.makeText(this,
                    "Иконки будут храниться в /sdcard/StatusWidget/ANCS-icons",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this,
                    "Иконки временно сохраняются внутри приложения",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void addObservedAppFilter(
            @NonNull Map<String, AppFilterChoice> destination,
            @NonNull Map<?, ?> raw) {
        Object identifierValue = firstObject(raw.get("app_id"), raw.get("app"), raw.get("id"));
        String identifier = identifierValue == null ? "" : clean(String.valueOf(identifierValue));
        String key = PhoneAppCatalog.filterKey(identifier);
        if (key.isEmpty()) return;
        Object nameValue = firstObject(raw.get("application"),
                raw.get("app_name"), raw.get("name"));
        String name = nameValue == null ? "" : clean(String.valueOf(nameValue));
        if (name.isEmpty()) {
            name = PhoneAppCatalog.displayNameFallback(identifier);
        }
        destination.put(key, new AppFilterChoice(key,
                name + (key.equals(PhoneNotificationFilter.normalizeAppKey(identifier))
                        ? " · " + identifier : "")));
    }

    @Nullable
    private static Object firstObject(@Nullable Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    private void refreshFilterSummaries() {
        if (selectedNotificationCategoriesValue != null) {
            selectedNotificationCategoriesValue.setText(getString(
                    R.string.phone_filter_categories_summary,
                    selectedNotificationCategories.size()));
        }
        if (notificationAppFilterModeValue != null) {
            int label = notificationAppFilterMode == PhoneNotificationFilter.MODE_ONLY_SELECTED
                    ? R.string.phone_filter_app_mode_only
                    : notificationAppFilterMode
                    == PhoneNotificationFilter.MODE_EXCEPT_SELECTED
                    ? R.string.phone_filter_app_mode_except
                    : R.string.phone_filter_app_mode_all;
            notificationAppFilterModeValue.setText(label);
        }
        if (selectedNotificationAppsValue != null) {
            selectedNotificationAppsValue.setText(getString(
                    R.string.phone_filter_apps_summary, selectedNotificationApps.size()));
        }
    }

    private void chooseNotificationDuration() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(notificationDurationSeconds));
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.phone_status_notification_duration_prompt);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.phone_status_notification_duration_title)
                .setMessage(R.string.phone_status_notification_duration_prompt)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            int requested;
            try {
                requested = Integer.parseInt(clean(input.getText().toString()));
            } catch (NumberFormatException invalid) {
                input.setError(getString(
                        R.string.phone_status_notification_duration_invalid));
                return;
            }
            if (requested < 1 || requested > 120) {
                input.setError(getString(
                        R.string.phone_status_notification_duration_invalid));
                return;
            }
            notificationDurationSeconds = requested;
            refreshStatusBarSummaries();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void chooseNotificationTickerColor() {
        chooseColor(getString(R.string.phone_status_notification_color_title),
                notificationTickerColor, selected -> {
                    notificationTickerColor = validColorOr(selected, "#FFFFFFFF");
                    AppleColorPickerDialog.decorateButton(notificationColorButton,
                            getString(R.string.phone_status_notification_color_title),
                            notificationTickerColor);
                });
    }

    private void chooseLowBatteryColor() {
        chooseColor(getString(R.string.phone_low_battery_color_title),
                lowBatteryAlertColor, selected -> {
                    lowBatteryAlertColor = validColorOr(selected, "#FFFF453A");
                    AppleColorPickerDialog.decorateButton(lowBatteryColorButton,
                            getString(R.string.phone_low_battery_color_title),
                            lowBatteryAlertColor);
                });
    }

    private void chooseLowBatteryColor2() {
        chooseColor(getString(R.string.phone_low_battery_color_2_title),
                lowBatteryAlertColor2, selected -> {
                    lowBatteryAlertColor2 = validColorOr(selected, "#FFFF2D55");
                    AppleColorPickerDialog.decorateButton(lowBatteryColor2Button,
                            getString(R.string.phone_low_battery_color_2_title),
                            lowBatteryAlertColor2);
                });
    }

    private void chooseColor(@NonNull String title, @NonNull String current,
                             @NonNull ColorSelection listener) {
        AppleColorPickerDialog.show(this, title, current,
                AppleColorPickerDialog.Options.opaque(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String selected) {
                        listener.onSelected(selected == null ? current : selected);
                    }

                    @Override public void onSelected(@Nullable String selected) {
                        listener.onSelected(selected == null ? current : selected);
                    }

                    @Override public void onCancelled(@Nullable String originalValue) {
                        listener.onSelected(current);
                    }
                });
    }

    private void chooseLowBatteryThreshold(boolean second) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(second ? lowBatteryThreshold2 : lowBatteryThreshold));
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.phone_low_battery_threshold_prompt);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(second ? R.string.phone_low_battery_threshold_2_title
                        : R.string.phone_low_battery_threshold_1_title)
                .setMessage(R.string.phone_low_battery_threshold_prompt)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            int requested;
            try {
                requested = Integer.parseInt(clean(input.getText().toString()));
            } catch (NumberFormatException invalid) {
                input.setError(getString(R.string.phone_low_battery_threshold_invalid));
                return;
            }
            if (requested < 1 || requested > 100) {
                input.setError(getString(R.string.phone_low_battery_threshold_invalid));
                return;
            }
            int first = second ? lowBatteryThreshold : requested;
            int secondValue = second ? requested : lowBatteryThreshold2;
            if (!PhoneLowBatteryAlertPolicy.validOrderedThresholds(first, secondValue)) {
                input.setError(getString(R.string.phone_low_battery_threshold_order_invalid));
                return;
            }
            if (second) lowBatteryThreshold2 = requested;
            else lowBatteryThreshold = requested;
            refreshAlertSummaries();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void refreshAlertSummaries() {
        if (lowBatteryThresholdValue != null) {
            lowBatteryThresholdValue.setText(getString(
                    R.string.phone_low_battery_threshold_value, lowBatteryThreshold));
        }
        if (lowBatteryThreshold2Value != null) {
            lowBatteryThreshold2Value.setText(getString(
                    R.string.phone_low_battery_threshold_value, lowBatteryThreshold2));
        }
    }

    private void refreshStatusBarSummaries() {
        if (selectedStatusItemsValue != null) {
            selectedStatusItemsValue.setText(selectedStatusItems.isEmpty()
                    ? getString(R.string.phone_status_items_none)
                    : getString(R.string.phone_status_items_summary,
                            selectedStatusItems.size()));
        }
        if (selectedNotificationFieldsValue != null) {
            selectedNotificationFieldsValue.setText(getString(
                    R.string.phone_status_notification_fields_summary,
                    selectedNotificationFields.size()));
        }
        if (notificationDurationValue != null) {
            notificationDurationValue.setText(getString(
                    R.string.phone_status_notification_duration_value,
                    notificationDurationSeconds));
        }
    }

    private static int boundedNotificationDuration(int seconds) {
        return Math.max(1, Math.min(120, seconds));
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

    private void openPhoneNotificationSettings() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            if (manager == null
                    || manager.getNotificationChannel(PHONE_MIRROR_CHANNEL_ID) == null) {
                safeStart(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the channel intent; safeStart below still handles broken Settings.
        }
        Intent channel = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, PHONE_MIRROR_CHANNEL_ID);
        try {
            startActivity(channel);
        } catch (RuntimeException unavailable) {
            safeStart(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
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
        String stockConnectionStatus = "";
        String ancsSetup = "";
        String lastError = "";
        String lastAppName = "";
        long lastAppAt = 0L;
        Integer batteryLevel = null;
        Boolean batteryCharging = null;
        Boolean batteryChargingEstimated = null;
        String batteryLevelSource = "";
        String batteryChargingSource = "";
        String callState = "";
        WidgetService service = WidgetService.getInstance();
        if (service != null) {
            for (ConnectorValue value : service.connectorValueSnapshot()) {
                if (value.connectorType != ConnectorType.PHONE) continue;
                values++;
                if (value.fresh) fresh++;
                if (value.available && value.readable) available++;
                if ("connected".equals(value.resourceId)) {
                    phoneConnected = Boolean.TRUE.equals(value.rawValue);
                } else if ("battery.level".equals(value.resourceId)
                        && value.available && value.rawValue instanceof Number) {
                    batteryLevel = ((Number) value.rawValue).intValue();
                } else if ("battery.level_source".equals(value.resourceId)
                        && value.available && value.rawValue != null) {
                    batteryLevelSource = clean(String.valueOf(value.rawValue));
                } else if ("battery.charging".equals(value.resourceId)
                        && value.available && value.rawValue instanceof Boolean) {
                    batteryCharging = (Boolean) value.rawValue;
                } else if ("battery.charging_estimated".equals(value.resourceId)
                        && value.available && value.rawValue instanceof Boolean) {
                    batteryChargingEstimated = (Boolean) value.rawValue;
                } else if ("battery.charging_source".equals(value.resourceId)
                        && value.available && value.rawValue != null) {
                    batteryChargingSource = clean(String.valueOf(value.rawValue));
                } else if ("call.state".equals(value.resourceId)
                        && value.available && value.rawValue != null) {
                    callState = clean(String.valueOf(
                            dezz.status.widget.launcher.information.PhoneInformationSourcePolicy
                                    .displayValue(value, "")));
                } else if ("diagnostics.ancs".equals(value.resourceId)) {
                    ancsStatus = clean(value.rawValue == null
                            ? "" : String.valueOf(value.rawValue));
                } else if ("diagnostics.sms".equals(value.resourceId)) {
                    mapStatus = clean(value.rawValue == null
                            ? "" : String.valueOf(value.rawValue));
                } else if ("diagnostics.device".equals(value.resourceId)
                        && value.rawValue instanceof Map) {
                    Map<?, ?> device = (Map<?, ?>) value.rawValue;
                    Object stockConnection = device.get("stock_connection");
                    Object setup = device.get("ancs_setup");
                    stockConnectionStatus = clean(stockConnection == null
                            ? "" : String.valueOf(stockConnection));
                    ancsSetup = clean(setup == null ? "" : String.valueOf(setup));
                } else if ("diagnostics.last_error".equals(value.resourceId)) {
                    lastError = clean(value.rawValue == null
                            ? "" : String.valueOf(value.rawValue));
                } else if ("diagnostics.last_app".equals(value.resourceId)
                        && value.rawValue instanceof Map) {
                    Map<?, ?> app = (Map<?, ?>) value.rawValue;
                    Object name = app.get("name");
                    Object receivedAt = app.get("received_at");
                    lastAppName = clean(name == null ? "" : String.valueOf(name));
                    if (receivedAt instanceof Number) {
                        lastAppAt = Math.max(0L, ((Number) receivedAt).longValue());
                    }
                }
            }
        }

        boolean ancsReceiving = "ready".equals(ancsStatus)
                || "ready_degraded".equals(ancsStatus);
        boolean notificationsRequested = checked(notificationsEnabled,
                preferences.phoneNotificationsEnabled.get());
        boolean messagesRequested = checked(messagesEnabled,
                preferences.phoneMessagesEnabled.get());
        boolean ancsRequested = notificationsRequested || messagesRequested
                || checked(sprutAncsPresenceEnabled,
                preferences.phoneSprutAncsPresenceEnabled.get());
        boolean notificationDelivery = notificationDeliveryEnabled();
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
        boolean iphoneCentral = experimentalRouteB && checked(iphoneCentralRole,
                PhoneBleRole.isIphoneCentral(preferences.phoneBleRole.get()));
        result.append('\n').append(line(true,
                getString(R.string.phone_diag_ble_role),
                getString(iphoneCentral
                        ? R.string.phone_ble_role_central
                        : R.string.phone_ble_role_peripheral)));
        result.append('\n').append(line(ancsRequested,
                getString(R.string.phone_diag_ancs_transport),
                getString(R.string.phone_diag_ancs_transport_names)));
        result.append('\n').append(line(phoneConnected,
                getString(R.string.phone_diag_connection),
                phoneConnected
                        ? getString(R.string.phone_diag_connected_selected)
                        : getString(R.string.phone_diag_disconnected_selected)));
        String levelSource = localizedBatterySource(batteryLevelSource);
        String chargingSource = localizedBatterySource(batteryChargingSource);
        String chargingText = batteryCharging == null
                ? getString(R.string.phone_diag_charging_unknown)
                : getString(Boolean.TRUE.equals(batteryCharging)
                        ? R.string.phone_diag_charging_yes
                        : R.string.phone_diag_charging_no)
                + (Boolean.TRUE.equals(batteryChargingEstimated)
                ? getString(R.string.phone_diag_charging_estimated) : "")
                + (chargingSource.isEmpty() ? "" : " · " + chargingSource);
        result.append('\n').append(line(batteryLevel != null,
                getString(R.string.phone_diag_battery),
                batteryLevel == null
                        ? getString(R.string.phone_diag_battery_waiting)
                        : getString(R.string.phone_diag_battery_format,
                                batteryLevel, levelSource, chargingText)));
        result.append('\n').append(line(!callState.isEmpty(),
                getString(R.string.phone_diag_call),
                callState.isEmpty()
                        ? getString(R.string.phone_diag_call_waiting) : callState));
        result.append('\n').append(line("accepted".equals(stockConnectionStatus)
                        || ancsReceiving,
                getString(R.string.phone_diag_stock_connection),
                localizedStockConnectionStatus(stockConnectionStatus)));
        result.append('\n').append(line(!messagesRequested
                        || "ready".equals(mapStatus),
                getString(R.string.phone_diag_sms),
                !messagesRequested
                        ? getString(R.string.phone_diag_not_required)
                        : "ready".equals(mapStatus)
                        ? getString(R.string.phone_diag_map_ready)
                        : localizedMapStatus(mapStatus)));
        result.append('\n').append(line(!ancsRequested
                        || ancsReceiving,
                getString(R.string.phone_diag_notifications),
                !ancsRequested
                        ? getString(R.string.phone_diag_not_required)
                        : ancsReceiving
                        ? ("ready_degraded".equals(ancsStatus)
                                ? getString(R.string.phone_diag_ancs_ready_degraded)
                                : getString(R.string.phone_diag_ancs_receiving))
                        : getString(R.string.phone_diag_ancs_iphone,
                                localizedAncsStatus(ancsStatus))));
        result.append('\n').append(line(!ancsRequested || ancsReceiving,
                getString(R.string.phone_diag_ancs_setup),
                !ancsRequested
                        ? getString(R.string.phone_diag_not_required)
                        : localizedAncsSetup(ancsSetup)));
        result.append('\n').append(line(!ancsRequested || notificationDelivery,
                getString(R.string.phone_diag_android_notifications),
                !ancsRequested
                        ? getString(R.string.phone_diag_not_required)
                        : notificationDelivery
                        ? getString(R.string.phone_diag_android_notifications_enabled)
                        : getString(R.string.phone_diag_android_notifications_blocked)));
        result.append('\n').append(line(fresh > 0,
                getString(R.string.phone_diag_values),
                getString(R.string.phone_diag_values_format, available, fresh, values)));
        result.append('\n').append(line(!lastAppName.isEmpty(),
                getString(R.string.phone_diag_last_app),
                lastAppName.isEmpty()
                        ? getString(R.string.phone_diag_no_notifications)
                        : getString(R.string.phone_diag_last_app_format,
                                lastAppName, formatAge(lastAppAt))));
        if (!lastError.isEmpty()) {
            result.append('\n').append(line(false,
                    getString(R.string.phone_diag_last_error), lastError));
        }
        diagnostics.setText(result);
        refreshConnectionJournal();
    }

    private void refreshConnectionJournal() {
        if (connectionJournal == null) return;
        boolean followLatest = connectionJournalScroll == null
                || connectionJournalScroll.getChildCount() == 0
                || connectionJournalScroll.getScrollY()
                + connectionJournalScroll.getHeight()
                >= connectionJournalScroll.getChildAt(0).getHeight() - dp(24);
        String text = PhoneConnectionJournal.tailText(500);
        String rendered = text.isEmpty()
                ? "Журнал пока пуст — события появятся при следующем подключении."
                : text;
        if (rendered.contentEquals(connectionJournal.getText())) return;
        connectionJournal.setText(rendered);
        if (followLatest && connectionJournalScroll != null) {
            // fullScroll(FOCUS_DOWN) requests focus for a descendant. On the ECARX Android 9
            // build that focus request also makes the outer settings NestedScrollView reveal the
            // journal every 500 ms. Move only this viewport instead; the page keeps its position.
            connectionJournalScroll.post(this::scrollConnectionJournalToBottomWithoutFocus);
        }
    }

    /**
     * Lets the fixed-height journal consume vertical drags while it can move, then hands a drag
     * back to the outer settings page at either edge. ECARX Android 9 does not reliably negotiate
     * the gesture when a plain ScrollView is nested inside the page, so keep this explicit edge
     * routing in addition to NestedScrollView's standard nested-scrolling contract.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void installConnectionJournalTouchRouting() {
        if (connectionJournalScroll == null) return;
        connectionJournalScroll.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    connectionJournalLastTouchY = event.getY();
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float delta = connectionJournalLastTouchY - event.getY();
                    connectionJournalLastTouchY = event.getY();
                    if (Math.abs(delta) >= 1f) {
                        int direction = delta > 0f ? 1 : -1;
                        view.getParent().requestDisallowInterceptTouchEvent(
                                view.canScrollVertically(direction));
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Scrolls the journal content without assigning focus to it or moving the outer page. */
    private void scrollConnectionJournalToBottomWithoutFocus() {
        if (connectionJournalScroll == null
                || connectionJournalScroll.getChildCount() == 0) return;
        View content = connectionJournalScroll.getChildAt(0);
        int viewport = connectionJournalScroll.getHeight()
                - connectionJournalScroll.getPaddingTop()
                - connectionJournalScroll.getPaddingBottom();
        int bottom = Math.max(0, content.getHeight() - Math.max(0, viewport));
        connectionJournalScroll.scrollTo(connectionJournalScroll.getScrollX(), bottom);
    }

    private void clearConnectionJournal() {
        PhoneConnectionJournal.clear();
        refreshConnectionJournal();
        Toast.makeText(this, "Журнал подключения очищен", Toast.LENGTH_SHORT).show();
    }

    private void exportConnectionJournal() {
        String text = PhoneConnectionJournal.tailText(1_600);
        if (text.isEmpty()) {
            Toast.makeText(this, "Журнал подключения пуст", Toast.LENGTH_SHORT).show();
            return;
        }
        File exported = createConnectionJournalShareFile(text);
        if (exported == null) {
            Toast.makeText(this, "Не удалось подготовить журнал", Toast.LENGTH_LONG).show();
            return;
        }
        Uri contentUri;
        try {
            contentUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", exported);
        } catch (RuntimeException error) {
            PhoneConnectionJournal.append("journal-export",
                    "FileProvider failed: " + error.getClass().getSimpleName());
            Toast.makeText(this, "Не удалось подготовить журнал", Toast.LENGTH_LONG).show();
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Natro — подключение iPhone")
                .putExtra(Intent.EXTRA_TEXT, "Журнал подключения Natro во вложении")
                .putExtra(Intent.EXTRA_STREAM, contentUri);
        share.setClipData(ClipData.newUri(
                getContentResolver(), exported.getName(), contentUri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Экспортировать журнал через…"));
            PhoneConnectionJournal.append("journal-export",
                    "system chooser opened file=" + exported.getName());
        } catch (RuntimeException error) {
            PhoneConnectionJournal.append("journal-export",
                    "chooser failed: " + error.getClass().getSimpleName());
            Toast.makeText(this, "Нет приложения для экспорта журнала",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private File createConnectionJournalShareFile(@NonNull String text) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new java.util.Date());
        String name = "Natro-ANCS-" + stamp + ".log";
        File directory = new File(getCacheDir(), "exports");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return null;
        File target = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
            return target;
        } catch (IOException | RuntimeException error) {
            PhoneConnectionJournal.append("journal-export",
                    "save failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    @NonNull
    private String localizedBatterySource(@NonNull String source) {
        switch (source) {
            case "ble_bas": return "BLE BAS";
            case "ble_bas_level_status": return "BLE BAS 1.1 · Battery Level Status";
            case "ble_bas_power_state": return "BLE BAS · Battery Power State";
            case "hfp_ecarx": return "HFP/ECARX";
            case "android_broadcast": return "Android Bluetooth";
            case "iphone_helper": return "iPhone Helper";
            case "hfp_vendor": return "HFP/OEM";
            case "android_metadata":
                return getString(R.string.phone_diag_source_android_metadata);
            default: return source;
        }
    }

    @NonNull
    private String formatAge(long timestamp) {
        if (timestamp <= 0L) return getString(R.string.phone_diag_just_now);
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1_000L);
        if (seconds < 60L) return getString(R.string.phone_diag_seconds_ago, seconds);
        long minutes = seconds / 60L;
        if (minutes < 60L) return getString(R.string.phone_diag_minutes_ago, minutes);
        return getString(R.string.phone_diag_hours_ago, minutes / 60L);
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
            case "service_not_published":
                return getString(R.string.phone_diag_ancs_not_published);
            case "stock_pairing_required":
                return getString(R.string.phone_diag_ancs_stock_pairing_required);
            case "connecting":
            case "negotiating":
            case "discovering":
            case "subscribing":
            case "starting":
            case "retrying":
            case "services_changed":
                return getString(R.string.phone_diag_ancs_waiting);
            case "authorization_required":
                return getString(R.string.phone_diag_ancs_authorization);
            case "ready_degraded":
                return getString(R.string.phone_diag_ancs_ready_degraded);
            case "disconnected":
            case "stopped":
                return getString(R.string.phone_diag_ancs_disconnected);
            default:
                return status.isEmpty()
                        ? getString(R.string.phone_diag_ancs_waiting) : status;
        }
    }

    @NonNull
    private String localizedStockConnectionStatus(@NonNull String status) {
        switch (status) {
            case "accepted":
                return getString(R.string.phone_diag_stock_accepted);
            case "phone_not_registered":
                return getString(R.string.phone_diag_stock_not_registered);
            case "rejected":
                return getString(R.string.phone_diag_stock_rejected);
            case "api_unavailable":
                return getString(R.string.phone_diag_stock_unavailable);
            case "invalid_address":
            case "no_configured_phone":
                return getString(R.string.phone_diag_ancs_no_phone);
            case "not_bonded":
                return getString(R.string.phone_diag_ancs_not_bonded);
            case "bluetooth_off":
                return getString(R.string.phone_diag_ancs_bluetooth);
            case "requesting":
            case "starting":
            case "reconfigured":
                return getString(R.string.phone_diag_stock_waiting);
            case "disabled":
            case "stopped":
                return getString(R.string.phone_diag_not_required);
            default:
                return status.isEmpty()
                        ? getString(R.string.phone_diag_stock_waiting) : status;
        }
    }

    @NonNull
    private String localizedAncsSetup(@NonNull String setup) {
        if ("disabled".equals(setup)) return getString(R.string.phone_diag_not_required);
        if ("dedicated_ble_v1".equals(setup)) {
            return getString(R.string.phone_diag_ancs_dedicated_route);
        }
        return getString(R.string.phone_diag_ancs_stock_route);
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
        chooseSprutAccessory(false);
    }

    private void chooseAncsSprutAccessory() {
        chooseSprutAccessory(true);
    }

    private void chooseSprutAccessory(boolean ancsTarget) {
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
                        chooseSprutService(accessories.get(which), ancsTarget))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseSprutService(@NonNull SprutCatalog.Accessory accessory,
                                    boolean ancsTarget) {
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
                        chooseSprutCharacteristic(
                                accessory, services.get(which), ancsTarget))
                .setNegativeButton(R.string.phone_back,
                        (dialog, which) -> chooseSprutAccessory(ancsTarget))
                .show();
    }

    private void chooseSprutCharacteristic(@NonNull SprutCatalog.Accessory accessory,
                                           @NonNull SprutCatalog.Service service,
                                           boolean ancsTarget) {
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
                    String path = values.get(which).path().stableId();
                    if (ancsTarget) {
                        selectedSprutAncsPath = path;
                        sprutAncsPresenceEnabled.setChecked(true);
                    } else {
                        selectedSprutPath = path;
                        sprutPresenceEnabled.setChecked(true);
                    }
                    refreshSprutSummary();
                })
                .setNegativeButton(R.string.phone_back,
                        (dialog, which) -> chooseSprutService(accessory, ancsTarget))
                .show();
    }

    private void clearSprutTarget() {
        selectedSprutPath = "";
        sprutPresenceEnabled.setChecked(false);
        refreshSprutSummary();
    }

    private void clearAncsSprutTarget() {
        selectedSprutAncsPath = "";
        sprutAncsPresenceEnabled.setChecked(false);
        refreshSprutSummary();
    }

    private void refreshSprutSummary() {
        refreshSprutPathSummary(selectedSprutPathValue, selectedSprutPath);
        refreshSprutPathSummary(selectedSprutAncsPathValue, selectedSprutAncsPath);
    }

    private void refreshSprutPathSummary(@Nullable TextView target,
                                         @NonNull String selectedPath) {
        if (target == null) return;
        if (selectedPath.isEmpty()) {
            target.setText(R.string.phone_sprut_not_selected);
            return;
        }
        String description = selectedPath;
        try {
            SprutPath path = SprutPath.parse(selectedPath);
            SprutCatalog.Characteristic characteristic = sprutCatalog.find(path);
            if (characteristic != null) {
                description = first(characteristic.name(), characteristic.type(),
                        selectedPath) + "\npath: " + selectedPath;
            } else {
                description = getString(R.string.phone_sprut_saved_path, selectedPath);
            }
        } catch (IllegalArgumentException ignored) {
            description = getString(R.string.phone_sprut_invalid_path, selectedPath);
        }
        target.setText(description);
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
        persistSettings(true);
    }

    private static boolean checked(
            @Nullable MaterialSwitch value,
            boolean savedFallback) {
        return value == null ? savedFallback : value.isChecked();
    }

    private void testAncsConnection() {
        if (!persistSettings(false)) return;
        if (!preferences.phoneConnectorEnabled.get()) {
            Toast.makeText(this, R.string.phone_test_enable_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!preferences.phoneNotificationsEnabled.get()
                && !preferences.phoneMessagesEnabled.get()
                && !preferences.phoneSprutAncsPresenceEnabled.get()) {
            Toast.makeText(this, R.string.phone_test_choose_source, Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothState bluetooth = bluetoothState();
        if (!bluetooth.supported || !bluetooth.enabled) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.phone_test_pairing_required_title)
                    .setMessage(R.string.phone_test_bluetooth_required)
                    .setPositiveButton(R.string.phone_open_bluetooth,
                            (dialog, which) -> openBluetoothSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (selectedBondedPhone() == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.phone_test_pairing_required_title)
                    .setMessage(R.string.phone_test_pairing_required)
                    .setPositiveButton(R.string.phone_open_bluetooth,
                            (dialog, which) -> openBluetoothSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        WidgetService service = WidgetService.getInstance();
        if (service == null || !service.reconnectPhoneForDiagnostics()) {
            Toast.makeText(this, R.string.phone_test_service_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_test_started_title)
                .setMessage(R.string.phone_test_started)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.phone_open_bluetooth,
                        (dialog, which) -> openBluetoothSettings())
                .show();
        refreshDiagnostics();
    }

    private void startLeEnrollment() {
        if (connectorEnabled != null) connectorEnabled.setChecked(true);
        if (!persistSettings(false)) return;

        WidgetService service = WidgetService.getInstance();
        if (service == null) {
            WidgetServiceStarter.startIfNeeded(this);
            Toast.makeText(this, R.string.phone_le_enrollment_service_wait,
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean started = service.beginPhoneLeEnrollment(
                snapshot -> runOnUiThread(() -> handleLeEnrollmentSnapshot(snapshot)));
        if (!started) {
            Toast.makeText(this, R.string.phone_le_enrollment_prerequisite,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (leEnrollmentStatus != null) {
            leEnrollmentStatus.setText(R.string.phone_le_enrollment_starting);
        }
    }

    private void handleLeEnrollmentSnapshot(
            @NonNull AndroidIphoneLeEnrollmentV2.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        if (leEnrollmentStatus != null) {
            leEnrollmentStatus.setText(enrollmentPhaseText(snapshot));
        }
        if (snapshot.phase
                == AndroidIphoneLeEnrollmentV2.Phase.WAITING_FOR_SAS_CONFIRMATION) {
            showEnrollmentSasDialog(snapshot);
            return;
        }
        if (!snapshot.terminal()) return;

        dismissEnrollmentSasDialog();
        if (snapshot.phase == AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED) {
            Toast.makeText(this, R.string.phone_le_enrollment_succeeded,
                    Toast.LENGTH_LONG).show();
        } else if (snapshot.phase == AndroidIphoneLeEnrollmentV2.Phase.FAILED) {
            Toast.makeText(this,
                    getString(R.string.phone_le_enrollment_failed, snapshot.detail),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showEnrollmentSasDialog(
            @NonNull AndroidIphoneLeEnrollmentV2.Snapshot snapshot) {
        if (!snapshot.sas.matches("\\d{8}")) return;
        if (leEnrollmentSasDialog != null
                && leEnrollmentSasGeneration == snapshot.generation) return;

        dismissEnrollmentSasDialog();
        leEnrollmentSasGeneration = snapshot.generation;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.phone_le_enrollment_sas_title)
                .setMessage(getString(R.string.phone_le_enrollment_sas_message, snapshot.sas))
                .setPositiveButton(R.string.phone_le_enrollment_sas_matches,
                        (ignored, which) -> confirmLeEnrollmentSas(true))
                .setNegativeButton(R.string.phone_le_enrollment_sas_mismatch,
                        (ignored, which) -> confirmLeEnrollmentSas(false))
                .create();
        dialog.setCancelable(false);
        leEnrollmentSasDialog = dialog;
        dialog.show();
    }

    private void confirmLeEnrollmentSas(boolean matches) {
        WidgetService service = WidgetService.getInstance();
        if (service != null) service.confirmPhoneLeEnrollmentSas(matches);
        leEnrollmentSasDialog = null;
    }

    private void dismissEnrollmentSasDialog() {
        AlertDialog dialog = leEnrollmentSasDialog;
        leEnrollmentSasDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void confirmForgetLeEnrollment() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_le_enrollment_forget_title)
                .setMessage(R.string.phone_le_enrollment_forget_message)
                .setPositiveButton(R.string.phone_le_enrollment_forget_confirm,
                        (ignored, which) -> forgetLeEnrollment())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void forgetLeEnrollment() {
        WidgetService service = WidgetService.getInstance();
        boolean cleared = service != null
                ? service.forgetPhoneLeEnrollment()
                : preferences.clearPhoneBleV2EnrollmentRecord();
        if (!cleared) {
            Toast.makeText(this, R.string.phone_le_enrollment_forget_failed,
                    Toast.LENGTH_LONG).show();
        }
        if (leEnrollmentStatus != null) leEnrollmentStatus.setText(leEnrollmentSummary());
    }

    @NonNull
    private String leEnrollmentSummary() {
        if (IphoneLeEnrollmentRecordV2.parse(
                preferences.phoneBleV2PendingEnrollmentRecord()) != null) {
            return getString(R.string.phone_le_enrollment_pending);
        }
        if (IphoneLeEnrollmentRecordV2.parse(
                preferences.phoneBleV2EnrollmentRecord()) == null) {
            return getString(R.string.phone_le_enrollment_not_configured);
        }
        return getString(R.string.phone_le_enrollment_configured);
    }

    @NonNull
    private String enrollmentPhaseText(
            @NonNull AndroidIphoneLeEnrollmentV2.Snapshot snapshot) {
        switch (snapshot.phase) {
            case SCANNING:
            case CONNECTING:
            case NEGOTIATING_MTU:
            case DISCOVERING:
            case SENDING_HELLO:
            case READING_RESPONSE:
                return getString(R.string.phone_le_enrollment_scanning);
            case WAITING_FOR_SAS_CONFIRMATION:
                return getString(R.string.phone_le_enrollment_compare);
            case SENDING_CONFIRM:
            case WAITING_FOR_HELPER_SAS_CONFIRMATION:
                return getString(R.string.phone_le_enrollment_wait_helper);
            case WAITING_FOR_BOND:
            case READING_ENCRYPTED_H:
            case STAGING_FINAL_COMMIT:
            case SENDING_FINAL_COMMIT:
            case READING_FINAL_ACK:
            case SENDING_PENDING_ROUTINE_HELLO:
            case READING_PENDING_ROUTINE_PROOF:
            case SENDING_PENDING_ROUTINE_CONFIRM:
            case READING_PENDING_ROUTINE_ACK:
            case READING_PENDING_ROUTINE_H:
                return getString(R.string.phone_le_enrollment_bonding);
            case SUCCEEDED:
                return getString(R.string.phone_le_enrollment_configured);
            case FAILED:
                return getString(R.string.phone_le_enrollment_failed, snapshot.detail);
            case CANCELLED:
                return getString(R.string.phone_le_enrollment_cancelled);
            default:
                return getString(R.string.phone_le_enrollment_starting);
        }
    }

    private boolean notificationDeliveryEnabled() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return false;
        try {
            if (!manager.areNotificationsEnabled()) return false;
            NotificationChannel channel =
                    manager.getNotificationChannel(PHONE_MIRROR_CHANNEL_ID);
            return channel == null
                    || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean persistSettings(boolean showConfirmation) {
        boolean connectorRequested = checked(connectorEnabled,
                preferences.phoneConnectorEnabled.get());
        boolean notificationsRequested = checked(notificationsEnabled,
                preferences.phoneNotificationsEnabled.get());
        boolean messagesRequested = checked(messagesEnabled,
                preferences.phoneMessagesEnabled.get());
        boolean includeTextRequested = checked(includeNotificationText,
                preferences.phoneIncludeNotificationText.get());
        boolean lowBatteryAlertRequested = checked(lowBatteryAlertEnabled,
                preferences.phoneLowBatteryAlertEnabled.get());
        boolean sprutPresenceRequested = checked(sprutPresenceEnabled,
                preferences.phoneSprutPresenceEnabled.get());
        boolean sprutAncsPresenceRequested = checked(sprutAncsPresenceEnabled,
                preferences.phoneSprutAncsPresenceEnabled.get());
        if (lowBatteryAlertRequested) {
            connectorRequested = true;
            if (connectorEnabled != null) connectorEnabled.setChecked(true);
        }
        if (sprutAncsPresenceRequested) {
            connectorRequested = true;
            if (connectorEnabled != null) connectorEnabled.setChecked(true);
        }
        if ((notificationsRequested || messagesRequested)
                && selectedNotificationCategories.isEmpty()) {
            Toast.makeText(this, R.string.phone_filter_categories_required,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if ((notificationsRequested || messagesRequested)
                && notificationAppFilterMode
                == PhoneNotificationFilter.MODE_ONLY_SELECTED
                && selectedNotificationApps.isEmpty()) {
            Toast.makeText(this, R.string.phone_filter_apps_required,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (connectorRequested && selectedDeviceAddress.isEmpty()) {
            Toast.makeText(this, R.string.phone_choose_required,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (sprutPresenceRequested) {
            if (selectedSprutPath.isEmpty()) {
                Toast.makeText(this, R.string.phone_sprut_choose_required,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            try {
                SprutPath.parse(selectedSprutPath);
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(this, R.string.phone_sprut_invalid_saved,
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        if (sprutAncsPresenceRequested) {
            if (selectedSprutAncsPath.isEmpty()) {
                Toast.makeText(this, R.string.phone_sprut_ancs_choose_required,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            try {
                SprutPath.parse(selectedSprutAncsPath);
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(this, R.string.phone_sprut_invalid_saved,
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        if (sprutPresenceRequested && sprutAncsPresenceRequested
                && selectedSprutPath.equals(selectedSprutAncsPath)) {
            Toast.makeText(this, R.string.phone_sprut_targets_must_differ,
                    Toast.LENGTH_LONG).show();
            return false;
        }

        int savedLowBatteryThreshold =
                PhoneLowBatteryAlertPolicy.boundedThreshold(lowBatteryThreshold);
        int savedLowBatteryThreshold2 =
                PhoneLowBatteryAlertPolicy.boundedThreshold(lowBatteryThreshold2);
        if (lowBatteryAlertRequested
                && !PhoneLowBatteryAlertPolicy.validOrderedThresholds(
                savedLowBatteryThreshold, savedLowBatteryThreshold2)) {
            Toast.makeText(this, R.string.phone_low_battery_threshold_order_invalid,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        boolean resetLowBatteryLatch =
                preferences.phoneLowBatteryAlertEnabled.get()
                        != lowBatteryAlertRequested
                || preferences.phoneLowBatteryAlertThreshold.get()
                        != savedLowBatteryThreshold
                || preferences.phoneLowBatteryAlertThreshold2.get()
                        != savedLowBatteryThreshold2;

        preferences.phoneConnectorEnabled.set(connectorRequested);
        preferences.phoneDeviceAddress.set(selectedDeviceAddress);
        preferences.phoneBleRole.set(experimentalRouteB && checked(iphoneCentralRole,
                PhoneBleRole.isIphoneCentral(preferences.phoneBleRole.get()))
                ? PhoneBleRole.IPHONE_CENTRAL : PhoneBleRole.IPHONE_PERIPHERAL);
        preferences.phoneBleExperimentalRouteBEnabled.set(experimentalRouteB);
        preferences.phoneNotificationsEnabled.set(notificationsRequested);
        preferences.phoneMessagesEnabled.set(messagesRequested);
        preferences.phoneIncludeNotificationText.set(includeTextRequested);
        preferences.phoneStatusBarItems.set(PhoneStatusBarPolicy.serializeIds(
                selectedStatusItems, PhoneStatusBarPolicy.statusIds()));
        preferences.phoneNotificationCategoryIds.set(
                PhoneNotificationFilter.serializeCategoryIds(
                        selectedNotificationCategories));
        preferences.phoneNotificationAppFilterMode.set(
                PhoneNotificationFilter.normalizeMode(notificationAppFilterMode));
        preferences.phoneNotificationAppFilterKeys.set(
                PhoneNotificationFilter.serializeAppKeys(selectedNotificationApps));
        preferences.phoneLowBatteryAlertEnabled.set(lowBatteryAlertRequested);
        preferences.phoneLowBatteryAlertThreshold.set(savedLowBatteryThreshold);
        preferences.phoneLowBatteryAlertColor.set(lowBatteryAlertColor);
        preferences.phoneLowBatteryAlertThreshold2.set(savedLowBatteryThreshold2);
        preferences.phoneLowBatteryAlertColor2.set(lowBatteryAlertColor2);
        if (resetLowBatteryLatch) {
            preferences.phoneLowBatteryAlertLatched.set(false);
            preferences.phoneLowBatteryAlertLatched2.set(false);
        }
        if (preferences.phoneStatusBarNotificationsEnabled.get()) {
            List<BrickType> order = BrickType.parseOrder(preferences.brickOrder.get());
            if (!order.contains(BrickType.MEDIA)) {
                order.add(BrickType.MEDIA);
                preferences.brickOrder.set(BrickType.serializeOrder(order));
            }
        }
        preferences.phoneSprutPresenceEnabled.set(sprutPresenceRequested);
        preferences.phoneSprutPresencePath.set(selectedSprutPath);
        preferences.phoneSprutAncsPresenceEnabled.set(sprutAncsPresenceRequested);
        preferences.phoneSprutAncsPresencePath.set(selectedSprutAncsPath);

        WidgetService service = WidgetService.getInstance();
        if (service != null) {
            service.applyPreferences();
        } else {
            // Enabling the phone connector must also work when every visual surface is disabled.
            // The shared foreground host is idempotent and safely no-ops when no consumer remains.
            WidgetServiceStarter.startIfNeeded(this);
        }

        if (showConfirmation) {
            Toast.makeText(this, R.string.phone_saved, Toast.LENGTH_LONG).show();
        }
        refreshDiagnostics();
        return true;
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

    @NonNull
    private static String validColorOr(@Nullable String raw, @NonNull String fallback) {
        SettingsColorValue parsed = SettingsColorValue.tryParse(raw);
        if (parsed == null || parsed.kind() != SettingsColorValue.Kind.COLOR) return fallback;
        String serialized = parsed.serialize();
        return serialized == null ? fallback : serialized;
    }

    private interface ColorSelection {
        void onSelected(@Nullable String selected);
    }

    private static final class AppFilterChoice {
        @NonNull final String key;
        @NonNull final String label;

        AppFilterChoice(@NonNull String key, @NonNull String label) {
            this.key = key;
            this.label = label;
        }
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
