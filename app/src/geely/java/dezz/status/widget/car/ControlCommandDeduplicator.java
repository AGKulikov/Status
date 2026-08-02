/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe exact-command fan-out used by the HOME and overlay climate surfaces. */
final class ControlCommandDeduplicator {
    private final Map<String, List<CarIntegration.ControlCommandListener>> listenersByKey =
            new HashMap<>();

    /** @return true when the caller must enqueue the vendor command, false for a duplicate. */
    synchronized boolean add(@NonNull String key,
                             @NonNull CarIntegration.ControlCommandListener listener) {
        List<CarIntegration.ControlCommandListener> listeners = listenersByKey.get(key);
        if (listeners != null) {
            listeners.add(listener);
            return false;
        }
        listeners = new ArrayList<>();
        listeners.add(listener);
        listenersByKey.put(key, listeners);
        return true;
    }

    @NonNull
    synchronized List<CarIntegration.ControlCommandListener> take(@NonNull String key) {
        List<CarIntegration.ControlCommandListener> listeners = listenersByKey.remove(key);
        return listeners == null ? new ArrayList<>() : new ArrayList<>(listeners);
    }

    @NonNull
    synchronized List<CarIntegration.ControlCommandListener> drain() {
        List<CarIntegration.ControlCommandListener> result = new ArrayList<>();
        for (List<CarIntegration.ControlCommandListener> listeners : listenersByKey.values()) {
            result.addAll(listeners);
        }
        listenersByKey.clear();
        return result;
    }
}
