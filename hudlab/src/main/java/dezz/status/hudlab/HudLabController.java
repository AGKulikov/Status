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

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
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
 * <p>Nothing is written automatically. A write is issued only after an explicit button tap.
 * This class never reboots the HUD and never disables or stops a system package.</p>
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
    private static final int MAX_LOG_LINES = 70;
    private static final String PREFS = "hud_lab_backups";
    private static final String BACKUP_PROFILE_TRANSFER_MODE = "profile_transfer_mode";
    private static final String BACKUP_VEHICLE_MODEL = "vehicle_model";
    private static final String BACKUP_CLOUD_PROFILE = "cloud_profile";
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
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final int[] visualFunctions = new int[20];
    private final Runnable profileVisualScanStep = new Runnable() {
        @Override
        public void run() {
            if (closed || !profileVisualScanRunning) return;
            if (profileVisualScanIndex >= visualFunctions.length) {
                profileVisualScanRunning = false;
                profileVisualScanAppliedIndex = -1;
                String finished = "01+MASK: перебор завершён; скорость не исчезла";
                lastCommand = finished;
                appendLog(finished);
                publishSnapshot();
                return;
            }

            try {
                visualPen = profileVisualScanPen;
                Arrays.fill(visualFunctions, profileVisualScanOneOff ? ON : OFF);
                visualFunctions[profileVisualScanIndex] =
                        profileVisualScanOneOff ? OFF : ON;
                sendVisualMask();
                profileVisualScanAppliedIndex = profileVisualScanIndex;
                String step = "01+MASK mode=" + modeName(profileVisualScanMode)
                        + ", PEN=" + profileVisualScanPen
                        + ", " + scanPatternName(profileVisualScanOneOff)
                        + ", сейчас F" + twoDigits(profileVisualScanAppliedIndex);
                lastCommand = step;
                appendLog(step);
                profileVisualScanIndex++;
                publishSnapshot();
                worker.postDelayed(this, PROFILE_VISUAL_SCAN_STEP_MS);
            } catch (Throwable failure) {
                profileVisualScanRunning = false;
                String failed = "01+MASK: ERROR " + shortFailure(failure);
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
    private boolean profileVisualScanOneOff;
    private int profileVisualScanMode = -1;
    private int profileVisualScanPen = -1;
    private int profileVisualScanIndex;
    private int profileVisualScanAppliedIndex = -1;
    private String lastCommand = "Команды ещё не отправлялись";

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
            Arrays.fill(visualFunctions, normalized);
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    void setAllVisualFunctionsForPen(int pen, int value) {
        int normalizedPen = Math.max(0, Math.min(15, pen));
        int normalizedValue = value == 0 ? OFF : ON;
        runCommand("HUD visual mask: PEN=" + normalizedPen + ", все=" + normalizedValue, () -> {
            visualPen = normalizedPen;
            Arrays.fill(visualFunctions, normalizedValue);
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    void setVisualFunction(int index, int value) {
        if (index < 0 || index >= visualFunctions.length) return;
        int normalized = value == 0 ? OFF : ON;
        runCommand(String.format(Locale.ROOT, "HUD visual F%02d=%d", index, normalized), () -> {
            visualFunctions[index] = normalized;
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    void setVisualPen(int pen) {
        int normalized = Math.max(0, Math.min(15, pen));
        runCommand("HUD visual PEN=" + normalized, () -> {
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

    void setProfileTransferMode(int mode) {
        runCommand("01 ProfileTransfer HUD mode=" + modeName(mode), () -> {
            ECarXCarProfiletransferManager manager = requireProfileTransfer();
            rememberIntOnce(BACKUP_PROFILE_TRANSFER_MODE, readProfileTransferMode());
            ApiResult write = manager.CB_HudDispModSetgReq(mode);
            SystemClock.sleep(220L);
            return "CB33278=" + result(write)
                    + ", PA33937=" + readProfileTransferModeStatus();
        });
    }

    void restoreProfileTransferMode() {
        runCommand("01 ProfileTransfer mode: откат", () -> {
            stopProfileVisualScanInternal();
            int original = backups.getInt(BACKUP_PROFILE_TRANSFER_MODE,
                    readProfileTransferMode());
            ApiResult write = requireProfileTransfer().CB_HudDispModSetgReq(original);
            SystemClock.sleep(220L);
            return "value=" + original + ", result=" + result(write)
                    + ", PA33937=" + readProfileTransferModeStatus();
        });
    }

    /**
     * Combines the confirmed ProfileTransfer mode with the lower DIM visual-function mask.
     *
     * <p>The scan deliberately changes one flag at a time and waits long enough for the
     * physical HUD to redraw. Pressing "found" leaves the exact currently displayed mask in
     * place so it can be verified a second time.</p>
     */
    void startProfileVisualScan(int mode, boolean oneOff, boolean allProfiles) {
        worker.post(() -> {
            if (closed) return;
            stopProfileVisualScanInternal();
            try {
                ECarXCarProfiletransferManager manager = requireProfileTransfer();
                rememberIntOnce(BACKUP_PROFILE_TRANSFER_MODE, readProfileTransferMode());
                ApiResult modeWrite = manager.CB_HudDispModSetgReq(mode);
                SystemClock.sleep(240L);

                profileVisualScanMode = mode;
                profileVisualScanOneOff = oneOff;
                profileVisualScanPen = allProfiles ? 15 : activePen();
                profileVisualScanIndex = 0;
                profileVisualScanAppliedIndex = -1;
                profileVisualScanRunning = true;

                String started = "01+MASK старт: mode=" + modeName(mode)
                        + ", PEN=" + profileVisualScanPen
                        + ", " + scanPatternName(oneOff)
                        + ", CB33278=" + result(modeWrite)
                        + ", PA33937=" + readProfileTransferModeStatus();
                lastCommand = started;
                appendLog(started);
                publishSnapshot();
                profileVisualScanStep.run();
            } catch (Throwable failure) {
                profileVisualScanRunning = false;
                String failed = "01+MASK старт: ERROR " + shortFailure(failure);
                lastCommand = failed;
                appendLog(failed);
                publishSnapshot();
            }
        });
    }

    void markProfileVisualScanFound() {
        worker.post(() -> {
            if (closed) return;
            worker.removeCallbacks(profileVisualScanStep);
            boolean wasRunning = profileVisualScanRunning;
            profileVisualScanRunning = false;
            String found = profileVisualScanAppliedIndex < 0
                    ? "01+MASK: активной комбинации пока нет"
                    : "01+MASK ЗАФИКСИРОВАНО: mode=" + modeName(profileVisualScanMode)
                    + ", PEN=" + profileVisualScanPen
                    + ", " + scanPatternName(profileVisualScanOneOff)
                    + ", F" + twoDigits(profileVisualScanAppliedIndex)
                    + "=" + (profileVisualScanOneOff ? OFF : ON)
                    + (wasRunning ? " (перебор остановлен)" : "");
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
                int active = activePen();
                Arrays.fill(visualFunctions, ON);
                visualPen = active;
                sendVisualMask();
                visualPen = 15;
                sendVisualMask();
                visualPen = active;

                int original = backups.getInt(BACKUP_PROFILE_TRANSFER_MODE,
                        readProfileTransferMode());
                ApiResult modeWrite =
                        requireProfileTransfer().CB_HudDispModSetgReq(original);
                SystemClock.sleep(220L);
                profileVisualScanMode = -1;
                profileVisualScanPen = -1;
                profileVisualScanAppliedIndex = -1;
                String restored = "01+MASK восстановление: active PEN и ProfAll → все F=1"
                        + ", mode=" + original + " → " + result(modeWrite)
                        + ", PA33937=" + readProfileTransferModeStatus();
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

    void setActiveProfileDimMode(int mode) {
        runCommand("02 CEM HUD mode=" + modeName(mode) + " для активного PEN", () -> {
            int pen = activePen();
            VendorVehicleHalPAProto.ProtoHudDispModSetgReq request =
                    new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
            request.hudDispModSetgReqHudDispModSetgReq = mode;
            request.hudDispModSetgReqIdPen = pen;
            requireSignals().setHudDispModSetgReq(request);
            return "signal30814 sent, PEN=" + pen;
        });
    }

    void setActiveProfileVisualMask(boolean hidden) {
        runCommand("03 active-PEN visual mask=" + (hidden ? "HIDE" : "SHOW"), () -> {
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

    void setCloudProfileHudCandidate() {
        runCommand("Доп. путь: cloud-profile HUD candidate", () -> {
            ECarXCarProfileManager manager = requireProfileManager();
            byte[] current = manager.getByteCBValueForUt(
                    ECarXCarProfileManager.ManagerId_papsetprofileclouddata);
            if (current == null || current.length == 0) {
                throw new IllegalStateException("PA33873 вернул пустой blob");
            }
            rememberBytesOnce(BACKUP_CLOUD_PROFILE, current);
            VendorVehicleHalPAProto.Profileclouddata profile =
                    VendorVehicleHalPAProto.Profileclouddata.parseFrom(current);
            profile.vfhudbyte0 = OFF;
            profile.profiletransferbyte3 = 3;
            profile.profiletransferbyte9 = ON;
            manager.CB_PSET_ProfileCloudData(profile);
            return "RMW PA33873→CB33264: vfhud[0]=0, transfer[3]=3, transfer[9]=1"
                    + ", bytes=" + current.length;
        });
    }

    void restoreCloudProfile() {
        runCommand("Доп. путь: cloud-profile точный откат", () -> {
            String encoded = backups.getString(BACKUP_CLOUD_PROFILE, null);
            if (encoded == null) throw new IllegalStateException("резервная копия ещё не создана");
            byte[] original = Base64.decode(encoded, Base64.DEFAULT);
            requireProfileManager().setbytesPropertyForUt(
                    ECarXCarProfileManager.ManagerId_cbpsetprofileclouddata, original);
            return "restored raw exact blob, bytes=" + original.length;
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
            appendLog("ecarxcar_service отключён; ждём переподключения");
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
        worker.post(() -> {
            if (closed) return;
            String outcome;
            try {
                outcome = command.run();
            } catch (Throwable failure) {
                outcome = "ERROR " + shortFailure(failure);
            }
            lastCommand = title + " → " + outcome;
            appendLog(lastCommand);
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
        VendorVehicleHalPAProto.ProtoHudVisFctSetgReq request =
                new VendorVehicleHalPAProto.ProtoHudVisFctSetgReq();
        for (int index = 0; index < visualFunctions.length; index++) {
            Field field = request.getClass().getField(String.format(
                    Locale.ROOT, "hudVisFctSetgReqHudFct%02d", index));
            field.setInt(request, visualFunctions[index]);
        }
        request.hudVisFctSetgReqPen = visualPen;
        requireSignals().setHudVisFctSetgReq(request);
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
        int pen = requireSignals().getProfPenSts1();
        if (pen < 1 || pen > 13) {
            throw new IllegalStateException("PEN=" + pen
                    + " не является активным профилем (ожидалось 1…13)");
        }
        return pen;
    }

    private int activeProfile() throws Exception {
        PATypes.PA_PSET_ActiveProfile value =
                requireProfileManager().getPA_PSET_ActiveProfile();
        if (value == null) throw new IllegalStateException("PA33845=null");
        return value.getData();
    }

    private String readActivePen() {
        try {
            return Integer.toString(activePen());
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

    private void rememberBytesOnce(String key, byte[] value) {
        if (value == null || value.length == 0 || backups.contains(key)) return;
        backups.edit().putString(key,
                Base64.encodeToString(value, Base64.NO_WRAP)).apply();
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
                .append(" · PEN=").append(profileVisualScanPen)
                .append(" · ").append(scanPatternName(profileVisualScanOneOff));
        if (profileVisualScanAppliedIndex >= 0) {
            out.append(" · на HUD сейчас F")
                    .append(twoDigits(profileVisualScanAppliedIndex))
                    .append('=').append(profileVisualScanOneOff ? OFF : ON);
        }
        if (profileVisualScanRunning) {
            out.append(" · следующий через ")
                    .append(PROFILE_VISUAL_SCAN_STEP_MS / 1_000L).append(",6 с");
        }
        return out.toString();
    }

    private static String scanPatternName(boolean oneOff) {
        return oneOff ? "все 1, по одному OFF" : "все 0, по одному ON";
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

    private static final class HudDisplayFunction {
        final String label;
        final int functionId;

        HudDisplayFunction(String label, int functionId) {
            this.label = label;
            this.functionId = functionId;
        }
    }
}
