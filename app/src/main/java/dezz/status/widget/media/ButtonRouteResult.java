/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Per-button readback; a successful sibling must never certify a failed VA route. */
public final class ButtonRouteResult {
    private ButtonRouteResult() {}
    public static boolean allRestored(Collection<VehicleButton> requested, String output, String error) {
        if (error != null || output == null || output.contains("NATRO_MEDIA_ERROR=")
                || output.contains("NATRO_BUTTON_ERROR=")) return false;
        Set<String> lines = new HashSet<>(java.util.Arrays.asList(output.split("\\r?\\n")));
        for (VehicleButton button : requested) {
            String state = button == VehicleButton.MEDIA ? MediaKeyPolicy.NATRO : button.name() + ":disabled";
            if (!lines.contains("NATRO_MEDIA_ROUTE=" + state)) return false;
        }
        return true;
    }
    public static boolean retryable(String output, String error) {
        // An explicit firmware/route rejection needs inspection, not repeated XSF restarts.
        return error != null || output == null || !output.contains("NATRO_BUTTON_ERROR=");
    }
}
