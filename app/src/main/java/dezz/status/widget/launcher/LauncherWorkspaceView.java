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
    @Nullable private Runnable layoutCompleteListener;

    public LauncherWorkspaceView(@NonNull Context context) {
        super(context);
    }

    public void setDescendantInvalidationListener(
            @Nullable DescendantInvalidationListener listener) {
        invalidationListener = listener;
    }

    public void setLayoutCompleteListener(@Nullable Runnable listener) {
        layoutCompleteListener = listener;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // A child can regain geometry after HOME even when the workspace bounds did not change.
        // Parent OnLayoutChangeListener and source invalidation do not cover that transition.
        Runnable listener = layoutCompleteListener;
        if (listener != null) listener.run();
    }

    @Override
    public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
        super.onDescendantInvalidated(child, target);
        DescendantInvalidationListener listener = invalidationListener;
        if (listener != null) listener.onDescendantInvalidated(target);
    }
}
