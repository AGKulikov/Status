/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

/** Launches the cover with the exact display/window hints used by the stock ECARX HUD. */
final class HudDisplayCover {
    static final int HUD_DISPLAY_ID = 2;
    private static final int STOCK_WINDOWING_MODE = 5;
    private static final int STOCK_SPLIT_POSITION = 1;

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private HudDisplayCover() {
    }

    static Result startBlack(Context context) {
        return start(context, false);
    }

    static Result startMarker(Context context) {
        return start(context, true);
    }

    private static Result start(Context context, boolean marker) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(HUD_DISPLAY_ID);

            StringBuilder details = new StringBuilder("Display ID 2");
            if (invokeIntOption(options, "setLaunchWindowingMode", STOCK_WINDOWING_MODE)) {
                details.append(" · windowing mode 5");
            } else {
                details.append(" · windowing mode 5 недоступен");
            }
            if (invokeIntOption(options, "setSpliteScreenPositon", STOCK_SPLIT_POSITION)) {
                details.append(" · split position 1");
            } else if (invokeIntOption(options, "setSplitScreenPosition", STOCK_SPLIT_POSITION)) {
                details.append(" · split position 1");
            } else {
                details.append(" · split position недоступна");
            }

            Intent intent = new Intent(context, HudCoverActivity.class);
            intent.putExtra(HudCoverActivity.EXTRA_MARKER, marker);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Bundle bundle = options.toBundle();
            context.startActivity(intent, bundle);
            return new Result(true,
                    (marker ? "Контрольная метка" : "Непрозрачный чёрный HUD-Activity")
                            + " запущен: " + details
                            + ". Проверьте физический HUD, а не превью на основном экране.");
        } catch (Throwable failure) {
            String message = failure.getMessage();
            return new Result(false,
                    "Не удалось запустить Activity на HUD: "
                            + failure.getClass().getSimpleName()
                            + (message == null ? "" : " · " + message));
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(HudCoverActivity.ACTION_STOP);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private static boolean invokeIntOption(ActivityOptions options, String methodName,
                                           int value) {
        try {
            Method method = ActivityOptions.class.getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            method.invoke(options, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
