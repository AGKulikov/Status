/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Root app_process entry point, invoked only for this explicit user setting. */
public final class MediaInputPatchMain {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new IllegalStateException("Root UID required");
            if (args.length > 1 && "restore".equals(args[0])) { restore(args); return; }
            if ((args.length != 1 && args.length != 2) || !("status".equals(args[0]) || "on".equals(args[0])
                    || "off".equals(args[0]))) throw new IllegalArgumentException("Bad operation");
            VehicleButton button = args.length == 2 ? VehicleButton.valueOf(args[1]) : VehicleButton.MEDIA;
            File file = new File(MediaInputPatch.PATH);
            if (!file.getCanonicalPath().equals(MediaInputPatch.PATH))
                throw new IllegalStateException("Unexpected cache path");
            if (file.length() > 64 * 1024 * 1024 || file.length() < 16)
                throw new IllegalStateException("Unsupported cache size");
            try (RandomAccessFile access = new RandomAccessFile(file,
                    "status".equals(args[0]) ? "r" : "rw");
                 java.nio.channels.FileLock lock = access.getChannel().lock(0, Long.MAX_VALUE,
                         "status".equals(args[0]))) {
                byte[] before = new byte[(int) access.length()];
                access.readFully(before);
                if (button == VehicleButton.VA && !"status".equals(args[0])) validateVaFirmware(before);
                String state;
                if (!"status".equals(args[0])) {
                    byte[] after = button == VehicleButton.MEDIA
                            ? MediaInputPatch.apply(before, "on".equals(args[0]))
                            : ButtonInputPatch.apply(before, button, "on".equals(args[0]));
                    if (!Arrays.equals(before, after)) {
                        File backup = new File(MediaInputPatch.PATH + ".natro-backup");
                        // Preserve the latest pre-change bytes, owned and readable only by root.
                        try (RandomAccessFile saved = new RandomAccessFile(backup, "rw")) {
                            backup.setReadable(false, false);
                            backup.setWritable(false, false);
                            backup.setReadable(true, true);
                            backup.setWritable(true, true);
                            saved.setLength(0); saved.write(before); saved.getFD().sync();
                        }
                        try {
                            access.seek(0); access.write(after); access.getFD().sync();
                            byte[] check = new byte[after.length];
                            access.seek(0); access.readFully(check);
                            if (!Arrays.equals(check, after)) throw new IllegalStateException("Readback mismatch");
                        } catch (Exception failure) {
                            access.seek(0); access.write(before); access.getFD().sync();
                            throw failure;
                        }
                    }
                    // MConfig restarts XSF after every explicit apply, not only changed bytes.
                    // Already-patched disk bytes do not prove what a surviving process loaded.
                    int restarted = restartInputService();
                    System.out.println("NATRO_MEDIA_RESTARTED=" + restarted);
                    if (button != VehicleButton.MEDIA)
                        System.out.println("NATRO_MEDIA_COVERAGE=" + ButtonInputPatch.coverage(after, button));
                    state = state(after, button);
                } else state = state(before, button);
                System.out.println("NATRO_MEDIA_ROUTE=" + state);
            }
        } catch (Throwable failure) {
            System.out.println("NATRO_MEDIA_ERROR=" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void restore(String[] args) throws Exception {
        java.util.LinkedHashSet<VehicleButton> requested = new java.util.LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            VehicleButton b = VehicleButton.valueOf(args[i]);
            if (b == VehicleButton.STAR) throw new IllegalArgumentException("STAR is not a cache route");
            requested.add(b);
        }
        File file = new File(MediaInputPatch.PATH);
        if (!file.getCanonicalPath().equals(MediaInputPatch.PATH) || file.length() < 16
                || file.length() > 64 * 1024 * 1024) throw new IllegalStateException("Unsupported cache path/size");
        try (RandomAccessFile access = new RandomAccessFile(file, "rw");
             java.nio.channels.FileLock lock = access.getChannel().lock()) {
            byte[] before = new byte[(int) access.length()]; access.readFully(before);
            String vaError = null;
            if (requested.contains(VehicleButton.VA)) {
                try { validateVaFirmware(before); }
                catch (Exception unavailable) { requested.remove(VehicleButton.VA); vaError = unavailable.getMessage(); }
            }
            ButtonRouteBatch batch = new ButtonRouteBatch(before, requested.toArray(new VehicleButton[0]));
            if (vaError != null) batch.errors.put(VehicleButton.VA, vaError);
            if (!Arrays.equals(before, batch.bytes)) {
                File backup = new File(MediaInputPatch.PATH + ".natro-backup");
                try (RandomAccessFile saved = new RandomAccessFile(backup, "rw")) {
                    backup.setReadable(false, false); backup.setWritable(false, false);
                    backup.setReadable(true, true); backup.setWritable(true, true);
                    saved.setLength(0); saved.write(before); saved.getFD().sync();
                }
                try {
                    access.seek(0); access.write(batch.bytes); access.getFD().sync();
                    byte[] check = new byte[batch.bytes.length]; access.seek(0); access.readFully(check);
                    if (!Arrays.equals(check, batch.bytes)) throw new IllegalStateException("Readback mismatch");
                } catch (Exception failed) {
                    access.seek(0); access.write(before); access.getFD().sync(); throw failed;
                }
            }
            int restarted = batch.states.isEmpty() ? 0 : restartInputService();
            System.out.println("NATRO_MEDIA_RESTARTED=" + restarted);
            for (java.util.Map.Entry<VehicleButton, String> e : batch.states.entrySet())
                System.out.println("NATRO_MEDIA_ROUTE=" + e.getValue());
            for (java.util.Map.Entry<VehicleButton, String> e : batch.errors.entrySet())
                System.out.println("NATRO_BUTTON_ERROR=" + e.getKey().name() + ":" + e.getValue());
        }
    }

    private static String state(byte[] data, VehicleButton button) {
        return button == VehicleButton.MEDIA ? MediaInputPatch.state(data)
                : button.name() + ":" + (ButtonInputPatch.state(data, button) ? "disabled" : "stock");
    }

    private static void validateVaFirmware(byte[] cache) throws Exception {
        if (!ButtonInputPatch.hasBroadcastVaRoute(cache)) return;
        File apk = new File("/system/app/XSFInputService/XSFInputService.apk");
        if (apk.length() < 16 || apk.length() > 64 * 1024 * 1024)
            throw new IllegalStateException("VA firmware APK unavailable");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(apk)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder sha = new StringBuilder();
        for (byte b : digest.digest()) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!"c4e380408d952298370ce8f9df177e902233c2a24825f15a02c1dbf371fccc14".equals(sha.toString()))
            throw new IllegalStateException("Unreviewed VA firmware SHA256=" + sha);
    }

    private static int restartInputService() throws Exception {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IllegalStateException("Process list unavailable");
        int restarted = 0;
        for (File process : processes) {
            if (!process.getName().matches("[0-9]+")) continue;
            try {
                byte[] cmd = Files.readAllBytes(new File(process, "cmdline").toPath());
                int end = 0;
                while (end < cmd.length && cmd[end] != 0) end++;
                if (InputServiceProcessIdentity.matches(new String(cmd, 0, end, StandardCharsets.UTF_8))) {
                    // Exact package process, never a substring match or all system input processes.
                    android.os.Process.sendSignal(Integer.parseInt(process.getName()), 9);
                    restarted++;
                }
            } catch (java.io.IOException processExited) { /* /proc is transient. */ }
        }
        return restarted;
    }
}
