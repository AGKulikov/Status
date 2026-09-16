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

    private static String state(byte[] data, VehicleButton button) {
        return button == VehicleButton.MEDIA ? MediaInputPatch.state(data)
                : button.name() + ":" + (ButtonInputPatch.state(data, button) ? "disabled" : "stock");
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
