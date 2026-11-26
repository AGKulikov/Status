/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SprutCatalogReadTest {
    @Test public void offlineAndTimeoutRecoverInTheSameReadOperation() {
        AtomicInteger queries = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        String result = SprutCatalogRead.run(() -> {
            switch (queries.incrementAndGet()) {
                case 1: return failed(new SprutHubRpcClient.RpcException(-666004,
                        "Hub not connected to server: Method not allowed", null));
                case 2: return failed(new SocketTimeoutException("list timeout"));
                default: return CompletableFuture.completedFuture("fresh catalog");
            }
        }, (ms, reason) -> {
            delays.add(ms);
            reasons.add(reason);
            return CompletableFuture.completedFuture(null);
        }).join();
        assertEquals("fresh catalog", result);
        assertEquals(List.of(2000L, 5000L), delays);
        assertEquals(List.of("hub_offline", "timeout"), reasons);
    }

    @Test public void persistentTimeoutStopsAfterTwoRetries() {
        AtomicInteger queries = new AtomicInteger();
        CompletableFuture<Object> result = SprutCatalogRead.run(() -> {
            queries.incrementAndGet();
            return failed(new SocketTimeoutException("room.list"));
        }, (ms, reason) -> CompletableFuture.completedFuture(null));
        assertThrows(CompletionException.class, result::join);
        assertEquals(3, queries.get());
    }

    @Test public void transportAndPermissionErrorsPropagateImmediately() {
        for (Throwable error : List.of(new IOException("connection closed"),
                new SprutHubRpcClient.RpcException(-666004, "permission denied", null),
                new SprutHubRpcClient.RpcException(-32602, "bad parameter", null))) {
            CompletableFuture<Object> result = SprutCatalogRead.run(() -> failed(error),
                    (ms, reason) -> { throw new AssertionError("unexpected retry"); });
            assertSame(error, assertThrows(CompletionException.class, result::join).getCause());
        }
    }

    @Test public void cancelledDelayNeverIssuesTheNextRead() {
        AtomicInteger queries = new AtomicInteger();
        CompletableFuture<Void> delay = new CompletableFuture<>();
        CompletableFuture<Object> result = SprutCatalogRead.run(() -> {
            queries.incrementAndGet();
            return failed(new SocketTimeoutException("room.list"));
        }, (ms, reason) -> delay);
        assertFalse(result.isDone());
        delay.completeExceptionally(new IOException("session closed"));
        assertThrows(CompletionException.class, result::join);
        assertEquals(1, queries.get());
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }
}
