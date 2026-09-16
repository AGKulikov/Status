/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Collections;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrationFactory;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.InstrumentTsrAccess;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.shell.PrivilegedShell;

/** These settings affect the stock driver display, independently of any Natro panel preset. */
public final class InstrumentOemController {
    private static InstrumentOemController instance;
    public static synchronized InstrumentOemController get(Context context) {
        if (instance == null) instance = new InstrumentOemController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private InstrumentTsrAccess tsr;
    private boolean tsrBusy;
    private volatile boolean whiteBarEnabled;
    private boolean whiteOwned, tsrHidden;
    private boolean whiteScheduled;
    private volatile Boolean lastWhiteDeny;
    private volatile boolean forceWhiteApply = true;
    private boolean observing;
    private boolean shellBusy;
    private long whiteGeneration;
    private int whiteAttempt;
    private int lastIgnition = -1;
    private String whiteStatus = "Не изменено";
    private String tsrStatus = "Не изменено";
    private Result whitePending;
    private final Runnable applyWhiteBar = this::applyWhiteBarNow;
    private final Runnable verifyWhiteBar = () -> {
        if (whiteBarEnabled && !shellBusy && !whiteScheduled) {
            whiteAttempt = 0;
            applyWhiteBarNow();
        }
    };
    private final ContentObserver naviObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { scheduleWhiteBar(); }
    };
    private final CarIntegration.TelemetryListener ignition = value -> {
        if (!"ignition_state".equals(value.id)) return;
        int state = (int) value.value;
        if (state != lastIgnition && state == 2097415 && tsr != null) tsr.refresh();
        lastIgnition = state;
    };

    private InstrumentOemController(Context context) {
        this.context = context;
        preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(InstrumentPanelStore.PREFS, Context.MODE_PRIVATE);
        whiteBarEnabled = preferences.getBoolean("hide_oem_white_bar", false);
        whiteOwned = preferences.getBoolean("oem_white_bar_owned", false);
        tsrHidden = preferences.getBoolean("hide_oem_speed_sign", false);
        if (whiteBarEnabled || whiteOwned) {
            reconcileObserver();
            scheduleWhiteBar();
        }
        if (isTsrHidden()) setTsrHidden(true, (success, detail) -> {});
    }
    public interface Result { void complete(boolean success, String detail); }
    public boolean isWhiteBarHidden() { return whiteBarEnabled; }
    public boolean isTsrHidden() { return tsrHidden; }
    public String whiteStatus() { return whiteStatus; }
    public String tsrStatus() { return tsrStatus; }

    public void setTsrHidden(boolean hidden, Result callback) {
        if (tsrBusy) { callback.complete(false, "Изменение TSR уже выполняется"); return; }
        tsrBusy = true;
        if (tsr == null) {
            tsr = CarIntegrationFactory.createInstrumentTsrAccess(context);
            CarIntegrations.get(context).subscribeTelemetry(Collections.singleton("ignition_state"), ignition);
        }
        tsrStatus = "Применение TSR…";
        tsr.setHidden(hidden, (success, detail) -> {
            tsrBusy = false;
            tsrStatus = detail;
            if (success && tsrHidden != hidden) {
                tsrHidden = hidden;
                dezz.status.widget.media.RuntimePreferenceWriter.put(preferences, "hide_oem_speed_sign", hidden);
            }
            if ((success && !hidden) || (!success && !isTsrHidden())) {
                CarIntegrations.get(context).unsubscribeTelemetry(ignition);
                tsr.close(); tsr = null;
            } else if (!success && !hidden) {
                // A rejected restore must not leave a hidden, uncommitted OFF request active.
                tsr.setHidden(true, (restored, ignored) -> {});
            }
            callback.complete(success, detail);
        });
    }

