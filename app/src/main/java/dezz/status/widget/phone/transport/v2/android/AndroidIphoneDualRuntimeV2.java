/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.content.Context;
import dezz.status.widget.Preferences;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportRuntimeV2;
import java.security.SecureRandom;
import java.util.Objects;

/** Production construction boundary; callers retain the returned runtime for the controller life. */
public final class AndroidIphoneDualRuntimeV2 {
    private AndroidIphoneDualRuntimeV2() { }

    public static IphoneDualTransportRuntimeV2 create(
            Context context, Preferences preferences) {
        Context app = Objects.requireNonNull(context, "context").getApplicationContext();
        SecureRandom random = new SecureRandom();
        return new IphoneDualTransportRuntimeV2(
                nonZeroProcessNonce(random),
                new AndroidMainBleSchedulerV2(),
                new AndroidIphoneBleStateStoreV2(
                        Objects.requireNonNull(preferences, "preferences")),
                new AndroidIphoneTransportFactoryV2(app),
                random
        );
    }

    private static long nonZeroProcessNonce(SecureRandom random) {
        long value;
        do {
            value = random.nextLong();
        } while (value == 0L);
        return value;
    }
}
