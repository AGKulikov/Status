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

/** Foreground LAN host; boot startup and persisted ADB permission require remembered trust. */
public final class LanTransferService extends Service {
    public static volatile LanTransferService instance;
    public static final int PORT = 8765;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Set<String> seenCommands = new HashSet<>();
    private final ExecutorService exportWorker = Executors.newSingleThreadExecutor();
    private volatile Export export;
    private volatile LanDownloadGrant downloadGrant;
    private volatile AdbConsoleSession dumpSession;
    private static final class Export {
        final String token, id, format;
        volatile boolean done;
        volatile boolean cancelled;
        volatile File file;
        volatile String error = "";
        volatile String progress = "Подготовка…";
        Export(String token, String id, String format) { this.token=token; this.id=id; this.format=format; }
        JSONObject state() throws JSONException {
            File ready=file;
            return new JSONObject().put("id",id).put("done",done).put("error",error).put("progress",progress)
                    .put("name",ready==null?"":ready.getName()).put("size",ready==null?0:ready.length());
        }
    }
    private volatile LanHttpServer server;
    private volatile LanFileStore files;
    private NatroSharedFolder sharedFolder;
    private volatile AdbConsoleSession adb;
    private volatile LanPairing pairing;
    private LanTrustStore trust;
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
                sharedFolder = new NatroSharedFolder(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Natro"));
                trust = new LanTrustStore(this);
                pairing = new LanPairing(SystemClock.elapsedRealtime(),trust); adb = new AdbConsoleSession(this);
                remoteCommands=trust.commandsAllowed();
                synchronized (this) { if (destroyed) { adb.close(); return; } server = new LanHttpServer(PORT, this::request); }
                status = "Сервер включён";
            } catch (Exception error) { status = "Не удалось запустить сервер: " + error.getMessage(); }
        }, "natro-lan-start").start();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        boolean restart=new LanTrustStore(this).autoStart();
        if(intent==null&&!restart)stopSelf();
        return restart?START_STICKY:START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    public String status() { return status; }
    public String pin() { return pairing == null ? "…" : pairing.pin(); }
    public long pinRemainingSeconds() { return pairing == null ? 0 : Math.max(0, (pairing.expires() - SystemClock.elapsedRealtime()) / 1000); }
    public String folder() { return files == null ? "" : files.path(); }
    public boolean commandsAllowed() { return remoteCommands; }
    public void allowCommands(boolean value) {
        remoteCommands=false;
        if(adb!=null)adb.cancel("Изменение разрешения удалённых команд");
        if(dumpSession!=null)dumpSession.cancel("Изменение разрешения удалённых команд");
        if(trust==null||!trust.setCommandsAllowed(value))throw new IllegalStateException("Разрешение ADB не сохранено");
        remoteCommands=value;
    }
    public void rotate() {
        remoteCommands=false;
        boolean revoked=pairing==null||pairing.rotate(SystemClock.elapsedRealtime());
        if (server != null) server.cancelConnections();
        if (adb != null) adb.cancel("Сопряжения отозваны");
        if (dumpSession != null) dumpSession.cancel("Сопряжения отозваны");
        if(!revoked){stopSelf();throw new IllegalStateException("Не удалось сохранить отзыв доступа; сервер остановлен");}
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
                JSONObject body=new JSONObject(request.text(1024));boolean remember=body.optBoolean("remember",false);
                String token = pairing.pair(body.optString("pin"), now,remember);
                if(token!=null&&remember)startService(new Intent(this,LanTransferService.class));
                json(output, token == null ? 403 : 200, token == null ? new JSONObject().put("error", "Сопряжение не сохранено: проверьте код, лимит попыток/4 телефонов и хранилище") : new JSONObject().put("token", token).put("remembered",remember)); return;
            }
            if(request.path.startsWith("/api/logs/file/")) { nativeDownload(request,output,now);return; }
            if (!pairing.authorized(request.token(), now)) { json(output, 401, new JSONObject().put("error", "Нужно сопряжение с магнитолой")); return; }
            switch (request.path) {
                case "/api/update":
                    dezz.status.widget.update.NatroUpdater updater=dezz.status.widget.update.NatroUpdater.get(this);
                    if(request.method.equals("POST")) {
                        if(!remoteCommands){json(output,403,new JSONObject().put("error","Разрешите удалённые команды на магнитоле"));return;}
                        String action=new JSONObject(request.text(1024)).getString("action");
                        if(action.equals("check"))updater.check();
                        else if(action.equals("download"))updater.download();
                        else if(action.equals("install")) {
                            Intent intent=updater.installationIntent();
                            if(destroyed||!remoteCommands||!pairing.authorized(request.token(),SystemClock.elapsedRealtime()))throw new IOException("Доступ отозван");
                            startActivity(intent);
                        } else throw new IOException("Неизвестное действие обновления");
                    }else if(!get(request,output))return;
                    json(output,200,updater.status());return;
                case "/api/logs":
                    if (request.method.equals("GET")) {
                        Export current=export;
                        if(current==null||!current.token.equals(request.token())) {
                            json(output,200,new JSONObject().put("done",true).put("id",""));return;
                        }
                        json(output,200,current.state());return;
                    }
                    if(!post(request,output))return;
                    JSONObject logRequest=new JSONObject(request.text(1024));
                    String exportId=logRequest.getString("id"), format=logRequest.getString("format");
                    if(!exportId.matches("[0-9a-f-]{36}")||!(format.equals("txt")||format.equals("zip")||format.equals("dump")||format.equals("vehicle")||format.equals("vehicle_all")))
                        throw new IOException("Некорректный формат экспорта");
                    if((format.equals("dump")||format.equals("vehicle")||format.equals("vehicle_all"))&&!remoteCommands)throw new IOException("Разрешите удалённые команды на магнитоле");
                    synchronized(exportWorker) {
                        Export current=export;
                        if(current!=null&&current.id.equals(exportId)) {
                            if(!current.token.equals(request.token())||!current.format.equals(format))throw new IOException("ID занят");
                            json(output,200,current.state());return;
                        }
                        if(current!=null&&!current.done){json(output,409,new JSONObject().put("error","Экспорт уже выполняется"));return;}
                        Export next=new Export(request.token(),exportId,format);
                        export=next;
                        exportWorker.execute(()->{
                            try {
                                if(next.cancelled||destroyed||!pairing.authorized(next.token,SystemClock.elapsedRealtime()))throw new IOException("Экспорт отменён или доступ отозван");
                                File result=(next.format.equals("dump")||(next.format.equals("vehicle")||next.format.equals("vehicle_all")))? collectDump(next):next.format.equals("txt")
                                        ? dezz.status.widget.diagnostics.DiagnosticJournal.copyForExport(this)
                                        : dezz.status.widget.diagnostics.DiagnosticBundle.create(this);
                                if(result==null||!result.isFile())throw new IOException("Не удалось сохранить логи");
                                next.file=result;
                            } catch(Exception failure){next.error="Экспорт не выполнен: "+(failure instanceof IOException?failure.getMessage():failure.getClass().getSimpleName());}
                            finally{next.done=true;}
                        });
                        json(output,200,next.state());return;
                    }
                case "/api/logs/cancel":
                    if(!post(request,output))return;
                    Export cancel=export;
                    if(cancel!=null&&cancel.token.equals(request.token())&&(cancel.format.equals("dump")||(cancel.format.equals("vehicle")||cancel.format.equals("vehicle_all")))&&!cancel.done){
                        cancel.cancelled=true;
                        if(dumpSession!=null)dumpSession.cancel("Сбор отменён с телефона");
                    }
                    json(output,200,new JSONObject().put("message","Отмена запрошена; проверьте результат, частичный архив может сохраниться"));return;
                case "/api/logs/download-ticket":
                    if(!post(request,output))return;
                    Export ticketExport=export;
                    String ticketId=new JSONObject(request.text(1024)).getString("id");
                    if(ticketExport==null||!ticketExport.id.equals(ticketId)||!ticketExport.token.equals(request.token())
                            ||!ticketExport.done||ticketExport.file==null)throw new IOException("Готовый экспорт не найден");
                    LanDownloadGrant grant=new LanDownloadGrant(request.token(),ticketId,now);downloadGrant=grant;
                    byte[] ticketBody=new JSONObject().put("url","/api/logs/file/"+ticketId).toString().getBytes(StandardCharsets.UTF_8);
                    LanHttpServer.header(output,200,"application/json; charset=utf-8",ticketBody.length,null,
                            "\r\nSet-Cookie: natroDownload="+grant.nonce+"; Path=/api/logs/file/; Max-Age=600; HttpOnly; SameSite=Strict");
                    output.write(ticketBody);return;
                case "/api/logs/download":
                    if(!get(request,output))return;
                    Export ready=export;
                    if(ready==null||!ready.id.equals(request.headers.get("x-natro-export"))||!ready.token.equals(request.token())||!ready.done||ready.file==null){
                        json(output,404,new JSONObject().put("error","Готовый экспорт не найден"));return;
                    }
                    // Open before writing headers. Never append a JSON error to a partial file body.
                    try(InputStream input=new FileInputStream(ready.file)) {
                        LanHttpServer.header(output,200,ready.format.equals("txt")?"text/plain; charset=utf-8":"application/zip",
                                ready.file.length(),"attachment; filename=\""+ready.file.getName()+"\"");
                        try {byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)output.write(block,0,count);}
                        catch(IOException disconnected){return;}
                    }
                    return;
                case "/api/unpair":
                    if(!post(request,output))return;
                    if(!pairing.forget(request.token()))throw new IOException("Не удалось сохранить отзыв доступа");
                    if(export!=null&&export.token.equals(request.token())&&dumpSession!=null)dumpSession.cancel("Сопряжение отозвано");
                    if(trust.hashes().isEmpty()){remoteCommands=false;if(adb!=null)adb.cancel("Сопряжение отозвано");}
                    json(output,200,new JSONObject().put("revoked",true));return;
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
                case "/api/natro-files":
                    if (!get(request, output)) return;
                    JSONArray shared = new JSONArray();
                    for (NatroSharedFolder.Item item : sharedFolder.list()) shared.put(new JSONObject()
                            .put("id", item.id).put("name", item.name).put("size", item.size).put("modified", item.modified));
                    json(output, 200, new JSONObject().put("files", shared)); return;
                case "/api/upload":
                    if (!post(request, output)) return;
                    String name = URLDecoder.decode(request.headers.getOrDefault("x-file-name", "file"), "UTF-8");
                    LanFileStore.Item file = files.receive(name, request.length, request.body);
                    json(output, 200, new JSONObject().put("name", file.name).put("size", file.file.length()).put("id", file.id)); return;
                case "/api/command":
                    if (!post(request, output)) return;
                    command(request, output); return;
                default:
                    if (request.path.startsWith("/api/natro-file/") && request.method.equals("GET")) {
                        // Validate and open before committing HTTP headers. Never append JSON to a partial binary response.
                        NatroSharedFolder.Download download = sharedFolder.open(request.path.substring(16));
                        try (NatroSharedFolder.Download owned = download) {
                            LanHttpServer.header(output, 200, "application/octet-stream", owned.size,
                                    "attachment; filename*=UTF-8''" + URLEncoder.encode(owned.name, "UTF-8").replace("+", "%20"));
                            owned.writeTo(output);
                        } catch (IOException interrupted) { /* socket closes; no false successful body */ }
                        return;
                    }
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
    private void nativeDownload(LanHttpServer.Request request,OutputStream output,long now)throws Exception {
        if(!get(request,output))return;
        LanDownloadGrant grant=downloadGrant;Export ready=export;
        String id=request.path.substring("/api/logs/file/".length());
        if(grant==null||ready==null||!grant.accepts(request.headers.get("cookie"),id,now)
                ||!ready.id.equals(grant.id)||!ready.token.equals(grant.owner)||!ready.done||ready.file==null
                ||!pairing.authorized(grant.owner,now)) {
            json(output,403,new JSONObject().put("error","Ссылка истекла. Снова нажмите «Проверить экспорт / скачать»."));return;
        }
        try(RandomAccessFile input=new RandomAccessFile(ready.file,"r")) {
            long total=input.length();long[] range;
            try{range=LanDownloadGrant.range(request.headers.get("range"),total);}
            catch(IOException bad){LanHttpServer.header(output,416,"text/plain",0,null,"\r\nContent-Range: bytes */"+total);return;}
            input.seek(range[0]);boolean partial=request.headers.containsKey("range");
            LanHttpServer.header(output,partial?206:200,ready.format.equals("txt")?"text/plain; charset=utf-8":"application/zip",
                    range[1],"attachment; filename=\""+ready.file.getName()+"\"","\r\nAccept-Ranges: bytes"
                            +(partial?"\r\nContent-Range: bytes "+range[0]+"-"+(range[0]+range[1]-1)+"/"+total:""));
            byte[] block=new byte[65536];long remaining=range[1];
            try {while(remaining>0) {
                if(destroyed||!pairing.authorized(grant.owner,SystemClock.elapsedRealtime()))return;
                int n=input.read(block,0,(int)Math.min(block.length,remaining));if(n<0)return;
                output.write(block,0,n);remaining-=n;
            }}catch(IOException disconnected){return;}
        }
    }
    private File collectDump(Export job)throws Exception {
        if(job.cancelled||!remoteCommands||destroyed)throw new IOException("Сбор отменён или доступ отозван");
        if(!dezz.status.widget.diagnostics.SystemFileDump.RUNNING.compareAndSet(false,true))throw new IOException("Системный дамп уже собирается");
        AdbConsoleSession session=new AdbConsoleSession(this);dumpSession=session;
        CountDownLatch done=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<dezz.status.widget.diagnostics.SystemFileDump.Report> report=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            boolean accepted=session.submitSystemDump(connection->{
                if(job.cancelled||!remoteCommands||destroyed||!pairing.authorized(job.token,SystemClock.elapsedRealtime()))throw new IOException("Сбор отменён или доступ отозван");
                connection.connect();
                dezz.status.widget.diagnostics.SystemFileDump.Shell shell=new dezz.status.widget.diagnostics.SystemFileDump.Shell(){
                            public AdbShellResult.Result command(String value)throws Exception{return connection.command(value);}
                            public AdbShellResult.Result commandBounded(String value,int limit)throws Exception{return connection.commandBounded(value,limit);}
                        };
                report.set((job.format.equals("vehicle")||job.format.equals("vehicle_all"))
                        ?dezz.status.widget.diagnostics.VehicleEvidenceArchive.collect(new File(getCacheDir(),"exports"),shell,text->job.progress=text,
                                dezz.status.widget.BuildConfig.VERSION_NAME,connection::collectQnxFull,job.format.equals("vehicle_all")?null:dezz.status.widget.diagnostics.QnxExclusions.load())
                        :dezz.status.widget.diagnostics.SystemFileDump.collect(new File(getCacheDir(),"exports"),shell,text->job.progress=text,
                                dezz.status.widget.BuildConfig.VERSION_NAME,connection::collectQnx,"unspecified"));
            },error->{
                dezz.status.widget.diagnostics.SystemFileDump.Report result=report.get();
                job.progress=result==null?"Дамп не создан":(error==null&&result.complete?"Сбор завершён":"Частичный дамп")+" · "+result.collected
                        +((job.format.equals("vehicle")||job.format.equals("vehicle_all"))?"":"/"+result.requested)+" файлов; подробности в manifest.tsv и каталоге QNX";
                session.close();dezz.status.widget.diagnostics.SystemFileDump.RUNNING.set(false);done.countDown();
            });
            if(!accepted){dezz.status.widget.diagnostics.SystemFileDump.RUNNING.set(false);throw new IOException("Сбор не принят");}
            done.await();
            if(report.get()==null)throw new IOException(job.progress);
            return report.get().file;
        }finally{session.close();if(dumpSession==session)dumpSession=null;}
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
        exportWorker.shutdownNow();
        if(dumpSession!=null)dumpSession.close();
    }
}
