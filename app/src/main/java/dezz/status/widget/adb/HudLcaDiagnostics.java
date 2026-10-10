/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Incremental, bounded allowlist: shell output is never copied into diagnostics. */
public final class HudLcaDiagnostics {
    private static final String PREFIX = "NATRO_HUD_DIAG ";
    private static final String STAGES = "(?:start|locate_module|platform|verification_tools|module_size|module_hash|module_block"
            + "|root_identity|module_metadata|module_context|backup_directory|operation_lock|backup_copy|prepare_original_block"
            + "|backup_hash|backup_existing_verify|target_copy|prepare_target_block|target_hash|journal_persist|remount_rw"
            + "|vendor_backup|target_install|installed_readback|rollback|completed"
            + "|block_prefix|block_bytes|block_suffix|block_size|block_commit)";
    private static final Pattern RECORD = Pattern.compile(
            "(?:stage="+STAGES+"|exit=[0-9]{1,3} stage="+STAGES
            + "|metadata=[0-9]{1,10}:[0-9]{1,10}:[0-7]{3,4}"
            + "|context=u:object_r:[a-zA-Z0-9_]{1,64}:s0"
            + "|platform=[0-9]{1,3}:[a-zA-Z0-9_-]{1,24}"
            + "|mode=(?:ORIGINAL|SIMPLE|GUIDE|AR) size=[0-9]{1,10}"
            + "|block_error=(?:permission|no_space|read_only|missing_tool|unsupported|io|other) rc=[0-9]{1,3})");
    private final Consumer<String> sink;
    private final StringBuilder line = new StringBuilder();
    private boolean discard;
    private int records, oversizedLines, sinkFailures;
    private String lastStage = "unobserved", terminal = "unobserved";

    public HudLcaDiagnostics(Consumer<String> sink) { this.sink = sink; }

    public void accept(byte[] bytes) {
        for (byte value : bytes) {
            int c = value & 255;
            if (c == '\n') {
                if (!discard) acceptLine(line.toString());
                line.setLength(0); discard = false;
            } else if (c != '\r' && !discard) {
                if (line.length() >= 256 || c < 32 || c > 126) {
                    discard = true; line.setLength(0); oversizedLines++;
                } else line.append((char)c);
            }
        }
    }

    private void acceptLine(String text) {
        if (!text.startsWith(PREFIX)) return;
        String record = text.substring(PREFIX.length());
        if (!RECORD.matcher(record).matches()) return;
        records++;
        if (record.startsWith("stage=")) lastStage = record.substring(6);
        if (record.startsWith("exit=")) terminal = record;
        // Keep observing the terminal marker even when output is unexpectedly repetitive.
        if (records <= 128 || record.startsWith("exit=")) {
            try { sink.accept(record); }
            catch (RuntimeException unavailable) { sinkFailures++; }
        }
    }

    public String summary() {
        return "shell_stage=" + lastStage + ", shell_terminal=" + terminal
                + ", diagnostic_records=" + records
                + ", diagnostic_suppressed=" + Math.max(0, records - 128)
                + ", partial_line=" + (discard || line.length() > 0)
                + ", ignored_non_ascii_or_long_lines=" + oversizedLines
                + ", diagnostic_sink_failures=" + sinkFailures;
    }
}
