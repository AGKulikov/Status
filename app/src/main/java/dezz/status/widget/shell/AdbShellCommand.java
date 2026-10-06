/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shell;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Shell-v1 input framing: ASCII OPEN, UTF-8 WRTE, no temporary files or retries. */
public final class AdbShellCommand {
    public static final String SERVICE = "shell:sh";
    public static final int MAX_PACKET = 4096;
    public interface Writer { void write(byte[] bytes) throws Exception; }
    private AdbShellCommand() {}

    public static byte[] encode(String command) {
        if (command == null || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Shell command must not contain NUL");
        // sh parses the complete quoted argument before executing anything. A cancelled upload
        // cannot execute a partial patch. exec also terminates the input shell without an EOF.
        return ("exec sh -c '" + command.replace("'", "'\"'\"'") + "'\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static void send(byte[] script, int peerLimit, Writer writer) throws Exception {
        int limit = Math.min(MAX_PACKET, peerLimit);
        if (limit <= 0) throw new IllegalArgumentException("Invalid ADB payload limit");
        for (int offset = 0; offset < script.length; offset += limit)
            writer.write(Arrays.copyOfRange(script, offset, Math.min(script.length, offset + limit)));
    }
}
