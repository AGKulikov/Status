/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/** Release barriers for HA1217's large offline, searchable Fluent icon library. */
public final class Ha1217FluentIconCatalogContractTest {
    private static final int EXPECTED_FLUENT_ICONS = 322;

    @Test public void curatedCatalogIsLargeStableCategorizedAndOffline() throws Exception {
        String catalog = javaSource("launcher/FluentIconCatalog.java");
        assertEquals(EXPECTED_FLUENT_ICONS,
                occurrences(catalog, "result.add(new LauncherIconResolver.Preset(\"fluent_"));
        assertTrue(catalog.contains("\"Интерфейс\""));
        assertTrue(catalog.contains("\"Медиа\""));
        assertTrue(catalog.contains("\"Навигация\""));
        assertTrue(catalog.contains("\"Автомобиль\""));
        assertTrue(catalog.contains("\"Умный дом\""));
        assertTrue(catalog.contains("\"Безопасность\""));
        assertTrue(catalog.contains("\"Работа и быт\""));
        assertFalse(catalog.contains("java.net"));
        assertFalse(catalog.contains("android.net.Uri"));
        assertFalse(catalog.contains("http://"));
        assertFalse(catalog.contains("https://"));
    }

    @Test public void everyGeneratedAssetIsAValidatedPinnedVector() throws Exception {
        Path drawable = projectPath("app/src/main/res/drawable");
        int count = 0;
        Set<String> geometries = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(drawable,
                "ic_fluent_*.xml")) {
            for (Path file : files) {
                count++;
                String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertTrue(file.toString(), xml.contains(
                        "Microsoft Fluent UI System Icons 1.1.328"));
                assertFalse(file.toString(), xml.contains("<bitmap"));
                assertFalse(file.toString(), xml.contains("<clip-path"));
                try (InputStream input = Files.newInputStream(file)) {
                    Document document = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(input);
                    assertTrue(file.toString(), "vector".equals(
                            document.getDocumentElement().getTagName()));
                    assertTrue(file + " duplicates another catalog glyph",
                            geometries.add(xml.replaceFirst("(?s)^.*?<!--.*?-->\\s*", "")));
                }
            }
        }
        assertEquals(EXPECTED_FLUENT_ICONS, count);
        assertEquals(EXPECTED_FLUENT_ICONS, geometries.size());
    }

    @Test public void chooserHasSearchAndCategoriesWithoutBackgroundLoading() throws Exception {
        String picker = javaSource("settings/VectorIconPickerDialog.java");
        assertTrue(picker.contains("setHint(\"Поиск иконки\")"));
        assertTrue(picker.contains("categoryNames.add(\"Все\")"));
        assertTrue(picker.contains("adapter.setQuery("));
        assertTrue(picker.contains("adapter.setCategory("));
        assertTrue(picker.contains("visibleOptions"));
        assertTrue(picker.contains("normalizedSearch"));
        assertFalse(picker.contains("AsyncTask"));
        assertFalse(picker.contains("new Thread"));
        assertFalse(picker.contains("Executor"));
        String resolver = javaSource("launcher/LauncherIconResolver.java");
        String catalog = javaSource("launcher/FluentIconCatalog.java");
        assertTrue(resolver.contains("if (key.startsWith(\"fluent_\"))"));
        assertTrue(resolver.contains("return PresetHolder.PRESETS"));
        assertTrue(catalog.contains("return PresetHolder.PRESETS"));
        assertFalse(catalog.contains("new HashMap"));
        String popup = javaSource("popup/PopupIconCatalog.java");
        assertTrue(popup.contains("LauncherIconResolver.isKnownKey(id)"));
        assertFalse(popup.contains("LauncherIconResolver.presets()"));
        assertFalse(popup.contains("LABELS"));
    }

    @Test public void packageAndGeneratorProvenanceArePinned() throws Exception {
        String notice = project("third_party/microsoft-fluent-system-icons/NOTICE.md");
        String license = project(
                "third_party/microsoft-fluent-system-icons/LICENSE-MIT.txt");
        String generator = project("tools/generate_fluent_icon_catalog.py");
        assertTrue(notice.contains("@fluentui/svg-icons` package version **1.1.328**"));
        assertTrue(notice.contains(
                "sha512-QSfxXJ34s0wr+FopPdAh2RKfOi+6EmsgXbAgo7ho0disnzn+m3mS3Ol6Ln4iADZ4v6X3kcXrA0VsZeEzJPO0tQ=="));
        assertTrue(notice.contains("2cfba1d6fd6d5c6155b6ca6c5f6420e1b0b88f39"));
        assertTrue(license.contains("Permission is hereby granted, free of charge"));
        assertTrue(generator.contains("unexpected viewBox"));
        assertTrue(generator.contains("unsupported SVG element"));
        assertTrue(generator.contains("never during an app build"));
    }

    private static String javaSource(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        return new String(Files.readAllBytes(projectPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relative) {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        return Files.exists(direct) ? direct : parent;
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(token, at)) >= 0) {
            count++;
            at += token.length();
        }
        return count;
    }
}
