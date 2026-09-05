/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/** Release contract for per-panel typography, inactivity dismissal and long-press feedback. */
public final class Ha1234PanelInteractionContractTest {
    @Test public void publicationIsTheNextVersionAndPreservesEarlierMappings()
            throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1234);
        String build = read(projectRoot().resolve("build.gradle")).replaceAll("\\s+", " ");
        String current = "if (version == '2.2.2') { return 208021234";
        String frozen221 = "if (version == '2.2.1') { return 208021233";
        String frozen220 = "if (version == '2.2.0') { return 208021232";
        assertTrue(build.contains(current));
        assertTrue(build.contains(frozen221));
        assertTrue(build.contains(frozen220));
        assertTrue(build.indexOf(current) < build.indexOf(frozen221));
    }

    @Test public void driverFavoritesExposeAndRenderIndependentInformationTextSizes()
            throws Exception {
        String settings = source("DriverFavoritesSettingsActivity.java");
        String homeSettings = source("InformationPanelSettingsActivity.java");
        String shortcuts = source("launcher/LauncherShortcutStore.java");
        String adapter = source("launcher/InformationShortcutView.java");

        assertTrue(settings.contains("Размер текста информационного элемента"));
        assertTrue(settings.contains("shortcutSlider(body, \"Размер подписи\""));
        assertTrue(settings.contains("shortcutSlider(body, \"Размер значения\""));
        assertTrue(settings.contains("shortcut.informationLabelTextSizeSp = value"));
        assertTrue(settings.contains("shortcut.informationValueTextSizeSp = value"));
        assertTrue(homeSettings.contains("dialogSeek(form, \"Подпись\", 8, 72"));
        assertTrue(homeSettings.contains("dialogSeek(form, \"Значение\", 8, 96"));
        assertTrue(homeSettings.contains(
                "item.valueTextSizeSp = 8 + valueTextSize.getProgress()"));
        assertTrue(shortcuts.contains("MIN_INFORMATION_VALUE_TEXT_SIZE_SP = 8"));
        assertTrue(shortcuts.contains("MAX_INFORMATION_VALUE_TEXT_SIZE_SP = 96"));
        assertTrue(adapter.contains(
                "item.valueTextSizeSp = shortcut.informationValueTextSizeSp"));
    }

    @Test public void everyFavoritesPanelOwnsAnOptionalResettableIdleTimer()
            throws Exception {
        String config = source("driver/DriverFavoritesPanelConfig.java");
        String store = source("driver/DriverFavoritesPanelStore.java");
        String settings = source("DriverFavoritesSettingsActivity.java");
        String overlay = source("driver/DriverPanelOverlayController.java");

        assertTrue(config.contains("public int autoCloseSeconds"));
        assertTrue(config.contains("AUTO_CLOSE_DISABLED_SECONDS = 0"));
        assertTrue(store.contains(".put(\"autoCloseSeconds\", value.autoCloseSeconds)"));
        assertTrue(store.contains("item.optInt(\n"
                + "                    \"autoCloseSeconds\", value.autoCloseSeconds)"));
        assertTrue(settings.contains("autoCloseSlider(root)"));
        assertTrue(settings.contains("Автозакрытие без активности: выключено"));

        assertTrue(overlay.contains("pendingFavoriteIdleDismissals = new HashMap<>()"));
        assertTrue(overlay.contains("scheduleFavoriteIdleDismiss(panelId)"));
        assertTrue(overlay.contains("cancelPendingFavoriteIdleDismiss(panelId)"));
        assertTrue(overlay.contains("FavoritePanelRoot extends FrameLayout"));
        assertTrue(overlay.contains("public boolean dispatchTouchEvent"));
        assertTrue(overlay.contains("action == MotionEvent.ACTION_DOWN"
                + " || action == MotionEvent.ACTION_MOVE"));
        assertTrue(overlay.contains("action == MotionEvent.ACTION_UP"
                + " || action == MotionEvent.ACTION_CANCEL"));
        assertTrue(overlay.contains("expected.config.autoCloseSeconds * 1_000L"));
        assertTrue(overlay.contains("favoriteWindows.get(panelId) != expected"));
    }

    @Test public void everyPlatformLongPressHandlerPlaysTheSameStockClick()
            throws Exception {
        Path root = widgetRoot();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(sources::add);
        }
        int handlers = 0;
        int feedbackCalls = 0;
        for (Path path : sources) {
            String value = read(path);
            handlers += occurrences(value, "setOnLongClickListener(");
            handlers += occurrences(value, "setOnItemLongClickListener(");
            if (!path.endsWith("LongPressFeedback.java")) {
                feedbackCalls += occurrences(value, "LongPressFeedback.play(");
            }
        }
        assertTrue("Expected launcher, driver and editor long-press handlers", handlers >= 8);
        assertEquals("Every long-press handler must opt into stock click feedback",
                handlers, feedbackCalls);

        String feedback = source("LongPressFeedback.java");
        assertTrue(feedback.contains("SoundEffectConstants.CLICK"));
        assertTrue(feedback.contains("view.playSoundEffect"));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String source(String relative) throws Exception {
        return read(widgetRoot().resolve(relative));
    }

    private static Path widgetRoot() {
        return projectRoot().resolve(
                Paths.get("app", "src", "main", "java", "dezz", "status", "widget"));
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) return current;
        }
        throw new IllegalStateException("Project root not found");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
