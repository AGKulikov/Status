/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.navigation.NavigationHudEndpointService;

/**
 * One HUD view tree: a producer-owned map Surface below Natro's independently placed widgets.
 *
 * <p>{@link TextureView} is deliberate. Unlike SurfaceView it participates in this window's
 * normal composition, so Canvas widgets may overlap the map without screenshots or frame copies.
 * Navigator receives only the Surface lease and renders its second MapWindow directly into it.</p>
 */
final class HudCompositeView extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    @NonNull private HudPanelConfig config;
    private final boolean localHudViewport;
    @NonNull private final TextureView mapTexture;
    @NonNull private final HudCanvasView canvas;
    @Nullable private Surface leasedSurface;
    @Nullable private SurfaceTexture leasedTexture;
    private int leasedWidth;
    private int leasedHeight;
    @Nullable private HudElementConfig activeMap;

    HudCompositeView(@NonNull Context context,
                     @NonNull HudPanelConfig config,
                     @NonNull HudRuntimeData data,
                     boolean localHudViewport) {
        super(context);
        this.config = config;
        this.localHudViewport = localHudViewport;
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(true);
        setClipToPadding(true);

        mapTexture = new TextureView(context);
        mapTexture.setOpaque(true);
        // TextureView rejects every background Drawable on Android 9, including the Drawable
        // produced by setBackgroundColor(). Its opaque producer surface is already black before
        // Navigator submits the first frame, so no View background is needed here.
        mapTexture.setSurfaceTextureListener(this);
        mapTexture.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left <= 1 || bottom - top <= 1) return;
            publishLaidOutSurface();
        });
        addView(mapTexture, new LayoutParams(1, 1));

        canvas = new HudCanvasView(context, config, data, false, null, localHudViewport);
        addView(canvas, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        reconcileMapElement();
    }

    void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        canvas.updateConfig(next);
        reconcileMapElement();
    }

    void invalidateHud() {
        canvas.invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyMapGeometry();
    }

    @Override
    protected void onDetachedFromWindow() {
        revokeSurface();
        super.onDetachedFromWindow();
    }

    private void reconcileMapElement() {
        activeMap = HudDirectMapGeometry.find(config);
        if (activeMap == null) {
            revokeSurface();
            mapTexture.setVisibility(View.GONE);
            return;
        }
        mapTexture.setVisibility(View.VISIBLE);
        // MapKit emits an alpha substrate in the roads-only mode. TextureView must advertise the
        // same contract or SurfaceFlinger deliberately replaces every alpha pixel with black.
        boolean transparentMap = activeMap.options.optBoolean(
                "transparentBackground", false);
        mapTexture.setOpaque(!transparentMap);
        mapTexture.setAlpha(activeMap.options.optInt("opacityPercent", 100) / 100f);
        int radius = activeMap.options.optInt("cornerRadiusPx", 0);
        mapTexture.setOutlineProvider(new RoundedOutline(radius));
        mapTexture.setClipToOutline(radius > 0);
        applyMapGeometry();
    }

    private void applyMapGeometry() {
        HudElementConfig item = activeMap;
        if (item == null || getWidth() <= 0 || getHeight() <= 0) return;
        RectF source = HudDirectMapGeometry.bounds(config, item, localHudViewport);
        int left = Math.round(source.left);
        int top = Math.round(source.top);
        int right = Math.round(source.right);
        int bottom = Math.round(source.bottom);
        LayoutParams params = (LayoutParams) mapTexture.getLayoutParams();
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        boolean sizeChanged = params.width != width || params.height != height;
        params.width = width;
        params.height = height;
        params.leftMargin = left;
        params.topMargin = top;
        mapTexture.setLayoutParams(params);
        if (sizeChanged) mapTexture.requestLayout();
        mapTexture.invalidateOutline();
        // KX11 may keep the producer buffer at the constructor's temporary 1x1 size without
        // issuing onSurfaceTextureSizeChanged(). Reconcile after this geometry traversal as well
        // as from the layout listener above.
        mapTexture.post(this::publishLaidOutSurface);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture,
                                          int width, int height) {
        publishSurface(texture, width, height);
        mapTexture.post(this::publishLaidOutSurface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture,
                                            int width, int height) {
        revokeSurface();
        publishSurface(texture, width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        revokeSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        // MapKit owns rendering cadence. Natro never reads or copies a produced frame.
    }

    private void publishSurface(@NonNull SurfaceTexture texture, int width, int height) {
        // The TextureView is intentionally constructed at 1x1 until the HUD element geometry is
        // known. Publishing that placeholder made Navigator attach a permanent 1x1 MapWindow on
        // cold boot because this Android 9 compositor does not reliably send the resize callback.
        if (activeMap == null || width <= 1 || height <= 1) return;
        if (leasedSurface != null && leasedTexture == texture
                && leasedWidth == width && leasedHeight == height) return;
        revokeSurface();
        texture.setDefaultBufferSize(width, height);
        Surface surface = new Surface(texture);
        leasedSurface = surface;
        leasedTexture = texture;
        leasedWidth = width;
        leasedHeight = height;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        long generation = NavigationHudEndpointService.publishHudSurface(
                surface, width, height, dpi);
        DiagnosticJournal.info("hud-map",
                "HUD TextureView surface published; generation=" + generation
                        + ", size=" + width + "x" + height
                        + ", transparent=" + !mapTexture.isOpaque());
    }

    private void publishLaidOutSurface() {
        if (activeMap == null || !mapTexture.isAvailable()) return;
        SurfaceTexture texture = mapTexture.getSurfaceTexture();
        if (texture == null) return;
        publishSurface(texture, mapTexture.getWidth(), mapTexture.getHeight());
    }

    private void revokeSurface() {
        Surface current = leasedSurface;
        leasedSurface = null;
        leasedTexture = null;
        leasedWidth = 0;
        leasedHeight = 0;
        if (current == null) return;
        NavigationHudEndpointService.revokeHudSurface(current);
        try {
            current.release();
        } catch (RuntimeException ignored) {}
    }

    private static final class RoundedOutline extends ViewOutlineProvider {
        private final int radius;

        RoundedOutline(int radius) {
            this.radius = Math.max(0, radius);
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, Math.max(1, view.getWidth()),
                    Math.max(1, view.getHeight()), radius);
        }
    }
}
