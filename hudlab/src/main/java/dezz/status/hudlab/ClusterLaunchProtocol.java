package dezz.status.hudlab;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * The exact Activity launch parameters used by mNavi 2.0 for displayId 2.
 */
final class ClusterLaunchProtocol {
    static final int DISPLAY_ID = 2;
    static final int SPLIT_SCREEN_POSITION = 0;
    static final int WINDOWING_MODE = 5;

    private ClusterLaunchProtocol() {
    }

    static void start(Context context, long durationMs, String token) {
        Intent intent = new Intent(Intent.ACTION_MAIN, Uri.parse(""));
        intent.setClassName(context.getPackageName(), ClusterProbeActivity.class.getName());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ClusterProbeActivity.EXTRA_DURATION_MS, durationMs);
        intent.putExtra(ClusterProbeActivity.EXTRA_LAUNCH_TOKEN, token);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(DISPLAY_ID);
        Bundle bundle = options.toBundle();
        if (bundle != null) {
            bundle.putInt("android.activity.SplitScreenShownPosition", SPLIT_SCREEN_POSITION);
            bundle.putInt("android.activity.windowingMode", WINDOWING_MODE);
        }
        context.startActivity(intent, bundle);
    }

    static String describe() {
        return "Intent ACTION_MAIN + FLAG_ACTIVITY_NEW_TASK"
                + " · setLaunchDisplayId(2)"
                + " · android.activity.SplitScreenShownPosition=0"
                + " · android.activity.windowingMode=5";
    }
}
