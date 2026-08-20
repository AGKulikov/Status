package dezz.status.widget.phone;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.PathParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Persistent catalog and icon cache for iOS applications observed through ANCS.
 *
 * <p>Catalog metadata is saved synchronously on the first notification. Icon retrieval is
 * asynchronous; the next notification can therefore switch to the independently configured icon
 * layout. With storage permission, files live in {@code /sdcard/StatusWidget/ANCS-icons}, which
 * Android 9 does not delete on app update or uninstall.</p>
 */
public final class PhoneAppIconStore {
    private static final String TAG = "PhoneAppIconStore";
    private static final String DIRECTORY = "StatusWidget/ANCS-icons";
    private static final String CATALOG = "catalog.json";
    private static final long MAX_ICON_BYTES = 1_500_000L;
    private static final Pattern PATH_DATA = Pattern.compile(
            "<path[^>]*\\sd\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEW_BOX = Pattern.compile(
            "viewBox\\s*=\\s*[\"']\\s*[-.0-9]+\\s+[-.0-9]+\\s+"
                    + "([.0-9]+)\\s+([.0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> SIMPLE_ICONS = createSimpleIcons();

    private static volatile PhoneAppIconStore instance;

    public static PhoneAppIconStore get(@NonNull Context context) {
        PhoneAppIconStore local = instance;
        if (local == null) {
            synchronized (PhoneAppIconStore.class) {
                local = instance;
                if (local == null) {
                    local = new PhoneAppIconStore(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    public static final class Observation {
        public final boolean iconWasCached;
        @NonNull public final String appIdentifier;

        Observation(boolean iconWasCached, @NonNull String appIdentifier) {
            this.iconWasCached = iconWasCached;
            this.appIdentifier = appIdentifier;
        }
    }

    public static final class App {
        @NonNull public final String identifier;
        @NonNull public final String name;
        public final int categoryId;
        public final long firstSeen;
        public final long lastSeen;
        public final int notifications;
        public final boolean iconCached;

        App(Record record, boolean iconCached) {
            identifier = record.identifier;
            name = record.name;
            categoryId = record.categoryId;
            firstSeen = record.firstSeen;
            lastSeen = record.lastSeen;
            notifications = record.notifications;
            this.iconCached = iconCached;
        }
    }

    private static final class Record {
        String identifier = "";
        String name = "";
        int categoryId;
        long firstSeen;
        long lastSeen;
        int notifications;
        String iconFile = "";
        String iconType = "";
    }

    private static final class Download {
        final byte[] bytes;
        final String type;

        Download(byte[] bytes, String type) {
            this.bytes = bytes;
            this.type = type;
        }
    }

    private final Context context;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Icon downloads remain best-effort when an OEM rejects priority changes.
            }
            runnable.run();
        }, "phone-app-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();
    private final Set<String> downloads =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private PhoneAppIconStore(Context context) {
        this.context = context;
        synchronized (this) {
            mergeCatalog(privateDirectory());
            File external = externalDirectory(false);
            if (external != null) {
                mergeCatalog(external);
                migrateToExternalLocked();
            }
        }
    }

    @NonNull
    public synchronized Observation observe(@Nullable String identifier,
                                            @Nullable String name,
                                            int categoryId) {
        String normalized = normalizeIdentifier(identifier);
        if (normalized.isEmpty()) return new Observation(false, "");
        maybeMigrateLocked();
        long now = System.currentTimeMillis();
        Record record = records.get(normalized);
        if (record == null) {
            record = new Record();
            record.identifier = normalized;
            record.firstSeen = now;
            records.put(normalized, record);
        }
        String safeName = clean(name, 256);
        if (safeName.isEmpty()) safeName = PhoneAppCatalog.displayNameFallback(normalized);
        record.name = safeName;
        record.categoryId = PhoneNotificationFilter.normalizeCategoryId(categoryId);
        record.lastSeen = now;
        record.notifications = Math.min(Integer.MAX_VALUE, record.notifications + 1);
        boolean cached = iconFile(record) != null;
        saveCatalogLocked();
        if (!cached && downloads.add(normalized)) {
            Record captured = record;
            worker.execute(() -> downloadIcon(captured.identifier));
        }
        return new Observation(cached, normalized);
    }

    public synchronized void updateName(@Nullable String identifier, @Nullable String name) {
        Record record = records.get(normalizeIdentifier(identifier));
        String safeName = clean(name, 256);
        if (record != null && !safeName.isEmpty() && !safeName.equals(record.name)) {
            record.name = safeName;
            saveCatalogLocked();
        }
    }

    @NonNull
    public synchronized List<App> catalog() {
        maybeMigrateLocked();
        List<App> apps = new ArrayList<>();
        for (Record record : records.values()) {
            apps.add(new App(record, iconFile(record) != null));
        }
        apps.sort(Comparator.comparing(app -> app.name, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(apps);
    }

    public synchronized boolean hasIcon(@Nullable String identifier) {
        Record record = records.get(normalizeIdentifier(identifier));
        return record != null && iconFile(record) != null;
    }

    @Nullable
    public synchronized Drawable drawable(@Nullable String identifier) {
        Record record = records.get(normalizeIdentifier(identifier));
        File icon = record == null ? null : iconFile(record);
        if (icon == null) return null;
        try {
            if ("svg".equals(record.iconType)) {
                String xml = new String(readBounded(icon), StandardCharsets.UTF_8);
                Matcher pathMatcher = PATH_DATA.matcher(xml);
                if (!pathMatcher.find()) return null;
                Path path = PathParser.createPathFromPathData(pathMatcher.group(1));
                if (path == null) return null;
                float width = 24f;
                float height = 24f;
                Matcher viewBox = VIEW_BOX.matcher(xml);
                if (viewBox.find()) {
                    width = Float.parseFloat(viewBox.group(1));
                    height = Float.parseFloat(viewBox.group(2));
                }
                return new PhoneAppIconDrawable(path, width, height);
            }
            byte[] bytes = readBounded(icon);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (Exception error) {
            Log.w(TAG, "Could not decode cached icon " + icon, error);
            return null;
        }
    }

    public synchronized void promoteToExternalStorage() {
        migrateToExternalLocked();
    }

    private void downloadIcon(String identifier) {
        try {
            Record snapshot;
            synchronized (this) {
                snapshot = records.get(identifier);
                if (snapshot == null) return;
            }
            Download downloaded = downloadSimpleIcon(identifier, snapshot.name);
            if (downloaded == null) downloaded = downloadAppleArtwork(identifier);
            if (downloaded == null) return;
            synchronized (this) {
                Record current = records.get(identifier);
                if (current == null) return;
                maybeMigrateLocked();
                File directory = writableDirectory();
                if (!directory.exists() && !directory.mkdirs()) return;
                String fileName = sha256(identifier) + "." + downloaded.type;
                File file = new File(directory, fileName);
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(downloaded.bytes);
                    output.flush();
                }
                current.iconFile = fileName;
                current.iconType = downloaded.type;
                saveCatalogLocked();
            }
        } catch (Exception error) {
            Log.w(TAG, "Icon download failed for " + identifier, error);
        } finally {
            downloads.remove(identifier);
        }
    }

    @Nullable
    private Download downloadSimpleIcon(String identifier, String name) throws IOException {
        String slug = simpleIconSlug(identifier, name);
        if (slug.isEmpty()) return null;
        byte[] bytes = download("https://cdn.jsdelivr.net/npm/simple-icons@16/icons/"
                + slug + ".svg");
        if (bytes == null
                || !PATH_DATA.matcher(new String(bytes, StandardCharsets.UTF_8)).find()) {
            return null;
        }
        return new Download(bytes, "svg");
    }

    @Nullable
    private Download downloadAppleArtwork(String identifier) throws Exception {
        String lookup = "https://itunes.apple.com/lookup?bundleId="
                + URLEncoder.encode(identifier, "UTF-8") + "&country=us";
        byte[] response = download(lookup);
        if (response == null) return null;
        JSONArray results = new JSONObject(new String(response, StandardCharsets.UTF_8))
                .optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject result = results.optJSONObject(0);
        if (result == null) return null;
        String url = result.optString(
                "artworkUrl512", result.optString("artworkUrl100", "")).trim();
        byte[] bytes = url.isEmpty() ? null : download(url);
        return bytes == null ? null : new Download(bytes, "png");
    }

    @Nullable
    private byte[] download(String url) throws IOException {
        try (Response response = http.newCall(
                new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful()) return null;
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_ICON_BYTES) return null;
            byte[] bytes = body.bytes();
            return bytes.length == 0 || bytes.length > MAX_ICON_BYTES ? null : bytes;
        }
    }

    private synchronized void mergeCatalog(@Nullable File directory) {
        if (directory == null) return;
        File catalog = new File(directory, CATALOG);
        if (!catalog.isFile()) return;
        try {
            JSONArray apps = new JSONObject(
                    new String(readBounded(catalog), StandardCharsets.UTF_8))
                    .optJSONArray("apps");
            if (apps == null) return;
            for (int index = 0; index < apps.length(); index++) {
                JSONObject json = apps.optJSONObject(index);
                if (json == null) continue;
                String identifier = normalizeIdentifier(json.optString("id"));
                if (identifier.isEmpty()) continue;
                Record value = new Record();
                value.identifier = identifier;
                value.name = clean(json.optString("name"), 256);
                value.categoryId = PhoneNotificationFilter.normalizeCategoryId(
                        json.optInt("category", 0));
                value.firstSeen = Math.max(0L, json.optLong("first_seen", 0L));
                value.lastSeen = Math.max(value.firstSeen,
                        json.optLong("last_seen", value.firstSeen));
                value.notifications = Math.max(0, json.optInt("notifications", 0));
                value.iconFile = safeFileName(json.optString("icon_file"));
                value.iconType = normalizeType(json.optString("icon_type"));
                Record old = records.get(identifier);
                if (old == null || value.lastSeen >= old.lastSeen) {
                    records.put(identifier, value);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not read " + catalog, error);
        }
    }

    private void saveCatalogLocked() {
        saveCatalogTo(writableDirectory());
    }

    private void saveCatalogTo(File directory) {
        try {
            if (!directory.exists() && !directory.mkdirs()) return;
            JSONArray apps = new JSONArray();
            for (Record record : records.values()) {
                apps.put(new JSONObject()
                        .put("id", record.identifier)
                        .put("name", record.name)
                        .put("category", record.categoryId)
                        .put("first_seen", record.firstSeen)
                        .put("last_seen", record.lastSeen)
                        .put("notifications", record.notifications)
                        .put("icon_file", record.iconFile)
                        .put("icon_type", record.iconType));
            }
            byte[] bytes = new JSONObject().put("schema", 1).put("apps", apps)
                    .toString(2).getBytes(StandardCharsets.UTF_8);
            File temp = new File(directory, CATALOG + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                output.write(bytes);
                output.flush();
            }
            File target = new File(directory, CATALOG);
            if (!temp.renameTo(target)) {
                try (FileOutputStream output = new FileOutputStream(target, false)) {
                    output.write(bytes);
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not save app catalog", error);
        }
    }

    private void maybeMigrateLocked() {
        if (externalDirectory(false) != null) migrateToExternalLocked();
    }

    private void migrateToExternalLocked() {
        File external = externalDirectory(true);
        if (external == null) return;
        File[] privateFiles = privateDirectory().listFiles();
        if (privateFiles != null) {
            for (File source : privateFiles) {
                if (!source.isFile() || CATALOG.equals(source.getName())) continue;
                File destination = new File(external, source.getName());
                if (!destination.exists()) copy(source, destination);
            }
        }
        saveCatalogTo(external);
    }

    @Nullable
    private File iconFile(Record record) {
        String name = safeFileName(record.iconFile);
        if (name.isEmpty()) return null;
        File external = externalDirectory(false);
        if (external != null) {
            File candidate = new File(external, name);
            if (candidate.isFile()) return candidate;
        }
        File candidate = new File(privateDirectory(), name);
        return candidate.isFile() ? candidate : null;
    }

    private File writableDirectory() {
        File external = externalDirectory(true);
        return external == null ? privateDirectory() : external;
    }

    private File privateDirectory() {
        return new File(context.getFilesDir(), "ANCS-icons");
    }

    @Nullable
    private File externalDirectory(boolean create) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        File directory = new File(Environment.getExternalStorageDirectory(), DIRECTORY);
        if (create && !directory.exists() && !directory.mkdirs()) return null;
        return directory.isDirectory() ? directory : null;
    }

    private static void copy(File source, File destination) {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        } catch (IOException error) {
            Log.w(TAG, "Could not migrate " + source, error);
        }
    }

    private static byte[] readBounded(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0L || file.length() > MAX_ICON_BYTES) {
            throw new IOException("Invalid icon file");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_ICON_BYTES) throw new IOException("Too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String normalizeIdentifier(@Nullable String value) {
        return PhoneNotificationFilter.normalizeAppKey(value == null ? "" : value);
    }

    private static String clean(@Nullable String value, int maximum) {
        String result = value == null ? "" : value.trim();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }

    private static String safeFileName(@Nullable String value) {
        String result = value == null ? "" : value.trim();
        return result.matches("[a-f0-9]{64}\\.(svg|png)") ? result : "";
    }

    private static String normalizeType(@Nullable String value) {
        return "svg".equals(value) || "png".equals(value) ? value : "";
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xFF));
        }
        return result.toString();
    }

    private static String simpleIconSlug(String identifier, String name) {
        String known = SIMPLE_ICONS.get(identifier);
        if (known != null) return known;
        String derived = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return derived.length() >= 2 && derived.length() <= 64 ? derived : "";
    }

    private static Map<String, String> createSimpleIcons() {
        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("net.whatsapp.whatsapp", "whatsapp");
        icons.put("ph.telegra.telegraph", "telegram");
        icons.put("org.telegram.telegram", "telegram");
        icons.put("org.whispersystems.signal", "signal");
        icons.put("com.viber", "viber");
        icons.put("com.vk.vkclient", "vk");
        icons.put("com.vk.vk", "vk");
        icons.put("com.burbn.instagram", "instagram");
        icons.put("com.facebook.facebook", "facebook");
        icons.put("com.facebook.messenger", "messenger");
        icons.put("com.google.gmail", "gmail");
        icons.put("com.microsoft.office.outlook", "microsoftoutlook");
        icons.put("ru.yandex.mobile.music", "yandexmusic");
        icons.put("com.yandex.mobile.music", "yandexmusic");
        icons.put("ru.yandex.mobile.maps", "yandexmaps");
        icons.put("com.yandex.mobile.maps", "yandexmaps");
        icons.put("com.google.maps", "googlemaps");
        icons.put("com.google.ios.youtube", "youtube");
        icons.put("com.zhiliaoapp.musically", "tiktok");
        icons.put("com.ss.iphone.ugc.aweme", "tiktok");
        icons.put("com.tinyspeck.chatlyio", "slack");
        icons.put("com.microsoft.skype.teams", "microsoftteams");
        icons.put("com.microsoft.teams", "microsoftteams");
        icons.put("com.hammerandchisel.discord", "discord");
        return Collections.unmodifiableMap(icons);
    }
}
