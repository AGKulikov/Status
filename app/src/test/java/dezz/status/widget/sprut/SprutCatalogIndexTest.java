/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class SprutCatalogIndexTest {
    @Test public void pagesLargeCatalogWithoutReturningEveryAccessory() {
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (int i = 0; i < 525; i++) accessories.add(accessory(i + 1L, "Устройство " + i));
        SprutCatalogIndex index = SprutCatalogIndex.build(
                new SprutCatalog(Collections.emptyList(), accessories));

        SprutCatalogIndex.Page first = index.search("", 0, 24);
        SprutCatalogIndex.Page last = index.search("", 999, 24);

        assertEquals(525, index.size());
        assertEquals(525, first.totalMatches());
        assertEquals(24, first.entries().size());
        assertEquals(22, last.pageCount());
        assertEquals(21, last.pageIndex());
        assertEquals(21, last.entries().size());
        assertEquals(504, last.fromIndex());
        assertEquals(525, last.toIndex());
    }

    @Test public void nestedCharacteristicIsSearchableOutsideFirstPage() {
        SprutCatalog.Accessory target = accessoryWithCharacteristic(9_999L, "Котельная",
                "Давление магистрали");
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (int i = 0; i < 500; i++) accessories.add(accessory(i + 1L, "Датчик " + i));
        accessories.add(target);
        SprutCatalog.Room room = new SprutCatalog.Room(7L, "Техническая комната", 0, "", true);
        SprutCatalog catalog = new SprutCatalog(Collections.singletonList(room), accessories);

        SprutCatalogIndex.Page result = SprutCatalogIndex.build(catalog)
                .search("давление магистрали", 0, 24);

        assertEquals(1, result.totalMatches());
        assertEquals(target, result.entries().get(0).accessory());
        assertEquals("Техническая комната", result.entries().get(0).roomName());
        assertEquals(1, result.entries().get(0).characteristicCount());
    }

    @Test public void breadcrumbQueryUsesSamePredicateForDiscoveryAndExpansion() {
        SprutCatalog.Accessory target = accessoryWithCharacteristic(9_999L, "Котельная",
                "Давление магистрали");
        SprutCatalog.Room room = new SprutCatalog.Room(7L, "Техническая комната", 0, "", true);
        SprutCatalogIndex index = SprutCatalogIndex.build(new SprutCatalog(
                Collections.singletonList(room), Collections.singletonList(target)));
        SprutCatalogIndex.Query query = SprutCatalogIndex.Query.parse(
                "техническая sensor давление");
        SprutCatalog.Service service = target.services().get(0);
        SprutCatalog.Characteristic characteristic = service.characteristics().get(0);

        assertEquals(1, index.search(query, 0, 24).totalMatches());
        assertTrue(query.matchesService(target, room.name(), service));
        assertTrue(query.matchesCharacteristic(target, room.name(), service, characteristic));
    }

    @Test public void searchesTwentyThousandNestedCharacteristicsWithBoundedPage() {
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (int accessory = 0; accessory < 500; accessory++) {
            accessories.add(accessoryWithManyCharacteristics(accessory + 1L,
                    accessory == 499));
        }
        SprutCatalogIndex.Page result = SprutCatalogIndex.build(
                new SprutCatalog(Collections.emptyList(), accessories))
                .search("последняя контрольная точка", 0, 24);

        assertEquals(1, result.totalMatches());
        assertEquals(1, result.entries().size());
        assertEquals(500L, result.entries().get(0).accessory().id());
    }

    @Test public void searchPageIsClampedAfterQueryNarrowsResults() {
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (int i = 0; i < 100; i++) accessories.add(accessory(i + 1L, "Общий " + i));
        accessories.add(accessory(500L, "Уникальные ворота"));
        SprutCatalogIndex.Page result = SprutCatalogIndex.build(
                new SprutCatalog(Collections.emptyList(), accessories))
                .search("уникальные ворота", 10, 24);

        assertEquals(0, result.pageIndex());
        assertEquals(1, result.pageCount());
        assertEquals(1, result.entries().size());
    }

    private static SprutCatalog.Accessory accessory(long id, String name) {
        return new SprutCatalog.Accessory(id, null, name, "", "", "", "", true, false,
                Collections.emptyList());
    }

    private static SprutCatalog.Accessory accessoryWithCharacteristic(long id, String name,
                                                                       String characteristicName) {
        SprutCatalog.Characteristic characteristic = new SprutCatalog.Characteristic(
                new SprutPath(id, 3L, 4L), "sensor", characteristicName, "pressure", "float",
                "bar", true, false, true, true, false, null, null, null,
                Collections.emptyList(), 2.4d, SprutCatalog.ValueType.DOUBLE);
        SprutCatalog.Service service = new SprutCatalog.Service(id, 3L, "Манометр", "sensor", 0,
                true, false, Collections.singletonList(characteristic));
        return new SprutCatalog.Accessory(id, 7L, name, "", "", "", "", true, false,
                Collections.singletonList(service));
    }

    private static SprutCatalog.Accessory accessoryWithManyCharacteristics(long id,
                                                                            boolean target) {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (int serviceIndex = 0; serviceIndex < 4; serviceIndex++) {
            long serviceId = serviceIndex + 1L;
            List<SprutCatalog.Characteristic> characteristics = new ArrayList<>();
            for (int characteristicIndex = 0; characteristicIndex < 10; characteristicIndex++) {
                boolean marker = target && serviceIndex == 3 && characteristicIndex == 9;
                String name = marker ? "Последняя контрольная точка"
                        : "Показатель " + characteristicIndex;
                characteristics.add(new SprutCatalog.Characteristic(
                        new SprutPath(id, serviceId, characteristicIndex + 1L), "sensor", name,
                        "measurement", "float", "", true, false, true, true, false,
                        null, null, null, Collections.emptyList(), characteristicIndex,
                        SprutCatalog.ValueType.INTEGER));
            }
            services.add(new SprutCatalog.Service(id, serviceId, "Сервис " + serviceIndex,
                    "sensor", serviceIndex, true, false, characteristics));
        }
        return new SprutCatalog.Accessory(id, null, "Устройство " + id, "", "", "", "",
                true, false, services);
    }
}
