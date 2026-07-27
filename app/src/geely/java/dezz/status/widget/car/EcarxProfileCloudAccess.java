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
import ecarx.car.hardware.vehicle.ECarXCarProfileManager;
import ecarx.car.hardware.vehicle.ECarXCarProfiletransferManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.PATypes;

/**
 * Minimal raw access to the complete ECARX ProfileCloudData protobuf.
 *
 * <p>Using {@code IUserProfile.applyUserProfileData()} here is unsafe: that adapter exposes only
 * 85 of the vendor message's 150 fields and rebuilds all hidden fields as zero. The low-level PA
 * manager lets us read PA33873 and send the same complete byte stream through CB33264.</p>
 */
final class EcarxProfileCloudAccess implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String TAG = "EcarxProfileCloud";
    private static final int PA_PROFILE_CLOUD_DATA =
            ECarXCarProfileManager.ManagerId_papsetprofileclouddata;
    private static final int CB_PROFILE_CLOUD_DATA =
            ECarXCarProfileManager.ManagerId_cbpsetprofileclouddata;

    @Nullable private ECarXCarProxy proxy;
    @Nullable private volatile ECarXCarProfileManager profileManager;
    @Nullable private volatile ECarXCarProfiletransferManager profileTransferManager;
    private volatile boolean closed;

    EcarxProfileCloudAccess(@NonNull Context context) {
        try {
            Context application = context.getApplicationContext();
            proxy = new ECarXCarProxy(application == null ? context : application, this);
            proxy.initECarXCar();
        } catch (Throwable failure) {
            Log.w(TAG, "Could not initialise raw profile access", failure);
            close();
        }
    }

    @Nullable
    byte[] readCompleteProfile() {
        ECarXCarProfileManager manager = profileManager;
        if (closed || manager == null) return null;
        try {
            byte[] value = manager.getByteCBValueForUt(PA_PROFILE_CLOUD_DATA);
            return value == null ? null : value.clone();
        } catch (Throwable failure) {
            Log.w(TAG, "Could not read PA ProfileCloudData", failure);
            return null;
        }
    }

    boolean writeCompleteProfile(@NonNull byte[] value) {
        ECarXCarProfileManager manager = profileManager;
        if (closed || manager == null || value.length == 0) return false;
        try {
            // setbytesPropertyForUt forwards the supplied bytes unchanged to the requested CB ID.
            manager.setbytesPropertyForUt(CB_PROFILE_CLOUD_DATA, value.clone());
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "Could not write CB ProfileCloudData", failure);
            return false;
        }
    }

    /**
     * Send one documented ProfileTransfer HUD mode. No visual mask or save command is coupled to
     * this call. Values outside the vendor SDK's explicit 0..3 range are rejected locally.
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
                profileManager = setManager.getECarXCarProfileManager();
                profileTransferManager = setManager.getECarXCarProfiletransferManager();
            } else {
                profileManager = null;
                profileTransferManager = null;
            }
        } catch (Throwable failure) {
            profileManager = null;
            profileTransferManager = null;
            Log.w(TAG, "PA profile manager is unavailable", failure);
        }
    }

    @Override
    public void onECarXCarServiceDeath() {
        profileManager = null;
        profileTransferManager = null;
    }

    void close() {
        closed = true;
        profileManager = null;
        profileTransferManager = null;
        ECarXCarProxy current = proxy;
        proxy = null;
        if (current != null) {
            try {
                current.cleanup();
            } catch (Throwable failure) {
                Log.d(TAG, "Raw profile proxy cleanup failed", failure);
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
