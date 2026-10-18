/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import dezz.status.widget.adb.AdbShellResult;
import java.nio.charset.StandardCharsets;

/** One read-only, exit-framed task snapshot. Queue time cannot make an old read fresh. */
final class InstrumentOwnerQuery {
    static final long MAX_AGE_MS = 1_500L;
    private final AdbShellResult frame = new AdbShellResult();
    private volatile long startedAt = -1L;

    String command(long now) {
        startedAt = now;
        return frame.wrap("am stack list");
    }

    Observation finish(String output, String error, long now, int display, String packageName) {
        // Legacy transports trim outer newlines. Restore the boundary, not missing output.
        frame.accept(("\n" + (output == null ? "" : output.trim()) + "\n")
                .getBytes(StandardCharsets.UTF_8));
        AdbShellResult.Result result = frame.finish();
        long age = now - startedAt;
        boolean complete = error == null && result.began && result.success() && !result.truncated
                && startedAt >= 0 && age >= 0 && age <= MAX_AGE_MS;
        InstrumentPanelCloseCoordinator.Owner owner = complete
                ? InstrumentDisplayOwner.ownerAfterClose(result.output, display, packageName, true)
                : InstrumentPanelCloseCoordinator.Owner.UNKNOWN;
        String description = "owner=" + owner + " exit=" + result.exitCode
                + " began=" + result.began + " truncated=" + result.truncated
                + " transport_ok=" + (error == null)
                + " age_ms=" + age + " "
                + InstrumentDisplayOwner.describeClose(result.output, display, packageName, complete);
        return new Observation(owner, startedAt, description);
    }

    static final class Observation {
        final InstrumentPanelCloseCoordinator.Owner owner;
        final long observedAt;
        final String description;
        Observation(InstrumentPanelCloseCoordinator.Owner owner, long observedAt, String description) {
            this.owner = owner; this.observedAt = observedAt; this.description = description;
        }
    }
}
