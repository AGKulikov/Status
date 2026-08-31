/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;
import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.navigation.NavigationIntegrationConfig;

/**
 * Native-size instrument composition. Navigator renders only into the map element's real pixel
 * rectangle; every other instrument is drawn by one {@link InstrumentClusterView} above it.
 */
public final class InstrumentPanelView extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    @NonNull private InstrumentPanelConfig config;
    @NonNull private final Preferences navigationPreferences;
    @NonNull private final InstrumentClusterView instruments;
    @NonNull private final View mapView;
    @Nullable private final TextureView mapTexture;
    @Nullable private Surface mapSurface;
    private boolean attached;
    private boolean windowVisible;
    private boolean leasePublished;
    private boolean clusterMapEnabled = true;
    private int publishedWidth;
    private int publishedHeight;
    @Nullable private String cachedMapProfileRaw;
    @Nullable private NavigationIntegrationConfig.MapProfile cachedMapProfile;

    public InstrumentPanelView(@NonNull Context context,
                               @NonNull InstrumentPanelConfig config,
                               boolean editorMode,
                               @Nullable InstrumentClusterView.EditorListener editorListener) {
        super(context);
        setWillNotDraw(false);
        this.config = config;
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
            texture.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = right - left;
                int height = bottom - top;
                if (width <= 1 || height <= 1) return;
                if (leasePublished && (width != publishedWidth || height != publishedHeight)) {
                    revokeLease();
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
        mapView.setAlpha(map.opacityPercent / 100f);
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
            revokeLease();
            return;
        }
        mapView.setVisibility(VISIBLE);
        mapView.setAlpha(map.opacityPercent / 100f);
        LayoutParams params = mapParams(map);
        mapView.setLayoutParams(params);
        if (mapTexture != null) mapTexture.setOpaque(!profile.roadsOnly);
        mapView.requestLayout();
        mapView.invalidate();
        mapView.post(this::publishLeaseIfReady);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        windowVisible = getWindowVisibility() == VISIBLE;
        publishLeaseIfReady();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        windowVisible = false;
        revokeLease();
        releaseOwnedSurface();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (windowVisible) publishLeaseIfReady();
        else revokeLease();
    }

    @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture,
                                                    int width, int height) {
        releaseOwnedSurface();
        mapSurface = new Surface(surfaceTexture);
        publishLeaseIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture,
                                                      int width, int height) {
        if (width == publishedWidth && height == publishedHeight) return;
        revokeLease();
        publishLeaseIfReady();
    }

    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        revokeLease();
        releaseOwnedSurface();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
        // Producer presentation is owned by Navigator; there is no per-frame work in Natro.
    }

    private void publishLeaseIfReady() {
        if (mapTexture == null || mapSurface == null || leasePublished
                || !attached || !windowVisible || mapView.getVisibility() != VISIBLE) return;
        int width = mapTexture.getWidth();
        int height = mapTexture.getHeight();
        if (width <= 1 || height <= 1 || !mapSurface.isValid()) return;
        SurfaceTexture texture = mapTexture.getSurfaceTexture();
        if (texture != null) texture.setDefaultBufferSize(width, height);
        long generation = NavigationHudEndpointService.publishClusterSurface(
                mapSurface, width, height,
                Math.max(1, getResources().getDisplayMetrics().densityDpi));
        if (generation >= 0L) {
            leasePublished = true;
            publishedWidth = width;
            publishedHeight = height;
        }
    }

    private void revokeLease() {
        Surface surface = mapSurface;
        if (leasePublished && surface != null) {
            NavigationHudEndpointService.revokeClusterSurface(surface);
        }
        leasePublished = false;
        publishedWidth = 0;
        publishedHeight = 0;
    }

    private void releaseOwnedSurface() {
        Surface surface = mapSurface;
        mapSurface = null;
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
