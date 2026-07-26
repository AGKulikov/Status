/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports readable system packages involved in ECARX HUD composition.
 *
 * <p>The exporter only reads public package metadata and files installed on read-only system
 * partitions. It never reads another application's private data and never changes a package.</p>
 */
final class HudSystemDumpExporter {
    private static final String[] TARGET_PACKAGES = {
            "com.ecarx.hud",
            "ecarx.dimprotocol.service",
            "com.ecarx.dimmenu",
            "ecarx.powersomeip.service",
            "com.ecarx.sdk.openapi",
            "ecarx.adaptapi.platform",
            "ecarx.geea.platform.api.signal",
            "ecarx.geea.platform.api.vf",
            "com.ecarx.car",
            "com.ecarx.car.multidisplay",
            "com.ecarx.providers.settings",
            "ecarx.settings"
    };

    private static final File[] FRAMEWORK_ROOTS = {
            new File("/system/framework"),
            new File("/system_ext/framework"),
            new File("/vendor/framework"),
            new File("/product/framework"),
            new File("/odm/framework")
    };

    private static final String[] FRAMEWORK_HINTS = {
            "ecarx", "geely", "geea", "dim", "vehicle", "xui", "adapt",
            "xsf", "openapi", "someip", "car"
    };

    private HudSystemDumpExporter() {
    }

    static Result export(Context context) throws Exception {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "HudLabDump");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Не удалось создать " + directory.getAbsolutePath());
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File output = new File(directory, "ecarx-hud-system-" + stamp + ".zip");
        File temporary = new File(directory, output.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Не удалось очистить временный файл");
        }

