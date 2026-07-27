/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.shell.AdbTransport;
import dezz.status.widget.shell.ConnectionStorage;
import dezz.status.widget.shell.ShellTransport;
import dezz.status.widget.shell.TelnetTransport;

/**
 * Minimal one-command shell coordinator for HUD display-stack experiments.
 *
 * <p>It only connects to the head unit itself: loopback and addresses assigned to its local
 * interfaces. It does not scan the LAN and exposes no arbitrary command input to the UI.</p>
 */
final class HudPrivilegedCommandRunner {
    interface Callback {
        void onFinished(String output, String error);
    }

    private static final int[] ADB_PORTS = {5555, 7777};
    private static final int TELNET_PORT = 23;
    private static final int LONG_COMMAND_TIMEOUT_MS = 25_000;

    private final Context appContext;
    private final ConnectionStorage storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "hud-display-shell");
        thread.setDaemon(true);
        return thread;
    });

    HudPrivilegedCommandRunner(Context context) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        storage = new ConnectionStorage(appContext);
    }

    void runTrusted(String command, Callback callback) {
        worker.execute(() -> {
            String output = null;
            String error = null;
            ShellTransport transport = null;
            try {
                transport = connect();
                if (transport == null) {
                    error = "локальный ADB 5555/7777 и Telnet 23 не найдены";
                } else {
                    output = execute(transport, command);
                }
            } catch (Throwable failure) {
                String message = failure.getMessage();
                error = failure.getClass().getSimpleName()
                        + (message == null || message.isEmpty() ? "" : " · " + message);
                storage.clear();
            } finally {
                if (transport != null) transport.close();
            }
            String finalOutput = output;
            String finalError = error;
            main.post(() -> callback.onFinished(finalOutput, finalError));
        });
    }

    void close() {
        worker.shutdown();
    }

    private ShellTransport connect() {
        ConnectionStorage.Endpoint cached = storage.load();
        ShellTransport fast = open(cached);
        if (fast != null) return fast;
        storage.clear();

        for (String host : localHosts()) {
            for (int port : ADB_PORTS) {
                if (!AdbTransport.probe(host, port)) continue;
                ShellTransport candidate = open(new ConnectionStorage.Endpoint(
                        host, port, ConnectionStorage.TRANSPORT_ADB));
                if (candidate != null) return candidate;
            }
            ShellTransport telnet = open(new ConnectionStorage.Endpoint(
                    host, TELNET_PORT, ConnectionStorage.TRANSPORT_TELNET));
            if (telnet != null) return telnet;
        }
        return null;
    }

    private ShellTransport open(ConnectionStorage.Endpoint endpoint) {
        if (endpoint == null) return null;
        ShellTransport transport = null;
        try {
            if (ConnectionStorage.TRANSPORT_ADB.equals(endpoint.transport)) {
                transport = AdbTransport.connect(appContext, endpoint.host, endpoint.port);
            } else if (ConnectionStorage.TRANSPORT_TELNET.equals(endpoint.transport)) {
                transport = TelnetTransport.connect(endpoint.host, endpoint.port);
            } else {
                return null;
            }
            String identity = execute(transport, "id");
            if (identity == null || !identity.contains("uid=")) {
                transport.close();
                return null;
            }
            storage.save(endpoint);
            return transport;
        } catch (Throwable ignored) {
            if (transport != null) transport.close();
            return null;
        }
    }

    private static String execute(ShellTransport transport, String command) throws Exception {
        if (transport instanceof TelnetTransport) {
            return ((TelnetTransport) transport).exec(command, LONG_COMMAND_TIMEOUT_MS);
        }
        return transport.exec(command);
    }

    private static LinkedHashSet<String> localHosts() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        hosts.add("::1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface item = interfaces.nextElement();
                if (!item.isUp()) continue;
                Enumeration<InetAddress> addresses = item.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    int scope = host.indexOf('%');
                    hosts.add(scope < 0 ? host : host.substring(0, scope));
                }
            }
        } catch (Throwable ignored) {
            // Loopback is enough on the target ECARX firmware.
        }
        return hosts;
    }
}
