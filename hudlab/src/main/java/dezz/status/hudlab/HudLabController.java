/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import com.ecarx.xui.adaptapi.ECarXCarProxy;
import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.base.CarFunction;
import com.ecarx.xui.adaptapi.car.vehicle.IHUD;
import com.google.protobuf.nano.MessageNano;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.property.ECarXCarPropertyManagerBase;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.vehicle.ECarXCarProfileManager;
import ecarx.car.hardware.vehicle.ECarXCarProfiletransferManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.ECarXCarVfhudManager;
import ecarx.car.hardware.vehicle.PATypes;
import vendor.ecarx.xma.pa.nano.VendorVehicleHalPAProto;

/**
 * Isolated controller for controlled HUD experiments on the target ECARX head unit.
 *
 * <p>Diagnostic writes are issued only after an explicit button tap. There is no boot receiver,
 * foreground service or background mode enforcement. No path reboots the HUD or disables a
 * system package.</p>
 */
final class HudLabController implements ECarXCarProxy.ECarXCarProxyMethod {
    interface Listener {
        void onUpdated(String snapshot, String eventLog, boolean connected);
    }

    private interface Command {
        String run() throws Exception;
    }

    private static final int OFF = 0;
    private static final int ON = 1;
    private static final int ZONE_ALL = Integer.MIN_VALUE;
    private static final long REFRESH_MS = 1_200L;
    private static final long PROFILE_VISUAL_SCAN_STEP_MS = 3_600L;
    private static final int MAX_LOG_LINES = 180;
    private static final String PREFS = "hud_lab_backups";
    private static final String BACKUP_PROFILE_TRANSFER_MODE = "profile_transfer_mode";
    private static final String BACKUP_VEHICLE_MODEL = "vehicle_model";
    private static final String BACKUP_USER_PROFILE_RAW_PREFIX = "user_profile_raw_before_hud_ar_";
    private static final String BACKUP_USER_PROFILE_HUD_MODE_PREFIX =
            "user_profile_hud_mode_before_";
    private static final String HUD_AR_PROFILE_KEY = "654443008";
    private static final String HUD_MODE_PROFILE_KEY = "251660288";
    private static final int PA_PROFILE_CLOUD_DATA =
            ECarXCarProfileManager.ManagerId_papsetprofileclouddata;
    private static final int CB_PROFILE_CLOUD_DATA =
            ECarXCarProfileManager.ManagerId_cbpsetprofileclouddata;
    private static final int PROFILE_READBACK_ATTEMPTS = 6;
    private static final long PROFILE_READBACK_DELAY_MS = 250L;
    private static final int PROFILE_TRANSFER_MODE_CB = 33278;
    private static final int VEHICLE_AREA_GLOBAL = 1;
    private static final HudDisplayFunction DISPLAY_SAFETY =
            new HudDisplayFunction("SAFETY", IHUD.SETTING_FUNC_HUD_DISPLAY_SAFETY);
    private static final HudDisplayFunction DISPLAY_MEDIA =
            new HudDisplayFunction("MEDIA", IHUD.SETTING_FUNC_HUD_DISPLAY_MEIDA);
    private static final HudDisplayFunction DISPLAY_NAVIGATION =
            new HudDisplayFunction("NAVI", IHUD.SETTING_FUNC_HUD_DISPLAY_NAVI);
    private static final HudDisplayFunction DISPLAY_BT_PHONE =
            new HudDisplayFunction("BTPHONE", IHUD.SETTING_FUNC_HUD_DISPLAY_BTPHONE);
    private static final HudDisplayFunction DISPLAY_DRIVE_ENVIRONMENT =
            new HudDisplayFunction("DRIVE_ENVIRONMENT",
                    IHUD.SETTING_FUNC_HUD_DISPLAY_DRIVE_ENVIRONMENT);
    private static final HudDisplayFunction[] DISPLAY_FUNCTIONS = {
            DISPLAY_DRIVE_ENVIRONMENT,
            DISPLAY_SAFETY,
            DISPLAY_MEDIA,
            DISPLAY_NAVIGATION,
            DISPLAY_BT_PHONE
    };

