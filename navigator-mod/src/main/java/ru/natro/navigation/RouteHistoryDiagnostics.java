/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Explicit 30-minute experiment; RAM only, no route commands, no tokens/account IDs or logcat. */
final class RouteHistoryDiagnostics {
    private static final RouteHistoryDiagnostics INSTANCE = new RouteHistoryDiagnostics();
    private static final String DB_LISTENER = "com.yandex.datasync.DatabaseListener";
    private static final String SNAP_LISTENER = "com.yandex.datasync.Database$SnapshotListener";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RouteHistoryDiff diff = new RouteHistoryDiff();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private Application application;
    private Object database, databaseListener;
    private Read read;
    private boolean running;
    private int epoch, dropped, logChars;
    private long started, lastSyncRequest;
    private String state = "Сбор выключен", lastFailure = "";
    private WeakReference<TextView> text = new WeakReference<>(null);
    private WeakReference<Button> startButton = new WeakReference<>(null);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (SystemClock.elapsedRealtime() - started >= 30 * 60_000L) {
                stop("SESSION_TIME_LIMIT");
                return;
            }
            try {
                Object resolved = RouteHistoryAccess.resolve(application);
                if (resolved != database) bind(resolved);
                if (database == null) {
                    state = "Ожидание базы: войдите в Яндекс и откройте историю поиска";
                } else if (read == null) openSnapshot();
            } catch (Exception failure) { failure("DISCOVERY_ERROR", failure); }
            refresh();
            if (running) main.postDelayed(this, 2000);
        }
    };

    static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        INSTANCE.openDialog(activity);
    }
    private void openDialog(Activity activity) {
        application = activity.getApplication();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView hint = new TextView(activity);
        hint.setText("История назначений · эксперимент\nОдин аккаунт Яндекса на обоих устройствах. "
                + "Журнал содержит названия и координаты. Сбор в памяти — до 30 минут. "
                + "Запись в истории не доказывает активный маршрут.");
        box.addView(hint);
        Button start = button(activity, box, running ? "Остановить сбор" : "Начать сбор", () -> {
            if (running) stop("SESSION_STOP"); else start();
        });
        startButton = new WeakReference<>(start);
        button(activity, box, "Запросить синхронизацию", this::requestSync);
        button(activity, box, "Отметить действие на iPhone", () -> new AlertDialog.Builder(activity)
                .setTitle("Что только что сделали?")
                .setItems(new String[]{"Выбрали точку", "Нажали Поехали", "Сменили точку", "Отменили маршрут"},
                        (dialog, which) -> event("USER_MARKER", "action",
                                new String[]{"SELECT", "GO", "CHANGE", "CANCEL"}[which]))
                .setNegativeButton("Закрыть", null).show());
        button(activity, box, "Скопировать журнал", () -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Natro route history diagnostic", export()));
                Toast.makeText(activity, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            }
        });
        button(activity, box, "Очистить журнал", () -> {
            log.clear(); dropped = 0; logChars = 0;
            event("LOG_CLEARED", "baseline_preserved", running);
        });
        TextView output = new TextView(activity);
        output.setTextIsSelectable(true);
        output.setTextSize(12);
        text = new WeakReference<>(output);
        box.addView(output);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Natro · диагностика маршрута")
                .setView(scroll).setPositiveButton("Свернуть", null).create();
        dialog.setOnDismissListener(ignored -> {
            if (text.get() == output) { text.clear(); startButton.clear(); }
        });
        refresh();
        dialog.show();
    }
    private static Button button(Activity activity, LinearLayout box, String title, Runnable action) {
        Button result = new Button(activity);
        result.setText(title);
        result.setOnClickListener(v -> action.run());
        box.addView(result);
        return result;
    }
    private void start() {
        if (read != null) { state = "Ожидается завершение предыдущего чтения"; refresh(); return; }
        started = SystemClock.elapsedRealtime();
        running = true;
        epoch++;
        lastFailure = "";
        lastSyncRequest = 0;
        diff.reset();
        state = "Поиск базы";
        event("SESSION_START", "schema", "route-history-diag-1");
        main.removeCallbacks(tick);
        main.post(tick);
    }
    private void stop(String reason) {
        running = false;
        main.removeCallbacks(tick);
        detach();
        state = "Сбор выключен";
        event(reason);
    }
    private void detach() {
        epoch++;
        if (read != null && read.snapshot != null) read.finish(false);
        if (database != null && databaseListener != null) {
            try { RouteHistoryAccess.listenerCall(database, "removeListener", DB_LISTENER, databaseListener); }
            catch (Exception failure) { failure("LISTENER_REMOVE_ERROR", failure); }
        }
        databaseListener = null;
        database = null;
        diff.reset();
    }
    private void bind(Object next) throws Exception {
        detach();
        database = next;
        event("DATABASE_CHANGED", "available", next != null);
        if (next == null) return;
        final int boundEpoch = epoch;
        databaseListener = RouteHistoryAccess.proxy(next, DB_LISTENER, (proxy, method, args) -> {
            String name = method.getName();
            // Do not retain error/info objects or serialize server text/account information.
            String errorType = name.equals("onDatabaseError") && args != null && args[0] != null
                    ? args[0].getClass().getSimpleName() : "";
            main.post(() -> {
                if (!running || boundEpoch != epoch) return;
                if (name.equals("onDatabaseSyncStarted")) event("SYNC_STARTED");
                else if (name.equals("onDatabaseSyncFinished")) event("SYNC_FINISHED");
                else if (name.equals("onDatabaseError")) event("SYNC_ERROR", "type", errorType);
                else if (name.equals("onDatabaseReset")) {
                    // Outstanding snapshots from the old database generation are not evidence.
                    detach(); event("DATABASE_RESET");
                }
            });
            return null;
        });
        try { RouteHistoryAccess.listenerCall(next, "addListener", DB_LISTENER, databaseListener); }
        catch (Exception failure) { detach(); throw failure; }
        state = "Наблюдение истории";
    }
    private void requestSync() {
        if (!running || database == null) { event("SYNC_NOT_REQUESTED", "reason", "no_active_database"); return; }
        long now = SystemClock.elapsedRealtime();
        if (lastSyncRequest != 0 && now - lastSyncRequest < 10_000) {
            event("SYNC_NOT_REQUESTED", "reason", "wait_10_seconds"); return;
        }
        try {
            if (RouteHistoryAccess.resolve(application) != database) {
                detach(); event("SYNC_NOT_REQUESTED", "reason", "database_changed"); return;
            }
            RouteHistoryAccess.call(database, "requestSync");
            lastSyncRequest = now;
            event("SYNC_REQUESTED"); // NOT success: only the native callback can report completion.
        } catch (Exception failure) { failure("SYNC_REQUEST_ERROR", failure); }
    }
    private void openSnapshot() throws Exception {
        Read operation = new Read(epoch, database);
        read = operation;
        operation.listener = RouteHistoryAccess.proxy(database, SNAP_LISTENER, (proxy, method, args) -> {
            if (method.getName().equals("onSnapshotOpened") && args != null && args.length == 1) {
                Object snapshot = args[0];
                main.post(() -> operation.opened(snapshot));
            }
            return null;
        });
        try {
            RouteHistoryAccess.listenerCall(database, "openSnapshot", SNAP_LISTENER, operation.listener);
            main.postDelayed(operation.timeout, 10_000);
        } catch (Exception failure) { operation.finish(false); throw failure; }
    }
    private final class Read {
        final int generation;
        final Object owner;
        final List<RouteHistoryDiff.Item> items = new ArrayList<>();
        Object listener, snapshot, iterator;
        boolean done;
        int scanned;
        final Runnable timeout = () -> {
            if (!done) { event("SNAPSHOT_TIMEOUT"); stop("SESSION_READ_TIMEOUT"); }
        };
        Read(int generation, Object owner) { this.generation = generation; this.owner = owner; }
        boolean current() { return running && generation == epoch && database == owner; }
        void opened(Object value) {
            if (done) { closeSnapshot(value); return; }
            snapshot = value;
            try {
                if (!current() || RouteHistoryAccess.resolve(application) != owner) { finish(false); return; }
                if (!Boolean.TRUE.equals(RouteHistoryAccess.call(snapshot, "isValid"))) {
                    throw new IllegalStateException("invalid snapshot");
                }
                Object collections = RouteHistoryAccess.call(snapshot, "collections");
                if (!(collections instanceof List)) throw new IllegalStateException("unknown collections");
                for (Object collection : (List<?>) collections) {
                    if (RouteHistoryAccess.COLLECTION_ID.equals(RouteHistoryAccess.call(collection, "getCollectionId"))) {
                        iterator = RouteHistoryAccess.call(collection, "records");
                        break;
                    }
                }
                if (iterator == null) { state = "Коллекция истории пока недоступна"; finish(false); return; }
                step();
            } catch (Exception failure) { failure("SNAPSHOT_ERROR", failure); finish(false); }
        }
        void step() {
            if (done) return;
            try {
                if (!current() || !Boolean.TRUE.equals(RouteHistoryAccess.call(snapshot, "isValid"))) {
                    finish(false); return;
                }
                for (int i = 0; i < 20; i++) {
                    if (!Boolean.TRUE.equals(RouteHistoryAccess.call(iterator, "hasNext"))) {
                        if (RouteHistoryAccess.resolve(application) == owner) finish(true);
                        else finish(false);
                        return;
                    }
                    if (++scanned > RouteHistoryDiff.MAX_RECORDS) throw new IllegalStateException("record limit");
                    Object record = RouteHistoryAccess.call(iterator, "next");
                    if (!Boolean.TRUE.equals(RouteHistoryAccess.call(record, "deleted"))) items.add(RouteHistoryAccess.read(record));
                }
                main.post(this::step);
            } catch (Exception failure) { failure("SNAPSHOT_READ_ERROR", failure); finish(false); }
        }
        void finish(boolean accept) {
            if (done) return;
            done = true;
            main.removeCallbacks(timeout);
            if (snapshot != null) closeSnapshot(snapshot);
            snapshot = null; iterator = null; listener = null;
            if (read == this) read = null;
            if (accept && current()) {
                try {
                    List<RouteHistoryDiff.Change> changes = diff.accept(items);
                    for (RouteHistoryDiff.Change change : changes) {
                        RouteHistoryDiff.Item item = change.item;
                        event(change.kind, "record_id", item.id, "title", item.title,
                                "latitude", item.latitude, "longitude", item.longitude,
                                "last_used_raw", item.lastUsed, "origin_device", "unknown");
                    }
                    state = "Наблюдение · записей: " + items.size();
                    lastFailure = "";
                    refresh();
                } catch (Exception failure) { failure("DIFF_ERROR", failure); }
            }
        }
    }
    private void closeSnapshot(Object snapshot) {
        try { RouteHistoryAccess.call(snapshot, "close"); }
        catch (Exception failure) { failure("SNAPSHOT_CLOSE_ERROR", failure); }
    }
    private void failure(String kind, Exception failure) {
        String type = failure.getClass().getSimpleName();
        state = kind + " · " + type;
        if (!state.equals(lastFailure)) { lastFailure = state; event(kind, "type", type); }
    }
    private void event(String kind, Object... pairs) {
        try {
            JSONObject object = new JSONObject();
            object.put("event", kind);
            object.put("observed_unix_ms", System.currentTimeMillis());
            object.put("observed_elapsed_ms", SystemClock.elapsedRealtime());
            object.put("epoch", epoch);
            for (int i = 0; i + 1 < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]);
            String line = object.toString();
            log.addLast(line); logChars += line.length();
            while (log.size() > 512 || logChars > 64000) {
                logChars -= log.removeFirst().length(); dropped++;
            }
            refresh();
        } catch (Exception ignored) { state = "Ошибка записи журнала"; }
    }
    private String export() {
        StringBuilder result = new StringBuilder("Natro route-history-diag-1\nHistory is not active navigation. last_used_raw is unconverted native timestamp.\n");
        result.append("dropped_lines=").append(dropped).append('\n');
        for (String line : log) result.append(line).append('\n');
        return result.toString();
    }
    private void refresh() {
        Button button = startButton.get();
        if (button != null) button.setText(running ? "Остановить сбор" : "Начать сбор");
        TextView output = text.get();
        if (output == null) return;
        StringBuilder tail = new StringBuilder(state).append("\nСтрок потеряно: ").append(dropped).append('\n');
        int skip = Math.max(0, log.size() - 12), index = 0;
        for (String line : log) if (index++ >= skip) tail.append(line).append('\n');
        output.setText(tail);
    }
}
