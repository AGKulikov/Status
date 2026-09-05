/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static dezz.status.widget.EcarxParkingWindowPolicy.State.HIDDEN;
import static dezz.status.widget.EcarxParkingWindowPolicy.State.UNKNOWN;
import static dezz.status.widget.EcarxParkingWindowPolicy.State.VISIBLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class EcarxParkingWindowPolicyTest {
    private static final String PARKING = EcarxParkingWindowPolicy.PARKING_PACKAGE;
    private static final NavigatorWindowFramePolicy.Frame SCREEN = frame(0, 0, 1920, 720);

    @Test public void visibleParkingApplicationAndSystemPanelOwnTheMainDisplay() {
        for (int type : new int[]{1, 2, 1000, 2002, 2038}) {
            assertEquals(VISIBLE, classify(window(PARKING, 0, type, 0, frame(0, 90, 640, 720))));
        }
    }

    @Test public void ownershipRequiresTheExactParkingPackageOnTheMainDisplay() {
        for (String name : new String[]{"android", "com.ecarx.car", "com.ecarx.parking.extra",
                "prefix.com.ecarx.parking", "ru.yandex.yandexnavi"}) {
            assertEquals(HIDDEN, classify(window(name, 0, 2038, 0, SCREEN)));
        }
        assertEquals(HIDDEN, classify(window(PARKING, 1, 2038, 0, SCREEN)));
        assertEquals(HIDDEN, classify(window(PARKING, 2, 2038, 0, SCREEN)));
    }

    @Test public void removedInvisibleAndGoneWindowsDoNotOwnTheScreen() {
        assertEquals(HIDDEN, classify());
        assertEquals(HIDDEN, classify(window(PARKING, 0, 2038, 4, SCREEN)));
        assertEquals(HIDDEN, classify(window(PARKING, 0, 2038, 8, null)));
    }

    @Test public void geometryMustIntersectTheScreenWithPositiveArea() {
        for (NavigatorWindowFramePolicy.Frame f : new NavigatorWindowFramePolicy.Frame[]{
                frame(-640, 0, 0, 720), frame(1920, 0, 2500, 720),
                frame(0, 720, 640, 900), frame(0, -720, 640, 0), frame(0, 0, 0, 720)}) {
            assertEquals(HIDDEN, classify(window(PARKING, 0, 2038, 0, f)));
        }
        assertEquals(VISIBLE, classify(window(PARKING, 0, 2038, 0, frame(-100, 0, 100, 720))));
    }

    @Test public void toastsKeyboardsAndWallpaperAreNotParkingGraphics() {
        for (int type : new int[]{2005, 2011, 2012, 2013}) {
            assertEquals(HIDDEN, classify(window(PARKING, 0, type, 0, SCREEN)));
        }
    }

    @Test public void incompleteInventoryIsUnknownRatherThanClosure() {
        assertEquals(UNKNOWN, EcarxParkingWindowPolicy.classify(SCREEN, 0, null));
        assertEquals(UNKNOWN, EcarxParkingWindowPolicy.classify(null, 0, Collections.emptyList()));
        assertEquals(UNKNOWN, classify((EcarxParkingWindowPolicy.WindowSample) null));
        assertEquals(UNKNOWN, classify(window("", 0, 2038, 0, SCREEN)));
        assertEquals(UNKNOWN, classify(window(PARKING, -1, 2038, 0, SCREEN)));
        assertEquals(UNKNOWN, classify(window(PARKING, 0, 2038, -1, SCREEN)));
        assertEquals(UNKNOWN, classify(window(PARKING, 0, 2038, 0, null)));
    }

    @Test public void oneVisibleParkingWindowKeepsTheHoldWhenAnotherCloses() {
        assertEquals(VISIBLE, classify(window(PARKING, 0, 2038, 8, null),
                window(PARKING, 0, 2, 0, SCREEN)));
        assertEquals(VISIBLE, classify(null, window(PARKING, 0, 2038, 0, SCREEN)));
        assertEquals(HIDDEN, classify(window(PARKING, 0, 2038, 8, null),
                window(PARKING, 0, 2, 4, SCREEN)));
    }

    @Test public void closureNeedsASecondFreshAbsentSnapshotNotJustPassageOfTime() {
        EcarxParkingWindowPolicy.VisibilityState state = new EcarxParkingWindowPolicy.VisibilityState();
        state.observe(VISIBLE, 0);
        state.observe(HIDDEN, 100);
        state.observe(HIDDEN, 279);
        assertTrue(state.isActive());
        assertTrue(state.needsAbsenceConfirmation());
        state.observe(HIDDEN, 280);
        assertFalse(state.isActive());
        assertFalse(state.needsAbsenceConfirmation());
    }

    @Test public void noMaximumDurationReleasesAVisibleParkingWindow() {
        EcarxParkingWindowPolicy.VisibilityState state = new EcarxParkingWindowPolicy.VisibilityState();
        state.observe(VISIBLE, 0);
        state.observe(VISIBLE, 86_400_000L);
        assertTrue(state.isActive());
        assertFalse(state.needsAbsenceConfirmation());
    }

    @Test public void channelFailureKeepsLastConfirmedVisibilityWithoutAnExpiry() {
        EcarxParkingWindowPolicy.VisibilityState state = new EcarxParkingWindowPolicy.VisibilityState();
        state.observe(VISIBLE, 0);
        state.observe(HIDDEN, 100);
        state.observe(UNKNOWN, 86_400_000L);
        assertTrue(state.isActive());
        assertFalse(state.needsAbsenceConfirmation());
        state.observe(HIDDEN, 86_400_100L);
        assertTrue(state.isActive());
        state.observe(HIDDEN, 86_400_280L);
        assertFalse(state.isActive());
    }

    @Test public void reappearingGraphicsCancelAPendingClosure() {
        EcarxParkingWindowPolicy.VisibilityState state = new EcarxParkingWindowPolicy.VisibilityState();
        state.observe(VISIBLE, 0);
        state.observe(HIDDEN, 100);
        state.observe(VISIBLE, 200);
        assertFalse(state.needsAbsenceConfirmation());
        state.observe(HIDDEN, 400);
        assertTrue(state.isActive());
        state.observe(HIDDEN, 580);
        assertFalse(state.isActive());
    }

    @Test public void coldStartUnknownDoesNotInventAParkingWindow() {
        EcarxParkingWindowPolicy.VisibilityState state = new EcarxParkingWindowPolicy.VisibilityState();
        state.observe(UNKNOWN, 0);
        assertFalse(state.isActive());
        state.observe(VISIBLE, 100);
        state.reset(); // Explicitly disabling the notification/shade observation feature.
        assertFalse(state.isActive());
        assertFalse(state.needsAbsenceConfirmation());
    }

    @Test public void cachedVendorInventoryIsNotAnotherVisibilityOrClosureConfirmation() {
        EcarxParkingWindowPolicy.FreshSnapshots snapshots = new EcarxParkingWindowPolicy.FreshSnapshots();
        Object[] visible = {new Object()};
        assertTrue(snapshots.accept(visible));
        assertFalse(snapshots.accept(visible));
        assertFalse(snapshots.accept(null));
        assertFalse(snapshots.accept(visible));
        Object[] empty = {};
        assertTrue(snapshots.accept(empty));
        assertFalse(snapshots.accept(empty));
        assertTrue(snapshots.accept(new Object[0]));
    }

    private static EcarxParkingWindowPolicy.State classify(EcarxParkingWindowPolicy.WindowSample... w) {
        return EcarxParkingWindowPolicy.classify(SCREEN, 0, Arrays.asList(w));
    }

    private static EcarxParkingWindowPolicy.WindowSample window(String pkg, int display, int type,
                                                               int visibility, NavigatorWindowFramePolicy.Frame f) {
        return new EcarxParkingWindowPolicy.WindowSample(pkg, display, type, visibility, f);
    }

    private static NavigatorWindowFramePolicy.Frame frame(int left, int top, int right, int bottom) {
        return new NavigatorWindowFramePolicy.Frame(left, top, right, bottom);
    }
}
