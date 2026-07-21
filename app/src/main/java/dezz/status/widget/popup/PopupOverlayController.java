/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.OutlineTextView;
import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ActionDispatcher;

/** Independent fixed-pixel, draggable, touchable popup grid controlled by retained HA state. */
public final class PopupOverlayController {
    private static final long ACTION_DEBOUNCE_MS = 750L;

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
    /** Prevent connector updates from replacing the touched View between DOWN and UP. */
    private boolean touchInProgress;
    private boolean refreshDeferred;

    private FrameLayout root;
    private WindowManager.LayoutParams params;
    private float touchX;
    private float touchY;
    private int startX;
    private int startY;
    private boolean rootDragging;
    @Nullable private PopupOverlayConfig currentConfig;

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
        currentConfig = overlayConfigs.find(overlayId);
        if (currentConfig == null || !currentConfig.enabled) {
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
                || AutomationContract.SCOPE_BUILTIN.equals(scope)) applyPreferences();
    }

    public void destroy() {
        destroyed = true;
        pendingActions.clear();
        lastActionAtByItem.clear();
        touchInProgress = false;
        refreshDeferred = false;
        main.removeCallbacksAndMessages(null);
        if (root != null) {
            try { windowManager.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        params = null;
    }

    private void ensureView() {
        if (root != null) return;
        root = new FrameLayout(context);
        root.setClipChildren(false);
        root.setClipToPadding(false);
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
        try {
            windowManager.addView(root, params);
        } catch (Exception e) {
            root = null;
            params = null;
            Toast.makeText(context, "Не удалось создать всплывающий оверлей: "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateWindowGeometry() {
        if (root == null || params == null) return;
        PopupOverlayConfig config = currentConfig;
        if (config == null) return;
        params.width = clamp(config.width, 100, 4000);
        params.height = clamp(config.height, 100, 4000);
        params.x = config.x;
        params.y = config.y;
        try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) {}

        GradientDrawable bg = new GradientDrawable();
        AutomationState overlayState = states.get(AutomationContract.SCOPE_OVERLAY, overlayId);
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
        root.removeAllViews();
        PopupOverlayConfig config = currentConfig;
        if (config == null || !states.effectiveVisibility(AutomationContract.SCOPE_OVERLAY,
                overlayId, config.defaultVisible)) {
            setOverlayVisible(false);
            return;
        }

        int rows = clamp(config.rows, 1, 50);
        int columns = clamp(config.columns, 1, 50);
        int gap = clamp(config.cellGap, 0, 500);
        int left = clamp(config.paddingLeft, 0, params.width / 2);
        int right = clamp(config.paddingRight, 0, params.width / 2);
        int top = clamp(config.paddingTop, 0, params.height / 2);
        int bottom = clamp(config.paddingBottom, 0, params.height / 2);
        int usableWidth = Math.max(columns, params.width - left - right - gap * (columns - 1));
        int usableHeight = Math.max(rows, params.height - top - bottom - gap * (rows - 1));
        int cellWidth = Math.max(1, usableWidth / columns);
        int cellHeight = Math.max(1, usableHeight / rows);
        boolean[][] used = new boolean[rows][columns];
        int visibleCount = 0;
        long now = System.currentTimeMillis();

        List<PopupItemConfig> items = configs.load(overlayId);
        for (PopupItemConfig item : items) {
            if (!item.enabled) continue;
            String stateScope = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? AutomationContract.SCOPE_BUILTIN : AutomationContract.SCOPE_POPUP;
            String stateId = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    && !item.builtinId.isEmpty() ? item.builtinId : item.automationId;
            AutomationState state = states.get(stateScope, stateId);
            if (!state.visible) continue;
            BuiltinValue builtin = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? builtinProvider.getBuiltinValue(item.builtinId) : null;
            if (PopupItemConfig.TYPE_BUILTIN.equals(item.type) && builtin == null) continue;
            if (builtin != null && !builtin.visible) continue;
            TilePresentation presentation = resolvePresentation(item, state, builtin, now);
            // Transparent is a value-rule shorthand for hiding the complete device. Filter it
            // before grid placement so the cell is released and another tile can occupy it.
            if (AutomationState.isFullyTransparentColor(presentation.color)) continue;
            int spanX = clamp(item.columnSpan, 1, columns);
            int spanY = clamp(item.rowSpan, 1, rows);
            int[] position = findPosition(used, item.row, item.column, spanY, spanX);
            if (position == null) continue;
            mark(used, position[0], position[1], spanY, spanX);

            View tile = buildTile(item, state, builtin, presentation);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    spanX * cellWidth + (spanX - 1) * gap,
                    spanY * cellHeight + (spanY - 1) * gap);
            lp.leftMargin = left + position[1] * (cellWidth + gap);
            lp.topMargin = top + position[0] * (cellHeight + gap);
            tile.setTranslationY(item.adjustY);
            tile.setTranslationX(item.adjustX);
            root.addView(tile, lp);
            visibleCount++;
        }
        setOverlayVisible(visibleCount > 0);
    }

    /** GONE alone is not reliable on every OEM WindowManager; NOT_TOUCHABLE guarantees that
     * an empty fixed-size overlay cannot leave a 500×500 dead touch zone. */
    private void setOverlayVisible(boolean visible) {
        if (root == null || params == null) return;
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
        int nextFlags = visible
                ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (nextFlags != params.flags) {
            params.flags = nextFlags;
            try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) {}
        }
    }

    private View buildTile(PopupItemConfig item, AutomationState state,
                           @Nullable BuiltinValue builtin,
                           @NonNull TilePresentation presentation) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(item.orientation == 1 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(item.padding, item.padding, item.padding, item.padding);

        String bgValue = state.backgroundColor == null ? item.backgroundColor : state.backgroundColor;
        int bgBase = AutomationState.parseColor(bgValue, 0xFF28282C);
        GradientDrawable bg = new GradientDrawable();
        int tileAlpha = state.backgroundColor == null
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
        if (state.icon != null && PopupIconCatalog.resolve(state.icon) != 0) iconId = state.icon;
        FrameLayout iconBox = new FrameLayout(context);
        GradientDrawable iconBg = new GradientDrawable();
        int iconBgBase = AutomationState.parseColor(item.iconBackgroundColor, 0x00000000);
        iconBg.setColor((iconBgBase & 0x00FFFFFF)
                | (clamp(item.iconBackgroundAlpha, 0, 255) << 24));
        iconBg.setCornerRadius(item.iconCornerRadius);
        iconBox.setBackground(iconBg);
        iconBox.setPadding(item.iconPadding, item.iconPadding, item.iconPadding, item.iconPadding);
        iconBox.setTranslationX(item.iconAdjustX);
        iconBox.setTranslationY(item.iconAdjustY);
        iconBox.setRotation(item.iconRotation);
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconRes = PopupIconCatalog.resolve(iconId);
        if (iconRes != 0) icon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(
                AutomationState.parseColor(item.iconColor, 0xFFFFFFFF)));
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
        title.setTextColor(withAlpha(AutomationState.parseColor(item.titleColor, 0xCCFFFFFF),
                item.titleAlpha));
        title.setTypeface(title.getTypeface(), item.titleBold ? Typeface.BOLD : Typeface.NORMAL);
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
        value.setTypeface(value.getTypeface(), item.textBold ? Typeface.BOLD : Typeface.NORMAL);
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
}
