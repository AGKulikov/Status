/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for the Android picker path that local JVM tests cannot instantiate. */
public final class InformationSourcePickerPhoneCatalogContractTest {
    @Test public void phoneCatalogDoesNotDependOnRunningWidgetService() throws IOException {
        String source = source();
        String phoneChoices = between(source, "private List<Choice> phoneChoices()",
                "private Choice phoneChoice(");

        assertTrue(phoneChoices.contains("PhoneInformationSourcePolicy.catalog()"));
        assertTrue(phoneChoices.contains("WidgetService.getInstance()"));
        assertTrue(phoneChoices.contains("if (service != null)"));
        assertFalse(phoneChoices.contains("if (service == null) return"));
        assertTrue(phoneChoices.indexOf("PhoneInformationSourcePolicy.catalog()")
                > phoneChoices.indexOf("if (service != null)"));
    }

    @Test public void everyCatalogEntryKeepsItsOwnNestedValuePath() throws IOException {
        String source = source();
        String phoneChoices = between(source, "private List<Choice> phoneChoices()",
                "private Choice phoneChoice(");
        String phoneChoice = between(source, "private Choice phoneChoice(",
                "private static String phoneLabel(");

        assertTrue(phoneChoices.contains(
                "PhoneInformationSourcePolicy.displayValue(live, source.valuePath)"));
        assertTrue(phoneChoices.contains("source.resourceId, source.valuePath, source.label"));
        assertTrue(phoneChoice.contains("new SourceBinding(ConnectorType.PHONE"));
        assertTrue(phoneChoice.contains("connectorId, resourceId, valuePath"));
        assertTrue(phoneChoice.contains("live.fresh && live.available"));
    }

    @Test public void unknownFuturePhoneScalarsRemainSelectable() throws IOException {
        String source = source();
        String phoneChoices = between(source, "private List<Choice> phoneChoices()",
                "private Choice phoneChoice(");

        assertTrue(phoneChoices.contains("for (ConnectorValue value : liveValues.values())"));
        assertTrue(phoneChoices.contains("catalogResources.containsKey(value.resourceId)"));
        assertTrue(phoneChoices.contains(
                "PhoneInformationSourcePolicy.valuePath(value.resourceId)"));
        assertTrue(phoneChoices.contains("value.rawValue instanceof Map<?, ?>"));
        assertTrue(phoneChoices.contains("value.rawValue instanceof List<?>"));
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "launcher", "information", "InformationSourcePicker.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "launcher", "information", "InformationSourcePicker.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to < 0 || to <= from) {
            throw new AssertionError("Missing source range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
