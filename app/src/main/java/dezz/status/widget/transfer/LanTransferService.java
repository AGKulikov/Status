/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import android.app.*;
import android.content.*;
import android.os.*;
import dezz.status.widget.LanTransferActivity;
import dezz.status.widget.adb.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit foreground-only LAN host; no cloud, no boot start, no saved pairing secrets. */
public final class LanTransferService extends Service {
    public static volatile LanTransferService instance;
    public static final int PORT = 8765;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Set<String> seenCommands = new HashSet<>();
    private LanHttpServer server;
    private LanFileStore files;
    private AdbConsoleSession adb;
    private LanPairing pairing;
    private volatile boolean remoteCommands;
    private volatile String clipboard = "", status = "Запуск…";
    private volatile boolean destroyed;
    private static final class Job {
        final String token, id, command; volatile String output = "Выполнение…"; volatile boolean done;
        Job(String token, String id, String command) { this.token = token; this.id = id; this.command = command; }
    }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("natro_lan", "Локальный обмен", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LanTransferActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(8765, new Notification.Builder(this, "natro_lan").setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Natro — локальный обмен включён").setContentText("Файлы, буфер обмена и команды · открыть управление")
                .setContentIntent(open).setOngoing(true).build());
        new Thread(() -> {
            try {
                File root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS); if (root == null) root = getFilesDir();
                files = new LanFileStore(new File(root, "Natro-Incoming"));
                pairing = new LanPairing(SystemClock.elapsedRealtime()); adb = new AdbConsoleSession(this);
                synchronized (this) { if (destroyed) { adb.close(); return; } server = new LanHttpServer(PORT, this::request); }
                status = "Сервер включён";
            } catch (Exception error) { status = "Не удалось запустить сервер: " + error.getMessage(); }
        }, "natro-lan-start").start();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    public String status() { return status; }
    public String pin() { return pairing == null ? "…" : pairing.pin(); }
    public long pinRemainingSeconds() { return pairing == null ? 0 : Math.max(0, (pairing.expires() - SystemClock.elapsedRealtime()) / 1000); }
    public String folder() { return files == null ? "" : files.path(); }
    public boolean commandsAllowed() { return remoteCommands; }
    public void allowCommands(boolean value) { remoteCommands = value; if (!value && adb != null) adb.cancel("Удалённое выполнение отключено"); }
    public void rotate() {
        if (pairing != null) pairing.rotate(SystemClock.elapsedRealtime());
        if (server != null) server.cancelConnections();
        if (adb != null) adb.cancel("Сопряжения отозваны");
    }
    public String text() { return clipboard; }
    public void setText(String text) {
        if (text.length() > 128 * 1024) throw new IllegalArgumentException("Текст слишком длинный");
        clipboard = text;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Natro LAN", text));
    }
    public static List<String> addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement(); if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) result.add("http://" + address.getHostAddress() + ":" + PORT);
                }
            }
        } catch (SocketException ignored) {}
        return result;
    }
    private void request(LanHttpServer.Request request, OutputStream output) throws Exception {
        try {
            if (request.method.equals("GET") && (request.path.equals("/") || request.path.equals("/client.js") || request.path.equals("/client.css"))) {
                String asset = request.path.equals("/") ? "index.html" : request.path.substring(1);
                try (InputStream input = getAssets().open("lan/" + asset)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
                    while ((count = input.read(block)) >= 0) bytes.write(block, 0, count);
                    String type = asset.endsWith("js") ? "text/javascript" : asset.endsWith("css") ? "text/css" : "text/html";
                    LanHttpServer.respond(output, 200, type, new String(bytes.toByteArray(), StandardCharsets.UTF_8)); return;
                }
            }
            long now = SystemClock.elapsedRealtime();
            if (request.path.equals("/api/pair") && request.method.equals("POST")) {
                String token = pairing.pair(new JSONObject(request.text(1024)).optString("pin"), now);
                json(output, token == null ? 403 : 200, token == null ? new JSONObject().put("error", "Неверный/истёкший код или превышен лимит попыток") : new JSONObject().put("token", token)); return;
            }
            if (!pairing.authorized(request.token(), now)) { json(output, 401, new JSONObject().put("error", "Нужно сопряжение с магнитолой")); return; }
            switch (request.path) {
                case "/api/status":
                    if (!get(request, output)) return;
                    json(output, 200, new JSONObject().put("status", status).put("adb", adb.endpoint()).put("commandsAllowed", remoteCommands).put("folder", folder())); return;
                case "/api/text":
                    if (request.method.equals("POST")) {
                        JSONObject body = new JSONObject(request.text(512 * 1024)); String text = body.getString("text");
                        if (text.length() > 128 * 1024) throw new IOException("Лимит текста — 128 КиБ");
                        CompletableFuture<Void> result = new CompletableFuture<>(); main.post(() -> {
                            try { setText(text); result.complete(null); } catch (Exception error) { result.completeExceptionally(error); }
                        }); result.get(3, TimeUnit.SECONDS);
                    } else {
                        CompletableFuture<Void> result = new CompletableFuture<>(); main.post(() -> {
                            try {
                                ClipboardManager manager = getSystemService(ClipboardManager.class);
                                ClipData data = manager.getPrimaryClip();
                                if (data != null && data.getItemCount() > 0 && data.getItemAt(0).getText() != null) {
                                    String text = data.getItemAt(0).getText().toString();
                                    if (text.length() <= 128 * 1024) clipboard = text;
                                }
                                result.complete(null);
                            } catch (Exception error) { result.completeExceptionally(error); }
                        }); result.get(3, TimeUnit.SECONDS);
                    }
                    json(output, 200, new JSONObject().put("text", clipboard)); return;
                case "/api/draft":
                    if (!post(request, output)) return;
                    String draft = new JSONObject(request.text(512 * 1024)).getString("text");
                    if (draft.length() > 128 * 1024) throw new IOException("Лимит команды — 128 КиБ");
                    AdbRemoteInbox.draft(draft); json(output, 200, new JSONObject().put("message", "Текст передан в поле ADB; команда не запускалась")); return;
                case "/api/files":
                    if (!get(request, output)) return;
                    JSONArray list = new JSONArray(); for (LanFileStore.Item file : files.list()) list.put(new JSONObject().put("id", file.id).put("name", file.name).put("size", file.file.length()));
                    json(output, 200, new JSONObject().put("files", list)); return;
                case "/api/upload":
                    if (!post(request, output)) return;
                    String name = URLDecoder.decode(request.headers.getOrDefault("x-file-name", "file"), "UTF-8");
                    LanFileStore.Item file = files.receive(name, request.length, request.body);
                    json(output, 200, new JSONObject().put("name", file.name).put("size", file.file.length()).put("id", file.id)); return;
                case "/api/command":
                    if (!post(request, output)) return;
                    command(request, output); return;
                default:
                    if (request.path.startsWith("/api/job/") && request.method.equals("GET")) {
                        Job job; synchronized (jobs) { job = jobs.get(request.path.substring(9)); }
                        if (job == null || !job.token.equals(request.token())) { json(output, 404, new JSONObject().put("error", "Команда не найдена; не отправляйте повторно вслепую")); return; }
                        json(output, 200, new JSONObject().put("id", job.id).put("done", job.done).put("output", job.output)); return;
                    }
                    if (request.path.startsWith("/api/file/") && request.method.equals("GET")) {
                        LanFileStore.Item item = files.find(request.path.substring(10));
                        if (item == null) { json(output, 404, new JSONObject().put("error", "Файл не найден")); return; }
                        LanHttpServer.header(output, 200, "application/octet-stream", item.file.length(), "attachment; filename*=UTF-8''" + URLEncoder.encode(item.name, "UTF-8").replace("+", "%20"));
                        try (InputStream input = new FileInputStream(item.file)) { byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count); } return;
                    }
                    json(output, 404, new JSONObject().put("error", "Не найдено"));
            }
        } catch (Exception error) { json(output, 400, new JSONObject().put("error", error.getMessage() == null ? "Запрос не выполнен" : error.getMessage())); }
    }
    private void command(LanHttpServer.Request request, OutputStream output) throws Exception {
        if (!remoteCommands) { json(output, 403, new JSONObject().put("error", "Разрешите удалённые команды на магнитоле")); return; }
        JSONObject body = new JSONObject(request.text(512 * 1024)); String command = body.getString("command").trim(), id = body.getString("id");
        if (!id.matches("[0-9a-f-]{36}") || command.isEmpty() || command.length() > 128 * 1024 || command.indexOf('\0') >= 0) throw new IOException("Некорректная команда");
        synchronized (jobs) {
            Job existing = jobs.get(id);
            if (existing != null) {
                if (!existing.token.equals(request.token()) || !existing.command.equals(command)) throw new IOException("ID занят другой командой");
                json(output, 200, new JSONObject().put("id", id).put("done", existing.done).put("output", existing.output)); return;
            }
            if (adb.busy()) { json(output, 409, new JSONObject().put("error", "Терминал занят; команда не отправлена")); return; }
            if (seenCommands.contains(id)) { json(output, 410, new JSONObject().put("error", "Эта команда уже принималась. Старый результат удалён, повторное выполнение запрещено")); return; }
            if (seenCommands.size() >= 256) { json(output, 429, new JSONObject().put("error", "Лимит команд сеанса; выключите и снова включите сервер")); return; }
            Job job = new Job(request.token(), id, command);
            if (!adb.submit(session -> {
                if (!remoteCommands || !pairing.authorized(job.token, SystemClock.elapsedRealtime())) throw new IOException("Доступ отозван");
                AdbRemoteInbox.log("iPhone > " + command);
                if (!session.connected()) session.connect();
                if (command.equalsIgnoreCase("root") || command.equalsIgnoreCase("adb root")) job.output = session.daemonRoot(true);
                else if (command.equalsIgnoreCase("unroot") || command.equalsIgnoreCase("adb unroot")) job.output = session.daemonRoot(false);
                else if (command.equalsIgnoreCase("connect")) job.output = session.endpoint();
                else job.output = session.command(command).describe();
            }, error -> {
                if (error != null) job.output = error.getMessage(); job.done = true;
                AdbRemoteInbox.log("iPhone < " + job.output);
            })) { json(output, 409, new JSONObject().put("error", "Терминал занят")); return; }
            jobs.put(id, job); seenCommands.add(id); while (jobs.size() > 32) jobs.remove(jobs.keySet().iterator().next());
            json(output, 200, new JSONObject().put("id", id));
        }
    }
    private boolean get(LanHttpServer.Request request, OutputStream output) throws Exception { return method(request, output, "GET"); }
    private boolean post(LanHttpServer.Request request, OutputStream output) throws Exception { return method(request, output, "POST"); }
    private boolean method(LanHttpServer.Request request, OutputStream output, String method) throws Exception {
        if (request.method.equals(method)) return true; json(output, 405, new JSONObject().put("error", "Метод запрещён")); return false;
    }
    private static void json(OutputStream output, int code, JSONObject body) throws IOException { LanHttpServer.respond(output, code, "application/json", body.toString()); }
    @Override public void onDestroy() {
        synchronized (this) { destroyed = true; if (server != null) server.close(); }
        remoteCommands = false; if (adb != null) adb.close(); instance = null; main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
