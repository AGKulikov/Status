/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable external-display selection.
 *
 * <p>mHUD 6.1 chooses array slot 2 or the first non-default display. That breaks when the OEM
 * changes display enumeration order. On the supported head unit the OEM assigns a fixed numeric
 * id to each physical display, so that exact id is authoritative. {@link Display#getUniqueId()}
 * remains a secondary fingerprint for compatible firmware variants.</p>
 */
public final class HudDisplaySelector {
    public static final class Candidate {
        public final int id;
        @NonNull public final String uniqueId;
        @NonNull public final String name;
        public final int width;
        public final int height;
        public final int refreshRate;
        public final boolean presentation;
        public final boolean defaultDisplay;
        @Nullable final Display display;

        Candidate(@NonNull Display display) {
            this.display = display;
            id = display.getDisplayId();
            uniqueId = safe(display.getUniqueId());
            name = safe(display.getName());
            Point size = new Point();
            try {
                display.getRealSize(size);
            } catch (RuntimeException ignored) {
                size.x = display.getMode().getPhysicalWidth();
                size.y = display.getMode().getPhysicalHeight();
            }
            width = Math.max(0, size.x);
            height = Math.max(0, size.y);
            refreshRate = Math.round(display.getRefreshRate());
            presentation = (display.getFlags() & Display.FLAG_PRESENTATION) != 0;
            defaultDisplay = id == Display.DEFAULT_DISPLAY;
        }

        /** Pure constructor used by local selection-policy tests. */
        public Candidate(int id, @NonNull String uniqueId, @NonNull String name,
                         int width, int height, boolean presentation, boolean defaultDisplay) {
            this.id = id;
            this.uniqueId = safe(uniqueId);
            this.name = safe(name);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.refreshRate = 0;
            this.presentation = presentation;
            this.defaultDisplay = defaultDisplay;
            this.display = null;
        }

        @NonNull
        public String label() {
            String kind = defaultDisplay ? "основной" : presentation ? "HUD / presentation"
                    : "внешний";
            return name + " · ID " + id + " · " + uniqueId + "\n"
                    + width + "×" + height
                    + (refreshRate > 0 ? " @" + refreshRate + " Гц" : "")
                    + " · " + kind
                    + (defaultDisplay ? ""
                    : HudViewportPolicy.containsCompleteHudPlane(width, height)
                    ? " · область HUD помещается"
                    : " · меньше безопасной области HUD");
        }
    }

    private HudDisplaySelector() {}

    @NonNull
    public static List<Candidate> available(@NonNull Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) return Collections.emptyList();
        ArrayList<Candidate> result = new ArrayList<>();
        try {
            for (Display display : manager.getDisplays()) {
                if (display != null && display.isValid()) result.add(new Candidate(display));
            }
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Once configured, the exact OEM numeric id is authoritative. It is intentionally unsafe to
     * fall through to another external screen when that id is temporarily absent: on a composite
     * head unit that could place HUD content on the passenger or central display.
     *
     * <p>A unique id can be used only by an imported legacy configuration which has no numeric
     * id. With no saved identity there is no automatic target; the user must choose a display
     * from the list that exposes its live id and dimensions.</p>
     */
    public static int preferredIndex(@NonNull List<Candidate> candidates,
                                     @Nullable String savedUniqueId, int savedId) {
        if (savedId >= 0) {
            for (int index = 0; index < candidates.size(); index++) {
                Candidate item = candidates.get(index);
                if (!item.defaultDisplay && item.id == savedId) return index;
            }
            return -1;
        }
        String unique = safe(savedUniqueId);
        if (!unique.isEmpty()) {
            for (int index = 0; index < candidates.size(); index++) {
                Candidate item = candidates.get(index);
                if (!item.defaultDisplay && unique.equals(item.uniqueId)) return index;
            }
            return -1;
        }
        return -1;
    }

    @Nullable
    public static Candidate select(@NonNull Context context, @NonNull HudPanelConfig config) {
        List<Candidate> candidates = available(context);
        int index = preferredIndex(candidates, config.displayUniqueId, config.displayId);
        return index < 0 ? null : candidates.get(index);
    }

    @Nullable
    static Display display(@Nullable Candidate candidate) {
        return candidate == null ? null : candidate.display;
    }

    public static void remember(@NonNull HudPanelConfig config,
                                @NonNull Candidate candidate) {
        config.displayUniqueId = candidate.uniqueId;
        config.displayId = candidate.id;
        config.displayName = candidate.name;
        config.displayWidth = candidate.width;
        config.displayHeight = candidate.height;
    }

    @NonNull
    private static String safe(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        return value.length() > 512 ? value.substring(0, 512) : value;
    }
}
