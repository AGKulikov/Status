"""Execute the actual archive, strict JSON and crash-recovery engine without Android side effects."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class FullBackupCoreTest(unittest.TestCase):
    def test_archive_validation_and_every_restore_interruption(self):
        jar = os.environ.get('NATRO_ANDROID_JSON_JAR') or os.environ.get('JSON_JAR')
        if not jar:
            self.skipTest('Set NATRO_ANDROID_JSON_JAR or JSON_JAR')
        harness = r'''
import dezz.status.widget.backup.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import org.json.*;
public class BackupReplay {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void put(File root,String path,String value)throws Exception{BackupFiles.atomicWrite(BackupFiles.child(root,path),value.getBytes(StandardCharsets.UTF_8));}
 static String read(File root,String path)throws Exception{return new String(BackupFiles.read(BackupFiles.child(root,path),10000),StandardCharsets.UTF_8);}
 static void rejected(byte[] bytes,char[] password,File stage)throws Exception {
  try {BackupArchive.read(new ByteArrayInputStream(bytes),password,stage);throw new AssertionError("invalid archive accepted");}
  catch(IOException|javax.crypto.AEADBadTagException expected){check(!stage.exists(),"failed plaintext removed");}
 }
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]), snapshot=new File(root,"snapshot");
  put(snapshot,"de_prefs/main.xml","<map><string name=\"name\">Пассажир</string></map>");
  byte[] photo=new byte[2300000];new Random(3).nextBytes(photo);
  BackupFiles.atomicWrite(BackupFiles.child(snapshot,"ce_files/user-icons/photo.png"),photo);
  char[] password="testing-long-password".toCharArray();
  ByteArrayOutputStream encoded=new ByteArrayOutputStream();
  BackupArchive.write(snapshot,new JSONObject().put("sourceVersion","3.0.2"),password,encoded);
  byte[] archive=encoded.toByteArray(); File stage=new File(root,"verified");
  JSONObject manifest=BackupArchive.read(new ByteArrayInputStream(archive),password,stage);
  check(manifest.getJSONArray("files").length()==2,"all files");
  check(read(new File(stage,"data"),"de_prefs/main.xml").contains("Пассажир"),"unicode round trip");
  check(BackupFiles.sha256(BackupFiles.child(stage,"data/ce_files/user-icons/photo.png"))
      .equals(BackupFiles.sha256(BackupFiles.child(snapshot,"ce_files/user-icons/photo.png"))),"multi-frame payload");
  rejected(archive,"incorrect password".toCharArray(),new File(root,"wrong-password"));
  byte[] corrupt=archive.clone();corrupt[corrupt.length/2]^=1;
  rejected(corrupt,password,new File(root,"tampered"));
  rejected(Arrays.copyOf(archive,archive.length-1),password,new File(root,"truncated"));
  byte[] trailing=Arrays.copyOf(archive,archive.length+1);
  rejected(trailing,password,new File(root,"trailing"));
  for(String path:new String[]{"../escape","/absolute","data/../../escape","a\\b","a//b","a/./b","a:b"}){
   try {BackupFiles.safePath(path);throw new AssertionError("unsafe path accepted");}catch(IOException expected){}
  }
  for(String json:new String[]{"{\"a\":1,\"a\":2}","[1,]","{\"x\":NaN}","{\"a\":01}","{} trailing","{\"a\":1,\"\\u0061\":2}"}) {
   try {BackupJson.validate(json);throw new AssertionError("invalid JSON accepted");}catch(IOException expected){}
  }
  BackupJson.validate("{\"future\":{\"long\":9223372036854775807,\"empty\":[],\"x\":null},\"unicode\":\"Ночь\"}");
  for(int fail=1;fail<=7;fail++) {
   File caseRoot=new File(root,"fault-"+fail),live=new File(caseRoot,"live"),replacement=new File(caseRoot,"replacement");
   put(live,"old.txt","old-only");put(live,"same.txt","old-value");
   put(replacement,"prefs/same.txt","new-value");put(replacement,"prefs/added.txt","new-only");
   Map<String,File> roots=new LinkedHashMap<>();roots.put("prefs",live);
   final int point=fail;
   BackupTransaction tx=new BackupTransaction(new File(caseRoot,"control"),roots,Collections.emptyMap(),step->{if(step==point)throw new IOException("power loss");});
   try{tx.apply(replacement,new JSONObject());}catch(IOException expected){}
   BackupTransaction recovered=new BackupTransaction(new File(caseRoot,"control"),roots,Collections.emptyMap(),null);
   recovered.recover();
   String value=read(live,"same.txt");
   if(value.equals("old-value")) check(new File(live,"old.txt").isFile()&&!new File(live,"added.txt").exists(),"whole old generation");
   else check(value.equals("new-value")&&!new File(live,"old.txt").exists()&&new File(live,"added.txt").isFile(),"whole new generation");
   if(value.equals("new-value")){recovered.rollback();check(read(live,"same.txt").equals("old-value"),"explicit rollback");}
   check(!recovered.hasUnfinishedRestore(),"journal completed");
  }
  File attack=new File(root,"target-attack");put(attack,"unknown/file","x");
  Map<String,File> roots=new LinkedHashMap<>();roots.put("prefs",new File(root,"attack-live"));
  put(roots.get("prefs"),"keep","unchanged");
  BackupTransaction tx=new BackupTransaction(new File(root,"attack-control"),roots,Collections.emptyMap(),null);
  try{tx.apply(attack,new JSONObject());throw new AssertionError("unknown namespace activated");}catch(IOException expected){}
  check(read(roots.get("prefs"),"keep").equals("unchanged"),"prevalidation preserves active files");
  System.out.println("Archive auth/size/path/JSON, multi-frame round-trip, 7 interruption points, rollback PASS");
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'BackupReplay.java').write_text(harness)
            classes=['BackupFiles','BackupJson','BackupCipher','BackupArchive','BackupTransaction']
            sources=[str(ROOT/'app/src/main/java/dezz/status/widget/backup'/f'{name}.java') for name in classes]
            build=subprocess.run(['java','com.sun.tools.javac.Main','-cp',jar,'-d',tmp,str(root/'BackupReplay.java'),*sources],capture_output=True,text=True)
            self.assertEqual(build.returncode,0,build.stderr)
            run=subprocess.run(['java','-Xmx96m','-cp',tmp+os.pathsep+jar,'BackupReplay',str(root/'data')],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
