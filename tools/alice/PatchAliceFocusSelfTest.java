/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;
/** Non-installable synthetic DEX fixtures: no proprietary method bodies. */
public final class PatchAliceFocusSelfTest {
 static ImmutableMethod method(String owner, String name, boolean error) {
  List<ImmutableMethodParameter> params=error?Collections.singletonList(new ImmutableMethodParameter("Lux/c;",null,null)):Collections.emptyList();
  List<Instruction> ins=new ArrayList<>();
  if(!error)ins.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,0,0,0,0,0,
     new ImmutableMethodReference("Landroid/media/AudioManager;","requestAudioFocus",Collections.emptyList(),"I")));
  ins.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
  return new ImmutableMethod(owner,name,params,"V",1,null,null,new ImmutableMethodImplementation(2,ins,null,null));
 }
 static ImmutableClassDef cls(String owner, String name, boolean error) {
  return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",null,null,null,null,
       Collections.singletonList(method(owner,name,error)));
 }
 public static void main(String[] args) throws Exception {
  Path root=Files.createTempDirectory("alice-dex-fixture");
  try {
   for(String kind:Arrays.asList("alicekit","quark")) {
    List<ClassDef> classes=new ArrayList<>();
    if(kind.equals("alicekit")) {
     classes.add(cls("Lcom/yandex/alice/audio/d;","f",false));
     classes.add(cls("Lcom/yandex/alice/io/listening/d;","b",true));
    } else classes.add(cls("Lcom/yandex/quark/audio/internal/d;","e",false));
    Path before=root.resolve(kind+".dex"), after=root.resolve(kind+"-after.dex");
    DexPool.writeTo(before.toString(),new ImmutableDexFile(Opcodes.forApi(28),classes));
    PatchAliceFocus.main(new String[]{before.toString(),after.toString(),kind});
    int hooks=0;
    for(ClassDef c:DexFileFactory.loadDexFile(after.toFile(),Opcodes.forApi(28)).getClasses())
     for(Method m:c.getMethods()) for(Instruction i:m.getImplementation().getInstructions())
      if(i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof MethodReference
        && ((MethodReference)((ReferenceInstruction)i).getReference()).getDefiningClass().equals(PatchAliceFocus.BRIDGE))hooks++;
    if(hooks!=(kind.equals("alicekit")?2:1))throw new AssertionError("Hook count");
    try { PatchAliceFocus.main(new String[]{after.toString(),root.resolve("duplicate.dex").toString(),kind});
      throw new AssertionError("Duplicate hook accepted"); } catch(IllegalStateException expected) { }
    try { PatchAliceFocus.main(new String[]{before.toString(),root.resolve("wrong.dex").toString(),kind.equals("alicekit")?"quark":"alicekit"});
      throw new AssertionError("Wrong lane accepted"); } catch(IllegalStateException expected) { }
   }
  } finally {
   try(java.util.stream.Stream<Path> files=Files.walk(root)) {files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}
  }
 }
}
