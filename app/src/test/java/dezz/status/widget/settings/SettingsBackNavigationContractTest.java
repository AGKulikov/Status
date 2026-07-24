/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source contract for visible navigation on every user-facing detail Activity.
 *
 * <p>{@code SettingsHubActivity} is the settings root and {@code LauncherActivity} is HOME, so
 * neither belongs in this list. Every other Activity declared by the app is covered here either
 * by the shared Apple-style chrome or by an existing, explicit back control.</p>
 */
public final class SettingsBackNavigationContractTest {
    private static final String[] SHARED_CHROME_DETAILS = {
            "ClimatePanelSettingsActivity.java",
            "FavoriteAppsSettingsActivity.java",
            "FavoriteRoutesSettingsActivity.java",
            "InformationPanelSettingsActivity.java",
            "LauncherSettingsActivity.java",
            "MediaPanelSettingsActivity.java",
            "NavigationPanelSettingsActivity.java",
            "PanelElementSettingsActivity.java",
            "PhoneConnectorSettingsActivity.java",
            "VehicleInfoPanelSettingsActivity.java"
    };

    private static final String[][] EXPLICIT_JAVA_DETAILS = {
            {"AboutActivity.java", "binding.backButton",
                    "binding.backButton.setOnClickListener(v -> finish())"},
            {"AppSelectionActivity.java", "binding.backButton",
                    "binding.backButton.setOnClickListener(v -> finish())"},
            {"AutomationSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(v -> finish())"},
            {"HomeAssistantSettingsActivity.java",
                    "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(view -> finish())"},
            {"IntentScenarioSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(view -> finish())"},
            {"LauncherShortcutSettingsActivity.java", "back.setText(\"←  Назад\")",
                    "back.setOnClickListener(v -> finish())"},
            {"MainActivity.java", "binding.sectionGeneral.detailBackButton",
                    "binding.sectionGeneral.detailBackButton.setOnClickListener(v -> finish())"},
            {"MqttSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(v -> finish())"},
            {"PopupSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(v -> backAction.run())"},
            {"PresetsActivity.java", "MaterialToolbar toolbar",
                    "toolbar.setNavigationOnClickListener(v -> finish())"},
            {"ScenarioSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(view -> finish())"},
            {"SprutHubSettingsActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(v -> finish())"},
            {"VisualBrickEditorActivity.java", "back.setContentDescription(\"Назад\")",
                    "back.setOnClickListener(v -> finish())"}
    };

    private static final String[] EXPLICIT_SAFE_INSET_DETAILS = {
            "AboutActivity.java",
            "AppSelectionActivity.java",
            "AutomationSettingsActivity.java",
            "HomeAssistantSettingsActivity.java",
            "IntentScenarioSettingsActivity.java",
            "LauncherShortcutSettingsActivity.java",
            "MqttSettingsActivity.java",
            "PopupSettingsActivity.java",
            "PresetsActivity.java",
            "ScenarioSettingsActivity.java",
            "SprutHubSettingsActivity.java",
            "VisualBrickEditorActivity.java"
    };

    private static final String[][] PROGRAMMATIC_BACK_LABELS = {
            {"AutomationSettingsActivity.java", "Button back = button(\"‹\")"},
            {"HomeAssistantSettingsActivity.java", "back.setText(\"‹\")"},
            {"IntentScenarioSettingsActivity.java", "back.setText(\"‹\")"},
            {"LauncherShortcutSettingsActivity.java", "back.setText(\"←  Назад\")"},
            {"MqttSettingsActivity.java", "back.setText(\"‹\")"},
            {"PopupSettingsActivity.java", "Button back = button(\"‹\")"},
            {"ScenarioSettingsActivity.java", "back.setText(\"‹\")"},
            {"SprutHubSettingsActivity.java", "back.setText(\"‹\")"},
            {"VisualBrickEditorActivity.java", "Button back = button(\"‹\")"}
    };

    @Test
    public void everyProgrammaticDetailHasSharedOrExplicitBackControl() throws IOException {
        for (String screen : SHARED_CHROME_DETAILS) {
            String source = javaSource(screen);
            assertTrue(screen + " must install the shared visible Back control",
                    source.contains("SettingsBackNavigation.install(this,"));
        }
        for (String[] contract : EXPLICIT_JAVA_DETAILS) {
            String source = javaSource(contract[0]);
            assertTrue(contract[0] + " must retain its explicit visible Back control",
                    source.contains(contract[1]));
            assertTrue(contract[0] + " must return through its Back control",
                    source.contains(contract[2]));
        }
    }

    @Test
    public void everyManifestActivityIsCoveredOrIsAnIntentionalRoot() throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        expected.add("SettingsHubActivity");
        expected.add("LauncherActivity");
        for (String screen : SHARED_CHROME_DETAILS) expected.add(activityName(screen));
        for (String[] contract : EXPLICIT_JAVA_DETAILS) {
            expected.add(activityName(contract[0]));
        }

        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("android:name=\"\\.([A-Za-z0-9_]+Activity)\"")
                .matcher(manifest());
        while (matcher.find()) declared.add(matcher.group(1));

