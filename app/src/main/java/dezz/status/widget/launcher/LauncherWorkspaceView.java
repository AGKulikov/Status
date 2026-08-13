/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * HOME workspace that exposes real descendant invalidations to the global-widget projection.
 *
 * <p>TextClock, media, vehicle and connector views already invalidate themselves when their live
 * state changes. Forwarding that event lets LauncherActivity refresh only after real work instead
 * of scanning every hidden source hierarchy twice per second.</p>
 */
public final class LauncherWorkspaceView extends FrameLayout {
    public interface DescendantInvalidationListener {
        void onDescendantInvalidated(@NonNull View target);
    }

    @Nullable private DescendantInvalidationListener invalidationListener;

    public LauncherWorkspaceView(@NonNull Context context) {
        super(context);
    }

    public void setDescendantInvalidationListener(
            @Nullable DescendantInvalidationListener listener) {
        invalidationListener = listener;
    }

    @Override
    public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
        super.onDescendantInvalidated(child, target);
        DescendantInvalidationListener listener = invalidationListener;
        if (listener != null) listener.onDescendantInvalidated(target);
    }
}
