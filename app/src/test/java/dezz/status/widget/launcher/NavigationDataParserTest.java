/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import dezz.status.widget.launcher.panels.PanelElementConfigStore;

public class NavigationDataParserTest {
    @Test
    public void exactYandexExtrasArePreferredAndNormalized() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(
                "9:07", "2 ч. 3 мин", "153.0 km", Collections.emptyList());

        assertEquals("09:07", parsed.arrival);
        assertEquals("2 ч 3 мин", parsed.duration);
        assertEquals("153 км", parsed.distance);
    }

    @Test
    public void readsRussianRouteSummaryUsedByNavigator() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Collections.singletonList(
                "Время прибытия: 09:27 Осталось: 10 мин · 3,5 км"));

        assertEquals("09:27", parsed.arrival);
        assertEquals("10 мин", parsed.duration);
        assertEquals("3,5 км", parsed.distance);
    }

    @Test
    public void readsSeparateAccessibilityAndRemoteViewLabels() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Arrays.asList(
                "09:27", "Осталось 1 ч 5 мин", "42 км"));

        assertEquals("09:27", parsed.arrival);
        assertEquals("1 ч 5 мин", parsed.duration);
        assertEquals("42 км", parsed.distance);
    }

    @Test
    public void choosesRemainingRouteInsteadOfNextManeuver() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Arrays.asList(
                "Через 500 м поверните направо", "Осталось 10 мин · 3,5 км", "09:27"));

        assertEquals("3,5 км", parsed.distance);
        assertEquals("10 мин", parsed.duration);
        assertEquals("09:27", parsed.arrival);
    }

    @Test
    public void readsSerializedNavigationDataFallback() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Collections.singletonList(
                "NavigationData(arrivalTime=14:56, remainingTime=2 ч 3 мин, "
                        + "remainingDistance=153 км)"));

        assertEquals("14:56", parsed.arrival);
        assertEquals("2 ч 3 мин", parsed.duration);
        assertEquals("153 км", parsed.distance);
    }

    @Test
    public void supportsHourOnlyDurationWithoutEmptyRegexMatches() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(
                Collections.singletonList("Осталось 2 ч"));
        assertEquals("2 ч", parsed.duration);

        NavigationDataParser.Parsed irrelevant = NavigationDataParser.parse(
                Collections.singletonList("Въезд закрыт"));
        assertFalse(irrelevant.hasData());
    }

    @Test
    public void clockWithoutRouteEvidenceIsNotTreatedAsEta() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(
                Collections.singletonList("Сейчас 07:45"));
        assertEquals("", parsed.arrival);
        assertFalse(parsed.hasData());
    }

    @Test
    public void exactStandaloneNavigatorAndMapsPackagesAreSupported() {
        assertTrue(NavigationDataRepository.isSupportedPackage("ru.yandex.yandexnavi"));
        assertTrue(NavigationDataRepository.isSupportedPackage("ru.yandex.yandexmaps"));
        assertEquals(NavigationDataRepository.PRODUCT_NAVIGATOR,
                NavigationDataRepository.productForPackage("ru.yandex.yandexnavi"));
        assertEquals(NavigationDataRepository.PRODUCT_MAPS,
                NavigationDataRepository.productForPackage("ru.yandex.yandexmaps"));
        assertTrue(NavigationDataRepository.notificationPriority("ru.yandex.yandexnavi")
                > NavigationDataRepository.notificationPriority("ru.yandex.yandexmaps"));
        assertTrue(NavigationDataRepository.notificationPriority("ru.yandex.yandexmaps")
                > NavigationDataRepository.notificationPriority("com.yandex.yango"));
    }

    @Test
    public void bootCountScopesTransientNavigationToOneAndroidBoot() {
        assertTrue(NavigationDataRepository.sameBootSession(42, 100_000L,
                42, 900_000L));
        assertFalse(NavigationDataRepository.sameBootSession(42, 100_000L,
                43, 100_000L));
    }

    @Test
    public void bootEpochIsSafeFallbackWhenBootCountIsUnavailable() {
        assertTrue(NavigationDataRepository.sameBootSession(-1, 1_000_000L,
                -1, 1_120_000L));
        assertFalse(NavigationDataRepository.sameBootSession(-1, 1_000_000L,
                -1, 1_360_001L));
        assertFalse(NavigationDataRepository.sameBootSession(-1, Long.MIN_VALUE,
                -1, 1_000_000L));
    }

    @Test
    public void richNavigationContractUsesExactMhudActions() {
        assertEquals("plus.monjaro.NAVIGATION_UPDATE",
                NavigationDataRepository.ACTION_MONJARO_NAVIGATION_UPDATE);
        assertEquals("plus.monjaro.LANE_SIGN_DIST",
                NavigationDataRepository.ACTION_MONJARO_LANE_SIGN_DISTANCE);
        assertEquals("com.yandex.LANES_BITMAP",
                NavigationDataRepository.ACTION_YANDEX_LANES_BITMAP);
        assertEquals("com.yandex.JAM_IMAGE",
                NavigationDataRepository.ACTION_YANDEX_JAM_IMAGE);
        assertEquals("debug.monjaro.RAINBOW_IMAGE_CLEAR",
                NavigationDataRepository.ACTION_DEBUG_RAINBOW_IMAGE_CLEAR);
    }

    @Test
    public void newGraphicElementsDoNotOverflowExistingLauncherLayouts() {
        String[] newElements = {
                PanelElementConfigStore.NAV_MANEUVER_IMAGE,
                PanelElementConfigStore.NAV_MANEUVER_DISTANCE,
                PanelElementConfigStore.NAV_TRIP_INFO,
                PanelElementConfigStore.NAV_COMBINED,
                PanelElementConfigStore.NAV_LANES_IMAGE,
                PanelElementConfigStore.NAV_LANE_INFO,
                PanelElementConfigStore.NAV_JAM_PROGRESS,
                PanelElementConfigStore.NAV_RAINBOW_IMAGE
        };
        for (String id : newElements) {
            PanelElementConfigStore.Definition definition = PanelElementConfigStore.definition(
                    LauncherLayoutStore.NAVIGATION, id);
            assertNotNull(definition);
            assertFalse(definition.enabledByDefault);
        }
    }
}
