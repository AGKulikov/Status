/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for the exact-device iPhone settings surface and its read-only data picker. */
public final class PhoneConnectorSettingsContractTest {
    @Test
    public void preferencesUseTheRequiredStableKeysAndKeepAddressLocal() throws IOException {
        String source = javaSource("Preferences.java");

        assertTrue(source.contains("\"phoneConnectorEnabled\", false"));
        assertTrue(source.contains("new Str(this, \"phoneDeviceAddress\", \"\")"));
        assertTrue(source.contains("\"phoneNotificationsEnabled\", true"));
        assertTrue(source.contains("\"phoneMessagesEnabled\", false"));
        assertTrue(source.contains("\"phoneIncludeNotificationText\", false"));
        assertTrue(source.contains("\"phoneSprutPresenceEnabled\", false"));
        assertTrue(source.contains("\"phoneSprutPresencePath\", \"\""));
        assertTrue(source.substring(source.indexOf("SECRET_PREFERENCE_KEYS"),
                source.indexOf("public static abstract class Preference"))
                .contains("\"phoneDeviceAddress\""));
    }

    @Test
    public void manifestDeclaresSettingsWithoutBroadSmsPermission() throws IOException {
        String source = mainSource("AndroidManifest.xml");

        assertFalse(source.contains("android.permission.READ_SMS"));
        assertTrue(source.contains("android:name=\".PhoneConnectorSettingsActivity\""));
        assertTrue(source.contains("android:screenOrientation=\"landscape\""));
    }

    @Test
    public void settingsRequireOneExactPairedPhoneAndNeverAutoSelect() throws IOException {
        String source = javaSource("PhoneConnectorSettingsActivity.java");

        assertTrue(source.contains("BluetoothAdapter.getDefaultAdapter()"));
        assertTrue(source.contains("adapter.getBondedDevices()"));
        assertTrue(source.contains("BluetoothClass.Device.Major.PHONE"));
        assertTrue(source.contains("normalizedName.contains(\"iphone\")"));
        assertTrue(source.contains("selectedDeviceAddress = devices.get(position).address"));
        assertTrue(source.contains("maskedAddress(value.address)"));
        assertTrue(source.contains("String advertisedName = clean(device.getName())"));
        assertTrue(source.contains("looksLikePhone(device, advertisedName)"));
        assertFalse(source.contains("looksLikePhone(device, name)"));
        assertTrue(source.contains(
                "connectorEnabled.isChecked() && selectedDeviceAddress.isEmpty()"));
        assertTrue(source.contains("R.string.phone_choose_required"));
        assertTrue(source.contains(
                "preferences.phoneDeviceAddress.set(selectedDeviceAddress)"));
        assertFalse(source.contains("selectedDeviceAddress = position == 0"));
        assertFalse(source.contains("labels[0] = getString(R.string.phone_no_device)"));
    }

    @Test
    public void diagnosticsUsePhoneSnapshotAndDoNotRequireAndroidNotificationAccess()
            throws IOException {
        String source = javaSource("PhoneConnectorSettingsActivity.java");

        assertTrue(source.contains("service.connectorValueSnapshot()"));
        assertTrue(source.contains("value.connectorType != ConnectorType.PHONE"));
        assertTrue(source.contains("\"diagnostics.ancs\".equals(value.resourceId)"));
        assertTrue(source.contains("\"connected\".equals(value.resourceId)"));
        assertFalse(source.contains("Permissions.isNotificationAccessGranted"));
        assertFalse(source.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        assertFalse(source.contains("phone_notification_access"));
        assertFalse(source.contains("Manifest.permission.READ_SMS"));
        assertFalse(source.contains("requestPermissions("));
        assertTrue(source.contains("\"diagnostics.sms\".equals(value.resourceId)"));
        assertTrue(source.contains("localizedMapStatus(mapStatus)"));
    }

    @Test
    public void applyReconfiguresRuntimeAndSprutPickerMatchesExporterRule()
            throws IOException {
        String source = javaSource("PhoneConnectorSettingsActivity.java");

        assertTrue(source.contains("SettingsBackNavigation.install(this, screen)"));
        assertTrue(source.contains("if (service != null) service.applyPreferences()"));
        assertTrue(source.contains("chooseSprutAccessory()"));
        assertTrue(source.contains("chooseSprutService("));
        assertTrue(source.contains("chooseSprutCharacteristic("));
        assertTrue(source.contains("characteristic.writable()"));
        assertTrue(source.contains("SprutActionValue.isBooleanLike(characteristic)"));
        assertTrue(source.contains("value.path().stableId()"));
        assertTrue(source.contains("selectedSprutPath = \"\""));
        assertFalse(source.contains("PhoneConnectorController"));
    }

    @Test
    public void settingsCatalogAndInformationPanelExposePhone() throws IOException {
        String catalog = javaSource("settings/SettingsDestinationCatalog.java");
        String picker = javaSource(
                "launcher/information/InformationSourcePicker.java");
        String hub = javaSource("SettingsHubActivity.java");

        assertTrue(catalog.contains("\"connector_phone\""));
        assertTrue(catalog.contains(
                "\"dezz.status.widget.PhoneConnectorSettingsActivity\""));
        assertTrue(hub.contains(
                "PhoneConnectorSettingsActivity.maskedAddress(address)"));
        assertFalse(hub.contains("\"Включено · \" + address"));
        assertTrue(picker.contains("\"Sprut.hub\","));
        assertTrue(picker.contains("\"Телефон\""));
        assertTrue(picker.contains("value.connectorType != ConnectorType.PHONE"));
        assertTrue(picker.contains(
                "if (\"diagnostics.device\".equals(value.resourceId)) continue;"));
        assertFalse(picker.contains(
                "case \"diagnostics.device\": return \"Диагностика устройства\";"));
        assertTrue(picker.contains("new SourceBinding(ConnectorType.PHONE"));
        assertTrue(picker.contains("service.connectorValueSnapshot()"));
    }

    @Test
    public void bothLocalesExplainSpecificDeviceAncsAndMaskedAddress() throws IOException {
        String english = resourceSource("values/strings.xml");
        String russian = resourceSource("values-ru/strings.xml");

        for (String source : new String[]{english, russian}) {
            assertTrue(source.contains("name=\"phone_connector_title\""));
            assertTrue(source.contains("name=\"phone_choose_required\""));
            assertTrue(source.contains("name=\"phone_diag_ancs_iphone\""));
            assertTrue(source.contains("name=\"phone_sprut_target_title\""));
            assertTrue(source.contains("name=\"phone_privacy_hint\""));
            assertTrue(source.contains("name=\"phone_diag_map_ready\""));
            assertTrue(source.contains("name=\"phone_diag_map_waiting\""));
            assertFalse(source.contains("name=\"phone_allow_sms\""));
            assertFalse(source.contains("name=\"phone_sms_permission_granted\""));
        }
        assertTrue(russian.contains("конкретного iPhone"));
        assertTrue(russian.contains("маскированном виде"));
        assertTrue(russian.contains("Android Notification Access"));
        assertTrue(russian.contains("доступ к общему хранилищу SMS не требуются"));
    }

    private static String javaSource(String relative) throws IOException {
        return source(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resourceSource(String relative) throws IOException {
        return source(Paths.get("res").resolve(relative));
    }

    private static String mainSource(String relative) throws IOException {
        return source(Paths.get(relative));
    }

    private static String source(Path relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
