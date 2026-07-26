/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

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
    private static final int MAX_LOG_LINES = 70;
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
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("hud-lab");
    private final Handler worker;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final int[] visualFunctions = new int[20];
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
    private CarSignalManager signals;
    private CarFunction carFunction;
    private boolean closed;
    private int visualPen = 1;
    private String lastCommand = "Команды ещё не отправлялись";

    HudLabController(Context context, Listener listener) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        this.listener = listener;
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
        runCommand("HUD visual mask: все=" + normalized, () -> {
            Arrays.fill(visualFunctions, normalized);
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
        int normalized = pen == 0 ? OFF : ON;
        runCommand("HUD visual PEN=" + normalized, () -> {
            visualPen = normalized;
            sendVisualMask();
            return visualMaskDescription();
        });
    }

    String currentVisualMask() {
        return visualMaskDescription();
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
            vfHud = ((ECarXCarSetManager) publicAttributes).getECarXCarVfhudManager();
            CarFunction functions = new CarFunction(appContext);
            functions.initCarSignalManager(connectedRoot, connectedSignals);
            carFunction = functions;
            appendLog("Подключено: VFHUD + CarFunction + прямые DIM-сигналы");
        } catch (Throwable failure) {
            root = null;
            vfHud = null;
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
        boolean connected = root != null && vfHud != null && signals != null;
        StringBuilder out = new StringBuilder(1_200);
        out.append("СОЕДИНЕНИЕ: ").append(connected ? "ГОТОВО" : "ОЖИДАНИЕ").append('\n');
        out.append("Последняя команда: ").append(lastCommand).append("\n\n");

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

    private enum SignalRead {
        HUD_REQUEST,
        HUD_ACTIVE_STATUS,
        HUD_STATUS,
        DIM_PRIORITY,
        DIM_RESOURCE
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
