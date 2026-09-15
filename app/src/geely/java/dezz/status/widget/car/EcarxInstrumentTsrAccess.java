/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.ecarx.xui.adaptapi.ECarXCarProxy;
import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.vehicle.ECarXCarActivesafetyManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;

import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Named SDK methods only: PA numeric IDs differ across archived SDK copies. */
final class EcarxInstrumentTsrAccess implements InstrumentTsrAccess,
        ECarXCarProxy.ECarXCarProxyMethod {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("instrument-tsr");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ECarXCarProxy proxy;
    private ECarXCarActivesafetyManager manager;
    private volatile boolean closed;
    private Boolean hidden;
    private Result pending;
    private long generation;
    private int attempts;

    EcarxInstrumentTsrAccess(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                proxy = new ECarXCarProxy(this.context, this);
                proxy.initECarXCar();
            } catch (Throwable failed) { finish(false, "ECARX недоступен: " + failed.getClass().getSimpleName()); }
        });
    }

    @Override public void setHidden(boolean value, Result result) {
        worker.post(() -> {
            if (closed) { main.post(() -> result.complete(false, "Соединение закрыто")); return; }
            if (pending != null) finish(false, "Заменено новой настройкой");
            hidden = value;
            pending = result;
            attempts = 0;
            long owner = ++generation;
            apply(owner);
        });
    }

    @Override public void refresh() {
        worker.post(() -> { if (!closed && hidden != null) { attempts = 0; apply(++generation); } });
    }

    private void apply(long owner) {
        if (closed || owner != generation || hidden == null) return;
        if (manager == null) {
            if (++attempts <= 10) worker.postDelayed(() -> apply(owner), 500);
            else finish(false, "Нет подключения к штатной настройке TSR");
            return;
        }
        try {
            int wanted = hidden ? 0 : 1;
            Integer before = read();
            if (before == null) { finish(false, "TSR: штатное чтение недоступно"); return; }
            if (before == wanted) { finish(true, "TSR=" + wanted + " (подтверждено чтением)"); return; }
            ApiResult accepted = manager.CB_ASY_TSR(wanted);
            DiagnosticJournal.infoAsync("instrument-oem", "CB_ASY_TSR=" + wanted
                    + ", result=" + accepted + ", sdk=" + manager.getClass().getClassLoader());
            if (accepted != ApiResult.SUCCEED) {
                finish(false, "TSR: штатная команда отклонена (" + accepted + ")");
                return;
            }
            worker.postDelayed(() -> verify(owner, wanted), 300);
        } catch (Throwable failure) { finish(false, "TSR: " + failure.getClass().getSimpleName()); }
    }

    private void verify(long owner, int wanted) {
        if (closed || owner != generation) return;
        Integer value = read();
        finish(value != null && value == wanted,
                "TSR=" + (value == null ? "недоступно" : value) + ", ожидается " + wanted);
    }

    private Integer read() {
        try {
            Object property = manager.getPA_Asy_TSR();
            if (property == null) return null;
            Object data = property.getClass().getMethod("getData").invoke(property);
            Object availability = property.getClass().getMethod("getAvailability").invoke(property);
            int support = availability instanceof Number ? ((Number) availability).intValue() : -1;
            int raw = data instanceof Number ? ((Number) data).intValue() : -1;
            return (support == 1 || support == 2) && (raw == 0 || raw == 1) ? raw : null;
        } catch (Throwable unavailable) { return null; }
    }

    private void finish(boolean success, String detail) {
        Result result = pending;
        pending = null;
        DiagnosticJournal.infoAsync("instrument-oem", "tsr success=" + success + ", " + detail);
        if (result != null) main.post(() -> result.complete(success, detail));
    }

    @Override public void onECarXCarServiceConnected(ECarXCar root, CarSignalManager ignored) {
        worker.post(() -> {
            if (closed) return;
            try {
                Object service = root.getCarManager(ECarXCar.PA_SERVICE);
                manager = service instanceof ECarXCarSetManager
                        ? ((ECarXCarSetManager) service).getECarXCarActivesafetyManager() : null;
                if (hidden != null) { attempts = 0; apply(++generation); }
            } catch (Throwable unavailable) { manager = null; }
        });
    }
    @Override public void onECarXCarServiceDeath() {
        worker.post(() -> {
            manager = null;
            generation++;
            // Do not leave the settings switch waiting forever if the service dies mid-write.
            finish(false, "Соединение TSR прервано; настройка не подтверждена");
        });
    }
    @Override public void close() {
        closed = true;
        worker.post(() -> {
            generation++;
            finish(false, "Соединение закрыто");
            manager = null;
            try {
                if (proxy != null) proxy.cleanup();
            } catch (Throwable failure) {
                DiagnosticJournal.infoAsync("instrument-oem", "tsr cleanup="
                        + failure.getClass().getSimpleName());
            } finally {
                proxy = null;
                thread.quitSafely();
            }
        });
    }
}
