/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persisted, Android-independent allow/deny policy for live ANCS notifications.
 *
 * <p>ANCS category {@code 0} is the protocol's catch-all category. Unknown future category
 * values are deliberately folded into it so a user can decide whether unrecognised notification
 * types are accepted instead of having a future iOS release bypass the configured filter.</p>
 */
public final class PhoneNotificationFilter {
    public static final int MODE_ALL = 0;
    public static final int MODE_ONLY_SELECTED = 1;
    public static final int MODE_EXCEPT_SELECTED = 2;

    private static final int MIN_CATEGORY_ID = 0;
    private static final int MAX_CATEGORY_ID = 11;
    private static final int MAX_APP_KEY_LENGTH = 512;
    private static final List<Category> CATEGORIES = createCategories();

    private PhoneNotificationFilter() {
    }

    @NonNull
    public static List<Category> categories() {
        return CATEGORIES;
    }

    @NonNull
    public static Set<Integer> allCategoryIds() {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Category category : CATEGORIES) result.add(category.id);
        return Collections.unmodifiableSet(result);
    }

    @NonNull
    public static Set<Integer> parseCategoryIds(@Nullable String csv) {
        LinkedHashSet<Integer> requested = new LinkedHashSet<>();
        if (csv != null) {
            for (String token : csv.split(",", -1)) {
                try {
                    int value = Integer.parseInt(token.trim());
                    if (value >= MIN_CATEGORY_ID && value <= MAX_CATEGORY_ID) {
                        requested.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid persisted entries fail closed and are omitted.
                }
            }
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Category category : CATEGORIES) {
            if (requested.contains(category.id)) result.add(category.id);
        }
        return Collections.unmodifiableSet(result);
    }

    @NonNull
    public static String serializeCategoryIds(@Nullable Collection<Integer> selected) {
        if (selected == null || selected.isEmpty()) return "";
        Set<Integer> requested = new LinkedHashSet<>(selected);
        StringBuilder result = new StringBuilder();
        for (Category category : CATEGORIES) {
            if (!requested.contains(category.id)) continue;
            if (result.length() > 0) result.append(',');
            result.append(category.id);
        }
        return result.toString();
    }

    @NonNull
    public static Set<String> parseAppKeys(@Nullable String csv) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (csv != null) {
            for (String token : csv.split(",", -1)) {
                String key = normalizeAppKey(token);
                if (!key.isEmpty()) result.add(key);
            }
        }
        List<String> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.naturalOrder());
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    @NonNull
    public static String serializeAppKeys(@Nullable Collection<String> selected) {
        if (selected == null || selected.isEmpty()) return "";
        List<String> sorted = new ArrayList<>();
        for (String raw : selected) {
            String key = normalizeAppKey(raw);
            if (!key.isEmpty() && !sorted.contains(key)) sorted.add(key);
        }
        sorted.sort(Comparator.naturalOrder());
        return String.join(",", sorted);
    }

    public static int normalizeMode(int mode) {
        return mode == MODE_ONLY_SELECTED || mode == MODE_EXCEPT_SELECTED
                ? mode : MODE_ALL;
    }

    public static boolean allowsCategory(@Nullable Set<Integer> selectedCategories,
                                         int categoryId) {
        return selectedCategories != null
                && selectedCategories.contains(normalizeCategoryId(categoryId));
    }

    public static boolean allows(int mode, @Nullable Set<String> selectedAppKeys,
                                 @Nullable Set<Integer> selectedCategories,
                                 @Nullable String appKey, int categoryId) {
        if (!allowsCategory(selectedCategories, categoryId)) return false;
        int normalizedMode = normalizeMode(mode);
        if (normalizedMode == MODE_ALL) return true;
        boolean selected = selectedAppKeys != null
                && selectedAppKeys.contains(normalizeAppKey(appKey));
        return normalizedMode == MODE_ONLY_SELECTED ? selected : !selected;
    }

    public static int normalizeCategoryId(int categoryId) {
        return categoryId >= MIN_CATEGORY_ID && categoryId <= MAX_CATEGORY_ID
                ? categoryId : MIN_CATEGORY_ID;
    }

    @NonNull
    public static String normalizeAppKey(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_APP_KEY_LENGTH) return "";
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '.' && character != '_' && character != '-') {
                return "";
            }
        }
        return value;
    }

    @NonNull
    private static List<Category> createCategories() {
        List<Category> result = new ArrayList<>(MAX_CATEGORY_ID + 1);
        for (int id = MIN_CATEGORY_ID; id <= MAX_CATEGORY_ID; id++) {
            result.add(new Category(id, AncsProtocol.categoryLabel(id)));
        }
        return Collections.unmodifiableList(result);
    }

    public static final class Category {
        public final int id;
        @NonNull public final String label;

        private Category(int id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }
    }
}
