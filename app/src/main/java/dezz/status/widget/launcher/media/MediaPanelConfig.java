/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted, connector-independent composition of the HOME media panel. */
public final class MediaPanelConfig {
    public static final String ARTWORK = "media.artwork";
    public static final String TITLE = "media.title";
    public static final String ARTIST = "media.artist";
    public static final String APPLICATION = "media.application";
    public static final String PREVIOUS = "media.previous";
    public static final String PLAY_PAUSE = "media.play_pause";
    public static final String NEXT = "media.next";

    public static final class Spec {
        @NonNull public final String id;
        @NonNull public final String label;
        public final int baseWidthDp;
        public final int baseHeightDp;

        private Spec(@NonNull String id, @NonNull String label, int width, int height) {
            this.id = id;
            this.label = label;
            this.baseWidthDp = width;
            this.baseHeightDp = height;
        }
    }

    public static final class Element {
        @NonNull public final String id;
        public boolean enabled;
        public int order;
        public int scalePercent;

        private Element(@NonNull String id, boolean enabled, int order, int scalePercent) {
            this.id = id;
            this.enabled = enabled;
            this.order = order;
            this.scalePercent = scalePercent;
        }

        @NonNull private Element copy() {
            return new Element(id, enabled, order, scalePercent);
        }
    }

    public static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new Spec(ARTWORK, "Обложка", 112, 112),
            new Spec(TITLE, "Название", 230, 58),
            new Spec(ARTIST, "Исполнитель", 190, 48),
            new Spec(APPLICATION, "Приложение", 150, 40),
            new Spec(PREVIOUS, "Предыдущий трек", 64, 64),
            new Spec(PLAY_PAUSE, "Играть / пауза", 72, 72),
            new Spec(NEXT, "Следующий трек", 64, 64)));

    @NonNull public String backgroundColor = "#121923";
    public int backgroundAlpha = 150;
    public int cornerRadiusPx = 28;
    public int spacingPx = 10;
    public int contentPaddingPx = 12;
    @NonNull public String titleColor = "#FFFFFF";
    @NonNull public String secondaryColor = "#C7D0DD";
    @NonNull public String controlColor = "#FFFFFF";

    private final LinkedHashMap<String, Element> elements = new LinkedHashMap<>();

    public MediaPanelConfig() {
        for (int index = 0; index < SPECS.size(); index++) {
            Spec spec = SPECS.get(index);
            elements.put(spec.id, new Element(spec.id, true, index, 100));
        }
    }

    @NonNull
    public Element element(@NonNull String id) {
        Element value = elements.get(id);
        if (value != null) return value;
        // Public callers use stable built-ins; returning a detached disabled value is safer than
        // crashing HOME if an imported future schema contains an unknown id.
        return new Element(id, false, SPECS.size(), 100);
    }

    @NonNull
    public List<Element> orderedElements() {
        ArrayList<Element> result = new ArrayList<>(elements.values());
        result.sort(Comparator.comparingInt(value -> value.order));
        return Collections.unmodifiableList(result);
    }

    public void setEnabled(@NonNull String id, boolean enabled) {
        Element value = elements.get(id);
        if (value != null) value.enabled = enabled;
    }

    public void setScale(@NonNull String id, int scalePercent) {
        Element value = elements.get(id);
        if (value != null) value.scalePercent = scalePercent;
    }

    /** Moves an element one visual slot and keeps a dense, deterministic order. */
    public void move(@NonNull String id, int direction) {
        if (direction == 0) return;
        ArrayList<Element> ordered = new ArrayList<>(orderedElements());
        int from = -1;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).id.equals(id)) { from = index; break; }
        }
        if (from < 0) return;
        int to = Math.max(0, Math.min(ordered.size() - 1, from + direction));
        if (from == to) return;
        Element moving = ordered.remove(from);
        ordered.add(to, moving);
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
    }

    @NonNull
    public MediaPanelConfig copy() {
        MediaPanelConfig value = new MediaPanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.spacingPx = spacingPx;
        value.contentPaddingPx = contentPaddingPx;
        value.titleColor = titleColor;
        value.secondaryColor = secondaryColor;
        value.controlColor = controlColor;
        value.elements.clear();
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            value.elements.put(entry.getKey(), entry.getValue().copy());
        }
        return value;
    }

    public void normalize() {
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 96);
        spacingPx = clamp(spacingPx, 0, 48);
        contentPaddingPx = clamp(contentPaddingPx, 0, 64);
        if (!isHexColor(backgroundColor)) backgroundColor = "#121923";
        if (!isHexColor(titleColor)) titleColor = "#FFFFFF";
        if (!isHexColor(secondaryColor)) secondaryColor = "#C7D0DD";
        if (!isHexColor(controlColor)) controlColor = "#FFFFFF";

        for (int index = 0; index < SPECS.size(); index++) {
            Spec spec = SPECS.get(index);
            if (!elements.containsKey(spec.id)) {
                elements.put(spec.id, new Element(spec.id, true, index, 100));
            }
            Element element = elements.get(spec.id);
            if (element != null) element.scalePercent = clamp(element.scalePercent, 45, 200);
        }
        ArrayList<Element> ordered = new ArrayList<>(elements.values());
        ordered.removeIf(value -> spec(value.id) == null);
        ordered.sort(Comparator.comparingInt(value -> value.order));
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
    }

    @NonNull
    public Map<String, Element> elementMap() {
        LinkedHashMap<String, Element> result = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            result.put(entry.getKey(), entry.getValue().copy());
        }
        return Collections.unmodifiableMap(result);
    }

    public static Spec spec(@NonNull String id) {
        for (Spec value : SPECS) if (value.id.equals(id)) return value;
        return null;
    }

    private static boolean isHexColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