    private final Context appContext;
    private final SharedPreferences backups;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("hud-lab");
    private final Handler worker;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    /** Profiles whose last successfully transmitted mask is not the safe all-one baseline. */
    private final Set<Integer> dirtyVisualMaskPens = new TreeSet<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final int[] visualFunctions = new int[HudVisualProbePlan.FUNCTION_COUNT];
    private final Runnable profileVisualScanStep = new Runnable() {
        @Override
        public void run() {
            if (closed || !profileVisualScanRunning) return;

            try {
                HudVisualProbePlan.Step probe =
                        HudVisualProbePlan.step(profileVisualScanIndex);
                visualPen = profileVisualScanPen;
                int[] values = probe.values();
                System.arraycopy(values, 0, visualFunctions, 0, visualFunctions.length);
                sendVisualMask();
                profileVisualScanAppliedIndex = probe.functionIndex;
                String step = "01+MASK SAFE mode=" + modeName(profileVisualScanMode)
                        + ", PEN=" + profileVisualScanPen
                        + ", шаг " + (profileVisualScanIndex + 1)
                        + "/" + HudVisualProbePlan.stepCount()
                        + ": " + probe.label;
                lastCommand = step;
                appendLog(step);
                profileVisualScanIndex++;
                if (probe.finalRestore) {
                    profileVisualScanRunning = false;
                    profileVisualScanAppliedIndex = -1;
                    String finished = "01+MASK SAFE завершён: PEN="
                            + profileVisualScanPen + ", все F=1 восстановлены";
                    lastCommand = finished;
                    appendLog(finished);
                    publishSnapshot();
                    return;
                }
                publishSnapshot();
                worker.postDelayed(this, PROFILE_VISUAL_SCAN_STEP_MS);
            } catch (Throwable failure) {
                profileVisualScanRunning = false;
                String restore = restoreVisualMaskBestEffort(profileVisualScanPen);
                String failed = "01+MASK SAFE: ERROR " + shortFailure(failure)
                        + "; " + restore;
                lastCommand = failed;
                appendLog(failed);
                publishSnapshot();
            }
        }
    };
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            publishSnapshot();
            worker.postDelayed(this, REFRESH_MS);
        }
    };

    private ECarXCarProxy proxy;
    private ECarXCar root;
    private ECarXCarVfhudManager vfHud;
    private ECarXCarProfileManager profileManager;
    private ECarXCarProfiletransferManager profileTransfer;
    private CarSignalManager signals;
    private CarFunction carFunction;
    private boolean closed;
    private int visualPen = 1;
    private boolean profileVisualScanRunning;
    private int profileVisualScanMode = -1;
    private int profileVisualScanPen = -1;
    private int profileVisualScanIndex;
    private int profileVisualScanAppliedIndex = -1;
    private String lastCommand = "Команды ещё не отправлялись";
    private String userProfileHudArStatus =
            "не читался; нажмите «ПРОЧИТАТЬ AR» во вкладке DISPLAY_*";
    private String userProfileHudModeStatus =
            "не читался; кнопки FIELD124 читают значение перед записью";

    HudLabController(Context context, Listener listener) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        this.listener = listener;
        backups = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Arrays.fill(visualFunctions, ON);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void start() {
        worker.post(() -> {
            appendLog("Подключение к ecarxcar_service…");
            try {
                proxy = new ECarXCarProxy(appContext, this);
                proxy.initECarXCar();
            } catch (Throwable failure) {
                appendLog("Ошибка подключения: " + shortFailure(failure));
            }
            worker.removeCallbacks(periodicRefresh);
            worker.post(periodicRefresh);
        });
    }

    void close() {
        worker.post(() -> {
            if (closed) return;
            worker.removeCallbacks(profileVisualScanStep);
            restoreAllDirtyVisualMasksBestEffort();
            closed = true;
            worker.removeCallbacksAndMessages(null);
            if (carFunction != null) {
                try {
                    carFunction.onECarXCarServiceDeath();
                } catch (Throwable ignored) {
                    // Best-effort SDK cleanup only.
                }
            }
            carFunction = null;
            vfHud = null;
            profileManager = null;
            profileTransfer = null;
            signals = null;
            root = null;
            ECarXCarProxy current = proxy;
            proxy = null;
            if (current != null) {
                try {
                    current.stopReconnection();
                    current.cleanup();
                } catch (Throwable ignored) {
                    // The process is already closing.
                }
            }
            thread.quitSafely();
        });
    }

    void refreshNow() {
        worker.post(this::publishSnapshot);
    }

    void setDisplayDriveEnvironment(boolean enabled) {
        setDisplayFunction(DISPLAY_DRIVE_ENVIRONMENT, enabled);
    }

    void setDisplaySafety(boolean enabled) {
        setDisplayFunction(DISPLAY_SAFETY, enabled);
    }

    void setDisplayMedia(boolean enabled) {
        setDisplayFunction(DISPLAY_MEDIA, enabled);
    }

    void setDisplayNavigation(boolean enabled) {
        setDisplayFunction(DISPLAY_NAVIGATION, enabled);
    }

    void setDisplayBtPhone(boolean enabled) {
        setDisplayFunction(DISPLAY_BT_PHONE, enabled);
    }

    void setPrimaryDisplayElements(boolean enabled) {
        setDisplayFunctions("DRIVE_ENVIRONMENT + SAFETY", enabled,
                DISPLAY_DRIVE_ENVIRONMENT, DISPLAY_SAFETY);
    }

    void restoreAllDisplayElements() {
        setDisplayFunctions("все пять DISPLAY_*", true, DISPLAY_FUNCTIONS);
    }

    void setSettingsActive(boolean enabled) {
        runCommand("AdaptAPI HUD_ACTIVE=" + value(enabled), () ->
                "accepted=" + setFunctionValue(IHUD.SETTING_FUNC_HUD_ACTIVE, enabled));
    }

    void setVfActive(boolean enabled) {
        runCommand("VFHUD ActvReq=" + value(enabled), () ->
                "result=" + result(requireVfHud().CB_VF_HUD_ActvReq(enabled ? ON : OFF)));
    }

    void setDimActive(boolean enabled) {
        runCommand("DIM HudDispActvReq=" + value(enabled), () ->
                "result=" + result(requireSignals().setHudDispActvReq(enabled ? ON : OFF)));
    }

    void setSettingsAr(boolean enabled) {
        runCommand("AdaptAPI HUD_AR_ENGINE=" + value(enabled), () ->
                "accepted=" + setFunctionValue(IHUD.SETTING_FUNC_HUD_AR_ENGINE, enabled));
    }

    void setVfAr(boolean enabled) {
        runCommand("VFHUD ARActvReq=" + value(enabled), () ->
                "result=" + result(requireVfHud().CB_VF_HUD_ARActvReq(enabled ? ON : OFF)));
    }

    void setAllActivationChannels(boolean enabled) {
        runCommand("Все каналы HUD=" + value(enabled), () -> {
            int requested = enabled ? ON : OFF;
            boolean settings = setFunctionValue(IHUD.SETTING_FUNC_HUD_ACTIVE, enabled);
            boolean settingsAr = setFunctionValue(IHUD.SETTING_FUNC_HUD_AR_ENGINE, enabled);
            ApiResult vf = requireVfHud().CB_VF_HUD_ActvReq(requested);
            ApiResult ar = requireVfHud().CB_VF_HUD_ARActvReq(requested);
            ApiResult dim = requireSignals().setHudDispActvReq(requested);
            return "Settings=" + settings + ", SettingsAR=" + settingsAr
                    + ", VF=" + result(vf) + ", AR=" + result(ar)
                    + ", DIM=" + result(dim);
        });
    }

    void setVfDisplayMode(int mode) {
        runCommand("VFHUD mode=" + modeName(mode), () ->
                "result=" + result(requireVfHud().CB_HUD_DispModSet(mode)));
    }

    void setDimDisplayMode(int mode, int pen) {
        runCommand("DIM mode=" + modeName(mode) + ", PEN=" + pen, () -> {
            VendorVehicleHalPAProto.ProtoHudDispModSetgReq request =
                    new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
            request.hudDispModSetgReqHudDispModSetgReq = mode;
            request.hudDispModSetgReqIdPen = pen;
            requireSignals().setHudDispModSetgReq(request);
            return "raw signal sent";
        });
    }

    void setAllVisualFunctions(int value) {
        int normalized = value == 0 ? OFF : ON;
        runCommand("HUD visual mask: PEN=" + visualPen + ", все=" + normalized, () -> {
            requireNoSafeVisualScan();
            Arrays.fill(visualFunctions, normalized);
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    void setVisualFunction(int index, int value) {
        if (index < 0 || index >= visualFunctions.length) return;
        int normalized = value == 0 ? OFF : ON;
        runCommand(String.format(Locale.ROOT, "HUD visual F%02d=%d", index, normalized), () -> {
            requireNoSafeVisualScan();
            visualFunctions[index] = normalized;
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    void setVisualPen(int pen) {
        int normalized = HudVisualProbePlan.requireProfilePen(pen);
        runCommand("HUD visual PEN=" + normalized, () -> {
            requireNoSafeVisualScan();
            visualPen = normalized;
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    String currentVisualMask() {
        return visualMaskDescription();
    }

    // ---------------------------------------------------------------------
    // Ten dump-derived experiments which were not present in HUD Lab 0.2.
    // Every write is initiated by an explicit UI tap. Profile-backed writes
    // remain transient until the user separately presses the save button.
    // ---------------------------------------------------------------------

    void setProfileTransferMode(int mode, Runnable onSuccess) {
        runCommand("01 ProfileTransfer HUD mode=" + modeName(mode), () -> {
            int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
            rememberProfileTransferModeOnce(readProfileTransferMode());
            ApiResult write = writeProfileTransferSdkMode(validatedMode);
            SystemClock.sleep(220L);
            return "CB33278=" + result(write)
                    + ", PA33937=" + readProfileTransferModeStatus();
        }, onSuccess);
    }

    /**
     * Applies the selected profile HUD mode once through both confirmed vendor routes and commits
     * the current profile settings once. This method never schedules another write.
     */
    void applyPersistentHudMode(int mode, String label) {
        runCommand(label, () -> {
            int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
            int pen = activePen();
            int before = readProfileTransferMode();
            rememberProfileTransferModeOnce(before);

            ApiResult profileWrite = writeProfileTransferSdkMode(validatedMode);
            SystemClock.sleep(180L);

            VendorVehicleHalPAProto.ProtoHudDispModSetgReq request =
                    new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
            request.hudDispModSetgReqHudDispModSetgReq = validatedMode;
            request.hudDispModSetgReqIdPen = pen;
            requireSignals().setHudDispModSetgReq(request);
            SystemClock.sleep(220L);

            ApiResult saveLow = requireSignals().setSaveSetgToMemPrmnt(OFF);
            requireSuccessfulWrite("signal29892 OFF", saveLow);
            SystemClock.sleep(80L);
            ApiResult saveEdge = requireSignals().setSaveSetgToMemPrmnt(ON);
            requireSuccessfulWrite("signal29892 ON", saveEdge);
            SystemClock.sleep(300L);

            int confirmedMode = readProfileTransferMode();
            if (confirmedMode != validatedMode) {
                throw new IllegalStateException("PA33937 не подтвердил mode="
                        + validatedMode + " (получено " + confirmedMode + ")");
            }
            return "OK · один проход · PEN=" + pen
                    + " · CB33278=" + result(profileWrite)
                    + " · DIM30814=[" + validatedMode + "," + pen + "]"
                    + " · SAVE29892=" + result(saveLow) + "→" + result(saveEdge)
                    + " · PA33937=" + confirmedMode
                    + " · PA33906=" + readPaMode();
        });
    }

    /**
     * Sends the invalid/sentinel value -1 directly to CB33278.
     *
     * <p>{@link ECarXCarProfiletransferManager#CB_HudDispModSetgReq(int)} rejects -1 before
     * touching the vehicle property. This deliberately separate diagnostic path bypasses only
     * that Java enum validator; it still uses the same ECARX property service and global area.
     * A valid mode must be available for rollback before the raw write is allowed.</p>
     */
    void setRawProfileTransferMinusOne() {
        runCommand("01 RAW ProfileTransfer HUD mode=-1", () -> {
            int current = readProfileTransferMode();
            rememberProfileTransferModeOnce(current);
            int rollback = savedProfileTransferMode();
            ApiResult write = writeRawProfileTransferMode(
                    HudProfileTransferMode.RAW_INVALID_SENTINEL);
            requireSuccessfulWrite("RAW CB33278", write);
            SystemClock.sleep(300L);
            return "raw setIntProperty(33278, GLOBAL, -1)=" + result(write)
                    + ", rollback=" + rollback
                    + ", PA33937=" + readProfileTransferModeStatus();
        });
    }

    void restoreProfileTransferMode() {
        runCommand("01 ProfileTransfer mode: откат", () -> {
            stopProfileVisualScanInternal();
            int original = savedProfileTransferMode();
            ApiResult write = writeProfileTransferSdkMode(original);
            SystemClock.sleep(220L);
            return "value=" + original + ", result=" + result(write)
                    + ", PA33937=" + readProfileTransferModeStatus();
        });
    }

    /**
     * Combines the confirmed ProfileTransfer mode with the lower DIM visual-function mask.
     *
     * <p>The scan deliberately changes one flag at a time and waits long enough for the
     * physical HUD to redraw. Every exit path attempts to restore the complete all-one mask.</p>
     */
    void startSafeProfileVisualScan(int mode) {
        worker.post(() -> {
            if (closed) return;
            int interruptedPen = profileVisualScanPen;
            boolean interrupted = profileVisualScanRunning;
            stopProfileVisualScanInternal();
            if (interrupted) {
                appendLog("01+MASK SAFE: предыдущий цикл прерван; "
                        + restoreVisualMaskBestEffort(interruptedPen));
            }
            String priorRestore = restoreAllDirtyVisualMasksBestEffort();
            if (!priorRestore.isEmpty()) {
                appendLog("01+MASK SAFE: очистка предыдущих проб: " + priorRestore);
            }
            profileVisualScanPen = -1;
            try {
                int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
                int pen = activePen();
                rememberProfileTransferModeOnce(readProfileTransferMode());
                ApiResult modeWrite = writeProfileTransferSdkMode(validatedMode);
                SystemClock.sleep(240L);

                profileVisualScanMode = validatedMode;
                profileVisualScanPen = pen;
                profileVisualScanIndex = 0;
                profileVisualScanAppliedIndex = -1;
                profileVisualScanRunning = true;

                String started = "01+MASK SAFE старт: mode=" + modeName(mode)
                        + ", PEN=" + profileVisualScanPen
                        + ", all=0 → all=1 → F00…F19 по одному OFF → all=1"
                        + ", CB33278=" + result(modeWrite)
                        + ", PA33937=" + readProfileTransferModeStatus();
                lastCommand = started;
                appendLog(started);
                publishSnapshot();
                profileVisualScanStep.run();
            } catch (Throwable failure) {
                profileVisualScanRunning = false;
                String failed = "01+MASK SAFE старт: ERROR " + shortFailure(failure)
                        + "; " + restoreVisualMaskBestEffort(profileVisualScanPen);
                lastCommand = failed;
                appendLog(failed);
                publishSnapshot();
            }
        });
    }

    /**
     * Applies one complete F00-F19 probe and leaves it active until another explicit command.
     */
    void applyHeldVisualProbe(int mode, int index) {
        if (index < 0 || index >= visualFunctions.length) return;
        worker.post(() -> {
            if (closed) return;
            stopProfileVisualScanInternal();
            String priorRestore = restoreAllDirtyVisualMasksBestEffort();
            if (!priorRestore.isEmpty()) {
                appendLog("01+MASK РУЧНОЙ: очистка предыдущих проб: " + priorRestore);
            }
            try {
                int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
                int pen = activePen();
                rememberProfileTransferModeOnce(readProfileTransferMode());
                ApiResult modeWrite = writeProfileTransferSdkMode(validatedMode);
                SystemClock.sleep(220L);

                profileVisualScanMode = validatedMode;
                profileVisualScanPen = pen;
                profileVisualScanAppliedIndex = index;
                visualPen = profileVisualScanPen;

                // First establish a known complete baseline, then send the complete probe vector.
                Arrays.fill(visualFunctions, ON);
                sendVisualMask();
                SystemClock.sleep(100L);
                Arrays.fill(visualFunctions, ON);
                visualFunctions[index] = OFF;
                sendVisualMask();

                String held = "01+MASK РУЧНОЙ: mode=" + modeName(mode)
                        + ", PEN=" + profileVisualScanPen
                        + ", держим F" + twoDigits(index)
                        + "=0, остальные=1"
                        + ", CB33278=" + result(modeWrite);
                lastCommand = held;
                appendLog(held);
            } catch (Throwable failure) {
                lastCommand = "01+MASK РУЧНОЙ: ERROR " + shortFailure(failure)
                        + "; " + restoreVisualMaskBestEffort(profileVisualScanPen);
                appendLog(lastCommand);
            }
            publishSnapshot();
        });
    }

    /** Applies and holds a complete all-zero or all-one baseline for the active profile. */
    void applyHeldVisualBaseline(int mode, int value) {
        int normalized = value == 0 ? OFF : ON;
        worker.post(() -> {
            if (closed) return;
            stopProfileVisualScanInternal();
            String priorRestore = restoreAllDirtyVisualMasksBestEffort();
            if (!priorRestore.isEmpty()) {
                appendLog("01+MASK BASELINE: очистка предыдущих проб: " + priorRestore);
            }
            try {
                int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
                int pen = activePen();
                rememberProfileTransferModeOnce(readProfileTransferMode());
                ApiResult modeWrite = writeProfileTransferSdkMode(validatedMode);
                SystemClock.sleep(220L);

                profileVisualScanMode = validatedMode;
                profileVisualScanPen = pen;
                profileVisualScanAppliedIndex = -1;
                visualPen = profileVisualScanPen;
                Arrays.fill(visualFunctions, normalized);
                sendVisualMask();

                String held = "01+MASK BASELINE: mode=" + modeName(mode)
                        + ", PEN=" + profileVisualScanPen
                        + ", все F=" + normalized
                        + ", CB33278=" + result(modeWrite);
                lastCommand = held;
                appendLog(held);
            } catch (Throwable failure) {
                lastCommand = "01+MASK BASELINE: ERROR " + shortFailure(failure)
                        + "; " + restoreVisualMaskBestEffort(profileVisualScanPen);
                appendLog(lastCommand);
            }
            publishSnapshot();
        });
    }

    void markProfileVisualScanFound() {
        worker.post(() -> {
            if (closed) return;
            worker.removeCallbacks(profileVisualScanStep);
            boolean wasRunning = profileVisualScanRunning;
            profileVisualScanRunning = false;
            int foundIndex = profileVisualScanAppliedIndex;
            String restore = restoreVisualMaskBestEffort(profileVisualScanPen);
            profileVisualScanAppliedIndex = -1;
            String found = foundIndex < 0
                    ? "01+MASK: активной комбинации пока нет"
                    : "01+MASK ЗАФИКСИРОВАНО: mode=" + modeName(profileVisualScanMode)
                    + ", PEN=" + profileVisualScanPen
                    + ", F" + twoDigits(foundIndex) + "=0"
                    + (wasRunning ? " (перебор остановлен)" : "")
                    + "; " + restore;
            lastCommand = found;
            appendLog(found);
            publishSnapshot();
        });
    }

    void restoreProfileVisualSearch() {
        worker.post(() -> {
            if (closed) return;
            stopProfileVisualScanInternal();
            try {
                String restoredPens = restoreAllDirtyVisualMasksBestEffort();
                int active = activePen();
                Arrays.fill(visualFunctions, ON);
                visualPen = active;
                sendVisualMask();

                int original = savedProfileTransferMode();
                ApiResult modeWrite = writeProfileTransferSdkMode(original);
                SystemClock.sleep(220L);
                profileVisualScanMode = -1;
                profileVisualScanPen = -1;
                profileVisualScanAppliedIndex = -1;
                String restored = "01+MASK восстановление: active PEN → все F=1"
                        + ", mode=" + original + " → " + result(modeWrite)
                        + ", PA33937=" + readProfileTransferModeStatus()
                        + (restoredPens.isEmpty() ? "" : "; ранее: " + restoredPens);
                lastCommand = restored;
                appendLog(restored);
            } catch (Throwable failure) {
                lastCommand = "01+MASK восстановление: ERROR " + shortFailure(failure);
                appendLog(lastCommand);
            }
            publishSnapshot();
        });
    }

    private void stopProfileVisualScanInternal() {
        worker.removeCallbacks(profileVisualScanStep);
        profileVisualScanRunning = false;
    }

    private String restoreVisualMaskBestEffort(int pen) {
        if (!isProfilePen(pen)) {
            return "all=1 не отправлен: активный PEN ещё не был определён";
        }
        try {
            visualPen = pen;
            Arrays.fill(visualFunctions, ON);
            sendVisualMask();
            return "PEN=" + pen + " восстановлен all=1";
        } catch (Throwable restoreFailure) {
            return "ОШИБКА восстановления all=1: " + shortFailure(restoreFailure);
        }
    }

    private String restoreAllDirtyVisualMasksBestEffort() {
        if (dirtyVisualMaskPens.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (int pen : new ArrayList<>(dirtyVisualMaskPens)) {
            if (result.length() > 0) result.append("; ");
            result.append(restoreVisualMaskBestEffort(pen));
        }
        return result.toString();
    }

    private void requireNoSafeVisualScan() {
        if (profileVisualScanRunning) {
            throw new IllegalStateException(
                    "сначала остановите 01+MASK SAFE; параллельная MASK-команда запрещена");
        }
    }

    void setActiveProfileDimMode(int mode) {
        runCommand("02 CEM HUD mode=" + modeName(mode) + " для активного PEN", () -> {
            int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
            int pen = activePen();
            VendorVehicleHalPAProto.ProtoHudDispModSetgReq request =
                    new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
            request.hudDispModSetgReqHudDispModSetgReq = validatedMode;
            request.hudDispModSetgReqIdPen = pen;
            requireSignals().setHudDispModSetgReq(request);
            byte[] protobuf = MessageNano.toByteArray(request);
            appendLog("TX signal30814 / VHAL 0x2170785E · mode="
                    + validatedMode + ", PEN=" + pen + " · protobuf=" + hex(protobuf));
            SystemClock.sleep(250L);
            return "signal30814 sent once, PEN=" + pen
                    + ", PA33906=" + readPaMode();
        });
    }

    void setActiveProfileVisualMask(boolean hidden) {
        runCommand("03 active-PEN visual mask=" + (hidden ? "HIDE" : "SHOW"), () -> {
            requireNoSafeVisualScan();
            visualPen = activePen();
            Arrays.fill(visualFunctions, hidden ? OFF : ON);
            sendVisualMask();
            return "signal30816, " + visualMaskDescription();
        });
    }

    void setDriverHmiBackground(int value) {
        runCommand("04 Driver HMI background=" + value, () -> {
            int pen = activePen();
            VendorVehicleHalPAProto.ProtoDrvrHmiBackGndInfoSetg request =
                    new VendorVehicleHalPAProto.ProtoDrvrHmiBackGndInfoSetg();
            request.drvrHmiBackGndInfoSetgPen = pen;
            request.drvrHmiBackGndInfoSetgSetg = value;
            requireSignals().setDrvrHmiBackGndInfoSetg(request);
            return "signal30805 sent, PEN=" + pen + ", value=" + value;
        });
    }

    void setDriverHmiInterface(int value) {
        runCommand("05 Driver HMI UI=" + value, () -> {
            int pen = activePen();
            VendorVehicleHalPAProto.ProtoDrvrHmiUsrIfSetg request =
                    new VendorVehicleHalPAProto.ProtoDrvrHmiUsrIfSetg();
            request.drvrHmiUsrIfSetgPen = pen;
            request.drvrHmiUsrIfSetgSetg = value;
            requireSignals().setDrvrHmiUsrIfSetg(request);
            return "signal30807 sent, PEN=" + pen + ", value=" + value;
        });
    }

    void setMultimediaInformationMode(int value) {
        runCommand("06 DIM information mode=" + value, () ->
                "signal30792=" + result(requireSignals().setMmedHmiModStd(value)));
    }

    void setIndividualTheme(boolean enabled) {
        runCommand("07 Individual DIM theme=" + value(enabled), () ->
                "signal30785=" + result(requireSignals().setDrvrIndThemeSetg(
                        enabled ? ON : OFF)));
    }

    void setHmiThemeMode(int value) {
        runCommand("08 HMI theme mode=" + value, () ->
                "signal30787=" + result(requireSignals().setHmiThemeModReq(value)));
    }

    void setDriverDisplayTheme(int value) {
        runCommand("09 Driver display theme=" + value, () -> {
            int pen = activePen();
            VendorVehicleHalPAProto.ProtoDrvrDispSetg request =
                    new VendorVehicleHalPAProto.ProtoDrvrDispSetg();
            request.drvrDispSetgPen = pen;
            request.drvrDispSetgSts = value;
            requireSignals().setDrvrDispSetg(request);
            return "signal30803 sent, PEN=" + pen
                    + ", feedback30873=" + readDriverDisplayTheme();
        });
    }

    void setVehicleModelClear(boolean enabled) {
        runCommand("10 Vehicle model clear=" + value(enabled), () -> {
            ECarXCarProfiletransferManager manager = requireProfileTransfer();
            rememberIntOnce(BACKUP_VEHICLE_MODEL, readVehicleModelClear());
            return "CB33284=" + result(manager.CB_VehMdlClrReq(enabled ? ON : OFF))
                    + ", feedback33943=" + readVehicleModelClear();
        });
    }

    void restoreVehicleModelClear() {
        runCommand("10 Vehicle model clear: откат", () -> {
            int original = backups.getInt(BACKUP_VEHICLE_MODEL, OFF);
            return "value=" + original + ", result="
                    + result(requireProfileTransfer().CB_VehMdlClrReq(original));
        });
    }

    void reloadActiveProfile() {
        runCommand("Перезагрузить активный профиль (откат transient-настроек)", () -> {
            int profile = activeProfile();
            int pen = activePen();
            ApiResult profileResult = requireProfileManager()
                    .CB_PSET_RequestActiveProfile(profile);
            ApiResult penResult = requireSignals().setProfChg(pen);
            return "profile=" + profile + " → " + result(profileResult)
                    + ", PEN=" + pen + " → " + result(penResult);
        });
    }

    void persistCurrentProfileSettings() {
        runCommand("Сохранить текущие профильные настройки", () -> {
            ApiResult low = requireSignals().setSaveSetgToMemPrmnt(OFF);
            SystemClock.sleep(80L);
            ApiResult edge = requireSignals().setSaveSetgToMemPrmnt(ON);
            return "signal29892 0→1: " + result(low) + " / " + result(edge);
        });
    }

    void pulseProfileTransferApply() {
        runCommand("Применить ProfileTransfer", () -> {
            ECarXCarProfiletransferManager manager = requireProfileTransfer();
            ApiResult high = manager.CB_Profile_Transfer_Reboot(ON);
            SystemClock.sleep(80L);
            ApiResult low = manager.CB_Profile_Transfer_Reboot(OFF);
            return "CB33274 1→0: " + result(high) + " / " + result(low);
        });
    }

    /**
     * Replays the old stock Settings "AR mode" switch through raw ProfileCloudData.
     *
     * <p>The public profile adapter exposes only 85 of the vendor protobuf's 150 fields, so even
     * its complete JSON can clear hidden vehicle settings. Read PA33873 as raw bytes, replace only
     * protobuf field 111 and send the complete untouched stream through CB33264.</p>
     */
    void setUserProfileHudAr(boolean enabled) {
        runCommand("UserProfile HUD AR=" + value(enabled), () -> {
            int profileId = requireActiveProfileId();
            byte[] completeProfile = requireRawProfileCloudDataForProfile(profileId);
            int before = HudProfileWirePatcher.readHudAr(completeProfile);
            rememberUserProfileRawOnce(profileId, completeProfile);
            String readback = writeAndConfirmRawHudAr(
                    profileId, completeProfile, enabled);
            userProfileHudArStatus = "profile=" + profileId + ", value="
                    + (enabled ? 1 : 0) + ", " + readback;
            return "profile=" + profileId + ", key " + HUD_AR_PROFILE_KEY
                    + ": " + before + "→" + (enabled ? 1 : 0)
                    + ", readback=" + readback;
        });
    }

    void refreshUserProfileHudAr() {
        runCommand("UserProfile HUD AR: чтение", () -> {
            int profileId = requireActiveProfileId();
            byte[] raw = requireRawProfileCloudDataForProfile(profileId);
            userProfileHudArStatus = "profile=" + profileId + ", value="
                    + HudProfileWirePatcher.readHudAr(raw) + ", rawBytes=" + raw.length;
            return userProfileHudArStatus;
        });
    }

    void restoreUserProfileHudAr() {
        runCommand("UserProfile HUD AR: точный откат", () -> {
            int profileId = requireActiveProfileId();
            String originalBase64 = backups.getString(userProfileBackupKey(profileId), null);
            if (originalBase64 == null) {
                throw new IllegalStateException(
                        "для активного профиля " + profileId + " резервная копия ещё не создана");
            }
            byte[] original = Base64.decode(originalBase64, Base64.NO_WRAP);
            int originalValue = HudProfileWirePatcher.readHudAr(original);
            // Overlay only the original AR bit on a newly read raw profile so unrelated settings
            // changed after the backup are retained.
            String readback = writeAndConfirmRawHudAr(
                    profileId,
                    requireRawProfileCloudDataForProfile(profileId),
                    originalValue == ON);
            backups.edit().remove(userProfileBackupKey(profileId)).apply();
            userProfileHudArStatus = "profile=" + profileId + ", value="
                    + originalValue + ", " + readback;
            return "profile=" + profileId + ", восстановлен исходный AR="
                    + originalValue + " поверх свежего raw-профиля, readback=" + readback;
        });
    }

    /**
     * Writes the ECARX user-profile HUD mode without rebuilding the profile protobuf.
     *
     * <p>UserProfile maps custom id 251660288 (CAR_FUNC_HUD_MODE) to
     * Profileclouddata.profiletransferbyte3, protobuf field 124. Only that field's varint is
     * replaced; all other bytes are retained exactly and PA33873 must confirm the new value.</p>
     */
    void setUserProfileHudMode(int mode) {
        runCommand("UserProfile FIELD124 HUD mode=" + modeName(mode), () -> {
            int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
            int profileId = requireActiveProfileId();
            byte[] completeProfile = requireRawProfileCloudDataForProfile(profileId);
            int before = HudProfileWirePatcher.readHudMode(completeProfile);
            rememberUserProfileHudModeOnce(profileId, before);
            String readback = writeAndConfirmRawHudMode(
                    profileId, completeProfile, validatedMode);
            userProfileHudModeStatus = "profile=" + profileId + ", field124="
                    + validatedMode + ", " + readback;
            return "profile=" + profileId + ", customId " + HUD_MODE_PROFILE_KEY
                    + ", field124: " + before + "→" + validatedMode
                    + ", readback=" + readback;
        });
    }

    void refreshUserProfileHudMode() {
        runCommand("UserProfile FIELD124 HUD mode: чтение", () -> {
            int profileId = requireActiveProfileId();
            byte[] raw = requireRawProfileCloudDataForProfile(profileId);
            userProfileHudModeStatus = "profile=" + profileId + ", field124="
                    + HudProfileWirePatcher.readHudMode(raw) + ", rawBytes=" + raw.length;
            return userProfileHudModeStatus;
        });
    }

    void restoreUserProfileHudMode() {
        runCommand("UserProfile FIELD124 HUD mode: точный откат", () -> {
            int profileId = requireActiveProfileId();
            String key = userProfileHudModeBackupKey(profileId);
            if (!backups.contains(key)) {
                throw new IllegalStateException(
                        "для активного профиля " + profileId
                                + " исходное поле 124 ещё не сохранено");
            }
            int originalValue = backups.getInt(key, -1);
            int validatedMode = HudProfileTransferMode.requireSdkMode(originalValue);
            String readback = writeAndConfirmRawHudMode(
                    profileId,
                    requireRawProfileCloudDataForProfile(profileId),
                    validatedMode);
            backups.edit().remove(key).apply();
            userProfileHudModeStatus = "profile=" + profileId + ", field124="
                    + validatedMode + ", " + readback;
            return "profile=" + profileId + ", восстановлен исходный field124="
                    + validatedMode + " поверх свежего raw-профиля, readback=" + readback;
        });
    }

    private void setDisplayFunction(HudDisplayFunction function, boolean enabled) {
        setDisplayFunctions(function.label, enabled, function);
    }

    private void setDisplayFunctions(String label, boolean enabled,
                                     HudDisplayFunction... functions) {
        runCommand("HUD DISPLAY " + label + "=" + value(enabled), () -> {
            StringBuilder result = new StringBuilder();
            for (HudDisplayFunction function : functions) {
                if (result.length() > 0) result.append(", ");
                result.append(function.label).append('=')
                        .append(setFunctionValue(function.functionId, enabled));
            }
            return "accepted[" + result + ']';
        });
    }

    @Override
    public void onECarXCarServiceConnected(ECarXCar connectedRoot,
                                            CarSignalManager connectedSignals) {
        worker.post(() -> acceptConnection(connectedRoot, connectedSignals));
    }

    @Override
    public void onECarXCarServiceDeath() {
        worker.post(() -> {
            boolean interruptedScan = profileVisualScanRunning;
            stopProfileVisualScanInternal();
            if (carFunction != null) {
                try {
                    carFunction.onECarXCarServiceDeath();
                } catch (Throwable ignored) {
                    // The SDK will reconnect its proxy.
                }
            }
            root = null;
            vfHud = null;
            profileManager = null;
            profileTransfer = null;
            signals = null;
            carFunction = null;
            appendLog("ecarxcar_service отключён; ждём переподключения"
                    + (interruptedScan
                    ? "; SAFE-цикл остановлен, all=1 будет восстановлен после подключения"
                    : ""));
            publishSnapshot();
        });
    }

    private void acceptConnection(ECarXCar connectedRoot,
                                  CarSignalManager connectedSignals) {
        if (closed || connectedRoot == null) return;
        try {
            Object publicAttributes = connectedRoot.getCarManager(ECarXCar.PA_SERVICE);
            if (!(publicAttributes instanceof ECarXCarSetManager)) {
                throw new IllegalStateException("PA_SERVICE не вернул ECarXCarSetManager");
            }
            root = connectedRoot;
            signals = connectedSignals;
            ECarXCarSetManager setManager = (ECarXCarSetManager) publicAttributes;
            vfHud = setManager.getECarXCarVfhudManager();
            profileManager = setManager.getECarXCarProfileManager();
            profileTransfer = setManager.getECarXCarProfiletransferManager();
            CarFunction functions = new CarFunction(appContext);
            functions.initCarSignalManager(connectedRoot, connectedSignals);
            carFunction = functions;
            appendLog("Подключено: VFHUD + ProfileTransfer + Profile + CEM/DIM");
            String recoveredMasks = restoreAllDirtyVisualMasksBestEffort();
            if (!recoveredMasks.isEmpty()) {
                appendLog("Аварийное восстановление после переподключения: "
                        + recoveredMasks);
            }
        } catch (Throwable failure) {
            root = null;
            vfHud = null;
            profileManager = null;
            profileTransfer = null;
            signals = null;
            carFunction = null;
            appendLog("Ошибка инициализации SDK: " + shortFailure(failure));
        }
        publishSnapshot();
    }

    private void runCommand(String title, Command command) {
        runCommand(title, command, null);
    }

    private void runCommand(String title, Command command, Runnable onSuccess) {
        worker.post(() -> {
            if (closed) return;
            String outcome;
            boolean succeeded = false;
            try {
                // A visual probe is meaningful only if no other HUD write can change the
                // active mode, profile, activation channel, theme or mask between its steps.
                requireNoSafeVisualScan();
                outcome = command.run();
                succeeded = true;
            } catch (Throwable failure) {
                outcome = "ERROR " + shortFailure(failure);
            }
            lastCommand = title + " → " + outcome;
            appendLog(lastCommand);
            if (succeeded && onSuccess != null && !closed) {
                main.post(onSuccess);
            }
            publishSnapshot();
            worker.postDelayed(this::publishSnapshot, 300L);
        });
    }

    private boolean setFunctionValue(int functionId, boolean enabled) {
        CarFunction functions = requireCarFunction();
        int requested = enabled ? ON : OFF;
        try {
            return functions.setFunctionValue(functionId, ZONE_ALL, requested);
        } catch (Throwable zonedFailure) {
            return functions.setFunctionValue(functionId, requested);
        }
    }

    private void sendVisualMask() throws Exception {
        HudVisualProbePlan.requireProfilePen(visualPen);
        VendorVehicleHalPAProto.ProtoHudVisFctSetgReq request =
                new VendorVehicleHalPAProto.ProtoHudVisFctSetgReq();
        for (int index = 0; index < visualFunctions.length; index++) {
            Field field = request.getClass().getField(String.format(
                    Locale.ROOT, "hudVisFctSetgReqHudFct%02d", index));
            field.setInt(request, visualFunctions[index]);
        }
        request.hudVisFctSetgReqPen = visualPen;
        byte[] protobuf = MessageNano.toByteArray(request);
        requireSignals().setHudVisFctSetgReq(request);
        if (isAllOneVisualMask()) {
            dirtyVisualMaskPens.remove(visualPen);
        } else {
            dirtyVisualMaskPens.add(visualPen);
        }
        appendLog("TX signal30816 / VHAL 0x21707860 · "
                + visualMaskDescription() + " · protobuf=" + hex(protobuf));
    }

    private boolean isAllOneVisualMask() {
        for (int value : visualFunctions) {
            if (value != ON) return false;
        }
        return true;
    }

    private void publishSnapshot() {
        if (closed) return;
        boolean connected = root != null && vfHud != null && signals != null
                && profileManager != null && profileTransfer != null;
        StringBuilder out = new StringBuilder(1_200);
        out.append("СОЕДИНЕНИЕ: ").append(connected ? "ГОТОВО" : "ОЖИДАНИЕ").append('\n');
        out.append("Последняя команда: ").append(lastCommand).append("\n\n");

        out.append("Dump-derived profile/DIM state\n");
        out.append("  Active profile / PEN: ").append(readActiveProfile())
                .append(" / ").append(readActivePen()).append('\n');
        out.append("  ProfileTransfer HUD mode CB33278/PA33937: ")
                .append(readProfileTransferModeStatus()).append('\n');
        out.append("  Фоновый автоповтор: УДАЛЁН в 0.21\n");
        out.append("  UserProfile HUD AR key ").append(HUD_AR_PROFILE_KEY).append(": ")
                .append(userProfileHudArStatus).append('\n');
        out.append("  UserProfile HUD mode key ").append(HUD_MODE_PROFILE_KEY)
                .append(" / field124: ").append(userProfileHudModeStatus).append('\n');
        out.append("  Vehicle model clear CB33284/PA33943: ")
                .append(readVehicleModelClear()).append('\n');
        out.append("  Driver display feedback 30873: ")
                .append(readDriverDisplayTheme()).append("\n\n");

        out.append("Поиск 01: ").append(profileVisualScanDescription()).append("\n\n");

        out.append("AdaptAPI Settings\n");
        out.append("  HUD_ACTIVE 0x").append(Integer.toHexString(IHUD.SETTING_FUNC_HUD_ACTIVE))
                .append(": ").append(readFunction(IHUD.SETTING_FUNC_HUD_ACTIVE)).append('\n');
        out.append("  HUD_AR_ENGINE 0x").append(Integer.toHexString(IHUD.SETTING_FUNC_HUD_AR_ENGINE))
                .append(": ").append(readFunction(IHUD.SETTING_FUNC_HUD_AR_ENGINE)).append("\n\n");

        out.append("Selective HUD content (новый путь)\n");
        for (HudDisplayFunction function : DISPLAY_FUNCTIONS) {
            out.append("  ").append(function.label).append(" 0x")
                    .append(Integer.toHexString(function.functionId)).append(": ")
                    .append(readFunction(function.functionId)).append('\n');
        }
        out.append('\n');

        out.append("VFHUD public attributes\n");
        out.append("  PA_VF_HUD_ActvSts: ").append(readPaHudActive()).append('\n');
        out.append("  PA_VF_HUD_ARActvSts: ").append(readPaArActive()).append('\n');
        out.append("  PA_HUD_DispModSet: ").append(readPaMode()).append("\n\n");

        out.append("Direct IHU → DIM\n");
        out.append("  HudActvReq: ").append(readSignal(SignalRead.HUD_REQUEST)).append('\n');
        out.append("  HudActvSts: ").append(readSignal(SignalRead.HUD_ACTIVE_STATUS)).append('\n');
        out.append("  HudSts: ").append(readSignal(SignalRead.HUD_STATUS)).append('\n');
        out.append("  DIM priority/resource: ")
                .append(readSignal(SignalRead.DIM_PRIORITY)).append(" / ")
                .append(readSignal(SignalRead.DIM_RESOURCE)).append("\n\n");
        out.append("Сырые данные скорости (только чтение)\n");
        out.append("  extended / unit / value: ")
                .append(readSignal(SignalRead.SPEED_EXTENDED)).append(" / ")
                .append(readSignal(SignalRead.SPEED_UNIT)).append(" / ")
                .append(readSignal(SignalRead.SPEED_VALUE)).append("\n\n");
        out.append("Локальная visual mask: ").append(visualMaskDescription()).append('\n');

        String snapshot = out.toString();
        StringBuilder history = new StringBuilder();
        for (String line : logLines) {
            history.append(line).append('\n');
        }
        String eventLog = history.toString();
        main.post(() -> {
            if (!closed) listener.onUpdated(snapshot, eventLog, connected);
        });
    }

    private String readFunction(int functionId) {
        CarFunction functions = carFunction;
        if (functions == null) return "—";
        try {
            FunctionStatus status;
            try {
                status = functions.isFunctionSupported(functionId, ZONE_ALL);
            } catch (Throwable zonedFailure) {
                status = functions.isFunctionSupported(functionId);
            }
            String values;
            try {
                values = Arrays.toString(functions.getSupportedFunctionValue(functionId, ZONE_ALL));
            } catch (Throwable zonedFailure) {
                try {
                    values = Arrays.toString(functions.getSupportedFunctionValue(functionId));
                } catch (Throwable ignored) {
                    values = "?";
                }
            }
            String current;
            try {
                current = Integer.toString(functions.getFunctionValue(functionId, ZONE_ALL));
            } catch (Throwable zonedFailure) {
                try {
                    current = Integer.toString(functions.getFunctionValue(functionId));
                } catch (Throwable ignored) {
                    current = "?";
                }
            }
            return "support=" + status + ", value=" + current + ", allowed=" + values;
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private String readPaHudActive() {
        ECarXCarVfhudManager manager = vfHud;
        if (manager == null) return "—";
        try {
            PATypes.PA_VF_HUD_ActvSts value = manager.getPA_VF_HUD_ActvSts();
            return value == null ? "null" : value.toString();
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private String readPaArActive() {
        ECarXCarVfhudManager manager = vfHud;
        if (manager == null) return "—";
        try {
            PATypes.PA_VF_HUD_ARActvSts value = manager.getPA_VF_HUD_ARActvSts();
            return value == null ? "null" : value.toString();
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private String readPaMode() {
        ECarXCarVfhudManager manager = vfHud;
        if (manager == null) return "—";
        try {
            PATypes.PA_HUD_DispModSet value = manager.getPA_HUD_DispModSet();
            return value == null ? "null" : value.toString();
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private int activePen() throws Exception {
        int signalPen = -1;
        Throwable signalFailure = null;
        try {
            signalPen = requireSignals().getProfPenSts1();
        } catch (Throwable failure) {
            signalFailure = failure;
        }
        if (isProfilePen(signalPen)) return signalPen;

        int profilePen;
        try {
            profilePen = activeProfile();
        } catch (Throwable profileFailure) {
            String signalDetail = signalFailure == null
                    ? Integer.toString(signalPen)
                    : shortFailure(signalFailure);
            throw new IllegalStateException("активный PEN недоступен: ProfPenSts1="
                    + signalDetail + ", PA33845=" + shortFailure(profileFailure),
                    profileFailure);
        }
        if (isProfilePen(profilePen)) return profilePen;

        String signalDetail = signalFailure == null
                ? Integer.toString(signalPen)
                : shortFailure(signalFailure);
        throw new IllegalStateException("активный PEN недоступен: ProfPenSts1="
                + signalDetail + ", PA33845=" + profilePen
                + " (ожидалось 0…13)");
    }

    private static boolean isProfilePen(int value) {
        return value >= HudVisualProbePlan.MIN_PROFILE_PEN
                && value <= HudVisualProbePlan.MAX_PROFILE_PEN;
    }

    private int activeProfile() throws Exception {
        PATypes.PA_PSET_ActiveProfile value =
                requireProfileManager().getPA_PSET_ActiveProfile();
        if (value == null) throw new IllegalStateException("PA33845=null");
        return value.getData();
    }

    private String readActivePen() {
        try {
            int signalPen;
            try {
                signalPen = requireSignals().getProfPenSts1();
            } catch (Throwable failure) {
                int profilePen = activeProfile();
                return profilePen + " (PA33845 fallback; ProfPenSts1="
                        + shortFailure(failure) + ")";
            }
            if (isProfilePen(signalPen)) return signalPen + " (ProfPenSts1)";
            int profilePen = activeProfile();
            if (isProfilePen(profilePen)) {
                return profilePen + " (PA33845 fallback; ProfPenSts1="
                        + signalPen + ")";
            }
            return "ERROR ProfPenSts1=" + signalPen + ", PA33845=" + profilePen;
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private String readActiveProfile() {
        try {
            return Integer.toString(activeProfile());
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private int readProfileTransferMode() {
        try {
            PATypes.PA_HudDispModSetgReq value =
                    requireProfileTransfer().getPA_HudDispModSetgReq();
            return value == null ? -1 : value.getData();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String readProfileTransferModeStatus() {
        try {
            PATypes.PA_HudDispModSetgReq value =
                    requireProfileTransfer().getPA_HudDispModSetgReq();
            return value == null ? "null" : value.toString();
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private ApiResult writeProfileTransferSdkMode(int mode) {
        int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
        ApiResult write = requireProfileTransfer().CB_HudDispModSetgReq(validatedMode);
        requireSuccessfulWrite("CB33278", write);
        // Some cars report PA33937 as unavailable even though the command visibly changes HUD.
        // In that case this known-good mode is still a safe rollback point for the RAW probe.
        rememberProfileTransferModeOnce(validatedMode);
        return write;
    }

    private ApiResult writeRawProfileTransferMode(int mode) throws Exception {
        ECarXCarProfiletransferManager manager = requireProfileTransfer();
        Field managerField = null;
        Class<?> owner = manager.getClass();
        while (owner != null && managerField == null) {
            try {
                managerField = owner.getDeclaredField("mMgr");
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            }
        }
        if (managerField == null) {
            throw new IllegalStateException("поле ECARX mMgr не найдено");
        }
        managerField.setAccessible(true);
        Object rawManager = managerField.get(manager);
        if (!(rawManager instanceof ECarXCarPropertyManagerBase)) {
            throw new IllegalStateException("ECARX mMgr имеет неожиданный тип "
                    + (rawManager == null ? "null" : rawManager.getClass().getName()));
        }
        return ((ECarXCarPropertyManagerBase) rawManager).setIntProperty(
                PROFILE_TRANSFER_MODE_CB, VEHICLE_AREA_GLOBAL, mode);
    }

    private static void requireSuccessfulWrite(String label, ApiResult write) {
        if (write != ApiResult.SUCCEED) {
            throw new IllegalStateException(label + " вернул " + result(write));
        }
    }

    private void rememberProfileTransferModeOnce(int value) {
        if (!HudProfileTransferMode.isSdkMode(value)
                || backups.contains(BACKUP_PROFILE_TRANSFER_MODE)) {
            return;
        }
        backups.edit().putInt(BACKUP_PROFILE_TRANSFER_MODE, value).apply();
    }

    private int savedProfileTransferMode() {
        if (!backups.contains(BACKUP_PROFILE_TRANSFER_MODE)) {
            throw new IllegalStateException(
                    "нет валидного исходного режима 0…3 — RAW-команда и откат запрещены");
        }
        int value = backups.getInt(BACKUP_PROFILE_TRANSFER_MODE, -1);
        if (!HudProfileTransferMode.isSdkMode(value)) {
            throw new IllegalStateException(
                    "резервная копия режима невалидна: " + value);
        }
        return value;
    }

    private int readVehicleModelClear() {
        try {
            PATypes.PA_VehMdlClrReq value =
                    requireProfileTransfer().getPA_VehMdlClrReq();
            return value == null ? -1 : value.getData();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String readDriverDisplayTheme() {
        try {
            return Integer.toString(requireSignals().getDrvrDispSetgStsSyncn());
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private void rememberIntOnce(String key, int value) {
        if (value < 0 || backups.contains(key)) return;
        backups.edit().putInt(key, value).apply();
    }

    private int requireActiveProfileId() throws Exception {
        int profileId = activeProfile();
        if (!isProfilePen(profileId)) {
            throw new IllegalStateException(
                    "PA33845 вернул невалидный активный профиль " + profileId
                            + " (ожидалось 0…13)");
        }
        return profileId;
    }

    private byte[] requireRawProfileCloudData() {
        byte[] raw = requireProfileManager().getByteCBValueForUt(PA_PROFILE_CLOUD_DATA);
        if (raw == null || raw.length == 0) {
            throw new IllegalStateException(
                    "PA33873 ProfileCloudData пуст; дождитесь загрузки профиля");
        }
        // A complete scan rejects malformed/truncated data before any write is attempted.
        HudProfileWirePatcher.readHudAr(raw);
        return raw.clone();
    }

    private byte[] requireRawProfileCloudDataForProfile(int expectedProfileId)
            throws Exception {
        requireExpectedActiveProfileId(expectedProfileId);
        byte[] raw = requireRawProfileCloudData();
        requireExpectedActiveProfileId(expectedProfileId);
        return raw;
    }

    private void requireExpectedActiveProfileId(int expectedProfileId) throws Exception {
        int actualProfileId = requireActiveProfileId();
        if (actualProfileId != expectedProfileId) {
            throw new IllegalStateException(
                    "активный профиль PA33845 изменился: ожидался " + expectedProfileId
                            + ", получен " + actualProfileId + "; запись отменена");
        }
    }

    private String writeAndConfirmRawHudAr(int expectedProfileId,
                                           byte[] completeProfile, boolean enabled)
            throws Exception {
        byte[] patched = HudProfileWirePatcher.patchHudAr(completeProfile, enabled);
        if (!HudProfileWirePatcher.isExactPatch(completeProfile, patched, enabled)) {
            throw new IllegalStateException(
                    "проверка точечного изменения поля vfhudbyte0 не пройдена");
        }
        requireExpectedActiveProfileId(expectedProfileId);
        requireProfileManager().setbytesPropertyForUt(CB_PROFILE_CLOUD_DATA, patched);
        int expected = enabled ? ON : OFF;
        String last = "нет данных";
        for (int attempt = 0; attempt < PROFILE_READBACK_ATTEMPTS; attempt++) {
            SystemClock.sleep(PROFILE_READBACK_DELAY_MS);
            requireExpectedActiveProfileId(expectedProfileId);
            try {
                byte[] readback = requireRawProfileCloudData();
                requireExpectedActiveProfileId(expectedProfileId);
                int actual = HudProfileWirePatcher.readHudAr(readback);
                last = "value=" + actual + ", bytes=" + readback.length;
                if (actual == expected) return last;
            } catch (Throwable failure) {
                last = shortFailure(failure);
            }
        }
        throw new IllegalStateException(
                "CB33264 отправлен, но PA33873 не подтвердил " + expected + ": " + last);
    }

    private String writeAndConfirmRawHudMode(int expectedProfileId,
                                             byte[] completeProfile, int mode)
            throws Exception {
        int expected = HudProfileTransferMode.requireSdkMode(mode);
        byte[] patched = HudProfileWirePatcher.patchHudMode(completeProfile, expected);
        if (!HudProfileWirePatcher.isExactHudModePatch(completeProfile, patched, expected)) {
            throw new IllegalStateException(
                    "проверка точечного изменения profiletransferbyte3/field124 не пройдена");
        }
        requireExpectedActiveProfileId(expectedProfileId);
        requireProfileManager().setbytesPropertyForUt(CB_PROFILE_CLOUD_DATA, patched);
        String last = "нет данных";
        for (int attempt = 0; attempt < PROFILE_READBACK_ATTEMPTS; attempt++) {
            SystemClock.sleep(PROFILE_READBACK_DELAY_MS);
            requireExpectedActiveProfileId(expectedProfileId);
            try {
                byte[] readback = requireRawProfileCloudData();
                requireExpectedActiveProfileId(expectedProfileId);
                int actual = HudProfileWirePatcher.readHudMode(readback);
                last = "field124=" + actual + ", bytes=" + readback.length;
                if (actual == expected) return last;
            } catch (Throwable failure) {
                last = shortFailure(failure);
            }
        }
        throw new IllegalStateException(
                "CB33264 отправлен, но PA33873 не подтвердил field124="
                        + expected + ": " + last);
    }

    private void rememberUserProfileRawOnce(int profileId, byte[] raw) {
        String key = userProfileBackupKey(profileId);
        if (backups.contains(key)) return;
        String encoded = Base64.encodeToString(raw, Base64.NO_WRAP);
        if (!backups.edit().putString(key, encoded).commit()) {
            throw new IllegalStateException(
                    "не удалось синхронно сохранить исходный raw-профиль; запись AR заблокирована");
        }
    }

    private static String userProfileBackupKey(int profileId) {
        return BACKUP_USER_PROFILE_RAW_PREFIX + profileId;
    }

    private void rememberUserProfileHudModeOnce(int profileId, int mode) {
        int validatedMode = HudProfileTransferMode.requireSdkMode(mode);
        String key = userProfileHudModeBackupKey(profileId);
        if (backups.contains(key)) return;
        if (!backups.edit().putInt(key, validatedMode).commit()) {
            throw new IllegalStateException(
                    "не удалось сохранить исходный field124; запись HUD mode заблокирована");
        }
    }

    private static String userProfileHudModeBackupKey(int profileId) {
        return BACKUP_USER_PROFILE_HUD_MODE_PREFIX + profileId;
    }

    private enum SignalRead {
        HUD_REQUEST,
        HUD_ACTIVE_STATUS,
        HUD_STATUS,
        DIM_PRIORITY,
        DIM_RESOURCE,
        SPEED_EXTENDED,
        SPEED_UNIT,
        SPEED_VALUE
    }

    private String readSignal(SignalRead what) {
        CarSignalManager manager = signals;
        if (manager == null) return "—";
        try {
            int value;
            switch (what) {
                case HUD_REQUEST:
                    value = manager.getHudActvReq();
                    break;
                case HUD_ACTIVE_STATUS:
                    value = manager.getHudActvSts();
                    break;
                case HUD_STATUS:
                    value = manager.getHudSts();
                    break;
                case DIM_PRIORITY:
                    value = manager.getNetDIMActvtPrio();
                    break;
                case DIM_RESOURCE:
                    value = manager.getNetDIMActvtResourceGroup();
                    break;
                case SPEED_EXTENDED:
                    value = manager.getVehSpdExtdIndcnForUseInt();
                    break;
                case SPEED_UNIT:
                    value = manager.getVehSpdIndcdVeSpdIndcdUnit();
                    break;
                case SPEED_VALUE:
                    value = manager.getVehSpdIndcdVehSpdIndcd();
                    break;
                default:
                    return "?";
            }
            return Integer.toString(value);
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private ECarXCarVfhudManager requireVfHud() {
        if (vfHud == null) throw new IllegalStateException("VFHUD ещё не подключён");
        return vfHud;
    }

    private ECarXCarProfileManager requireProfileManager() {
        if (profileManager == null) {
            throw new IllegalStateException("ProfileManager ещё не подключён");
        }
        return profileManager;
    }

    private ECarXCarProfiletransferManager requireProfileTransfer() {
        if (profileTransfer == null) {
            throw new IllegalStateException("ProfileTransfer ещё не подключён");
        }
        return profileTransfer;
    }

    private CarSignalManager requireSignals() {
        if (signals == null) throw new IllegalStateException("DIM signals ещё не подключены");
        return signals;
    }

    private CarFunction requireCarFunction() {
        if (carFunction == null) throw new IllegalStateException("CarFunction ещё не подключён");
        return carFunction;
    }

    private void appendLog(String message) {
        logLines.addFirst(clock.format(new Date()) + "  " + message);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeLast();
        }
    }

    private String visualMaskDescription() {
        StringBuilder out = new StringBuilder("PEN=").append(visualPen).append(" [");
        for (int index = 0; index < visualFunctions.length; index++) {
            if (index > 0) out.append(' ');
            out.append(String.format(Locale.ROOT, "%02d:%d", index, visualFunctions[index]));
        }
        return out.append(']').toString();
    }

    private String profileVisualScanDescription() {
        if (profileVisualScanMode < 0) {
            return "не запущен";
        }
        StringBuilder out = new StringBuilder();
        out.append(profileVisualScanRunning ? "ИДЁТ" : "ПАУЗА")
                .append(" · mode=").append(modeName(profileVisualScanMode))
                .append(" · PEN=").append(profileVisualScanPen);
        if (profileVisualScanAppliedIndex >= 0) {
            out.append(" · на HUD сейчас F")
                    .append(twoDigits(profileVisualScanAppliedIndex))
                    .append("=0, остальные=1");
        } else if (profileVisualScanRunning && profileVisualScanIndex > 0) {
            out.append(" · baseline/restore");
        }
        if (profileVisualScanRunning) {
            out.append(" · шаг ").append(profileVisualScanIndex)
                    .append('/').append(HudVisualProbePlan.stepCount())
                    .append(" · следующий через 3,6 с");
        }
        return out.toString();
    }

    private static String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case 0:
                return "0 IntellGuide";
            case 1:
                return "1 IntellDrv";
            case 2:
                return "2 AR";
            case 3:
                return "3 Simple";
            case 4:
                return "4 Rerouting";
            case 5:
                return "5 TunnelEnter";
            case 6:
                return "6 TunnelEnd";
            default:
                return Integer.toString(mode);
        }
    }

    private static String result(ApiResult result) {
        return result == null ? "null" : result.name();
    }

    private static String value(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static String shortFailure(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? type : type + ": " + message.trim();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) {
            out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return out.toString();
    }

    private static final class HudDisplayFunction {
        final String label;
        final int functionId;

        HudDisplayFunction(String label, int functionId) {
            this.label = label;
            this.functionId = functionId;
        }
    }
}
