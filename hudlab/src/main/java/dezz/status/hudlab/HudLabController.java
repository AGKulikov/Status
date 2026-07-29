package dezz.status.hudlab;

import android.car.CarNotConnectedException;
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
import com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction;
import com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction;
import com.ecarx.xui.adaptapi.policy.IAudioAttributes;
import com.google.protobuf.nano.MessageNano;
import dezz.status.hudlab.HudVisualProbePlan;
import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.property.ECarXCarPropertyManagerBase;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.vehicle.ECarXCarProfileManager;
import ecarx.car.hardware.vehicle.ECarXCarProfiletransferManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.ECarXCarVfhudManager;
import ecarx.car.hardware.vehicle.PATypes;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import vendor.ecarx.xma.pa.nano.VendorVehicleHalPAProto;

/* loaded from: classes4.dex */
final class HudLabController implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String BACKUP_DIM_NAVI_MODE = "dim_navi_mode_before";
    private static final String BACKUP_PROFILE_TRANSFER_MODE = "profile_transfer_mode";
    private static final String BACKUP_USER_PROFILE_HUD_MODE_PREFIX = "user_profile_hud_mode_before_";
    private static final String BACKUP_USER_PROFILE_RAW_PREFIX = "user_profile_raw_before_hud_ar_";
    private static final String BACKUP_VEHICLE_MODEL = "vehicle_model";
    private static final int CB_PROFILE_CLOUD_DATA = 33264;
    private static final HudDisplayFunction DISPLAY_BT_PHONE;
    private static final HudDisplayFunction DISPLAY_DRIVE_ENVIRONMENT;
    private static final HudDisplayFunction[] DISPLAY_FUNCTIONS;
    private static final HudDisplayFunction DISPLAY_MEDIA;
    private static final HudDisplayFunction DISPLAY_NAVIGATION;
    private static final HudDisplayFunction DISPLAY_SAFETY;
    private static final String HUD_AR_PROFILE_KEY = "654443008";
    private static final String HUD_MODE_PROFILE_KEY = "251660288";
    private static final int MAX_LOG_LINES = 180;
    private static final int OFF = 0;
    private static final int PA_PROFILE_CLOUD_DATA = 33873;
    private static final String PREFS = "hud_lab_backups";
    private static final int PROFILE_READBACK_ATTEMPTS = 6;
    private static final long PROFILE_READBACK_DELAY_MS = 250;
    private static final int PROFILE_TRANSFER_MODE_CB = 33278;
    private static final long PROFILE_VISUAL_SCAN_STEP_MS = 3600;
    private static final long REFRESH_MS = 1200;
    private static final int VEHICLE_AREA_GLOBAL = 1;
    private static final int ZONE_ALL = Integer.MIN_VALUE;
    private static final int f8ON = 1;
    private final Context appContext;
    private final SharedPreferences backups;
    private CarFunction carFunction;
    private final SimpleDateFormat clock;
    private boolean closed;
    private IDimMenuInteraction dimMenuInteraction;
    private final Set<Integer> dirtyVisualMaskPens;
    private String lastCommand;
    private final Listener listener;
    private final ArrayDeque<String> logLines;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable periodicRefresh;
    private ECarXCarProfileManager profileManager;
    private ECarXCarProfiletransferManager profileTransfer;
    private int profileVisualScanAppliedIndex;
    private int profileVisualScanIndex;
    private int profileVisualScanMode;
    private int profileVisualScanPen;
    private boolean profileVisualScanRunning;
    private final Runnable profileVisualScanStep;
    private ECarXCarProxy proxy;
    private ECarXCar root;
    private CarSignalManager signals;
    private final HandlerThread thread;
    private String userProfileHudArStatus;
    private String userProfileHudModeStatus;
    private ECarXCarVfhudManager vfHud;
    private final int[] visualFunctions;
    private int visualPen;
    private final Handler worker;

    interface ClusterProbeCallback {
        void onCaptured(ClusterSignalSnapshot clusterSignalSnapshot);
    }

    interface Command {
        String run() throws Exception;
    }

    interface Listener {
        void onUpdated(String str, String str2, boolean z);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface ProbeIntReader {
        int read() throws Exception;
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

    static final class HudDisplayFunction {
        final int functionId;
        final String label;

        HudDisplayFunction(String str, int i) {
            this.label = str;
            this.functionId = i;
        }
    }

    static {
        HudDisplayFunction hudDisplayFunction = new HudDisplayFunction("SAFETY", IHUD.SETTING_FUNC_HUD_DISPLAY_SAFETY);
        DISPLAY_SAFETY = hudDisplayFunction;
        HudDisplayFunction hudDisplayFunction2 = new HudDisplayFunction(IAudioAttributes.USAGE_MD_MEDIA, IHUD.SETTING_FUNC_HUD_DISPLAY_MEIDA);
        DISPLAY_MEDIA = hudDisplayFunction2;
        HudDisplayFunction hudDisplayFunction3 = new HudDisplayFunction("NAVI", IHUD.SETTING_FUNC_HUD_DISPLAY_NAVI);
        DISPLAY_NAVIGATION = hudDisplayFunction3;
        HudDisplayFunction hudDisplayFunction4 = new HudDisplayFunction("BTPHONE", IHUD.SETTING_FUNC_HUD_DISPLAY_BTPHONE);
        DISPLAY_BT_PHONE = hudDisplayFunction4;
        HudDisplayFunction hudDisplayFunction5 = new HudDisplayFunction("DRIVE_ENVIRONMENT", IHUD.SETTING_FUNC_HUD_DISPLAY_DRIVE_ENVIRONMENT);
        DISPLAY_DRIVE_ENVIRONMENT = hudDisplayFunction5;
        DISPLAY_FUNCTIONS = new HudDisplayFunction[]{hudDisplayFunction5, hudDisplayFunction, hudDisplayFunction2, hudDisplayFunction3, hudDisplayFunction4};
    }

    HudLabController(Context context, Listener listener) {
        HandlerThread handlerThread = new HandlerThread("hud-lab");
        this.thread = handlerThread;
        this.logLines = new ArrayDeque<>();
        this.dirtyVisualMaskPens = new TreeSet();
        this.clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        int[] iArr = new int[20];
        this.visualFunctions = iArr;
        this.profileVisualScanStep = new Runnable() { // from class: dezz.status.hudlab.HudLabController.1
            @Override // java.lang.Runnable
            public void run() {
                if (HudLabController.this.closed || !HudLabController.this.profileVisualScanRunning) {
                    return;
                }
                try {
                    HudVisualProbePlan.Step step = HudVisualProbePlan.step(HudLabController.this.profileVisualScanIndex);
                    HudLabController hudLabController = HudLabController.this;
                    hudLabController.visualPen = hudLabController.profileVisualScanPen;
                    System.arraycopy(step.values(), 0, HudLabController.this.visualFunctions, 0, HudLabController.this.visualFunctions.length);
                    HudLabController.this.sendVisualMask();
                    HudLabController.this.profileVisualScanAppliedIndex = step.functionIndex;
                    String str = "01+MASK SAFE mode=" + HudLabController.modeName(HudLabController.this.profileVisualScanMode) + ", PEN=" + HudLabController.this.profileVisualScanPen + ", шаг " + (HudLabController.this.profileVisualScanIndex + 1) + "/" + HudVisualProbePlan.stepCount() + ": " + step.label;
                    HudLabController.this.lastCommand = str;
                    HudLabController.this.appendLog(str);
                    HudLabController.this.profileVisualScanIndex++;
                    if (step.finalRestore) {
                        HudLabController.this.profileVisualScanRunning = false;
                        HudLabController.this.profileVisualScanAppliedIndex = -1;
                        String str2 = "01+MASK SAFE завершён: PEN=" + HudLabController.this.profileVisualScanPen + ", все F=1 восстановлены";
                        HudLabController.this.lastCommand = str2;
                        HudLabController.this.appendLog(str2);
                        HudLabController.this.publishSnapshot();
                        return;
                    }
                    HudLabController.this.publishSnapshot();
                    HudLabController.this.worker.postDelayed(this, HudLabController.PROFILE_VISUAL_SCAN_STEP_MS);
                } catch (Throwable th) {
                    HudLabController.this.profileVisualScanRunning = false;
                    HudLabController hudLabController2 = HudLabController.this;
                    String str3 = "01+MASK SAFE: ERROR " + HudLabController.shortFailure(th) + "; " + hudLabController2.restoreVisualMaskBestEffort(hudLabController2.profileVisualScanPen);
                    HudLabController.this.lastCommand = str3;
                    HudLabController.this.appendLog(str3);
                    HudLabController.this.publishSnapshot();
                }
            }
        };
        this.periodicRefresh = new Runnable() { // from class: dezz.status.hudlab.HudLabController.2
            @Override // java.lang.Runnable
            public void run() {
                if (HudLabController.this.closed) {
                    return;
                }
                HudLabController.this.publishSnapshot();
                HudLabController.this.worker.postDelayed(this, HudLabController.REFRESH_MS);
            }
        };
        this.visualPen = 1;
        this.profileVisualScanMode = -1;
        this.profileVisualScanPen = -1;
        this.profileVisualScanAppliedIndex = -1;
        this.lastCommand = "Команды ещё не отправлялись";
        this.userProfileHudArStatus = "не читался; нажмите «ПРОЧИТАТЬ AR» во вкладке DISPLAY_*";
        this.userProfileHudModeStatus = "не читался; кнопки FIELD124 читают значение перед записью";
        Context applicationContext = context.getApplicationContext();
        Context context2 = applicationContext != null ? applicationContext : context;
        this.appContext = context2;
        this.listener = listener;
        this.backups = context2.getSharedPreferences(PREFS, 0);
        Arrays.fill(iArr, 1);
        handlerThread.start();
        this.worker = new Handler(handlerThread.getLooper());
    }

    public void lambda$onECarXCarServiceConnected$42(ECarXCar eCarXCar, CarSignalManager carSignalManager) throws IllegalStateException, CarNotConnectedException {
        Object carManager = null;
        if (this.closed || eCarXCar == null) {
            return;
        }
        try {
            carManager = eCarXCar.getCarManager(ECarXCar.PA_SERVICE);
        } catch (Throwable th) {
            this.root = null;
            this.vfHud = null;
            this.profileManager = null;
            this.profileTransfer = null;
            this.signals = null;
            this.carFunction = null;
            appendLog("Ошибка инициализации SDK: " + shortFailure(th));
        }
        if (!(carManager instanceof ECarXCarSetManager)) {
            throw new IllegalStateException("PA_SERVICE не вернул ECarXCarSetManager");
        }
        this.root = eCarXCar;
        this.signals = carSignalManager;
        ECarXCarSetManager eCarXCarSetManager = (ECarXCarSetManager) carManager;
        this.vfHud = eCarXCarSetManager.getECarXCarVfhudManager();
        this.profileManager = eCarXCarSetManager.getECarXCarProfileManager();
        this.profileTransfer = eCarXCarSetManager.getECarXCarProfiletransferManager();
        CarFunction carFunction = new CarFunction(this.appContext);
        carFunction.initCarSignalManager(eCarXCar, carSignalManager);
        this.carFunction = carFunction;
        appendLog("Подключено: VFHUD + ProfileTransfer + Profile + CEM/DIM");
        String strRestoreAllDirtyVisualMasksBestEffort = restoreAllDirtyVisualMasksBestEffort();
        if (!strRestoreAllDirtyVisualMasksBestEffort.isEmpty()) {
            appendLog("Аварийное восстановление после переподключения: " + strRestoreAllDirtyVisualMasksBestEffort);
        }
        publishSnapshot();
    }

    private int activePen() throws Exception {
        Throwable th;
        int profPenSts1;
        try {
            profPenSts1 = requireSignals().getProfPenSts1();
            th = null;
        } catch (Throwable th2) {
            th = th2;
            profPenSts1 = -1;
        }
        if (isProfilePen(profPenSts1)) {
            return profPenSts1;
        }
        try {
            int iActiveProfile = activeProfile();
            if (isProfilePen(iActiveProfile)) {
                return iActiveProfile;
            }
            throw new IllegalStateException("активный PEN недоступен: ProfPenSts1=" + (th == null ? Integer.toString(profPenSts1) : shortFailure(th)) + ", PA33845=" + iActiveProfile + " (ожидалось 0…13)");
        } catch (Throwable th3) {
            throw new IllegalStateException("активный PEN недоступен: ProfPenSts1=" + (th == null ? Integer.toString(profPenSts1) : shortFailure(th)) + ", PA33845=" + shortFailure(th3), th3);
        }
    }

    private int activeProfile() throws Exception {
        PATypes.PA_PSET_ActiveProfile pA_PSET_ActiveProfile = requireProfileManager().getPA_PSET_ActiveProfile();
        if (pA_PSET_ActiveProfile != null) {
            return pA_PSET_ActiveProfile.getData();
        }
        throw new IllegalStateException("PA33845=null");
    }

    public void appendLog(String str) {
        this.logLines.addFirst(this.clock.format(new Date()) + "  " + str);
        while (this.logLines.size() > 180) {
            this.logLines.removeLast();
        }
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & (-1))));
        }
        return sb.toString();
    }

    private boolean isAllOneVisualMask() {
        for (int i : this.visualFunctions) {
            if (i != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean isProfilePen(int i) {
        return i >= 0 && i <= 13;
    }

    public void lambda$applyHeldVisualBaseline$19(int i, int i2) {
        if (this.closed) {
            return;
        }
        stopProfileVisualScanInternal();
        String strRestoreAllDirtyVisualMasksBestEffort = restoreAllDirtyVisualMasksBestEffort();
        if (!strRestoreAllDirtyVisualMasksBestEffort.isEmpty()) {
            appendLog("01+MASK BASELINE: очистка предыдущих проб: " + strRestoreAllDirtyVisualMasksBestEffort);
        }
        try {
            int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
            int iActivePen = activePen();
            rememberProfileTransferModeOnce(readProfileTransferMode());
            ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iRequireSdkMode);
            SystemClock.sleep(220L);
            this.profileVisualScanMode = iRequireSdkMode;
            this.profileVisualScanPen = iActivePen;
            this.profileVisualScanAppliedIndex = -1;
            this.visualPen = iActivePen;
            Arrays.fill(this.visualFunctions, i2);
            sendVisualMask();
            String str = "01+MASK BASELINE: mode=" + modeName(i) + ", PEN=" + this.profileVisualScanPen + ", все F=" + i2 + ", CB33278=" + result(apiResultWriteProfileTransferSdkMode);
            this.lastCommand = str;
            appendLog(str);
        } catch (Throwable th) {
            String str2 = "01+MASK BASELINE: ERROR " + shortFailure(th) + "; " + restoreVisualMaskBestEffort(this.profileVisualScanPen);
            this.lastCommand = str2;
            appendLog(str2);
        }
        publishSnapshot();
    }

    public void lambda$applyHeldVisualProbe$18(int i, int i2) {
        if (this.closed) {
            return;
        }
        stopProfileVisualScanInternal();
        String strRestoreAllDirtyVisualMasksBestEffort = restoreAllDirtyVisualMasksBestEffort();
        if (!strRestoreAllDirtyVisualMasksBestEffort.isEmpty()) {
            appendLog("01+MASK РУЧНОЙ: очистка предыдущих проб: " + strRestoreAllDirtyVisualMasksBestEffort);
        }
        try {
            int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
            int iActivePen = activePen();
            rememberProfileTransferModeOnce(readProfileTransferMode());
            ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iRequireSdkMode);
            SystemClock.sleep(220L);
            this.profileVisualScanMode = iRequireSdkMode;
            this.profileVisualScanPen = iActivePen;
            this.profileVisualScanAppliedIndex = i2;
            this.visualPen = iActivePen;
            Arrays.fill(this.visualFunctions, 1);
            sendVisualMask();
            SystemClock.sleep(100L);
            Arrays.fill(this.visualFunctions, 1);
            this.visualFunctions[i2] = 0;
            sendVisualMask();
            String str = "01+MASK РУЧНОЙ: mode=" + modeName(i) + ", PEN=" + this.profileVisualScanPen + ", держим F" + twoDigits(i2) + "=0, остальные=1, CB33278=" + result(apiResultWriteProfileTransferSdkMode);
            this.lastCommand = str;
            appendLog(str);
        } catch (Throwable th) {
            String str2 = "01+MASK РУЧНОЙ: ERROR " + shortFailure(th) + "; " + restoreVisualMaskBestEffort(this.profileVisualScanPen);
            this.lastCommand = str2;
            appendLog(str2);
        }
        publishSnapshot();
    }

    public String lambda$applyPersistentHudMode$14(int i) throws Exception {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
        int iActivePen = activePen();
        rememberProfileTransferModeOnce(readProfileTransferMode());
        ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iRequireSdkMode);
        SystemClock.sleep(180L);
        VendorVehicleHalPAProto.ProtoHudDispModSetgReq protoHudDispModSetgReq = new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
        protoHudDispModSetgReq.hudDispModSetgReqHudDispModSetgReq = iRequireSdkMode;
        protoHudDispModSetgReq.hudDispModSetgReqIdPen = iActivePen;
        requireSignals().setHudDispModSetgReq(protoHudDispModSetgReq);
        SystemClock.sleep(220L);
        ApiResult saveSetgToMemPrmnt = requireSignals().setSaveSetgToMemPrmnt(0);
        requireSuccessfulWrite("signal29892 OFF", saveSetgToMemPrmnt);
        SystemClock.sleep(80L);
        ApiResult saveSetgToMemPrmnt2 = requireSignals().setSaveSetgToMemPrmnt(1);
        requireSuccessfulWrite("signal29892 ON", saveSetgToMemPrmnt2);
        SystemClock.sleep(300L);
        int profileTransferMode = readProfileTransferMode();
        if (profileTransferMode == iRequireSdkMode) {
            return "OK · один проход · PEN=" + iActivePen + " · CB33278=" + result(apiResultWriteProfileTransferSdkMode) + " · DIM30814=[" + iRequireSdkMode + "," + iActivePen + "] · SAVE29892=" + result(saveSetgToMemPrmnt) + "→" + result(saveSetgToMemPrmnt2) + " · PA33937=" + profileTransferMode + " · PA33906=" + readPaMode();
        }
        throw new IllegalStateException("PA33937 не подтвердил mode=" + iRequireSdkMode + " (получено " + profileTransferMode + ")");
    }

    public void lambda$close$1() {
        if (this.closed) {
            return;
        }
        this.worker.removeCallbacks(this.profileVisualScanStep);
        restoreAllDirtyVisualMasksBestEffort();
        this.closed = true;
        this.worker.removeCallbacksAndMessages(null);
        CarFunction carFunction = this.carFunction;
        if (carFunction != null) {
            try {
                carFunction.onECarXCarServiceDeath();
            } catch (Throwable th) {
            }
        }
        this.carFunction = null;
        this.vfHud = null;
        this.profileManager = null;
        this.profileTransfer = null;
        this.signals = null;
        this.root = null;
        ECarXCarProxy eCarXCarProxy = this.proxy;
        this.proxy = null;
        if (eCarXCarProxy != null) {
            try {
                eCarXCarProxy.stopReconnection();
                eCarXCarProxy.cleanup();
            } catch (Throwable th2) {
            }
        }
        this.thread.quitSafely();
    }

    public void lambda$markProfileVisualScanFound$20() {
        String str;
        if (this.closed) {
            return;
        }
        this.worker.removeCallbacks(this.profileVisualScanStep);
        boolean z = this.profileVisualScanRunning;
        this.profileVisualScanRunning = false;
        int i = this.profileVisualScanAppliedIndex;
        String strRestoreVisualMaskBestEffort = restoreVisualMaskBestEffort(this.profileVisualScanPen);
        this.profileVisualScanAppliedIndex = -1;
        if (i < 0) {
            str = "01+MASK: активной комбинации пока нет";
        } else {
            str = "01+MASK ЗАФИКСИРОВАНО: mode=" + modeName(this.profileVisualScanMode) + ", PEN=" + this.profileVisualScanPen + ", F" + twoDigits(i) + "=0" + (z ? " (перебор остановлен)" : "") + "; " + strRestoreVisualMaskBestEffort;
        }
        this.lastCommand = str;
        appendLog(str);
        publishSnapshot();
    }

    public void lambda$onECarXCarServiceDeath$43() {
        boolean z = this.profileVisualScanRunning;
        stopProfileVisualScanInternal();
        CarFunction carFunction = this.carFunction;
        if (carFunction != null) {
            try {
                carFunction.onECarXCarServiceDeath();
            } catch (Throwable th) {
            }
        }
        this.root = null;
        this.vfHud = null;
        this.profileManager = null;
        this.profileTransfer = null;
        this.signals = null;
        this.carFunction = null;
        appendLog("ecarxcar_service отключён; ждём переподключения".concat(z ? "; SAFE-цикл остановлен, all=1 будет восстановлен после подключения" : ""));
        publishSnapshot();
    }

    public String lambda$persistCurrentProfileSettings$33() throws Exception {
        ApiResult saveSetgToMemPrmnt = requireSignals().setSaveSetgToMemPrmnt(0);
        SystemClock.sleep(80L);
        return "signal29892 0→1: " + result(saveSetgToMemPrmnt) + " / " + result(requireSignals().setSaveSetgToMemPrmnt(1));
    }

    public void lambda$publishSnapshot$45(String str, String str2, boolean z) {
        if (this.closed) {
            return;
        }
        this.listener.onUpdated(str, str2, z);
    }

    public String lambda$pulseProfileTransferApply$34() throws Exception {
        ECarXCarProfiletransferManager eCarXCarProfiletransferManagerRequireProfileTransfer = requireProfileTransfer();
        ApiResult apiResultCB_Profile_Transfer_Reboot = eCarXCarProfiletransferManagerRequireProfileTransfer.CB_Profile_Transfer_Reboot(1);
        SystemClock.sleep(80L);
        return "CB33274 1→0: " + result(apiResultCB_Profile_Transfer_Reboot) + " / " + result(eCarXCarProfiletransferManagerRequireProfileTransfer.CB_Profile_Transfer_Reboot(0));
    }

    public String lambda$refreshUserProfileHudAr$36() throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        byte[] bArrRequireRawProfileCloudDataForProfile = requireRawProfileCloudDataForProfile(iRequireActiveProfileId);
        String str = "profile=" + iRequireActiveProfileId + ", value=" + HudProfileWirePatcher.readHudAr(bArrRequireRawProfileCloudDataForProfile) + ", rawBytes=" + bArrRequireRawProfileCloudDataForProfile.length;
        this.userProfileHudArStatus = str;
        return str;
    }

    public String lambda$refreshUserProfileHudMode$39() throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        byte[] bArrRequireRawProfileCloudDataForProfile = requireRawProfileCloudDataForProfile(iRequireActiveProfileId);
        String str = "profile=" + iRequireActiveProfileId + ", field124=" + HudProfileWirePatcher.readHudMode(bArrRequireRawProfileCloudDataForProfile) + ", rawBytes=" + bArrRequireRawProfileCloudDataForProfile.length;
        this.userProfileHudModeStatus = str;
        return str;
    }

    public String lambda$reloadActiveProfile$32() throws Exception {
        int iActiveProfile = activeProfile();
        int iActivePen = activePen();
        return "profile=" + iActiveProfile + " → " + result(requireProfileManager().CB_PSET_RequestActiveProfile(iActiveProfile)) + ", PEN=" + iActivePen + " → " + result(requireSignals().setProfChg(iActivePen));
    }

    public String lambda$restoreProfileTransferMode$16() throws Exception {
        stopProfileVisualScanInternal();
        int iSavedProfileTransferMode = savedProfileTransferMode();
        ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iSavedProfileTransferMode);
        SystemClock.sleep(220L);
        return "value=" + iSavedProfileTransferMode + ", result=" + result(apiResultWriteProfileTransferSdkMode) + ", PA33937=" + readProfileTransferModeStatus();
    }

    public void lambda$restoreProfileVisualSearch$21() {
        if (this.closed) {
            return;
        }
        stopProfileVisualScanInternal();
        try {
            String strRestoreAllDirtyVisualMasksBestEffort = restoreAllDirtyVisualMasksBestEffort();
            int iActivePen = activePen();
            Arrays.fill(this.visualFunctions, 1);
            this.visualPen = iActivePen;
            sendVisualMask();
            int iSavedProfileTransferMode = savedProfileTransferMode();
            ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iSavedProfileTransferMode);
            SystemClock.sleep(220L);
            this.profileVisualScanMode = -1;
            this.profileVisualScanPen = -1;
            this.profileVisualScanAppliedIndex = -1;
            String str = "01+MASK восстановление: active PEN → все F=1, mode=" + iSavedProfileTransferMode + " → " + result(apiResultWriteProfileTransferSdkMode) + ", PA33937=" + readProfileTransferModeStatus() + (strRestoreAllDirtyVisualMasksBestEffort.isEmpty() ? "" : "; ранее: " + strRestoreAllDirtyVisualMasksBestEffort);
            this.lastCommand = str;
            appendLog(str);
        } catch (Throwable th) {
            String str2 = "01+MASK восстановление: ERROR " + shortFailure(th);
            this.lastCommand = str2;
            appendLog(str2);
        }
        publishSnapshot();
    }

    public String lambda$restoreUserProfileHudAr$37() throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        String string = this.backups.getString(userProfileBackupKey(iRequireActiveProfileId), null);
        if (string == null) {
            throw new IllegalStateException("для активного профиля " + iRequireActiveProfileId + " резервная копия ещё не создана");
        }
        int hudAr = HudProfileWirePatcher.readHudAr(Base64.decode(string, 2));
        String strWriteAndConfirmRawHudAr = writeAndConfirmRawHudAr(iRequireActiveProfileId, requireRawProfileCloudDataForProfile(iRequireActiveProfileId), hudAr == 1);
        this.backups.edit().remove(userProfileBackupKey(iRequireActiveProfileId)).apply();
        this.userProfileHudArStatus = "profile=" + iRequireActiveProfileId + ", value=" + hudAr + ", " + strWriteAndConfirmRawHudAr;
        return "profile=" + iRequireActiveProfileId + ", восстановлен исходный AR=" + hudAr + " поверх свежего raw-профиля, readback=" + strWriteAndConfirmRawHudAr;
    }

    public String lambda$restoreUserProfileHudMode$40() throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        String strUserProfileHudModeBackupKey = userProfileHudModeBackupKey(iRequireActiveProfileId);
        if (!this.backups.contains(strUserProfileHudModeBackupKey)) {
            throw new IllegalStateException("для активного профиля " + iRequireActiveProfileId + " исходное поле 124 ещё не сохранено");
        }
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(this.backups.getInt(strUserProfileHudModeBackupKey, -1));
        String strWriteAndConfirmRawHudMode = writeAndConfirmRawHudMode(iRequireActiveProfileId, requireRawProfileCloudDataForProfile(iRequireActiveProfileId), iRequireSdkMode);
        this.backups.edit().remove(strUserProfileHudModeBackupKey).apply();
        this.userProfileHudModeStatus = "profile=" + iRequireActiveProfileId + ", field124=" + iRequireSdkMode + ", " + strWriteAndConfirmRawHudMode;
        return "profile=" + iRequireActiveProfileId + ", восстановлен исходный field124=" + iRequireSdkMode + " поверх свежего raw-профиля, readback=" + strWriteAndConfirmRawHudMode;
    }

    public String lambda$restoreVehicleModelClear$31() throws Exception {
        int i = this.backups.getInt(BACKUP_VEHICLE_MODEL, 0);
        return "value=" + i + ", result=" + result(requireProfileTransfer().CB_VehMdlClrReq(i));
    }

    public void lambda$runCommand$44(Command command, String str, Runnable runnable) {
        String strRun;
        boolean z;
        if (this.closed) {
            return;
        }
        try {
            requireNoSafeVisualScan();
            strRun = command.run();
            z = true;
        } catch (Throwable th) {
            String strRun2 = "ERROR " + shortFailure(th);
            strRun = strRun2;
            z = false;
        }
        String str2 = str + " → " + strRun;
        this.lastCommand = str2;
        appendLog(str2);
        if (z && runnable != null && !this.closed) {
            this.main.post(runnable);
        }
        publishSnapshot();
        this.worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                publishSnapshot();
            }
        }, 300L);
    }

    public String lambda$setActiveProfileDimMode$22(int i) throws Exception {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
        int iActivePen = activePen();
        VendorVehicleHalPAProto.ProtoHudDispModSetgReq protoHudDispModSetgReq = new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
        protoHudDispModSetgReq.hudDispModSetgReqHudDispModSetgReq = iRequireSdkMode;
        protoHudDispModSetgReq.hudDispModSetgReqIdPen = iActivePen;
        requireSignals().setHudDispModSetgReq(protoHudDispModSetgReq);
        appendLog("TX signal30814 / VHAL 0x2170785E · mode=" + iRequireSdkMode + ", PEN=" + iActivePen + " · protobuf=" + hex(MessageNano.toByteArray(protoHudDispModSetgReq)));
        SystemClock.sleep(PROFILE_READBACK_DELAY_MS);
        return "signal30814 sent once, PEN=" + iActivePen + ", PA33906=" + readPaMode();
    }

    public String lambda$setActiveProfileVisualMask$23(boolean z) throws Exception {
        requireNoSafeVisualScan();
        this.visualPen = activePen();
        Arrays.fill(this.visualFunctions, !z ? 1 : 0);
        sendVisualMask();
        return "signal30816, " + visualMaskDescription();
    }

    public String lambda$setAllActivationChannels$7(boolean z) throws Exception {
        return "Settings=" + setFunctionValue(537985280, z) + ", SettingsAR=" + setFunctionValue(IHUD.SETTING_FUNC_HUD_AR_ENGINE, z) + ", VF=" + result(requireVfHud().CB_VF_HUD_ActvReq(z ? 1 : 0)) + ", AR=" + result(requireVfHud().CB_VF_HUD_ARActvReq(z ? 1 : 0)) + ", DIM=" + result(requireSignals().setHudDispActvReq(z ? 1 : 0));
    }

    public String lambda$setAllVisualFunctions$10(int i) throws Exception {
        requireNoSafeVisualScan();
        Arrays.fill(this.visualFunctions, i);
        sendVisualMask();
        return visualMaskDescription();
    }

    public String lambda$setDimActive$4(boolean z) throws Exception {
        return "result=" + result(requireSignals().setHudDispActvReq(z ? 1 : 0));
    }

    public String lambda$setDimDisplayMode$9(int i, int i2) throws Exception {
        VendorVehicleHalPAProto.ProtoHudDispModSetgReq protoHudDispModSetgReq = new VendorVehicleHalPAProto.ProtoHudDispModSetgReq();
        protoHudDispModSetgReq.hudDispModSetgReqHudDispModSetgReq = i;
        protoHudDispModSetgReq.hudDispModSetgReqIdPen = i2;
        requireSignals().setHudDispModSetgReq(protoHudDispModSetgReq);
        return "raw signal sent";
    }

    public String lambda$setDisplayFunctions$41(HudDisplayFunction[] hudDisplayFunctionArr, boolean z) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (HudDisplayFunction hudDisplayFunction : hudDisplayFunctionArr) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(hudDisplayFunction.label).append('=').append(setFunctionValue(hudDisplayFunction.functionId, z));
        }
        return "accepted[" + ((Object) sb) + ']';
    }

    public String lambda$setDriverDisplayTheme$29(int i) throws Exception {
        int validatedValue = InstrumentClusterModes.requireDriverDisplayTemplate(i);
        int iActivePen = activePen();
        VendorVehicleHalPAProto.ProtoDrvrDispSetg protoDrvrDispSetg = new VendorVehicleHalPAProto.ProtoDrvrDispSetg();
        protoDrvrDispSetg.drvrDispSetgPen = iActivePen;
        protoDrvrDispSetg.drvrDispSetgSts = validatedValue;
        requireSignals().setDrvrDispSetg(protoDrvrDispSetg);
        appendLog("TX signal30803 · PEN=" + iActivePen + ", value=" + validatedValue + ", protobuf=" + hex(MessageNano.toByteArray(protoDrvrDispSetg)));
        return "signal30803 отправлен, PEN=" + iActivePen + ", value=" + validatedValue + ", feedback30873=" + readDriverDisplayTheme();
    }

    public String lambda$setDriverHmiBackground$24(int i) throws Exception {
        int validatedValue = InstrumentClusterModes.requireDriverHmiBackground(i);
        int iActivePen = activePen();
        VendorVehicleHalPAProto.ProtoDrvrHmiBackGndInfoSetg protoDrvrHmiBackGndInfoSetg = new VendorVehicleHalPAProto.ProtoDrvrHmiBackGndInfoSetg();
        protoDrvrHmiBackGndInfoSetg.drvrHmiBackGndInfoSetgPen = iActivePen;
        protoDrvrHmiBackGndInfoSetg.drvrHmiBackGndInfoSetgSetg = validatedValue;
        requireSignals().setDrvrHmiBackGndInfoSetg(protoDrvrHmiBackGndInfoSetg);
        appendLog("TX signal30805 · PEN=" + iActivePen + ", value=" + validatedValue + ", protobuf=" + hex(MessageNano.toByteArray(protoDrvrHmiBackGndInfoSetg)));
        return "signal30805 отправлен, PEN=" + iActivePen + ", value=" + validatedValue;
    }

    public String lambda$setDriverHmiInterface$25(int i) throws Exception {
        int validatedValue = InstrumentClusterModes.requireDriverHmiInterface(i);
        int iActivePen = activePen();
        VendorVehicleHalPAProto.ProtoDrvrHmiUsrIfSetg protoDrvrHmiUsrIfSetg = new VendorVehicleHalPAProto.ProtoDrvrHmiUsrIfSetg();
        protoDrvrHmiUsrIfSetg.drvrHmiUsrIfSetgPen = iActivePen;
        protoDrvrHmiUsrIfSetg.drvrHmiUsrIfSetgSetg = validatedValue;
        requireSignals().setDrvrHmiUsrIfSetg(protoDrvrHmiUsrIfSetg);
        appendLog("TX signal30807 · PEN=" + iActivePen + ", value=" + validatedValue + ", protobuf=" + hex(MessageNano.toByteArray(protoDrvrHmiUsrIfSetg)));
        return "signal30807 отправлен, PEN=" + iActivePen + ", value=" + validatedValue;
    }

    public String lambda$setHmiThemeMode$28(int i) throws Exception {
        int validatedValue = InstrumentClusterModes.requireHmiTheme(i);
        ApiResult write = requireSignals().setHmiThemeModReq(validatedValue);
        requireSuccessfulWrite("signal30787", write);
        return "signal30787=" + result(write) + ", value=" + validatedValue;
    }

    public String lambda$setIndividualTheme$27(boolean z) throws Exception {
        ApiResult drvrIndThemeSetg = requireSignals().setDrvrIndThemeSetg(z ? 1 : 0);
        requireSuccessfulWrite("signal30785", drvrIndThemeSetg);
        return "signal30785=" + result(drvrIndThemeSetg) + ", value=" + (z ? 1 : 0);
    }

    public String lambda$setMultimediaInformationMode$26(int i) throws Exception {
        int validatedValue = InstrumentClusterModes.requireInformationLayer(i);
        ApiResult write = requireSignals().setMmedHmiModStd(validatedValue);
        requireSuccessfulWrite("signal30792", write);
        return "signal30792=" + result(write) + ", value=" + validatedValue;
    }

    public String lambda$setProfileTransferMode$13(int i) throws Exception {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
        rememberProfileTransferModeOnce(readProfileTransferMode());
        ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iRequireSdkMode);
        SystemClock.sleep(220L);
        return "CB33278=" + result(apiResultWriteProfileTransferSdkMode) + ", PA33937=" + readProfileTransferModeStatus();
    }

    public String lambda$setRawProfileTransferMinusOne$15() throws Exception {
        rememberProfileTransferModeOnce(readProfileTransferMode());
        int iSavedProfileTransferMode = savedProfileTransferMode();
        ApiResult apiResultWriteRawProfileTransferMode = writeRawProfileTransferMode(-1);
        requireSuccessfulWrite("RAW CB33278", apiResultWriteRawProfileTransferMode);
        SystemClock.sleep(300L);
        return "raw setIntProperty(33278, GLOBAL, -1)=" + result(apiResultWriteRawProfileTransferMode) + ", rollback=" + iSavedProfileTransferMode + ", PA33937=" + readProfileTransferModeStatus();
    }

    public String lambda$setSettingsActive$2(boolean z) throws Exception {
        return "accepted=" + setFunctionValue(537985280, z);
    }

    public String lambda$setSettingsAr$5(boolean z) throws Exception {
        return "accepted=" + setFunctionValue(IHUD.SETTING_FUNC_HUD_AR_ENGINE, z);
    }

    public String lambda$setUserProfileHudAr$35(boolean z) throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        byte[] bArrRequireRawProfileCloudDataForProfile = requireRawProfileCloudDataForProfile(iRequireActiveProfileId);
        int hudAr = HudProfileWirePatcher.readHudAr(bArrRequireRawProfileCloudDataForProfile);
        rememberUserProfileRawOnce(iRequireActiveProfileId, bArrRequireRawProfileCloudDataForProfile);
        String strWriteAndConfirmRawHudAr = writeAndConfirmRawHudAr(iRequireActiveProfileId, bArrRequireRawProfileCloudDataForProfile, z);
        this.userProfileHudArStatus = "profile=" + iRequireActiveProfileId + ", value=" + (z ? 1 : 0) + ", " + strWriteAndConfirmRawHudAr;
        return "profile=" + iRequireActiveProfileId + ", key 654443008: " + hudAr + "→" + (z ? 1 : 0) + ", readback=" + strWriteAndConfirmRawHudAr;
    }

    public String lambda$setUserProfileHudMode$38(int i) throws Exception {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
        int iRequireActiveProfileId = requireActiveProfileId();
        byte[] bArrRequireRawProfileCloudDataForProfile = requireRawProfileCloudDataForProfile(iRequireActiveProfileId);
        int hudMode = HudProfileWirePatcher.readHudMode(bArrRequireRawProfileCloudDataForProfile);
        rememberUserProfileHudModeOnce(iRequireActiveProfileId, hudMode);
        String strWriteAndConfirmRawHudMode = writeAndConfirmRawHudMode(iRequireActiveProfileId, bArrRequireRawProfileCloudDataForProfile, iRequireSdkMode);
        this.userProfileHudModeStatus = "profile=" + iRequireActiveProfileId + ", field124=" + iRequireSdkMode + ", " + strWriteAndConfirmRawHudMode;
        return "profile=" + iRequireActiveProfileId + ", customId 251660288, field124: " + hudMode + "→" + iRequireSdkMode + ", readback=" + strWriteAndConfirmRawHudMode;
    }

    public String lambda$setVehicleModelClear$30(boolean z) throws Exception {
        ECarXCarProfiletransferManager eCarXCarProfiletransferManagerRequireProfileTransfer = requireProfileTransfer();
        rememberIntOnce(BACKUP_VEHICLE_MODEL, readVehicleModelClear());
        return "CB33284=" + result(eCarXCarProfiletransferManagerRequireProfileTransfer.CB_VehMdlClrReq(z ? 1 : 0)) + ", feedback33943=" + readVehicleModelClear();
    }

    public String lambda$setVfActive$3(boolean z) throws Exception {
        return "result=" + result(requireVfHud().CB_VF_HUD_ActvReq(z ? 1 : 0));
    }

    public String lambda$setVfAr$6(boolean z) throws Exception {
        return "result=" + result(requireVfHud().CB_VF_HUD_ARActvReq(z ? 1 : 0));
    }

    public String lambda$setVfDisplayMode$8(int i) throws Exception {
        return "result=" + result(requireVfHud().CB_HUD_DispModSet(i));
    }

    public String lambda$setVisualFunction$11(int i, int i2) throws Exception {
        requireNoSafeVisualScan();
        this.visualFunctions[i] = i2;
        sendVisualMask();
        return visualMaskDescription();
    }

    public String lambda$setVisualPen$12(int i) throws Exception {
        requireNoSafeVisualScan();
        this.visualPen = i;
        sendVisualMask();
        return visualMaskDescription();
    }

    public void lambda$start$0() {
        appendLog("Подключение к ecarxcar_service…");
        try {
            ECarXCarProxy eCarXCarProxy = new ECarXCarProxy(this.appContext, this);
            this.proxy = eCarXCarProxy;
            eCarXCarProxy.initECarXCar();
        } catch (Throwable th) {
            appendLog("Ошибка подключения: " + shortFailure(th));
        }
        this.worker.removeCallbacks(this.periodicRefresh);
        this.worker.post(this.periodicRefresh);
    }

    public void lambda$startSafeProfileVisualScan$17(int i) {
        if (this.closed) {
            return;
        }
        int i2 = this.profileVisualScanPen;
        boolean z = this.profileVisualScanRunning;
        stopProfileVisualScanInternal();
        if (z) {
            appendLog("01+MASK SAFE: предыдущий цикл прерван; " + restoreVisualMaskBestEffort(i2));
        }
        String strRestoreAllDirtyVisualMasksBestEffort = restoreAllDirtyVisualMasksBestEffort();
        if (!strRestoreAllDirtyVisualMasksBestEffort.isEmpty()) {
            appendLog("01+MASK SAFE: очистка предыдущих проб: " + strRestoreAllDirtyVisualMasksBestEffort);
        }
        this.profileVisualScanPen = -1;
        try {
            int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
            int iActivePen = activePen();
            rememberProfileTransferModeOnce(readProfileTransferMode());
            ApiResult apiResultWriteProfileTransferSdkMode = writeProfileTransferSdkMode(iRequireSdkMode);
            SystemClock.sleep(240L);
            this.profileVisualScanMode = iRequireSdkMode;
            this.profileVisualScanPen = iActivePen;
            this.profileVisualScanIndex = 0;
            this.profileVisualScanAppliedIndex = -1;
            this.profileVisualScanRunning = true;
            String str = "01+MASK SAFE старт: mode=" + modeName(i) + ", PEN=" + this.profileVisualScanPen + ", all=0 → all=1 → F00…F19 по одному OFF → all=1, CB33278=" + result(apiResultWriteProfileTransferSdkMode) + ", PA33937=" + readProfileTransferModeStatus();
            this.lastCommand = str;
            appendLog(str);
            publishSnapshot();
            this.profileVisualScanStep.run();
        } catch (Throwable th) {
            this.profileVisualScanRunning = false;
            String str2 = "01+MASK SAFE старт: ERROR " + shortFailure(th) + "; " + restoreVisualMaskBestEffort(this.profileVisualScanPen);
            this.lastCommand = str2;
            appendLog(str2);
            publishSnapshot();
        }
    }

    public static String modeName(int i) {
        if (i == 1212435456) {
            return "DYNAMIC AUTO";
        }
        if (i == 1212435457) {
            return "DYNAMIC 0 IntellGuide";
        }
        if (i == 1212435458) {
            return "DYNAMIC 1 IntellDrv";
        }
        if (i == 1212435459) {
            return "DYNAMIC 2 AR";
        }
        if (i == 1212435460) {
            return "DYNAMIC 3 Simple";
        }
        switch (i) {
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
                return Integer.toString(i);
        }
    }

    private String profileVisualScanDescription() {
        if (this.profileVisualScanMode < 0) {
            return "не запущен";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.profileVisualScanRunning ? "ИДЁТ" : "ПАУЗА").append(" · mode=").append(modeName(this.profileVisualScanMode)).append(" · PEN=").append(this.profileVisualScanPen);
        if (this.profileVisualScanAppliedIndex >= 0) {
            sb.append(" · на HUD сейчас F").append(twoDigits(this.profileVisualScanAppliedIndex)).append("=0, остальные=1");
        } else if (this.profileVisualScanRunning && this.profileVisualScanIndex > 0) {
            sb.append(" · baseline/restore");
        }
        if (this.profileVisualScanRunning) {
            sb.append(" · шаг ").append(this.profileVisualScanIndex).append('/').append(HudVisualProbePlan.stepCount()).append(" · следующий через 3,6 с");
        }
        return sb.toString();
    }

    public void publishSnapshot() {
        if (this.closed) {
            return;
        }
        final boolean z = (this.root == null || this.vfHud == null || this.signals == null || this.profileManager == null || this.profileTransfer == null) ? false : true;
        StringBuilder sb = new StringBuilder(1200);
        sb.append("СОЕДИНЕНИЕ: ").append(z ? "ГОТОВО" : "ОЖИДАНИЕ").append("\nПоследняя команда: ");
        sb.append(this.lastCommand).append("\n\nDump-derived profile/DIM state\n  Active profile / PEN: ");
        sb.append(readActiveProfile()).append(" / ").append(readActivePen()).append("\n  ProfileTransfer HUD mode CB33278/PA33937: ");
        sb.append(readProfileTransferModeStatus()).append("\n  Фоновый автоповтор: УДАЛЁН в 0.21\n  UserProfile HUD AR key 654443008: ");
        sb.append(this.userProfileHudArStatus).append("\n  UserProfile HUD mode key 251660288 / field124: ");
        sb.append(this.userProfileHudModeStatus).append("\n  Vehicle model clear CB33284/PA33943: ");
        sb.append(readVehicleModelClear()).append("\n  Driver display feedback 30873: ");
        sb.append(readDriverDisplayTheme()).append("\n\nПоиск 01: ");
        sb.append(profileVisualScanDescription()).append("\n\nAdaptAPI Settings\n  HUD_ACTIVE 0x");
        sb.append(Integer.toHexString(537985280)).append(": ").append(readFunction(537985280)).append("\n  HUD_AR_ENGINE 0x");
        sb.append(Integer.toHexString(IHUD.SETTING_FUNC_HUD_AR_ENGINE)).append(": ").append(readFunction(IHUD.SETTING_FUNC_HUD_AR_ENGINE)).append("\n\nSelective HUD content (новый путь)\n");
        for (HudDisplayFunction hudDisplayFunction : DISPLAY_FUNCTIONS) {
            sb.append("  ").append(hudDisplayFunction.label).append(" 0x").append(Integer.toHexString(hudDisplayFunction.functionId)).append(": ").append(readFunction(hudDisplayFunction.functionId)).append('\n');
        }
        sb.append("\nVFHUD public attributes\n  PA_VF_HUD_ActvSts: ");
        sb.append(readPaHudActive()).append("\n  PA_VF_HUD_ARActvSts: ");
        sb.append(readPaArActive()).append("\n  PA_HUD_DispModSet: ");
        sb.append(readPaMode()).append("\n\nDirect IHU → DIM\n  HudActvReq: ");
        sb.append(readSignal(SignalRead.HUD_REQUEST)).append("\n  HudActvSts: ");
        sb.append(readSignal(SignalRead.HUD_ACTIVE_STATUS)).append("\n  HudSts: ");
        sb.append(readSignal(SignalRead.HUD_STATUS)).append("\n  DIM priority/resource: ");
        sb.append(readSignal(SignalRead.DIM_PRIORITY)).append(" / ").append(readSignal(SignalRead.DIM_RESOURCE)).append("\n\nСырые данные скорости (только чтение)\n  extended / unit / value: ");
        sb.append(readSignal(SignalRead.SPEED_EXTENDED)).append(" / ").append(readSignal(SignalRead.SPEED_UNIT)).append(" / ").append(readSignal(SignalRead.SPEED_VALUE)).append("\n\nЛокальная visual mask: ");
        sb.append(visualMaskDescription()).append('\n');
        final String string = sb.toString();
        StringBuilder sb2 = new StringBuilder();
        Iterator<String> it = this.logLines.iterator();
        while (it.hasNext()) {
            sb2.append(it.next()).append('\n');
        }
        final String string2 = sb2.toString();
        this.main.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.3
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$publishSnapshot$45(string, string2, z);
            }
        });
    }

    private String readActivePen() {
        try {
            int profPenSts1 = requireSignals().getProfPenSts1();
            if (isProfilePen(profPenSts1)) {
                return profPenSts1 + " (ProfPenSts1)";
            }
            int iActiveProfile = activeProfile();
            return (isProfilePen(iActiveProfile) ? new StringBuilder().append(iActiveProfile).append(" (PA33845 fallback; ProfPenSts1=").append(profPenSts1).append(")") : new StringBuilder().append("ERROR ProfPenSts1=").append(profPenSts1).append(", PA33845=").append(iActiveProfile)).toString();
        } catch (Throwable th) {
            try {
                return activeProfile() + " (PA33845 fallback; ProfPenSts1=" + shortFailure(th) + ")";
            } catch (Throwable th2) {
                return "ERROR " + shortFailure(th2);
            }
        }
    }

    private String readActiveProfile() {
        try {
            return Integer.toString(activeProfile());
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private String readDriverDisplayTheme() {
        try {
            return Integer.toString(requireSignals().getDrvrDispSetgStsSyncn());
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    /* JADX WARN: Unreachable blocks removed: 3, instructions: 4 */
    private String readFunction(int i) {
        FunctionStatus functionStatusIsFunctionSupported;
        String string;
        String string2 = "?";
        CarFunction carFunction = this.carFunction;
        if (carFunction == null) {
            return "—";
        }
        try {
            try {
                functionStatusIsFunctionSupported = carFunction.isFunctionSupported(i, Integer.MIN_VALUE);
            } catch (Throwable th) {
                functionStatusIsFunctionSupported = carFunction.isFunctionSupported(i);
            }
            try {
                string = Arrays.toString(carFunction.getSupportedFunctionValue(i, Integer.MIN_VALUE));
            } catch (Throwable th2) {
                string = "?";
            }
            try {
                String string22 = Integer.toString(carFunction.getFunctionValue(i, Integer.MIN_VALUE));
                string2 = string22;
            } catch (Throwable th3) {
                try {
                    string2 = Integer.toString(carFunction.getFunctionValue(i));
                } catch (Throwable th4) {
                }
            }
            return "support=" + functionStatusIsFunctionSupported + ", value=" + string2 + ", allowed=" + string;
        } catch (Throwable th5) {
            return "ERROR " + shortFailure(th5);
        }
    }

    private String readPaArActive() {
        ECarXCarVfhudManager eCarXCarVfhudManager = this.vfHud;
        if (eCarXCarVfhudManager == null) {
            return "—";
        }
        try {
            PATypes.PA_VF_HUD_ARActvSts pA_VF_HUD_ARActvSts = eCarXCarVfhudManager.getPA_VF_HUD_ARActvSts();
            return pA_VF_HUD_ARActvSts == null ? "null" : pA_VF_HUD_ARActvSts.toString();
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private String readPaHudActive() {
        ECarXCarVfhudManager eCarXCarVfhudManager = this.vfHud;
        if (eCarXCarVfhudManager == null) {
            return "—";
        }
        try {
            PATypes.PA_VF_HUD_ActvSts pA_VF_HUD_ActvSts = eCarXCarVfhudManager.getPA_VF_HUD_ActvSts();
            return pA_VF_HUD_ActvSts == null ? "null" : pA_VF_HUD_ActvSts.toString();
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private String readPaMode() {
        ECarXCarVfhudManager eCarXCarVfhudManager = this.vfHud;
        if (eCarXCarVfhudManager == null) {
            return "—";
        }
        try {
            PATypes.PA_HUD_DispModSet pA_HUD_DispModSet = eCarXCarVfhudManager.getPA_HUD_DispModSet();
            return pA_HUD_DispModSet == null ? "null" : pA_HUD_DispModSet.toString();
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private int readProfileTransferMode() {
        try {
            PATypes.PA_HudDispModSetgReq pA_HudDispModSetgReq = requireProfileTransfer().getPA_HudDispModSetgReq();
            if (pA_HudDispModSetgReq == null) {
                return -1;
            }
            return pA_HudDispModSetgReq.getData();
        } catch (Throwable th) {
            return -1;
        }
    }

    private String readProfileTransferModeStatus() {
        try {
            PATypes.PA_HudDispModSetgReq pA_HudDispModSetgReq = requireProfileTransfer().getPA_HudDispModSetgReq();
            return pA_HudDispModSetgReq == null ? "null" : pA_HudDispModSetgReq.toString();
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private String readSignal(SignalRead signalRead) {
        int hudActvReq;
        CarSignalManager carSignalManager = this.signals;
        if (carSignalManager == null) {
            return "—";
        }
        try {
            switch (signalRead) {
                case HUD_REQUEST:
                    hudActvReq = carSignalManager.getHudActvReq();
                    break;
                case HUD_ACTIVE_STATUS:
                    hudActvReq = carSignalManager.getHudActvSts();
                    break;
                case HUD_STATUS:
                    hudActvReq = carSignalManager.getHudSts();
                    break;
                case DIM_PRIORITY:
                    hudActvReq = carSignalManager.getNetDIMActvtPrio();
                    break;
                case DIM_RESOURCE:
                    hudActvReq = carSignalManager.getNetDIMActvtResourceGroup();
                    break;
                case SPEED_EXTENDED:
                    hudActvReq = carSignalManager.getVehSpdExtdIndcnForUseInt();
                    break;
                case SPEED_UNIT:
                    hudActvReq = carSignalManager.getVehSpdIndcdVeSpdIndcdUnit();
                    break;
                case SPEED_VALUE:
                    hudActvReq = carSignalManager.getVehSpdIndcdVehSpdIndcd();
                    break;
                default:
                    return "?";
            }
            return Integer.toString(hudActvReq);
        } catch (Throwable th) {
            return "ERROR " + shortFailure(th);
        }
    }

    private ECarXCarPropertyManagerBase rawSignalPropertyManager() throws Exception {
        CarSignalManager signalManager = this.signals;
        if (signalManager == null) {
            throw new IllegalStateException("CarSignalManager ещё не подключён");
        }
        Class<?> type = signalManager.getClass();
        Field managerField = null;
        while (type != null && managerField == null) {
            try {
                managerField = type.getDeclaredField("mMgr");
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        if (managerField == null) {
            throw new IllegalStateException("поле CarSignalManager.mMgr не найдено");
        }
        managerField.setAccessible(true);
        Object manager = managerField.get(signalManager);
        if (!(manager instanceof ECarXCarPropertyManagerBase)) {
            throw new IllegalStateException("неожиданный тип mMgr: " + (manager == null ? "null" : manager.getClass().getName()));
        }
        return (ECarXCarPropertyManagerBase) manager;
    }

    private String readProbeInt(ProbeIntReader reader) {
        if (this.signals == null) {
            return "—";
        }
        try {
            return Integer.toString(reader.read());
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private String readNetIhuActivation() {
        try {
            byte[] raw = rawSignalPropertyManager().getBytesProperty(CarSignalManager.SignalId_NetIHUActvt, 1);
            if (raw == null) {
                return "null";
            }
            VendorVehicleHalPAProto.ProtoNetIHUActvt value = VendorVehicleHalPAProto.ProtoNetIHUActvt.parseFrom(raw);
            return value.netIHUActvtPrio + "/" + value.netIHUActvtResourceGroup + " raw=" + Base64.encodeToString(raw, 2);
        } catch (Throwable failure) {
            return "ERROR " + shortFailure(failure);
        }
    }

    private ClusterSignalSnapshot buildClusterProbeSnapshot(String phase) {
        final CarSignalManager signalManager = this.signals;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("NetIHU 28882 prio/resource", readNetIhuActivation());
        values.put("NetDIM 30937 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda22
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDIMActvtPrio();
            }
        }));
        values.put("NetDIM 30938 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda5
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDIMActvtResourceGroup();
            }
        }));
        values.put("NetASDM 29132 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda10
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetASDMActvtPrio();
            }
        }));
        values.put("NetASDM 29133 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda12
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetASDMActvtResourceGroup();
            }
        }));
        values.put("NetDMM 29134 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda13
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDMMActvtPrio();
            }
        }));
        values.put("NetDMM 29135 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda14
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDMMActvtResourceGroup();
            }
        }));
        values.put("NetDVR 29136 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda15
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDVRActvtPrio();
            }
        }));
        values.put("NetDVR 29137 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda16
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetDVRActvtResourceGroup();
            }
        }));
        values.put("NetPAS 29138 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda17
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetPASActvtPrio();
            }
        }));
        values.put("NetPAS 29139 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda18
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetPASActvtResourceGroup();
            }
        }));
        values.put("NetTCAM 31659 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda23
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetTCAMActvtPrio();
            }
        }));
        values.put("NetTCAM 31660 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda24
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetTCAMActvtResourceGroup();
            }
        }));
        values.put("NetVGM 31661 prio", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda25
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetVGMActvtPrio();
            }
        }));
        values.put("NetVGM 31662 resource", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda26
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNetVGMActvtResourceGroup();
            }
        }));
        values.put("DisConfigNotify 30872", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda27
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getDisConfigNotify();
            }
        }));
        values.put("IHUSetDispAD 28965", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda28
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getIHUSetDispAD();
            }
        }));
        values.put("DrvrDispFeedback 30873", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda1
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getDrvrDispSetgStsSyncn();
            }
        }));
        values.put("DrvrAsscDisp 28952", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda2
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getDrvrAsscSysDisp();
            }
        }));
        values.put("DrvrAsscSts 28953", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda3
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getDrvrAsscSysSts();
            }
        }));
        values.put("NavActvMenuReq", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda4
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getNavActvMenuReq();
            }
        }));
        values.put("DIM NaviMode", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda6
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return HudLabController.this.lambda$buildClusterProbeSnapshot$20();
            }
        }));
        values.put("HUD req", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda7
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getHudActvReq();
            }
        }));
        values.put("HUD active", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda8
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getHudActvSts();
            }
        }));
        values.put("HUD status", readProbeInt(new ProbeIntReader() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda9
            @Override // dezz.status.hudlab.HudLabController.ProbeIntReader
            public final int read() {
                return signalManager.getHudSts();
            }
        }));
        return new ClusterSignalSnapshot(phase, SystemClock.elapsedRealtime(), values);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ int lambda$buildClusterProbeSnapshot$20() throws Exception {
        IDimMenuInteraction dim = this.dimMenuInteraction;
        if (dim == null) {
            throw new IllegalStateException("DimMenuInteraction не подключён");
        }
        return dim.getNaviMode();
    }

    void captureClusterProbeState(final String phase, final ClusterProbeCallback callback) {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda19
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$captureClusterProbeState$25(phase, callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$captureClusterProbeState$25(String phase, final ClusterProbeCallback callback) {
        final ClusterSignalSnapshot snapshot = buildClusterProbeSnapshot(phase);
        appendLog("ПРИБОРКА TRACE " + phase + ": " + snapshot.values);
        this.main.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$captureClusterProbeState$24(callback, snapshot);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$captureClusterProbeState$24(ClusterProbeCallback callback, ClusterSignalSnapshot snapshot) {
        if (!this.closed && callback != null) {
            callback.onCaptured(snapshot);
        }
    }

    private int readVehicleModelClear() {
        try {
            PATypes.PA_VehMdlClrReq pA_VehMdlClrReq = requireProfileTransfer().getPA_VehMdlClrReq();
            if (pA_VehMdlClrReq == null) {
                return -1;
            }
            return pA_VehMdlClrReq.getData();
        } catch (Throwable th) {
            return -1;
        }
    }

    private void rememberIntOnce(String str, int i) {
        if (i < 0 || this.backups.contains(str)) {
            return;
        }
        this.backups.edit().putInt(str, i).apply();
    }

    private void rememberProfileTransferModeOnce(int i) {
        if (!HudProfileTransferMode.isSdkMode(i) || this.backups.contains(BACKUP_PROFILE_TRANSFER_MODE)) {
            return;
        }
        this.backups.edit().putInt(BACKUP_PROFILE_TRANSFER_MODE, i).apply();
    }

    private void rememberUserProfileHudModeOnce(int i, int i2) {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i2);
        String strUserProfileHudModeBackupKey = userProfileHudModeBackupKey(i);
        if (!this.backups.contains(strUserProfileHudModeBackupKey) && !this.backups.edit().putInt(strUserProfileHudModeBackupKey, iRequireSdkMode).commit()) {
            throw new IllegalStateException("не удалось сохранить исходный field124; запись HUD mode заблокирована");
        }
    }

    private void rememberUserProfileRawOnce(int i, byte[] bArr) {
        String strUserProfileBackupKey = userProfileBackupKey(i);
        if (!this.backups.contains(strUserProfileBackupKey) && !this.backups.edit().putString(strUserProfileBackupKey, Base64.encodeToString(bArr, 2)).commit()) {
            throw new IllegalStateException("не удалось синхронно сохранить исходный raw-профиль; запись AR заблокирована");
        }
    }

    private int requireActiveProfileId() throws Exception {
        int iActiveProfile = activeProfile();
        if (isProfilePen(iActiveProfile)) {
            return iActiveProfile;
        }
        throw new IllegalStateException("PA33845 вернул невалидный активный профиль " + iActiveProfile + " (ожидалось 0…13)");
    }

    private CarFunction requireCarFunction() {
        CarFunction carFunction = this.carFunction;
        if (carFunction != null) {
            return carFunction;
        }
        throw new IllegalStateException("CarFunction ещё не подключён");
    }

    private void requireExpectedActiveProfileId(int i) throws Exception {
        int iRequireActiveProfileId = requireActiveProfileId();
        if (iRequireActiveProfileId != i) {
            throw new IllegalStateException("активный профиль PA33845 изменился: ожидался " + i + ", получен " + iRequireActiveProfileId + "; запись отменена");
        }
    }

    private void requireNoSafeVisualScan() {
        if (this.profileVisualScanRunning) {
            throw new IllegalStateException("сначала остановите 01+MASK SAFE; параллельная MASK-команда запрещена");
        }
    }

    private ECarXCarProfileManager requireProfileManager() {
        ECarXCarProfileManager eCarXCarProfileManager = this.profileManager;
        if (eCarXCarProfileManager != null) {
            return eCarXCarProfileManager;
        }
        throw new IllegalStateException("ProfileManager ещё не подключён");
    }

    private ECarXCarProfiletransferManager requireProfileTransfer() {
        ECarXCarProfiletransferManager eCarXCarProfiletransferManager = this.profileTransfer;
        if (eCarXCarProfiletransferManager != null) {
            return eCarXCarProfiletransferManager;
        }
        throw new IllegalStateException("ProfileTransfer ещё не подключён");
    }

    private byte[] requireRawProfileCloudData() {
        byte[] byteCBValueForUt = requireProfileManager().getByteCBValueForUt(33873);
        if (byteCBValueForUt == null || byteCBValueForUt.length == 0) {
            throw new IllegalStateException("PA33873 ProfileCloudData пуст; дождитесь загрузки профиля");
        }
        HudProfileWirePatcher.readHudAr(byteCBValueForUt);
        return (byte[]) byteCBValueForUt.clone();
    }

    private byte[] requireRawProfileCloudDataForProfile(int i) throws Exception {
        requireExpectedActiveProfileId(i);
        byte[] bArrRequireRawProfileCloudData = requireRawProfileCloudData();
        requireExpectedActiveProfileId(i);
        return bArrRequireRawProfileCloudData;
    }

    private CarSignalManager requireSignals() {
        CarSignalManager carSignalManager = this.signals;
        if (carSignalManager != null) {
            return carSignalManager;
        }
        throw new IllegalStateException("DIM signals ещё не подключены");
    }

    private IDimMenuInteraction requireDimMenuInteraction() {
        IDimMenuInteraction menu = this.dimMenuInteraction;
        if (menu == null) {
            IDimMenuInteraction menu2 = new DimMenuInteraction(this.appContext);
            this.dimMenuInteraction = menu2;
            return menu2;
        }
        return menu;
    }

    private static void requireSuccessfulWrite(String str, ApiResult apiResult) {
        if (apiResult != ApiResult.SUCCEED) {
            throw new IllegalStateException(str + " вернул " + result(apiResult));
        }
    }

    private ECarXCarVfhudManager requireVfHud() {
        ECarXCarVfhudManager eCarXCarVfhudManager = this.vfHud;
        if (eCarXCarVfhudManager != null) {
            return eCarXCarVfhudManager;
        }
        throw new IllegalStateException("VFHUD ещё не подключён");
    }

    private String restoreAllDirtyVisualMasksBestEffort() {
        if (this.dirtyVisualMaskPens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator it = new ArrayList(this.dirtyVisualMaskPens).iterator();
        while (it.hasNext()) {
            int iIntValue = ((Integer) it.next()).intValue();
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(restoreVisualMaskBestEffort(iIntValue));
        }
        return sb.toString();
    }

    public String restoreVisualMaskBestEffort(int i) {
        if (!isProfilePen(i)) {
            return "all=1 не отправлен: активный PEN ещё не был определён";
        }
        try {
            this.visualPen = i;
            Arrays.fill(this.visualFunctions, 1);
            sendVisualMask();
            return "PEN=" + i + " восстановлен all=1";
        } catch (Throwable th) {
            return "ОШИБКА восстановления all=1: " + shortFailure(th);
        }
    }

    private static String result(ApiResult apiResult) {
        return apiResult == null ? "null" : apiResult.name();
    }

    private void runCommand(String str, Command command) {
        runCommand(str, command, null);
    }

    private void runCommand(final String str, final Command command, final Runnable runnable) {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.4
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$runCommand$44(command, str, runnable);
            }
        });
    }

    private int savedProfileTransferMode() {
        if (!this.backups.contains(BACKUP_PROFILE_TRANSFER_MODE)) {
            throw new IllegalStateException("нет валидного исходного режима 0…3 — RAW-команда и откат запрещены");
        }
        int i = this.backups.getInt(BACKUP_PROFILE_TRANSFER_MODE, -1);
        if (HudProfileTransferMode.isSdkMode(i)) {
            return i;
        }
        throw new IllegalStateException("резервная копия режима невалидна: " + i);
    }

    public void sendVisualMask() throws Exception {
        HudVisualProbePlan.requireProfilePen(this.visualPen);
        VendorVehicleHalPAProto.ProtoHudVisFctSetgReq protoHudVisFctSetgReq = new VendorVehicleHalPAProto.ProtoHudVisFctSetgReq();
        for (int i = 0; i < this.visualFunctions.length; i++) {
            protoHudVisFctSetgReq.getClass().getField(String.format(Locale.ROOT, "hudVisFctSetgReqHudFct%02d", Integer.valueOf(i))).setInt(protoHudVisFctSetgReq, this.visualFunctions[i]);
        }
        int i2 = this.visualPen;
        protoHudVisFctSetgReq.hudVisFctSetgReqPen = i2;
        byte[] byteArray = MessageNano.toByteArray(protoHudVisFctSetgReq);
        requireSignals().setHudVisFctSetgReq(protoHudVisFctSetgReq);
        if (isAllOneVisualMask()) {
            this.dirtyVisualMaskPens.remove(Integer.valueOf(this.visualPen));
        } else {
            this.dirtyVisualMaskPens.add(Integer.valueOf(this.visualPen));
        }
        appendLog("TX signal30816 / VHAL 0x21707860 · " + visualMaskDescription() + " · protobuf=" + hex(byteArray));
    }

    private void setDisplayFunction(HudDisplayFunction hudDisplayFunction, boolean z) {
        setDisplayFunctions(hudDisplayFunction.label, z, hudDisplayFunction);
    }

    private void setDisplayFunctions(String str, final boolean z, final HudDisplayFunction... hudDisplayFunctionArr) {
        runCommand("HUD DISPLAY " + str + "=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.5
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDisplayFunctions$41(hudDisplayFunctionArr, z);
            }
        });
    }

    /* JADX WARN: Type inference failed for: r1v2, types: [java.lang.Throwable] */
    private boolean setFunctionValue(int i, boolean z) {
        CarFunction carFunctionRequireCarFunction = requireCarFunction();
        try {
            return carFunctionRequireCarFunction.setFunctionValue(i, Integer.MIN_VALUE, z ? 1 : 0);
        } catch (Throwable th) {
            return carFunctionRequireCarFunction.setFunctionValue(i, z ? 1 : 0);
        }
    }

    public static String shortFailure(Throwable th) {
        String simpleName = th.getClass().getSimpleName();
        String message = th.getMessage();
        return (message == null || message.trim().isEmpty()) ? simpleName : simpleName + ": " + message.trim();
    }

    private void stopProfileVisualScanInternal() {
        this.worker.removeCallbacks(this.profileVisualScanStep);
        this.profileVisualScanRunning = false;
    }

    private static String twoDigits(int i) {
        return String.format(Locale.ROOT, "%02d", Integer.valueOf(i));
    }

    private static String userProfileBackupKey(int i) {
        return BACKUP_USER_PROFILE_RAW_PREFIX + i;
    }

    private static String userProfileHudModeBackupKey(int i) {
        return BACKUP_USER_PROFILE_HUD_MODE_PREFIX + i;
    }

    private static String value(boolean z) {
        return z ? "ON" : "OFF";
    }

    private String visualMaskDescription() {
        StringBuilder sbAppend = new StringBuilder("PEN=").append(this.visualPen).append(" [");
        for (int i = 0; i < this.visualFunctions.length; i++) {
            if (i > 0) {
                sbAppend.append(' ');
            }
            sbAppend.append(String.format(Locale.ROOT, "%02d:%d", Integer.valueOf(i), Integer.valueOf(this.visualFunctions[i])));
        }
        return sbAppend.append(']').toString();
    }

    private String writeAndConfirmRawHudAr(int i, byte[] bArr, boolean z) throws Exception {
        int hudAr = -1;
        byte[] bArrPatchHudAr = HudProfileWirePatcher.patchHudAr(bArr, z);
        if (!HudProfileWirePatcher.isExactPatch(bArr, bArrPatchHudAr, z)) {
            throw new IllegalStateException("проверка точечного изменения поля vfhudbyte0 не пройдена");
        }
        requireExpectedActiveProfileId(i);
        requireProfileManager().setbytesPropertyForUt(33264, bArrPatchHudAr);
        String strShortFailure = "нет данных";
        for (int i2 = 0; i2 < 6; i2++) {
            SystemClock.sleep(PROFILE_READBACK_DELAY_MS);
            requireExpectedActiveProfileId(i);
            try {
                byte[] bArrRequireRawProfileCloudData = requireRawProfileCloudData();
                requireExpectedActiveProfileId(i);
                hudAr = HudProfileWirePatcher.readHudAr(bArrRequireRawProfileCloudData);
                strShortFailure = "value=" + hudAr + ", bytes=" + bArrRequireRawProfileCloudData.length;
            } catch (Throwable th) {
                strShortFailure = shortFailure(th);
            }
            if (hudAr == (z ? 1 : 0)) {
                return strShortFailure;
            }
        }
        throw new IllegalStateException("CB33264 отправлен, но PA33873 не подтвердил " + (z ? 1 : 0) + ": " + strShortFailure);
    }

    private String writeAndConfirmRawHudMode(int i, byte[] bArr, int i2) throws Exception {
        int hudMode = -1;
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i2);
        byte[] bArrPatchHudMode = HudProfileWirePatcher.patchHudMode(bArr, iRequireSdkMode);
        if (!HudProfileWirePatcher.isExactHudModePatch(bArr, bArrPatchHudMode, iRequireSdkMode)) {
            throw new IllegalStateException("проверка точечного изменения profiletransferbyte3/field124 не пройдена");
        }
        requireExpectedActiveProfileId(i);
        requireProfileManager().setbytesPropertyForUt(33264, bArrPatchHudMode);
        String strShortFailure = "нет данных";
        for (int i3 = 0; i3 < 6; i3++) {
            SystemClock.sleep(PROFILE_READBACK_DELAY_MS);
            requireExpectedActiveProfileId(i);
            try {
                byte[] bArrRequireRawProfileCloudData = requireRawProfileCloudData();
                requireExpectedActiveProfileId(i);
                hudMode = HudProfileWirePatcher.readHudMode(bArrRequireRawProfileCloudData);
                String strShortFailure2 = "field124=" + hudMode + ", bytes=" + bArrRequireRawProfileCloudData.length;
                strShortFailure = strShortFailure2;
            } catch (Throwable th) {
                strShortFailure = shortFailure(th);
            }
            if (hudMode == iRequireSdkMode) {
                return strShortFailure;
            }
        }
        throw new IllegalStateException("CB33264 отправлен, но PA33873 не подтвердил field124=" + iRequireSdkMode + ": " + strShortFailure);
    }

    private ApiResult writeProfileTransferSdkMode(int i) {
        int iRequireSdkMode = HudProfileTransferMode.requireSdkMode(i);
        ApiResult apiResultCB_HudDispModSetgReq = requireProfileTransfer().CB_HudDispModSetgReq(iRequireSdkMode);
        requireSuccessfulWrite("CB33278", apiResultCB_HudDispModSetgReq);
        rememberProfileTransferModeOnce(iRequireSdkMode);
        return apiResultCB_HudDispModSetgReq;
    }

    private ApiResult writeRawProfileTransferMode(int i) throws Exception {
        ECarXCarProfiletransferManager eCarXCarProfiletransferManagerRequireProfileTransfer = requireProfileTransfer();
        Class<?> superclass = eCarXCarProfiletransferManagerRequireProfileTransfer.getClass();
        Field declaredField = null;
        while (superclass != null && declaredField == null) {
            try {
                declaredField = superclass.getDeclaredField("mMgr");
            } catch (NoSuchFieldException e) {
                superclass = superclass.getSuperclass();
            }
        }
        if (declaredField == null) {
            throw new IllegalStateException("поле ECARX mMgr не найдено");
        }
        declaredField.setAccessible(true);
        Object obj = declaredField.get(eCarXCarProfiletransferManagerRequireProfileTransfer);
        if (obj instanceof ECarXCarPropertyManagerBase) {
            return ((ECarXCarPropertyManagerBase) obj).setIntProperty(33278, 1, i);
        }
        throw new IllegalStateException("ECARX mMgr имеет неожиданный тип " + (obj == null ? "null" : obj.getClass().getName()));
    }

    void applyHeldVisualBaseline(final int i, int i2) {
        final int i3 = i2 == 0 ? 0 : 1;
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.6
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$applyHeldVisualBaseline$19(i, i3);
            }
        });
    }

    void applyHeldVisualProbe(final int i, final int i2) {
        if (i2 < 0 || i2 >= this.visualFunctions.length) {
            return;
        }
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.7
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$applyHeldVisualProbe$18(i, i2);
            }
        });
    }

    void applyPersistentHudMode(final int i, String str) {
        runCommand(str, new Command() { // from class: dezz.status.hudlab.HudLabController.8
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$applyPersistentHudMode$14(i);
            }
        });
    }

    void close() {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.9
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$close$1();
            }
        });
    }

    String currentVisualMask() {
        return visualMaskDescription();
    }

    void markProfileVisualScanFound() {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.10
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$markProfileVisualScanFound$20();
            }
        });
    }

    @Override // com.ecarx.xui.adaptapi.ECarXCarProxy.ECarXCarProxyMethod
    public void onECarXCarServiceConnected(final ECarXCar eCarXCar, final CarSignalManager carSignalManager) {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.11
            @Override // java.lang.Runnable
            public final void run() {
                try {
                    HudLabController.this.lambda$onECarXCarServiceConnected$42(
                            eCarXCar,
                            carSignalManager);
                } catch (Throwable failure) {
                    HudLabController.this.lastCommand =
                            "ECARX connect ERROR " + shortFailure(failure);
                    HudLabController.this.appendLog(HudLabController.this.lastCommand);
                    HudLabController.this.publishSnapshot();
                }
            }
        });
    }

    @Override // com.ecarx.xui.adaptapi.ECarXCarProxy.ECarXCarProxyMethod
    public void onECarXCarServiceDeath() {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.12
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$onECarXCarServiceDeath$43();
            }
        });
    }

    void persistCurrentProfileSettings() {
        runCommand("Сохранить текущие профильные настройки", new Command() { // from class: dezz.status.hudlab.HudLabController.13
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$persistCurrentProfileSettings$33();
            }
        });
    }

    void pulseProfileTransferApply() {
        runCommand("Применить ProfileTransfer", new Command() { // from class: dezz.status.hudlab.HudLabController.14
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$pulseProfileTransferApply$34();
            }
        });
    }

    void refreshNow() {
        this.worker.post(new Runnable() {
            @Override
            public void run() {
                publishSnapshot();
            }
        });
    }

    void refreshUserProfileHudAr() {
        runCommand("UserProfile HUD AR: чтение", new Command() { // from class: dezz.status.hudlab.HudLabController.15
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$refreshUserProfileHudAr$36();
            }
        });
    }

    void refreshUserProfileHudMode() {
        runCommand("UserProfile FIELD124 HUD mode: чтение", new Command() { // from class: dezz.status.hudlab.HudLabController.16
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$refreshUserProfileHudMode$39();
            }
        });
    }

    void reloadActiveProfile() {
        runCommand("Перезагрузить активный профиль (откат transient-настроек)", new Command() { // from class: dezz.status.hudlab.HudLabController.17
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$reloadActiveProfile$32();
            }
        });
    }

    void restoreAllDisplayElements() {
        setDisplayFunctions("все пять DISPLAY_*", true, DISPLAY_FUNCTIONS);
    }

    void restoreProfileTransferMode() {
        runCommand("01 ProfileTransfer mode: откат", new Command() { // from class: dezz.status.hudlab.HudLabController.18
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$restoreProfileTransferMode$16();
            }
        });
    }

    void restoreProfileVisualSearch() {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.19
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$restoreProfileVisualSearch$21();
            }
        });
    }

    void restoreUserProfileHudAr() {
        runCommand("UserProfile HUD AR: точный откат", new Command() { // from class: dezz.status.hudlab.HudLabController.20
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$restoreUserProfileHudAr$37();
            }
        });
    }

    void restoreUserProfileHudMode() {
        runCommand("UserProfile FIELD124 HUD mode: точный откат", new Command() { // from class: dezz.status.hudlab.HudLabController.21
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$restoreUserProfileHudMode$40();
            }
        });
    }

    void restoreVehicleModelClear() {
        runCommand("10 Vehicle model clear: откат", new Command() { // from class: dezz.status.hudlab.HudLabController.22
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$restoreVehicleModelClear$31();
            }
        });
    }

    void setActiveProfileDimMode(final int i) {
        runCommand("02 CEM HUD mode=" + modeName(i) + " для активного PEN", new Command() { // from class: dezz.status.hudlab.HudLabController.23
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setActiveProfileDimMode$22(i);
            }
        });
    }

    void setActiveProfileVisualMask(final boolean z) {
        runCommand("03 active-PEN visual mask=".concat(z ? "HIDE" : "SHOW"), new Command() { // from class: dezz.status.hudlab.HudLabController.24
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setActiveProfileVisualMask$23(z);
            }
        });
    }

    void setAllActivationChannels(final boolean z) {
        runCommand("Все каналы HUD=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.25
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setAllActivationChannels$7(z);
            }
        });
    }

    void setAllVisualFunctions(int i) {
        final int i2 = i == 0 ? 0 : 1;
        runCommand("HUD visual mask: PEN=" + this.visualPen + ", все=" + i2, new Command() { // from class: dezz.status.hudlab.HudLabController.26
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setAllVisualFunctions$10(i2);
            }
        });
    }

    void setDimActive(final boolean z) {
        runCommand("DIM HudDispActvReq=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.27
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDimActive$4(z);
            }
        });
    }

    void setDimDisplayMode(final int i, final int i2) {
        runCommand("DIM mode=" + modeName(i) + ", PEN=" + i2, new Command() { // from class: dezz.status.hudlab.HudLabController.28
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDimDisplayMode$9(i, i2);
            }
        });
    }

    void setDisplayBtPhone(boolean z) {
        setDisplayFunction(DISPLAY_BT_PHONE, z);
    }

    void setDisplayDriveEnvironment(boolean z) {
        setDisplayFunction(DISPLAY_DRIVE_ENVIRONMENT, z);
    }

    void setDisplayMedia(boolean z) {
        setDisplayFunction(DISPLAY_MEDIA, z);
    }

    void setDisplayNavigation(boolean z) {
        setDisplayFunction(DISPLAY_NAVIGATION, z);
    }

    void setDisplaySafety(boolean z) {
        setDisplayFunction(DISPLAY_SAFETY, z);
    }

    void setDriverDisplayTheme(final int i) {
        runCommand("09 Driver display theme=" + i, new Command() { // from class: dezz.status.hudlab.HudLabController.29
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDriverDisplayTheme$29(i);
            }
        });
    }

    void setDriverHmiBackground(final int i) {
        runCommand("04 Driver HMI background=" + i, new Command() { // from class: dezz.status.hudlab.HudLabController.30
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDriverHmiBackground$24(i);
            }
        });
    }

    void setDriverHmiInterface(final int i) {
        runCommand("05 Driver HMI UI=" + i, new Command() { // from class: dezz.status.hudlab.HudLabController.31
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setDriverHmiInterface$25(i);
            }
        });
    }

    void setHmiThemeMode(final int i) {
        runCommand("08 HMI theme mode=" + i, new Command() { // from class: dezz.status.hudlab.HudLabController.32
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setHmiThemeMode$28(i);
            }
        });
    }

    void setIndividualTheme(final boolean z) {
        runCommand("07 Individual DIM theme=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.33
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setIndividualTheme$27(z);
            }
        });
    }

    void setMultimediaInformationMode(final int i) {
        runCommand("06 DIM information mode=" + i, new Command() { // from class: dezz.status.hudlab.HudLabController.34
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setMultimediaInformationMode$26(i);
            }
        });
    }

    void setDimNavigationMode(final int mode) {
        runCommand("DIM Navi mode=" + InstrumentClusterModes.naviModeName(mode), new Command() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda21
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() {
                return HudLabController.this.lambda$setDimNavigationMode$26(mode);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ String lambda$setDimNavigationMode$26(int mode) throws Exception {
        int validatedValue = InstrumentClusterModes.requireNaviMode(mode);
        IDimMenuInteraction menu = requireDimMenuInteraction();
        int before = menu.getNaviMode();
        rememberIntOnce(BACKUP_DIM_NAVI_MODE, before);
        if (!menu.switchNaviMode(validatedValue)) {
            throw new IllegalStateException("DimMenuInteraction.switchNaviMode вернул false");
        }
        SystemClock.sleep(180L);
        int after = menu.getNaviMode();
        if (after != validatedValue) {
            throw new IllegalStateException("NaviMode не подтверждён: ожидался " + validatedValue + ", прочитан " + after);
        }
        appendLog("DIM protocol opcode 13 · NaviMode " + before + " → " + after);
        return "switchNaviMode=" + validatedValue + " (" + InstrumentClusterModes.naviModeName(validatedValue) + "), readback=" + after;
    }

    void restoreDimNavigationMode() {
        runCommand("DIM Navi mode: точный откат", new Command() { // from class: dezz.status.hudlab.HudLabController$$ExternalSyntheticLambda0
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() {
                return HudLabController.this.lambda$restoreDimNavigationMode$27();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ String lambda$restoreDimNavigationMode$27() throws Exception {
        if (!this.backups.contains(BACKUP_DIM_NAVI_MODE)) {
            throw new IllegalStateException("исходный NaviMode не сохранён этой сборкой");
        }
        int original = InstrumentClusterModes.requireNaviMode(this.backups.getInt(BACKUP_DIM_NAVI_MODE, -1));
        IDimMenuInteraction menu = requireDimMenuInteraction();
        if (!menu.switchNaviMode(original)) {
            throw new IllegalStateException("DimMenuInteraction.switchNaviMode вернул false");
        }
        SystemClock.sleep(180L);
        int after = menu.getNaviMode();
        if (after != original) {
            throw new IllegalStateException("Откат NaviMode не подтверждён: ожидался " + original + ", прочитан " + after);
        }
        this.backups.edit().remove(BACKUP_DIM_NAVI_MODE).apply();
        return "исходный NaviMode восстановлен: " + original + " (" + InstrumentClusterModes.naviModeName(original) + ")";
    }

    void setPrimaryDisplayElements(boolean z) {
        setDisplayFunctions("DRIVE_ENVIRONMENT + SAFETY", z, DISPLAY_DRIVE_ENVIRONMENT, DISPLAY_SAFETY);
    }

    void setProfileTransferMode(final int i, Runnable runnable) {
        runCommand("01 ProfileTransfer HUD mode=" + modeName(i), new Command() { // from class: dezz.status.hudlab.HudLabController.35
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setProfileTransferMode$13(i);
            }
        }, runnable);
    }

    void setRawProfileTransferMinusOne() {
        runCommand("01 RAW ProfileTransfer HUD mode=-1", new Command() { // from class: dezz.status.hudlab.HudLabController.36
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setRawProfileTransferMinusOne$15();
            }
        });
    }

    void setSettingsActive(final boolean z) {
        runCommand("AdaptAPI HUD_ACTIVE=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.37
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setSettingsActive$2(z);
            }
        });
    }

    void setSettingsAr(final boolean z) {
        runCommand("AdaptAPI HUD_AR_ENGINE=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.38
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setSettingsAr$5(z);
            }
        });
    }

    void setUserProfileHudAr(final boolean z) {
        runCommand("UserProfile HUD AR=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.39
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setUserProfileHudAr$35(z);
            }
        });
    }

    void setUserProfileHudMode(final int i) {
        runCommand("UserProfile FIELD124 HUD mode=" + modeName(i), new Command() { // from class: dezz.status.hudlab.HudLabController.40
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setUserProfileHudMode$38(i);
            }
        });
    }

    void setVehicleModelClear(final boolean z) {
        runCommand("10 Vehicle model clear=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.41
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVehicleModelClear$30(z);
            }
        });
    }

    void setVfActive(final boolean z) {
        runCommand("VFHUD ActvReq=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.42
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVfActive$3(z);
            }
        });
    }

    void setVfAr(final boolean z) {
        runCommand("VFHUD ARActvReq=" + value(z), new Command() { // from class: dezz.status.hudlab.HudLabController.43
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVfAr$6(z);
            }
        });
    }

    void setVfDisplayMode(final int i) {
        runCommand("VFHUD mode=" + modeName(i), new Command() { // from class: dezz.status.hudlab.HudLabController.44
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVfDisplayMode$8(i);
            }
        });
    }

    void setVisualFunction(final int i, int i2) {
        if (i < 0 || i >= this.visualFunctions.length) {
            return;
        }
        final int i3 = i2 == 0 ? 0 : 1;
        runCommand(String.format(Locale.ROOT, "HUD visual F%02d=%d", Integer.valueOf(i), Integer.valueOf(i3)), new Command() { // from class: dezz.status.hudlab.HudLabController.45
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVisualFunction$11(i, i3);
            }
        });
    }

    void setVisualPen(int i) {
        final int iRequireProfilePen = HudVisualProbePlan.requireProfilePen(i);
        runCommand("HUD visual PEN=" + iRequireProfilePen, new Command() { // from class: dezz.status.hudlab.HudLabController.46
            @Override // dezz.status.hudlab.HudLabController.Command
            public final String run() throws Exception {
                return HudLabController.this.lambda$setVisualPen$12(iRequireProfilePen);
            }
        });
    }

    void start() {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.47
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$start$0();
            }
        });
    }

    void startSafeProfileVisualScan(final int i) {
        this.worker.post(new Runnable() { // from class: dezz.status.hudlab.HudLabController.48
            @Override // java.lang.Runnable
            public final void run() {
                HudLabController.this.lambda$startSafeProfileVisualScan$17(i);
            }
        });
    }
}
