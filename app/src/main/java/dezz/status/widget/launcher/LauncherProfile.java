/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.content.ContextWrapper;

public interface LauncherProfile {
    boolean isPassengerLauncherProfile();
    static boolean passenger(Context context) {
        while(context!=null) {
            if(context instanceof LauncherProfile)return ((LauncherProfile)context).isPassengerLauncherProfile();
            if(!(context instanceof ContextWrapper))break;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }
        return false;
    }
}
