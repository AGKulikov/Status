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

/** Source-level release guard for the HA1220 compound glyph-outline scenario editor. */
public final class Ha1220IconOutlineScenarioContractTest {
    @Test public void editorExposesOneCompoundColorAndWidthAction() throws IOException {
        String editor = source("ScenarioSettingsActivity.java");
        String menu = between(editor, "private static final String[] FIELD_VALUES",
                "private static final String[] FIELD_LABELS");
        assertTrue(menu.contains("\"ICON_OUTLINE_COLOR\""));
        assertFalse(menu.contains("\"ICON_OUTLINE_WIDTH\""));
        assertTrue(editor.contains("Изменить контур значка (цвет и толщина)"));
        assertTrue(editor.contains("showIconOutlinePicker(falseBranch"));
        assertTrue(editor.contains("LocalField.ICON_OUTLINE_COLOR, value"));
        assertTrue(editor.contains("LocalField.ICON_OUTLINE_WIDTH, width"));
        assertTrue(editor.contains("for (int index = 0; index < canonical.length(); index++)"));
        assertTrue(editor.contains("requiresCompoundOutlineConfirmation(field, true"));
        assertTrue(editor.contains("outlineConfirmationAfterFieldSelection("));
        assertTrue(editor.contains("trueOutlineSelectionConfirmed = true"));
        assertTrue(editor.contains("falseOutlineSelectionConfirmed = true"));
        assertTrue(editor.contains("SettingsColorValue.tryParse(persistedColor)"));
    }

    @Test public void savedPairsStayEditableAndLegacySinglesStayUntouched() throws IOException {
        String editor = source("ScenarioSettingsActivity.java");
        assertTrue(editor.contains("isEditorBranchSupported(scenario.actions)"));
        assertTrue(editor.contains("actions.size() != 2"));
        assertTrue(editor.contains("field != LocalField.ICON_OUTLINE_COLOR"));
        assertTrue(editor.contains("field != LocalField.ICON_OUTLINE_WIDTH"));
        assertTrue(editor.contains("Изменить цвет контура значка (старый сценарий)"));
        assertTrue(editor.contains("Изменить толщину контура значка (старый сценарий)"));
        assertTrue(editor.contains("!isDriverGlyphTarget(targetId)"));
    }

    @Test public void driverAndHomeRenderTheOutlineOnTheGlyphMask() throws IOException {
        String driver = source("driver/DriverPanelOverlayController.java");
        String launcher = source("LauncherActivity.java");
        assertTrue(driver.contains("new OutlineImageView(context)"));
        assertTrue(driver.contains("applyGlyphOutline(binding.icon, style)"));
        assertTrue(driver.contains("@NonNull final OutlineImageView icon"));
        assertFalse(driver.contains("content.setStroke(outlineWidthPx, outlineColor)"));
        assertTrue(driver.contains("&& initialAutomation.iconTint != null"));
        assertTrue(count(driver, "applyGlyphOutline(binding.icon, style)") >= 2);

        assertTrue(launcher.contains("new OutlineImageView(this)"));
        assertTrue(launcher.contains("binding.card.setStrokeWidth(0)"));
        assertTrue(launcher.contains("binding.icon.setOutlineColor(outlineColor)"));
        assertTrue(launcher.contains("binding.icon.setOutlineWidth(style.outlineWidthPx)"));
        assertTrue(launcher.contains("boolean originalApplicationIcon"));
        assertTrue(launcher.contains("binding.icon.setColorFilter(Color.parseColor("));
    }

    private static String source(String relative) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path direct = root.resolve("app/src/main/java/dezz/status/widget").resolve(relative);
        if (!Files.exists(direct)) {
            direct = root.resolve("src/main/java/dezz/status/widget").resolve(relative);
        }
        return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Missing source section");
        return source.substring(from, to);
    }

    private static int count(String source, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }
}
