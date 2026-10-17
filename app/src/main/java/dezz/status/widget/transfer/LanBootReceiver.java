/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;
import android.content.*;
import android.os.UserManager;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** No direct-boot credentials or cloud listener; start only after unlock and explicit enrolment. */
public final class LanBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        String action=intent==null?null:intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                &&!Intent.ACTION_USER_UNLOCKED.equals(action))return;
        UserManager user=context.getSystemService(UserManager.class);
        if(user==null||!user.isUserUnlocked())return;
        PendingResult pending=goAsync();Context app=context.getApplicationContext();
        new Thread(()->{
            try {
                if(new LanTrustStore(app).autoStart())app.startForegroundService(new Intent(app,LanTransferService.class));
            }catch(RuntimeException failure){DiagnosticJournal.infoAsync("lan-start","automatic_start_failed="+failure.getClass().getSimpleName());}
            finally{pending.finish();}
        },"natro-lan-boot").start();
    }
}