        StringBuilder report = new StringBuilder(32_768);
        Set<String> entries = new HashSet<>();
        int packageCount = 0;
        int fileCount = 0;
        long byteCount = 0L;

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(temporary)))) {
            appendDeviceReport(context, report);
            PackageManager packages = context.getPackageManager();
            for (String packageName : TARGET_PACKAGES) {
                PackageExport exported = exportPackage(packages, packageName, zip, entries, report);
                if (exported.found) packageCount++;
                fileCount += exported.files;
                byteCount += exported.bytes;
            }

            FrameworkExport framework = exportFrameworkCandidates(zip, entries, report);
            fileCount += framework.files;
            byteCount += framework.bytes;
            addText(zip, entries, "report.txt", report.toString());
        } catch (Throwable failure) {
            // A partial archive is deliberately not presented as a valid diagnostic dump.
            temporary.delete();
            throw failure;
        }

        if (output.exists() && !output.delete()) {
            temporary.delete();
            throw new IOException("Не удалось заменить старый ZIP");
        }
        if (!temporary.renameTo(output)) {
            temporary.delete();
            throw new IOException("Не удалось завершить ZIP");
        }
        return new Result(output, packageCount, fileCount, byteCount);
    }

    private static PackageExport exportPackage(PackageManager packages, String packageName,
                                                ZipOutputStream zip, Set<String> entries,
                                                StringBuilder report) {
        report.append("\n============================================================\n")
                .append("PACKAGE ").append(packageName).append('\n');
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNATURES;
        try {
            PackageInfo info = packages.getPackageInfo(packageName, flags);
            ApplicationInfo app = info.applicationInfo;
            report.append("versionName=").append(info.versionName).append('\n')
                    .append("versionCode=").append(Build.VERSION.SDK_INT >= 28
                            ? info.getLongVersionCode() : info.versionCode).append('\n')
                    .append("uid=").append(app == null ? "?" : app.uid).append('\n')
                    .append("flags=0x").append(app == null ? "?"
                            : Integer.toHexString(app.flags)).append('\n')
                    .append("enabled=").append(app == null ? "?" : app.enabled).append('\n')
                    .append("processName=").append(app == null ? "?" : app.processName).append('\n')
                    .append("sourceDir=").append(app == null ? "?" : app.sourceDir).append('\n')
                    .append("publicSourceDir=").append(app == null ? "?"
                            : app.publicSourceDir).append('\n')
                    .append("nativeLibraryDir=").append(app == null ? "?"
                            : app.nativeLibraryDir).append('\n')
                    .append("sharedLibraryFiles=").append(app == null ? "?"
                            : Arrays.toString(app.sharedLibraryFiles)).append('\n');
            appendSignatures(info.signatures, report);
            appendStrings("requestedPermission", info.requestedPermissions, report);
            appendComponents("activity", info.activities, report);
            appendComponents("service", info.services, report);
            appendComponents("receiver", info.receivers, report);
            appendComponents("provider", info.providers, report);

            List<File> sourceFiles = new ArrayList<>();
            if (app != null && app.sourceDir != null) sourceFiles.add(new File(app.sourceDir));
            if (app != null && app.splitSourceDirs != null) {
                for (String split : app.splitSourceDirs) {
                    if (split != null) sourceFiles.add(new File(split));
                }
            }

            int copied = 0;
            long bytes = 0L;
            for (File source : sourceFiles) {
                String leaf = source.equals(sourceFiles.get(0))
                        ? "base.apk" : safeName(source.getName());
                String entry = "packages/" + safeName(packageName) + "/" + leaf;
                long copiedBytes = addFile(zip, entries, entry, source, report);
                if (copiedBytes >= 0L) {
                    copied++;
                    bytes += copiedBytes;
                }
            }
            return new PackageExport(true, copied, bytes);
        } catch (PackageManager.NameNotFoundException missing) {
            report.append("NOT_INSTALLED\n");
        } catch (Throwable failure) {
            report.append("PACKAGE_ERROR=").append(shortFailure(failure)).append('\n');
        }
        return new PackageExport(false, 0, 0L);
    }

    private static FrameworkExport exportFrameworkCandidates(ZipOutputStream zip,
                                                              Set<String> entries,
                                                              StringBuilder report) {
        report.append("\n============================================================\n")
                .append("FRAMEWORK CANDIDATES\n");
        int count = 0;
        long bytes = 0L;
        for (File root : FRAMEWORK_ROOTS) {
            report.append("\n[").append(root.getAbsolutePath()).append("]\n");
            File[] files;
            try {
                files = root.listFiles();
            } catch (Throwable failure) {
                report.append("LIST_ERROR=").append(shortFailure(failure)).append('\n');
                continue;
            }
            if (files == null) {
                report.append("NOT_READABLE_OR_MISSING\n");
                continue;
            }
            Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
            for (File file : files) {
                report.append(file.getName())
                        .append(" size=").append(file.length())
                        .append(" readable=").append(file.canRead()).append('\n');
                if (!isFrameworkCandidate(file)) continue;
                String relativeRoot = safeName(root.getAbsolutePath().replaceFirst("^/", ""));
                String entry = "framework/" + relativeRoot + "/" + safeName(file.getName());
                long copied = addFile(zip, entries, entry, file, report);
                if (copied >= 0L) {
                    count++;
                    bytes += copied;
                }
            }
        }
        return new FrameworkExport(count, bytes);
    }

    private static boolean isFrameworkCandidate(File file) {
        if (!file.isFile() || !file.canRead()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jar") || name.endsWith(".apk"))) return false;
        for (String hint : FRAMEWORK_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    private static long addFile(ZipOutputStream zip, Set<String> entries, String entryName,
                                File source, StringBuilder report) {
        if (!source.isFile() || !source.canRead()) {
            report.append("COPY_SKIPPED ").append(source.getAbsolutePath())
                    .append(" exists=").append(source.exists())
                    .append(" readable=").append(source.canRead()).append('\n');
            return -1L;
        }
        String unique = uniqueEntry(entries, entryName);
        try {
            zip.putNextEntry(new ZipEntry(unique));
            long bytes = 0L;
            byte[] buffer = new byte[128 * 1024];
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source))) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    zip.write(buffer, 0, read);
                    bytes += read;
                }
            }
            zip.closeEntry();
            report.append("COPIED ").append(source.getAbsolutePath())
                    .append(" -> ").append(unique)
                    .append(" bytes=").append(bytes).append('\n');
            return bytes;
        } catch (Throwable failure) {
            try {
                zip.closeEntry();
            } catch (Throwable ignored) {
                // The next file may still be exportable.
            }
            report.append("COPY_ERROR ").append(source.getAbsolutePath())
                    .append(": ").append(shortFailure(failure)).append('\n');
            return -1L;
        }
    }

    private static void addText(ZipOutputStream zip, Set<String> entries, String entryName,
                                String value) throws IOException {
        String unique = uniqueEntry(entries, entryName);
        zip.putNextEntry(new ZipEntry(unique));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String uniqueEntry(Set<String> entries, String requested) {
        if (entries.add(requested)) return requested;
        int index = 2;
        while (!entries.add(requested + "." + index)) index++;
        return requested + "." + index;
    }

    private static void appendDeviceReport(Context context, StringBuilder out) {
        out.append("HUD LAB SYSTEM EXPORT\n")
                .append("created=").append(new Date()).append('\n')
                .append("manufacturer=").append(Build.MANUFACTURER).append('\n')
                .append("brand=").append(Build.BRAND).append('\n')
                .append("model=").append(Build.MODEL).append('\n')
                .append("device=").append(Build.DEVICE).append('\n')
                .append("product=").append(Build.PRODUCT).append('\n')
                .append("displayBuild=").append(Build.DISPLAY).append('\n')
                .append("fingerprint=").append(Build.FINGERPRINT).append('\n')
                .append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("release=").append(Build.VERSION.RELEASE).append('\n')
                .append("securityPatch=").append(Build.VERSION.SECURITY_PATCH).append('\n')
                .append("supportedAbis=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        try {
            WindowManager windows = (WindowManager) context.getSystemService(
                    Context.WINDOW_SERVICE);
            Display display = windows == null ? null : windows.getDefaultDisplay();
            if (display != null) {
                out.append("activityDisplayId=").append(display.getDisplayId()).append('\n')
                        .append("activityDisplay=").append(display).append('\n');
            }
        } catch (Throwable failure) {
            out.append("displayError=").append(shortFailure(failure)).append('\n');
        }
        try {
            StatFs stats = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            out.append("externalFreeBytes=").append(stats.getAvailableBytes()).append('\n');
        } catch (Throwable failure) {
            out.append("storageError=").append(shortFailure(failure)).append('\n');
        }
    }

    private static void appendSignatures(Signature[] signatures, StringBuilder out) {
        if (signatures == null) return;
        for (int index = 0; index < signatures.length; index++) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] value = digest.digest(signatures[index].toByteArray());
                StringBuilder hex = new StringBuilder(value.length * 2);
                for (byte item : value) hex.append(String.format(Locale.ROOT, "%02x", item));
                out.append("signatureSha256[").append(index).append("]=")
                        .append(hex).append('\n');
            } catch (Throwable failure) {
                out.append("signatureError=").append(shortFailure(failure)).append('\n');
            }
        }
    }

    private static void appendStrings(String label, String[] values, StringBuilder out) {
        if (values == null) return;
        for (String value : values) out.append(label).append('=').append(value).append('\n');
    }

    private static void appendComponents(String label, ComponentInfo[] components,
                                         StringBuilder out) {
        if (components == null) return;
        for (ComponentInfo component : components) {
            out.append(label).append('=').append(component.name)
                    .append(" process=").append(component.processName)
                    .append(" exported=").append(component.exported)
                    .append(" enabled=").append(component.enabled)
                    .append(" permission=").append(componentPermission(component))
                    .append('\n');
        }
    }

    private static String componentPermission(ComponentInfo component) {
        if (component instanceof ActivityInfo) {
            return String.valueOf(((ActivityInfo) component).permission);
        }
        if (component instanceof ServiceInfo) {
            return String.valueOf(((ServiceInfo) component).permission);
        }
        if (component instanceof ProviderInfo) {
            ProviderInfo provider = (ProviderInfo) component;
            return "read=" + provider.readPermission + ",write=" + provider.writePermission;
        }
        return "null";
    }

    private static String safeName(String value) {
        return value == null ? "unknown"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String shortFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }

    static final class Result {
        final File file;
        final int packageCount;
        final int fileCount;
        final long sourceBytes;

        Result(File file, int packageCount, int fileCount, long sourceBytes) {
            this.file = file;
            this.packageCount = packageCount;
            this.fileCount = fileCount;
            this.sourceBytes = sourceBytes;
        }

        String summary() {
            return "Готово: " + packageCount + " пакетов, " + fileCount
                    + " файлов, исходный объём " + humanBytes(sourceBytes)
                    + "\n" + file.getAbsolutePath();
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024L) return bytes + " B";
            double value = bytes;
            String[] units = {"KiB", "MiB", "GiB"};
            for (String unit : units) {
                value /= 1024.0;
                if (value < 1024.0 || "GiB".equals(unit)) {
                    return String.format(Locale.ROOT, "%.1f %s", value, unit);
                }
            }
            return bytes + " B";
        }
    }

    private static final class PackageExport {
        final boolean found;
        final int files;
        final long bytes;

        PackageExport(boolean found, int files, long bytes) {
            this.found = found;
            this.files = files;
            this.bytes = bytes;
        }
    }

    private static final class FrameworkExport {
        final int files;
        final long bytes;

        FrameworkExport(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }
}
