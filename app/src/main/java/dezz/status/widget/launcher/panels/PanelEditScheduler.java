/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.panels;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * Coalesces high-frequency visual-editor input without changing autosave semantics.
 *
 * <p>SeekBar and text callbacks can arrive dozens of times per second. Rebuilding a preview and
 * serializing a complete versioned JSON document for every callback is particularly expensive on
 * the Android 9 head unit. A preview is capped to roughly one update every two display frames,
 * while persistence trails the last input by a short interval and is explicitly flushed by the
 * owning Activity from {@code onStop()}.</p>
 */
public final class PanelEditScheduler {
    public static final long PREVIEW_DELAY_MS = 32L;
    public static final long SAVE_DELAY_MS = 180L;

    /** Small injectable surface keeps the coalescing policy testable without Android runtime. */
    public interface Dispatcher {
        void postDelayed(@NonNull Runnable task, long delayMillis);
        void remove(@NonNull Runnable task);
    }

    @NonNull private final Dispatcher dispatcher;
    @NonNull private final Runnable previewAction;
    @NonNull private final Runnable saveAction;
    private boolean previewPending;
    private boolean savePending;

    @NonNull private final Runnable previewTask;
    @NonNull private final Runnable saveTask;

    public PanelEditScheduler(@NonNull Dispatcher dispatcher,
                              @NonNull Runnable previewAction,
                              @NonNull Runnable saveAction) {
        this.dispatcher = dispatcher;
        this.previewAction = previewAction;
        this.saveAction = saveAction;
        previewTask = () -> {
            if (!previewPending) return;
            previewPending = false;
            this.previewAction.run();
        };
        saveTask = () -> {
            if (!savePending) return;
            savePending = false;
            this.saveAction.run();
        };
    }

    /** Main-thread factory used by settings Activities. */
    @NonNull
    public static PanelEditScheduler onMainThread(@NonNull Runnable previewAction,
                                                   @NonNull Runnable saveAction) {
        Handler handler = new Handler(Looper.getMainLooper());
        return new PanelEditScheduler(new Dispatcher() {
            @Override public void postDelayed(@NonNull Runnable task, long delayMillis) {
                handler.postDelayed(task, delayMillis);
            }

            @Override public void remove(@NonNull Runnable task) {
                handler.removeCallbacks(task);
            }
        }, previewAction, saveAction);
    }

    /** Requests one responsive preview and one trailing durable save. */
    public void request() {
        if (!previewPending) {
            previewPending = true;
            dispatcher.postDelayed(previewTask, PREVIEW_DELAY_MS);
        }
        if (savePending) dispatcher.remove(saveTask);
        savePending = true;
        dispatcher.postDelayed(saveTask, SAVE_DELAY_MS);
    }

    /** Makes the latest edit durable before the Activity leaves the foreground. */
    public void flush() {
        if (previewPending) {
            dispatcher.remove(previewTask);
            previewTask.run();
        }
        if (savePending) {
            dispatcher.remove(saveTask);
            saveTask.run();
        }
    }

    /** Drops callbacks after a final flush or when the owner is being discarded. */
    public void cancel() {
        dispatcher.remove(previewTask);
        dispatcher.remove(saveTask);
        previewPending = false;
        savePending = false;
    }

    public boolean hasPendingSave() {
        return savePending;
    }
}
