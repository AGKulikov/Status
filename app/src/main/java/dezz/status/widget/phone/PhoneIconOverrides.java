/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** User-owned mappings, separate from the automatic download cache. All files are backup data. */
public final class PhoneIconOverrides {
    public static final String DIRECTORY = "phone-icon-overrides";
    public static final class Entry {
        public final String identifier, name, file;
        Entry(String identifier, String name, String file) {
            this.identifier = identifier; this.name = name; this.file = file;
        }
    }
    private final File directory;
    private Map<String, Entry> entries;

    public PhoneIconOverrides(File directory) { this.directory = directory; }

    public synchronized Map<String, Entry> entries() throws IOException {
        load();
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public synchronized File icon(String identifier) throws IOException {
        load();
        Entry entry = entries.get(identifier);
        if (entry == null || entry.file.isEmpty()) return null;
        File file = new File(directory, entry.file);
        return file.isFile() ? file : null;
    }

    /** png is validated/normalized by the Android importer before this transaction. */
    public synchronized void put(String identifier, String name, byte[] png) throws IOException {
        load();
        if (!identifier.matches("[a-z0-9][a-z0-9._-]{0,255}") || name.trim().isEmpty()
                || name.length() > 256) throw new IOException("Укажите название и корректный ID приложения");
        if (png == null || png.length == 0 || png.length > 1_500_000)
            throw new IOException("Некорректный размер иконки");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Не удалось создать каталог иконок");
        String filename = UUID.randomUUID() + ".png";
        File image = new File(directory, filename);
        try (FileOutputStream output = new FileOutputStream(image)) {
            output.write(png); output.getFD().sync();
        }
        if (!java.util.Arrays.equals(png, Files.readAllBytes(image.toPath())))
            throw new IOException("Проверка сохранённой иконки не пройдена");
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        next.put(identifier, new Entry(identifier, name.trim(), filename));
        try { save(next); }
        catch (IOException failure) { image.delete(); throw failure; }
        // Keep old image versions until explicit garbage collection/backup policy is implemented.
    }

    public synchronized void reset(String identifier) throws IOException {
        load();
        Entry old = entries.get(identifier);
        if (old == null) return;
        Map<String, Entry> next = new LinkedHashMap<>(entries);
        // Keep the manually added application visible even when it has no automatic icon.
        next.put(identifier, new Entry(identifier, old.name, ""));
        save(next);
    }

    private void load() throws IOException {
        if (entries != null) return;
        File file = new File(directory, "catalog.json");
        if (!file.exists()) { entries = new LinkedHashMap<>(); return; }
        if (file.length() > 2_000_000) throw new IOException("Каталог иконок слишком большой");
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (root.getInt("schema") != 1) throw new IOException("Неизвестная версия каталога иконок");
            JSONArray array = root.getJSONArray("apps");
            Map<String, Entry> result = new LinkedHashMap<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.getString("id"), name = item.getString("name"), image = item.getString("file");
                if (!id.matches("[a-z0-9][a-z0-9._-]{0,255}") || name.trim().isEmpty() || name.length() > 256
                        || (!image.isEmpty() && !image.matches("[a-f0-9-]{36}\\.png"))
                        || result.containsKey(id)) throw new IOException("Повреждён каталог иконок");
                result.put(id, new Entry(id, name, image));
            }
            entries = result;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Не удалось прочитать каталог иконок", error); }
    }

    private void save(Map<String, Entry> next) throws IOException {
        File temp = new File(directory, "catalog.next");
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : next.values()) array.put(new JSONObject().put("id", entry.identifier)
                    .put("name", entry.name).put("file", entry.file));
            byte[] bytes = new JSONObject().put("schema", 1).put("apps", array).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 2_000_000) throw new IOException("Каталог иконок слишком большой");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes); output.getFD().sync();
            }
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(temp.toPath())))
                throw new IOException("Проверка каталога не пройдена");
            Files.move(temp.toPath(), new File(directory, "catalog.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            entries = next;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Не удалось сохранить каталог иконок", error); }
        finally { temp.delete(); }
    }
}
