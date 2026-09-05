/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard rails for regressions reported from the HA1129 hardware build. */
public final class Ha1130RegressionContractTest {
    @Test public void launcherFramesAreGeometryOnlyAndUserBackdropsRemainIndependent()
            throws IOException {
        String frame = source("launcher/LauncherElementFrame.java");
        assertTrue(frame.contains("setCardBackgroundColor(Color.TRANSPARENT)"));
        assertTrue(frame.contains("setRadius(0)"));
        assertTrue(frame.contains("setCardElevation(0)"));
        assertTrue(frame.contains("setUseCompatPadding(false)"));
        assertTrue(frame.contains("setPreventCornerOverlap(false)"));
        assertTrue(frame.contains("LauncherBackdropView layers explicitly added by the user"));
    }

    @Test public void statusContainerHasNoImplicitPerFrameChildAnimation()
            throws IOException {
        String service = source("WidgetService.java");
        assertTrue(service.contains("binding.overlayContainer.setLayoutTransition(null)"));
        assertFalse(service.contains("new android.animation.LayoutTransition()"));
        assertFalse(service.contains("LayoutTransition.CHANGING"));
        assertTrue(service.contains("ordinary text and icon frames must never move their siblings"));
    }

    @Test public void driverHorizontalGroupDialogShowsChoicesInsteadOfMessageOnly()
            throws IOException {
        String settings = source("DriverPanelSettingsActivity.java");
        String method = between(settings, "private void chooseInformationGroup(",
                "private void createInformationGroup(");
        assertTrue(method.contains(".setView(explanation)"));
        assertTrue(method.contains(".setItems(choices.toArray("));
        assertFalse(method.contains(".setMessage("));
        assertTrue(method.contains("showInformationGroupSettings(shortcut)"));
    }

    @Test public void phoneNotificationRenderingKeepsTheAutomationContracts()
            throws IOException {
        String service = source("WidgetService.java");
        String show = between(service, "private boolean showPhonePopupNotification(",
                "private void clearPhonePopupNotification()");
        String present = between(service, "private boolean presentPhoneNotification(",
                "private boolean hasActiveRoutinePhoneNotificationDestination(");
        assertTrue(show.contains("PhoneNotificationAutomation.OVERLAY_ID"));
        assertTrue(show.contains("mainHandler.removeCallbacks(phonePopupNotificationExpiry)"));
        assertTrue(show.contains("mainHandler.postDelayed(phonePopupNotificationExpiry"));
        assertFalse(show.contains("Queue"));
        assertTrue(service.contains("clearPhonePopupNotification();"));
        assertTrue(present.contains(
                "updatePhoneNotificationFieldStates(delivery.presentation, delivery.selectedFields)"));
        assertTrue(service.contains("PhoneNotificationAutomation.automationIdForField(fieldId)"));

        String settings = source("PhoneNotificationAutomationSettingsActivity.java");
        assertTrue(settings.contains("Показывать в строке состояния"));
        assertTrue(settings.contains("Показывать во всплывающем оверлее"));
        assertTrue(settings.contains("ScenarioSettingsActivity.intentForTarget(this,"));
        assertTrue(settings.contains("Условия · Приложение"));
        assertTrue(settings.contains("Условия · Тема"));
        assertTrue(settings.contains("Условия · Текст"));
        assertTrue(settings.contains("TargetScope.POPUP"));

        String automation = source("phone/PhoneNotificationAutomation.java");
        assertTrue(automation.contains("APPLICATION_AUTOMATION_ID"));
        assertTrue(automation.contains("TOPIC_AUTOMATION_ID"));
        assertTrue(automation.contains("TEXT_AUTOMATION_ID"));

        String popup = source("popup/PopupOverlayController.java");
        assertTrue(popup.contains("PhoneNotificationAutomation.isFieldAutomationId(stateId)"));
        assertTrue(popup.contains("state.expiresAt > 0L && now >= state.expiresAt"));
    }

    @Test public void everyAutomationCanChooseSystemOrSmartHomeConditions()
            throws IOException {
        String settings = source("ScenarioSettingsActivity.java");
        assertTrue(settings.contains("SystemConditionResolver.CONNECTOR_TYPE"));
        assertTrue(settings.contains("\"Диапазон времени\""));
        assertTrue(settings.contains("\"Пассажир присутствует\""));
        assertTrue(settings.contains("\"Элемент другой автоматизации отображается\""));
        assertTrue(settings.contains("showHomeAssistantSourcePicker()"));
        assertTrue(settings.contains("showSprutSourcePicker()"));
        assertTrue(settings.contains("showMqttSourcePicker()"));
        String service = source("WidgetService.java");
        assertTrue(service.contains("private final Runnable systemConditionRefresh"));
        assertTrue(service.contains("if (destroyed || !integrationsStarted) return"));
        assertTrue(service.contains("mainHandler.postDelayed(systemConditionRefresh"));
    }

    private static String source(String relative) throws IOException {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Missing range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
