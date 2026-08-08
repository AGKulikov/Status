/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.diagnostics.ActionRecorder;

/** Opens the stock KX11 climate popup through its own exported Binder service. */
public final class StockHvacPopupClient {
    public static final String SERVICE_ACTION = "ecarx.hvac.app.HvacAppService";
    public static final String SERVICE_PACKAGE = "ecarx.hvac.app";

    static final String INTERFACE_DESCRIPTOR = "ecarx.hvac.app.IOpenHvacAidlInterface";
    static final int TRANSACTION_OPEN_HVAC_MAIN = 1;
    private static final long CONNECT_TIMEOUT_MS = 6_000L;

    public interface Callback {
        void onComplete(boolean success, @NonNull String message);
    }

    private static volatile StockHvacPopupClient instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService binderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "status-open-hvac-main");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable connectTimeout = () -> {
        if (!requestPending) return;
        requestPending = false;
        binding = false;
        disconnect();
        finish(false, "Штатная служба климата не ответила за 6 секунд");
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            remote = service;
            if (requestPending) transactOpen(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
            if (!requestPending) return;
            requestPending = false;
            mainHandler.removeCallbacks(connectTimeout);
            finish(false, "Соединение со штатной службой климата оборвалось");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            boolean wasPending = requestPending;
            requestPending = false;
            binding = false;
            remote = null;
            mainHandler.removeCallbacks(connectTimeout);
            disconnect();
            if (wasPending) finish(false, "Binder штатного климата завершился");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            requestPending = false;
            binding = false;
            remote = null;
            mainHandler.removeCallbacks(connectTimeout);
            disconnect();
            finish(false, "Штатная служба климата вернула пустой Binder");
        }
    };

    private boolean binding;
    private boolean bound;
    private boolean requestPending;
    private boolean transactionRunning;
    private IBinder remote;
    @NonNull private Callback pendingCallback = (success, message) -> { };

    private StockHvacPopupClient(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    public static void openMainPopup(@NonNull Context context, @NonNull Callback callback) {
        StockHvacPopupClient client = instance;
        if (client == null) {
            synchronized (StockHvacPopupClient.class) {
                client = instance;
                if (client == null) {
                    client = new StockHvacPopupClient(context);
                    instance = client;
                }
            }
        }
        StockHvacPopupClient target = client;
        target.mainHandler.post(() -> target.openOnMain(callback));
    }

    public static boolean isStockHvacWindow(CharSequence packageName, CharSequence className) {
        return isStockHvacName(packageName) || isStockHvacName(className);
    }

    private static boolean isStockHvacName(CharSequence value) {
        if (value == null) return false;
        String name = value.toString().trim();
        return SERVICE_PACKAGE.equals(name) || name.startsWith(SERVICE_PACKAGE + ".");
    }

    private void openOnMain(@NonNull Callback callback) {
        IBinder cached = remote;
        ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "STOCK_HVAC_OPEN_REQUEST",
                ActionRecorder.object(
                        "service_action", SERVICE_ACTION,
                        "service_package", SERVICE_PACKAGE,
                        "operation", "openHvacMain",
                        "cached_binder", cached != null && cached.isBinderAlive()));
        if (requestPending || transactionRunning) {
            ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "STOCK_HVAC_OPEN_REJECTED",
                    ActionRecorder.object("reason", "request already running"));
            callback.onComplete(false, "Вызов штатного окна климата уже выполняется");
            return;
        }

        requestPending = true;
        pendingCallback = callback;
        if (cached != null && cached.isBinderAlive()) {
            transactOpen(cached);
            return;
        }
        remote = null;
        if (!bound && !binding) {
            binding = true;
            Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
            ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "STOCK_HVAC_BIND_REQUEST",
                    ActionRecorder.object("action", SERVICE_ACTION,
                            "package", SERVICE_PACKAGE));
            try {
                bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound) {
                    binding = false;
                    requestPending = false;
                    finish(false, "Служба ecarx.hvac.app.HvacAppService не найдена");
                    return;
                }
            } catch (SecurityException error) {
                binding = false;
                requestPending = false;
                finish(false, "Прошивка запретила доступ к штатному климату: "
                        + safeMessage(error));
                return;
            } catch (RuntimeException error) {
                binding = false;
                requestPending = false;
                finish(false, "Не удалось подключиться к штатному климату: "
                        + safeMessage(error));
                return;
            }
        }
        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void transactOpen(@NonNull IBinder binder) {
        if (!requestPending || transactionRunning) return;
        requestPending = false;
        transactionRunning = true;
        mainHandler.removeCallbacks(connectTimeout);
        binderExecutor.execute(() -> {
            boolean success = false;
            String message;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                String descriptor = binder.getInterfaceDescriptor();
                if (descriptor != null && !INTERFACE_DESCRIPTOR.equals(descriptor)) {
                    throw new RemoteException("неожиданный интерфейс " + descriptor);
                }
                data.writeInterfaceToken(INTERFACE_DESCRIPTOR);
                ActionRecorder.record(ActionRecorder.SOURCE_SERVICE,
                        "STOCK_HVAC_BINDER_TRANSACTION", ActionRecorder.object(
                                "interface", INTERFACE_DESCRIPTOR,
                                "transaction", TRANSACTION_OPEN_HVAC_MAIN,
                                "operation", "openHvacMain",
                                "flags", 0));
                if (!binder.transact(TRANSACTION_OPEN_HVAC_MAIN, data, reply, 0)) {
                    throw new RemoteException("Binder не принял transaction 1");
                }
                reply.readException();
                success = true;
                message = "Штатное окно климата открыто";
            } catch (Throwable error) {
                message = "Прямой вызов окна климата не выполнен: " + safeMessage(error);
            } finally {
                reply.recycle();
                data.recycle();
            }
            boolean result = success;
            String resultMessage = message;
            mainHandler.post(() -> {
                transactionRunning = false;
                if (!result) {
                    remote = null;
                    disconnect();
                }
                finish(result, resultMessage);
            });
        });
    }

    private void finish(boolean success, @NonNull String message) {
        ActionRecorder.record(ActionRecorder.SOURCE_SERVICE, "STOCK_HVAC_OPEN_RESULT",
                ActionRecorder.object("success", success, "message", message));
        Callback callback = pendingCallback;
        pendingCallback = (ignoredSuccess, ignoredMessage) -> { };
        callback.onComplete(success, message);
    }

    private void disconnect() {
        if (!bound) return;
        try {
            appContext.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        bound = false;
        remote = null;
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
