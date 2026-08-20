/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import dezz.status.widget.WidgetService;
import dezz.status.widget.WidgetServiceStarter;

/**
 * Activity-lifecycle bridge for the real phone-notification overlay editor.
 *
 * <p>The service may still be starting when settings opens. A short bounded retry attaches the
 * preview as soon as the singleton becomes available. Pause requests a delayed release in the
 * service, allowing parent and child editors to hand the same surface to each other without a
 * flash or without leaving the test notification behind after the user exits settings.</p>
 */
public final class PhoneNotificationEditorPreviewSession {
    private static final long ATTACH_RETRY_MS = 100L;
    private static final int MAX_ATTACH_ATTEMPTS = 30;

    private final Context context;
    private final String overlayId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private int attempts;
    private final Runnable attach = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            WidgetService service = WidgetService.getInstance();
            if (service != null) {
                attempts = 0;
                service.startPhoneNotificationEditorPreview(overlayId);
                return;
            }
            if (attempts++ == 0) WidgetServiceStarter.startIfNeeded(context);
            if (attempts <= MAX_ATTACH_ATTEMPTS) {
                handler.postDelayed(this, ATTACH_RETRY_MS);
            }
        }
    };

    public PhoneNotificationEditorPreviewSession(@NonNull Context context,
                                                  @NonNull String overlayId) {
        if (!PhoneNotificationAutomation.isNotificationOverlayId(overlayId)) {
            throw new IllegalArgumentException("Unsupported phone notification overlay");
        }
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.overlayId = overlayId;
    }

    public void onResume() {
        resumed = true;
        attempts = 0;
        handler.removeCallbacks(attach);
        handler.post(attach);
    }

    public void onPause() {
        resumed = false;
        handler.removeCallbacks(attach);
        WidgetService service = WidgetService.getInstance();
        if (service != null) service.schedulePhoneNotificationEditorPreviewStop(overlayId);
    }

    public void close() {
        onPause();
        handler.removeCallbacksAndMessages(null);
    }
}
