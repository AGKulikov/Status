/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable catalog structure with mutable current characteristic values. */
public final class SprutCatalog {
    private final List<Room> rooms;
    private final List<Accessory> accessories;
    private final List<Service> services;
    private final List<Characteristic> characteristics;
    private final Map<Long, Room> roomsById;
    private final Map<Long, Accessory> accessoriesById;
    private final Map<ServicePath, Service> servicesByPath;
    private final Map<SprutPath, Characteristic> characteristicsByPath;

    public SprutCatalog(Collection<Room> rooms, Collection<Accessory> accessories) {
        this.rooms = immutableCopy(rooms);
        this.accessories = immutableCopy(accessories);

        Map<Long, Room> roomIndex = new LinkedHashMap<>();
        for (Room room : this.rooms) roomIndex.put(room.id(), room);
        roomsById = Collections.unmodifiableMap(roomIndex);

        Map<Long, Accessory> accessoryIndex = new LinkedHashMap<>();
        Map<ServicePath, Service> serviceIndex = new LinkedHashMap<>();
        Map<SprutPath, Characteristic> characteristicIndex = new LinkedHashMap<>();
        List<Service> allServices = new ArrayList<>();
        List<Characteristic> allCharacteristics = new ArrayList<>();
        for (Accessory accessory : this.accessories) {
            accessoryIndex.put(accessory.id(), accessory);
            for (Service service : accessory.services()) {
                allServices.add(service);
                serviceIndex.put(new ServicePath(accessory.id(), service.id()), service);
                for (Characteristic characteristic : service.characteristics()) {
                    allCharacteristics.add(characteristic);
                    characteristicIndex.put(characteristic.path(), characteristic);
                }
            }
        }
        accessoriesById = Collections.unmodifiableMap(accessoryIndex);
        servicesByPath = Collections.unmodifiableMap(serviceIndex);
        characteristicsByPath = Collections.unmodifiableMap(characteristicIndex);
        services = Collections.unmodifiableList(allServices);
        characteristics = Collections.unmodifiableList(allCharacteristics);
    }

    public static SprutCatalog empty() {
        return new SprutCatalog(Collections.emptyList(), Collections.emptyList());
    }

    public List<Room> rooms() { return rooms; }

    public List<Accessory> accessories() { return accessories; }

    /** Flattened snapshot in accessory/service order. */
    public List<Service> services() { return services; }

    /** Flattened snapshot in accessory/service/characteristic order. */
    public List<Characteristic> characteristics() { return characteristics; }

    public Room findRoom(long roomId) { return roomsById.get(roomId); }

    public Accessory findAccessory(long accessoryId) { return accessoriesById.get(accessoryId); }

    public Service findService(long accessoryId, long serviceId) {
        return servicesByPath.get(new ServicePath(accessoryId, serviceId));
    }

    /** Returns {@code null} when the characteristic is not present in the latest snapshot. */
    public Characteristic find(SprutPath path) {
        return characteristicsByPath.get(Objects.requireNonNull(path, "path"));
    }

    public String roomNameFor(Accessory accessory) {
        if (accessory == null || accessory.roomId() == null) return "";
        Room room = roomsById.get(accessory.roomId());
        return room == null ? "" : room.name();
    }

