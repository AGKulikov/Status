/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Passive native analytics/log hooks. No native body replacement or player command. */
public final class PatchMusicWave {
    static final String BRIDGE = "Lru/natro/music/NativeWaveDiagnostics;";
    public static void main(String[] args) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forApi(28));
        List<ClassDef> classes = new ArrayList<>();
        int direct = 0, grouped = 0, loggingCount = 0;
        for (ClassDef cls : dex.getClasses()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                MethodImplementation body = method.getImplementation();
                boolean telemetry=cls.getType().equals("Lnz0;") && method.getName().equals("a")
                        && method.getParameterTypes().toString().equals("[Ljava/lang/String;, Ljava/util/Map;]")
                        && method.getReturnType().equals("V") && (method.getAccessFlags() & 8)==0;
                boolean logging=cls.getType().equals("Lzzh;") && method.getName().equals("a")
                        && method.getParameterTypes().toString().equals("[I, Ljava/lang/String;, Ljava/lang/String;, Ljava/lang/Throwable;]")
                        && method.getReturnType().equals("V") && (method.getAccessFlags() & 8)!=0;
                boolean group=cls.getType().equals("Lnz0;") && method.getName().equals("b")
                        && method.getParameterTypes().toString().equals("[Llc0;, Ljava/lang/String;]")
                        && method.getReturnType().equals("V") && (method.getAccessFlags() & 8)==0;
                if (body != null && (telemetry || logging || group)) {
                    for(Instruction instruction:body.getInstructions())
                        if(instruction instanceof ReferenceInstruction
                            && ((ReferenceInstruction)instruction).getReference() instanceof MethodReference
                            && ((MethodReference)((ReferenceInstruction)instruction).getReference()).getDefiningClass().equals(BRIDGE))
                            throw new IllegalStateException("Already patched");
                    MutableMethodImplementation changed=new MutableMethodImplementation(body);
                    List<String> params=group ? Collections.singletonList("Ljava/lang/Object;")
                            : telemetry ? Arrays.asList("Ljava/lang/String;","Ljava/util/Map;")
                            : Arrays.asList("Ljava/lang/String;","Ljava/lang/String;","Ljava/lang/Throwable;");
                    if(body.getRegisterCount()<params.size()+1)throw new IllegalStateException("Invalid registers");
                    changed.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                            body.getRegisterCount()-(group?2:params.size()),params.size(),
                            new ImmutableMethodReference(BRIDGE,group?"onGroupedTelemetry":telemetry?"onTelemetry":"onNativeLog",params,"V")));
                    if(telemetry)direct++;else if(group)grouped++;else loggingCount++;
                    methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), changed));
                } else methods.add(method);
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if(direct!=1 || grouped!=1 || loggingCount!=1)throw new IllegalStateException("Native schema mismatch: "+direct+"/"+grouped+"/"+loggingCount);
        DexPool.writeTo(args[1], new ImmutableDexFile(Opcodes.forApi(28), classes));
        verifyNativeBodies(dex,DexFileFactory.loadDexFile(new File(args[1]),Opcodes.forApi(28)));
        System.out.println("Music diagnostic hooks: direct="+direct+" grouped="+grouped+" logging="+loggingCount);
    }
    /** Removing only the three inserted calls must restore the entire canonical native DEX. */
    static void verifyNativeBodies(DexFile original,DexFile patched)throws Exception {
        List<ClassDef> restored=new ArrayList<>();int removed=0;
        for(ClassDef cls:patched.getClasses()) {
            List<Method> methods=new ArrayList<>();
            for(Method method:cls.getMethods()) {
                MethodImplementation body=method.getImplementation();
                Instruction first=body==null?null:body.getInstructions().iterator().next();
                if(first instanceof ReferenceInstruction
                        && ((ReferenceInstruction)first).getReference() instanceof MethodReference
                        && ((MethodReference)((ReferenceInstruction)first).getReference()).getDefiningClass().equals(BRIDGE)) {
                    MutableMethodImplementation restoredBody=new MutableMethodImplementation(body);
                    restoredBody.removeInstruction(0);removed++;
                    methods.add(new ImmutableMethod(method.getDefiningClass(),method.getName(),method.getParameters(),
                            method.getReturnType(),method.getAccessFlags(),method.getAnnotations(),method.getHiddenApiRestrictions(),restoredBody));
                }else methods.add(method);
            }
            restored.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                    cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(removed!=3)throw new IllegalStateException("Unexpected hook count");
        Path before=Files.createTempFile("music-native-original-",".dex"),after=Files.createTempFile("music-native-restored-",".dex");
        try {
            DexPool.writeTo(before.toString(),original);
            DexPool.writeTo(after.toString(),new ImmutableDexFile(Opcodes.forApi(28),restored));
            if(!Arrays.equals(Files.readAllBytes(before),Files.readAllBytes(after)))
                throw new IllegalStateException("Native body/metadata changed beyond diagnostic entry calls");
        }finally{Files.deleteIfExists(before);Files.deleteIfExists(after);}
    }
}
