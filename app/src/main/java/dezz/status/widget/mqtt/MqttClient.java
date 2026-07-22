/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLParameters;

/**
 * Small MQTT 3.1.1 client tailored to the widget: one wildcard subscription, QoS 1 delivery,
 * retained state replay, LWT availability, keepalive and unlimited reconnects. It intentionally
 * has no Android activity dependency and lives for the whole foreground-service lifetime.
 */
public final class MqttClient {
    private static final String TAG = "StatusWidgetMqtt";
    private static final int MAX_PACKET_BYTES = 256 * 1024;
    private static final int MAX_PENDING_QOS1 = 128;
    private static final long QOS1_RETRY_MS = 5_000L;
    private static final long MIN_RECONNECT_DELAY_MS = 1_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000L;

    public interface Listener {
        void onMessage(@NonNull String topic, @NonNull byte[] payload, boolean retained);
        void onConnectionChanged(boolean connected, String detail);
    }

    public static final class Config {
        public final String host;
        public final int port;
        public final boolean tls;
        public final String username;
        public final String password;
        public final String clientId;
        public final String subscription;
        public final String availabilityTopic;
        public final int keepAliveSeconds;
        public final int qos;

        public Config(String host, int port, boolean tls, String username, String password,
                      String clientId, String subscription, String availabilityTopic,
                      int keepAliveSeconds, int qos) {
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.username = username;
            this.password = password;
            this.clientId = clientId;
            this.subscription = subscription;
            this.availabilityTopic = availabilityTopic;
            this.keepAliveSeconds = Math.max(10, Math.min(600, keepAliveSeconds));
            this.qos = qos <= 0 ? 0 : 1;
        }
    }

    private final Config config;
    private final Listener listener;
    private final AtomicInteger packetIds = new AtomicInteger(1);
    private final Object outputLock = new Object();
    private final ConcurrentHashMap<Integer, PendingPublish> pendingQos1 =
            new ConcurrentHashMap<>();
    private final KeepAliveState keepAlive;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream output;
    private Thread worker;
    /** Invalidates callbacks and sockets belonging to a stopped/restarted worker. */
    private long lifecycleGeneration;

    public MqttClient(@NonNull Config config, @NonNull Listener listener) {
        this.config = config;
        this.listener = listener;
        keepAlive = new KeepAliveState(config.keepAliveSeconds);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        final long generation = ++lifecycleGeneration;
        Thread next = new Thread(() -> runLoop(generation), "status-widget-mqtt");
        next.setDaemon(true);
        worker = next;
        next.start();
    }

    public synchronized void stop() {
        running = false;
        lifecycleGeneration++;
        if (output != null) {
            try {
                publish(config.availabilityTopic, "offline".getBytes(StandardCharsets.UTF_8),
                        true, config.qos == 1);
                writePacket(0xE0, new byte[0]);
            } catch (IOException ignored) {}
        }
        pendingQos1.clear();
        Socket activeSocket = socket;
        socket = null;
        output = null;
        closeQuietly(activeSocket);
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        worker = null;
    }

    public boolean isRunning() { return running; }

    /** Used by popup buttons; state publishing stays the responsibility of Home Assistant. */
    public void publish(String topic, String json, boolean retained) throws IOException {
        publish(topic, json.getBytes(StandardCharsets.UTF_8), retained, config.qos == 1);
    }

    private void runLoop(long generation) {
        ReconnectBackoff backoff = new ReconnectBackoff();
        try {
            while (isCurrent(generation)) {
                ConnectionAttempt attempt = new ConnectionAttempt();
                try {
                    connectAndRead(generation, attempt);
                } catch (Exception e) {
                    if (isCurrent(generation)) {
                        String detail = e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage();
                        Log.w(TAG, "MQTT connection lost: " + detail);
                        notifyConnectionChanged(generation, false, detail);
                    }
                }
                // A confirmed CONNACK+SUBACK proves that the previous outage ended. The next
                // independent drop therefore starts at one second instead of inheriting an old
                // exponential delay that may already have reached a minute.
                if (attempt.established) backoff.onConnectionEstablished();
                if (!isCurrent(generation)) break;
                try {
                    Thread.sleep(backoff.takeDelay());
                } catch (InterruptedException interrupted) {
                    if (!isCurrent(generation)) break;
                    // The worker has no other interrupt-based control path; a stray interrupt
                    // merely shortens this one retry delay.
                }
            }
        } finally {
            clearWorker(generation);
        }
    }

