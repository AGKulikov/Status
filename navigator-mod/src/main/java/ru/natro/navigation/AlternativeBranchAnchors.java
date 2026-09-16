/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Geographic anchors on the first distinct alternative branch, never on the shared road. */
final class AlternativeBranchAnchors {
    static final double PREFERRED_METERS = 300d;
    private static final double EARTH_METERS = 6_371_000d;
    private static final double SAMPLE_METERS = 5d;
    private static final double SHARED_ROAD_METERS = 6d;

    static final class Anchor {
        final double latitude, longitude, meters;
        Anchor(double latitude, double longitude, double meters) {
            this.latitude = latitude; this.longitude = longitude; this.meters = meters;
        }
    }

    private AlternativeBranchAnchors() {}

    static List<Anchor> candidates(double[][] alternative, int fork, double fraction,
                                   double[][] current, int currentFork, double currentFraction) {
        if (!validPosition(alternative, fork, fraction)
                || !validPosition(current, currentFork, currentFraction)) return Collections.emptyList();
        double[] origin = interpolate(alternative[fork], alternative[fork + 1], fraction);
        double[] currentOrigin = interpolate(current[currentFork], current[currentFork + 1], currentFraction);
        if (distance(origin, currentOrigin) > 20d) return Collections.emptyList();
        List<double[]> branch = new ArrayList<>();
        branch.add(origin);
        double total = 0d;
        for (int i = fork + 1; i < alternative.length && total < PREFERRED_METERS + 20d; i++) {
            if (!valid(alternative[i])) return Collections.emptyList();
            total += distance(branch.get(branch.size() - 1), alternative[i]);
            branch.add(alternative[i]);
        }
        if (branch.size() < 2 || total < 10d) return Collections.emptyList();
        // Only nearby active-route segments can overlap the first 320 m of this branch.
        // The spatial filter keeps distance tests bounded even for a long intercity route.
        List<double[]> activeSegments = new ArrayList<>();
        double scaleX = Math.cos(Math.toRadians(origin[0])) * EARTH_METERS * Math.PI / 180d;
        double scaleY = EARTH_METERS * Math.PI / 180d;
        for (int i = currentFork; i < current.length - 1; i++) {
            if (!valid(current[i]) || !valid(current[i + 1])) return Collections.emptyList();
            double[] first = i == currentFork ? currentOrigin : current[i];
            double x1 = longitudeDelta(origin[1], first[1]) * scaleX;
            double y1 = (first[0] - origin[0]) * scaleY;
            double x2 = longitudeDelta(origin[1], current[i + 1][1]) * scaleX;
            double y2 = (current[i + 1][0] - origin[0]) * scaleY;
            if (Math.min(x1, x2) > 350d || Math.max(x1, x2) < -350d
                    || Math.min(y1, y2) > 350d || Math.max(y1, y2) < -350d) continue;
            activeSegments.add(new double[]{x1, y1, x2, y2});
        }
        if (activeSegments.isEmpty()) return Collections.emptyList();
        boolean diverged = false;
        double end = Math.min(PREFERRED_METERS + 20d, total);
        for (double meters = SAMPLE_METERS; meters <= end; meters += SAMPLE_METERS) {
            double[] point = at(branch, meters);
            double separation = separation(point, origin, scaleX, scaleY, activeSegments);
            if (separation > SHARED_ROAD_METERS * 2d) diverged = true;
            else if (diverged && separation <= SHARED_ROAD_METERS) {
                end = meters - SAMPLE_METERS; // stop before the first rejoin/crossing
                break;
            }
        }
        if (!diverged) return Collections.emptyList();
        double preferred = Math.min(PREFERRED_METERS, Math.max(0d, end - 15d));
        List<Anchor> result = new ArrayList<>();
        for (double ratio : new double[]{1d, .8d, .6d, .4d, .2d}) {
            double meters = preferred * ratio;
            if (meters < 5d) continue;
            double[] point = at(branch, meters);
            if (separation(point, origin, scaleX, scaleY, activeSegments) <= SHARED_ROAD_METERS) continue;
            result.add(new Anchor(point[0], point[1], meters));
        }
        return result;
    }

    private static double separation(double[] point, double[] origin, double scaleX, double scaleY,
                                      List<double[]> segments) {
        double x = longitudeDelta(origin[1], point[1]) * scaleX;
        double y = (point[0] - origin[0]) * scaleY;
        double best = Double.POSITIVE_INFINITY;
        for (double[] s : segments) {
            double dx = s[2] - s[0], dy = s[3] - s[1], length = dx * dx + dy * dy;
            double t = length == 0d ? 0d : Math.max(0d, Math.min(1d,
                    ((x - s[0]) * dx + (y - s[1]) * dy) / length));
            double px = x - s[0] - t * dx, py = y - s[1] - t * dy;
            best = Math.min(best, px * px + py * py);
        }
        return Math.sqrt(best);
    }

    private static double[] at(List<double[]> points, double meters) {
        double remaining = meters;
        for (int i = 1; i < points.size(); i++) {
            double length = distance(points.get(i - 1), points.get(i));
            if (length > 0d && remaining <= length) return interpolate(points.get(i - 1), points.get(i), remaining / length);
            remaining -= length;
        }
        return points.get(points.size() - 1);
    }

    static double distance(double[] a, double[] b) {
        double lat = Math.toRadians(b[0] - a[0]), lon = Math.toRadians(longitudeDelta(a[1], b[1]));
        double h = Math.sin(lat / 2d) * Math.sin(lat / 2d)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(lon / 2d) * Math.sin(lon / 2d);
        return 2d * EARTH_METERS * Math.asin(Math.sqrt(Math.min(1d, Math.max(0d, h))));
    }

    private static double[] interpolate(double[] a, double[] b, double fraction) {
        // Spherical interpolation avoids turning a sparse long segment into a straight lat/lon
        // chord; also preserves the correct branch across the antimeridian.
        double angle = distance(a, b) / EARTH_METERS;
        if (angle < 1e-12d) return new double[]{a[0], a[1]};
        double wa = Math.sin((1d - fraction) * angle) / Math.sin(angle);
        double wb = Math.sin(fraction * angle) / Math.sin(angle);
        double la = Math.toRadians(a[0]), lb = Math.toRadians(b[0]);
        double oa = Math.toRadians(a[1]), ob = Math.toRadians(b[1]);
        double x = wa * Math.cos(la) * Math.cos(oa) + wb * Math.cos(lb) * Math.cos(ob);
        double y = wa * Math.cos(la) * Math.sin(oa) + wb * Math.cos(lb) * Math.sin(ob);
        double z = wa * Math.sin(la) + wb * Math.sin(lb);
        return new double[]{Math.toDegrees(Math.atan2(z, Math.hypot(x, y))), Math.toDegrees(Math.atan2(y, x))};
    }

    private static double longitudeDelta(double from, double to) {
        return (to - from + 540d) % 360d - 180d;
    }
    private static boolean validPosition(double[][] points, int index, double fraction) {
        return points != null && index >= 0 && index + 1 < points.length
                && Double.isFinite(fraction) && fraction >= 0d && fraction <= 1d
                && valid(points[index]) && valid(points[index + 1]);
    }
    private static boolean valid(double[] point) {
        return point != null && point.length >= 2 && Double.isFinite(point[0])
                && Double.isFinite(point[1]) && Math.abs(point[0]) <= 90d && Math.abs(point[1]) <= 180d;
    }
}
