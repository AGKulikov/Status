/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Recoverable file-set transaction. The caller must keep application writers stopped throughout. */
public final class BackupTransaction {
    public interface FaultProbe { void afterStep(int step) throws IOException; }
    public interface Participant {
        void apply(JSONObject metadata) throws Exception;
        void finish(JSONObject metadata, boolean committed) throws Exception;
    }
    private Participant participant;
    public BackupTransaction withParticipant(Participant value) {participant=value;return this;}
    private final File journalDirectory;
    private final Map<String, File> roots;
    private final Map<String, Set<String>> exclusions;
    private final FaultProbe probe;
    private int step;

    public BackupTransaction(File journalDirectory, Map<String, File> roots,
                             Map<String, Set<String>> exclusions, FaultProbe probe) {
        this.journalDirectory = journalDirectory;
        this.roots = new LinkedHashMap<>(roots);
        this.exclusions = exclusions;
        this.probe = probe == null ? ignored -> {} : probe;
    }

    public Map<String, File> currentFiles() throws IOException {
        Map<String, File> files = new LinkedHashMap<>();
        for (Map.Entry<String, File> root : roots.entrySet()) {
            BackupFiles.safePath(root.getKey());
            walk(root.getKey(), root.getValue(), "", files);
        }
        return files;
    }

