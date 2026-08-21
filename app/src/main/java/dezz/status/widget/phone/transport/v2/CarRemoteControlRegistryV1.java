/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable, finite mapping shared with Helper. No raw vehicle function can cross BLE.
 *
 * <p>The original 39 entries never change their ids. Optional v56 entries are appended so a
 * v55 Helper can safely ignore them while a v56 Helper still accepts a Natro build exposing only
 * the original catalog.</p>
 */
public final class CarRemoteControlRegistryV1 {
    public static final class Entry {
        public final int wireId;
        public final String controlId;
        public final boolean mechanical;
        public final boolean requiresConfirmation;
        public final boolean media;
        /** Wire integer units per one public descriptor unit. */
        public final int scale;

        Entry(int wireId, String controlId, boolean mechanical,
              boolean requiresConfirmation, boolean media, int scale) {
            this.wireId = wireId;
            this.controlId = controlId;
            this.mechanical = mechanical;
            this.requiresConfirmation = requiresConfirmation;
            this.media = media;
            this.scale = scale;
        }
    }

    private static final Map<Integer, Entry> BY_WIRE;
    private static final Map<String, Entry> BY_CONTROL;
    private static final List<Entry> ALL;

    static {
        LinkedHashMap<Integer, Entry> wire = new LinkedHashMap<>();
        LinkedHashMap<String, Entry> control = new LinkedHashMap<>();
        add(wire, control, 1, "climate.power", false, false, false);
        add(wire, control, 2, "climate.ac", false, false, false);
        add(wire, control, 3, "climate.auto", false, false, false);
        add(wire, control, 4, "climate.defrost_front", false, false, false);
        add(wire, control, 5, "climate.defrost_front_max", false, false, false);
        add(wire, control, 6, "climate.defrost_rear", false, false, false);
        add(wire, control, 7, "climate.sync", false, false, false);
        add(wire, control, 8, "climate.circulation", false, false, false);
        add(wire, control, 9, "climate.fan", false, false, false);
        add(wire, control, 10, "climate.airflow", false, false, false);
        addScaled(wire, control, 11, "climate.temp_driver", 100);
        addScaled(wire, control, 12, "climate.temp_passenger", 100);
        add(wire, control, 13, "climate.rear_power", false, false, false);
        add(wire, control, 14, "climate.rear_auto", false, false, false);
        add(wire, control, 15, "climate.rear_fan", false, false, false);
        addScaled(wire, control, 16, "climate.temp_rear_left", 100);
        addScaled(wire, control, 17, "climate.temp_rear_right", 100);
        add(wire, control, 18, "climate.panel_lock", false, false, false);
        add(wire, control, 20, "climate.seat_heat_driver", false, false, false);
        add(wire, control, 21, "climate.seat_heat_passenger", false, false, false);
        add(wire, control, 22, "climate.seat_vent_driver", false, false, false);
        add(wire, control, 23, "climate.seat_vent_passenger", false, false, false);
        add(wire, control, 24, "climate.wheel_heat", false, false, false);
        add(wire, control, 25, "climate.seat_heat_rear_left", false, false, false);
        add(wire, control, 26, "climate.seat_heat_rear_right", false, false, false);
        add(wire, control, 27, "climate.seat_vent_rear_left", false, false, false);
        add(wire, control, 28, "climate.seat_vent_rear_right", false, false, false);
        add(wire, control, 30, "vehicle.trunk", true, true, false);
        add(wire, control, 31, "vehicle.drive_mode", false, false, false);
        add(wire, control, 32, "vehicle.auto_hold", false, false, false);
        add(wire, control, 33, "vehicle.start_stop", false, false, false);
        add(wire, control, 34, "vehicle.fuel_save", false, false, false);
        add(wire, control, 35, "vehicle.wiper_service", true, true, false);
        add(wire, control, 40, "comfort.ambient_enabled", false, false, false);
        addScaled(wire, control, 41, "comfort.ambient_brightness", 100);
        add(wire, control, 42, "comfort.passenger_screen", false, false, false);
        addScaled(wire, control, 43, "comfort.passenger_screen_day", 100);
        addScaled(wire, control, 44, "comfort.passenger_screen_night", 100);
        add(wire, control, 45, "comfort.ambient_mode", false, false, false);
        add(wire, control, 46, "comfort.ambient_effect", false, false, false);
        add(wire, control, 47, "comfort.ambient_color", false, false, false);
        add(wire, control, 48, "comfort.ambient_theme", false, false, false);
        add(wire, control, 50, "media.play_pause", false, false, true);
        add(wire, control, 51, "media.next", false, false, true);
        add(wire, control, 52, "media.previous", false, false, true);
        add(wire, control, 53, "media.mute", false, false, true);
        add(wire, control, 54, "media.volume", false, false, true, 100);
        add(wire, control, 55, "vehicle.window_close_driver", true, true, false);
        add(wire, control, 56, "vehicle.window_close_passenger", true, true, false);
        add(wire, control, 57, "vehicle.window_close_rear_left", true, true, false);
        add(wire, control, 58, "vehicle.window_close_rear_right", true, true, false);
        add(wire, control, 59, "vehicle.sunroof_close", true, true, false);
        BY_WIRE = Collections.unmodifiableMap(wire);
        BY_CONTROL = Collections.unmodifiableMap(control);
        ALL = Collections.unmodifiableList(new ArrayList<>(wire.values()));
    }

    private CarRemoteControlRegistryV1() { }

    private static void add(Map<Integer, Entry> wire, Map<String, Entry> control, int wireId,
                            String controlId, boolean mechanical,
                            boolean confirmation, boolean media) {
        add(wire, control, wireId, controlId, mechanical, confirmation, media, 1);
    }

    private static void addScaled(Map<Integer, Entry> wire, Map<String, Entry> control,
                                  int wireId, String controlId, int scale) {
        add(wire, control, wireId, controlId, false, false, false, scale);
    }

    private static void add(Map<Integer, Entry> wire, Map<String, Entry> control, int wireId,
                            String controlId, boolean mechanical,
                            boolean confirmation, boolean media, int scale) {
        if (scale <= 0) throw new IllegalArgumentException("scale");
        Entry entry = new Entry(wireId, controlId, mechanical, confirmation, media, scale);
        if (wire.put(wireId, entry) != null || control.put(controlId, entry) != null) {
            throw new IllegalStateException("duplicate car remote registry entry");
        }
    }

    public static Entry forWireId(int wireId) { return BY_WIRE.get(wireId); }
    public static Entry forControlId(String controlId) { return BY_CONTROL.get(controlId); }
    public static List<Entry> all() { return ALL; }
}
