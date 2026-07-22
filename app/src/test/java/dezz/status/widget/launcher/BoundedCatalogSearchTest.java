package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BoundedCatalogSearchTest {
    @Test public void largeCatalogCountsAllButReturnsOnlyBoundedPage() {
        List<BoundedCatalogSearch.Item<Integer>> items = new ArrayList<>();
        for (int index = 0; index < 700; index++) {
            items.add(new BoundedCatalogSearch.Item<>(index, "Устройство " + index,
                    "Комната устройство " + index));
        }

        BoundedCatalogSearch.Result<Integer> result =
                BoundedCatalogSearch.filter(items, "устройство", 60);

        assertEquals(700, result.matches);
        assertEquals(60, result.visible.size());
        assertEquals(Integer.valueOf(0), result.visible.get(0).value);
        assertEquals(Integer.valueOf(59), result.visible.get(59).value);
    }

    @Test public void searchIsLocaleIndependentAndChecksWholeCatalog() {
        List<BoundedCatalogSearch.Item<String>> items = new ArrayList<>();
        items.add(new BoundedCatalogSearch.Item<>("a", "Кухня", "Кухня свет"));
        items.add(new BoundedCatalogSearch.Item<>("b", "Гараж", "Гараж ВОРОТА"));

        BoundedCatalogSearch.Result<String> result =
                BoundedCatalogSearch.filter(items, "ворота", 10);

        assertEquals(1, result.matches);
        assertEquals("b", result.visible.get(0).value);
    }

    @Test public void invalidLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundedCatalogSearch.filter(new ArrayList<>(), "", 0));
    }
}
