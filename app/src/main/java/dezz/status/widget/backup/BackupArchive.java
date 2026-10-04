/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Portable authenticated archive. Authentication completes before any file is decoded/activated. */
public final class BackupArchive {
    public static final int FORMAT = 1;
    private static final int MANIFEST_LIMIT = 16 * 1024 * 1024;
    private BackupArchive() {}

    public static JSONObject manifest(File snapshot, JSONObject metadata) throws Exception {
        JSONObject result = new JSONObject(metadata.toString());
        result.put("format", FORMAT);
        JSONArray entries = new JSONArray(); long total = 0;
        for (Map.Entry<String, File> entry : BackupFiles.inventory(snapshot).entrySet()) {
            File file = entry.getValue(); total += file.length();
            entries.put(new JSONObject().put("path", entry.getKey()).put("bytes", file.length())
                    .put("sha256", BackupFiles.sha256(file)));
        }
        return result.put("files", entries).put("totalBytes", total);
    }

    public static void write(File snapshot, JSONObject metadata, char[] password, OutputStream destination)
            throws Exception {
        if (password == null || password.length < 8) throw new IOException("Пароль должен содержать минимум 8 символов");
        JSONObject manifest = manifest(snapshot, metadata);
        byte[] manifestBytes=manifest.toString().getBytes(StandardCharsets.UTF_8);
        if(manifestBytes.length>MANIFEST_LIMIT)throw new IOException("Backup manifest is too large");
        try (ZipOutputStream zip = new ZipOutputStream(BackupCipher.encrypt(destination, password))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifestBytes); zip.closeEntry();
            JSONArray files = manifest.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) {
                JSONObject record = files.getJSONObject(index);
                File file = BackupFiles.child(snapshot, record.getString("path"));
                zip.putNextEntry(new ZipEntry("data/" + record.getString("path")));
                try (InputStream input = new FileInputStream(file)) {
                    long count = BackupFiles.copy(input, zip, BackupFiles.MAX_FILE_BYTES);
                    if (count != record.getLong("bytes")) throw new IOException("Snapshot changed during export");
                }
                zip.closeEntry();
                if (!BackupFiles.sha256(file).equals(record.getString("sha256")))
                    throw new IOException("Snapshot changed during export");
            }
        }
    }

    public static JSONObject read(InputStream source, char[] password, File emptyStage) throws Exception {
        BackupFiles.directory(emptyStage);
        if (Objects.requireNonNull(emptyStage.list()).length != 0) throw new IOException("Staging must be empty");
        File verifiedZip = new File(emptyStage, "authenticated.zip");
        File data = new File(emptyStage, "data");
        try {
            try (FileOutputStream out = new FileOutputStream(verifiedZip)) {
                BackupCipher.decrypt(source, out, password);
                out.getFD().sync();
            }
            JSONObject manifest;
            try (ZipFile zip = new ZipFile(verifiedZip)) {
                Set<String> zipNames = new HashSet<>();
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    BackupFiles.safePath(entry.getName());
                    if (!zipNames.add(entry.getName()) || entry.isDirectory()
                            || zipNames.size() > BackupFiles.MAX_FILES + 1) throw new IOException("Invalid/duplicate ZIP entry");
                }
                ZipEntry manifestEntry = zip.getEntry("manifest.json");
                if (manifestEntry == null) throw new IOException("Manifest missing");
                byte[] raw;
                try (InputStream stream = zip.getInputStream(manifestEntry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    BackupFiles.copy(stream, out, MANIFEST_LIMIT); raw = out.toByteArray();
                }
                String manifestText = new String(raw, StandardCharsets.UTF_8);
                BackupJson.validate(manifestText);
                manifest = new JSONObject(manifestText);
                if (manifest.getInt("format") != FORMAT) throw new IOException("Future archive format is not supported");
                JSONArray files = manifest.getJSONArray("files");
                if (files.length() > BackupFiles.MAX_FILES || files.length() + 1 != zipNames.size())
                    throw new IOException("Manifest/ZIP inventory mismatch");
                Set<String> paths = new HashSet<>(); long total = 0;
                for (int index = 0; index < files.length(); index++) {
                    JSONObject record = files.getJSONObject(index);
                    String path = BackupFiles.safePath(record.getString("path"));
                    long size = record.getLong("bytes");
                    if (!paths.add(path) || size < 0 || size > BackupFiles.MAX_FILE_BYTES
                            || (total += size) > BackupFiles.MAX_TOTAL_BYTES) throw new IOException("Invalid manifest entry");
                    ZipEntry entry = zip.getEntry("data/" + path);
                    if (entry == null || entry.getSize() != size) throw new IOException("Missing/truncated archive file");
                    File file = BackupFiles.child(data, path); BackupFiles.directory(file.getParentFile());
                    try (InputStream stream = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(file)) {
                        if (BackupFiles.copy(stream, out, size) != size) throw new IOException("Truncated archive file");
                        out.getFD().sync();
                    }
                    if (!BackupFiles.sha256(file).equals(record.getString("sha256")))
                        throw new IOException("Archive checksum mismatch");
                }
                if (manifest.getLong("totalBytes") != total) throw new IOException("Archive total mismatch");
            }
            if (!verifiedZip.delete()) throw new IOException("Cannot remove decrypted staging container");
            return manifest;
        } catch (Exception error) {
            BackupFiles.removeTree(emptyStage);
            throw error;
        }
    }

}
