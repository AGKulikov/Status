/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents the Android 9 ProfileVerifier crash seen in HA1068. */
public final class ProfileInstallerDependencyContractTest {
    @Test
    public void resolvableFutureRuntimeIsPackagedExplicitly() throws IOException {
        String appBuild = readFromRootOrApp("app/build.gradle", "build.gradle");
        String catalog = readFromRootOrApp(
                "gradle/libs.versions.toml", "../gradle/libs.versions.toml");

        assertTrue(appBuild.contains("implementation libs.concurrent.futures"));
        assertTrue(catalog.contains(
                "androidx.concurrent:concurrent-futures"));
        assertTrue(catalog.contains("concurrent = \"1.3.0\""));
    }

    private static String readFromRootOrApp(String root, String app) throws IOException {
        Path rootPath = Paths.get(root);
        Path file = Files.isRegularFile(rootPath) ? rootPath : Paths.get(app);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
