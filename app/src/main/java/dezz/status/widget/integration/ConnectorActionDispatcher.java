/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.mqtt.MqttController;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.ha.api.HaApiController;

/** Default action router shared by the floating overlay's independently bound tiles. */
public final class ConnectorActionDispatcher implements ActionDispatcher {
    private final MqttController mqtt;
    private final SprutHubController sprut;
    private final HaApiController homeAssistant;

    public ConnectorActionDispatcher(@NonNull MqttController mqtt,
                                     @NonNull SprutHubController sprut,
                                     @NonNull HaApiController homeAssistant) {
        this.mqtt = mqtt;
        this.sprut = sprut;
        this.homeAssistant = homeAssistant;
    }

    /**
     * True only after the selected connector has an authoritative current session.
     *
     * <p>Intent-triggered commands use this as a pre-dispatch barrier. In particular, a TOGGLE
     * must never be calculated from a catalog cached before ignition-off. This method does not
     * replace the connector's own checks, which are repeated immediately before every write.</p>
     */
    public boolean isReady(@NonNull ActionBinding binding) {
        switch (binding.connectorType) {
            case MQTT:
                return MqttController.isConnected();
            case SPRUTHUB:
                return SprutHubController.isSynced();
            case HOME_ASSISTANT:
                return homeAssistant.isOnline();
            default:
                return false;
        }
    }

    @NonNull
    @Override
    public CompletableFuture<Void> dispatch(@NonNull ActionBinding binding,
                                            @NonNull JSONObject contextPayload) {
        switch (binding.connectorType) {
            case MQTT:
                try {
                    mqtt.publishAction(binding);
                    return CompletableFuture.completedFuture(null);
                } catch (IOException | IllegalArgumentException e) {
                    return failed(e);
                }
            case SPRUTHUB:
                return sprut.execute(binding).thenApply(ignored -> null);
            case HOME_ASSISTANT:
                return homeAssistant.execute(binding).thenApply(ignored -> null);
            default:
                return failed(new IOException("Unsupported connector action"));
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
