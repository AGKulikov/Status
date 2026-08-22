package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Release boundary for Natro 2.2.8 and Helper 60 readable Live Activity controls. */
public final class Ha1242LiveActivityTypographyContractTest {
    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    @Test
    public void releaseIdentityAndHelperTypographyAreExplicit() throws Exception {
        String build = read("build.gradle");
        String widget = read("ios/KX11-iPhone-ANCS-Helper-v60/"
                + "NatroLiveActivityExtension/NatroLiveActivityWidget.swift");
        String manager = read("ios/KX11-iPhone-ANCS-Helper-v60/"
                + "KX11ANCSHelper/NatroLiveActivityManager.swift");
        String manifest = read("release-manifests/HA1242.md");

        assertTrue(build.contains("return '2.2.8'"));
        assertTrue(build.contains("if (version == '2.2.8')"));
        assertTrue(build.contains("return 208021242"));
        assertTrue(widget.contains("size: island ? 12 : 14"));
        assertTrue(widget.contains("lineLimit(functionGrid ? 2 : 1)"));
        assertTrue(widget.contains("minimumScaleFactor(0.82)"));
        assertTrue(manager.contains("minimumActivityUpdateInterval: TimeInterval = 3.0"));
        assertTrue(manager.contains("updateExistingActivities(immediate: true)"));
        assertTrue(manifest.contains("Helper: build `60`, marketing `60.0`"));
    }
}
