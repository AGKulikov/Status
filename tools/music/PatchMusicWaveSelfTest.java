import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;
public final class PatchMusicWaveSelfTest {
 static ClassDef cls(String owner,boolean logger){
  List<ImmutableMethodParameter> args=new ArrayList<>();
  for(String type:logger?Arrays.asList("I","Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/Throwable;")
       :Arrays.asList("Ljava/lang/String;","Ljava/util/Map;"))args.add(new ImmutableMethodParameter(type,null,null));
  Method m=new ImmutableMethod(owner,"a",args,"V",logger?9:1,null,null,
      new ImmutableMethodImplementation(24,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),null,null));
  List<Method> methods=new ArrayList<>();methods.add(m);
  if(!logger)methods.add(new ImmutableMethod(owner,"b",Arrays.asList(
      new ImmutableMethodParameter("Llc0;",null,null),new ImmutableMethodParameter("Ljava/lang/String;",null,null)),"V",1,null,null,
      new ImmutableMethodImplementation(24,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),null,null)));
  return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",null,null,null,null,methods);
 }
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory("music-hook-test");
  try {
   Path before=dir.resolve("before.dex"),after=dir.resolve("after.dex");
   DexPool.writeTo(before.toString(),new ImmutableDexFile(Opcodes.forApi(28),Arrays.asList(cls("Lnz0;",false),cls("Lzzh;",true))));
   PatchMusicWave.main(new String[]{before.toString(),after.toString()});
   int count=0;
   for(ClassDef c:DexFileFactory.loadDexFile(after.toFile(),Opcodes.forApi(28)).getClasses())
    for(Method m:c.getMethods()){
     List<Instruction> ins=new ArrayList<>();m.getImplementation().getInstructions().forEach(ins::add);
     if(ins.size()!=2 || ins.get(1).getOpcode()!=Opcode.RETURN_VOID)throw new AssertionError("Native body changed");
     RegisterRangeInstruction hook=(RegisterRangeInstruction)ins.get(0);
     boolean grouped=m.getName().equals("b");
     int n=grouped?1:c.getType().equals("Lnz0;")?2:3;
     if(hook.getStartRegister()!=24-(grouped?2:n) || hook.getRegisterCount()!=n)throw new AssertionError("Argument register mapping");
     count++;
    }
   if(count!=3)throw new AssertionError("Hook coverage");
   try{PatchMusicWave.main(new String[]{after.toString(),dir.resolve("double.dex").toString()});throw new AssertionError("Double patch accepted");}
   catch(IllegalStateException expected){}
   DexPool.writeTo(before.toString(),new ImmutableDexFile(Opcodes.forApi(28),Collections.singletonList(cls("Lnz0;",false))));
   try{PatchMusicWave.main(new String[]{before.toString(),dir.resolve("missing.dex").toString()});throw new AssertionError("Missing schema accepted");}
   catch(IllegalStateException expected){}
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}}
 }
}
