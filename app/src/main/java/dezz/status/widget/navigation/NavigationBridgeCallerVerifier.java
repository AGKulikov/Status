/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

/** Package/UID/signature boundary for the explicit Navigator-to-Natro Binder endpoint. */
final class NavigationBridgeCallerVerifier {
    private NavigationBridgeCallerVerifier() {}

    static boolean isTrustedNavigator(@NonNull Context context, int sendingUid) {
        if (sendingUid <= 0) return false;
        PackageManager packages = context.getPackageManager();
        String[] names;
        try {
            names = packages.getPackagesForUid(sendingUid);
        } catch (RuntimeException failure) {
            return false;
        }
        if (!containsExactPackage(names, NavigationBridgeContract.NAVIGATOR_PACKAGE)) {
            return false;
        }
        try {
            // The final Navigator is deliberately signed with the Natro update certificate.
            // Original Yandex Music/Maps packages keep their original certificates and therefore
            // cannot use this endpoint even if they know the explicit component name.
            return packages.checkSignatures(
                    context.getPackageName(), NavigationBridgeContract.NAVIGATOR_PACKAGE)
                    == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static boolean containsExactPackage(String[] packages, @NonNull String expected) {
        if (packages == null) return false;
        for (String candidate : packages) {
            if (expected.equals(candidate)) return true;
        }
        return false;
    }
}
