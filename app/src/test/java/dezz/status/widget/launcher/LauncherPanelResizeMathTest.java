package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LauncherPanelResizeMathTest {
    private static final LauncherPanelResizeMath.Rect START =
            new LauncherPanelResizeMath.Rect(100, 80, 500, 380);

    @Test public void everyVisualCornerHasAnUnambiguousHitTarget() {
        assertEquals(LauncherPanelResizeMath.Corner.TOP_LEFT,
                LauncherPanelResizeMath.cornerAt(4, 5, 400, 300, 64));
        assertEquals(LauncherPanelResizeMath.Corner.TOP_RIGHT,
                LauncherPanelResizeMath.cornerAt(396, 5, 400, 300, 64));
        assertEquals(LauncherPanelResizeMath.Corner.BOTTOM_LEFT,
                LauncherPanelResizeMath.cornerAt(4, 295, 400, 300, 64));
        assertEquals(LauncherPanelResizeMath.Corner.BOTTOM_RIGHT,
                LauncherPanelResizeMath.cornerAt(396, 295, 400, 300, 64));
        assertEquals(LauncherPanelResizeMath.Corner.NONE,
                LauncherPanelResizeMath.cornerAt(200, 150, 400, 300, 64));
    }

    @Test public void overlappingZonesOnSmallPanelChooseNearestCorner() {
        assertEquals(LauncherPanelResizeMath.Corner.TOP_LEFT,
                LauncherPanelResizeMath.cornerAt(8, 10, 100, 80, 64));
        assertEquals(LauncherPanelResizeMath.Corner.BOTTOM_RIGHT,
                LauncherPanelResizeMath.cornerAt(92, 70, 100, 80, 64));
    }

    @Test public void firstMoveCannotJumpAnExistingOffGridRectangle() {
        LauncherPanelResizeMath.Rect offGrid =
                new LauncherPanelResizeMath.Rect(103, 87, 507, 383);
        for (LauncherPanelResizeMath.Corner corner : new LauncherPanelResizeMath.Corner[]{
                LauncherPanelResizeMath.Corner.TOP_LEFT,
                LauncherPanelResizeMath.Corner.TOP_RIGHT,
                LauncherPanelResizeMath.Corner.BOTTOM_LEFT,
                LauncherPanelResizeMath.Corner.BOTTOM_RIGHT}) {
            assertRect(offGrid, LauncherPanelResizeMath.resize(corner, offGrid,
                    0, 0, 1_000, 800, 160, 96, 20));
        }
    }

    @Test public void bottomRightMovesOnlyBottomRightAndSnapsGestureDelta() {
        LauncherPanelResizeMath.Rect result = resize(
                LauncherPanelResizeMath.Corner.BOTTOM_RIGHT, 33, 47);
        assertRect(100, 80, 540, 420, result);
    }

    @Test public void topLeftKeepsBottomRightFixed() {
        LauncherPanelResizeMath.Rect result = resize(
                LauncherPanelResizeMath.Corner.TOP_LEFT, -37, -21);
        assertRect(60, 60, 500, 380, result);
    }

    @Test public void topRightKeepsBottomLeftFixed() {
        LauncherPanelResizeMath.Rect result = resize(
                LauncherPanelResizeMath.Corner.TOP_RIGHT, 39, -43);
        assertRect(100, 40, 540, 380, result);
    }

    @Test public void bottomLeftKeepsTopRightFixed() {
        LauncherPanelResizeMath.Rect result = resize(
                LauncherPanelResizeMath.Corner.BOTTOM_LEFT, -43, 39);
        assertRect(60, 80, 500, 420, result);
    }

    @Test public void everyCornerHonorsMinimumsAndParentBounds() {
        assertRect(340, 284, 500, 380, resize(
                LauncherPanelResizeMath.Corner.TOP_LEFT, 9_000, 9_000));
        assertRect(100, 284, 260, 380, resize(
                LauncherPanelResizeMath.Corner.TOP_RIGHT, -9_000, 9_000));
        assertRect(340, 80, 500, 176, resize(
                LauncherPanelResizeMath.Corner.BOTTOM_LEFT, 9_000, -9_000));
        assertRect(100, 80, 260, 176, resize(
                LauncherPanelResizeMath.Corner.BOTTOM_RIGHT, -9_000, -9_000));

        assertRect(0, 0, 500, 380, resize(
                LauncherPanelResizeMath.Corner.TOP_LEFT, -9_000, -9_000));
        assertRect(100, 0, 1_000, 380, resize(
                LauncherPanelResizeMath.Corner.TOP_RIGHT, 9_000, -9_000));
        assertRect(0, 80, 500, 800, resize(
                LauncherPanelResizeMath.Corner.BOTTOM_LEFT, -9_000, 9_000));
        assertRect(100, 80, 1_000, 800, resize(
                LauncherPanelResizeMath.Corner.BOTTOM_RIGHT, 9_000, 9_000));
    }

    @Test public void homeEditorWiresFourVisibleHandlesAndPersistsFinalRectangle()
            throws IOException {
        String source = source("dezz/status/widget/launcher/LauncherElementFrame.java");
        assertTrue(source.contains(
                "addResizeHandle(LauncherPanelResizeMath.Corner.TOP_LEFT"));
        assertTrue(source.contains(
                "addResizeHandle(LauncherPanelResizeMath.Corner.TOP_RIGHT"));
        assertTrue(source.contains(
                "addResizeHandle(LauncherPanelResizeMath.Corner.BOTTOM_LEFT"));
        assertTrue(source.contains(
                "addResizeHandle(LauncherPanelResizeMath.Corner.BOTTOM_RIGHT"));
        assertTrue(source.contains("LauncherPanelResizeMath.cornerAt("));
        assertTrue(source.contains("LauncherPanelResizeMath.resize("));
        assertTrue(source.contains(
                "handle.setVisibility(enabled ? VISIBLE : GONE)"));
        assertTrue(source.contains("listener.onGeometryChanged(elementId, lp.leftMargin"));
    }

    private static LauncherPanelResizeMath.Rect resize(
            LauncherPanelResizeMath.Corner corner, int dx, int dy) {
        return LauncherPanelResizeMath.resize(corner, START, dx, dy,
                1_000, 800, 160, 96, 20);
    }

    private static void assertRect(LauncherPanelResizeMath.Rect expected,
                                   LauncherPanelResizeMath.Rect actual) {
        assertRect(expected.left, expected.top, expected.right, expected.bottom, actual);
    }

    private static void assertRect(int left, int top, int right, int bottom,
                                   LauncherPanelResizeMath.Rect actual) {
        assertEquals(left, actual.left);
        assertEquals(top, actual.top);
        assertEquals(right, actual.right);
        assertEquals(bottom, actual.bottom);
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
