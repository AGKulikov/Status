/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.ecarx.xui.adaptapi.ECarXCarProxy;
import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot documented HU restart. It never sends powertrain or factory-reset commands. */
final class EcarxButtonPowerAccess implements ECarXCarProxy.ECarXCarProxyMethod {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean writeStarted = new AtomicBoolean();
    private final CarIntegration.ControlCommandListener result;
    private ECarXCarProxy proxy;
    static void restart(Context context, CarIntegration.ControlCommandListener listener) {
        EcarxButtonPowerAccess access = new EcarxButtonPowerAccess(listener);
        try {
            access.proxy = new ECarXCarProxy(context.getApplicationContext(), access);
            access.proxy.initECarXCar();
            access.main.postDelayed(() -> access.finish(false, "Power API не подключён"), 5000);
        } catch (Throwable failed) { access.finish(false, "Power API недоступен"); }
    }
    private EcarxButtonPowerAccess(CarIntegration.ControlCommandListener listener) { result = listener; }
    @Override public void onECarXCarServiceConnected(ECarXCar root, CarSignalManager ignored) {
        if (!writeStarted.compareAndSet(false, true) || finished.get()) return;
        // The proxy callback may be on MAIN; run the only write on a short-lived worker.
        new Thread(() -> {
            if (finished.get()) return;
            try {
                Object manager = root.getCarManager(ECarXCar.PA_SERVICE);
                if (!(manager instanceof ECarXCarSetManager)) { finish(false, "Power API недоступен"); return; }
                ApiResult accepted = ((ECarXCarSetManager) manager).getECarXCarPowerManager().CB_Power_Softkey(3);
                finish(accepted == ApiResult.SUCCEED, "CB_Power_Softkey(3): " + accepted);
            } catch (Throwable failed) { finish(false, "Не удалось отправить команду перезагрузки"); }
        }, "natro-hu-restart").start();
    }
    @Override public void onECarXCarServiceDeath() { finish(false, "Соединение Power API закрыто"); }
    private void finish(boolean success, String detail) {
        if (!finished.compareAndSet(false, true)) return;
        main.removeCallbacksAndMessages(null);
        ECarXCarProxy old = proxy; proxy = null;
        if (old != null) try { old.cleanup(); } catch (Throwable ignored) { }
        main.post(() -> result.onResult(success, detail));
    }
}
