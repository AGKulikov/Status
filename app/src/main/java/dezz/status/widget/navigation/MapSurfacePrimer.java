/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Writes one transparent initial buffer before transferring a brand-new Surface to MapKit.
 * Never uses lockCanvas (which would connect a CPU producer), readback or tile readiness.
 * A resize/reconnect retains the same primer, so a live map is never cleared by this class.
 */
final class MapSurfacePrimer {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 10,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), r -> {
                Thread t = new Thread(r, "natro-map-initial-buffer"); t.setDaemon(true); return t;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private static EGLDisplay initializedDisplay;
    private final Surface surface;
    private final AtomicBoolean started = new AtomicBoolean();
    volatile boolean complete;

    MapSurfacePrimer(Surface surface) { this.surface = surface; }

    void start(Runnable finished) {
        if (!started.compareAndSet(false, true)) return;
        try {
            WORKERS.execute(() -> {
                String result = "cleared";
                try { clearInitialBuffer(surface); }
                catch (Exception | LinkageError failure) { result = failure.getClass().getSimpleName(); }
                finally {
                    // Cleanup has disconnected our GL producer before MapKit can attach.
                    complete = true;
                    finished.run();
                }
                DiagnosticJournal.infoAsync("map-startup", "initial_buffer=" + result);
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            // A crowded initialization queue must not permanently suppress a valid map.
            complete = true;
            finished.run();
            DiagnosticJournal.infoAsync("map-startup", "initial_buffer=queue_full_fallback");
        }
    }

    private static synchronized EGLDisplay display() {
        if (initializedDisplay != null) return initializedDisplay;
        EGLDisplay next = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(next != EGL14.EGL_NO_DISPLAY
                && EGL14.eglInitialize(next, version, 0, version, 1), "initialize");
        initializedDisplay = next;
        return next;
    }

    static void clearInitialBuffer(Surface surface) {
        if (!surface.isValid()) return;
        EGLDisplay display = display();
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        try {
            int[] count = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
            require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    && count[0] > 0, "config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT, "context");
            if (!surface.isValid()) return;
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            require(window != EGL14.EGL_NO_SURFACE, "surface");
            require(EGL14.eglMakeCurrent(display, window, window, context), "current");
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "clear");
            require(EGL14.eglSwapBuffers(display, window), "publish");
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            // The process-wide default EGL display is shared with Android HWUI. Do not
            // terminate it while other Natro windows still use it.
        }
    }

    private static void require(boolean ok, String stage) {
        if (!ok) throw new IllegalStateException("EGL initial buffer: " + stage);
    }
}