    public void setWhiteBarHidden(boolean hidden, Result callback) {
        if (whitePending != null) { callback.complete(false, "Изменение полосы уже выполняется"); return; }
        whiteBarEnabled = hidden;
        dezz.status.widget.media.RuntimePreferenceWriter.put(preferences, "hide_oem_white_bar", hidden);
        whitePending = callback;
        reconcileObserver();
        scheduleWhiteBar();
    }
    private void reconcileObserver() {
        if (whiteBarEnabled && !observing) {
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("NaviMode"),
                    false, naviObserver);
            observing = true;
        } else if (!whiteBarEnabled && observing) {
            context.getContentResolver().unregisterContentObserver(naviObserver);
            observing = false;
        }
    }
    private void scheduleWhiteBar() {
        whiteGeneration++;
        whiteAttempt = 0;
        main.removeCallbacks(verifyWhiteBar);
        // A stream of mode notifications cannot keep moving the existing deadline.
        if (!whiteBarEnabled) { main.removeCallbacks(applyWhiteBar); whiteScheduled = false; }
        if (!whiteScheduled) {
            whiteScheduled = true;
            main.postDelayed(applyWhiteBar, whiteBarEnabled ? 1500 : 0);
        }
    }
    private void applyWhiteBarNow() {
        whiteScheduled = false;
        if (shellBusy) return; // completion will reconcile the newest generation.
        final long owner = whiteGeneration;
        shellBusy = true;
        whiteAttempt++;
        final int[] modes = {-1, -1};
        final boolean[] requested = {false};
        final boolean[] wrote = {false};
        PrivilegedShell.get(context).runCommand(() -> {
            modes[0] = Settings.Global.getInt(context.getContentResolver(), "NaviMode", -1);
            Integer actual = whiteBarEnabled ? InstrumentDisplayLauncher.readDimMode(context) : null;
            modes[1] = actual == null || actual < 1 || actual > 3 ? modes[0] : actual;
            boolean deny = InstrumentOemPolicy.suppressWhiteBar(whiteBarEnabled, modes[1]);
            requested[0] = deny;
            wrote[0] = forceWhiteApply || lastWhiteDeny == null || lastWhiteDeny != deny;
            String read = "appops get com.ecarx.dimmenu SYSTEM_ALERT_WINDOW";
            return wrote[0] ? "appops set com.ecarx.dimmenu SYSTEM_ALERT_WINDOW "
                    + (deny ? "deny" : "allow") + " && " + read : read;
        }, (output, error) -> {
            shellBusy = false;
            boolean deny = requested[0];
            boolean success = error == null && InstrumentOemPolicy.appOpMatches(output, deny);
            forceWhiteApply = !success;
            if (success) {
                lastWhiteDeny = deny;
                if (whiteOwned != deny) {
                    whiteOwned = deny;
                    dezz.status.widget.media.RuntimePreferenceWriter.put(preferences, "oem_white_bar_owned", deny);
                }
            }
            if (owner != whiteGeneration) {
                main.removeCallbacks(applyWhiteBar); whiteScheduled = true;
                main.post(applyWhiteBar); return;
            }
            if (!success && whiteAttempt < 3) { whiteScheduled = true; main.postDelayed(applyWhiteBar, 100); return; }
            whiteStatus = success ? (deny ? "Запрет штатного наложения подтверждён"
                    : whiteBarEnabled ? "Ожидание режима навигации приборки" : "Штатные наложения восстановлены")
                    : "Не удалось подтвердить настройку полосы через встроенный ADB";
            if (wrote[0] || !success) DiagnosticJournal.infoAsync("instrument-oem", "white_bar navi_mode=" + modes[1]
                    + ", global_mode=" + modes[0] + ", enabled=" + whiteBarEnabled
                    + ", denied=" + deny + ", confirmed=" + success
                    + ", window=" + InstrumentPanelActivity.windowState());
            Result callback = whitePending;
            whitePending = null;
            if (callback != null) callback.complete(success, whiteStatus);
            if (whiteBarEnabled) main.postDelayed(verifyWhiteBar, 5000);
        });
    }
}
