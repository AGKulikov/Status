/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.RectF;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses a free screen-facing side for Natro map balloons that share one MapWindow.
 *
 * <p>MapKit collision modes can hide a conflicting placemark, but child collections cannot ask
 * one another to move the complete card to another side of its geographical point. This small
 * screen-space pass keeps lane guidance and traffic-light balloons clear of one another and of
 * fixed camera signs. Camera signs reserve their centred footprint but never move away from the
 * exact map coordinate. The selected {@link Placement#legName} is also the stock Yandex balloon
 * leg that points back to that coordinate.</p>
 */
final class MapOverlayPlacementCoordinator {
    static final String OWNER_LANES = "lanes";
    static final String OWNER_TRAFFIC_LIGHTS = "traffic_lights";
    static final String OWNER_CAMERAS = "cameras";

    private static final float VIEWPORT_MARGIN_PX = 6f;
    private static final float ITEM_MARGIN_PX = 8f;
    private static final float OUT_OF_BOUNDS_WEIGHT = 50_000f;
    private static final float OVERLAP_WEIGHT = 200f;

    private final ArrayList<Reservation> reservations = new ArrayList<>();
    private Object mapWindow;
    private int viewportWidth;
    private int viewportHeight;

    void attach(Object nextMapWindow, int width, int height) {
        mapWindow = nextMapWindow;
        viewportWidth = Math.max(1, width);
        viewportHeight = Math.max(1, height);
        reservations.clear();
    }

    void detach() {
        reservations.clear();
        mapWindow = null;
        viewportWidth = 0;
        viewportHeight = 0;
    }

    /** Starts one deterministic high-to-low priority placement pass. */
    void beginLayout() {
        reservations.clear();
    }

    void clearOwner(String owner) {
        for (int index = reservations.size() - 1; index >= 0; index--) {
            if (owner.equals(reservations.get(index).owner)) reservations.remove(index);
        }
    }

    Placement reserve(String owner, String key, double latitude, double longitude,
                      int bitmapWidth, int bitmapHeight, boolean preferRight) {
        float[] screen = project(latitude, longitude);
        int safeWidth = Math.max(1, bitmapWidth);
        int safeHeight = Math.max(1, bitmapHeight);
        Candidate[] candidates = candidates(preferRight);
        Candidate best = candidates[0];
        RectF bestRect = rect(screen[0], screen[1], safeWidth, safeHeight, best);
        double bestScore = score(bestRect, 0);
        for (int index = 1; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            RectF bounds = rect(screen[0], screen[1], safeWidth, safeHeight, candidate);
            double score = score(bounds, index);
            if (score < bestScore) {
                best = candidate;
                bestRect = bounds;
                bestScore = score;
            }
        }
        RectF occupied = new RectF(bestRect);
        occupied.inset(-ITEM_MARGIN_PX, -ITEM_MARGIN_PX);
        reservations.add(new Reservation(owner, key, occupied));
        return new Placement(best.anchorX, best.anchorY, best.legName);
    }

    /** Reserves an immovable placemark whose visual centre must remain on its map coordinate. */
    Placement reserveCentered(String owner, String key, double latitude, double longitude,
                              int bitmapWidth, int bitmapHeight) {
        float[] screen = project(latitude, longitude);
        int safeWidth = Math.max(1, bitmapWidth);
        int safeHeight = Math.max(1, bitmapHeight);
        Candidate centered = new Candidate(.50f, .50f, "CENTER");
        RectF occupied = rect(screen[0], screen[1], safeWidth, safeHeight, centered);
        occupied.inset(-ITEM_MARGIN_PX, -ITEM_MARGIN_PX);
        reservations.add(new Reservation(owner, key, occupied));
        return new Placement(centered.anchorX, centered.anchorY, centered.legName);
    }

    private float[] project(double latitude, double longitude) {
        Object currentWindow = mapWindow;
        if (currentWindow != null) {
            try {
                Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
                Object point = pointClass.getConstructor(double.class, double.class)
                        .newInstance(latitude, longitude);
                Method projection = ReflectMethods.publicMethod(currentWindow.getClass(),
                        "worldToScreen", new Class<?>[]{pointClass});
                Object screenPoint = projection.invoke(currentWindow, point);
                if (screenPoint != null) {
                    float x = ((Number) ReflectMethods.publicMethod(screenPoint.getClass(),
                            "getX", new Class<?>[0]).invoke(screenPoint)).floatValue();
                    float y = ((Number) ReflectMethods.publicMethod(screenPoint.getClass(),
                            "getY", new Class<?>[0]).invoke(screenPoint)).floatValue();
                    if (Float.isFinite(x) && Float.isFinite(y)) return new float[]{x, y};
                }
            } catch (Throwable ignored) {
                // Stable centre fallback keeps the layer alive on a regional MapKit variant.
            }
        }
        return new float[]{viewportWidth * .5f, viewportHeight * .5f};
    }

    private double score(RectF bounds, int preferenceIndex) {
        float safeLeft = VIEWPORT_MARGIN_PX;
        float safeTop = VIEWPORT_MARGIN_PX;
        float safeRight = Math.max(safeLeft, viewportWidth - VIEWPORT_MARGIN_PX);
        float safeBottom = Math.max(safeTop, viewportHeight - VIEWPORT_MARGIN_PX);
        double outside = Math.max(0f, safeLeft - bounds.left)
                + Math.max(0f, bounds.right - safeRight)
                + Math.max(0f, safeTop - bounds.top)
                + Math.max(0f, bounds.bottom - safeBottom);
        double overlap = 0d;
        for (Reservation reservation : reservations) {
            overlap += overlapArea(bounds, reservation.bounds);
        }
        return outside * OUT_OF_BOUNDS_WEIGHT + overlap * OVERLAP_WEIGHT + preferenceIndex;
    }

    private static double overlapArea(RectF first, RectF second) {
        float width = Math.min(first.right, second.right) - Math.max(first.left, second.left);
        float height = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top);
        return width > 0f && height > 0f ? (double) width * height : 0d;
    }

    private static RectF rect(float x, float y, int width, int height, Candidate candidate) {
        float left = x - candidate.anchorX * width;
        float top = y - candidate.anchorY * height;
        return new RectF(left, top, left + width, top + height);
    }

    /** Side placements are preferred; diagonal and vertical slots resolve crowded junctions. */
    private static Candidate[] candidates(boolean preferRight) {
        Candidate right = new Candidate(-.08f, .50f, "LEFT_CENTER");
        Candidate left = new Candidate(1.08f, .50f, "RIGHT_CENTER");
        Candidate upperRight = new Candidate(-.05f, 1.05f, "BOTTOM_LEFT");
        Candidate upperLeft = new Candidate(1.05f, 1.05f, "BOTTOM_RIGHT");
        Candidate lowerRight = new Candidate(-.05f, -.05f, "TOP_LEFT");
        Candidate lowerLeft = new Candidate(1.05f, -.05f, "TOP_RIGHT");
        Candidate above = new Candidate(.50f, 1.08f, "BOTTOM_CENTER");
        Candidate below = new Candidate(.50f, -.08f, "TOP_CENTER");
        return preferRight
                ? new Candidate[]{right, left, upperRight, upperLeft,
                lowerRight, lowerLeft, above, below}
                : new Candidate[]{left, right, upperLeft, upperRight,
                lowerLeft, lowerRight, above, below};
    }

    static final class Placement {
        final float anchorX;
        final float anchorY;
        final String legName;

        Placement(float anchorX, float anchorY, String legName) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.legName = legName;
        }

        boolean sameSlot(Placement other) {
            return other != null && legName.equals(other.legName);
        }
    }

    private static final class Candidate {
        final float anchorX;
        final float anchorY;
        final String legName;

        Candidate(float anchorX, float anchorY, String legName) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.legName = legName;
        }
    }

    private static final class Reservation {
        final String owner;
        final String key;
        final RectF bounds;

        Reservation(String owner, String key, RectF bounds) {
            this.owner = owner;
            this.key = key;
            this.bounds = bounds;
        }
    }
}
