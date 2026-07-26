/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Interprets the live SurfaceFlinger layer list after the bridge accepts its first frame. */
final class HudSurfaceLayerDiagnostics {
    private HudSurfaceLayerDiagnostics() {}

    @NonNull
    static Result inspect(@Nullable String dump, @Nullable String error,
                          @NonNull String baseName, boolean maskEnabled) {
        String contentName = baseName + "_content";
        String maskName = baseName + "_mask";
        boolean contentFound = containsLayer(dump, contentName);
        boolean maskFound = containsLayer(dump, maskName);
        String failure = bounded(error);
        if (failure.isEmpty() && dump == null) failure = "нет ответа dumpsys";
        return new Result(contentFound, maskFound, maskEnabled, failure);
    }

    private static boolean containsLayer(@Nullable String dump, @NonNull String expected) {
        if (dump == null || dump.isEmpty()) return false;
        String[] lines = dump.split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().contains(expected)) return true;
        }
        return false;
    }

    @NonNull
    private static String bounded(@Nullable String value) {
        if (value == null) return "";
        String result = value.trim().replace('\n', ' ').replace('\r', ' ');
        return result.length() > 120 ? result.substring(0, 120) : result;
    }

    static final class Result {
        final boolean contentFound;
        final boolean maskFound;
        final boolean maskEnabled;
        @NonNull final String failure;

        Result(boolean contentFound, boolean maskFound, boolean maskEnabled,
               @NonNull String failure) {
            this.contentFound = contentFound;
            this.maskFound = maskFound;
            this.maskEnabled = maskEnabled;
            this.failure = failure;
        }

        boolean complete() {
            return contentFound && (!maskEnabled || maskFound);
        }

        @NonNull
        String detail() {
            String maskState = maskEnabled ? "маска ВКЛ" : "маска ВЫКЛ";
            if (!failure.isEmpty()) {
                return maskState + " · проверка слоёв недоступна: " + failure;
            }
            if (complete()) {
                return maskState + (maskEnabled
                        ? " · mask/content найдены в SurfaceFlinger"
                        : " · content найден в SurfaceFlinger");
            }
            if (!contentFound && !maskFound) {
                return maskState + " · слои не найдены в SurfaceFlinger";
            }
            if (!contentFound) {
                return maskState + " · слой content не найден";
            }
            return maskState + " · слой mask не найден";
        }
    }
}
