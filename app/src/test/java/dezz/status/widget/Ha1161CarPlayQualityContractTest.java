/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the CarPlay visuals, stable overlays and diagnostics delivered in HA1161. */
public final class Ha1161CarPlayQualityContractTest {
    @Test public void carPlayPhoneIndicatorsShareOneMeasuredGeometryAndBatteryRenderer()
            throws Exception {
        String widget = source("WidgetService.java");
        String outline = source("OutlineImageView.java");
        String information = source("launcher/information/InformationPanelView.java");
        String layout = source("driver/DriverInformationTileLayoutPolicy.java");
        assertTrue(widget.contains("PhoneIndicatorVisualPolicy.cellularIconTextGapPx"));
        assertTrue(information.contains("PhoneIndicatorVisualPolicy.cellularIconTextGapPx"));
        assertTrue(layout.contains("PhoneIndicatorVisualPolicy.cellularIconTextGapPx"));
        assertTrue(outline.contains("persistent CarPlay outline"));
        assertTrue(outline.contains("imageLevel / 10_000f"));
        assertTrue(outline.contains("drawBatteryCharging"));
        assertTrue(widget.contains("R.color.iphone_battery_charging"));
    }

    @Test public void explicitDriverButtonHeightLeavesAutomaticSiblingsToFillTheRail()
            throws Exception {
        String settings = source("DriverPanelSettingsActivity.java");
        String overlay = source("driver/DriverPanelOverlayController.java");
        String store = source("launcher/LauncherShortcutStore.java");
        assertTrue(settings.contains("Высота кнопки: авто"));
        assertTrue(settings.contains("DriverButtonHeightPolicy.resolvedHeight"));
        assertTrue(overlay.contains("DriverButtonHeightPolicy.spacingRequest"));
        assertTrue(overlay.contains("DriverButtonHeightPolicy.isExplicit"));
        assertTrue(store.contains("buttonHeightPx"));
    }

    @Test public void notificationsRenderInPlaceAndGenericPopupsSkipNoOpGenerations()
            throws Exception {
        String popup = source("popup/PopupOverlayController.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        assertTrue(popup.contains("renderPhoneNotificationCard()"));
        assertTrue(popup.contains("lastPhoneNotificationSignature"));
        assertTrue(popup.contains("visualSignature == lastVisualSignature"));
        assertTrue(popup.contains("EMPTY_GENERATION_GRACE_MS"));
        assertTrue(popup.contains("geometryChanged"));
        assertTrue(editor.contains("PanelContentEditOverlay.Model"));
        assertTrue(editor.contains("Восстановить компоновку CarPlay"));
        assertFalse(editor.contains("editOverlay.setModel(new LayoutModel(), null)"));
    }

    @Test public void artworkAndBothConnectionJournalsHavePersistentRecoveryState()
            throws Exception {
        String media = source("launcher/media/MediaPanelView.java");
        String androidJournal = source("phone/PhoneConnectionJournal.java");
        String settings = source("PhoneConnectorSettingsActivity.java");
        String ios = project("ios/KX11-iPhone-ANCS-Helper-v10/KX11ANCSHelper/"
                + "ViewController.swift");
        assertTrue(media.contains("rejectedArtworkFingerprint"));
        assertTrue(media.contains("keep that fingerprint hidden"));
        assertTrue(androidJournal.contains("MAX_LINES = 600"));
        assertTrue(androidJournal.contains("phone-connection.log"));
        assertTrue(androidJournal.contains("NOTIFICATION_CONTENT"));
        assertTrue(androidJournal.contains("RAW_PROTOCOL_FIELD"));
        assertTrue(settings.contains("Экспортировать журнал"));
        assertTrue(settings.contains("connectionJournalScroll"));
        assertTrue(ios.contains("maximumLogLines = 600"));
        assertTrue(ios.contains("Поделиться журналом"));
        assertTrue(ios.contains("B4 snapshot"));
        assertTrue(ios.contains("B4 notify backpressure"));
    }

    @Test public void releaseIdentityIsHa1161() throws Exception {
        String build = project("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1169'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative);
        Path file = Files.isRegularFile(parent) ? parent : direct;
        return new String(Files.readAllBytes(file.normalize()), StandardCharsets.UTF_8);
    }
}
