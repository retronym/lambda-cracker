package io.github.retronym.lambdacracker;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;

/**
 * Rewrites every {@code MethodHandles.Lookup.makeHiddenClassDefiner} overload so the class
 * bytes pass through {@code LambdaCrackerHook.maybeAugment} on entry. All hidden-class
 * definitions funnel through these overloads — including the lambda proxies spun by
 * {@code InnerClassLambdaMetafactory}, which never reach ClassFileTransformers.
 */
final class LookupInjector implements ClassFileTransformer {
    volatile int injectedCount;
    volatile Throwable failure;

    private static final String TARGET = "java/lang/invoke/MethodHandles$Lookup";
    private static final ClassDesc CD_HOOK = ClassDesc.of("io.github.retronym.lambdacracker.boot.LambdaCrackerHook");
    private static final MethodTypeDesc MTD_AUGMENT = MethodTypeDesc.ofDescriptor("([B)[B");

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain pd, byte[] bytes) {
        if (!TARGET.equals(className)) return null;
        try {
            // Stack maps of the transformed methods are regenerated; resolving java.base
            // internals needs the class-loading fallback.
            ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                    ClassHierarchyResolver.defaultResolver()
                            .orElse(ClassHierarchyResolver.ofClassLoading(ClassLoader.getSystemClassLoader()))));
            ClassModel cm = cf.parse(bytes);
            ClassTransform xform = (clb, cle) -> {
                if (cle instanceof MethodModel mm && mm.methodName().equalsString("makeHiddenClassDefiner")) {
                    int slot = byteArraySlot(mm);
                    if (slot >= 0) {
                        injectedCount++;
                        clb.transformMethod(mm, MethodTransform.transformingCode(new CodeTransform() {
                            @Override
                            public void atStart(CodeBuilder cob) {
                                cob.aload(slot).invokestatic(CD_HOOK, "maybeAugment", MTD_AUGMENT).astore(slot);
                            }

                            @Override
                            public void accept(CodeBuilder cob, CodeElement coe) {
                                cob.with(coe);
                            }
                        }));
                        return;
                    }
                }
                clb.with(cle);
            };
            return cf.transformClass(cm, xform);
        } catch (Throwable t) {
            failure = t;
            return null;
        }
    }

    private static int byteArraySlot(MethodModel mm) {
        MethodTypeDesc mtd = mm.methodTypeSymbol();
        int slot = mm.flags().has(AccessFlag.STATIC) ? 0 : 1;
        for (int i = 0; i < mtd.parameterCount(); i++) {
            String d = mtd.parameterType(i).descriptorString();
            if (d.equals("[B")) return slot;
            slot += d.equals("J") || d.equals("D") ? 2 : 1;
        }
        return -1;
    }
}
