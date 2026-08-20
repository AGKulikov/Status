/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.performance;

import android.app.Activity;
import android.app.Dialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Compatibility task used by the large settings activities to query PackageManager off-main.
 *
 * <p>The reflective boundary keeps the reusable task independent of their private choice types.
 * Delivery is generation-fenced by the owning activity.</p>
 */
public final class PerformancePickerTask implements Runnable {
    @NonNull private final Activity owner;
    @NonNull private final String queryMethod;
    @NonNull private final String deliveryMethod;
    @Nullable private final Object argument;
    private final int generation;
    @NonNull private final Dialog loading;

    public PerformancePickerTask(@NonNull Activity owner,
                                 @NonNull String queryMethod,
                                 @NonNull String deliveryMethod,
                                 @Nullable Object argument,
                                 int generation,
                                 @NonNull Dialog loading) {
        this.owner = owner;
        this.queryMethod = queryMethod;
        this.deliveryMethod = deliveryMethod;
        this.argument = argument;
        this.generation = generation;
        this.loading = loading;
    }

    @Override
    public void run() {
        List<?> result = null;
        Throwable failure = null;
        try {
            Method query = owner.getClass().getDeclaredMethod(queryMethod);
            query.setAccessible(true);
            Object value = query.invoke(owner);
            if (value instanceof List<?>) result = (List<?>) value;
        } catch (Throwable error) {
            failure = error;
        }
        List<?> deliveredResult = result;
        Throwable deliveredFailure = failure;
        owner.runOnUiThread(() -> deliver(deliveredResult, deliveredFailure));
    }

    private void deliver(@Nullable List<?> result, @Nullable Throwable failure) {
        try {
            Method delivery = owner.getClass().getDeclaredMethod(deliveryMethod,
                    Object.class, List.class, int.class, Dialog.class, Throwable.class);
            delivery.setAccessible(true);
            delivery.invoke(owner, argument, result, generation, loading, failure);
        } catch (Throwable ignored) {
            if (loading.isShowing()) loading.dismiss();
        }
    }
}
