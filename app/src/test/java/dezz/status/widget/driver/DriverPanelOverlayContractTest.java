package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DriverPanelOverlayContractTest {
    @Test
    public void productionRailIsContinuousAndClimateTapPassesThroughInput() throws Exception {
        String root = System.getProperty("user.dir");
        Path controller = Paths.get(root, "src/main/java/dezz/status/widget/driver/"
                + "DriverPanelOverlayController.java");
        if (!Files.exists(controller)) {
            controller = Paths.get(root, "app/src/main/java/dezz/status/widget/driver/"
                    + "DriverPanelOverlayController.java");
        }
        String source = read(controller);
        assertTrue(source.contains("enabled.size(),"));
        assertTrue(source.contains("false);"));
        assertTrue(source.contains("setPanelTouchable(false)"));
        assertTrue(source.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue(source.contains("performTap(target.x, target.y"));
        assertTrue(source.contains("\"input tap \" + target.x + \" \" + target.y"));
        assertFalse(source.contains("shortcuts.subList"));
        assertTrue(source.contains("windowContext(display, attachedType)"));
        assertTrue(source.contains("fullScreenParams(attachedType)"));

        Path climateView = controller.resolveSibling("DriverClimateShortcutView.java");
        String climateSource = read(climateView);
        assertTrue(climateSource.contains("fanKnown && fanActive"));
        assertTrue(climateSource.contains("if (!showFan) return;"));
        assertFalse(climateSource.contains("drawText(\"AUTO\""));
    }

    @Test
    public void appCatalogEnumeratesSystemAndNonLauncherPackages() throws Exception {
        String root = System.getProperty("user.dir");
        Path catalog = Paths.get(root, "src/main/java/dezz/status/widget/launcher/"
                + "InstalledAppCatalog.java");
        if (!Files.exists(catalog)) {
            catalog = Paths.get(root, "app/src/main/java/dezz/status/widget/launcher/"
                    + "InstalledAppCatalog.java");
        }
        String source = read(catalog);
        assertTrue(source.contains("getInstalledApplications"));
        assertTrue(source.contains("FLAG_SYSTEM"));
        assertTrue(source.contains("CATEGORY_LEANBACK_LAUNCHER"));
        assertTrue(source.contains("CATEGORY_HOME"));
        assertFalse(source.contains("queryIntentActivities(query, 0)"));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
