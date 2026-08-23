/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.WidgetService;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;

/**
 * Multiplexes one WidgetService registry subscription to every visible information tile.
 *
 * <p>A launcher page can legitimately contain dozens of information views. Registering every
 * view directly in ConnectorValueRegistry made view recreation consume the registry's global
 * listener budget and eventually crashed the main thread. This hub keeps exactly one upstream
 * listener per live WidgetService instance and fans immutable snapshots out in-process.</p>
 */
final class ConnectorValueSubscriptionHub {
    interface Subscriber {
        void onValuesChanged(@NonNull Collection<ConnectorValue> values);
    }

    private static final Map<WidgetService, Entry> ENTRIES = new IdentityHashMap<>();

    private ConnectorValueSubscriptionHub() {}

    @NonNull
    static List<ConnectorValue> subscribe(@NonNull WidgetService service,
                                          @NonNull Subscriber subscriber) {
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(service);
            if (entry == null) {
                entry = new Entry(service);
                ENTRIES.put(service, entry);
                entry.snapshot = service.addConnectorValueListener(entry.upstream);
            }
            if (!entry.subscribers.contains(subscriber)) entry.subscribers.add(subscriber);
            return new ArrayList<>(entry.snapshot);
        }
    }

    static void unsubscribe(@NonNull WidgetService service,
                            @NonNull Subscriber subscriber) {
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(service);
            if (entry == null) return;
            entry.subscribers.remove(subscriber);
            if (!entry.subscribers.isEmpty()) return;
            ENTRIES.remove(service);
            service.removeConnectorValueListener(entry.upstream);
        }
    }

    private static final class Entry {
        final WidgetService service;
        final List<Subscriber> subscribers = new ArrayList<>();
        List<ConnectorValue> snapshot = new ArrayList<>();
        final ConnectorValueRegistry.Listener upstream;

        Entry(WidgetService service) {
            this.service = service;
            this.upstream = changedValues -> {
                List<Subscriber> targets;
                List<ConnectorValue> changes = new ArrayList<>(changedValues);
                synchronized (ENTRIES) {
                    Entry current = ENTRIES.get(service);
                    if (current != this) return;
                    snapshot = service.connectorValueSnapshot();
                    targets = new ArrayList<>(subscribers);
                }
                for (Subscriber target : targets) target.onValuesChanged(changes);
            };
        }
    }
}
