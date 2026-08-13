/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared install-identity gate for historical HA feature contracts. */
final class ReleaseIdentityContract {
    private static final String PACKAGE_NAME = "ru.natro.statuswidget";
    private static final int MIN_SDK = 28;
    private static final int TARGET_SDK = 28;
    private static final String STABLE_CERT_SHA256 =
            "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75";
    private static final Pattern DEFAULT_VERSION = Pattern.compile(
            "(?m)^\\s*return\\s+['\"](v(\\d+)\\.(\\d+)\\.(\\d+)-ha(\\d+))['\"]\\s*$");

    private ReleaseIdentityContract() {}

    /**
     * Accepts the current release and later releases, while requiring every publication surface
     * to describe the same monotonically increasing, in-place-install-compatible Android build.
     */
    static void assertCurrentAtLeast(int minimumHaRevision) throws Exception {
        assertTrue("Historical HA baseline must be positive", minimumHaRevision > 0);
        Path root = projectRoot();
        String rootBuild = read(root.resolve("build.gradle"));
        Matcher versionMatcher = DEFAULT_VERSION.matcher(rootBuild);
        assertTrue("Root build must expose one canonical default HA version", versionMatcher.find());
        String versionName = versionMatcher.group(1);
        int major = Integer.parseInt(versionMatcher.group(2));
        int minor = Integer.parseInt(versionMatcher.group(3));
        int patch = Integer.parseInt(versionMatcher.group(4));
        int revision = Integer.parseInt(versionMatcher.group(5));
        assertFalse("Root build must not expose competing default HA versions",
                versionMatcher.find());

        long versionCode = versionCode(major, minor, patch, revision);
        long historicalCode = versionCode(2, 8, 2, minimumHaRevision);
        assertTrue("Current HA revision regressed below the historical feature release",
                revision >= minimumHaRevision);
        assertTrue("Android versionCode must remain monotonic", versionCode >= historicalCode);
        assertTrue("Android versionCode must remain a positive signed int",
                versionCode > 0L && versionCode <= Integer.MAX_VALUE);

        String compactBuild = rootBuild.replaceAll("\\s+", " ");
        assertTrue("Gradle versionCode must keep the canonical monotonic mapping",
                compactBuild.contains("return major * 100000000 + minor * 1000000 "
                        + "+ patch * 10000 + revision"));
        assertTrue("Explicit CI version codes must remain fail-fast integers",
                compactBuild.contains("return explicitCode.toInteger()"));

        String suffix = Integer.toString(revision);
        String workflow = read(root.resolve(".github/workflows/verify-ha" + suffix + ".yml"));
        assertEquals("Verification workflow versionName must match the build default",
                versionName, yamlScalar(workflow, "VERSION_NAME"));
        assertEquals("Verification workflow versionCode must match the canonical mapping",
                versionCode, Long.parseLong(yamlScalar(workflow, "VERSION_CODE")));
        assertTrue("Every current candidate must run the complete geely unit suite",
                workflow.contains("testGeelyDebugUnitTest"));
        assertTrue("Candidate APK package verification must stay install-compatible",
                workflow.contains("package: name='" + PACKAGE_NAME + "'"));
        assertTrue("Candidate APK must remain installable on Android 9",
                workflow.contains("sdkVersion:'" + MIN_SDK + "'"));
        assertTrue("Candidate publication must retain an exact Git source bundle",
                Pattern.compile("(?m)^\\s*name:\\s*ha" + suffix
                        + "-[^\\r\\n]*source-bundle\\s*$").matcher(workflow).find());
        assertTrue("Candidate publication must retain the unsigned APK handoff",
                Pattern.compile("(?m)^\\s*name:\\s*ha" + suffix
                        + "-[^\\r\\n]*unsigned-release\\s*$").matcher(workflow).find());
        assertTrue("Candidate publication must retain the official signing tools",
                Pattern.compile("(?m)^\\s*name:\\s*ha" + suffix
                        + "-android-build-tools\\s*$").matcher(workflow).find());

        String releaseManifest = read(root.resolve(
                "release-manifests/HA" + suffix + ".md"));
        assertTrue("Release manifest must carry the matching versionName",
                releaseManifest.contains(versionName));
        assertTrue("Release manifest must carry the matching versionCode",
                releaseManifest.contains(Long.toString(versionCode)));
        assertTrue("Release manifest must retain the installed package",
                releaseManifest.contains(PACKAGE_NAME));
        assertTrue("Release manifest must retain the stable update certificate",
                releaseManifest.contains(STABLE_CERT_SHA256));

        String appBuild = read(root.resolve("app/build.gradle"));
        String androidManifest = read(root.resolve("app/src/main/AndroidManifest.xml"));
        assertTrue("applicationId is the Android in-place update identity",
                appBuild.matches("(?s).*applicationId\\s+['\"]"
                        + Pattern.quote(PACKAGE_NAME) + "['\"].*"));
        assertTrue("KX11 must remain supported at API 28",
                appBuild.matches("(?s).*\\bminSdk\\s+" + MIN_SDK + "\\b.*"));
        assertTrue("Target SDK compatibility must remain frozen for the Android 9 head unit",
                appBuild.matches("(?s).*\\btargetSdkVersion\\s+" + TARGET_SDK + "\\b.*"));
        assertFalse("The source manifest must not override Gradle's installation package",
                androidManifest.matches("(?s).*<manifest[^>]*\\bpackage\\s*=.*"));
        assertTrue("Stable HA key alias must remain the signing default",
                appBuild.contains("?: \"status-widget-ha\""));
        assertTrue("APK Signature Scheme v2 must remain enabled",
                appBuild.contains("enableV2Signing = true"));
        assertTrue("APK Signature Scheme v3 must remain enabled for the installed lineage",
                appBuild.contains("enableV3Signing = true"));
        assertTrue("Detached v4 output must remain disabled",
                appBuild.contains("enableV4Signing = false"));

        String releaseWorkflow = read(root.resolve(
                ".github/workflows/release-ha" + suffix + ".yml"));
        assertEquals("Signed workflow versionName must match the build default",
                versionName, yamlScalar(releaseWorkflow, "VERSION_NAME"));
        assertEquals("Signed workflow versionCode must match the canonical mapping",
                versionCode, Long.parseLong(yamlScalar(releaseWorkflow, "VERSION_CODE")));
        assertEquals("Signed workflow must retain the package",
                PACKAGE_NAME, yamlScalar(releaseWorkflow, "PACKAGE_NAME"));
        assertEquals("Signed workflow must retain API 28 support",
                Integer.toString(MIN_SDK), yamlScalar(releaseWorkflow, "MIN_SDK"));
        assertEquals("Signed workflow must retain the installed signing lineage",
                STABLE_CERT_SHA256, yamlScalar(releaseWorkflow, "STABLE_CERT_SHA256"));
        assertTrue("Signed APK must require v2", releaseWorkflow.contains(
                "--v2-signing-enabled true"));
        assertTrue("Signed APK must require v3", releaseWorkflow.contains(
                "--v3-signing-enabled true"));
        assertTrue("Signed APK must reject a second signer", releaseWorkflow.contains(
                "Number of signers: 1"));
        assertTrue("Signed APK certificate must be verified", releaseWorkflow.contains(
                "signer #1 certificate sha-256 digest: ${STABLE_CERT_SHA256}"));
    }

    private static long versionCode(int major, int minor, int patch, int revision) {
        return major * 100_000_000L + minor * 1_000_000L + patch * 10_000L + revision;
    }

    private static String yamlScalar(String yaml, String key) {
        Pattern line = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + ":\\s*['\"]?([^'\"\\s#]+)['\"]?\\s*(?:#.*)?$");
        Matcher matcher = line.matcher(yaml);
        assertTrue("Missing YAML scalar " + key, matcher.find());
        String value = matcher.group(1);
        assertFalse("Duplicate YAML scalar " + key, matcher.find());
        return value;
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("build.gradle"))
                    && Files.isDirectory(current.resolve("app"))) {
                return current;
            }
        }
        throw new IllegalStateException("Status Widget project root not found");
    }

    private static String read(Path path) throws Exception {
        assertTrue("Required release identity file is missing: " + path,
                Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
