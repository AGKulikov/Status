/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.climate.ClimatePanelConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelView;

/**
 * Owns the independent climate windows used outside the HOME activity.
 *
 * <p>The compact mode is a small draggable button which expands a second overlay window. The
 * {@link ClimatePanelView} is deliberately kept started while that window is collapsed, so an
 * expansion immediately shows the latest confirmed ECARX state rather than waiting for the next
 * vehicle event. Reserved mode first asks {@link ScreenReservationController} to shrink the
 * application work area, and only after the shell confirms success replaces the button with a
 * fixed panel in the reserved strip.</p>
 */
public final class ClimatePanelOverlayController {
    private static final String TAG = "ClimatePanelOverlay";
    private static final int MODE_COMPACT = 0;
    private static final int MODE_RESERVED = 1;
    /** Coalesce live extent-slider changes instead of queueing dozens of shell commands. */
    private static final long RESERVATION_DEBOUNCE_MS = 160L;
    private static final long VENDOR_RESERVATION_VERIFY_MS = 250L;
    private static final int VENDOR_RESERVATION_VERIFY_ATTEMPTS = 3;
    private static final long FAILURE_TOAST_THROTTLE_MS = 5_000L;
    /** PrivilegedShell suppresses a failed discovery for 60 s; retry just after that window. */
    private static final long RESTORE_RETRY_MS = 65_000L;
    private static final long OVERLAY_PERMISSION_RECHECK_MS = 5_000L;

    public interface StatusListener {
        void onStatus(@NonNull String status, @NonNull String detail);
    }

    private final Context appContext;
    private final Preferences preferences;
    private final ClimatePanelConfigStore configStore;
    private final ScreenReservationController reservationController;
    private final ScreenReservationStateStore reservationStateStore;
    @Nullable private final StatusListener statusListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int dragThreshold;

    @Nullable private Context windowContext;
    @Nullable private WindowManager windowManager;
    private int attachedDisplayId = Integer.MIN_VALUE;

    @Nullable private FrameLayout buttonRoot;
    @Nullable private WindowManager.LayoutParams buttonParams;
    @Nullable private FrameLayout panelRoot;
    @Nullable private WindowManager.LayoutParams panelParams;
    @Nullable private ClimatePanelView climatePanel;
    @Nullable private TextView collapseButton;

    private boolean buttonAttached;
    private boolean panelAttached;
    private boolean compactPanelExpanded;
    private boolean reservedActive;
    private boolean destroyed;
    private boolean compactReservationReconciled;
    private boolean exactStopRestorePending;
    private ReservationBackend reservationBackend = ReservationBackend.NONE;
    private boolean vendorReservationVerificationPending;
    private int vendorReservationVerificationAttempt;
    private int vendorUnreservedAppWidth;
    private int vendorUnreservedAppHeight;
    private int reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    private int reservationGeneration;
    private int requestedEdge;
    private int requestedExtent;
    private int requestedDisplayId;
    private int appliedEdge = -1;
    private int appliedExtent = -1;
    private int appliedDisplayId = -1;
    private long lastFailureToastAt;

    private float downRawX;
    private float downRawY;
    private int downWindowX;
    private int downWindowY;
    private boolean buttonDragging;

    private final Runnable reservationApply = this::beginReservationApply;
    private final Runnable missingPermissionRetry = this::applyPreferences;

    private enum ReservationBackend {
        NONE,
        VENDOR,
        OVERSCAN_PENDING,
        OVERSCAN,
        /** Ordinary overlay plus a HOME-only safe area; this is not a global reservation. */
        LOCAL
    }

    public ClimatePanelOverlayController(@NonNull Context context,
                                         @NonNull Preferences preferences) {
        this(context, preferences, null);
    }

    public ClimatePanelOverlayController(@NonNull Context context,
                                         @NonNull Preferences preferences,
                                         @Nullable StatusListener statusListener) {
        appContext = context.getApplicationContext();
        this.preferences = preferences;
        this.statusListener = statusListener;
        configStore = new ClimatePanelConfigStore(preferences);
        reservationController = new ScreenReservationController(appContext);
        reservationStateStore = new ScreenReservationStateStore(appContext);
        int slop = ViewConfiguration.get(appContext).getScaledTouchSlop();
        dragThreshold = Math.max(slop * 2, dpFrom(appContext, 12));
    }

