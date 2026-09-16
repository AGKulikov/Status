/* SPDX-License-Identifier: GPL-3.0-or-later */
import dezz.status.widget.transfer.*;
import dezz.status.widget.adb.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real TCP/filesystem tests; no Android or vehicle connection. */
public final class LanCoreReplay {
    static int assertions;
    static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    interface Attempt { void run() throws Exception; }
    static void fails(Attempt attempt, String reason) throws Exception {
        try { attempt.run(); throw new AssertionError(reason); } catch (IOException expected) { assertions++; }
    }
    static void pairing() {
        LanPairing p = new LanPairing(0);
        check(p.pin().matches("[0-9]{6}"), "six digits including leading zero");
        check(p.pair("bad", 1) == null, "wrong PIN");
        String token = p.pair(p.pin(), 2);
        check(token != null && token.length() == 43, "256-bit bearer");
        check(p.authorized(token, 3), "valid session");
        check(!p.authorized(token + "x", 3), "wrong token");
        check(!p.authorized(null, 3), "missing token");
        p.rotate(10); check(!p.authorized(token, 11), "rotation revokes sessions");
        for (int i = 0; i < 5; i++) check(p.pair("x", 11) == null, "failed attempt");
        check(p.pair(p.pin(), 12) == null, "rate-limited even with correct PIN");
        check(p.pair(p.pin(), 60011) != null, "rate limit resets");
        check(p.pair(p.pin(), 300010) == null, "PIN expires exactly at five minutes");
        p.rotate(400000); List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) tokens.add(p.pair(p.pin(), 400001 + i));
        check(!p.authorized(tokens.get(0), 400100), "only four clients retained");
        check(p.authorized(tokens.get(4), 400100), "newest retained");
        check(!p.authorized(tokens.get(4), 2200100), "idle expiry boundary");
    }
    static void files(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("incoming"));
        LanFileStore store = new LanFileStore(dir.toFile());
        byte[] content = "Текст из iPhone\n\u0000binary".getBytes(StandardCharsets.UTF_8);
        LanFileStore.Item first = store.receive("../../file.txt", content.length, new ByteArrayInputStream(content));
        check(first.file.getCanonicalFile().getParentFile().equals(dir.toFile().getCanonicalFile()), "no traversal");
        check(Arrays.equals(content, Files.readAllBytes(first.file.toPath())), "byte-exact upload");
        check(store.find(first.id) != null && store.find("../../x") == null, "lookup only by opaque id");
        LanFileStore.Item duplicate = store.receive("../../file.txt", 0, new ByteArrayInputStream(new byte[0]));
        check(!duplicate.id.equals(first.id) && first.file.length() == content.length, "duplicate name never overwrites");
        int count = store.list().size();
        fails(() -> store.receive("partial", 20, new ByteArrayInputStream(new byte[3])), "short body accepted");
        check(store.list().size() == count, "aborted file is not listed");
        try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
            check(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part")), "partial removed");
        }
        fails(() -> store.receive("big", LanHttpServer.MAX_UPLOAD + 1, new ByteArrayInputStream(new byte[0])), "oversized");
        fails(() -> store.receive("negative", -1, new ByteArrayInputStream(new byte[0])), "negative");
        String unicode = "磁📱".repeat(150);
        LanFileStore.Item longName = store.receive(unicode, 1, new ByteArrayInputStream(new byte[]{7}));
        check(longName.file.getName().getBytes(StandardCharsets.UTF_8).length <= 255, "UTF-8 filename byte limit");
        check(!Character.isHighSurrogate(longName.name.charAt(longName.name.length() - 1)), "no split emoji");
        Path outside = Files.write(root.resolve("private.txt"), content);
        Path symlink = dir.resolve(UUID.randomUUID() + "_leak.txt");
        Files.createSymbolicLink(symlink, outside);
        check(store.list().stream().noneMatch(item -> item.file.toPath().equals(symlink)), "never serve symbolic link");
        Path stale = Files.write(dir.resolve("." + UUID.randomUUID() + ".part"), content);
        Path unrelated = Files.write(dir.resolve("my.part"), content);
        new LanFileStore(dir.toFile());
        check(!Files.exists(stale) && Files.exists(unrelated) && Files.exists(first.file.toPath()), "restart cleans only own unfinished uploads");
        LanFileStore quota = new LanFileStore(root.resolve("quota").toFile());
        for (int i = 0; i < 100; i++) quota.receive("empty", 0, new ByteArrayInputStream(new byte[0]));
        fails(() -> quota.receive("overflow", 0, new ByteArrayInputStream(new byte[0])), "file-count quota");
        Thread.currentThread().interrupt();
        try { fails(() -> quotaFree(root).receive("cancel", 1, new ByteArrayInputStream(new byte[]{1})), "interrupted upload"); }
        finally { Thread.interrupted(); }
    }
    static LanFileStore quotaFree(Path root) throws IOException { return new LanFileStore(root.resolve("cancel").toFile()); }
    static String exchange(LanHttpServer server, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.replace("{host}", "127.0.0.1:" + server.port()).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush(); socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static void http() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        try (LanHttpServer server = new LanHttpServer(0, (req, out) -> {
            handled.incrementAndGet(); LanHttpServer.respond(out, 200, "text/plain", req.method + ":" + req.text(1024));
        })) {
            String get = "GET / HTTP/1.1\r\nHost: {host}\r\n\r\n";
            String reply = exchange(server, get);
            check(reply.startsWith("HTTP/1.1 200"), "own Host accepted");
            check(reply.contains("Content-Security-Policy:") && !reply.contains("Access-Control-Allow-Origin"), "CSP, no CORS");
            check(exchange(server, get.replace("{host}", "evil.example")).startsWith("HTTP/1.1 403"), "DNS rebinding rejected");
            String post = "POST /api/text HTTP/1.1\r\nHost: {host}\r\nOrigin: http://{host}\r\nX-Natro-Client: 1\r\nContent-Length: 3\r\n\r\nabc";
            check(exchange(server, post).endsWith("POST:abc"), "same-origin POST");
            int before = handled.get();
            check(exchange(server, post.replace("http://{host}", "http://evil.example")).startsWith("HTTP/1.1 403"), "cross-origin rejected");
            check(exchange(server, post.replace("Origin: http://{host}\r\n", "")).startsWith("HTTP/1.1 403"), "missing Origin rejected");
            check(exchange(server, post.replace("X-Natro-Client: 1\r\n", "")).startsWith("HTTP/1.1 403"), "HTML form rejected");
            check(exchange(server, post.replace("Content-Length: 3", "Content-Length: 268435457")).startsWith("HTTP/1.1 413"), "oversized HTTP rejected");
            check(exchange(server, post.replace("Content-Length: 3", "Content-Length: 3\r\nContent-Length: 4")).isEmpty(), "duplicate length rejected");
            check(exchange(server, post.replace("Content-Length: 3", "Transfer-Encoding: chunked")).isEmpty(), "chunked smuggling rejected");
            check(exchange(server, get.replace("GET / ", "GET //evil.example/ ")).isEmpty(), "network-path target rejected");
            check(handled.get() == before, "invalid requests never reach application");
        }
        check(LanHttpServer.local(InetAddress.getByName("172.20.10.2")), "iPhone hotspot subnet");
        check(LanHttpServer.local(InetAddress.getByName("192.168.1.2")), "LAN subnet");
        check(LanHttpServer.local(InetAddress.getByName("fd00::1")), "IPv6 ULA");
        check(!LanHttpServer.local(InetAddress.getByName("8.8.8.8")), "no public interface");
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
        try {
            for (int n = 0; n < 20; n++) {
                LanHttpServer server = new LanHttpServer(0, (req, out) -> {});
                List<Socket> clients = new ArrayList<>();
                for (int i = 0; i < 8; i++) clients.add(new Socket("127.0.0.1", server.port()));
                server.close(); server.close();
                for (Socket socket : clients) socket.close();
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean remaining;
            do {
                remaining = Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().startsWith("natro-lan-"));
                if (remaining) Thread.sleep(10);
            } while (remaining && System.nanoTime() < until);
            check(!remaining, "all server threads terminate after close");
            check(uncaught.get() == null, "no shutdown/scheduling race: " + uncaught.get());
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }
    static void shell() throws Exception {
        for (String command : new String[]{"printf \"a'b\"", "printf '✓'; exit 7", "false # comment", "printf ok; true"}) {
            AdbShellResult frame = new AdbShellResult();
            Process process = new ProcessBuilder("sh", "-c", frame.wrap(command)).redirectErrorStream(true).start();
            byte[] bytes = process.getInputStream().readAllBytes(); process.waitFor();
            for (byte b : bytes) frame.accept(new byte[]{b});
            AdbShellResult.Result result = frame.finish();
            check(result.exitCode != null, "framing survives quoting, exit and comments");
            if (command.contains("exit 7")) check(result.exitCode == 7 && result.output.equals("✓"), "real failure code and Unicode");
            if (command.startsWith("false")) check(!result.success(), "nonzero is not success");
        }
        AdbShellResult missing = new AdbShellResult(); missing.accept("EOF before status".getBytes(StandardCharsets.UTF_8));
        check(!missing.finish().success() && missing.finish().exitCode == null, "disconnect remains unknown");
        try { AdbCommandCatalog.appendService("valid.app/.Svc:unknown-format", "natro.app/.Svc"); throw new AssertionError("unknown existing service erased"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[1]);
        switch (args[0]) { case "pairing": pairing(); break; case "files": files(root); break;
            case "http": http(); break; case "shell": shell(); break; default: throw new AssertionError("unknown suite"); }
        System.out.println(args[0] + ": PASS (" + assertions + " assertions)");
    }
}
