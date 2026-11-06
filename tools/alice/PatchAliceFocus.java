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
import java.util.*;

/** Minimal pre-focus and error hooks. No activation-source list and no native body replacement. */
public final class PatchAliceFocus {
    static final String BRIDGE = "Lru/natro/navigation/NativeAliceBridge;";
    public static void main(String[] args) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forApi(28));
        List<ClassDef> classes = new ArrayList<>();
        int focus = 0, errors = 0;
        for (ClassDef cls : dex.getClasses()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                MethodImplementation body = method.getImplementation();
                boolean audio = (cls.getType().equals("Lcom/yandex/alice/audio/d;") && method.getName().equals("f"))
                        || (cls.getType().equals("Lcom/yandex/quark/audio/internal/d;") && method.getName().equals("e"));
                boolean error = cls.getType().equals("Lcom/yandex/alice/io/listening/d;")
                        && method.getName().equals("b") && method.getParameterTypes().toString().equals("[Lux/c;]");
                if (body != null && (audio || error)) {
                    MutableMethodImplementation changed = new MutableMethodImplementation(body);
                    int index = 0, hits = 0;
                    for (Instruction instruction : body.getInstructions()) {
                        if (instruction instanceof ReferenceInstruction) {
                            Object ref = ((ReferenceInstruction) instruction).getReference();
                            if (ref instanceof MethodReference) {
                                MethodReference target = (MethodReference) ref;
                                if (target.getDefiningClass().equals(BRIDGE)) throw new IllegalStateException("Already patched");
                                if (audio && target.getDefiningClass().equals("Landroid/media/AudioManager;")
                                        && target.getName().equals("requestAudioFocus")) {
                                    changed.addInstruction(index + hits, new BuilderInstruction35c(Opcode.INVOKE_STATIC,
                                            0, 0, 0, 0, 0, 0, new ImmutableMethodReference(BRIDGE,
                                            "beforeAudioFocus", Collections.emptyList(), "V")));
                                    hits++; focus++;
                                }
                            }
                        }
                        index++;
                    }
                    if (audio && hits != 1) throw new IllegalStateException("Expected exactly one native focus request");
                    if (error) {
                        changed.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                                body.getRegisterCount() - 1, 1, new ImmutableMethodReference(BRIDGE,
                                "onEngineError", Collections.singletonList("Ljava/lang/Object;"), "V")));
                        errors++;
                    }
                    methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                            method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), changed));
                } else methods.add(method);
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        int expectedError = args[2].equals("alicekit") ? 1 : 0;
        if (focus != 1 || errors != expectedError) throw new IllegalStateException("Unexpected hook coverage " + focus + "/" + errors);
        DexPool.writeTo(args[1], new ImmutableDexFile(Opcodes.forApi(28), classes));
        System.out.println(args[2] + ": focus=" + focus + " errors=" + errors);
    }
}
