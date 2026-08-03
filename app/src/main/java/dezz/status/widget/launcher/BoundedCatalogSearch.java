/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure bounded search used by catalog pickers so a 500+ device hub never creates 500 Views. */
public final class BoundedCatalogSearch {
    private BoundedCatalogSearch() {}

    public static final class Item<T> {
        @NonNull public final T value;
        @NonNull public final String label;
        @NonNull private final String searchText;

        public Item(@NonNull T value, String label, String searchText) {
            this.value = Objects.requireNonNull(value, "value");
            this.label = label == null ? "" : label;
            this.searchText = (searchText == null ? this.label : searchText)
                    .toLowerCase(Locale.ROOT);
        }
    }

    public static final class Result<T> {
        @NonNull public final List<Item<T>> visible;
        public final int matches;

        private Result(List<Item<T>> visible, int matches) {
            this.visible = Collections.unmodifiableList(visible);
            this.matches = matches;
        }
    }

    @NonNull
    public static <T> Result<T> filter(@NonNull List<Item<T>> source, String rawQuery,
                                       int limit) {
        Objects.requireNonNull(source, "source");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid result limit");
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        List<Item<T>> visible = new ArrayList<>(Math.min(limit, source.size()));
        int matches = 0;
        for (Item<T> item : source) {
            Item<T> checked = Objects.requireNonNull(item, "item");
            if (!query.isEmpty() && !checked.searchText.contains(query)) continue;
            matches++;
            if (visible.size() < limit) visible.add(checked);
        }
        return new Result<>(visible, matches);
    }
}
