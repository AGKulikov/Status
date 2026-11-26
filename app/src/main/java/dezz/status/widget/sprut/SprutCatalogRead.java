/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Retry a missing list on the same authenticated transport. The caller owns session cancellation. */
final class SprutCatalogRead {
    private static final long[] RETRY_DELAYS_MS = {2_000L, 5_000L};

    interface RetryDelay {
        CompletableFuture<Void> waitFor(long delayMs, String reason);
    }

    static <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> query, RetryDelay delay) {
        return read(query, delay, 0);
    }

    private static <T> CompletableFuture<T> read(Supplier<CompletableFuture<T>> query,
                                                   RetryDelay delay, int attempt) {
        return query.get().<CompletableFuture<T>>handle((value, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(value);
            String reason = retryReason(failure);
            if (reason == null || attempt == RETRY_DELAYS_MS.length) {
                throw new CompletionException(failure);
            }
            return delay.waitFor(RETRY_DELAYS_MS[attempt], reason)
                    .thenCompose(ignored -> read(query, delay, attempt + 1));
        }).thenCompose(next -> next);
    }

    static String retryReason(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof SocketTimeoutException) return "timeout";
        if (error instanceof SprutHubRpcClient.RpcException) {
            SprutHubRpcClient.RpcException rpc = (SprutHubRpcClient.RpcException) error;
            if (rpc.code == -666004 && rpc.getMessage() != null
                    && rpc.getMessage().toLowerCase(Locale.ROOT).contains("hub not connected")) {
                return "hub_offline";
            }
        }
        return null;
    }

    private SprutCatalogRead() {}
}
