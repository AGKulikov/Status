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
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream output;
    private Thread worker;
    private long lastTrafficAt;

    public MqttClient(@NonNull Config config, @NonNull Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "status-widget-mqtt");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (output != null) {
            try {
                publish(config.availabilityTopic, "offline".getBytes(StandardCharsets.UTF_8),
                        true, config.qos == 1);
                writePacket(0xE0, new byte[0]);
            } catch (IOException ignored) {}
        }
        pendingQos1.clear();
        closeSocket();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    public boolean isRunning() { return running; }

    /** Used by popup buttons; state publishing stays the responsibility of Home Assistant. */
    public void publish(String topic, String json, boolean retained) throws IOException {
        publish(topic, json.getBytes(StandardCharsets.UTF_8), retained, config.qos == 1);
    }

    private void runLoop() {
        long backoff = 1_000L;
        while (running) {
            try {
                connectAndRead();
                backoff = 1_000L;
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "MQTT connection lost: " + e.getMessage());
                    listener.onConnectionChanged(false, e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
            } finally {
                closeSocket();
            }
            if (!running) break;
            SystemClock.sleep(backoff);
            backoff = Math.min(60_000L, backoff * 2L);
        }
    }

    private void connectAndRead() throws IOException {
        SocketFactory factory = config.tls ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket next = factory.createSocket();
        next.connect(new InetSocketAddress(config.host, config.port), 15_000);
        next.setKeepAlive(true);
        next.setTcpNoDelay(true);
        if (next instanceof SSLSocket) {
            next.setSoTimeout(15_000);
            SSLParameters tlsParameters = ((SSLSocket) next).getSSLParameters();
            tlsParameters.setEndpointIdentificationAlgorithm("HTTPS");
            ((SSLSocket) next).setSSLParameters(tlsParameters);
            ((SSLSocket) next).startHandshake();
        }
        next.setSoTimeout(1_000);
        socket = next;
        output = next.getOutputStream();
        InputStream input = next.getInputStream();

        writePacket(0x10, connectBody());
        Packet connAck = readPacket(input);
        if (connAck.type() != 2 || connAck.body.length < 2 || connAck.body[1] != 0) {
            int code = connAck.body.length >= 2 ? connAck.body[1] & 0xff : -1;
            throw new IOException("Broker rejected CONNECT, code=" + code);
        }
        subscribe(config.subscription);
        resendPendingAfterReconnect();
        publish(config.availabilityTopic, "online".getBytes(StandardCharsets.UTF_8), true,
                config.qos == 1);
        listener.onConnectionChanged(true, "connected");
        lastTrafficAt = SystemClock.elapsedRealtime();

        while (running && !next.isClosed()) {
            try {
                Packet packet = readPacket(input);
                lastTrafficAt = SystemClock.elapsedRealtime();
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
                retryPending();
                long silence = SystemClock.elapsedRealtime() - lastTrafficAt;
                if (silence >= config.keepAliveSeconds * 500L) {
                    writePacket(0xC0, new byte[0]);
                    lastTrafficAt = SystemClock.elapsedRealtime();
                }
            }
        }
    }

    private byte[] connectBody() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeUtf(out, "MQTT");
        out.writeByte(4); // protocol level 3.1.1
        boolean hasUser = config.username != null && !config.username.isEmpty();
        boolean hasPassword = config.password != null && !config.password.isEmpty();
        int flags = 0x02 | 0x04 | 0x20 | (config.qos << 3); // clean + retained will
        if (hasUser) flags |= 0x80;
        if (hasPassword) flags |= 0x40;
        out.writeByte(flags);
        out.writeShort(config.keepAliveSeconds);
        writeUtf(out, config.clientId);
        writeUtf(out, config.availabilityTopic);
        writeUtf(out, "offline");
        if (hasUser) writeUtf(out, config.username);
        if (hasPassword) writeUtf(out, config.password);
        return bytes.toByteArray();
    }

    private void subscribe(String filter) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(nextPacketId());
        writeUtf(out, filter);
        out.writeByte(config.qos);
        writePacket(0x82, bytes.toByteArray());
    }

    private void handlePacket(Packet packet) throws IOException {
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
        listener.onMessage(topic, payload, (packet.header & 0x01) != 0);
        if (qos == 1) {
            writePacket(0x40, new byte[] {(byte) (packetId >> 8), (byte) packetId});
        }
    }

    private void publish(String topic, byte[] payload, boolean retained, boolean qosOne)
            throws IOException {
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
            lastTrafficAt = SystemClock.elapsedRealtime();
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

    private void closeSocket() {
        output = null;
        Socket old = socket;
        socket = null;
        if (old != null) try { old.close(); } catch (IOException ignored) {}
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xffff) throw new IOException("MQTT UTF value is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
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
}
