/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** The only change in classes3 is an observer on the actual factory passed to NaviKit. */
public final class PatchTurnFactory {
    private static final String BASE = "03b3a2fb3725028f7056c009572c898690374231c3bb55c9156cb9d46358743f";
    private static final String FACTORY = "Lcom/yandex/navikit/ui/balloons/BalloonFactory;";
    private static final String LAYER = "Lcom/yandex/navikit/guidance_layer/NaviGuidanceLayer;";
    private static final String WINDOW = "Lcom/yandex/mapkit/map/MapWindow;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final Opcodes API = Opcodes.forApi(28);
    private static final ImmutableMethodReference HOOK = new ImmutableMethodReference(
            "Lru/natro/navigation/StockTurnTextures;", "wrapFactory", Arrays.asList(OBJECT, OBJECT), OBJECT);

    private static String digest(Path file) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    private static ImmutableDexFile transform(DexFile source, boolean remove) {
        int sites = 0;
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef cls : source.getClasses()) {
            if (!cls.getType().equals("Lqn2/t;")) {
                classes.add(cls);
                continue;
            }
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                if (!method.getName().equals("a") || !method.getReturnType().equals(LAYER)
                        || !method.getParameterTypes().equals(Arrays.asList("Lqn2/p;", "Z"))) {
                    methods.add(method);
                    continue;
                }
                MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
                for (int i = 0; i < body.getInstructions().size(); i++) {
                    Instruction in = body.getInstructions().get(i);
                    if (!(in instanceof ReferenceInstruction) || in.getOpcode() != Opcode.INVOKE_STATIC_RANGE)
                        continue;
                    MethodReference ref = (MethodReference) ((ReferenceInstruction) in).getReference();
                    if (!ref.getDefiningClass().equals("Lcom/yandex/navikit/guidance_layer/NaviGuidanceLayerFactory;")
                            || !ref.getName().equals("createNaviGuidanceLayer")) continue;
                    RegisterRangeInstruction args = (RegisterRangeInstruction) in;
                    if (args.getStartRegister() != 0 || args.getRegisterCount() != 14
                            || ref.getParameterTypes().size() != 14 || !ref.getReturnType().equals(LAYER)
                            || !ref.getParameterTypes().get(0).equals(WINDOW)
                            || !ref.getParameterTypes().get(5).equals(FACTORY)) {
                        throw new IllegalStateException("Unexpected stock factory call signature/registers");
                    }
                    if (remove) {
                        if (i < 3) throw new IllegalStateException("Missing factory hook");
                        Instruction hook = body.getInstructions().get(i - 3);
                        Instruction result = body.getInstructions().get(i - 2);
                        Instruction cast = body.getInstructions().get(i - 1);
                        if (hook.getOpcode() != Opcode.INVOKE_STATIC || !HOOK.equals(((ReferenceInstruction) hook).getReference())
                                || ((FiveRegisterInstruction) hook).getRegisterCount() != 2
                                || ((FiveRegisterInstruction) hook).getRegisterC() != 0
                                || ((FiveRegisterInstruction) hook).getRegisterD() != 5
                                || result.getOpcode() != Opcode.MOVE_RESULT_OBJECT
                                || ((OneRegisterInstruction) result).getRegisterA() != 5
                                || cast.getOpcode() != Opcode.CHECK_CAST || ((OneRegisterInstruction) cast).getRegisterA() != 5
                                || !new ImmutableTypeReference(FACTORY).equals(((ReferenceInstruction) cast).getReference())) {
                            throw new IllegalStateException("Unexpected factory hook");
                        }
                        for (int n = 0; n < 3; n++) body.removeInstruction(i - 3);
                        i -= 3;
                    } else {
                        body.addInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 5, 0, 0, 0, HOOK));
                        body.addInstruction(i + 1, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5));
                        body.addInstruction(i + 2, new BuilderInstruction21c(Opcode.CHECK_CAST, 5, new ImmutableTypeReference(FACTORY)));
                        i += 3;
                    }
                    sites++;
                }
                methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(),
                        method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body));
            }
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if (sites != 1) throw new IllegalStateException("Expected one factory hook, found " + sites);
        return new ImmutableDexFile(API, classes);
    }

    private static void verifyBridge(Path input) throws Exception {
        DexFile bridge = DexFileFactory.loadDexFile(input.toFile(), API);
        int matches = 0;
        for (ClassDef cls : bridge.getClasses()) {
            if (!cls.getType().equals(HOOK.getDefiningClass()) || !AccessFlags.PUBLIC.isSet(cls.getAccessFlags())) continue;
            for (Method method : cls.getDirectMethods()) {
                int flags = method.getAccessFlags();
                if (HOOK.equals(method) && AccessFlags.PUBLIC.isSet(flags) && AccessFlags.STATIC.isSet(flags)
                        && method.getImplementation() != null) matches++;
            }
        }
        if (matches != 1) throw new IllegalStateException("Missing public static StockTurnTextures.wrapFactory(Object,Object):Object");
        System.out.println("PASS bridge links the exact stock factory observer");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: PatchTurnFactory STOCK_CLASSES3 OUTPUT_CLASSES3 | verify-bridge CLASSES19");
        if (args[0].equals("verify-bridge")) {
            verifyBridge(Paths.get(args[1]));
            return;
        }
        Path input = Paths.get(args[0]), output = Paths.get(args[1]);
        if (!BASE.equals(digest(input))) throw new IllegalArgumentException("Expected signed Navigator 3.4.9 classes3.dex");
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Distinct input and output paths required");
        DexFile stock = DexFileFactory.loadDexFile(input.toFile(), API);
        DexPool.writeTo(output.toString(), transform(stock, false));
        Path normalized = Files.createTempFile("turn-factory-stock-", ".dex");
        Path restored = Files.createTempFile("turn-factory-restored-", ".dex");
        try {
            DexPool.writeTo(normalized.toString(), stock);
            DexPool.writeTo(restored.toString(), transform(DexFileFactory.loadDexFile(output.toFile(), API), true));
            if (!digest(normalized).equals(digest(restored)))
                throw new IllegalStateException("classes3 differs outside the reviewed three-instruction factory hook");
            System.out.println("PASS factory capture and canonical whole-DEX round trip " + digest(output));
        } finally {
            Files.deleteIfExists(normalized);
            Files.deleteIfExists(restored);
        }
    }
}
