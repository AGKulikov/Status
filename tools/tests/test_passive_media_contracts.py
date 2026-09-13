"""Run source-boundary Java tests with minimal assertion adapters, not an Android/Gradle build."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PassiveMediaContractsTest(unittest.TestCase):
    def test_java_source_boundaries(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            sources = {
                "org/junit/Test.java": "package org.junit;@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Test {}",
                "org/junit/Assert.java": """package org.junit;public class Assert {
public static void assertTrue(boolean v){if(!v)throw new AssertionError();}
public static void assertFalse(boolean v){assertTrue(!v);}
public static void assertFalse(String m,boolean v){if(v)throw new AssertionError(m);}}""",
                "ContractsReplay.java": """public class ContractsReplay {
public static void main(String[] args)throws Exception{int n=0;for(String name:args){
Class<?> c=Class.forName(name);Object instance=c.getConstructor().newInstance();
for(java.lang.reflect.Method m:c.getDeclaredMethods())if(m.isAnnotationPresent(org.junit.Test.class)){
try{m.invoke(instance);n++;}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(name+\".\"+m.getName(),e.getCause());}}}
if(n!=7)throw new AssertionError(n);}}"""
            }
            files = []
            for name, source in sources.items():
                file = folder / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(source)
                files.append(str(file))
            names = ["dezz.status.widget.launcher.PassiveMediaKeyContractTest", "dezz.status.widget.DiagnosticsContractTest"]
            files += [str(ROOT / "app/src/test/java" / (name.replace(".", "/") + ".java")) for name in names]
            result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(folder), *files], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(folder), "ContractsReplay", *names], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
