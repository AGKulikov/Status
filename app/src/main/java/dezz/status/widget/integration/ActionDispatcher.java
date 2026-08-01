/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

/** Routes a popup action through the connector selected by that individual tile. */
public interface ActionDispatcher {
    @NonNull CompletableFuture<Void> dispatch(@NonNull ActionBinding binding,
                                              @NonNull JSONObject contextPayload);
}
