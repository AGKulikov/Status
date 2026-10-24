/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolve only exported MediaBrowser endpoints advertised by the exact installed player. */
final class YandexBrowserServiceResolver {
    static final String PACKAGE = "ru.yandex.music";
    static final ComponentName LEGACY = new ComponentName(PACKAGE,
            "ru.yandex.music.common.media.mediabrowser.MusicBrowserService");
    static ComponentName resolve(PackageManager packages) {
        try {
            ServiceInfo legacy = packages.getServiceInfo(LEGACY, 0);
            if (eligible(legacy)) return LEGACY;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { }
        try {
            return select(packages.queryIntentServices(new Intent(
                    "android.media.browse.MediaBrowserService").setPackage(PACKAGE), 0));
        } catch (RuntimeException unavailable) { return null; }
    }
    static ComponentName select(List<ResolveInfo> services) {
        Set<ComponentName> candidates = new LinkedHashSet<>();
        if (services != null) for (ResolveInfo info : services) {
            ServiceInfo service = info == null ? null : info.serviceInfo;
            if (eligible(service)) candidates.add(new ComponentName(service.packageName, service.name));
        }
        // Ambiguous exports do not license a speculative service start.
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
    private static boolean eligible(ServiceInfo info) {
        return info != null && PACKAGE.equals(info.packageName) && info.name != null
                && !info.name.isEmpty() && info.enabled && info.exported
                && (info.permission == null || info.permission.isEmpty());
    }
}
