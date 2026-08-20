/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.content.Context;
import dezz.status.widget.Preferences;
import dezz.status.widget.phone.transport.v2.IphoneBleMode;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportRuntimeV2;
import dezz.status.widget.phone.transport.v2.IphoneSwitchTransportV2;
import java.util.Objects;
import java.util.UUID;

/** Creates one fresh framework adapter for each target generation. */
public final class AndroidIphoneTransportFactoryV2
        implements IphoneDualTransportRuntimeV2.TransportFactory {
    private final Context context;
    private final Preferences preferences;

    public AndroidIphoneTransportFactoryV2(Context context, Preferences preferences) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    @Override public IphoneSwitchTransportV2 create(
            IphoneBleMode mode, UUID androidInstallationId) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(androidInstallationId, "androidInstallationId");
        return switch (mode) {
            case ANDROID_CENTRAL -> new AndroidCentralTransportV2(context, preferences);
            case ANDROID_PERIPHERAL -> new AndroidPeripheralTransportV2(
                    context,
                    androidInstallationId,
                    new PieReverseGattObserverV2(context)
            );
        };
    }
}
