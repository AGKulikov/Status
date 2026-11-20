/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ru.natro.navigation.NavigatorStatePublisher.RouteTurnFrame;

/** Reads bounded crops of the original map surface. Pixels never enter IPC, disk or logs. */
final class PrimaryTurnObserver {
    private static final int MAX_PIXELS = 512 * 1024;
    private static final long INTERVAL_MS = 320;
    private static final long CAPTURE_MS = 900;
    private static final int MAX_CAPTURE_MOTION = 24;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService PIXELS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NatroTurnArtwork");
        thread.setDaemon(true);
        return thread;
    });

    private final Runnable refresh;
    // Only touched on the pixel worker. Texture identity changes with the stock artwork.
    private final WeakHashMap<StockTurnTextures.Texture, StockTurnPixelMatcher.Pattern> patterns =
            new WeakHashMap<>();
    private View primaryView;
    private Object window;
    private long epoch = -1, generation, lastAttempt, lastLog;
    private boolean pending;
    private int nativeRequests;
    private PrimaryTurnSample latest;
    private int consecutiveMisses;
    private ObservedScene scene;
    private List<RouteTurnFrame> currentTurns = Collections.emptyList();
    private String lastReason = "";
    private Runnable timeout;
    private SurfaceHolder watchedHolder;
    private long bufferGeneration;
    private final SurfaceHolder.Callback surfaceChanges = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) { bufferGeneration++; }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { bufferGeneration++; }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { bufferGeneration++; }
    };

    private void watchHolder(SurfaceHolder next) {
        if (next == watchedHolder) return;
        if (watchedHolder != null) watchedHolder.removeCallback(surfaceChanges);
        watchedHolder = next;
        bufferGeneration++;
        if (next != null) next.addCallback(surfaceChanges);
    }

    PrimaryTurnObserver(Runnable refresh) { this.refresh = refresh; }

    void clear() {
        generation++;
        cancelTimeout();
        discard("route_or_window_reset");
        pending = false;
        scene = null;
        primaryView = null;
        window = null;
        epoch = -1;
        currentTurns = Collections.emptyList();
        watchHolder(null);
        MAIN.removeCallbacks(refresh);
    }

    PrimaryTurnSample observe(View view, Object mapWindow, long routeEpoch,
                              List<RouteTurnFrame> turns, NavigatorStatePublisher.LaneGuidanceFrame lane,
                              NavigatorStatePublisher.NavigationFrame frame) {
        if (view == null || mapWindow == null || !frame.routeActive) {
            if (window != null) clear();
            return null;
        }
        if (view != primaryView || mapWindow != window || epoch != routeEpoch) {
            clear();
            primaryView = view;
            window = mapWindow;
            epoch = routeEpoch;
            lastAttempt = 0;
        }
        long now = SystemClock.elapsedRealtime();
        currentTurns = candidates(turns, lane, frame);
        if (latest != null && now - latest.sampledAt >= PrimaryTurnSample.FRESH_MS)
            discard("proof_age");
        if (latest != null && !bindingStillCurrent()) discard("binding_changed");
        if (currentTurns.isEmpty() || !view.isShown() || !view.isAttachedToWindow() || secure(view)) {
            discard(currentTurns.isEmpty() ? "no_upcoming_turn" : "primary_not_visible");
            if (pending) { generation++; pending = false; cancelTimeout(); }
            MAIN.removeCallbacks(refresh);
            return null;
        }
        if (!pending && nativeRequests < 2 && now - lastAttempt >= INTERVAL_MS) {
            lastAttempt = now;
            try {
                start(now);
            } catch (Exception | OutOfMemoryError unavailable) {
                reason("map capture unavailable");
            }
        }
        return latest;
    }

    private static boolean secure(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity)
                return ((((Activity) context).getWindow().getAttributes().flags &
                        WindowManager.LayoutParams.FLAG_SECURE) != 0);
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return false;
    }

    private static List<RouteTurnFrame> candidates(List<RouteTurnFrame> turns,
            NavigatorStatePublisher.LaneGuidanceFrame lane, NavigatorStatePublisher.NavigationFrame frame) {
        ArrayList<RouteTurnFrame> result = new ArrayList<>();
        for (RouteTurnFrame turn : turns) if (upcoming(turn, frame)) result.add(turn);
        if (lane != null && lane.hasMapPosition() && lane.routeSegmentIndex >= 0) {
            RouteTurnFrame site = new RouteTurnFrame(lane.id, "LANE_SITE", lane.latitude, lane.longitude,
                    lane.bearingDegrees, lane.routeSegmentIndex, lane.routeSegmentPosition);
            if (upcoming(site, frame) && !contains(result, site)) result.add(site);
        }
        return result;
    }

    private static boolean upcoming(RouteTurnFrame turn, NavigatorStatePublisher.NavigationFrame frame) {
        return turn.hasContent() && (!frame.routeProgressValid ||
                turn.routeSegmentIndex + turn.routeSegmentPosition >= frame.routeSegmentIndex + frame.routeSegmentPosition);
    }

    private static boolean samePosition(RouteTurnFrame a, RouteTurnFrame b) {
        return Math.abs(a.routeSegmentIndex + a.routeSegmentPosition -
                (b.routeSegmentIndex + b.routeSegmentPosition)) < 0.000001
                && Math.abs(a.latitude - b.latitude) < 0.000001
                && Math.abs(a.longitude - b.longitude) < 0.000001;
    }

    private static boolean contains(List<RouteTurnFrame> turns, RouteTurnFrame target) {
        for (RouteTurnFrame turn : turns) if (samePosition(turn, target)) return true;
        return false;
    }

    private static final class Trial {
        final RouteTurnFrame turn;
        final StockTurnTextures.Texture texture;
        final PointF point;
        final Rect bounds;
        int dx, dy;
        Trial(RouteTurnFrame turn, StockTurnTextures.Texture texture, PointF point) {
            this.turn = turn;
            this.texture = texture;
            this.point = point;
            int width = texture.bitmap.getWidth(), height = texture.bitmap.getHeight();
            int left = Math.round(point.x - texture.anchorX * width);
            int top = Math.round(point.y - texture.anchorY * height);
            bounds = new Rect(left, top, left + width, top + height);
        }
    }

    private static PointF project(Object mapWindow, RouteTurnFrame turn) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.yandex.mapkit.geometry.Point");
        Object geo = type.getConstructor(double.class, double.class).newInstance(turn.latitude, turn.longitude);
        Method project = ReflectMethods.publicMethod(mapWindow.getClass(), "worldToScreen", new Class<?>[]{type});
        Object point = project.invoke(mapWindow, geo);
        if (point == null) return null;
        float x = ((Number) get(point, "getX")).floatValue(), y = ((Number) get(point, "getY")).floatValue();
        return Float.isFinite(x) && Float.isFinite(y) ? new PointF(x, y) : null;
    }

    private static Object get(Object obj, String name) throws ReflectiveOperationException {
        return ReflectMethods.publicMethod(obj.getClass(), name, new Class<?>[0]).invoke(obj);
    }

    // The signed MapView constructor adds exactly platformView.getView() as a direct child.
    // Its movable GL implementation returns its inner TextureView, the other backends return themselves.
    private static View mapSurface(View mapView) {
        if (!(mapView instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) mapView;
        View result = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getName();
            boolean stockTexture = child instanceof TextureView
                    && name.equals("com.yandex.runtime.view.PlatformGLTextureView$TextureViewImpl");
            boolean stockSurface = child instanceof SurfaceView
                    && (name.equals("com.yandex.runtime.view.PlatformGLSurfaceView")
                    || name.equals("com.yandex.runtime.view.PlatformGLSurfaceViewBelowApi34Workaround")
                    || name.equals("com.yandex.runtime.view.PlatformVulkanSurfaceView"));
            if (!stockTexture && !stockSurface) continue;
            if (result != null || !child.isShown()) return null;
            result = child;
        }
        return result;
    }

    private static final class ObservedScene {
        final int width, height;
        final View source;
        Object backing;
        long bufferGeneration;
        boolean projectionVerified = true;
        boolean failed, ambiguous, revisionsChanged;
        final List<StockTurnTextures.Texture> textures;
        final List<RouteTurnFrame> turns;
        final ArrayList<PointF> points = new ArrayList<>();

        ObservedScene(View source, int width, int height, List<StockTurnTextures.Texture> textures, List<RouteTurnFrame> turns) {
            this.source = source;
            this.width = width;
            this.height = height;
            this.textures = textures;
            this.turns = turns;
        }
    }

    private static boolean inViewport(PointF point, int width, int height) {
        return point != null && point.x >= 0 && point.y >= 0 && point.x < width && point.y < height;
    }

    // Any alternative newly visible in the camera or the source factory invalidates the
    // sample, even when the winning turn and its own texture are still unchanged.
    private boolean bindingStillCurrent() { return sceneStillCurrent(scene, false, true); }

    private boolean sceneStillCurrent(ObservedScene scene, boolean validateCaptureProjection, boolean allowRevision) {
        if (scene == null || scene.turns.size() != currentTurns.size()
                || !sameSources(scene.textures, StockTurnTextures.forWindow(window), allowRevision)) return false;
        try {
            View source = mapSurface(primaryView);
            if (source != scene.source || source.getWidth() != scene.width || source.getHeight() != scene.height
                    || scene.bufferGeneration != bufferGeneration) return false;
            if (source instanceof SurfaceView) {
                SurfaceHolder holder = ((SurfaceView)source).getHolder();
                if (holder != watchedHolder || holder.getSurface() != scene.backing || !holder.getSurface().isValid()
                        || holder.getSurfaceFrame().width() != scene.width
                        || holder.getSurfaceFrame().height() != scene.height) return false;
            } else {
                TextureView view = (TextureView)source;
                if (!view.isAvailable() || view.getSurfaceTexture() != scene.backing) return false;
            }
            if (((Number) get(window, "width")).intValue() != scene.width
                    || ((Number) get(window, "height")).intValue() != scene.height) return false;
            for (int i = 0; i < currentTurns.size(); i++) {
                RouteTurnFrame current = currentTurns.get(i);
                RouteTurnFrame previous = scene.turns.get(i);
                if (!samePosition(current, previous) || !current.action.equals(previous.action)) return false;
                PointF before = scene.points.get(i), after = project(window, current);
                boolean visible = inViewport(after, scene.width, scene.height);
                if (visible != inViewport(before, scene.width, scene.height)) return false;
                if (validateCaptureProjection && visible && (Math.abs(before.x - after.x) > MAX_CAPTURE_MOTION
                        || Math.abs(before.y - after.y) > MAX_CAPTURE_MOTION)) return false;
            }
            return true;
        } catch (Exception changed) {
            return false;
        }
    }

    private static boolean sameSources(List<StockTurnTextures.Texture> before,
            List<StockTurnTextures.Texture> after, boolean allowRevision) {
        if (!allowRevision) return before.equals(after);
        if (before.size() != after.size()) return false;
        for (StockTurnTextures.Texture old : before) {
            boolean found = false;
            for (StockTurnTextures.Texture next : after)
                if (old.source == next.source && old.combined == next.combined) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    private static boolean sameSource(StockTurnTextures.Texture a, StockTurnTextures.Texture b) {
        return a.source == b.source && a.combined == b.combined;
    }

    private static boolean sameMatch(Trial a, Trial b) {
        return samePosition(a.turn, b.turn) && sameSource(a.texture, b.texture);
    }

    private static final class Tile {
        final Rect crop = new Rect();
        final ArrayList<Trial> trials = new ArrayList<>();
        Trial match;
        boolean ambiguous;
    }

    private static long area(Rect r) { return (long) r.width() * r.height(); }

    // Adjacent candidates share a readback. Distant turns use separate small reads, and
    // all eligible candidates participate or the entire sample is discarded.
    private static boolean addTrial(ArrayList<Tile> tiles, Rect viewport, Trial trial) {
        Rect box = new Rect(trial.bounds);
        box.inset(-MAX_CAPTURE_MOTION - 2, -MAX_CAPTURE_MOTION - 2);
        box.intersect(viewport);
        long total = 0, smallestExtra = Long.MAX_VALUE;
        Tile best = null;
        for (Tile tile : tiles) {
            Rect union = new Rect(tile.crop);
            union.union(box);
            long extra = area(union) - area(tile.crop);
            if (extra < smallestExtra) { best = tile; smallestExtra = extra; }
            total += area(tile.crop);
        }
        if (best == null || (smallestExtra > area(box) && tiles.size() < 4)) {
            smallestExtra = area(box);
            best = new Tile();
            tiles.add(best);
        }
        if (total + smallestExtra > MAX_PIXELS) return false;
        best.crop.union(box);
        best.trials.add(trial);
        return true;
    }

    private void start(long capturedAt) throws Exception {
        List<StockTurnTextures.Texture> textures = StockTurnTextures.forWindow(window);
        if (textures.isEmpty()) { discard("no_stock_texture"); reason("awaiting stock texture"); return; }
        ArrayList<StockTurnTextures.Texture> renderTextures = new ArrayList<>(textures);
        // createTexture precedes the GPU swap. Re-check the last *observed* image
        // alongside the new revision, only while the same native source is alive.
        // This is a new pixel measurement, never an extension of an old timestamp.
        if (latest != null && !textures.contains(latest.texture) && !latest.texture.bitmap.isRecycled()) {
            for (StockTurnTextures.Texture texture : textures) {
                if (sameSource(texture, latest.texture)) {
                    renderTextures.add(latest.texture);
                    break;
                }
            }
        }
        int width = ((Number) get(window, "width")).intValue();
        int height = ((Number) get(window, "height")).intValue();
        View source = mapSurface(primaryView);
        if (source == null) { discard("map_surface_unavailable"); reason("map surface unavailable"); return; }
        if (width <= 0 || height <= 0 || source.getWidth() != width || source.getHeight() != height) {
            discard("map_size_changed"); reason("map size changed"); return;
        }
        Rect viewport = new Rect(0, 0, width, height);
        ArrayList<Tile> tiles = new ArrayList<>();
        int count = 0;
        ObservedScene next = new ObservedScene(source, width, height, textures, currentTurns);
        for (RouteTurnFrame turn : currentTurns) {
            PointF point = project(window, turn);
            next.points.add(point);
            if (!inViewport(point, width, height)) continue;
            for (StockTurnTextures.Texture texture : renderTextures) {
                if ((!texture.combined && turn.action.equals("LANE_SITE")) || texture.bitmap.isRecycled())
                    continue;
                Trial trial = new Trial(turn, texture, point);
                if (!viewport.contains(trial.bounds)) continue;
                if (++count > 64 || !addTrial(tiles, viewport, trial)) {
                    discard("capture_budget"); reason("turn sample exceeded budget"); return;
                }
            }
        }
        if (tiles.isEmpty()) { discard("no_on_screen_source"); reason("no on-screen source"); return; }
        Surface surface;
        boolean owned = false;
        if (source instanceof SurfaceView) {
            SurfaceHolder holder = ((SurfaceView)source).getHolder();
            watchHolder(holder);
            if (holder.getSurfaceFrame().width() != width || holder.getSurfaceFrame().height() != height) {
                discard("surface_size_changed"); return;
            }
            surface = holder.getSurface();
            next.backing = surface;
        } else {
            watchHolder(null);
            TextureView texture = (TextureView) source;
            SurfaceTexture backing = texture.getSurfaceTexture();
            if (!texture.isAvailable() || backing == null) { discard("surface_unavailable"); return; }
            surface = new Surface(backing);
            next.backing = backing;
            owned = true;
        }
        if (!surface.isValid()) {
            if (owned) surface.release();
            discard("surface_invalid");
            return;
        }
        next.bufferGeneration = bufferGeneration;
        // While this read is in flight a cached result remains tied to its own camera.
        long ticket = ++generation;
        pending = true;
        final boolean releaseSurface = owned;
        timeout = () -> {
            if (ticket != generation) return;
            generation++;
            pending = false;
            timeout = null;
            reason("turn sample expired");
            MAIN.post(refresh);
        };
        MAIN.postDelayed(timeout, CAPTURE_MS);
        captureTile(surface, releaseSurface, tiles, 0, ticket, capturedAt, next);
    }

    private void captureTile(Surface surface, boolean owned, List<Tile> tiles, int index,
                             long ticket, long capturedAt, ObservedScene captured) {
        Tile tile = tiles.get(index);
        Rect crop = tile.crop;
        Bitmap target = null;
        try {
            target = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888);
            Bitmap copy = target;
            nativeRequests++;
            PixelCopy.request(surface, crop, copy, status -> {
                if (generation == ticket) {
                    captured.projectionVerified &= sceneStillCurrent(captured, true, true);
                    captured.revisionsChanged |= !sameSources(captured.textures, StockTurnTextures.forWindow(window), false);
                }
                if (status != PixelCopy.SUCCESS || generation != ticket) {
                    captured.failed = true;
                    nativeRequests--;
                    copy.recycle();
                    if (owned) surface.release();
                    finish(ticket, capturedAt, null, captured);
                    return;
                }
                try {
                    for (Trial trial : tile.trials) {
                        PointF after = project(window, trial.turn);
                        if (after == null) { captured.projectionVerified = false; break; }
                        trial.dx = Math.round(after.x) - Math.round(trial.point.x);
                        trial.dy = Math.round(after.y) - Math.round(trial.point.y);
                        if (Math.abs(trial.dx) > MAX_CAPTURE_MOTION || Math.abs(trial.dy) > MAX_CAPTURE_MOTION)
                            captured.projectionVerified = false;
                    }
                } catch (Exception moved) { captured.projectionVerified = false; }
                PIXELS.execute(() -> {
                    boolean usable = false;
                    try { usable = match(copy, tile); }
                    catch (RuntimeException | OutOfMemoryError ignored) { /* discard sample */ }
                    finally { copy.recycle(); }
                    final boolean valid = usable;
                    MAIN.post(() -> {
                        nativeRequests--;
                        captured.ambiguous |= tile.ambiguous;
                        captured.failed |= !valid && !tile.ambiguous;
                        if (valid && generation == ticket && index + 1 < tiles.size()) {
                            captureTile(surface, owned, tiles, index + 1, ticket, capturedAt, captured);
                            return;
                        }
                        if (owned) surface.release();
                        Trial hit = null;
                        if (valid && generation == ticket) for (Tile done : tiles) {
                            if (done.match == null) continue;
                            if (hit != null && !sameMatch(hit, done.match)) {
                                hit = null; captured.ambiguous = true; break;
                            }
                            if (hit == null || done.match.texture.revision > hit.texture.revision) hit = done.match;
                        }
                        finish(ticket, capturedAt, hit, captured);
                    });
                });
            }, MAIN);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            captured.failed = true;
            if (target != null) { target.recycle(); nativeRequests--; }
            if (owned) surface.release();
            finish(ticket, capturedAt, null, captured);
        }
    }

    private void cancelTimeout() {
        if (timeout != null) MAIN.removeCallbacks(timeout);
        timeout = null;
    }

    private boolean match(Bitmap bitmap, Tile tile) {
        Rect crop = tile.crop;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w*h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        Trial hit = null;
        for (Trial t : tile.trials) {
            StockTurnPixelMatcher.Pattern pattern = patterns.get(t.texture);
            if (pattern == null && !patterns.containsKey(t.texture)) {
                Bitmap original = t.texture.bitmap;
                int tw = original.getWidth(), th = original.getHeight();
                if ((long)tw * th > MAX_PIXELS) continue;
                int[] rgba = new int[tw*th];
                original.getPixels(rgba, 0, tw, 0, 0, tw, th);
                pattern = StockTurnPixelMatcher.prepare(rgba, tw, th);
                patterns.put(t.texture, pattern);
            }
            if (pattern == null || !matchesMotion(pattern, pixels, w, h, t, crop)) continue;
            if (hit != null && !sameMatch(hit, t)) {
                tile.ambiguous = true;
                return false;
            }
            if (hit == null || t.texture.revision > hit.texture.revision) hit = t;
        }
        tile.match = hit;
        return true;
    }

    private static boolean matchesMotion(StockTurnPixelMatcher.Pattern pattern, int[] pixels,
            int w, int h, Trial trial, Rect crop) {
        if (Math.abs(trial.dx) > MAX_CAPTURE_MOTION || Math.abs(trial.dy) > MAX_CAPTURE_MOTION) return false;
        int minX = Math.min(0, trial.dx), maxX = Math.max(0, trial.dx);
        int minY = Math.min(0, trial.dy), maxY = Math.max(0, trial.dy);
        // PixelCopy can sample anywhere between request and callback. Search only
        // the measured sweep, at native resolution; test every competing route point.
        // The matcher's +/-2 tolerance covers each four-pixel step without gaps.
        for (int y = minY; ; y = Math.min(y + 4, maxY)) {
            for (int x = minX; ; x = Math.min(x + 4, maxX)) {
                if (StockTurnPixelMatcher.matches(pattern, pixels, w, h,
                        trial.bounds.left - crop.left + x, trial.bounds.top - crop.top + y)) return true;
                if (x == maxX) break;
            }
            if (y == maxY) break;
        }
        return false;
    }

    private void finish(long ticket, long at, Trial hit, ObservedScene captured) {
        if (ticket != generation) return;
        pending = false;
        cancelTimeout();
        // Screen coordinates matter while identifying a new bitmap. A confirmed sample
        // already has a route position and can move on screen until its original expiry.
        boolean captureCurrent = captured.projectionVerified && sceneStillCurrent(captured, false, true);
        captured.revisionsChanged |= !sameSources(captured.textures, StockTurnTextures.forWindow(window), false);
        boolean retainDuringTransition = latest != null
                && SystemClock.elapsedRealtime() - latest.sampledAt < PrimaryTurnSample.FRESH_MS
                && primaryView.isShown() && primaryView.isAttachedToWindow() && !secure(primaryView)
                && bindingStillCurrent();
        if (!retainDuringTransition) discard("confirmation_no_longer_current");
        if (captureCurrent && captured.ambiguous) discard("ambiguous_pixels");
        if (hit != null && !captured.failed && !captured.ambiguous && SystemClock.elapsedRealtime() - at <= CAPTURE_MS
                && primaryView.isShown() && primaryView.isAttachedToWindow() && !secure(primaryView)
                && captureCurrent) {
            scene = captured;
            if (latest == null) transition("shown; revision=" + hit.texture.revision);
            latest = new PrimaryTurnSample(hit.turn, hit.texture, at, epoch);
            consecutiveMisses = 0;
        } else if (latest != null && captureCurrent && !captured.failed && !captured.ambiguous
                && !captured.revisionsChanged
                && SystemClock.elapsedRealtime() - at <= CAPTURE_MS && ++consecutiveMisses >= 2) {
            discard("two_confirmed_absences");
        }
        if (latest == null) scene = null;
        reason(latest == null ? "no unique visible turn" : latest.sampledAt == at
                ? "native turn confirmed" : "previous confirmation retained");
        MAIN.removeCallbacks(refresh);
        MAIN.post(refresh);
        MAIN.postDelayed(refresh, INTERVAL_MS + 5);
    }

    private void discard(String cause) {
        if (latest != null) transition("hidden; cause=" + cause
                + "; ageMs=" + (SystemClock.elapsedRealtime() - latest.sampledAt)
                + "; revision=" + latest.texture.revision + "; misses=" + consecutiveMisses);
        latest = null;
        scene = null;
        consecutiveMisses = 0;
    }

    private static void transition(String detail) {
        Log.d("NatroTurnMap", detail);
        NavigationBridgeClient.reportDiagnostic("turn-balloon " + detail);
    }

    private void reason(String text) {
        long now = SystemClock.elapsedRealtime();
        if (!text.equals(lastReason) && now-lastLog >= 2000) {
            Log.d("NatroTurnMap", text);
            lastReason = text;
            lastLog = now;
        }
    }
}