    /** Re-read all persisted settings. Safe to call for every live-editor update. */
    public void applyPreferences() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::applyPreferences);
            return;
        }
        if (destroyed) return;
        main.removeCallbacks(missingPermissionRetry);
        if (!preferences.climatePanelEnabled.get()) {
            stopAndRestore(null);
            return;
        }
        if (exactStopRestorePending) {
            // The user re-enabled the panel while a failed stop-restore was waiting to retry.
            // Invalidate both its delayed runnable and its completion callback so it cannot stop
            // the newly-active service after eventually reconnecting to the privileged shell.
            exactStopRestorePending = false;
            reservationGeneration++;
            compactReservationReconciled = false;
        }

        int displayId = Math.max(0, preferences.climatePanelDisplayId.get());
        ensureDisplay(displayId);
        if (!canDrawOverlays()) {
            handleMissingOverlayPermission();
            return;
        }
        ensureClimatePanel();
        if (climatePanel != null) climatePanel.reloadConfig();

        if (preferences.climatePanelMode.get() == MODE_RESERVED) {
            compactReservationReconciled = false;
            requestReservedMode(attachedDisplayId);
        } else {
            requestCompactMode();
        }
    }

    int getAttachedDisplayId() {
        return attachedDisplayId;
    }

    /** Remove both windows and restore the complete application work area. */
    public void stopAndRestore(@Nullable Runnable completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> stopAndRestore(completion));
            return;
        }
        reservationGeneration++;
        main.removeCallbacks(reservationApply);
        main.removeCallbacks(missingPermissionRetry);
        compactPanelExpanded = false;
        reservedActive = false;
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        exactStopRestorePending = true;
        removeAllWindows();
        stopClimatePanel();
        int generation = reservationGeneration;
        publishStatus("restoring", "Восстанавливаем полную область экрана");
        attemptExactRestore(generation, completion);
    }

    /**
     * Release local resources. When {@code restoreReservation} is true the privileged rollback is
     * also requested; services should use that path for a deliberate stop.
     */
    public void destroy(boolean restoreReservation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> destroy(restoreReservation));
            return;
        }
        if (destroyed) return;
        destroyed = true;
        exactStopRestorePending = false;
        reservationGeneration++;
        main.removeCallbacksAndMessages(null);
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        removeAllWindows();
        stopClimatePanel();
        if (restoreReservation) reservationController.restore(result -> { });
    }

    private void requestCompactMode() {
        main.removeCallbacks(reservationApply);
        boolean shouldRestore = reservationBackend == ReservationBackend.OVERSCAN
                || reservationStateStore.hasManagedReservation()
                || !compactReservationReconciled;
        if (shouldRestore) reservationGeneration++;
        reservedActive = false;
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        appliedEdge = appliedExtent = appliedDisplayId = -1;
        hidePanelWindow();
        compactPanelExpanded = false;
        if (ensureButtonWindow()) {
            updateButtonGeometry();
            publishStatus("compact", "Климат доступен по плавающей кнопке");
        } else {
            publishStatus("overlay_error", "Не удалось показать плавающую кнопку");
        }
        if (shouldRestore) {
            compactReservationReconciled = true;
            attemptCompactRestore(reservationGeneration);
        }
    }

    private void requestReservedMode(int displayId) {
        requestedDisplayId = displayId;
        requestedEdge = clamp(preferences.climatePanelEdge.get(), 0, 3);
        Point display = displaySize();
        int maximum = isHorizontalEdge(requestedEdge) ? display.y : display.x;
        // WindowManager geometry and wm overscan both use physical pixels. Keep the editor's
        // "px" value exact instead of silently scaling its minimum through screen density.
        requestedExtent = clamp(preferences.climatePanelExtent.get(), 80,
                Math.max(80, maximum));
        reservationGeneration++;
        // The overlay generation only suppresses our callback; this separately stops an older
        // ScreenReservationController generation before it can issue a delayed global mutation.
        reservationController.cancelPending();

        if (reservedActive
                && requestedEdge == appliedEdge
                && requestedExtent == appliedExtent
                && requestedDisplayId == appliedDisplayId) {
            compactPanelExpanded = false;
            if (!showPanelWindow(true)) {
                failCurrentReservationWindow(
                        "Закреплённое окно не создано; включена плавающая кнопка");
                return;
            }
            if (reservationBackend == ReservationBackend.OVERSCAN_PENDING) {
                // applied* still describes the last verified strip. A newer shell request may
                // have been cancelled or may already have mutated WindowManager, so always
                // converge and verify again even when the slider returns to the old geometry.
                main.removeCallbacks(reservationApply);
                main.postDelayed(reservationApply, RESERVATION_DEBOUNCE_MS);
            } else if (vendorReservationVerificationPending) {
                ensureButtonWindow();
                scheduleVendorReservationVerification(reservationGeneration);
            } else {
                removeButtonWindow();
                if (reservationBackend == ReservationBackend.LOCAL
                        && reservationStateStore.hasManagedReservation()) {
                    attemptFallbackRestore(reservationGeneration);
                }
            }
            return;
        }

        // An old overscan journal means the display is already globally modified. Keep using that
        // verified backend until it is restored; attaching an OEM inset on top would reserve the
        // same strip twice after an in-place update from an older build.
        if (reservationBackend != ReservationBackend.OVERSCAN
                && reservationBackend != ReservationBackend.OVERSCAN_PENDING
                && !reservationStateStore.hasManagedReservation()) {
            Display targetDisplay = windowManager == null
                    ? null : windowManager.getDefaultDisplay();
            int nativeType = ClimateReservationWindowPolicy.resolve(
                    appContext, targetDisplay, requestedEdge);
            boolean replacingOwnVendorWindow = reservationBackend == ReservationBackend.VENDOR
                    && panelAttached && panelParams != null && panelParams.type == nativeType;
            if (ClimateReservationWindowPolicy.isVendorType(nativeType)
                    && (replacingOwnVendorWindow
                    || !ClimateReservationWindowPolicy.isOccupied(
                            appContext, targetDisplay, nativeType))) {
                beginVendorReservation(nativeType);
                return;
            }
        }

        beginOverscanReservation();
    }

    /**
     * Tries the ECARX WindowManager bar type first. Unlike an application overlay, that window is
     * part of the vendor inset policy. Merely accepting addView is not enough: Display#getSize()
     * must also report the requested reduction before this backend is considered active.
     */
    private void beginVendorReservation(int nativeType) {
        main.removeCallbacks(reservationApply);
        boolean replacingVendor = reservationBackend == ReservationBackend.VENDOR
                && vendorUnreservedAppWidth > 0 && vendorUnreservedAppHeight > 0;
        int previousUnreservedWidth = vendorUnreservedAppWidth;
        int previousUnreservedHeight = vendorUnreservedAppHeight;
        hidePanelWindow();
        reservationBackend = ReservationBackend.NONE;
        reservedActive = false;
        vendorReservationVerificationPending = true;
        vendorReservationVerificationAttempt = 0;
        reservedWindowType = nativeType;
        appliedEdge = requestedEdge;
        appliedExtent = requestedExtent;
        appliedDisplayId = requestedDisplayId;
        Point before = applicationDisplaySize();
        vendorUnreservedAppWidth = replacingVendor ? previousUnreservedWidth : before.x;
        vendorUnreservedAppHeight = replacingVendor ? previousUnreservedHeight : before.y;
        compactPanelExpanded = false;

        // Keep the compact control until the native work-area change has been measured.
        ensureButtonWindow();
        updateButtonGeometry();
        if (!showPanelWindow(true)) {
            fallbackFromVendorReservation(
                    "OEM-окно резервирования отклонено WindowManager");
            return;
        }
        reservedActive = true;
        reservationBackend = ReservationBackend.VENDOR;
        publishStatus("reserved_vendor_pending",
                "Проверяем рабочую область, созданную системной панелью ECARX");
        scheduleVendorReservationVerification(reservationGeneration);
    }

    private void scheduleVendorReservationVerification(int generation) {
        main.postDelayed(() -> verifyVendorReservation(generation),
                VENDOR_RESERVATION_VERIFY_MS);
    }

    private void verifyVendorReservation(int generation) {
        if (destroyed || generation != reservationGeneration
                || !vendorReservationVerificationPending
                || reservationBackend != ReservationBackend.VENDOR
                || !preferences.climatePanelEnabled.get()
                || preferences.climatePanelMode.get() != MODE_RESERVED) {
            return;
        }
        Point after = applicationDisplaySize();
        if (ClimateReservationAreaPolicy.isReserved(appliedEdge, appliedExtent,
                vendorUnreservedAppWidth, vendorUnreservedAppHeight, after.x, after.y)) {
            vendorReservationVerificationPending = false;
            removeButtonWindow();
            publishStatus("reserved_vendor",
                    "Место зарезервировано системной панелью ECARX");
            return;
        }
        vendorReservationVerificationAttempt++;
        if (vendorReservationVerificationAttempt < VENDOR_RESERVATION_VERIFY_ATTEMPTS) {
            scheduleVendorReservationVerification(generation);
            return;
        }
        fallbackFromVendorReservation(
                "OEM-панель не изменила рабочую область приложений");
    }

    private void fallbackFromVendorReservation(@NonNull String detail) {
        vendorReservationVerificationPending = false;
        reservationBackend = ReservationBackend.NONE;
        reservedActive = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        hidePanelWindow();
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        appliedEdge = appliedExtent = appliedDisplayId = -1;
        publishStatus("reserved_vendor_fallback", detail);
        beginOverscanReservation();
    }

    private void beginOverscanReservation() {
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        // A usable control must remain on screen while the privileged command is being checked.
        // If a previously-confirmed overscan strip is still active, keep its panel visible until
        // the new geometry succeeds to avoid a distracting flash during live slider movement.
        boolean keepVerifiedPanel =
                reservationBackend == ReservationBackend.OVERSCAN && reservedActive;
        if (!keepVerifiedPanel) {
            hidePanelWindow();
            reservedActive = false;
            reservationBackend = ReservationBackend.NONE;
            compactPanelExpanded = false;
            if (!ensureButtonWindow()) {
                publishStatus("overlay_error",
                        "Резервирование не включено: нет безопасного окна панели");
                attemptFallbackRestore(reservationGeneration);
                return;
            }
            updateButtonGeometry();
        }
        reservationBackend = ReservationBackend.OVERSCAN_PENDING;
        publishStatus("reserved_pending", "Проверяем системное резервирование");
        main.removeCallbacks(reservationApply);
        main.postDelayed(reservationApply, RESERVATION_DEBOUNCE_MS);
    }

    private void beginReservationApply() {
        if (destroyed || !preferences.climatePanelEnabled.get()
                || preferences.climatePanelMode.get() != MODE_RESERVED) return;
        final int generation = reservationGeneration;
        final int edge = requestedEdge;
        final int extent = requestedExtent;
        final int displayId = requestedDisplayId;
        reservationController.apply(toReservationEdge(edge), extent, displayId, result -> {
            if (destroyed) return;
            if (generation != reservationGeneration
                    || !preferences.climatePanelEnabled.get()
                    || preferences.climatePanelMode.get() != MODE_RESERVED) {
                // A newer live-editor value arrived while the shell was busy.
                main.removeCallbacks(reservationApply);
                main.postDelayed(reservationApply, RESERVATION_DEBOUNCE_MS);
                return;
            }
            if (result.success) {
                reservedActive = true;
                reservationBackend = ReservationBackend.OVERSCAN;
                vendorReservationVerificationPending = false;
                reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                appliedEdge = edge;
                appliedExtent = extent;
                appliedDisplayId = displayId;
                compactPanelExpanded = false;
                removeButtonWindow();
                if (showPanelWindow(true)) {
                    publishStatus("reserved",
                            "Место под климатическую панель зарезервировано");
                } else {
                    failCurrentReservationWindow(
                            "Закреплённое окно не создано; включена плавающая кнопка");
                }
            } else {
                Log.w(TAG, "Screen reservation failed: " + result.message);
                showReservationFailure(result.message);
                showLocalReservationFallback(result.message);
            }
        });
    }

    /**
     * Keeps the requested fixed panel usable when the firmware exposes neither a working ECARX
     * bar type nor legacy overscan. This only reserves space inside our own HOME activity; other
     * applications still see an ordinary overlay, so the runtime status deliberately says local.
     */
    private void showLocalReservationFallback(@NonNull String globalFailure) {
        reservedActive = false;
        reservationBackend = ReservationBackend.LOCAL;
        vendorReservationVerificationPending = false;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        appliedEdge = requestedEdge;
        appliedExtent = requestedExtent;
        appliedDisplayId = requestedDisplayId;
        compactPanelExpanded = false;
        hidePanelWindow();
        if (!showPanelWindow(true)) {
            reservationBackend = ReservationBackend.NONE;
            appliedEdge = appliedExtent = appliedDisplayId = -1;
            ensureButtonWindow();
            updateButtonGeometry();
            attemptFallbackRestore(reservationGeneration);
            publishStatus("fallback",
                    globalFailure + "; доступна только плавающая кнопка");
            return;
        }
        reservedActive = true;
        removeButtonWindow();
        publishStatus("reserved_local",
                "Глобальное резервирование недоступно: " + globalFailure
                        + ". Лаунчер использует локальную безопасную область");
        // A failed verified overscan operation may have created a recovery journal. Restore it
        // behind the local panel so no partly-applied global strip survives.
        attemptFallbackRestore(reservationGeneration);
    }

    private void failCurrentReservationWindow(@NonNull String detail) {
        reservedActive = false;
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        appliedEdge = appliedExtent = appliedDisplayId = -1;
        hidePanelWindow();
        ensureButtonWindow();
        updateButtonGeometry();
        attemptFallbackRestore(reservationGeneration);
        publishStatus("fallback", detail);
    }

    @NonNull
    private static ScreenReservationController.Edge toReservationEdge(int edge) {
        switch (edge) {
            case 1: return ScreenReservationController.Edge.TOP;
            case 2: return ScreenReservationController.Edge.LEFT;
            case 3: return ScreenReservationController.Edge.RIGHT;
            case 0:
            default: return ScreenReservationController.Edge.BOTTOM;
        }
    }

    private void ensureDisplay(int requestedId) {
        DisplayManager displayManager = appContext.getSystemService(DisplayManager.class);
        Display display = displayManager == null ? null : displayManager.getDisplay(requestedId);
        if (display == null && displayManager != null) {
            display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        int actualId = display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
        if (windowManager != null && attachedDisplayId == actualId) return;

        removeAllWindows();
        stopClimatePanel();
        // The old display may still have a journalled system inset, but its panel window has just
        // been detached. Treat the new display as pending so it gets a usable compact button until
        // ScreenReservationController restores the old display and verifies the new one.
        reservedActive = false;
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        compactPanelExpanded = false;
        compactReservationReconciled = false;
        appliedEdge = appliedExtent = appliedDisplayId = -1;
        attachedDisplayId = actualId;
        if (display != null) {
            windowContext = appContext.createDisplayContext(display);
        } else {
            windowContext = appContext;
        }
        windowManager = windowContext.getSystemService(WindowManager.class);
    }

    private void ensureClimatePanel() {
        if (climatePanel != null || windowContext == null) return;
        climatePanel = new ClimatePanelView(windowContext, CarIntegrations.get(appContext),
                configStore);
        climatePanel.start();
    }

    private void stopClimatePanel() {
        if (climatePanel != null) climatePanel.stop();
        climatePanel = null;
        panelRoot = null;
        collapseButton = null;
    }

    private boolean ensureButtonWindow() {
        if (!canDrawOverlays() || windowManager == null || windowContext == null) return false;
        int size = clamp(preferences.climateButtonSize.get(), 48,
                Math.max(48, Math.min(displaySize().x, displaySize().y)));
        if (buttonRoot == null) buttonRoot = buildButton();
        refreshButtonAppearance();
        if (buttonParams == null) {
            buttonParams = overlayParams(size, size, false);
            buttonParams.x = preferences.climateButtonX.get();
            buttonParams.y = preferences.climateButtonY.get();
        }
        buttonParams.width = size;
        buttonParams.height = size;
        clampButtonPosition(size);
        if (!buttonAttached) {
            try {
                windowManager.addView(buttonRoot, buttonParams);
                buttonAttached = true;
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not add climate button overlay", error);
                buttonAttached = false;
                return false;
            }
        } else {
            updateWindow(buttonRoot, buttonParams);
        }
        return true;
    }

    @NonNull
    private FrameLayout buildButton() {
        FrameLayout root = new FrameLayout(windowContext);
        root.setClickable(true);
        root.setFocusable(false);
        root.setElevation(dp(10));
        root.setContentDescription("Открыть климатическую панель");
        root.setBackground(roundRipple(buttonAccentColor(), 0xEA17202C, 1));

        ImageView icon = new ImageView(windowContext);
        icon.setImageResource(R.drawable.ic_car_climate);
        icon.setColorFilter(buttonAccentColor());
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(icon, new FrameLayout.LayoutParams(match(), match()));

        root.setOnClickListener(view -> toggleCompactPanel());
        root.setOnTouchListener(this::handleButtonTouch);
        return root;
    }

    private void refreshButtonAppearance() {
        if (buttonRoot == null) return;
        int accent = buttonAccentColor();
        buttonRoot.setBackground(roundRipple(accent, 0xEA17202C, 1));
        if (buttonRoot.getChildCount() > 0 && buttonRoot.getChildAt(0) instanceof ImageView) {
            ((ImageView) buttonRoot.getChildAt(0)).setColorFilter(accent);
        }
    }

    private boolean handleButtonTouch(View view, MotionEvent event) {
        if (buttonParams == null || windowManager == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downWindowX = buttonParams.x;
                downWindowY = buttonParams.y;
                buttonDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (preferences.climateButtonLocked.get()) return true;
                int dx = Math.round(event.getRawX() - downRawX);
                int dy = Math.round(event.getRawY() - downRawY);
                if (!buttonDragging && Math.hypot(dx, dy) >= dragThreshold) {
                    buttonDragging = true;
                }
                if (buttonDragging) {
                    buttonParams.x = downWindowX + dx;
                    buttonParams.y = downWindowY + dy;
                    clampButtonPosition(buttonParams.width);
                    updateWindow(view, buttonParams);
                    if (compactPanelExpanded) updateCompactPanelGeometry();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (buttonDragging) {
                    persistButtonPosition();
                } else {
                    view.performClick();
                }
                buttonDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (buttonDragging) persistButtonPosition();
                buttonDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void toggleCompactPanel() {
        if (preferences.climatePanelMode.get() == MODE_RESERVED && reservedActive) return;
        compactPanelExpanded = !compactPanelExpanded;
        if (compactPanelExpanded) showPanelWindow(false);
        else hidePanelWindow();
    }

    private boolean showPanelWindow(boolean reserved) {
        if (!canDrawOverlays() || windowManager == null) return false;
        ensureClimatePanel();
        if (climatePanel == null) return false;
        ensurePanelRoot();
        if (panelRoot == null) return false;

        WindowManager.LayoutParams desired = reserved
                ? reservedPanelParams() : compactPanelParams();
        // Window type is immutable after addView. Edge changes can switch an OEM dock between
        // STATUS_BAR and NAVIGATION_BAR, so detach before applying the new type.
        if (panelAttached && panelParams != null && panelParams.type != desired.type) {
            hidePanelWindow();
        }
        if (panelParams == null) panelParams = desired;
        else copyGeometryAndFlags(desired, panelParams);
        if (collapseButton != null) {
            collapseButton.setVisibility(reserved ? View.GONE : View.VISIBLE);
        }
        if (!panelAttached) {
            try {
                windowManager.addView(panelRoot, panelParams);
                panelAttached = true;
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not add climate panel overlay", error);
                panelAttached = false;
                panelParams = null;
                return false;
            }
        } else {
            updateWindow(panelRoot, panelParams);
        }
        return panelAttached;
    }

    private void ensurePanelRoot() {
        if (panelRoot != null || windowContext == null || climatePanel == null) return;
        FrameLayout root = new FrameLayout(windowContext);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.addView(climatePanel, new FrameLayout.LayoutParams(match(), match()));

        TextView collapse = new TextView(windowContext);
        collapse.setText("×");
        collapse.setTextColor(Color.WHITE);
        collapse.setTextSize(24);
        collapse.setGravity(Gravity.CENTER);
        collapse.setContentDescription("Свернуть климатическую панель");
        collapse.setBackground(roundRipple(Color.WHITE, 0xA81A202B, 0));
        collapse.setOnClickListener(view -> {
            compactPanelExpanded = false;
            hidePanelWindow();
        });
        int closeSize = dp(40);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(closeSize, closeSize,
                Gravity.TOP | Gravity.END);
        closeLp.setMargins(dp(6), dp(6), dp(6), dp(6));
        root.addView(collapse, closeLp);
        panelRoot = root;
        collapseButton = collapse;
    }

    @NonNull
    private WindowManager.LayoutParams compactPanelParams() {
        Point display = displaySize();
        int width = clamp(preferences.climateOverlayWidth.get(), 320, display.x);
        int height = clamp(preferences.climateOverlayHeight.get(), 160, display.y);
        WindowManager.LayoutParams result = overlayParams(width, height, false);
        int size = buttonParams == null ? preferences.climateButtonSize.get() : buttonParams.width;
        int bx = buttonParams == null ? preferences.climateButtonX.get() : buttonParams.x;
        int by = buttonParams == null ? preferences.climateButtonY.get() : buttonParams.y;
        int gap = dp(8);
        int right = bx + size + gap;
        result.x = right + width <= display.x ? right : bx - width - gap;
        result.x = clamp(result.x, 0, Math.max(0, display.x - width));
        result.y = clamp(by + size / 2 - height / 2, 0, Math.max(0, display.y - height));
        return result;
    }

    @NonNull
    private WindowManager.LayoutParams reservedPanelParams() {
        Point display = displaySize();
        int extent = clamp(appliedExtent > 0 ? appliedExtent : requestedExtent, 80,
                isHorizontalEdge(requestedEdge) ? display.y : display.x);
        int edge = appliedEdge >= 0 ? appliedEdge : requestedEdge;
        int width = isHorizontalEdge(edge) ? display.x : extent;
        int height = isHorizontalEdge(edge) ? extent : display.y;
        boolean vendor = ClimateReservationWindowPolicy.isVendorType(reservedWindowType);
        WindowManager.LayoutParams result = overlayParams(width, height,
                reservationBackend == ReservationBackend.OVERSCAN
                        || reservationBackend == ReservationBackend.OVERSCAN_PENDING);
        result.type = reservedWindowType;
        if (vendor) {
            // OEM bar windows must be attached to an edge, not positioned as an unrestricted
            // application overlay. This is what lets ECARX WindowManager derive its inset frame.
            result.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (edge == ClimateReservationWindowPolicy.EDGE_BOTTOM) {
                result.gravity = Gravity.BOTTOM | Gravity.LEFT;
            } else if (edge == ClimateReservationWindowPolicy.EDGE_TOP) {
                result.gravity = Gravity.TOP | Gravity.LEFT;
            } else if (edge == ClimateReservationWindowPolicy.EDGE_LEFT) {
                result.gravity = Gravity.LEFT | Gravity.TOP;
            } else {
                result.gravity = Gravity.RIGHT | Gravity.TOP;
            }
            result.x = 0;
            result.y = 0;
            result.setTitle("Status Widget climate system dock");
        } else if (edge == ClimateReservationWindowPolicy.EDGE_BOTTOM) {
            result.x = 0;
            result.y = Math.max(0, display.y - extent);
        } else if (edge == ClimateReservationWindowPolicy.EDGE_TOP) {
            result.x = 0;
            result.y = 0;
        } else if (edge == ClimateReservationWindowPolicy.EDGE_LEFT) {
            result.x = 0;
            result.y = 0;
        } else {
            result.x = Math.max(0, display.x - extent);
            result.y = 0;
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private WindowManager.LayoutParams overlayParams(int width, int height, boolean overscan) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (overscan) flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN;
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        result.gravity = Gravity.TOP | Gravity.LEFT;
        result.setTitle(overscan ? "Status Widget climate dock" : "Status Widget climate overlay");
        return result;
    }

    private void updateButtonGeometry() {
        if (buttonParams == null || buttonRoot == null) return;
        int size = clamp(preferences.climateButtonSize.get(), 48,
                Math.max(48, Math.min(displaySize().x, displaySize().y)));
        buttonParams.width = size;
        buttonParams.height = size;
        // Preferences are authoritative unless the user is in the middle of a drag.
        if (!buttonDragging) {
            buttonParams.x = preferences.climateButtonX.get();
            buttonParams.y = preferences.climateButtonY.get();
        }
        clampButtonPosition(size);
        updateWindow(buttonRoot, buttonParams);
        if (compactPanelExpanded) updateCompactPanelGeometry();
    }

    private void updateCompactPanelGeometry() {
        if (!panelAttached || panelRoot == null || panelParams == null) return;
        WindowManager.LayoutParams desired = compactPanelParams();
        copyGeometryAndFlags(desired, panelParams);
        updateWindow(panelRoot, panelParams);
    }

    private void clampButtonPosition(int size) {
        if (buttonParams == null) return;
        Point display = displaySize();
        buttonParams.x = clamp(buttonParams.x, 0, Math.max(0, display.x - size));
        buttonParams.y = clamp(buttonParams.y, 0, Math.max(0, display.y - size));
    }

    private void persistButtonPosition() {
        if (buttonParams == null) return;
        preferences.climateButtonX.set(buttonParams.x);
        preferences.climateButtonY.set(buttonParams.y);
    }

    private void hidePanelWindow() {
        if (!panelAttached || panelRoot == null || windowManager == null) return;
        try {
            windowManager.removeView(panelRoot);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not remove climate panel", error);
        }
        panelAttached = false;
        panelParams = null;
    }

    private void removeButtonWindow() {
        if (!buttonAttached || buttonRoot == null || windowManager == null) return;
        try {
            windowManager.removeView(buttonRoot);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not remove climate button", error);
        }
        buttonAttached = false;
        buttonParams = null;
    }

    private void removeAllWindows() {
        hidePanelWindow();
        removeButtonWindow();
        buttonRoot = null;
        buttonParams = null;
    }

    private void updateWindow(@NonNull View view,
                              @NonNull WindowManager.LayoutParams layoutParams) {
        if (windowManager == null || !view.isAttachedToWindow()) return;
        try {
            windowManager.updateViewLayout(view, layoutParams);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not update climate overlay geometry", error);
        }
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private Point displaySize() {
        Point point = new Point();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealSize(point);
        }
        if (point.x <= 0 || point.y <= 0) {
            Context source = windowContext == null ? appContext : windowContext;
            point.x = source.getResources().getDisplayMetrics().widthPixels;
            point.y = source.getResources().getDisplayMetrics().heightPixels;
        }
        return point;
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private Point applicationDisplaySize() {
        Point point = new Point();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getSize(point);
        }
        if (point.x <= 0 || point.y <= 0) {
            Point real = displaySize();
            point.x = real.x;
            point.y = real.y;
        }
        return point;
    }

    private boolean canDrawOverlays() {
        boolean granted = Settings.canDrawOverlays(appContext);
        if (!granted) Log.w(TAG, "Overlay permission is not granted");
        return granted;
    }

    private void handleMissingOverlayPermission() {
        reservationGeneration++;
        int generation = reservationGeneration;
        main.removeCallbacks(reservationApply);
        removeAllWindows();
        stopClimatePanel();
        reservedActive = false;
        reservationBackend = ReservationBackend.NONE;
        vendorReservationVerificationPending = false;
        vendorUnreservedAppWidth = vendorUnreservedAppHeight = 0;
        reservedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        compactPanelExpanded = false;
        publishStatus("waiting_overlay_permission",
                "Нужно разрешение Android «Поверх других приложений»");
        reservationController.restore(result -> {
            if (destroyed || generation != reservationGeneration) return;
            appliedEdge = appliedExtent = appliedDisplayId = -1;
            long delay = result.success ? OVERLAY_PERMISSION_RECHECK_MS : RESTORE_RETRY_MS;
            if (!result.success) {
                publishStatus("restore_retry", result.message);
            }
            main.removeCallbacks(missingPermissionRetry);
            main.postDelayed(missingPermissionRetry, delay);
        });
    }

    private void attemptExactRestore(int generation, @Nullable Runnable completion) {
        if (destroyed || generation != reservationGeneration || !exactStopRestorePending) return;
        reservationController.restore(result -> {
            if (destroyed || generation != reservationGeneration || !exactStopRestorePending) {
                return;
            }
            if (result.success) {
                appliedEdge = appliedExtent = appliedDisplayId = -1;
                exactStopRestorePending = false;
                publishStatus("stopped", "Полная область экрана восстановлена");
                if (completion != null) completion.run();
                return;
            }
            // Do not let the foreground owner disappear while the durable recovery journal says
            // that this app still owns a global overscan change. PrivilegedShell's failed-probe
            // cache expires at 60 seconds, hence the slightly longer retry interval.
            publishStatus("restore_retry", result.message);
            main.postDelayed(() -> attemptExactRestore(generation, completion),
                    RESTORE_RETRY_MS);
        });
    }

    private void attemptCompactRestore(int generation) {
        if (destroyed || generation != reservationGeneration
                || !preferences.climatePanelEnabled.get()
                || preferences.climatePanelMode.get() != MODE_COMPACT) return;
        reservationController.restore(result -> {
            if (destroyed || generation != reservationGeneration
                    || !preferences.climatePanelEnabled.get()
                    || preferences.climatePanelMode.get() != MODE_COMPACT) return;
            if (result.success) {
                appliedEdge = appliedExtent = appliedDisplayId = -1;
                publishStatus("compact", "Климат доступен по плавающей кнопке");
                return;
            }
            publishStatus("restore_retry", result.message);
            main.postDelayed(() -> attemptCompactRestore(generation), RESTORE_RETRY_MS);
        });
    }

    private void attemptFallbackRestore(int generation) {
        if (destroyed || generation != reservationGeneration
                || !preferences.climatePanelEnabled.get()
                || preferences.climatePanelMode.get() != MODE_RESERVED
                || reservationBackend == ReservationBackend.VENDOR
                || reservationBackend == ReservationBackend.OVERSCAN_PENDING
                || reservationBackend == ReservationBackend.OVERSCAN) return;
        reservationController.restore(result -> {
            if (destroyed || generation != reservationGeneration
                    || !preferences.climatePanelEnabled.get()
                    || preferences.climatePanelMode.get() != MODE_RESERVED
                    || reservationBackend == ReservationBackend.VENDOR
                    || reservationBackend == ReservationBackend.OVERSCAN_PENDING
                    || reservationBackend == ReservationBackend.OVERSCAN) return;
            if (result.success) {
                if (reservationBackend == ReservationBackend.LOCAL && reservedActive) {
                    publishStatus("reserved_local",
                            "Глобальное резервирование недоступно; лаунчер использует "
                                    + "локальную безопасную область");
                } else {
                    appliedEdge = appliedExtent = appliedDisplayId = -1;
                    publishStatus("fallback",
                            "Полная область экрана восстановлена; доступна плавающая кнопка");
                }
                return;
            }
            if (reservationBackend == ReservationBackend.LOCAL && reservedActive) {
                publishStatus("reserved_local",
                        "Локальная область активна; повтор восстановления: " + result.message);
            } else {
                publishStatus("restore_retry", result.message);
            }
            main.postDelayed(() -> attemptFallbackRestore(generation), RESTORE_RETRY_MS);
        });
    }

    private void publishStatus(@NonNull String status, @Nullable String detail) {
        if (statusListener != null) {
            statusListener.onStatus(status, detail == null ? "" : detail);
        }
    }

    private int buttonAccentColor() {
        ClimatePanelConfig config = configStore.load();
        try {
            return Color.parseColor(config.accentColor);
        } catch (IllegalArgumentException ignored) {
            return Color.rgb(53, 183, 255);
        }
    }

    @NonNull
    private android.graphics.drawable.Drawable roundRipple(int rippleColor, int fillColor,
                                                            int strokeDp) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(fillColor);
        if (strokeDp > 0) content.setStroke(dp(strokeDp), rippleColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(
                Color.argb(92, Color.red(rippleColor), Color.green(rippleColor),
                        Color.blue(rippleColor))), content, mask);
    }

    private void showReservationFailure(@Nullable String detail) {
        long now = System.currentTimeMillis();
        if (now - lastFailureToastAt < FAILURE_TOAST_THROTTLE_MS) return;
        lastFailureToastAt = now;
        String text = "Резервирование экрана недоступно. Включён режим с кнопкой.";
        if (detail != null && !detail.trim().isEmpty()) Log.i(TAG, detail);
        Toast.makeText(appContext, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        Context source = windowContext == null ? appContext : windowContext;
        return dpFrom(source, value);
    }

    private static int dpFrom(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static boolean isHorizontalEdge(int edge) {
        return edge == 0 || edge == 1;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void copyGeometryAndFlags(@NonNull WindowManager.LayoutParams source,
                                             @NonNull WindowManager.LayoutParams target) {
        target.width = source.width;
        target.height = source.height;
        target.x = source.x;
        target.y = source.y;
        target.gravity = source.gravity;
        target.flags = source.flags;
        target.type = source.type;
        target.format = source.format;
        target.setTitle(source.getTitle());
    }
}
