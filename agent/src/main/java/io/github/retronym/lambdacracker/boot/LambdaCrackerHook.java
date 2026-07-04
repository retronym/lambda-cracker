package io.github.retronym.lambdacracker.boot;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;

/**
 * Called from instrumented {@code MethodHandles.Lookup.makeHiddenClassDefiner} for every
 * hidden class about to be defined. Lambda proxies (name contains {@code $$Lambda}) get a
 * {@code toString()} that delegates to {@link LambdaCrackerRuntime#render} with the
 * implementation-method coordinates baked in as a string constant; everything else passes
 * through untouched after a cheap hand-rolled constant-pool peek.
 */
public final class LambdaCrackerHook {
    private LambdaCrackerHook() {}

    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static final ClassDesc CD_RUNTIME = ClassDesc.of("io.github.retronym.lambdacracker.boot.LambdaCrackerRuntime");
    private static final MethodTypeDesc MTD_TO_STRING = MethodTypeDesc.ofDescriptor("()Ljava/lang/String;");
    private static final MethodTypeDesc MTD_RENDER =
            MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;");

    public static byte[] maybeAugment(byte[] bytes) {
        // Re-entrancy: augmenting uses the Classfile API, which may itself cause hidden
        // classes (LambdaForms, its own lambdas) to be defined. Those pass through plain.
        if (bytes == null || BUSY.get() != null) return bytes;
        BUSY.set(Boolean.TRUE);
        try {
            String name = thisClassName(bytes);
            if (name == null || !name.contains("$$Lambda")) return bytes;
            byte[] augmented = augment(bytes);
            return augmented != null ? augmented : bytes;
        } catch (Throwable t) {
            return bytes; // never break class definition
        } finally {
            BUSY.remove();
        }
    }

    private static byte[] augment(byte[] bytes) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(bytes);
        for (MethodModel mm : cm.methods())
            if (mm.methodName().equalsString("toString")) return null; // overload delegation: already augmented
        String meta = findImplMeta(cm);
        if (meta == null) return null;
        return cf.transformClass(cm, ClassTransform.endHandler(clb ->
                clb.withMethodBody("toString", MTD_TO_STRING, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL,
                        cob -> cob.aload(0)
                                .loadConstant(meta)
                                .invokestatic(CD_RUNTIME, "render", MTD_RENDER)
                                .areturn())));
    }

    /**
     * The proxy's forwarding methods contain exactly one interesting invocation: the
     * implementation method the metafactory was given. Constructor references appear as an
     * {@code <init>} invoke on the referenced class.
     *
     * <p>A boxed-argument static method reference (e.g. {@code Function<Integer,Integer> f =
     * Math::abs}) forwards through a metafactory-synthesized unbox-call-rebox sequence, and
     * that unbox call — unlike javac- or scalac-emitted boxing, which uses the concrete
     * wrapper type — invokes the generic {@code Number.intValue()} (etc.), not
     * {@code Integer.intValue()}. Skip it like any other boxing/unboxing call, or it gets
     * mistaken for the impl method.
     */
    private static String findImplMeta(ClassModel cm) {
        String self = cm.thisClass().asInternalName();
        for (MethodModel mm : cm.methods()) {
            if (mm.methodName().equalsString("<init>")) continue;
            CodeModel code = mm.code().orElse(null);
            if (code == null) continue;
            for (CodeElement e : code.elementList()) {
                if (!(e instanceof InvokeInstruction inv)) continue;
                if (isBoxingOrUnboxing(inv)) continue;
                String owner = inv.owner().asInternalName();
                if (owner.equals(self) || owner.equals("java/lang/Object")
                        || owner.equals("java/util/Objects") || owner.startsWith("java/lang/invoke/")) continue;
                char kind = switch (inv.opcode()) {
                    case INVOKESTATIC -> 'S';
                    case INVOKEINTERFACE -> 'I';
                    default -> inv.name().equalsString("<init>") ? 'N' : 'V';
                };
                return kind + "|" + owner + "|" + inv.name().stringValue() + "|" + inv.typeSymbol().descriptorString();
            }
        }
        return null;
    }

    private static boolean isBoxingOrUnboxing(InvokeInstruction inv) {
        String owner = inv.owner().asInternalName();
        String name = inv.name().stringValue();
        boolean isStatic = inv.opcode() == Opcode.INVOKESTATIC;
        int argc = inv.typeSymbol().parameterCount();
        if (isStatic && argc == 1 && isWrapper(owner) && name.equals("valueOf")) return true;
        if (!isStatic && argc == 0 && (isWrapper(owner) || owner.equals("java/lang/Number")) && isValueMethod(name)) return true;
        return false;
    }

    private static boolean isWrapper(String owner) {
        return switch (owner) {
            case "java/lang/Integer", "java/lang/Long", "java/lang/Double", "java/lang/Float",
                 "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean" -> true;
            default -> false;
        };
    }

    private static boolean isValueMethod(String name) {
        return switch (name) {
            case "intValue", "longValue", "doubleValue", "floatValue", "shortValue", "byteValue",
                 "booleanValue", "charValue" -> true;
            default -> false;
        };
    }

    /** Minimal constant-pool walk to read this_class without a full parse. */
    private static String thisClassName(byte[] b) {
        if (b.length < 10 || b[0] != (byte) 0xCA) return null;
        int cpCount = u2(b, 8);
        int[] utf8Off = new int[cpCount];
        int[] classNameIdx = new int[cpCount];
        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            int tag = b[p] & 0xff;
            switch (tag) {
                case 1 -> { utf8Off[i] = p + 1; p += 3 + u2(b, p + 1); }      // Utf8
                case 7 -> { classNameIdx[i] = u2(b, p + 1); p += 3; }         // Class
                case 8, 16, 19, 20 -> p += 3;                                 // String, MethodType, Module, Package
                case 15 -> p += 4;                                            // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 5;                   // int/float/refs/NameAndType/(inv)dynamic
                case 5, 6 -> { p += 9; i++; }                                 // long/double
                default -> { return null; }
            }
        }
        int thisIdx = u2(b, p + 2);
        if (thisIdx <= 0 || thisIdx >= cpCount) return null;
        int nameIdx = classNameIdx[thisIdx];
        if (nameIdx <= 0 || nameIdx >= cpCount || utf8Off[nameIdx] == 0) return null;
        int off = utf8Off[nameIdx];
        return new String(b, off + 2, u2(b, off), StandardCharsets.UTF_8);
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }
}
