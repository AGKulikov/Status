/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.integration.ActionDispatcher;

/** Owns one WindowManager-backed controller per configured floating overlay. */
public final class PopupOverlayManager {
    private final android.content.Context context;
    private final Preferences prefs;
    private final AutomationStateStore states;
    private final ActionDispatcher actionDispatcher;
    private final PopupOverlayController.BuiltinProvider builtinProvider;
    private final PopupOverlayConfigStore overlayConfigs;
    private final PopupItemConfigStore itemConfigs;
    private final Map<String, PopupOverlayController> controllers = new LinkedHashMap<>();
    /** Canonical state key -> overlay ids that actually render it. */
    private final Map<String, Set<String>> stateOwners = new LinkedHashMap<>();
    private boolean destroyed;

    public PopupOverlayManager(@NonNull android.content.Context context,
                               @NonNull Preferences prefs,
                               @NonNull AutomationStateStore states,
                               @NonNull ActionDispatcher actionDispatcher,
                               @NonNull PopupOverlayController.BuiltinProvider builtinProvider) {
        this.context = context;
        this.prefs = prefs;
        this.states = states;
        this.actionDispatcher = actionDispatcher;
        this.builtinProvider = builtinProvider;
        this.overlayConfigs = new PopupOverlayConfigStore(prefs);
        this.itemConfigs = new PopupItemConfigStore(prefs);
    }

    public synchronized void applyPreferences() {
        if (destroyed) return;
        List<PopupOverlayConfig> configs = overlayConfigs.load();
        Set<String> configuredIds = new LinkedHashSet<>();
        for (PopupOverlayConfig config : configs) {
            configuredIds.add(config.id);
            PopupOverlayController controller = controllers.get(config.id);
            if (controller == null) {
                controller = new PopupOverlayController(context, prefs, states, actionDispatcher,
                        builtinProvider, config.id, overlayConfigs);
                controllers.put(config.id, controller);
            }
            controller.applyPreferences();
        }
        rebuildStateOwners(configuredIds);
        Iterator<Map.Entry<String, PopupOverlayController>> iterator =
                controllers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PopupOverlayController> entry = iterator.next();
            if (configuredIds.contains(entry.getKey())) continue;
            entry.getValue().destroy();
            iterator.remove();
        }
    }

    public synchronized void onStateChanged(@NonNull String scope, @NonNull String id) {
        if (destroyed) return;
        if (AutomationContract.SCOPE_OVERLAY.equals(scope)) {
            PopupOverlayController controller = controllers.get(id);
            if (controller != null) controller.onStateChanged(scope);
            return;
        }
        Set<String> owners = stateOwners.get(scope + "|" + id);
        if (owners == null) return;
        for (String owner : owners) {
            PopupOverlayController controller = controllers.get(owner);
            if (controller != null) controller.onStateChanged(scope);
        }
    }

    private void rebuildStateOwners(Set<String> configuredIds) {
        stateOwners.clear();
        for (PopupItemConfig item : itemConfigs.load()) {
            if (!configuredIds.contains(item.overlayId)) continue;
            String scope = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    ? AutomationContract.SCOPE_BUILTIN : AutomationContract.SCOPE_POPUP;
            String id = PopupItemConfig.TYPE_BUILTIN.equals(item.type)
                    && !item.builtinId.isEmpty() ? item.builtinId : item.automationId;
            stateOwners.computeIfAbsent(scope + "|" + id,
                    ignored -> new LinkedHashSet<>()).add(item.overlayId);
        }
    }

    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        for (PopupOverlayController controller : controllers.values()) controller.destroy();
        controllers.clear();
        stateOwners.clear();
    }
}
