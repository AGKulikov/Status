/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetService;

/** Explicit-broadcast compatibility endpoint for Home Assistant Companion. */
public final class HaUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "HaUpdateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (AutomationContract.ACTION_SYNC_REQUEST.equals(intent.getAction())
                || AutomationContract.ACTION_REFRESH.equals(intent.getAction())) {
            sendSnapshot(context, intent);
            return;
        }
        try {
            AutomationContract.IncomingUpdate update = AutomationContract.fromIntent(intent);
            new AutomationStateStore(context).apply(update.scope, update.id, update.payload);

            if (WidgetService.isRunning()) {
                WidgetService.getInstance().onAutomationStateChanged(update.scope, update.id);
            } else if (new Preferences(context).widgetEnabled.get()) {
                context.startForegroundService(new Intent(context, WidgetService.class));
            }
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Ignored invalid HA update", e);
        }
    }

    private static void sendSnapshot(Context context, Intent request) {
        String replyPackage = request.getStringExtra(AutomationContract.EXTRA_REPLY_PACKAGE);
        if (replyPackage == null || replyPackage.trim().isEmpty()) {
            Log.w(TAG, "Ignored sync request without reply_package");
            return;
        }
        Intent response = new Intent(AutomationContract.ACTION_SYNC_RESPONSE);
        response.setPackage(replyPackage.trim());
        response.putExtra(AutomationContract.EXTRA_PAYLOAD,
                new AutomationStateStore(context).snapshot().toString());
        context.sendBroadcast(response);
    }
}