    private void connectAndRead(long generation, ConnectionAttempt attempt) throws IOException {
        SocketFactory factory = config.tls ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket next = factory.createSocket();
        if (!claimSocket(generation, next)) {
            closeQuietly(next);
            return;
        }
        try {
            ensureCurrent(generation);
            next.connect(new InetSocketAddress(config.host, config.port), 15_000);
            ensureCurrent(generation);
            next.setKeepAlive(true);
            next.setTcpNoDelay(true);
            next.setSoTimeout(15_000);
            if (next instanceof SSLSocket) {
                SSLParameters tlsParameters = ((SSLSocket) next).getSSLParameters();
                tlsParameters.setEndpointIdentificationAlgorithm("HTTPS");
                ((SSLSocket) next).setSSLParameters(tlsParameters);
                ((SSLSocket) next).startHandshake();
                ensureCurrent(generation);
            }
            OutputStream nextOutput = next.getOutputStream();
            InputStream input = next.getInputStream();
            installOutput(generation, next, nextOutput);
            keepAlive.reset(SystemClock.elapsedRealtime());

            writePacket(0x10, connectBody());
            Packet connAck = readPacket(input);
            keepAlive.onTraffic(SystemClock.elapsedRealtime());
            if (connAck.header != 0x20 || connAck.body.length != 2 || connAck.body[1] != 0) {
                int code = connAck.body.length >= 2 ? connAck.body[1] & 0xff : -1;
                throw new IOException("Broker rejected CONNECT, code=" + code);
            }
            int subscriptionId = subscribe(config.subscription);
            awaitSubAck(input, generation, subscriptionId);
            // Do this immediately after a validated SUBACK: later availability/QoS writes may
            // fail, but the broker session itself was nevertheless established successfully.
            attempt.established = true;
            next.setSoTimeout(1_000);
            resendPendingAfterReconnect();
            publish(config.availabilityTopic, "online".getBytes(StandardCharsets.UTF_8), true,
                    config.qos == 1);
            notifyConnectionChanged(generation, true, "connected");

            while (isCurrent(generation) && !next.isClosed()) {
                try {
                    Packet packet = readPacket(input);
                    keepAlive.onTraffic(SystemClock.elapsedRealtime());
                    handlePacket(packet, generation);
                } catch (SocketTimeoutException ignored) {
                    long now = SystemClock.elapsedRealtime();
                    if (keepAlive.hasTimedOut(now)) {
                        throw new IOException("MQTT PINGRESP timeout");
                    }
                    retryPending();
                    if (keepAlive.shouldPing(now)) {
                        writePacket(0xC0, new byte[0]);
                        keepAlive.onPingSent(SystemClock.elapsedRealtime());
                    }
                }
            }
        } finally {
            closeSocket(next);
        }
    }

