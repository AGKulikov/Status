/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Immutable, bounded view model for one expanded Sprut accessory.
 *
 * <p>The potentially expensive matching pass is intentionally pure Java so callers can execute it
 * on a worker thread. The returned object retains at most {@value #DEFAULT_SERVICE_LIMIT} service
 * references and characteristics only for the selected service, capped at
 * {@value #DEFAULT_CHARACTERISTIC_LIMIT}. Counts are lower bounds when the corresponding
 * {@code hasMore...()} flag is true; the UI never needs to rescan the catalog to render "40+" or
 * "80+".</p>
 */
public final class SprutCatalogExpansion {
    public static final int DEFAULT_SERVICE_LIMIT = 40;
    public static final int DEFAULT_CHARACTERISTIC_LIMIT = 80;

    private SprutCatalogExpansion() {}

    /** Builds the production 40-service/80-characteristic expansion. */
    @NonNull
    public static Result compute(@NonNull SprutCatalog.Accessory accessory,
                                 @NonNull String roomName,
                                 @NonNull SprutCatalogIndex.Query query,
                                 long expandedServiceId) {
        return compute(accessory, roomName, query, expandedServiceId,
                DEFAULT_SERVICE_LIMIT, DEFAULT_CHARACTERISTIC_LIMIT);
    }

    /**
     * Builds an expansion with explicit limits. Primarily useful for tests and compact screens.
     * Both limits must be positive.
     */
    @NonNull
    public static Result compute(@NonNull SprutCatalog.Accessory accessory,
                                 @NonNull String roomName,
                                 @NonNull SprutCatalogIndex.Query query,
                                 long expandedServiceId,
                                 int serviceLimit,
                                 int characteristicLimit) {
        Objects.requireNonNull(accessory, "accessory");
        Objects.requireNonNull(roomName, "roomName");
        Objects.requireNonNull(query, "query");
        if (serviceLimit <= 0) throw new IllegalArgumentException("serviceLimit must be positive");
        if (characteristicLimit <= 0) {
            throw new IllegalArgumentException("characteristicLimit must be positive");
        }

        boolean accessoryHeaderMatches = query.matchesAccessoryHeader(accessory, roomName);
        List<ServiceResult> services = new ArrayList<>(Math.min(
                serviceLimit, accessory.services().size()));
        boolean hasMoreServices = false;
        int inspectedServices = 0;

        for (SprutCatalog.Service service : accessory.services()) {
            if ((inspectedServices++ & 63) == 0) checkInterrupted();
            boolean serviceHeaderMatches = accessoryHeaderMatches
                    || query.matchesServiceHeader(accessory, roomName, service);

            // An unfiltered accessory intentionally omits empty services, matching the existing
            // catalog browser. A direct service query may still reveal an empty matching service.
            if (accessoryHeaderMatches && service.characteristics().isEmpty()) continue;

            if (services.size() >= serviceLimit) {
                if (serviceHeaderMatches
                        || hasMatchingCharacteristic(accessory, roomName, service, query)) {
                    hasMoreServices = true;
                    break;
                }
                continue;
            }

            boolean expanded = service.id() == expandedServiceId;
            CharacteristicWindow window = serviceHeaderMatches
                    ? allCharacteristics(service, expanded, characteristicLimit)
                    : matchingCharacteristics(accessory, roomName, service, query, expanded,
                            characteristicLimit);
            if (!serviceHeaderMatches && window.boundedCount == 0) continue;
            services.add(new ServiceResult(service, serviceHeaderMatches, expanded,
                    window.boundedCount, window.hasMore, window.characteristics));
        }

        checkInterrupted();
        return new Result(accessory, roomName, accessoryHeaderMatches,
                Collections.unmodifiableList(services), hasMoreServices);
    }

    private static boolean hasMatchingCharacteristic(SprutCatalog.Accessory accessory,
                                                      String roomName,
                                                      SprutCatalog.Service service,
                                                      SprutCatalogIndex.Query query) {
        int inspected = 0;
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if ((inspected++ & 255) == 0) checkInterrupted();
            if (query.matchesCharacteristic(accessory, roomName, service, characteristic)) {
                return true;
            }
        }
        return false;
    }

    private static CharacteristicWindow allCharacteristics(SprutCatalog.Service service,
                                                            boolean expanded,
                                                            int limit) {
        int size = service.characteristics().size();
        int boundedCount = Math.min(size, limit);
        if (!expanded || boundedCount == 0) {
            return new CharacteristicWindow(boundedCount, size > limit,
                    Collections.emptyList());
        }
        List<SprutCatalog.Characteristic> characteristics = new ArrayList<>(boundedCount);
        for (int i = 0; i < boundedCount; i++) {
            characteristics.add(service.characteristics().get(i));
        }
        return new CharacteristicWindow(boundedCount, size > limit,
                Collections.unmodifiableList(characteristics));
    }

    private static CharacteristicWindow matchingCharacteristics(
            SprutCatalog.Accessory accessory, String roomName, SprutCatalog.Service service,
            SprutCatalogIndex.Query query, boolean expanded, int limit) {
        List<SprutCatalog.Characteristic> characteristics = expanded
                ? new ArrayList<>(Math.min(limit, service.characteristics().size())) : null;
        int boundedCount = 0;
        boolean hasMore = false;
        int inspected = 0;
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if ((inspected++ & 255) == 0) checkInterrupted();
            if (!query.matchesCharacteristic(accessory, roomName, service, characteristic)) {
                continue;
            }
            if (boundedCount >= limit) {
                hasMore = true;
                break;
            }
            boundedCount++;
            if (characteristics != null) characteristics.add(characteristic);
        }
        List<SprutCatalog.Characteristic> retained = characteristics == null
                || characteristics.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(characteristics);
        return new CharacteristicWindow(boundedCount, hasMore, retained);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static final class CharacteristicWindow {
        private final int boundedCount;
        private final boolean hasMore;
        private final List<SprutCatalog.Characteristic> characteristics;

        private CharacteristicWindow(int boundedCount, boolean hasMore,
                                     List<SprutCatalog.Characteristic> characteristics) {
            this.boundedCount = boundedCount;
            this.hasMore = hasMore;
            this.characteristics = characteristics;
        }
    }

    public static final class Result {
        private final SprutCatalog.Accessory accessory;
        private final String roomName;
        private final boolean accessoryHeaderMatches;
        private final List<ServiceResult> services;
        private final boolean hasMoreServices;

        private Result(SprutCatalog.Accessory accessory, String roomName,
                       boolean accessoryHeaderMatches, List<ServiceResult> services,
                       boolean hasMoreServices) {
            this.accessory = accessory;
            this.roomName = roomName;
            this.accessoryHeaderMatches = accessoryHeaderMatches;
            this.services = services;
            this.hasMoreServices = hasMoreServices;
        }

        public SprutCatalog.Accessory accessory() { return accessory; }

        public String roomName() { return roomName; }

        public boolean accessoryHeaderMatches() { return accessoryHeaderMatches; }

        /** At most the requested service limit, in the accessory's stable service order. */
        public List<ServiceResult> services() { return services; }

        /** True when at least one additional matching service was deliberately not retained. */
        public boolean hasMoreServices() { return hasMoreServices; }
    }

    public static final class ServiceResult {
        private final SprutCatalog.Service service;
        private final boolean serviceHeaderMatches;
        private final boolean expanded;
        private final int boundedCharacteristicCount;
        private final boolean hasMoreCharacteristics;
        private final List<SprutCatalog.Characteristic> characteristics;

        private ServiceResult(SprutCatalog.Service service, boolean serviceHeaderMatches,
                              boolean expanded, int boundedCharacteristicCount,
                              boolean hasMoreCharacteristics,
                              List<SprutCatalog.Characteristic> characteristics) {
            this.service = service;
            this.serviceHeaderMatches = serviceHeaderMatches;
            this.expanded = expanded;
            this.boundedCharacteristicCount = boundedCharacteristicCount;
            this.hasMoreCharacteristics = hasMoreCharacteristics;
            this.characteristics = characteristics;
        }

        public SprutCatalog.Service service() { return service; }

        public boolean serviceHeaderMatches() { return serviceHeaderMatches; }

        public boolean expanded() { return expanded; }

        /** Matching count capped at the requested characteristic limit. */
        public int boundedCharacteristicCount() { return boundedCharacteristicCount; }

        /** True when the real matching count is greater than the bounded count. */
        public boolean hasMoreCharacteristics() { return hasMoreCharacteristics; }

        /** Empty for collapsed services; bounded references for the selected expanded service. */
        public List<SprutCatalog.Characteristic> characteristics() { return characteristics; }
    }
}
