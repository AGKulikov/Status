/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.R;

/** Offline-only icon allow-list. MQTT never supplies arbitrary paths, files or URLs. */
public final class PopupIconCatalog {
    private PopupIconCatalog() {}

    public static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            "gate", "garage", "light", "lock", "power", "temperature", "water", "door",
            "humidity", "motion", "smoke", "camera", "blinds", "thermostat", "plug",
            "battery", "energy", "alarm", "vacuum", "weather", "music", "phone", "car",
            "location", "fan", "climate", "scenario", "wifi", "gps", "bluetooth"));

    /** Human labels in exactly the same order as {@link #IDS}. */
    public static final List<String> LABELS = Collections.unmodifiableList(Arrays.asList(
            "Ворота", "Гараж", "Свет", "Замок", "Питание", "Температура", "Вода",
            "Дверь", "Влажность", "Движение / присутствие", "Дым / газ",
            "Камера / звонок", "Шторы / жалюзи", "Термостат", "Розетка", "Батарея",
            "Энергия", "Сигнализация", "Пылесос", "Погода", "Музыка / колонка",
            "Телефон", "Автомобиль", "Местоположение", "Вентилятор", "Климат",
            "Сценарий", "Wi-Fi", "GPS", "Bluetooth"));

    @DrawableRes
    public static int resolve(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (id) {
            case "gate": return R.drawable.ic_popup_gate;
            case "garage": return R.drawable.ic_popup_garage;
            case "light": return R.drawable.ic_popup_light;
            case "lock": return R.drawable.ic_popup_lock;
            case "power": return R.drawable.ic_popup_power;
            case "temperature": return R.drawable.ic_popup_temperature;
            case "water": return R.drawable.ic_popup_water;
            case "door": return R.drawable.ic_popup_door;
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
            case "car": return R.drawable.ic_smart_car;
            case "location": return R.drawable.ic_smart_location;
            case "fan":
            case "climate": return R.drawable.ic_car_climate;
            case "scenario": return R.drawable.ic_section_content;
            case "wifi": return R.drawable.ic_status_filled_wifi_internet;
            case "gps": return R.drawable.ic_status_filled_gps_good;
            case "bluetooth": return R.drawable.ic_status_filled_bt_connected;
            default: return 0;
        }
    }

    public static boolean isAllowed(@NonNull String id) { return resolve(id) != 0; }
}