    private byte[] connectBody() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeUtf(out, "MQTT");
        out.writeByte(4); // protocol level 3.1.1
        boolean hasPassword = config.password != null && !config.password.isEmpty();
        // MQTT 3.1.1 forbids Password Flag=1 with User Name Flag=0. An explicitly empty
        // username is still a valid username field for brokers that authenticate by password.
        boolean hasUser = (config.username != null && !config.username.isEmpty()) || hasPassword;
        int flags = 0x02 | 0x04 | 0x20 | (config.qos << 3); // clean + retained will
        if (hasUser) flags |= 0x80;
        if (hasPassword) flags |= 0x40;
        out.writeByte(flags);
        out.writeShort(config.keepAliveSeconds);
        writeUtf(out, config.clientId);
        writeUtf(out, config.availabilityTopic);
        writeUtf(out, "offline");
        if (hasUser) writeUtf(out, config.username == null ? "" : config.username);
        if (hasPassword) writeUtf(out, config.password);
        return bytes.toByteArray();
    }

    private int subscribe(String filter) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int packetId = nextPacketId();
        out.writeShort(packetId);
        writeUtf(out, filter);
        out.writeByte(config.qos);
        writePacket(0x82, bytes.toByteArray());
        return packetId;
    }

    private void awaitSubAck(InputStream input, long generation, int expectedPacketId)
            throws IOException {
        while (isCurrent(generation)) {
            Packet packet = readPacket(input);
            keepAlive.onTraffic(SystemClock.elapsedRealtime());
            if (packet.type() == 9) {
                validateSubAck(expectedPacketId, config.qos, packet.header, packet.body);
                return;
            }
            // A retained PUBLISH or a PUBACK may legally be interleaved before SUBACK.
            handlePacket(packet, generation);
        }
        throw new IOException("MQTT stopped before SUBACK");
    }

    static void validateSubAck(int expectedPacketId, int requestedQos, int header, byte[] body)
            throws IOException {
        if (header != 0x90 || body == null || body.length != 3) {
            throw new IOException("Invalid MQTT SUBACK");
        }
        int packetId = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        if (packetId != expectedPacketId) {
            throw new IOException("MQTT SUBACK packet id mismatch");
        }
        int grantedQos = body[2] & 0xff;
        if (grantedQos == 0x80) throw new IOException("Broker rejected MQTT subscription");
        if (grantedQos > 1 || grantedQos > (requestedQos <= 0 ? 0 : 1)) {
            throw new IOException("Invalid MQTT SUBACK QoS=" + grantedQos);
        }
    }

    private void handlePacket(Packet packet, long generation) throws IOException {
        if (packet.type() == 13) {
            if (packet.header != 0xD0 || packet.body.length != 0) {
                throw new IOException("Invalid MQTT PINGRESP");
            }
            keepAlive.onPingResponse(SystemClock.elapsedRealtime());
            return;
        }
        if (packet.type() == 4 && packet.body.length >= 2) {
            int packetId = ((packet.body[0] & 0xff) << 8) | (packet.body[1] & 0xff);
            pendingQos1.remove(packetId);
            return;
        }
        if (packet.type() != 3) return;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.body));
        String topic = readUtf(in);
        int qos = (packet.header >> 1) & 0x03;
        int packetId = qos > 0 ? in.readUnsignedShort() : 0;
        byte[] payload = new byte[in.available()];
        in.readFully(payload);
        notifyMessage(generation, topic, payload, (packet.header & 0x01) != 0);
        if (qos == 1) {
            writePacket(0x40, new byte[] {(byte) (packetId >> 8), (byte) packetId});
        }
    }

    private void publish(String topic, byte[] payload, boolean retained, boolean qosOne)
            throws IOException {
        validateTopicName(topic);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeUtf(out, topic);
        int packetId = qosOne ? nextPacketId() : 0;
        if (qosOne) out.writeShort(packetId);
        out.write(payload);
        int header = 0x30 | (qosOne ? 0x02 : 0) | (retained ? 0x01 : 0);
        byte[] body = bytes.toByteArray();
        if (qosOne) {
            if (pendingQos1.size() >= MAX_PENDING_QOS1) {
                throw new IOException("Too many unacknowledged MQTT messages");
            }
            pendingQos1.put(packetId, new PendingPublish(header, body));
        }
        try {
            writePacket(header, body);
            PendingPublish pending = pendingQos1.get(packetId);
            if (pending != null) pending.lastSentAt = SystemClock.elapsedRealtime();
        } catch (IOException e) {
            if (qosOne) pendingQos1.remove(packetId);
            throw e;
        }
    }

    private void retryPending() throws IOException {
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<Integer, PendingPublish> entry : pendingQos1.entrySet()) {
            PendingPublish pending = entry.getValue();
            if (now - pending.lastSentAt < QOS1_RETRY_MS) continue;
            writePacket(pending.header | 0x08, pending.body); // MQTT DUP flag
            pending.lastSentAt = now;
        }
    }

    private void resendPendingAfterReconnect() throws IOException {
        long now = SystemClock.elapsedRealtime();
        for (PendingPublish pending : pendingQos1.values()) {
            writePacket(pending.header | 0x08, pending.body);
            pending.lastSentAt = now;
        }
    }

    private void writePacket(int header, byte[] body) throws IOException {
        if (body.length > MAX_PACKET_BYTES) throw new IOException("MQTT packet is too large");
        synchronized (outputLock) {
            OutputStream out = output;
            if (out == null) throw new IOException("MQTT is not connected");
            out.write(header);
            writeRemainingLength(out, body.length);
            out.write(body);
            out.flush();
            keepAlive.onTraffic(SystemClock.elapsedRealtime());
        }
    }

    private static Packet readPacket(InputStream in) throws IOException {
        int header = in.read();
        if (header < 0) throw new EOFException("Broker closed the socket");
        int remaining = readRemainingLength(in);
        if (remaining > MAX_PACKET_BYTES) throw new IOException("MQTT packet is too large");
        byte[] body = readExactly(in, remaining);
        return new Packet(header, body);
    }

    private int nextPacketId() {
        return packetIds.getAndUpdate(v -> v >= 0xffff ? 1 : v + 1);
    }

    private synchronized boolean claimSocket(long generation, Socket next) {
        if (!isCurrentLocked(generation)) return false;
        socket = next;
        return true;
    }

    private synchronized void installOutput(long generation, Socket expected,
                                            OutputStream nextOutput) throws IOException {
        if (!isCurrentLocked(generation) || socket != expected) {
            throw new IOException("MQTT stopped while connecting");
        }
        output = nextOutput;
    }

    private synchronized boolean isCurrent(long generation) {
        return isCurrentLocked(generation);
    }

    private boolean isCurrentLocked(long generation) {
        return running && lifecycleGeneration == generation;
    }

    private synchronized void ensureCurrent(long generation) throws IOException {
        if (!isCurrentLocked(generation)) throw new IOException("MQTT stopped");
    }

    private synchronized void notifyMessage(long generation, String topic, byte[] payload,
                                            boolean retained) throws IOException {
        if (!isCurrentLocked(generation)) throw new IOException("MQTT stopped");
        listener.onMessage(topic, payload, retained);
    }

    private synchronized void notifyConnectionChanged(long generation, boolean connected,
                                                       String detail) {
        if (!isCurrentLocked(generation)) return;
        listener.onConnectionChanged(connected, detail);
    }

    private synchronized void clearWorker(long generation) {
        if (lifecycleGeneration == generation && worker == Thread.currentThread()) worker = null;
    }

    private void closeSocket(Socket expected) {
        synchronized (this) {
            if (socket == expected) {
                output = null;
                socket = null;
            }
        }
        closeQuietly(expected);
    }

    private static void closeQuietly(Socket target) {
        if (target != null) try { target.close(); } catch (IOException ignored) {}
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xffff) throw new IOException("MQTT UTF value is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void validateTopicName(String topic) throws IOException {
        String value = topic == null ? "" : topic;
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > 0xffff || value.indexOf('\u0000') >= 0
                || value.indexOf('#') >= 0 || value.indexOf('+') >= 0) {
            throw new IOException("Invalid MQTT topic name");
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated MQTT packet");
            offset += count;
        }
        return result;
    }

    private static void writeRemainingLength(OutputStream out, int value) throws IOException {
        do {
            int digit = value % 128;
            value /= 128;
            if (value > 0) digit |= 0x80;
            out.write(digit);
        } while (value > 0);
    }

    private static int readRemainingLength(InputStream in) throws IOException {
        int multiplier = 1;
        int value = 0;
        int loops = 0;
        int digit;
        do {
            digit = in.read();
            if (digit < 0) throw new EOFException("Missing MQTT remaining length");
            value += (digit & 0x7f) * multiplier;
            multiplier *= 128;
            if (++loops > 4) throw new IOException("Invalid MQTT remaining length");
        } while ((digit & 0x80) != 0);
        return value;
    }

    private static final class Packet {
        final int header;
        final byte[] body;
        Packet(int header, byte[] body) { this.header = header; this.body = body; }
        int type() { return (header >> 4) & 0x0f; }
    }

    private static final class PendingPublish {
        final int header;
        final byte[] body;
        volatile long lastSentAt;

        PendingPublish(int header, byte[] body) {
            this.header = header;
            this.body = body;
        }
    }

    private static final class ConnectionAttempt {
        boolean established;
    }

    /** Pure reconnect policy kept package-visible for local JVM tests. */
    static final class ReconnectBackoff {
        private long nextDelay = MIN_RECONNECT_DELAY_MS;

        void onConnectionEstablished() {
            nextDelay = MIN_RECONNECT_DELAY_MS;
        }

        long takeDelay() {
            long result = nextDelay;
            nextDelay = Math.min(MAX_RECONNECT_DELAY_MS, nextDelay * 2L);
            return result;
        }
    }

    /** Tracks a concrete outstanding PINGREQ; unrelated traffic cannot acknowledge it. */
    static final class KeepAliveState {
        private final long pingAfterMs;
        private final long pingTimeoutMs;
        private long lastTrafficAt;
        private long pingSentAt = -1L;

        KeepAliveState(int keepAliveSeconds) {
            int seconds = Math.max(1, keepAliveSeconds);
            pingAfterMs = seconds * 500L;
            pingTimeoutMs = seconds * 1_000L;
        }

        synchronized void reset(long now) {
            lastTrafficAt = now;
            pingSentAt = -1L;
        }

        synchronized void onTraffic(long now) {
            lastTrafficAt = now;
        }

        synchronized boolean shouldPing(long now) {
            return pingSentAt < 0L && now - lastTrafficAt >= pingAfterMs;
        }

        synchronized void onPingSent(long now) {
            lastTrafficAt = now;
            pingSentAt = now;
        }

        synchronized boolean hasTimedOut(long now) {
            return pingSentAt >= 0L && now - pingSentAt >= pingTimeoutMs;
        }

        synchronized void onPingResponse(long now) {
            lastTrafficAt = now;
            pingSentAt = -1L;
        }

        synchronized boolean isPingOutstanding() {
            return pingSentAt >= 0L;
        }
    }
}
