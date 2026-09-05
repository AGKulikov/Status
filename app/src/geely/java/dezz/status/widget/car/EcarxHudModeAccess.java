/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.ECarXCarProxy;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.vehicle.ECarXCarProfiletransferManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.PATypes;

/** Minimal ECARX ProfileTransfer access for the documented stock-HUD layout modes. */
final class EcarxHudModeAccess implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String TAG = "EcarxHudModeAccess";

    @Nullable private ECarXCarProxy proxy;
    @Nullable private volatile ECarXCarProfiletransferManager profileTransferManager;
    private volatile boolean closed;

    EcarxHudModeAccess(@NonNull Context context) {
        try {
            Context application = context.getApplicationContext();
            proxy = new ECarXCarProxy(application == null ? context : application, this);
            proxy.initECarXCar();
        } catch (Throwable failure) {
            Log.w(TAG, "Could not initialise HUD mode access", failure);
            close();
        }
    }

    /**
     * Send one documented ProfileTransfer HUD mode. Values outside the vendor SDK's explicit
     * 0..3 range are rejected locally.
     */
    @NonNull
    HudModeResult writeHudMode(int mode) {
        if (mode < 0 || mode > 3) {
            return new HudModeResult(false, null, "режим должен быть 0…3");
        }
        ECarXCarProfiletransferManager manager = profileTransferManager;
        if (closed || manager == null) {
            return new HudModeResult(false, null,
                    "ProfileTransfer ещё не подключён к ecarxcar_service");
        }
        try {
            ApiResult result = manager.CB_HudDispModSetgReq(mode);
            Integer readback = readHudMode(manager);
            boolean accepted = result == ApiResult.SUCCEED;
            return new HudModeResult(accepted, readback,
                    "CB33278=" + result + ", PA33937="
                            + (readback == null ? "недоступно" : readback));
        } catch (Throwable failure) {
            Log.w(TAG, "Could not write HUD ProfileTransfer mode", failure);
            return new HudModeResult(false, null,
                    failure.getClass().getSimpleName()
                            + (failure.getMessage() == null
                            ? "" : ": " + failure.getMessage()));
        }
    }

    @Nullable
    private static Integer readHudMode(@NonNull ECarXCarProfiletransferManager manager) {
        try {
            PATypes.PA_HudDispModSetgReq value = manager.getPA_HudDispModSetgReq();
            if (value == null || value.getData() < 0) return null;
            return value.getData();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onECarXCarServiceConnected(ECarXCar root,
                                            CarSignalManager ignoredSignals) {
        if (closed || root == null) return;
        try {
            Object manager = root.getCarManager(ECarXCar.PA_SERVICE);
            if (manager instanceof ECarXCarSetManager) {
                ECarXCarSetManager setManager = (ECarXCarSetManager) manager;
                profileTransferManager = setManager.getECarXCarProfiletransferManager();
            } else {
                profileTransferManager = null;
            }
        } catch (Throwable failure) {
            profileTransferManager = null;
            Log.w(TAG, "HUD ProfileTransfer manager is unavailable", failure);
        }
    }

    @Override
    public void onECarXCarServiceDeath() {
        profileTransferManager = null;
    }

    void close() {
        closed = true;
        profileTransferManager = null;
        ECarXCarProxy current = proxy;
        proxy = null;
        if (current != null) {
            try {
                current.cleanup();
            } catch (Throwable failure) {
                Log.d(TAG, "HUD mode proxy cleanup failed", failure);
            }
        }
    }

    static final class HudModeResult {
        final boolean accepted;
        @Nullable final Integer readback;
        @NonNull final String detail;

        HudModeResult(boolean accepted, @Nullable Integer readback,
                      @NonNull String detail) {
            this.accepted = accepted;
            this.readback = readback;
            this.detail = detail;
        }
    }
}