    private void walk(String owner, File file, String relative, Map<String, File> files) throws IOException {
        if (exclusions.getOrDefault(owner, Collections.emptySet()).contains(relative)) return;
        if (!file.exists()) return;
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) throw new IOException("Symlink in application storage");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect application storage");
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) walk(owner, child, relative.isEmpty() ? child.getName()
                    : relative + "/" + child.getName(), files);
        } else if (file.isFile()) {
            if (relative.isEmpty()) throw new IOException("Storage root must be a directory");
            files.put(BackupFiles.safePath(owner + "/" + relative), file);
        } else throw new IOException("Unknown application storage entry");
    }

    private File target(String path) throws IOException {
        BackupFiles.safePath(path); int slash = path.indexOf('/');
        if (slash < 1) throw new IOException("Storage namespace missing");
        String owner = path.substring(0, slash), relative = path.substring(slash + 1);
        File root = roots.get(owner);
        if (root == null) throw new IOException("Unknown storage namespace: " + owner);
        for (String excluded : exclusions.getOrDefault(owner, Collections.emptySet()))
            if (relative.equals(excluded) || relative.startsWith(excluded + "/"))
                throw new IOException("Archive targets maintenance metadata");
        return BackupFiles.child(root, relative);
    }

    public void snapshot(File emptyDestination) throws IOException {
        BackupFiles.directory(emptyDestination);
        if (Objects.requireNonNull(emptyDestination.list()).length != 0) throw new IOException("Snapshot must be empty");
        long total = 0;
        Map<String, File> source = currentFiles();
        if (source.size() > BackupFiles.MAX_FILES) throw new IOException("Too many application files");
        for (Map.Entry<String, File> entry : source.entrySet()) {
            if ((total += entry.getValue().length()) > BackupFiles.MAX_TOTAL_BYTES) throw new IOException("Snapshot too large");
            String before = BackupFiles.sha256(entry.getValue());
            File destination = BackupFiles.child(emptyDestination, entry.getKey());
            BackupFiles.atomicCopy(entry.getValue(), destination);
            if (!before.equals(BackupFiles.sha256(destination)) || !before.equals(BackupFiles.sha256(entry.getValue())))
                throw new IOException("Application writer was not stopped");
        }
        if (!source.keySet().equals(currentFiles().keySet())) throw new IOException("Storage changed during snapshot");
    }

    public boolean hasUnfinishedRestore() { return new File(journalDirectory, "journal.json").isFile(); }
    public boolean hasRollbackPoint() { return new File(journalDirectory, "last-rollback.json").isFile(); }

    public void copyRollbackPoint(File destination) throws Exception {
        String id=rollbackId(json(new File(journalDirectory,"last-rollback.json")).getString("id"));
        File source=new File(journalDirectory,"rollback-"+id);
        JSONObject expected=json(new File(journalDirectory,"rollback-"+id+".json"));
        JSONObject actual=BackupArchive.manifest(source,new JSONObject());
        if(!actual.toString().equals(expected.toString()))throw new IOException("Rollback point is corrupt");
        for(Map.Entry<String,File> file:BackupFiles.inventory(source).entrySet())
            BackupFiles.atomicCopy(file.getValue(),BackupFiles.child(destination,file.getKey()));
    }

    public void apply(File checkedSnapshot, JSONObject metadata) throws Exception {
        if (hasUnfinishedRestore()) throw new IOException("Recover the previous transaction first");
        BackupFiles.directory(journalDirectory);
        String rollbackId = UUID.randomUUID().toString();
        File rollback = new File(journalDirectory, "rollback-" + rollbackId);
        snapshot(rollback);
        JSONObject old = BackupArchive.manifest(rollback, new JSONObject());
        BackupFiles.atomicWrite(new File(journalDirectory, "rollback-" + rollbackId + ".json"), bytes(old));
        BackupFiles.atomicWrite(new File(journalDirectory, "last-rollback.json"),
                bytes(new JSONObject().put("id", rollbackId)));
        Map<String, File> replacement = BackupFiles.inventory(checkedSnapshot);
        for (String path : replacement.keySet()) target(path);
        LinkedHashSet<String> affected = new LinkedHashSet<>(currentFiles().keySet());
        affected.addAll(replacement.keySet());
        JSONObject journal = new JSONObject().put("schema", 1).put("phase", "PREPARED")
                .put("affected", new JSONArray(affected)).put("replacement", metadata).put("rollbackId", rollbackId);
        writeJournal(journal); probe.afterStep(++step);
        journal.put("phase", "APPLYING"); writeJournal(journal); probe.afterStep(++step);
        for (String path : affected) {
            File source = replacement.get(path), destination = target(path);
            if (source == null) BackupFiles.delete(destination);
            else BackupFiles.atomicCopy(source, destination, ".natro-restore-" + rollbackId);
            probe.afterStep(++step);
        }
        Map<String, File> written = currentFiles();
        if (!written.keySet().equals(replacement.keySet())) throw new IOException("Restore inventory read-back differs");
        for (String path : replacement.keySet()) if (!BackupFiles.sha256(replacement.get(path)).equals(BackupFiles.sha256(target(path))))
            throw new IOException("Restore read-back differs");
        if(participant!=null)participant.apply(metadata);
        journal.put("phase", "COMMITTED"); writeJournal(journal); probe.afterStep(++step);
        if(participant!=null)participant.finish(metadata,true);
        BackupFiles.delete(new File(journalDirectory, "journal.json"));
    }

    public void recover() throws Exception {
        File journalFile = new File(journalDirectory, "journal.json");
        if (!journalFile.isFile()) return;
        JSONObject journal = json(journalFile);
        if (journal.getInt("schema") != 1) throw new IOException("Unknown recovery journal");
        if (journal.getString("phase").equals("COMMITTED")) {
            if(participant!=null)participant.finish(journal.optJSONObject("replacement"),true);
            BackupFiles.delete(journalFile); return;
        }
        restoreRollback(journal.getJSONArray("affected"), journal.getString("rollbackId"));
        if(participant!=null)participant.finish(journal.optJSONObject("replacement"),false);
        BackupFiles.delete(journalFile);
    }

    public void rollback() throws Exception {
        if (hasUnfinishedRestore()) { recover(); return; }
        String rollbackId = rollbackId(json(new File(journalDirectory, "last-rollback.json")).getString("id"));
        JSONObject old = json(new File(journalDirectory, "rollback-" + rollbackId + ".json"));
        LinkedHashSet<String> affected = new LinkedHashSet<>(currentFiles().keySet());
        JSONArray entries = old.getJSONArray("files");
        for (int index = 0; index < entries.length(); index++) affected.add(entries.getJSONObject(index).getString("path"));
        JSONObject journal = new JSONObject().put("schema", 1).put("phase", "ROLLBACK")
                .put("affected", new JSONArray(affected)).put("rollbackId", rollbackId);
        writeJournal(journal); restoreRollback(journal.getJSONArray("affected"), journal.getString("rollbackId"));
        BackupFiles.delete(new File(journalDirectory, "journal.json"));
    }

    private static String rollbackId(String value) throws IOException {
        if (!value.matches("[a-f0-9-]{36}")) throw new IOException("Invalid rollback generation");
        return value;
    }

    private void restoreRollback(JSONArray affected, String rawId) throws Exception {
        String id = rollbackId(rawId);
        JSONObject manifest = json(new File(journalDirectory, "rollback-" + id + ".json"));
        File rollback = new File(journalDirectory, "rollback-" + id);
        Map<String, File> originals = BackupFiles.inventory(rollback);
        JSONArray records = manifest.getJSONArray("files");
        if (records.length() != originals.size()) throw new IOException("Rollback point is incomplete");
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            File file = originals.get(record.getString("path"));
            if (file == null || file.length() != record.getLong("bytes")
                    || !BackupFiles.sha256(file).equals(record.getString("sha256")))
                throw new IOException("Rollback point is corrupt");
        }
        for (int index = 0; index < affected.length(); index++) {
            String path = affected.getString(index); File original = originals.get(path), destination = target(path);
            BackupFiles.delete(new File(destination.getParentFile(), destination.getName() + ".natro-restore-" + id));
            if (original == null) BackupFiles.delete(destination);
            else BackupFiles.atomicCopy(original, destination, ".natro-restore-" + id);
            probe.afterStep(++step);
        }
        for (String path : originals.keySet()) if (!BackupFiles.sha256(originals.get(path)).equals(BackupFiles.sha256(target(path))))
            throw new IOException("Rollback read-back differs");
    }

    private void writeJournal(JSONObject journal) throws IOException {
        BackupFiles.atomicWrite(new File(journalDirectory, "journal.json"), bytes(journal));
    }
    private static byte[] bytes(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private static JSONObject json(File file) throws Exception {
        String text = new String(BackupFiles.read(file, 16 * 1024 * 1024), StandardCharsets.UTF_8);
        BackupJson.validate(text); return new JSONObject(text);
    }
}