    private static <T> List<T> immutableCopy(Collection<T> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public enum ValueType {
        BOOLEAN("boolValue"),
        DOUBLE("doubleValue"),
        FLOAT("floatValue"),
        INTEGER("intValue"),
        LONG("longValue"),
        STRING("stringValue"),
        UNKNOWN("");

        private final String jsonKey;

        ValueType(String jsonKey) { this.jsonKey = jsonKey; }

        public String jsonKey() { return jsonKey; }

        public static ValueType fromJsonKey(String key) {
            if (key != null) {
                for (ValueType type : values()) {
                    if (type.jsonKey.equals(key)) return type;
                }
            }
            return UNKNOWN;
        }
    }

    public static final class Room {
        private final long id;
        private final String name;
        private final int order;
        private final String type;
        private final boolean visible;

        Room(long id, String name, int order, String type, boolean visible) {
            this.id = id;
            this.name = nonNull(name);
            this.order = order;
            this.type = nonNull(type);
            this.visible = visible;
        }

        public long id() { return id; }

        public String name() { return name; }

        public int order() { return order; }

        public String type() { return type; }

        public boolean visible() { return visible; }
    }

    public static final class Accessory {
        private final long id;
        private final Long roomId;
        private final String name;
        private final String manufacturer;
        private final String model;
        private final String serial;
        private final String firmware;
        private final boolean online;
        private final boolean virtual;
        private final List<Service> services;

        Accessory(long id, Long roomId, String name, String manufacturer, String model,
                  String serial, String firmware, boolean online, boolean virtual,
                  Collection<Service> services) {
            this.id = id;
            this.roomId = roomId;
            this.name = nonNull(name);
            this.manufacturer = nonNull(manufacturer);
            this.model = nonNull(model);
            this.serial = nonNull(serial);
            this.firmware = nonNull(firmware);
            this.online = online;
            this.virtual = virtual;
            this.services = immutableCopy(services);
        }

        public long id() { return id; }

        public Long roomId() { return roomId; }

        public String name() { return name; }

        public String manufacturer() { return manufacturer; }

        public String model() { return model; }

        public String serial() { return serial; }

        public String firmware() { return firmware; }

        public boolean online() { return online; }

        public boolean virtual() { return virtual; }

        public List<Service> services() { return services; }
    }

    public static final class Service {
        private final long accessoryId;
        private final long id;
        private final String name;
        private final String type;
        private final int order;
        private final boolean visible;
        private final boolean system;
        private final List<Characteristic> characteristics;

        Service(long accessoryId, long id, String name, String type, int order, boolean visible,
                boolean system, Collection<Characteristic> characteristics) {
            this.accessoryId = accessoryId;
            this.id = id;
            this.name = nonNull(name);
            this.type = nonNull(type);
            this.order = order;
            this.visible = visible;
            this.system = system;
            this.characteristics = immutableCopy(characteristics);
        }

        public long accessoryId() { return accessoryId; }

        public long id() { return id; }

        public String name() { return name; }

        public String type() { return type; }

        public int order() { return order; }

        public boolean visible() { return visible; }

        public boolean system() { return system; }

        public List<Characteristic> characteristics() { return characteristics; }
    }

    public static final class Characteristic {
        private final SprutPath path;
        private final String serviceType;
        private final String name;
        private final String type;
        private final String format;
        private final String unit;
        private final boolean readable;
        private final boolean writable;
        private final boolean events;
        private final boolean visible;
        private final boolean hidden;
        private final Number minValue;
        private final Number maxValue;
        private final Number minStep;
        private final List<ValidValue> validValues;
        private volatile Object currentValue;
        private volatile ValueType valueType;

        Characteristic(SprutPath path, String serviceType, String name, String type,
                       String format, String unit, boolean readable, boolean writable,
                       boolean events, boolean visible, boolean hidden, Number minValue,
                       Number maxValue, Number minStep, Collection<ValidValue> validValues,
                       Object currentValue, ValueType valueType) {
            this.path = Objects.requireNonNull(path, "path");
            this.serviceType = nonNull(serviceType);
            this.name = nonNull(name);
            this.type = nonNull(type);
            this.format = nonNull(format);
            this.unit = nonNull(unit);
            this.readable = readable;
            this.writable = writable;
            this.events = events;
            this.visible = visible;
            this.hidden = hidden;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.minStep = minStep;
            this.validValues = immutableCopy(validValues);
            this.currentValue = currentValue;
            this.valueType = valueType == null ? ValueType.UNKNOWN : valueType;
        }

        public SprutPath path() { return path; }

        public String serviceType() { return serviceType; }

        public String name() { return name; }

        public String type() { return type; }

        public String format() { return format; }

        public String unit() { return unit; }

        public boolean readable() { return readable; }

        public boolean writable() { return writable; }

        public boolean events() { return events; }

        public boolean visible() { return visible; }

        public boolean hidden() { return hidden; }

        public Number minValue() { return minValue; }

        public Number maxValue() { return maxValue; }

        public Number minStep() { return minStep; }

        public List<ValidValue> validValues() { return validValues; }

        public Object currentValue() { return currentValue; }

        public ValueType valueType() { return valueType; }

        synchronized Object updateCurrentValue(Object newValue, ValueType newType) {
            Object previous = currentValue;
            currentValue = newValue;
            if (newType != null && newType != ValueType.UNKNOWN) valueType = newType;
            return previous;
        }
    }

    public static final class ValidValue {
        private final Object value;
        private final String key;
        private final String name;

        ValidValue(Object value, String key, String name) {
            this.value = value;
            this.key = nonNull(key);
            this.name = nonNull(name);
        }

        public Object value() { return value; }

        public String key() { return key; }

        public String name() { return name; }
    }

    private static String nonNull(String value) { return value == null ? "" : value; }

    private static final class ServicePath {
        private final long accessoryId;
        private final long serviceId;

        ServicePath(long accessoryId, long serviceId) {
            this.accessoryId = accessoryId;
            this.serviceId = serviceId;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ServicePath)) return false;
            ServicePath path = (ServicePath) other;
            return accessoryId == path.accessoryId && serviceId == path.serviceId;
        }

        @Override public int hashCode() { return Objects.hash(accessoryId, serviceId); }
    }
}
