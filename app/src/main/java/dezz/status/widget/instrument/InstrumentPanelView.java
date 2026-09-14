/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.navigation.NavigationIntegrationConfig;

/**
 * Native-size instrument composition. Navigator renders only into the map element's real pixel
 * rectangle; every other instrument is drawn by one {@link InstrumentClusterView} above it.
 */
public final class InstrumentPanelView extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    private static final long COLD_LEASE_FAST_RETRY_MS = 150L;
    private static final long COLD_LEASE_SLOW_RETRY_MS = 1_000L;
    private static final int COLD_LEASE_FAST_RETRY_COUNT = 40;
    @NonNull private InstrumentPanelConfig config;
    @NonNull private final InstrumentPanelStore panelStore;
    @NonNull private final Preferences navigationPreferences;
    @NonNull private final InstrumentClusterView instruments;
    @NonNull private final View mapView;
    @Nullable private final TextureView mapTexture;
    @Nullable private Surface mapSurface;
    @Nullable private SurfaceTexture ownedTexture;
    @Nullable private Surface publishedSurface;
    private boolean attached;
    private boolean windowVisible;
    private boolean leasePublished;
    private boolean clusterMapEnabled = true;
    private final dezz.status.widget.navigation.MapEdgeFade mapEdgeFade =
            new dezz.status.widget.navigation.MapEdgeFade();
    private final RectF edgeBounds = new RectF();
    private int publishedWidth;
    private int publishedHeight;
    private int coldLeaseRetryCount;
    private long lastGeometryRecovery;
    private long lastWaitLog;
    @NonNull private String lastWaitReason = "";
    /** Wait for a surface update, without depending on complete MapKit tile loading. */
    private float desiredMapAlpha = 1f;
    private boolean awaitingFirstMapFrame = true;
    private long firstFrameWaitStarted;
    @Nullable private String cachedMapProfileRaw;
    @Nullable private NavigationIntegrationConfig.MapProfile cachedMapProfile;
    @NonNull private final Runnable coldLeaseRetry = this::retryColdLease;

    public InstrumentPanelView(@NonNull Context context,
                               @NonNull InstrumentPanelConfig config,
                               boolean editorMode,
                               @Nullable InstrumentClusterView.EditorListener editorListener) {
        super(context);
        setWillNotDraw(false);
        this.config = config;
        panelStore = new InstrumentPanelStore(context);
        navigationPreferences = new Preferences(context);
        if (editorMode) {
            mapTexture = null;
            mapView = new MapPlaceholderView(context);
        } else {
            NavigationIntegrationConfig.MapProfile initialProfile = clusterProfile();
            clusterMapEnabled = initialProfile.enabled;
            TextureView texture = new TextureView(context);
            texture.setSurfaceTextureListener(this);
            texture.setOpaque(!initialProfile.roadsOnly);
            texture.setAlpha(0f);
            texture.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                int height = bottom - top;
                if (width <= 1 || height <= 1) return;
                if (leasePublished && (width != publishedWidth || height != publishedHeight)) {
                    replaceLeaseIfReady();
                    return;
                }
                publishLeaseIfReady();
            });
            mapTexture = texture;
            mapView = texture;
        }
        addView(mapView);
        instruments = new InstrumentClusterView(context, config, editorMode, editorListener);
        addView(instruments, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        updateConfig(config);
    }

    @NonNull
    public InstrumentClusterView instruments() {
        return instruments;
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child != mapView) return super.drawChild(canvas, child, drawingTime);
        InstrumentElementConfig map = firstMap();
        if (map == null) return super.drawChild(canvas, child, drawingTime);
        edgeBounds.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        int save = mapEdgeFade.begin(canvas, edgeBounds,
                map.options.optBoolean("edgeBlurEnabled", false),
                map.options.optInt("edgeBlurSizePx", 24),
                map.options.optInt("edgeBlurStrengthPercent", 100));
        boolean result = super.drawChild(canvas, child, drawingTime);
        mapEdgeFade.finish(canvas, save, edgeBounds);
        return result;
    }

    /** Updates the editor's map locator during a drag without rebuilding telemetry state. */
    public void refreshMapGeometry() {
        InstrumentElementConfig map = firstMap();
        if (map == null || !map.enabled || !clusterMapEnabled) {
            mapView.setVisibility(GONE);
            revokeLease();
            return;
        }
        mapView.setVisibility(VISIBLE);
        mapView.setLayoutParams(mapParams(map));
        desiredMapAlpha = map.opacityPercent / 100f;
        if (mapTexture == null || !awaitingFirstMapFrame) {
            mapView.setAlpha(desiredMapAlpha);
        }
        mapView.invalidate();
    }

    public void updateConfig(@NonNull InstrumentPanelConfig value) {
        config = value;
        config.normalize();
        // The cached instrument canvas owns the configurable black-to-colour gradient. Keeping
        // this backing view black prevents a one-frame flash while a preset or map is re-laid out.
        setBackgroundColor(config.transparentBackground ? Color.TRANSPARENT : Color.BLACK);
        instruments.setConfig(config);
        InstrumentElementConfig map = firstMap();
        NavigationIntegrationConfig.MapProfile profile = clusterProfile();
        instruments.setNavigationProfile(profile);
        clusterMapEnabled = profile.enabled;
        if (map == null || !map.enabled || !profile.enabled) {
            mapView.setVisibility(GONE);
            beginFirstFrameGate();
            revokeLease();
            return;
        }
        mapView.setVisibility(VISIBLE);
        desiredMapAlpha = map.opacityPercent / 100f;
        if (mapTexture == null || !awaitingFirstMapFrame) {
            mapView.setAlpha(desiredMapAlpha);
        }
        LayoutParams params = mapParams(map);
        mapView.setLayoutParams(params);
        if (mapTexture != null) mapTexture.setOpaque(!profile.roadsOnly);
        mapView.requestLayout();
        mapView.invalidate();
        invalidate();
        coldLeaseRetryCount = 0;
        mapView.post(this::publishLeaseIfReady);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        windowVisible = getWindowVisibility() == VISIBLE;
        coldLeaseRetryCount = 0;
        publishLeaseIfReady();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        windowVisible = false;
        removeCallbacks(coldLeaseRetry);
        revokeLease();
        releaseOwnedSurface();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (windowVisible) replaceLeaseIfReady();
        // Window visibility on the KX11 briefly changes for system overlays and DIM transitions.
        // Keep the producer lease until the View/Surface is actually detached or destroyed so a
        // transient visibility callback cannot tear down the OffscreenMapWindow and flash black.
    }

    @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture,
                                                    int width, int height) {
        if (mapTexture == null || mapTexture.getSurfaceTexture() != surfaceTexture) return;
        // Recovery can already have published this exact TextureView surface before Android's
        // delayed callback. Releasing it here would strand a lease pointing at a dead Surface.
        if (ownedTexture == surfaceTexture && mapSurface != null && mapSurface.isValid()) {
            replaceLeaseIfReady();
            return;
        }
        revokeLease();
        beginFirstFrameGate();
        releaseOwnedSurface();
        ownedTexture = surfaceTexture;
        mapSurface = new Surface(surfaceTexture);
        coldLeaseRetryCount = 0;
        publishLeaseIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture,
                                                      int width, int height) {
        if (surfaceTexture != ownedTexture) return;
        if (hasLiveLease() && width == publishedWidth && height == publishedHeight) return;
        replaceLeaseIfReady();
    }

    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        if (ownedTexture != null && surfaceTexture != ownedTexture) return true;
        removeCallbacks(coldLeaseRetry);
        revokeLease();
        releaseOwnedSurface();
        beginFirstFrameGate();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
        // Full tile loading is optional: cached and partially loaded maps must remain visible.
        if (awaitingFirstMapFrame && attached && leasePublished && mapTexture != null
                && mapTexture.getSurfaceTexture() == surfaceTexture
                && mapSurface != null && mapSurface.isValid()) {
            long sentGeneration = NavigationHudEndpointService.sentMapGeneration(mapSurface, true);
            if (sentGeneration < 0L) {
                logColdWait("local_update_before_dispatch");
                return;
            }
            awaitingFirstMapFrame = false;
            mapView.setAlpha(desiredMapAlpha);
            removeCallbacks(coldLeaseRetry);
            DiagnosticJournal.infoAsync("cluster-map", "cluster surface updated; map shown, opacity="
                    + desiredMapAlpha + ", sent_generation=" + sentGeneration
                    + ", texture_timestamp_ns=" + surfaceTexture.getTimestamp()
                    + ", producer_frame_ack=false, first_update_wait_ms="
                    + (android.os.SystemClock.uptimeMillis() - firstFrameWaitStarted));
        }
    }

    private void beginFirstFrameGate() {
        if (mapTexture == null) return;
        awaitingFirstMapFrame = true;
        firstFrameWaitStarted = android.os.SystemClock.uptimeMillis();
        mapView.setAlpha(0f);
    }

    private void publishLeaseIfReady() {
        publishLeaseIfReady(false);
    }

    /** Replaces dimensions without sending a detach-only interval to Navigator. */
    private void replaceLeaseIfReady() {
        publishLeaseIfReady(true);
    }

    private void publishLeaseIfReady(boolean replace) {
        if (mapTexture == null || !attached
                || mapView.getVisibility() != VISIBLE || !clusterMapEnabled) return;
        // Settings persists enabled=false before calling the launcher off boundary. Reject every
        // late layout/Surface retry from the old Activity even if it was already in the UI queue.
        if (!panelStore.isEnabled()) {
            revokeLease();
            removeCallbacks(coldLeaseRetry);
            return;
        }
        // Android 9 on KX11 can make TextureView.isAvailable() true without delivering the first
        // SurfaceTextureListener callback after a cold multi-display launch. The old retry loop
        // only checked mapSurface and therefore retried a permanently null value until some
        // unrelated window transition happened to deliver another callback. Recover the owned
        // Surface directly from TextureView on every admission attempt.
        if (mapTexture.isAvailable()) {
            SurfaceTexture texture = mapTexture.getSurfaceTexture();
            if (texture != null && (texture != ownedTexture
                    || mapSurface == null || !mapSurface.isValid())) {
                revokeLease();
                releaseOwnedSurface();
                ownedTexture = texture;
                mapSurface = new Surface(texture);
            }
        }
        // ECARX can expose the DIM TextureView before it reports the secondary window as visible.
        // We intentionally keep an existing lease through later visibility changes, so admission
        // must follow the real attached Surface instead of the unreliable initial visibility bit.
        if (!mapTexture.isAvailable() || ownedTexture != mapTexture.getSurfaceTexture()
                || mapSurface == null || !mapSurface.isValid()) {
            logColdWait("surface_unavailable");
            scheduleColdLeaseRetry();
            return;
        }
        int width = mapTexture.getWidth();
        int height = mapTexture.getHeight();
        if (width <= 1 || height <= 1) {
            logColdWait("geometry_not_ready");
            scheduleColdLeaseRetry();
            return;
        }
        if (hasLiveLease() && width == publishedWidth && height == publishedHeight) return;
        // Preserve the last frame when replacing only this live lease's dimensions.
        if (!leasePublished) beginFirstFrameGate();
        SurfaceTexture texture = mapTexture.getSurfaceTexture();
        if (texture != null) texture.setDefaultBufferSize(width, height);
        NavigationHudEndpointService.ensureClusterEndpointStarted(getContext());
        long generation = NavigationHudEndpointService.publishClusterSurface(
                mapSurface, width, height,
                Math.max(1, getResources().getDisplayMetrics().densityDpi));
        if (generation >= 0L) {
            leasePublished = true;
            publishedSurface = mapSurface;
            publishedWidth = width;
            publishedHeight = height;
            coldLeaseRetryCount = 0;
            removeCallbacks(coldLeaseRetry);
            DiagnosticJournal.infoAsync("cluster-map", "stage=local_surface_published, generation="
                    + generation + ", size=" + width + "x" + height
                    + ", texture_id=" + System.identityHashCode(ownedTexture)
                    + ", surface_id=" + System.identityHashCode(mapSurface));
            scheduleColdLeaseRetry();
        } else {
            logColdWait("endpoint_admission_rejected");
            scheduleColdLeaseRetry();
        }
    }

    /**
     * A cold multi-display launch occasionally misses every initial visibility/layout callback on
     * KX11. Keep retrying the tiny lease admission check while this live panel owns the TextureView;
     * after six seconds the cadence drops to one check per second and stops immediately on success.
     */
    private void scheduleColdLeaseRetry() {
        if (!attached || (hasLiveLease() && !awaitingFirstMapFrame) || mapTexture == null || !clusterMapEnabled
                || mapView.getVisibility() != VISIBLE) return;
        removeCallbacks(coldLeaseRetry);
        long delay = !leasePublished && coldLeaseRetryCount < COLD_LEASE_FAST_RETRY_COUNT
                ? COLD_LEASE_FAST_RETRY_MS : COLD_LEASE_SLOW_RETRY_MS;
        postDelayed(coldLeaseRetry, delay);
    }

    private void retryColdLease() {
        if (!attached || (hasLiveLease() && !awaitingFirstMapFrame) || mapTexture == null || !clusterMapEnabled
                || mapView.getVisibility() != VISIBLE) return;
        if (!panelStore.isEnabled()) { revokeLease(); return; }
        coldLeaseRetryCount++;
        if (!hasLiveLease()) recoverColdGeometry();
        publishLeaseIfReady(false);
        if (hasLiveLease() && awaitingFirstMapFrame) {
            logColdWait(NavigationHudEndpointService.sentMapGeneration(mapSurface, true) < 0L
                    ? "awaiting_dispatch" : "awaiting_surface_update");
            scheduleColdLeaseRetry();
        }
    }

    private boolean hasLiveLease() {
        return leasePublished && publishedSurface == mapSurface && mapSurface != null
                && mapSurface.isValid() && mapTexture != null && mapTexture.isAvailable()
                && ownedTexture == mapTexture.getSurfaceTexture();
    }

    /** Replays only the missing layout part of Settings' reload; a healthy map is never rebuilt. */
    private void recoverColdGeometry() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastGeometryRecovery != 0L && now - lastGeometryRecovery < 1_000L) return;
        lastGeometryRecovery = now;
        InstrumentElementConfig map = firstMap();
        if (map == null || !map.enabled || getWidth() <= 1 || getHeight() <= 1) return;
        LayoutParams expected = mapParams(map);
        android.view.ViewGroup.LayoutParams current = mapView.getLayoutParams();
        boolean changed = !(current instanceof LayoutParams) || current.width != expected.width
                || current.height != expected.height
                || ((LayoutParams) current).leftMargin != expected.leftMargin
                || ((LayoutParams) current).topMargin != expected.topMargin;
        if (changed) mapView.setLayoutParams(expected);
        if (changed || !mapTexture.isAvailable() || mapView.getWidth() <= 1 || mapView.getHeight() <= 1) {
            requestLayout();
            mapView.requestLayout();
            mapView.invalidate();
            invalidate();
            logColdWait("geometry_reconciled");
        }
    }

    private void logColdWait(@NonNull String reason) {
        long now = android.os.SystemClock.uptimeMillis();
        if (reason.equals(lastWaitReason) && now - lastWaitLog < 2_000L) return;
        lastWaitReason = reason;
        lastWaitLog = now;
        DiagnosticJournal.infoAsync("cluster-map", "stage=cold_wait, reason=" + reason
                + ", retry=" + coldLeaseRetryCount + ", parent=" + getWidth() + "x" + getHeight()
                + ", map=" + mapView.getWidth() + "x" + mapView.getHeight()
                + ", texture_available=" + (mapTexture != null && mapTexture.isAvailable())
                + ", surface_valid=" + (mapSurface != null && mapSurface.isValid())
                + ", attached=" + attached + ", window_visible=" + windowVisible
                + ", local_lease=" + leasePublished + ", awaiting_update=" + awaitingFirstMapFrame);
    }

    private void revokeLease() {
        Surface surface = publishedSurface;
        if (leasePublished && surface != null) {
            NavigationHudEndpointService.revokeClusterSurface(surface);
        }
        leasePublished = false;
        publishedSurface = null;
        publishedWidth = 0;
        publishedHeight = 0;
    }

    private void releaseOwnedSurface() {
        Surface surface = mapSurface;
        mapSurface = null;
        ownedTexture = null;
        if (surface != null) {
            try { surface.release(); } catch (RuntimeException ignored) {}
        }
    }

    @Nullable
    private InstrumentElementConfig firstMap() {
        for (InstrumentElementConfig element : config.elements) {
            if (element.type == InstrumentElementType.NAV_MAP) return element;
        }
        return null;
    }

    @NonNull
    private LayoutParams mapParams(@NonNull InstrumentElementConfig map) {
        int availableWidth = Math.max(1, getWidth());
        int availableHeight = Math.max(1, getHeight());
        int left = Math.round(map.x * availableWidth / (float) config.columns);
        int top = Math.round(map.y * availableHeight / (float) config.rows);
        int width = Math.round(map.width * availableWidth / (float) config.columns);
        int height = Math.round(map.height * availableHeight / (float) config.rows);
        LayoutParams params = new LayoutParams(Math.max(1, width), Math.max(1, height));
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        InstrumentElementConfig map = firstMap();
        if (map != null && map.enabled) {
            mapView.setLayoutParams(mapParams(map));
            mapView.post(this::publishLeaseIfReady);
        }
    }

    @NonNull
    private NavigationIntegrationConfig.MapProfile clusterProfile() {
        String raw = navigationPreferences.navigationIntegrationConfigJson.get();
        String normalized = raw == null ? "" : raw;
        if (cachedMapProfile != null && normalized.equals(cachedMapProfileRaw)) {
            return cachedMapProfile;
        }
        NavigationIntegrationConfig.MapProfile resolved;
        if (normalized.trim().isEmpty()) {
            resolved = new NavigationIntegrationConfig().clusterMap;
        } else {
            try {
                resolved = NavigationIntegrationConfig.fromJson(normalized).clusterMap;
            } catch (RuntimeException invalid) {
                resolved = new NavigationIntegrationConfig().clusterMap;
            }
        }
        cachedMapProfileRaw = normalized;
        cachedMapProfile = resolved;
        return resolved;
    }

    /** Lightweight editor-only locator; it never starts a second MapKit renderer. */
    private static final class MapPlaceholderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        MapPlaceholderView(@NonNull Context context) {
            super(context);
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xFF101924);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFF30485D);
            paint.setStrokeWidth(Math.max(2f, getWidth() * .004f));
            for (int index = -2; index <= 6; index++) {
                float y = getHeight() * (index + 1f) / 6f;
                canvas.drawLine(0f, y, getWidth(), y + getHeight() * .35f, paint);
            }
            paint.setColor(0xFF5D8FB6);
            paint.setStrokeWidth(Math.max(3f, getWidth() * .007f));
            canvas.drawLine(getWidth() * .08f, getHeight() * .72f,
                    getWidth() * .92f, getHeight() * .28f, paint);
            paint.setColor(0xFFFFC400);
            paint.setStrokeWidth(Math.max(5f, getWidth() * .012f));
            canvas.drawLine(getWidth() * .38f, getHeight() * .88f,
                    getWidth() * .55f, getHeight() * .48f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(14f, getHeight() * .09f));
            paint.setColor(0xCCFFFFFF);
            canvas.drawText("НЕЗАВИСИМАЯ КАРТА", getWidth() * .5f,
                    getHeight() * .14f, paint);
        }
    }
}
