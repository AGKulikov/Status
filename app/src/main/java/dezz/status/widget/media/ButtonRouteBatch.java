/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** One cache transaction; an unsupported firmware route does not block the other buttons. */
public final class ButtonRouteBatch {
    public byte[] bytes;
    public final Map<VehicleButton, String> errors = new LinkedHashMap<>();
    public final Map<VehicleButton, String> states = new LinkedHashMap<>();
    public ButtonRouteBatch(byte[] original, VehicleButton[] buttons) {
        bytes = original.clone();
        for (VehicleButton button : buttons) {
            try {
                byte[] next = button == VehicleButton.MEDIA ? MediaInputPatch.apply(bytes, true)
                        : ButtonInputPatch.apply(bytes, button, true);
                states.put(button, button == VehicleButton.MEDIA ? MediaInputPatch.state(next)
                        : button.name() + ":disabled");
                bytes = next;
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                errors.put(button, unavailable.getMessage());
            }
        }
    }
}
