package dezz.status.widget.transfer;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class NatroSharedFolderReplay {
    interface Checked { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void denied(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected refusal");
    }
    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args[0]), root = base.resolve("Download/Natro");
        NatroSharedFolder store = new NatroSharedFolder(root.toFile());
        check(store.list().isEmpty());
        byte[] data = new byte[131079]; new Random(17).nextBytes(data);
        Path original = root.resolve("Дамп <script> & файл.zip"); Files.write(original, data);
        Files.createDirectory(root.resolve("subfolder"));
        Files.write(root.resolve(".unfinished.part"), data);
        Files.write(root.resolve("unfinished.part"), data);
        Path outside = base.resolve("secret"); Files.write(outside, data);
        Files.createSymbolicLink(root.resolve("escape"), outside);
        List<NatroSharedFolder.Item> items = store.list(); check(items.size() == 1);
        NatroSharedFolder.Item item = items.get(0);
        check(item.name.equals(original.getFileName().toString()));
        check(item.id.equals(store.list().get(0).id));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NatroSharedFolder.Download download = store.open(item.id)) { download.writeTo(bytes); }
        check(Arrays.equals(data, bytes.toByteArray()));
        denied(() -> store.open("../secret")); denied(() -> store.open(outside.toString()));
        denied(() -> store.open("%2e%2e%2fsecret"));
        Files.delete(original); Files.createSymbolicLink(original, outside);
        denied(() -> store.open(item.id)); Files.delete(original);
        check(store.list().isEmpty()); denied(() -> store.open(item.id));
        Files.write(original, data);
        File exported = store.exportDump(original.toFile());
        check(Arrays.equals(data, Files.readAllBytes(exported.toPath())));
        File second = store.exportDump(original.toFile()); check(!second.equals(exported));
        check(Files.exists(original));
        Thread.currentThread().interrupt(); denied(() -> store.exportDump(original.toFile())); Thread.interrupted();
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            check(entries.noneMatch(path -> path.getFileName().toString().startsWith(".Natro-dump-")));
        }
        Path linked = base.resolve("linked"); Files.createSymbolicLink(linked, root);
        denied(() -> new NatroSharedFolder(linked.toFile()).list());
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("huge").toFile(), "rw")) { file.setLength(NatroSharedFolder.MAX_DOWNLOAD + 1); }
        for (NatroSharedFolder.Item entry : store.list()) if (entry.name.equals("huge")) denied(() -> store.open(entry.id));
        for (int i=0; i<1001; i++) Files.createFile(root.resolve("entry-"+i));
        denied(store::list);
        check(Arrays.equals(data, Files.readAllBytes(outside)));
        System.out.println("PASS: binary, Unicode, opaque IDs, traversal, links, stale IDs, export, cancellation, limits");
    }
}
