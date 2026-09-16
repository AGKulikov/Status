/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Minimal same-origin HTTP/1.1 server: one request per socket, bounded workers/headers/body. */
public final class LanHttpServer implements AutoCloseable {
    public static final long MAX_UPLOAD = 256L * 1024 * 1024;
    public interface Handler { void handle(Request request, OutputStream output) throws Exception; }
    public static final class Request {
        public final String method, path, query, authority;
        public final Map<String, String> headers;
        public final long length;
        public final InputStream body;
        Request(String method, String target, String authority, Map<String, String> headers, long length, InputStream body) {
            this.method = method; int mark = target.indexOf('?'); path = mark < 0 ? target : target.substring(0, mark);
            query = mark < 0 ? "" : target.substring(mark + 1); this.authority = authority;
            this.headers = headers; this.length = length; this.body = body;
        }
        public String text(int max) throws IOException {
            if (length > max) throw new IOException("Тело запроса слишком велико");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[4096]; long remaining = length;
            while (remaining > 0) {
                int count = body.read(block, 0, (int) Math.min(block.length, remaining));
                if (count < 0) throw new EOFException("Передача оборвана"); bytes.write(block, 0, count); remaining -= count;
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        public String token() { String auth = headers.get("authorization"); return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : ""; }
    }
    private final Handler handler;
    private final ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "natro-lan-http"), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "natro-lan-deadline"));
    public LanHttpServer(int port, Handler handler) throws IOException {
        this.handler = handler; server = new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(port));
        new Thread(this::accept, "natro-lan-listener").start();
    }
    public int port() { return server.getLocalPort(); }
    private void accept() {
        while (!closed) try {
            Socket socket = server.accept(); socket.setSoTimeout(20_000);
            if (!local(socket.getInetAddress()) || !local(socket.getLocalAddress())) { socket.close(); continue; }
            clients.add(socket);
            try { workers.execute(() -> serve(socket)); }
            catch (RejectedExecutionException busy) { clients.remove(socket); socket.close(); }
        } catch (IOException stopped) { if (!closed) close(); }
    }
    public static boolean local(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
    private void serve(Socket socket) {
        ScheduledFuture<?> deadline = null;
        try (Socket owned = socket) {
            if (closed) return;
            deadline = deadlines.schedule(() -> { try { socket.close(); } catch (IOException ignored) {} }, 5, TimeUnit.MINUTES);
            InputStream input = new BufferedInputStream(owned.getInputStream()); OutputStream output = owned.getOutputStream();
            String line = line(input, 4096); String[] start = line.split(" ", -1);
            if (start.length != 3 || !start[2].equals("HTTP/1.1") || !start[1].startsWith("/") || start[1].startsWith("//"))
                throw new IOException("Некорректный HTTP запрос");
            Map<String, String> headers = new HashMap<>(); int bytes = line.length();
            while (!(line = line(input, 8192)).isEmpty()) {
                bytes += line.length(); if (bytes > 16384) throw new IOException("Заголовок слишком велик");
                int colon = line.indexOf(':'); if (colon < 1) throw new IOException("Некорректный заголовок");
                String key = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
                if (headers.put(key, value) != null) throw new IOException("Повтор заголовка");
            }
            String address = socket.getLocalAddress().getHostAddress();
            String authority = (address.contains(":") ? "[" + address + "]" : address) + ":" + server.getLocalPort();
            if (!authority.equalsIgnoreCase(headers.get("host"))) { respond(output, 403, "text/plain", "Недопустимый Host"); return; }
            String origin = headers.get("origin");
            if (origin != null && !origin.equalsIgnoreCase("http://" + authority)) { respond(output, 403, "text/plain", "Другой Origin запрещён"); return; }
            if (headers.containsKey("transfer-encoding")) throw new IOException("Нужен Content-Length");
            long length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            if (length < 0 || length > MAX_UPLOAD) { respond(output, 413, "text/plain", "Лимит файла — 256 МиБ"); return; }
            if (!start[0].equals("GET") && (!start[0].equals("POST") || !"1".equals(headers.get("x-natro-client")) || origin == null)) {
                respond(output, 403, "text/plain", "Недопустимый запрос"); return;
            }
            handler.handle(new Request(start[0], start[1], authority, headers, length, input), output);
        } catch (Exception ignored) { /* no request bodies, PINs or tokens in logs */ }
        finally { if (deadline != null) deadline.cancel(false); clients.remove(socket); }
    }
    private static String line(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).replaceFirst("\r$", "");
            if (bytes.size() >= limit || value == 0) throw new IOException("Некорректный HTTP заголовок"); bytes.write(value);
        }
        throw new EOFException();
    }
    public static void respond(OutputStream output, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); header(output, status, type + "; charset=utf-8", bytes.length, null); output.write(bytes); output.flush();
    }
    public static void header(OutputStream output, int status, String type, long length, String disposition) throws IOException {
        String headers = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
                + "\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
                + (disposition == null ? "" : "\r\nContent-Disposition: " + disposition) + "\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
    }
    @Override public void close() {
        closed = true; try { server.close(); } catch (IOException ignored) {}
        cancelConnections(); workers.shutdownNow(); deadlines.shutdownNow();
    }
    public void cancelConnections() {
        for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) {}
    }
}