        assertEquals("Every Activity in AndroidManifest must have a navigation contract",
                expected, declared);
    }

    @Test
    public void sharedBackChromeIsVisibleAndFinishesDetail() throws IOException {
        String source = javaSource("settings/SettingsBackNavigation.java");

        assertTrue(source.contains("back.setText(\"Назад\")"));
        assertTrue(source.contains("back.setContentDescription(\"Назад\")"));
        assertTrue(source.contains("back.setOnClickListener(view -> activity.finish())"));
        assertTrue(source.contains("activity.addContentView(back, buttonParams)"));
    }

    @Test
    public void sharedChromeScreensDoNotRenderASecondLegacyBackButton() throws IOException {
        for (String screen : SHARED_CHROME_DETAILS) {
            String source = javaSource(screen);
            assertFalse(screen + " must rely on the one shared Back control",
                    source.contains("setText(\"←  Назад\")"));
        }
    }

    @Test
    public void programmaticBackControlsAlwaysHaveAVisibleLabel() throws IOException {
        for (String[] contract : PROGRAMMATIC_BACK_LABELS) {
            assertTrue(contract[0] + " must render a visible Back glyph or label",
                    javaSource(contract[0]).contains(contract[1]));
        }
    }

    @Test
    public void explicitBackRowsStayBelowTheLiveStatusOverlay() throws IOException {
        for (String screen : EXPLICIT_SAFE_INSET_DETAILS) {
            assertTrue(screen + " must apply the shared safe top inset without another Back button",
                    javaSource(screen).contains(
                            "SettingsBackNavigation.applySafeTopInset("));
        }

        String helper = javaSource("settings/SettingsBackNavigation.java");
        assertTrue(helper.contains("Math.max(0, statusOverlayHeight() - systemTop)"));
        assertTrue(helper.contains("observedTop != lastAppliedTop[0]"));
        assertTrue(helper.contains("content.postDelayed(updater[0], 750L)"));

        String legacyStatusEditor = javaSource("MainActivity.java");
        assertTrue(legacyStatusEditor.contains("updateStatusBarPadding(h)"));
        assertTrue(legacyStatusEditor.contains(
                "Math.max(0, widgetHeight - systemTopInset)"));
    }

    @Test
    public void layoutBackControlsHaveVisibleIconsOrText() throws IOException {
        assertTrue(layout("activity_about.xml").contains("android:id=\"@+id/backButton\""));
        assertTrue(layout("activity_about.xml").contains(
                "app:icon=\"@drawable/ic_arrow_back\""));
        assertTrue(layout("activity_about.xml").contains(
                "android:contentDescription=\"Назад\""));
        assertTrue(layout("activity_app_selection.xml").contains(
                "android:id=\"@+id/backButton\""));
        assertTrue(layout("activity_app_selection.xml").contains(
                "app:icon=\"@drawable/ic_arrow_back\""));
        assertTrue(layout("activity_app_selection.xml").contains(
                "android:contentDescription=\"Назад\""));
        assertTrue(layout("activity_presets.xml").contains(
                "app:navigationIcon=\"@drawable/ic_arrow_back\""));
        assertTrue(layout("activity_presets.xml").contains(
                "app:navigationContentDescription=\"Назад\""));
        assertTrue(layout("section_general.xml").contains(
                "android:id=\"@+id/detailBackButton\""));
        assertTrue(layout("section_general.xml").contains("android:text=\"Назад\""));
    }

    @Test
    public void navigationRebuildsCannotDropSharedBackControl() throws IOException {
        String source = javaSource("NavigationPanelSettingsActivity.java");

        assertTrue(source.contains("private void showContent()"));
        assertTrue(source.contains("SettingsBackNavigation.install(this, screen)"));
        assertFalse("All full navigation-screen rebuilds must go through showContent()",
                source.contains("setContentView(buildContent())"));
    }

    @Test
    public void popupCatalogAndEditorBackBothReturnToTheirCaller() throws IOException {
        String source = javaSource("PopupSettingsActivity.java");

        assertTrue(source.contains("header(\"Всплывающие оверлеи\", this::finish)"));
        assertTrue(source.contains("header(overlay.name, this::finish)"));
    }

    @Test
    public void settingsHubShowsBackOnlyForChildNavigation() throws IOException {
        String source = javaSource("SettingsHubActivity.java");

        assertTrue(source.contains("EXTRA_SHOW_BACK"));
        assertTrue(source.contains(".putExtra(EXTRA_SHOW_BACK, true)"));
        assertTrue(source.contains("buildBackButton()"));
        assertTrue(source.contains("button.setText(\"Назад\")"));
        assertTrue(source.contains("button.setVisibility(showBack ? View.VISIBLE : View.GONE)"));
    }

    private static String javaSource(String name) throws IOException {
        return source(Paths.get("dezz", "status", "widget", name), "java");
    }

    private static String manifest() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String activityName(String sourceFile) {
        return sourceFile.substring(0, sourceFile.length() - ".java".length());
    }

    private static String layout(String name) throws IOException {
        return source(Paths.get(name), "res/layout");
    }

    private static String source(Path relative, String tree) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", tree).resolve(relative);
        Path fromApp = Paths.get("src", "main", tree).resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
