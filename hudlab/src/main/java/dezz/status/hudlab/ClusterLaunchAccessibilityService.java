package dezz.status.hudlab;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Supplies the same kind of Context from which mNavi performs its display-2 startActivity call.
 *
 * <p>The service does not inspect UI content, handle events, run at boot, repeat commands or
 * keep a foreground process. It is used only after the visible HUD Lab button is pressed.</p>
 */
public final class ClusterLaunchAccessibilityService extends AccessibilityService {
    private static WeakReference<ClusterLaunchAccessibilityService> active =
            new WeakReference<>(null);

    static boolean isConnected() {
        return active.get() != null;
    }

    static Context activeContext(Context fallback) {
        ClusterLaunchAccessibilityService service = active.get();
        return service == null ? fallback : service;
    }

    static void launchProbe(long durationMs, String token) {
        ClusterLaunchAccessibilityService service = active.get();
        if (service == null) {
            throw new IllegalStateException("AccessibilityService не подключена");
        }
        ClusterLaunchProtocol.start(service, durationMs, token);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        active = new WeakReference<>(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        ClusterLaunchAccessibilityService current = active.get();
        if (current == this) {
            active.clear();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        ClusterLaunchAccessibilityService current = active.get();
        if (current == this) {
            active.clear();
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally empty: the service is only an explicit launch context.
    }

    @Override
    public void onInterrupt() {
        // No continuous operation to interrupt.
    }
}
