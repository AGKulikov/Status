/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.scenario;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards PHONE as a selectable scenario input without ever exposing it as a command target. */
public final class ScenarioPhoneSourceContractTest {
    @Test public void phoneIsAnExplicitScenarioSourceWithItsOwnPicker() throws IOException {
        String source = source("ScenarioSettingsActivity.java");

        assertTrue(source.contains(
                "\"HOME_ASSISTANT\", \"MQTT\", \"SPRUTHUB\", \"PHONE\""));
        assertTrue(source.contains(
                "\"Home Assistant\", \"MQTT\", \"Sprut.hub\", \"Телефон\""));
        assertTrue(source.contains("case \"PHONE\":"));
        assertTrue(source.contains("showPhoneSourcePicker();"));
        assertTrue(source.contains("value.connectorType == ConnectorType.PHONE"));
        assertTrue(source.contains("showSearchPicker(\"2. Данные телефона\""));
    }

    @Test public void phonePickerOffersPrimaryValueAndNestedAttributes() throws IOException {
        String source = source("ScenarioSettingsActivity.java");
        String phone = between(source, "private void showPhoneValuePicker(",
                "private void showManualMqttSourcePicker()");

        assertTrue(phone.contains(
                "sourceBinding(ConnectorType.PHONE, value.connectorId,"));
        assertTrue(phone.contains("\"Основное значение\\nсейчас: \""));
        assertTrue(phone.contains(
                "appendAttributeChoices(choices, ConnectorType.PHONE, value.connectorId,"));
        assertTrue(phone.contains("choice -> applySource(choice.value,"));
        assertTrue(source.contains(
                "sourceBinding(type, selectedConnectorId, resource, valuePath)"));
    }

    @Test public void phoneAndUnknownLegacySourcesAreNeverRelabeledAsMqtt()
            throws IOException {
        String scenario = source("ScenarioSettingsActivity.java");
        String label = between(scenario,
                "private static String sourceLabel(ValueReference reference)",
                "private String targetLabel(");

        assertTrue(label.contains("case \"PHONE\": connector = \"Телефон\";"));
        assertTrue(label.contains("case \"MQTT\": connector = \"MQTT\";"));
        assertTrue(label.contains("default: connector = reference.connectorType;"));

        assertTrue(source("AutomationSettingsActivity.java")
                .contains("case PHONE: connector = \"Телефон\";"));
        assertTrue(source("PopupSettingsActivity.java")
                .contains("case PHONE: connector = \"Телефон\";"));
        assertTrue(source("IntentScenarioSettingsActivity.java")
                .contains("case PHONE:"));
        assertTrue(source("VisualBrickEditorActivity.java")
                .contains("case PHONE: connector = \"Телефон\";"));
    }

    @Test public void phoneCannotBeTurnedIntoAnActionConnector() throws IOException {
        String editor = source("VisualBrickEditorActivity.java");
        int phoneGuard = editor.indexOf(
                "if (source.connectorType == ConnectorType.PHONE)");
        int mqttAction = editor.indexOf(
                "if (source.connectorType == ConnectorType.MQTT)", phoneGuard);
        int homeAssistantAction = editor.indexOf(
                "new ActionBinding(ConnectorType.HOME_ASSISTANT", phoneGuard);

        assertTrue(phoneGuard >= 0);
        assertTrue(phoneGuard < mqttAction);
        assertTrue(phoneGuard < homeAssistantAction);
        assertTrue(editor.contains("Телефон — источник только для чтения"));
        assertFalse(editor.contains("new ActionBinding(ConnectorType.PHONE"));

        String dispatcher = source("integration/ConnectorActionDispatcher.java");
        assertTrue(dispatcher.contains("Unsupported connector action"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
