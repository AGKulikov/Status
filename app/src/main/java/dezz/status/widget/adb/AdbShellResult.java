/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell-v1 framing. Captures a bounded head and tail, including the real exit status. */
public final class AdbShellResult {
    public static final int OUTPUT_LIMIT = 128 * 1024;
    private static final int TAIL_LIMIT = 512;
    private final String token;
    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private byte[] tail = new byte[0];
    private long total;

    public AdbShellResult() { this("NATRO_EXIT_" + UUID.randomUUID().toString().replace("-", "")); }
    AdbShellResult(String token) { this.token = token; }
    public static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    public String wrap(String command) {
        // Inner sh prevents exit, trailing comments, and compound commands from eating framing.
        return "sh -c " + quote(command) + "; printf '\\n" + token + ":%s\\n' \"$?\"";
    }
    public void accept(byte[] chunk) {
        int keep = Math.min(chunk.length, OUTPUT_LIMIT - head.size());
        if (keep > 0) head.write(chunk, 0, keep);
        int count = Math.min(TAIL_LIMIT, tail.length + chunk.length);
        byte[] next = new byte[count];
        int fromChunk = Math.min(count, chunk.length);
        int fromOld = count - fromChunk;
        if (fromOld > 0) System.arraycopy(tail, tail.length - fromOld, next, 0, fromOld);
        System.arraycopy(chunk, chunk.length - fromChunk, next, fromOld, fromChunk);
        tail = next; total += chunk.length;
    }
    public Result finish() {
        String end = new String(tail, StandardCharsets.UTF_8);
        Matcher marker = Pattern.compile("\\r?\\n" + token + ":([0-9]{1,3})\\r?\\n?$").matcher(end);
        Integer code = marker.find() ? Integer.valueOf(marker.group(1)) : null;
        String output = new String(head.toByteArray(), StandardCharsets.UTF_8);
        if (total <= OUTPUT_LIMIT) output = output.replaceFirst("\\r?\\n" + token + ":[0-9]{1,3}\\r?\\n?$", "");
        else output += "\n… Вывод ограничен 128 КиБ; команда дочитана до завершения.";
        return new Result(output, code, total > OUTPUT_LIMIT);
    }
    public static final class Result {
        public final String output;
        public final Integer exitCode;
        public final boolean truncated;
        public Result(String output, Integer exitCode, boolean truncated) {
            this.output = output; this.exitCode = exitCode; this.truncated = truncated;
        }
        public boolean success() { return exitCode != null && exitCode == 0; }
        public String describe() {
            return output + (output.isEmpty() ? "" : "\n") + (exitCode == null
                    ? "Соединение закрыто без кода завершения. Результат неизвестен; команда не повторялась."
                    : "Код завершения: " + exitCode);
        }
    }
}
