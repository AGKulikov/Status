"""Execute the production history differ on host JVM; no Android or real user data."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'navigator-mod/src/main/java/ru/natro/navigation/RouteHistoryDiff.java'


class RouteHistoryDiagnosticTest(unittest.TestCase):
    def test_version_pinned_account_binding(self):
        prefix = 'ru.yandex.yandexmaps.multiplatform.datasync.wrapper.'
        files = {
            'com.yandex.datasync.Database': 'public interface Database { String getDatabaseId(); boolean isValid(); }',
            'ru.yandex.yandexmaps.app.di.components.a0': 'public class a0 { private final Provider v2 = new Provider(); public static class Provider { public Object get() { return new ru.yandex.yandexmaps.datasync.c(); } } }',
            'ru.yandex.yandexmaps.datasync.c': 'public class c { public ' + prefix + 'api.s g() { return new ' + prefix + 'api.s(); } }',
            prefix + 'api.s': 'public class s { private final ' + prefix + 'internal.m1 holder = new ' + prefix + 'internal.m1(); }',
            prefix + 'internal.m1': 'public class m1 { private final java.util.Map<String,c1> g = ru.natro.navigation.BindingReplay.bindings; }',
            prefix + 'internal.c1': 'public class c1 { public Object account = new Object(); public String id = ".ext.maps_common@ymapspointshistory1"; private final Flow f; public c1(com.yandex.datasync.Database db) { f = new Flow(db); } public Object o() { return account; } public String q() { return id; } public static class Flow { final f2 value; Flow(com.yandex.datasync.Database db) { value = new f2(db); } public Object getValue() { return value; } } }',
            prefix + 'internal.f2': 'public class f2 { private final com.yandex.datasync.Database db; public f2(com.yandex.datasync.Database value) { db = value; } }',
            'ru.natro.navigation.BindingReplay': r'''public class BindingReplay {
  public static final java.util.Map<String,ru.yandex.yandexmaps.multiplatform.datasync.wrapper.internal.c1> bindings = new java.util.LinkedHashMap<>();
  public static class App { public Object c() { return new ru.yandex.yandexmaps.app.di.components.a0(); } }
  public static class Db implements com.yandex.datasync.Database {
    public String getDatabaseId() { return ".ext.maps_common@ymapspointshistory1"; }
    public boolean isValid() { return true; }
  }
  static void check(boolean b) { if (!b) throw new AssertionError(); }
  public static void main(String[] ignored) throws Exception {
    App app = new App(); Db database = new Db();
    check(RouteHistoryAccess.resolve(app) == null);
    ru.yandex.yandexmaps.multiplatform.datasync.wrapper.internal.c1 binding = new ru.yandex.yandexmaps.multiplatform.datasync.wrapper.internal.c1(database);
    bindings.put("one", binding); check(RouteHistoryAccess.resolve(app) == database);
    binding.account = null; check(RouteHistoryAccess.resolve(app) == null);
    binding.account = new Object(); binding.id = "unrelated"; check(RouteHistoryAccess.resolve(app) == null);
    binding.id = database.getDatabaseId();
    bindings.put("two", new ru.yandex.yandexmaps.multiplatform.datasync.wrapper.internal.c1(new Db()));
    try { RouteHistoryAccess.resolve(app); throw new AssertionError(); }
    catch (IllegalStateException expected) {}
    System.out.println("production binding replay passed");
  }
}''',
        }
        with tempfile.TemporaryDirectory() as directory:
            sources = []
            for name, body in files.items():
                path = pathlib.Path(directory, name.replace('.', '/') + '.java')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body)
                sources.append(str(path))
            for name in ['RouteHistoryAccess.java', 'RouteHistoryDiff.java', 'ReflectMethods.java']:
                sources.append(str(SOURCE.parent / name))
            compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
            subprocess.run(compiler + ['-d', directory] + sources, check=True)
            subprocess.run(['java', '-cp', directory, 'ru.natro.navigation.BindingReplay'], check=True)

    def test_production_diff_scenarios(self):
        harness = r'''
package ru.natro.navigation;
import java.util.*;
public class HistoryReplay {
  static RouteHistoryDiff.Item item(String id, long time) {
    return new RouteHistoryDiff.Item(id, "Fixture", 55, 37, time);
  }
  static void expect(boolean value) { if (!value) throw new AssertionError(); }
  static void kind(List<RouteHistoryDiff.Change> events, String expected) {
    expect(events.size() == 1 && events.get(0).kind.equals(expected));
  }
  public static void main(String[] ignored) {
    RouteHistoryDiff diff = new RouteHistoryDiff();
    kind(diff.accept(Arrays.asList(item("a", 100))), "INITIAL");
    expect(diff.accept(Arrays.asList(item("a", 100))).isEmpty());
    kind(diff.accept(Arrays.asList(item("a", 100), item("b", 101))), "ADDED");
    kind(diff.accept(Arrays.asList(item("a", 100), item("b", 102))), "UPDATED");
    // A backwards source clock is still an update, never silently lost.
    kind(diff.accept(Arrays.asList(item("a", 100), item("b", 99))), "UPDATED");
    try { diff.accept(Arrays.asList(item("a", 1), item("a", 2))); throw new AssertionError(); }
    catch (IllegalArgumentException expected) {}
    // Rejected snapshot must not poison baseline or imply deletions.
    expect(diff.accept(Arrays.asList(item("a", 100), item("b", 99))).isEmpty());
    kind(diff.accept(Arrays.asList(item("b", 99))), "REMOVED_FROM_HISTORY");
    diff.reset();
    kind(diff.accept(Arrays.asList(item("b", 99))), "INITIAL");
    diff.reset();
    expect(diff.accept(Collections.emptyList()).isEmpty());
    kind(diff.accept(Arrays.asList(item("b", 99))), "ADDED");
    for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 91}) {
      try { new RouteHistoryDiff.Item("x", "x", coordinate, 0, 0); throw new AssertionError(); }
      catch (IllegalArgumentException expected) {}
    }
    List<RouteHistoryDiff.Item> oversized = new ArrayList<>();
    for (int i = 0; i <= RouteHistoryDiff.MAX_RECORDS; i++) oversized.add(item("r"+i, i));
    try { diff.accept(oversized); throw new AssertionError(); }
    catch (IllegalArgumentException expected) {}
    expect(diff.accept(Arrays.asList(item("b", 99))).isEmpty());
    System.out.println("production diff replay passed");
  }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            (path / 'HistoryReplay.java').write_text(harness)
            compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
            subprocess.run(compiler + ['-d', directory, str(SOURCE), str(path / 'HistoryReplay.java')], check=True)
            subprocess.run(['java', '-cp', directory, 'ru.natro.navigation.HistoryReplay'], check=True)


if __name__ == '__main__':
    unittest.main()
