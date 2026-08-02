/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.OutlineTextView;
import dezz.status.widget.Fonts;
import dezz.status.widget.Preferences;
import dezz.status.widget.VisualBrickEditorActivity;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ActionDispatcher;
import dezz.status.widget.phone.PhoneAppIconStore;
import dezz.status.widget.phone.PhoneNotificationAutomation;
import dezz.status.widget.phone.PhoneNotificationPreviewIconFactory;
import dezz.status.widget.launcher.panels.PanelContentEditOverlay;

/** Independent fixed-pixel, draggable, touchable popup grid controlled by retained HA state. */
public final class PopupOverlayController {
    private static final long ACTION_DEBOUNCE_MS = 750L;
    private static final long STATE_REFRESH_DEBOUNCE_MS = 50L;

    public interface BuiltinProvider {
        @Nullable BuiltinValue getBuiltinValue(@NonNull String automationId);
    }

    public static final class BuiltinValue {
        public final String text;
        public final String color;
        public final String iconId;
        public final boolean visible;

        public BuiltinValue(String text, String color, String iconId, boolean visible) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.iconId = iconId;
            this.visible = visible;
        }
    }

    private final android.content.Context context;
    private final AutomationStateStore states;
    private final PopupItemConfigStore configs;
    private final PopupOverlayConfigStore overlayConfigs;
    private final String overlayId;
    private final ActionDispatcher actionDispatcher;
    private final BuiltinProvider builtinProvider;
    private final WindowManager windowManager;
    /** A car display is noisy: the platform slop alone turns ordinary taps into drags. */
    private final int dragThreshold;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pendingActions = new HashSet<>();
    private final Map<String, Long> lastActionAtByItem = new HashMap<>();
    private boolean destroyed;
    /** Ephemeral WYSIWYG session. It never writes fake notification data to retained state. */
    private boolean editorPreview;
    /** While one reserved phone layout is edited, the other one must not cover it. */
    private boolean editorPreviewSuppressed;
    /** Prevent connector updates from replacing the touched View between DOWN and UP. */
    private boolean touchInProgress;
    private boolean refreshDeferred;
    private List<PopupItemConfig> currentItems = Collections.emptyList();
    /** Actual auto-placement resolved during the last render, used by the direct grid editor. */
    private final Map<String, int[]> renderedPlacements = new HashMap<>();
    /** Live tile Views stay attached while the edit gesture is in progress. */
    private final Map<String, View> renderedTiles = new HashMap<>();
    private final Runnable stateRefresh = () -> {
        PopupOverlayConfig config = PopupOverlayController.this.currentConfig;
        if (destroyed || config == null || (!config.enabled && !editorPreview)
                || editorPreviewSuppressed) return;
        if (touchInProgress) {
            refreshDeferred = true;
            return;
        }
        ensureView();
        updateWindowGeometry();
        renderItems();
    };

    private FrameLayout root;
    private WindowManager.LayoutParams params;
    /**
     * Android 9 on the KX11 can traverse an application-overlay ViewGroup after
     * {@code removeAllViews()} has already nulled one of its child slots.  Keep the complete
     * popup detached while its child tree is rebuilt, then attach the finished tree atomically.
     */
    private boolean rootAdded;
    /** Every currently attached generation; normally one, briefly two during a frame-safe swap. */
    private final Set<FrameLayout> attachedRoots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private float touchX;
    private float touchY;
    private int startX;
    private int startY;
    private boolean rootDragging;
    @Nullable private PopupOverlayConfig currentConfig;

    /** Manager-owned mode switch; call applyPreferences after changing it. */
    public void setEditorPreviewMode(boolean active, boolean suppressed) {
        editorPreview = active;
        editorPreviewSuppressed = suppressed;
    }

    public PopupOverlayController(@NonNull android.content.Context context,
                                  @NonNull Preferences prefs,
                                  @NonNull AutomationStateStore states,
                                  @NonNull ActionDispatcher actionDispatcher,
                                  @NonNull BuiltinProvider builtinProvider) {
        this(context, prefs, states, actionDispatcher, builtinProvider,
                PopupItemConfig.DEFAULT_OVERLAY_ID, new PopupOverlayConfigStore(prefs));
    }

    public PopupOverlayController(@NonNull android.content.Context context,
                                  @NonNull Preferences prefs,
                                  @NonNull AutomationStateStore states,
                                  @NonNull ActionDispatcher actionDispatcher,
                                  @NonNull BuiltinProvider builtinProvider,
                                  @NonNull String overlayId,
                                  @NonNull PopupOverlayConfigStore overlayConfigs) {
        this.context = context;
        this.states = states;
        this.configs = new PopupItemConfigStore(prefs);
        this.overlayConfigs = overlayConfigs;
        this.overlayId = AutomationContract.requireSafeId(overlayId);
        this.actionDispatcher = actionDispatcher;
        this.builtinProvider = builtinProvider;
        this.windowManager = context.getSystemService(WindowManager.class);
        int platformSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int twentyFourDp = Math.round(24f * context.getResources().getDisplayMetrics().density);
        this.dragThreshold = Math.max(platformSlop * 3, twentyFourDp);
    }

    public void applyPreferences() {
        if (destroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::applyPreferences);
            return;
        }
        if (touchInProgress) {
            refreshDeferred = true;
            return;
        }
        main.removeCallbacks(stateRefresh);
        currentConfig = overlayConfigs.find(overlayId);
        if (currentConfig == null || (!currentConfig.enabled && !editorPreview)
                || editorPreviewSuppressed) {
            currentItems = Collections.emptyList();
            setOverlayVisible(false);
            return;
        }
        currentItems = configs.load(overlayId);
        ensureView();
        updateWindowGeometry();
        renderItems();
    }

    /** Applies a manager-owned snapshot so N overlays require only one JSON parse per refresh. */
    public void applyPreferences(@NonNull PopupOverlayConfig config,
                                 @NonNull List<PopupItemConfig> items) {
        if (destroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> applyPreferences(config, items));
            return;
        }
        main.removeCallbacks(stateRefresh);
        currentConfig = config;
        currentItems = new java.util.ArrayList<>(items);
        if (touchInProgress) {
            refreshDeferred = true;
            return;
        }
        if ((!config.enabled && !editorPreview) || editorPreviewSuppressed) {
            setOverlayVisible(false);
            return;
        }
        ensureView();
        updateWindowGeometry();
        renderItems();
    }

    public void onStateChanged(String scope) {
        if (AutomationContract.SCOPE_POPUP.equals(scope)
                || AutomationContract.SCOPE_OVERLAY.equals(scope)
                || AutomationContract.SCOPE_BUILTIN.equals(scope)) {
            // Initial connector snapshots can update dozens of tiles in one burst. Rebuild this
            // overlay once after the burst and reuse its already-parsed item configuration.
            main.removeCallbacks(stateRefresh);
            main.postDelayed(stateRefresh, STATE_REFRESH_DEBOUNCE_MS);
        }
    }

    public void destroy() {
        destroyed = true;
        pendingActions.clear();
        lastActionAtByItem.clear();
        touchInProgress = false;
        refreshDeferred = false;
        currentItems = Collections.emptyList();
        renderedPlacements.clear();
        renderedTiles.clear();
        main.removeCallbacksAndMessages(null);
        detachAllRootsImmediately();
        root = null;
        params = null;
    }

    private void ensureView() {
        if (root != null) return;
        root = new FrameLayout(context);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        // The KX11 firmware has a broken inherited-RTL traversal for overlay windows.  Every
        // generated popup is physically left-to-right, so make that contract explicit.
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.setOnTouchListener(this::dragOverlay);
        PopupOverlayConfig config = currentConfig;
        if (config == null) return;
        params = new WindowManager.LayoutParams(
                config.width, config.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = config.x;
        params.y = config.y;
        params.windowAnimations = 0;
        rootAdded = false;
    }

    private void updateWindowGeometry() {
        if (root == null || params == null) return;
        PopupOverlayConfig config = currentConfig;
        if (config == null) return;
        params.width = clamp(config.width, 100, 4000);
        params.height = clamp(config.height, 100, 4000);
        params.x = config.x;
        params.y = config.y;
        if (rootAdded) {
            try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) {}
        }

        GradientDrawable bg = new GradientDrawable();
        AutomationState overlayState = editorPreview ? AutomationState.missing()
                : states.get(AutomationContract.SCOPE_OVERLAY, overlayId);
        String configuredBackground = overlayState.backgroundColor == null
                ? config.backgroundColor : overlayState.backgroundColor;
        int base = AutomationState.parseColor(configuredBackground, 0xFF000000);
        int alpha = overlayState.backgroundColor == null
                ? clamp(config.backgroundAlpha, 0, 255) : (base >>> 24);
        bg.setColor((base & 0x00FFFFFF) | (alpha << 24));
        bg.setCornerRadius(config.cornerRadius);
        root.setBackground(bg);
    }

    private void renderItems() {
        if (root == null || params == null) return;
        if (touchInProgress) {
            refreshDeferred = true;
            return;
        }
        // Never structurally mutate a ViewGroup that ViewRootImpl has ever measured. Build the
        // replacement completely off-window, attach it, and retire the old generation only after
        // the replacement reaches its first pre-draw. Detaching first caused the visible 1–2
        // frame blink on every connector-state refresh in the KX11 launcher.
        FrameLayout previousRoot = root;
        WindowManager.LayoutParams previousParams = params;
        boolean previousAdded = rootAdded;
        root = null;
        params = null;
        rootAdded = false;
        ensureView();
        updateWindowGeometry();
        if (root == null || params == null) return;
        renderedPlacements.clear();
        renderedTiles.clear();
        PopupOverlayConfig config = currentConfig;
        if (config == null || editorPreviewSuppressed
                || (!editorPreview && !states.effectiveVisibility(
                AutomationContract.SCOPE_OVERLAY, overlayId, config.defaultVisible))) {
            setOverlayVisible(false);
            return;
        }

        int rows = clamp(config.rows, 1, 50);
        int columns = clamp(config.columns, 1, 50);
        int left = clamp(config.paddingLeft, 0, Math.max(0, params.width - columns));
        int right = clamp(config.paddingRight, 0,
                Math.max(0, params.width - left - columns));
        int top = clamp(config.paddingTop, 0, Math.max(0, params.height - rows));
        int bottom = clamp(config.paddingBottom, 0,
                Math.max(0, params.height - top - rows));
        int gap = safeGridGap(config.cellGap, columns, rows,
                params.width - left - right, params.height - top - bottom);
        int usableWidth = Math.max(columns, params.width - left - right - gap * (columns - 1));
        int usableHeight = Math.max(rows, params.height - top - bottom - gap * (rows - 1));
        int cellWidth = Math.max(1, usableWidth / columns);
        int cellHeight = Math.max(1, usableHeight / rows);
        boolean[][] used = new boolean[rows][columns];
        int visibleCount = 0;
        long now = System.currentTimeMillis();

        for (PopupItemConfig item : currentItems) {
            if (!item.enabled) continue;
            String stateScope = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? AutomationContract.SCOPE_BUILTIN : AutomationContract.SCOPE_POPUP;
            String stateId = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    && !item.builtinId.isEmpty() ? item.builtinId : item.automationId;
            AutomationState state = states.get(stateScope, stateId);
            boolean previewField = editorPreview
                    && PhoneNotificationAutomation.isFieldAutomationId(stateId);
            if (!previewField && !state.visible) continue;
            if (!previewField && PhoneNotificationAutomation.isFieldAutomationId(stateId)
                    && (!state.fresh || state.text == null || state.text.isEmpty()
                    || (state.expiresAt > 0L && now >= state.expiresAt))) {
                // A local condition may override visibility but never the lifetime/content of a
                // phone delivery. Otherwise a false-branch "show" rule would resurrect an old or
                // empty notification after its timer expired.
                continue;
            }
            BuiltinValue builtin = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? builtinProvider.getBuiltinValue(item.builtinId) : null;
            if (PopupItemConfig.TYPE_BUILTIN.equals(item.type) && builtin == null) continue;
            if (builtin != null && !builtin.visible) continue;
            TilePresentation presentation = previewField
                    ? new TilePresentation(
                    PhoneNotificationAutomation.editorPreviewText(stateId),
                    item.defaultTextColor, false, false)
                    : resolvePresentation(item, state, builtin, now);
            // Transparent is a value-rule shorthand for hiding the complete device. Filter it
            // before grid placement so the cell is released and another tile can occupy it.
            if (AutomationState.isFullyTransparentColor(presentation.color)) continue;
            int spanX = clamp(item.columnSpan, 1, columns);
            int spanY = clamp(item.rowSpan, 1, rows);
            int[] position = findPosition(used, item.row, item.column, spanY, spanX);
            if (position == null) continue;
            mark(used, position[0], position[1], spanY, spanX);
            renderedPlacements.put(item.id, new int[]{position[1], position[0], spanX, spanY});

            View tile = buildTile(item, state, builtin, presentation, previewField);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    spanX * cellWidth + (spanX - 1) * gap,
                    spanY * cellHeight + (spanY - 1) * gap);
            lp.leftMargin = left + position[1] * (cellWidth + gap);
            lp.topMargin = top + position[0] * (cellHeight + gap);
            tile.setTranslationY(item.adjustY);
            tile.setTranslationX(item.adjustX);
            root.addView(tile, lp);
            renderedTiles.put(item.id, tile);
            visibleCount++;
        }
        if (editorPreview) attachEditorChrome(left, top, right, bottom);
        boolean visible = visibleCount > 0 || editorPreview;
        setOverlayVisible(visible);
        if (!visible) return;
        if (rootAdded && root != null) {
            retireOlderRootsAfterFirstDraw(root);
        } else if (previousAdded && previousRoot != null && previousParams != null) {
            // A transient WindowManager failure must leave the already rendered generation on
            // screen. It is safer to display one stale frame than to flash an empty launcher.
            root = previousRoot;
            params = previousParams;
            rootAdded = attachedRoots.contains(previousRoot)
                    || previousRoot.isAttachedToWindow();
            if (rootAdded) attachedRoots.add(previousRoot);
        }
    }

    /**
     * Adds launcher-style edit chrome over the real WindowManager surface. The fake notification
     * remains rendered underneath, so every drag/resize is a genuine WYSIWYG operation.
     */
    private void attachEditorChrome(int left, int top, int right, int bottom) {
        if (root == null) return;
        PanelContentEditOverlay grid = new PanelContentEditOverlay(context);
        grid.setModel(new EditorGridModel(), new PanelContentEditOverlay.Listener() {
            @Override public void onGestureStateChanged(boolean active) {
                touchInProgress = active;
                if (!active && refreshDeferred && !destroyed) {
                    refreshDeferred = false;
                    // ACTION_UP/CANCEL must leave View.dispatchTouchEvent before WindowManager
                    // swaps the complete overlay root.
                    main.post(PopupOverlayController.this::applyPreferences);
                }
            }

            @Override public void onPlacementChanged(@NonNull String id, boolean finished) {
                applyEditorPlacementsToViews();
                if (finished) {
                    persistEditorPlacements();
                }
            }

            @Override public void onItemClicked(@NonNull String id) {
                try {
                    Intent edit = VisualBrickEditorActivity.intent(context,
                            VisualBrickEditorActivity.SURFACE_POPUP, id, overlayId)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(edit);
                } catch (RuntimeException failure) {
                    Toast.makeText(context, "Не удалось открыть точные настройки: "
                            + failure.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        grid.setEditing(true);
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridParams.setMargins(left, top, right, bottom);
        root.addView(grid, gridParams);

        TextView move = new TextView(context);
        move.setText("✥  Переместить окно");
        move.setTextColor(0xFFFFFFFF);
        move.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        move.setGravity(Gravity.CENTER);
        move.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(0xE62278D7);
        handleBackground.setCornerRadius(dp(12));
        handleBackground.setStroke(dp(1), 0xFFFFFFFF);
        move.setBackground(handleBackground);
        move.setOnTouchListener(this::dragEditorWindow);
        FrameLayout.LayoutParams moveParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.TOP | Gravity.END);
        moveParams.setMargins(dp(4), dp(4), dp(4), 0);
        root.addView(move, moveParams);
    }

    /** Moves/resizes the real content together with the edit outline without rebuilding Views. */
    private void applyEditorPlacementsToViews() {
        PopupOverlayConfig config = currentConfig;
        if (root == null || params == null || config == null) return;
        int rows = clamp(config.rows, 1, 50);
        int columns = clamp(config.columns, 1, 50);
        int left = clamp(config.paddingLeft, 0, Math.max(0, params.width - columns));
        int right = clamp(config.paddingRight, 0,
                Math.max(0, params.width - left - columns));
        int top = clamp(config.paddingTop, 0, Math.max(0, params.height - rows));
        int bottom = clamp(config.paddingBottom, 0,
                Math.max(0, params.height - top - rows));
        int gap = safeGridGap(config.cellGap, columns, rows,
                params.width - left - right, params.height - top - bottom);
        int usableWidth = Math.max(columns,
                params.width - left - right - gap * (columns - 1));
        int usableHeight = Math.max(rows,
                params.height - top - bottom - gap * (rows - 1));
        int cellWidth = Math.max(1, usableWidth / columns);
        int cellHeight = Math.max(1, usableHeight / rows);
        for (Map.Entry<String, View> entry : renderedTiles.entrySet()) {
            int[] placement = renderedPlacements.get(entry.getKey());
            if (placement == null) continue;
            FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(
                    placement[2] * cellWidth + (placement[2] - 1) * gap,
                    placement[3] * cellHeight + (placement[3] - 1) * gap);
            tileParams.leftMargin = left + placement[0] * (cellWidth + gap);
            tileParams.topMargin = top + placement[1] * (cellHeight + gap);
            entry.getValue().setLayoutParams(tileParams);
        }
    }

    private final class EditorGridModel implements PanelContentEditOverlay.Model {
        @Override public int columns() {
            return currentConfig == null ? 1 : Math.max(1, currentConfig.columns);
        }

        @Override public int rows() {
            return currentConfig == null ? 1 : Math.max(1, currentConfig.rows);
        }

        @Override public int cellGapPx() {
            return currentConfig == null ? 0 : Math.max(0, currentConfig.cellGap);
        }

        @NonNull @Override public List<PanelContentEditOverlay.Item> items() {
            List<PanelContentEditOverlay.Item> result = new ArrayList<>();
            for (PopupItemConfig item : currentItems) {
                if (!item.enabled) continue;
                int[] placement = renderedPlacements.get(item.id);
                if (placement == null) continue;
                result.add(new PanelContentEditOverlay.Item(item.id, item.name,
                        placement[0], placement[1], placement[2], placement[3]));
            }
            return result;
        }

        @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                              int columnSpan, int rowSpan) {
            int safeColumns = columns();
            int safeRows = rows();
            if (column < 0 || row < 0 || columnSpan < 1 || rowSpan < 1
                    || column + columnSpan > safeColumns || row + rowSpan > safeRows) {
                return false;
            }
            PopupItemConfig target = null;
            for (PopupItemConfig item : currentItems) {
                if (item.id.equals(id)) {
                    target = item;
                    break;
                }
            }
            if (target == null) return false;
            int[] previous = renderedPlacements.get(id);
            if (previous != null && previous[0] == column && previous[1] == row
                    && previous[2] == columnSpan && previous[3] == rowSpan) return false;
            List<Map.Entry<String, int[]>> collisions = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : renderedPlacements.entrySet()) {
                if (id.equals(entry.getKey())) continue;
                int[] other = entry.getValue();
                if (rectanglesOverlap(column, row, columnSpan, rowSpan,
                        other[0], other[1], other[2], other[3])) collisions.add(entry);
            }
            if (!collisions.isEmpty()) {
                // A full 1×3 notification grid has no empty cell. Match HOME's editor behavior:
                // dragging one same-sized field onto another swaps them instead of refusing the
                // gesture. Complex multi-cell collisions remain fail-closed.
                if (previous == null || collisions.size() != 1
                        || previous[2] != columnSpan || previous[3] != rowSpan) return false;
                Map.Entry<String, int[]> collision = collisions.get(0);
                int[] otherPlacement = collision.getValue();
                if (otherPlacement[2] != previous[2] || otherPlacement[3] != previous[3]
                        || placementCollidesWithPeer(collision.getKey(), id,
                        previous[0], previous[1], otherPlacement[2], otherPlacement[3])) {
                    return false;
                }
                PopupItemConfig otherItem = findCurrentItem(collision.getKey());
                if (otherItem == null) return false;
                otherItem.column = previous[0];
                otherItem.row = previous[1];
                renderedPlacements.put(otherItem.id, new int[]{previous[0], previous[1],
                        otherPlacement[2], otherPlacement[3]});
            }
            target.column = column;
            target.row = row;
            target.columnSpan = columnSpan;
            target.rowSpan = rowSpan;
            renderedPlacements.put(id, new int[]{column, row, columnSpan, rowSpan});
            return true;
        }
    }

    @Nullable
    private PopupItemConfig findCurrentItem(@NonNull String id) {
        for (PopupItemConfig item : currentItems) if (item.id.equals(id)) return item;
        return null;
    }

    private boolean placementCollidesWithPeer(@NonNull String movingId,
                                              @NonNull String ignoredId,
                                              int column, int row,
                                              int columnSpan, int rowSpan) {
        for (Map.Entry<String, int[]> entry : renderedPlacements.entrySet()) {
            if (movingId.equals(entry.getKey()) || ignoredId.equals(entry.getKey())) continue;
            int[] other = entry.getValue();
            if (rectanglesOverlap(column, row, columnSpan, rowSpan,
                    other[0], other[1], other[2], other[3])) return true;
        }
        return false;
    }

    private void persistEditorPlacements() {
        try {
            configs.save(overlayId, currentItems);
            // The editor already applied the final LayoutParams in-place. Rebuilding/removing the
            // WindowManager root from inside PanelContentEditOverlay's ACTION_UP callback is both
            // redundant and unsafe on the KX11 Android 9 ViewRoot implementation.
        } catch (JSONException | RuntimeException failure) {
            Toast.makeText(context, "Не удалось сохранить компоновку: "
                    + failure.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean dragEditorWindow(View view, MotionEvent event) {
        if (params == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!beginTouch()) return false;
                startX = params.x;
                startY = params.y;
                touchX = event.getRawX();
                touchY = event.getRawY();
                rootDragging = false;
                view.setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchX;
                float dy = event.getRawY() - touchY;
                if (Math.abs(dx) > dragThreshold / 3f || Math.abs(dy) > dragThreshold / 3f) {
                    rootDragging = true;
                }
                if (rootDragging) {
                    params.x = startX + Math.round(dx);
                    params.y = startY + Math.round(dy);
                    try { windowManager.updateViewLayout(root, params); }
                    catch (Exception ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                view.setPressed(false);
                if (rootDragging) saveCurrentPosition();
                rootDragging = false;
                finishTouch();
                return true;
            default:
                return touchInProgress;
        }
    }

    private static boolean rectanglesOverlap(int x, int y, int width, int height,
                                             int otherX, int otherY,
                                             int otherWidth, int otherHeight) {
        return x < otherX + otherWidth && x + width > otherX
                && y < otherY + otherHeight && y + height > otherY;
    }

    /** Keeps every configured cell and gap inside the physical popup viewport. */
    private static int safeGridGap(int requested, int columns, int rows,
                                   int availableWidth, int availableHeight) {
        int candidate = clamp(requested, 0, 500);
        int horizontalLimit = columns <= 1 ? candidate
                : Math.max(0, (Math.max(1, availableWidth) - columns) / (columns - 1));
        int verticalLimit = rows <= 1 ? candidate
                : Math.max(0, (Math.max(1, availableHeight) - rows) / (rows - 1));
        return Math.min(candidate, Math.min(horizontalLimit, verticalLimit));
    }

    /** Hidden overlays are removed from WindowManager, so they cannot leave a dead touch zone. */
    private void setOverlayVisible(boolean visible) {
        if (root == null || params == null) return;
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
        int nextFlags = visible
                ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (nextFlags != params.flags) {
            params.flags = nextFlags;
            if (rootAdded) {
                try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) {}
            }
        }
        if (!visible) {
            detachAllRootsImmediately();
            return;
        }
        if (rootAdded) return;
        try {
            windowManager.addView(root, params);
            rootAdded = true;
            attachedRoots.add(root);
        } catch (RuntimeException failure) {
            rootAdded = root.isAttachedToWindow();
            if (rootAdded) attachedRoots.add(root);
            if (!rootAdded) {
                Toast.makeText(context, "Не удалось создать всплывающий оверлей: "
                        + failure.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Retires the previous complete trees only after the replacement is ready to draw. */
    private void retireOlderRootsAfterFirstDraw(@NonNull FrameLayout replacement) {
        List<FrameLayout> retiring = new ArrayList<>(attachedRoots);
        retiring.remove(replacement);
        if (retiring.isEmpty()) return;
        replacement.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    private boolean handled;

                    @Override public boolean onPreDraw() {
                        if (handled) return true;
                        handled = true;
                        android.view.ViewTreeObserver observer =
                                replacement.getViewTreeObserver();
                        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                        // Run after this traversal so the replacement contributes a compositor
                        // frame before any previous surface is removed.
                        main.post(() -> {
                            for (FrameLayout retired : retiring) {
                                detachRootImmediately(retired);
                            }
                        });
                        return true;
                    }
                });
        replacement.invalidate();
    }

    private void detachAllRootsImmediately() {
        List<FrameLayout> attached = new ArrayList<>(attachedRoots);
        for (FrameLayout candidate : attached) detachRootImmediately(candidate);
        // A failed add may not have entered the set but can still report itself attached.
        if (root != null && root.isAttachedToWindow()) detachRootImmediately(root);
    }

    /** Detaches one complete, immutable generation on the KX11 Android 9 build. */
    private void detachRootImmediately(@Nullable FrameLayout current) {
        if (current == null || (!attachedRoots.contains(current)
                && !current.isAttachedToWindow())) return;
        try {
            windowManager.removeViewImmediate(current);
        } catch (RuntimeException ignored) {
            // A simultaneous service teardown may have removed it already.
        } finally {
            attachedRoots.remove(current);
            if (current == root) rootAdded = false;
        }
    }

    private View buildTile(PopupItemConfig item, AutomationState state,
                           @Nullable BuiltinValue builtin,
                           @NonNull TilePresentation presentation,
                           boolean previewField) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(item.orientation == 1 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(item.padding, item.padding, item.padding, item.padding);

        String bgValue = previewField || state.backgroundColor == null
                ? item.backgroundColor : state.backgroundColor;
        int bgBase = AutomationState.parseColor(bgValue, 0xFF28282C);
        GradientDrawable bg = new GradientDrawable();
        int tileAlpha = previewField || state.backgroundColor == null
                ? clamp(item.backgroundAlpha, 0, 255) : (bgBase >>> 24);
        bg.setColor((bgBase & 0x00FFFFFF) | (tileAlpha << 24));
        bg.setCornerRadius(item.cornerRadius);
        int borderBase = AutomationState.parseColor(item.borderColor, 0x00FFFFFF);
        bg.setStroke(item.borderWidth,
                (borderBase & 0x00FFFFFF) | (clamp(item.borderAlpha, 0, 255) << 24));
        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setColor(0xFFFFFFFF);
        rippleMask.setCornerRadius(item.cornerRadius);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
                bg, rippleMask));

        String iconId = item.icon;
        if (builtin != null && PopupIconCatalog.resolve(builtin.iconId) != 0) iconId = builtin.iconId;
        String phoneAppIdentifier = null;
        if (!previewField && state.icon != null && state.icon.startsWith("phone-app:")) {
            phoneAppIdentifier = state.icon.substring("phone-app:".length()).trim();
        } else if (!previewField && state.icon != null
                && PopupIconCatalog.resolve(state.icon) != 0) {
            iconId = state.icon;
        }
        FrameLayout iconBox = new FrameLayout(context);
        GradientDrawable iconBg = new GradientDrawable();
        int iconBgBase = AutomationState.parseColor(item.iconBackgroundColor, 0x00000000);
        iconBg.setColor((iconBgBase & 0x00FFFFFF)
                | (clamp(item.iconBackgroundAlpha, 0, 255) << 24));
        iconBg.setCornerRadius(item.iconCornerRadius);
        iconBox.setBackground(iconBg);
        iconBox.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        iconBox.setClipToOutline(item.iconCornerRadius > 0);
        iconBox.setPadding(item.iconPadding, item.iconPadding, item.iconPadding, item.iconPadding);
        iconBox.setTranslationX(item.iconAdjustX);
        iconBox.setTranslationY(item.iconAdjustY);
        iconBox.setRotation(item.iconRotation);
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconRes = PopupIconCatalog.resolve(iconId);
        boolean previewApplicationIcon = previewField
                && PhoneNotificationAutomation.isIconOverlayId(overlayId)
                && PhoneNotificationAutomation.APPLICATION_AUTOMATION_ID.equals(
                item.automationId);
        Drawable appIcon = previewApplicationIcon
                ? PhoneNotificationPreviewIconFactory.create(context, item.iconSize)
                : phoneAppIdentifier == null ? null
                : PhoneAppIconStore.get(context).drawable(phoneAppIdentifier);
        String renderedIconColor = SmartHomeTileColorPolicy.contentColor(
                item.sourceBinding, item.iconColor, presentation.color);
        if (appIcon != null) {
            icon.setImageDrawable(appIcon);
            ImageViewCompat.setImageTintList(icon,
                    appIcon instanceof BitmapDrawable || previewApplicationIcon
                    ? null
                    : ColorStateList.valueOf(
                            AutomationState.parseColor(renderedIconColor, 0xFFFFFFFF)));
        } else {
            if (iconRes != 0) icon.setImageResource(iconRes);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(
                    AutomationState.parseColor(renderedIconColor, 0xFFFFFFFF)));
        }
        icon.setAlpha(item.iconAlpha / 255f);
        iconBox.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(item.iconSize, item.iconSize);
        iconLp.gravity = item.iconAlignment == 0 ? Gravity.START
                : item.iconAlignment == 2 ? Gravity.END : Gravity.CENTER;
        tile.addView(iconBox, iconLp);

        LinearLayout textGroup = new LinearLayout(context);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        textGroup.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textGroupLp = item.orientation == 1
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tile.addView(textGroup, textGroupLp);

        TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, item.titleSize);
        String renderedTitleColor = SmartHomeTileColorPolicy.contentColor(
                item.sourceBinding, item.titleColor, presentation.color);
        title.setTextColor(withAlpha(AutomationState.parseColor(
                renderedTitleColor, 0xCCFFFFFF),
                item.titleAlpha));
        title.setTypeface(Fonts.resolve(context, item.titleFontFamily,
                item.titleBold, item.titleItalic));
        title.setVisibility(item.showTitle ? View.VISIBLE : View.GONE);
        textGroup.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        OutlineTextView value = new OutlineTextView(context);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);
        value.setText(presentation.text);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, item.textSize);
        value.setTextColor(withAlpha(AutomationState.parseColor(presentation.color, 0xFFFFFFFF),
                item.textAlpha));
        value.setTypeface(Fonts.resolve(context, item.textFontFamily,
                item.textBold, item.textItalic));
        value.setVisibility(item.showStatus ? View.VISIBLE : View.GONE);
        textGroup.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasBoundAction = item.actionBinding != null && item.actionBinding.isBound();
        boolean hasAnyAction = hasBoundAction || !item.actionId.isEmpty();
        // Never send a command from a cached or not-yet-confirmed device state. This also keeps
        // actions fail-closed during boot and reconnect even if their last persisted patch said
        // action_enabled=true.
        boolean commandPending = pendingActions.contains(item.id);
        boolean actionable = hasAnyAction && !presentation.pending && !presentation.stale
                && !commandPending
                && state.actionEnabled;
        String unavailableReason = null;
        if (hasAnyAction && !actionable) {
            if (commandPending) unavailableReason = "Команда уже отправляется";
            else if (presentation.pending) unavailableReason = "Статус устройства ещё не получен";
            else if (presentation.stale) unavailableReason = "Статус устройства устарел";
            else if (!state.actionEnabled) unavailableReason = "Управление сейчас недоступно";
        }
        // Keep the cell itself enabled even while its command is unavailable: every pixel of the
        // allocated grid cell remains a drag surface.  Command availability is handled by the
        // click callback and visual alpha, not by disabling the parent View (which makes touch
        // dispatch OEM-dependent when tapping padding/background instead of a TextView).
        tile.setEnabled(true);
        tile.setClickable(true);
        tile.setFocusable(actionable);
        tile.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        tile.setAlpha(actionable || !hasAnyAction ? 1f : 0.45f);
        attachTileTouch(tile, item, actionable, unavailableReason);
        return tile;
    }

    @NonNull
    private static TilePresentation resolvePresentation(@NonNull PopupItemConfig item,
                                                        @NonNull AutomationState state,
                                                        @Nullable BuiltinValue builtin,
                                                        long now) {
        boolean reactive = !PopupItemConfig.TYPE_STATIC_TEXT.equals(item.type)
                && !PopupItemConfig.TYPE_BUILTIN.equals(item.type);
        boolean pending = reactive && !state.present;
        boolean stale = reactive && state.present
                && state.isStale(now, item.staleAfterSeconds * 1000L);
        String text = builtin == null ? item.defaultText : builtin.text;
        String color = builtin == null ? item.defaultTextColor : builtin.color;
        if (!pending && !stale && state.text != null) text = state.text;
        if (!pending && !stale && state.color != null) color = state.color;
        if (pending) {
            text = item.pendingText;
            color = item.pendingColor;
        } else if (stale) {
            text = item.staleText;
            color = item.staleColor;
        }
        return new TilePresentation(text, color, pending, stale);
    }

    private static final class TilePresentation {
        final String text;
        final String color;
        final boolean pending;
        final boolean stale;

        TilePresentation(String text, String color, boolean pending, boolean stale) {
            this.text = text;
            this.color = color;
            this.pending = pending;
            this.stale = stale;
        }
    }

    /**
     * The complete tile is one touch target. Small pointer noise remains a click; an unlocked
     * overlay starts moving only after a deliberately large drag. Connector refreshes are held
     * until UP/CANCEL so they cannot replace the View halfway through the gesture.
     */
    private void attachTileTouch(View tile, PopupItemConfig item, boolean actionable,
                                 @Nullable String unavailableReason) {
        final float[] down = new float[2];
        final int[] origin = new int[2];
        final boolean[] dragging = new boolean[1];
        final boolean[] clickCancelled = new boolean[1];
        final boolean[] tracking = new boolean[1];
        tile.setOnClickListener(view -> {
            if (actionable) activate(item, null);
            else if (unavailableReason != null) {
                Toast.makeText(context, unavailableReason, Toast.LENGTH_SHORT).show();
            }
        });
        tile.setOnTouchListener((view, event) -> {
            if (params == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!beginTouch()) return false;
                    tracking[0] = true;
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    origin[0] = params.x;
                    origin[1] = params.y;
                    dragging[0] = false;
                    clickCancelled[0] = false;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking[0]) return false;
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!isPositionLocked()
                            && (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                        dragging[0] = true;
                    } else if (isPositionLocked()
                            && (Math.abs(dx) > dragThreshold * 2
                            || Math.abs(dy) > dragThreshold * 2)) {
                        // A long swipe on a fixed overlay is not a deliberate device command.
                        clickCancelled[0] = true;
                    }
                    if (dragging[0]) {
                        view.setPressed(false);
                        params.x = origin[0] + Math.round(dx);
                        params.y = origin[1] + Math.round(dy);
                        try { windowManager.updateViewLayout(root, params); }
                        catch (Exception ignored) {}
                    } else {
                        view.setPressed(!clickCancelled[0]);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!tracking[0]) return false;
                    view.setPressed(false);
                    if (dragging[0]) saveCurrentPosition();
                    else if (!clickCancelled[0]) view.performClick();
                    tracking[0] = false;
                    finishTouch();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (!tracking[0]) return false;
                    view.setPressed(false);
                    if (dragging[0]) saveCurrentPosition();
                    tracking[0] = false;
                    finishTouch();
                    return true;
                default:
                    return tracking[0];
            }
        });
    }

    private void activate(PopupItemConfig item, @Nullable TextView feedback) {
        long now = System.currentTimeMillis();
        Long lastActionAt = lastActionAtByItem.get(item.id);
        if (lastActionAt != null && now - lastActionAt < ACTION_DEBOUNCE_MS) {
            Toast.makeText(context, "Подождите перед повторной командой", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (item.confirmationRequired) {
            try {
                android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(item.title)
                        .setMessage(item.confirmationText)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, which) -> sendAction(item))
                        .create();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                dialog.show();
            } catch (RuntimeException e) {
                Toast.makeText(context, "Не удалось показать подтверждение: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        sendAction(item);
    }

    private void sendAction(PopupItemConfig item) {
        if (destroyed || !pendingActions.add(item.id)) return;
        lastActionAtByItem.put(item.id, System.currentTimeMillis());
        renderItems();
        try {
            JSONObject payload;
            try { payload = new JSONObject(item.actionPayload); }
            catch (JSONException ignored) { payload = new JSONObject(); }
            payload.put("schema", AutomationContract.SCHEMA_VERSION);
            payload.put("request_id", UUID.randomUUID().toString());
            payload.put("item_id", item.id);
            payload.put("overlay_id", overlayId);
            payload.put("automation_id", item.automationId);
            payload.put("action_id", item.actionId);
            payload.put("sent_at", System.currentTimeMillis());
            String stateScope = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? AutomationContract.SCOPE_BUILTIN : AutomationContract.SCOPE_POPUP;
            String stateId = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    && !item.builtinId.isEmpty() ? item.builtinId : item.automationId;
            payload.put("last_state", states.get(stateScope, stateId).toJson());
            ActionBinding binding = item.actionBinding != null
                    ? item.actionBinding : ActionBinding.legacy(item.actionId, item.actionPayload);
            actionDispatcher.dispatch(binding, payload).whenComplete((ignored, failure) ->
                    main.post(() -> {
                        pendingActions.remove(item.id);
                        if (destroyed) return;
                        if (failure != null) {
                            Throwable cause = failure;
                            while (cause.getCause() != null) cause = cause.getCause();
                            Toast.makeText(context, "Команда не отправлена: "
                                            + (cause.getMessage() == null
                                            ? cause.getClass().getSimpleName() : cause.getMessage()),
                                    Toast.LENGTH_LONG).show();
                            renderItems();
                            return;
                        }
                        if (item.autoHideAfterAction) {
                            try {
                                JSONObject hidden = new JSONObject();
                                hidden.put("visible", false);
                                states.apply(AutomationContract.SCOPE_OVERLAY,
                                        overlayId, hidden);
                                renderItems();
                            } catch (JSONException e) {
                                android.util.Log.w("PopupOverlay", "Could not auto-hide tile", e);
                            }
                        }
                        renderItems();
                        Toast.makeText(context, "Команда отправлена", Toast.LENGTH_SHORT).show();
                    }));
        } catch (JSONException | RuntimeException e) {
            pendingActions.remove(item.id);
            if (!destroyed) renderItems();
            Toast.makeText(context, "Команда не сформирована: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean dragOverlay(View view, MotionEvent event) {
        if (params == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!beginTouch()) return false;
                startX = params.x;
                startY = params.y;
                touchX = event.getRawX();
                touchY = event.getRawY();
                rootDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchX;
                float dy = event.getRawY() - touchY;
                if (!isPositionLocked()
                        && (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                    rootDragging = true;
                }
                if (rootDragging) {
                    params.x = startX + Math.round(dx);
                    params.y = startY + Math.round(dy);
                    try { windowManager.updateViewLayout(root, params); }
                    catch (Exception ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (rootDragging) saveCurrentPosition();
                rootDragging = false;
                finishTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (rootDragging) saveCurrentPosition();
                rootDragging = false;
                finishTouch();
                return true;
            default:
                return touchInProgress;
        }
    }

    private boolean beginTouch() {
        if (destroyed || touchInProgress) return false;
        touchInProgress = true;
        return true;
    }

    private void finishTouch() {
        touchInProgress = false;
        if (!refreshDeferred || destroyed) return;
        refreshDeferred = false;
        applyPreferences();
    }

    private boolean isPositionLocked() {
        return currentConfig != null && currentConfig.positionLocked;
    }

    private void saveCurrentPosition() {
        if (params == null) return;
        if (currentConfig != null) {
            currentConfig.x = params.x;
            currentConfig.y = params.y;
        }
        overlayConfigs.savePosition(overlayId, params.x, params.y);
    }

    private static int[] findPosition(boolean[][] used, int requestedRow, int requestedColumn,
                                      int spanRows, int spanColumns) {
        if (requestedRow >= 0 && requestedColumn >= 0
                && fits(used, requestedRow, requestedColumn, spanRows, spanColumns)) {
            return new int[] {requestedRow, requestedColumn};
        }
        for (int row = 0; row < used.length; row++) {
            for (int column = 0; column < used[0].length; column++) {
                if (fits(used, row, column, spanRows, spanColumns)) {
                    return new int[] {row, column};
                }
            }
        }
        return null;
    }

    private static boolean fits(boolean[][] used, int row, int column, int rows, int columns) {
        if (row < 0 || column < 0 || row + rows > used.length
                || column + columns > used[0].length) return false;
        for (int r = row; r < row + rows; r++) {
            for (int c = column; c < column + columns; c++) if (used[r][c]) return false;
        }
        return true;
    }

    private static void mark(boolean[][] used, int row, int column, int rows, int columns) {
        for (int r = row; r < row + rows; r++) {
            for (int c = column; c < column + columns; c++) used[r][c] = true;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, int alpha) {
        int combined = ((color >>> 24) * clamp(alpha, 0, 255) + 127) / 255;
        return (color & 0x00FFFFFF) | (combined << 24);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
