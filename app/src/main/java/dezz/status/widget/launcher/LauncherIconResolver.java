/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.Arrays;
import java.util.List;

import dezz.status.widget.R;

/** Resolves application icons and the built-in human-friendly preset library. */
public final class LauncherIconResolver {
    public static final class Preset {
        @NonNull public final String key;
        @NonNull public final String label;
        Preset(String key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final List<Preset> PRESETS = Arrays.asList(
            new Preset("app", "Иконка приложения"),
            new Preset("apps", "Приложения"),
            new Preset("navigation", "Навигация"),
            new Preset("media", "Медиа"),
            new Preset("media_previous", "Предыдущий трек"),
            new Preset("media_next", "Следующий трек"),
            new Preset("garage", "Гараж / ворота"),
            new Preset("gate", "Ворота"),
            new Preset("door", "Дверь"),
            new Preset("lock", "Замок"),
            new Preset("light", "Свет"),
            new Preset("power", "Питание"),
            new Preset("temperature", "Температура"),
            new Preset("climate", "Климат / вентилятор"),
            new Preset("climate_ac", "Кондиционер"),
            new Preset("climate_auto", "Климат AUTO"),
            new Preset("fan", "Скорость вентилятора"),
            new Preset("seat_heat", "Подогрев сиденья"),
            new Preset("seat_vent", "Вентиляция сиденья"),
            new Preset("wheel_heat", "Подогрев руля"),
            new Preset("defrost_front", "Обогрев лобового"),
            new Preset("defrost_rear", "Обогрев заднего стекла"),
            new Preset("wiper", "Дворники"),
            new Preset("drive_mode", "Режим движения"),
            new Preset("auto_hold", "Auto Hold"),
            new Preset("start_stop", "Start/Stop"),
            new Preset("fuel_save", "Экономия топлива"),
            new Preset("water", "Вода"),
            new Preset("devices", "Умный дом"),
            new Preset("scenario", "Сценарий"),
            new Preset("edit", "Изменить"),
            new Preset("settings", "Настройки"),
            new Preset("notification", "Уведомления"));

    private LauncherIconResolver() {}

    @NonNull public static List<Preset> presets() { return PRESETS; }

    @Nullable
    public static Drawable resolve(@NonNull Context context,
                                   @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return resolve(context, shortcut, null);
    }

    @Nullable
    public static Drawable resolve(@NonNull Context context,
                                   @NonNull LauncherShortcutStore.Shortcut shortcut,
                                   @Nullable String colorOverride) {
        Drawable source = null;
        if ("app".equals(shortcut.icon) && shortcut.kind == LauncherShortcutStore.Kind.APP) {
            try {
                ComponentName component = ComponentName.unflattenFromString(shortcut.target);
                if (component != null) source = context.getPackageManager().getActivityIcon(component);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (source == null) source = ContextCompat.getDrawable(context, drawable(shortcut.icon));
        if (source == null) return null;
        source = DrawableCompat.wrap(source).mutate();
        String tint = colorOverride == null ? shortcut.iconColor : colorOverride;
        if (!"none".equalsIgnoreCase(tint)
                && !("app".equals(shortcut.icon) && shortcut.kind == LauncherShortcutStore.Kind.APP)) {
            try { DrawableCompat.setTint(source, Color.parseColor(tint)); }
            catch (IllegalArgumentException ignored) { DrawableCompat.setTint(source, Color.WHITE); }
        }
        return source;
    }

    private static int drawable(String key) {
        switch (key) {
            case "navigation": return android.R.drawable.ic_menu_mylocation;
            case "media": return android.R.drawable.ic_media_play;
            case "media_previous": return android.R.drawable.ic_media_previous;
            case "media_next": return android.R.drawable.ic_media_next;
            case "garage": return R.drawable.ic_popup_garage;
            case "gate": return R.drawable.ic_popup_gate;
            case "door": return R.drawable.ic_popup_door;
            case "lock": return R.drawable.ic_popup_lock;
            case "light": return R.drawable.ic_popup_light;
            case "power": return R.drawable.ic_popup_power;
            case "temperature": return R.drawable.ic_popup_temperature;
            case "climate":
            case "climate_ac":
            case "climate_auto":
            case "fan": return R.drawable.ic_car_climate;
            case "seat_heat": return R.drawable.ic_car_seat_heat;
            case "seat_vent": return R.drawable.ic_car_seat_vent;
            case "wheel_heat": return R.drawable.ic_car_wheel_heat;
            case "defrost_front": return R.drawable.ic_car_defrost_front;
            case "defrost_rear": return R.drawable.ic_car_defrost_rear;
            case "wiper": return R.drawable.ic_car_wiper;
            case "drive_mode": return R.drawable.ic_car_drive_mode;
            case "fuel_save": return R.drawable.ic_car_fuel_save;
            case "auto_hold":
            case "start_stop": return R.drawable.ic_popup_power;
            case "water": return R.drawable.ic_popup_water;
            case "devices": return R.drawable.ic_section_widget;
            case "scenario": return R.drawable.ic_section_content;
            case "edit": return R.drawable.ic_drag_handle;
            case "settings": return R.drawable.ic_settings;
            case "notification": return R.drawable.ic_info;
            case "apps":
            default: return android.R.drawable.ic_dialog_dialer;
        }
    }
}
