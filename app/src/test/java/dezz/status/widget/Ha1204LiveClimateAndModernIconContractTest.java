/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HA1204 gates for action-independent live climate and licensed modern vehicle symbols. */
public final class Ha1204LiveClimateAndModernIconContractTest {
    @Test public void liveClimateChoiceIsIndependentFromPrimaryAction() throws Exception {
        String policy = javaSource("launcher/LiveClimateIconPolicy.java");
        String settings = javaSource("DriverPanelSettingsActivity.java");
        String picker = javaSource("launcher/ShortcutActionPicker.java");
        String runtime = javaSource("driver/DriverPanelOverlayController.java");

        assertTrue(policy.contains("existingButton ? currentLiveClimate"));
        assertTrue(policy.contains("selectedKind == LauncherShortcutStore.Kind.BUILTIN"));
        assertTrue(policy.contains("Builtin.STOCK_CLIMATE.key.equals(selectedTarget)"));
        assertTrue(settings.contains("LiveClimateIconPolicy.afterPrimaryActionChange("));
        assertTrue(picker.contains("LiveClimateIconPolicy.afterPrimaryActionChange("));
        assertTrue(settings.contains(
                "boolean interactive = LauncherShortcutStore.isInteractive(shortcut)"));
        assertTrue(settings.contains("shortcut.liveClimateIcon = false;"));
        assertTrue(runtime.contains("return shortcut.liveClimateIcon\n"
                + "                && LauncherShortcutStore.isInteractive(shortcut);"));
        assertTrue(runtime.contains("|| shortcut.kind == LauncherShortcutStore.Kind.CAR)\n"
                + "                && !liveClimate\n"
                + "                && shortcut.showState"));
        assertFalse(settings.contains("isStockClimate(shortcut)"));
        assertFalse(runtime.contains("isStockClimate(shortcut)"));
    }

    @Test public void primaryAutomotiveFamilyIsGoogleRoundedWithExactMdiFallbacks()
            throws Exception {
        String googleNotice = project("third_party/google-material-symbols/NOTICE.md");
        String mdiNotice = project(
                "third_party/pictogrammers-material-design-icons/NOTICE.md");
        assertTrue(googleNotice.contains("Google Material Symbols Rounded"));
        assertTrue(googleNotice.contains("50f0603134ce7b70b2d71b686cc13e8b57ccb74c"));
        assertTrue(mdiNotice.contains("@mdi/svg` version `7.4.47"));
        assertTrue(mdiNotice.contains("9e04201d4557e729822fb57f62a316c3dea1d4a8"));
        assertApacheLicense("third_party/google-material-symbols/LICENSE-APACHE-2.0.txt");
        assertApacheLicense(
                "third_party/pictogrammers-material-design-icons/LICENSE-APACHE-2.0.txt");

        Set<String> google = noticeResources(googleNotice);
        Set<String> mdi = noticeResources(mdiNotice);
        assertTrue("Google Rounded must remain the primary family", google.size() >= 20);
        assertTrue("MDI must remain a limited exact-function fallback", mdi.size() <= 15);
        Set<String> overlap = new LinkedHashSet<>(google);
        overlap.retainAll(mdi);
        assertTrue("One resource cannot claim two upstream glyphs", overlap.isEmpty());

        for (String resource : google) {
            assertVectorProvenance(resource, "Google Material Symbols Rounded");
        }
        for (String resource : mdi) {
            assertVectorProvenance(resource, "Pictogrammers MDI 7.4.47");
        }
    }

    @Test public void misleadingFallbacksStayOutOfPickerButOldLayoutsStillResolve()
            throws Exception {
        String resolver = javaSource("launcher/LauncherIconResolver.java");
        String[] hiddenKeys = {
                "child_lock", "hood_open", "mirror_fold",
                "parking_sensor", "sunroof", "car_window"
        };
        for (String key : hiddenKeys) {
            assertFalse(key + " must not be offered with a semantically false glyph",
                    resolver.contains("new Preset(\"" + key + "\""));
            assertTrue(key + " must keep its legacy resolver for stored layouts",
                    resolver.contains("case \"" + key + "\":"));
        }
        assertTrue(resolver.contains("case \"trunk_closed\":"));
        assertTrue(resolver.contains("case \"trunk_open\":"));
        assertVector("ic_car_trunk_closed.xml");
        assertVector("ic_car_trunk_open.xml");
    }

    @Test public void releaseIdentityIsMonotonicAndInstallCompatible() throws Exception {
        String rootBuild = project("build.gradle");
        String workflow = project(".github/workflows/verify-ha1204.yml");
        String manifest = project("release-manifests/HA1204.md");
        assertTrue(rootBuild.contains("return 'v2.8.2-ha1204'"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1204'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021204'"));
        assertTrue(workflow.contains("Ha1204LiveClimateAndModernIconContractTest"));
        assertTrue(workflow.contains("LiveClimateIconPolicyTest"));
        assertTrue(manifest.contains("Android package remains `ru.natro.statuswidget`"));
        assertTrue(manifest.contains("208021204"));
        assertTrue(manifest.contains(
                "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75"));
    }

    private static Set<String> noticeResources(String notice) {
        Set<String> resources = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("`(ic_car_[a-z0-9_]+\\.xml)`").matcher(notice);
        while (matcher.find()) resources.add(matcher.group(1));
        return resources;
    }

    private static void assertVectorProvenance(String resource, String marker) throws Exception {
        String xml = drawable(resource);
        assertTrue(resource, xml.contains("<vector"));
        assertFalse(resource, xml.contains("<bitmap"));
        assertTrue(resource + " provenance", xml.contains(marker));
    }

    private static void assertVector(String resource) throws Exception {
        String xml = drawable(resource);
        assertTrue(resource, xml.contains("<vector"));
        assertFalse(resource, xml.contains("<bitmap"));
    }

    private static void assertApacheLicense(String relative) throws Exception {
        String license = project(relative);
        assertTrue(relative, license.contains("Apache License"));
        assertTrue(relative, license.contains("Version 2.0, January 2004"));
    }

    private static String javaSource(String relative) throws Exception {
        return read(Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                        .resolve(relative),
                Paths.get("src", "main", "java", "dezz", "status", "widget")
                        .resolve(relative));
    }

    private static String drawable(String resource) throws Exception {
        return read(Paths.get("app", "src", "main", "res", "drawable", resource),
                Paths.get("src", "main", "res", "drawable", resource));
    }

    private static String project(String relative) throws Exception {
        return read(Paths.get(relative), Paths.get("..").resolve(relative));
    }

    private static String read(Path fromRoot, Path fromApp) throws Exception {
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
