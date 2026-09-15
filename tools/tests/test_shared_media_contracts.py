"""Local source/Java policy gates; deliberately not an Android build or a hardware test."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TESTS = [
    "dezz.status.widget.Ha1161CarPlayQualityContractTest",
    "dezz.status.widget.Ha1170TextAlignmentAndArtworkContractTest",
    "dezz.status.widget.launcher.MediaAutoResumeContractTest",
    "dezz.status.widget.Ha1217StatusMediaPlaybackContractTest",
    "dezz.status.widget.StatusMediaBrickLayoutContractTest",
    "dezz.status.widget.settings.SettingsBackNavigationContractTest",
    "dezz.status.widget.launcher.MediaSourceStabilityContractTest",
    "dezz.status.widget.launcher.media.MediaPanelInteractionContractTest",
    "dezz.status.widget.Ha1217HwgpsMediaDriverContractTest",
    "dezz.status.widget.Ha1181ExpandedRecorderAndPhonePolicyContractTest",
    "dezz.status.widget.Ha1140NavigatorCrashRegressionTest",
    "dezz.status.widget.launcher.MediaTimelineTest",
    "dezz.status.widget.launcher.MediaStateFreshnessTest",
    "dezz.status.widget.launcher.media.MediaVolumeMathTest",
]


class SharedMediaContracts(unittest.TestCase):
    def test_existing_java_policies_and_display_boundaries(self):
        with tempfile.TemporaryDirectory(prefix="natro-shared-media-") as tmp:
            folder = Path(tmp)
            sources = {
                "org/junit/Test.java": "package org.junit; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Test {}",
                "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
                "org/junit/Assert.java": """package org.junit; public class Assert {
 public static void assertTrue(boolean b){assertTrue("Expected true",b);}
 public static void assertTrue(String m,boolean b){if(!b)throw new AssertionError(m);}
 public static void assertFalse(boolean b){assertTrue("Expected false",!b);}
 public static void assertFalse(String m,boolean b){assertTrue(m,!b);}
 public static void assertEquals(Object a,Object b){assertEquals("",a,b);}
 public static void assertEquals(String m,Object a,Object b){if(!java.util.Objects.equals(a,b))throw new AssertionError(m+": "+a+" != "+b);}
 public static void assertEquals(long a,long b){assertTrue(a+" != "+b,a==b);}
 }""",
                "SharedMediaReplay.java": """public class SharedMediaReplay {
 public static void main(String[] args)throws Exception {
  int passed=0,failed=0;
  for(String name:args) {
   Class<?> type=Class.forName(name); Object test=type.getConstructor().newInstance();
   for(java.lang.reflect.Method m:type.getMethods()) if(m.isAnnotationPresent(org.junit.Test.class)) {
    try {m.invoke(test);passed++;} catch(java.lang.reflect.InvocationTargetException e) {
     failed++; System.err.println(name+"."+m.getName()); e.getCause().printStackTrace();
    }
   }
  }
  System.out.println("Java policy/source cases: "+passed+" passed, "+failed+" failed");
  if(failed>0||passed==0)System.exit(1);
 }
}"""
            }
            paths = []
            for name, source in sources.items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths += [str(ROOT / "app/src/test/java" / (name.replace(".", "/") + ".java")) for name in TESTS]
            paths.append(str(ROOT / "app/src/test/java/dezz/status/widget/ReleaseIdentityContract.java"))
            paths += [str(ROOT / "app/src/main/java/dezz/status/widget/launcher" / name) for name in
                      ("MediaTimeline.java", "MediaStateFreshness.java", "media/MediaVolumeMath.java")]
            compiled = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(folder), *paths], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", str(folder), "SharedMediaReplay", *TESTS], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
