/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.BuildConfig;
import dezz.status.widget.NavigatorInstallDiagnosticsActivity;

/** User-initiated PackageInstaller session; no shell, uninstall, reset or automatic retry. */
public final class NavigatorInstallDiagnostics {
    public static final String RESULT_ACTION = "ru.natro.statuswidget.NAVIGATOR_INSTALL_RESULT";
    private static final String PREFS = "navigator_install_diagnostics_v1";
    private static final String NONCE = "natro_install_nonce";
    // This platform extra is intentionally read by its documented wire name, not hidden APIs.
    private static final String LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "natro-install-diagnostics"); t.setDaemon(true); return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NavigatorInstallDiagnostics() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static String phase(Context c) { return prefs(c).getString("phase", NavigatorInstallPolicy.IDLE); }
    public static String summary(Context c) {
        return prefs(c).getString("summary", "Выбери APK Навигатора из пары 2.8.3, который не устанавливается.");
    }
    public static String report(Context c) { return prefs(c).getString("report", ""); }
    public static boolean busy() { return BUSY.get(); }
    private static File candidate(Context c) { return new File(c.getCacheDir(), "navigator-install-candidate.apk"); }

    private static JSONObject record(Context c) throws Exception {
        String value = report(c); return value.isEmpty() ? new JSONObject() : new JSONObject(value);
    }
    private static void save(Context c, String phase, String summary, JSONObject report) throws Exception {
        report.put("phase", phase).put("updatedAtMillis", System.currentTimeMillis());
        if (!prefs(c).edit().putString("phase", phase).putString("summary", summary)
                .putString("report", report.toString(2)).commit()) throw new IOException("Cannot persist install report");
    }
    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > 8000 ? value.substring(0, 8000) : value;
    }
    private static void localFailure(Context c, String stage, Exception error) {
        try {
            JSONObject r = record(c);
            r.put("localErrorStage", stage).put("localError", safe(error.toString()));
            String phase = NavigatorInstallPolicy.pending(phase(c))
                    ? NavigatorInstallPolicy.COMMITTED : NavigatorInstallPolicy.LOCAL_ERROR;
            save(c, phase,
                    "Не удалось завершить проверку. Подробности сохранены в отчёте.", r);
        } catch (Exception ignored) {
            prefs(c).edit().putString("phase", NavigatorInstallPolicy.LOCAL_ERROR)
                    .putString("summary", "Не удалось сохранить отчёт: " + safe(error.toString())).apply();
        }
    }
    /** Never replace the identity of an owned session which may still complete. */
    private static boolean previousSessionOpen(Context c) throws Exception {
        int id = prefs(c).getInt("session", -1);
        if (id < 0 || c.getPackageManager().getPackageInstaller().getSessionInfo(id) == null) return false;
        save(c, NavigatorInstallPolicy.COMMITTED,
                "Предыдущая попытка ещё открыта в Android. Дождись результата или отмени её.", record(c));
        return true;
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return b.toString();
    }
    private static String hash(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[128 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) d.update(buffer, 0, n);
        }
        return hex(d.digest());
    }
    private static JSONArray certificates(PackageInfo p) throws Exception {
        JSONArray result = new JSONArray();
        Signature[] signatures = p.signingInfo == null ? p.signatures : p.signingInfo.getApkContentsSigners();
        if (signatures != null) for (Signature signature : signatures) {
            result.put(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        }
        return result;
    }
    private static JSONObject packageRecord(PackageInfo p, boolean installed) throws Exception {
        JSONObject r = new JSONObject().put("package", p.packageName)
                .put("versionName", p.versionName).put("versionCode", p.getLongVersionCode())
                .put("certificateSha256", certificates(p));
        if (p.applicationInfo != null) {
            r.put("minSdk", p.applicationInfo.minSdkVersion).put("targetSdk", p.applicationInfo.targetSdkVersion)
                    .put("flags", p.applicationInfo.flags).put("uid", p.applicationInfo.uid);
            if (installed) {
                r.put("firstInstallTime", p.firstInstallTime).put("lastUpdateTime", p.lastUpdateTime)
                        .put("splitCount", p.applicationInfo.splitSourceDirs == null ? 0 : p.applicationInfo.splitSourceDirs.length);
                try { r.put("baseApkSha256", hash(new File(p.applicationInfo.sourceDir))); }
                catch (Exception e) { r.put("baseApkReadError", safe(e.toString())); }
            }
        }
        return r;
    }

    public static void prepare(Context context, Uri uri) {
        Context c = context.getApplicationContext();
        if (!NavigatorInstallPolicy.canSelect(phase(c)) || !BUSY.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            File partial = new File(c.getCacheDir(), "navigator-install-candidate.part");
            try {
                if (!NavigatorInstallPolicy.canSelect(phase(c))) return;
                if (previousSessionOpen(c)) return;
                JSONObject r = new JSONObject().put("format", "natro-navigator-install-v1")
                        .put("natroVersion", BuildConfig.VERSION_NAME).put("natroVersionCode", BuildConfig.VERSION_CODE)
                        .put("androidApi", Build.VERSION.SDK_INT).put("firmware", Build.FINGERPRINT)
                        .put("natroUid", android.os.Process.myUid())
                        .put("expectedApkSha256", NavigatorInstallPolicy.APK_SHA256)
                        .put("freeDataBytesBeforeCopy", Environment.getDataDirectory().getUsableSpace())
                        .put("canRequestPackageInstalls", c.getPackageManager().canRequestPackageInstalls());
                if (!prefs(c).edit().remove("nonce").remove("session").remove("confirmation").commit()) {
                    throw new IOException("Cannot reset the completed attempt identity");
                }
                save(c, NavigatorInstallPolicy.READING, "Проверяю APK и установленный Навигатор…", r);
                long total = 0; MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    if (in == null) throw new IOException("The selected document cannot be opened");
                    byte[] buffer = new byte[128 * 1024]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 256L * 1024 * 1024) throw new IOException("Selected file exceeds 256 MiB");
                        out.write(buffer, 0, n); digest.update(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                File apk = candidate(c);
                if (!partial.renameTo(apk)) throw new IOException("Cannot retain selected APK");
                String sha = hex(digest.digest());
                r.put("selectedApkBytes", total).put("selectedApkSha256", sha)
                        .put("freeDataBytesAfterCopy", Environment.getDataDirectory().getUsableSpace());
                PackageManager pm = c.getPackageManager();
                int flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
                PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
                r.put("selectedPackage", archive == null ? "PackageManager could not parse this APK" : packageRecord(archive, false));
                PackageInfo installed = null;
                try {
                    installed = pm.getPackageInfo(NavigatorInstallPolicy.PACKAGE, flags);
                    r.put("installedPackage", packageRecord(installed, true));
                } catch (PackageManager.NameNotFoundException e) {
                    r.put("installedPackage", "not installed for this Android user");
                }
                if (!NavigatorInstallPolicy.exactArtifact(total, sha)) {
                    save(c, NavigatorInstallPolicy.BLOCKED,
                            "Файл не совпадает с выпущенным Навигатором 2.8.3. Выбери APK этой пары; сведения сохранены в отчёте.", r);
                    return;
                }
                if (archive != null && !NavigatorInstallPolicy.PACKAGE.equals(archive.packageName)) {
                    throw new IOException("Unexpected package identity for the exact released APK");
                }
                if (installed != null) {
                    JSONArray certs = certificates(installed);
                    if (certs.length() > 0 && (certs.length() != 1
                            || !NavigatorInstallPolicy.CERT_SHA256.equals(certs.getString(0)))) {
                        save(c, NavigatorInstallPolicy.BLOCKED,
                                "Подпись установленного Навигатора отличается от подписи обновления. Сохрани отчёт; текущий Навигатор удалять не нужно.", r);
                        return;
                    }
                }
                save(c, NavigatorInstallPolicy.READY,
                        "Файл совпадает с выпущенным APK. Можно повторить установку и получить системный результат.", r);
            } catch (Exception e) { localFailure(c, "prepare", e); }
            finally { partial.delete(); BUSY.set(false); }
        });
    }

    public static void install(Context context) {
        Context c = context.getApplicationContext();
        if (!NavigatorInstallPolicy.READY.equals(phase(c)) || !BUSY.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            PackageInstaller installer = c.getPackageManager().getPackageInstaller();
            int id = -1; boolean committed = false;
            try {
                if (!NavigatorInstallPolicy.READY.equals(phase(c))) return;
                if (previousSessionOpen(c)) return;
                if (!c.getPackageManager().canRequestPackageInstalls()) throw new SecurityException("Android has not allowed this app to request installs");
                File apk = candidate(c);
                if (!NavigatorInstallPolicy.exactArtifact(apk.length(), hash(apk))) throw new IOException("Prepared APK changed or is missing; select the released APK again");
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(NavigatorInstallPolicy.PACKAGE); params.setSize(apk.length());
                id = installer.createSession(params);
                String nonce = UUID.randomUUID().toString();
                if (!prefs(c).edit().putInt("session", id).putString("nonce", nonce).remove("confirmation").commit()) throw new IOException("Cannot persist session identity");
                JSONObject r = record(c); r.put("sessionId", id);
                save(c, NavigatorInstallPolicy.WRITING, "Передаю APK установщику Android…", r);
                try (PackageInstaller.Session session = installer.openSession(id);
                     InputStream in = new FileInputStream(apk)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
                    try (OutputStream target = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buffer = new byte[128 * 1024]; int n;
                        while ((n = in.read(buffer)) != -1) { target.write(buffer, 0, n); digest.update(buffer, 0, n); count += n; }
                        session.fsync(target);
                    }
                    if (!NavigatorInstallPolicy.exactArtifact(count, hex(digest.digest()))) throw new IOException("Session APK bytes do not match the verified release");
                    Intent callback = new Intent(c, NavigatorInstallDiagnosticsActivity.class)
                            .setAction(RESULT_ACTION).putExtra(NONCE, nonce)
                            .setData(Uri.parse("natro-install://session/" + id + "/" + nonce))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent result = PendingIntent.getActivity(c, id, callback, flags);
                    save(c, NavigatorInstallPolicy.COMMITTED, "Ожидаю ответ установщика Android…", r);
                    session.commit(result.getIntentSender()); committed = true;
                }
            } catch (Exception e) {
                if (id >= 0 && !committed) try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
                localFailure(c, "session", e);
            } finally { BUSY.set(false); }
        });
    }

    /** Only an exact, still-active session may update the persisted outcome. */
    public static void receive(Context context, Intent intent) {
        if (intent == null || !RESULT_ACTION.equals(intent.getAction())) return;
        Context c = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                SharedPreferences p = prefs(c);
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                if (!NavigatorInstallPolicy.acceptsResult(phase(c), p.getInt("session", -1), p.getString("nonce", ""),
                        session, intent.getStringExtra(NONCE), status)) return;
                JSONObject r = record(c);
                r.put("systemStatus", status).put("systemMessage", safe(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
                        .put("systemPackage", safe(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)))
                        .put("otherPackage", safe(intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)));
                if (intent.hasExtra(LEGACY_STATUS)) r.put("legacyStatus", intent.getIntExtra(LEGACY_STATUS, Integer.MIN_VALUE));
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (NavigatorInstallPolicy.WAITING.equals(phase(c))) return;
                    Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirmation == null) throw new IOException("PackageInstaller did not provide its confirmation Intent");
                    // Preserve the platform confirmation for Activity/process recreation; never manufacture one.
                    if (!p.edit().putString("confirmation", confirmation.toUri(Intent.URI_INTENT_SCHEME)).commit()) throw new IOException("Cannot persist confirmation");
                    save(c, NavigatorInstallPolicy.USER_ACTION, NavigatorInstallPolicy.statusText(status), r);
                } else {
                    r.put("confirmedSuccess", status == PackageInstaller.STATUS_SUCCESS);
                    save(c, NavigatorInstallPolicy.DONE, NavigatorInstallPolicy.statusText(status), r);
                    p.edit().remove("confirmation").commit();
                    candidate(c).delete();
                }
            } catch (Exception e) { localFailure(c, "result", e); }
        });
    }

    public interface Confirmation { void ready(Intent intent, String error); }
    public static void continueConfirmation(Context context, Confirmation callback) {
        Context c = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                if (!NavigatorInstallPolicy.USER_ACTION.equals(phase(c))) return;
                String raw = prefs(c).getString("confirmation", "");
                if (raw.isEmpty()) throw new IOException("No confirmation from PackageInstaller");
                Intent confirmation = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
                save(c, NavigatorInstallPolicy.WAITING, "Подтверди установку в окне Android. Затем здесь появится результат.", record(c));
                MAIN.post(() -> callback.ready(confirmation, null));
            } catch (Exception e) {
                localFailure(c, "confirmation", e); MAIN.post(() -> callback.ready(null, safe(e.toString())));
            }
        });
    }

    public static void confirmationLaunchFailed(Context context, Exception error) {
        Context c = context.getApplicationContext();
        WORK.execute(() -> {
            if (!NavigatorInstallPolicy.WAITING.equals(phase(c))) return;
            try { save(c, NavigatorInstallPolicy.USER_ACTION, "Окно Android не открылось. Можно повторить подтверждение или сохранить отчёт.", record(c).put("confirmationLaunchError", safe(error.toString()))); }
            catch (Exception e) { localFailure(c, "confirmation launch", e); }
        });
    }

    /** Cancels only this diagnostic's own session; it never uninstalls the current app. */
    public static void cancel(Context context) {
        Context c = context.getApplicationContext();
        if (!NavigatorInstallPolicy.pending(phase(c)) || !BUSY.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            try {
                if (!NavigatorInstallPolicy.pending(phase(c))) return;
                int id = prefs(c).getInt("session", -1);
                PackageInstaller installer = c.getPackageManager().getPackageInstaller();
                if (id >= 0 && installer.getSessionInfo(id) != null) installer.abandonSession(id);
                JSONObject r = record(c); r.put("cancelRequested", true);
                save(c, NavigatorInstallPolicy.UNKNOWN,
                        "Отмена запрошена. Успешная установка не подтверждена; системный ответ появится в отчёте.", r);
                prefs(c).edit().remove("confirmation").commit();
            } catch (Exception e) { localFailure(c, "cancel", e); }
            finally { BUSY.set(false); }
        });
    }

    /** Reconcile after process death without inventing success or starting another install. */
    public static void reconcile(Context context) {
        Context c = context.getApplicationContext();
        if (BUSY.get()) return;
        WORK.execute(() -> {
            if (BUSY.get()) return;
            try {
                String phase = phase(c); int id = prefs(c).getInt("session", -1);
                PackageInstaller installer = c.getPackageManager().getPackageInstaller();
                if (NavigatorInstallPolicy.READING.equals(phase) || NavigatorInstallPolicy.WRITING.equals(phase)) {
                    if (id >= 0) try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
                    save(c, NavigatorInstallPolicy.UNKNOWN, "Подготовка прервалась. Выбери APK снова.", record(c));
                } else if (NavigatorInstallPolicy.pending(phase) && id >= 0 && installer.getSessionInfo(id) == null) {
                    save(c, NavigatorInstallPolicy.UNKNOWN,
                            "Сессия завершилась, но окончательный ответ Android не получен. Успех не подтверждён; сохрани отчёт.", record(c));
                }
            } catch (Exception e) { localFailure(c, "reconcile", e); }
        });
    }

    public static void export(Context context, java.util.function.Consumer<File> callback) {
        Context c = context.getApplicationContext();
        WORK.execute(() -> {
            File result = null;
            try {
                File dir = new File(c.getCacheDir(), "exports");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create export directory");
                result = new File(dir, "Natro-Navigator-install-" + System.currentTimeMillis() + ".txt");
                try (FileOutputStream stream = new FileOutputStream(result)) {
                    stream.write((summary(c) + "\n\n" + report(c) + "\n").getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
                }
            } catch (Exception ignored) { result = null; }
            File file = result; MAIN.post(() -> callback.accept(file));
        });
    }
}
