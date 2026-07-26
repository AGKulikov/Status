/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
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
            new Preset("home", "Домой"),
            new Preset("back", "Назад"),
            new Preset("work", "Работа"),
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
            new Preset("humidity", "Влажность"),
            new Preset("motion", "Движение / присутствие"),
            new Preset("smoke", "Дым / газ"),
            new Preset("camera", "Камера / звонок"),
            new Preset("blinds", "Шторы / жалюзи"),
            new Preset("thermostat", "Термостат"),
            new Preset("plug", "Розетка"),
            new Preset("battery", "Батарея"),
            new Preset("energy", "Энергия"),
            new Preset("alarm", "Сигнализация"),
            new Preset("vacuum", "Пылесос"),
            new Preset("weather", "Погода"),
            new Preset("music", "Музыка / колонка"),
            new Preset("phone", "Телефон"),
            new Preset("messages", "Сообщения"),
            new Preset("mail", "Почта"),
            new Preset("calendar", "Календарь"),
            new Preset("chat", "Чат"),
            new Preset("social", "Социальные сети"),
            new Preset("photo", "Фото"),
            new Preset("maps", "Карты"),
            new Preset("video", "Видео"),
            new Preset("phone_app_phone", "Звонок с iPhone"),
            new Preset("phone_app_music", "Музыкальное приложение"),
            new Preset("phone_app_work", "Рабочее приложение"),
            new Preset("missed_call", "Пропущенный звонок"),
            new Preset("voicemail", "Голосовая почта"),
            new Preset("news", "Новости"),
            new Preset("health", "Здоровье"),
            new Preset("finance", "Финансы"),
            new Preset("phone_notification", "Уведомление телефона"),
            new Preset("car", "Автомобиль"),
            new Preset("location", "Местоположение"),
            new Preset("status_wifi", "Wi‑Fi статусной строки"),
            new Preset("status_gps", "GPS статусной строки"),
            new Preset("status_bluetooth", "Bluetooth статусной строки"),
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
            ComponentName component = ComponentName.unflattenFromString(shortcut.target);
            if (component != null) source = HighResolutionAppIconLoader.load(context, component);
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

    /** Resolves a built-in icon without manufacturing a launcher shortcut. */
    @Nullable
    public static Drawable resolvePreset(@NonNull Context context, @NonNull String iconKey,
                                         @Nullable String colorOverride) {
        Drawable source = ContextCompat.getDrawable(context, drawable(iconKey));
        if (source == null) return null;
        source = DrawableCompat.wrap(source).mutate();
        if (colorOverride != null && !"none".equalsIgnoreCase(colorOverride)) {
            try { DrawableCompat.setTint(source, Color.parseColor(colorOverride)); }
            catch (IllegalArgumentException ignored) { DrawableCompat.setTint(source, Color.WHITE); }
        }
        return source;
    }

    private static int drawable(String key) {
        switch (key) {
            case "navigation": return R.drawable.ic_launcher_navigation;
            case "home": return R.drawable.ic_launcher_home;
            case "back": return R.drawable.ic_launcher_back;
            case "work": return R.drawable.ic_launcher_work;
            case "media": return R.drawable.ic_media_play;
            case "media_previous": return R.drawable.ic_media_previous;
            case "media_next": return R.drawable.ic_media_next;
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
            case "humidity": return R.drawable.ic_smart_humidity;
            case "motion": return R.drawable.ic_smart_motion;
            case "smoke": return R.drawable.ic_smart_smoke;
            case "camera": return R.drawable.ic_smart_camera;
            case "blinds": return R.drawable.ic_smart_blinds;
            case "thermostat": return R.drawable.ic_smart_thermostat;
            case "plug": return R.drawable.ic_smart_plug;
            case "battery": return R.drawable.ic_smart_battery;
            case "energy": return R.drawable.ic_smart_energy;
            case "alarm": return R.drawable.ic_smart_alarm;
            case "vacuum": return R.drawable.ic_smart_vacuum;
            case "weather": return R.drawable.ic_smart_weather;
            case "music": return R.drawable.ic_smart_music;
            case "phone": return R.drawable.ic_smart_phone;
            case "messages": return R.drawable.ic_phone_app_messages;
            case "mail": return R.drawable.ic_phone_app_mail;
            case "calendar": return R.drawable.ic_phone_app_calendar;
            case "chat": return R.drawable.ic_phone_app_chat;
            case "social": return R.drawable.ic_phone_app_social;
            case "photo": return R.drawable.ic_phone_app_photo;
            case "maps": return R.drawable.ic_phone_app_maps;
            case "video": return R.drawable.ic_phone_app_video;
            case "phone_app_phone": return R.drawable.ic_phone_app_phone;
            case "phone_app_music": return R.drawable.ic_phone_app_music;
            case "phone_app_work": return R.drawable.ic_phone_app_work;
            case "missed_call": return R.drawable.ic_phone_app_missed_call;
            case "voicemail": return R.drawable.ic_phone_app_voicemail;
            case "news": return R.drawable.ic_phone_app_news;
            case "health": return R.drawable.ic_phone_app_health;
            case "finance": return R.drawable.ic_phone_app_finance;
            case "phone_notification": return R.drawable.ic_phone_app_notification;
            case "car": return R.drawable.ic_smart_car;
            case "location": return R.drawable.ic_smart_location;
            case "status_wifi": return R.drawable.ic_status_wifi_internet;
            case "status_gps": return R.drawable.ic_status_gps_good;
            case "status_bluetooth": return R.drawable.ic_status_bt_connected;
            case "devices": return R.drawable.ic_section_widget;
            case "scenario": return R.drawable.ic_section_content;
            case "edit": return R.drawable.ic_drag_handle;
            case "settings": return R.drawable.ic_settings;
            case "notification": return R.drawable.ic_info;
            case "app":
            case "apps":
            default: return R.drawable.ic_launcher_apps;
        }
    }
}
