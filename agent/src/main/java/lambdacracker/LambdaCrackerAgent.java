package lambdacracker;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Premain entry point. This class runs on the system classloader; the classes reachable
 * from code injected into java.base live in {@code lambdacracker.boot} and are loaded a
 * second time by the bootstrap loader — no statics are shared between the two copies, so
 * this class must only refer to them by name.
 */
public final class LambdaCrackerAgent {
    private LambdaCrackerAgent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        // Retransform MethodHandles.Lookup first, while this agent's jar is still absent
        // from the bootstrap loader's search path. LookupInjector.transform() resolves an
        // anonymous CodeTransform (LookupInjector$1) the first time it runs; if the jar were
        // already appended to the bootstrap search by then, parent-delegation would let the
        // bootstrap loader define that class instead of the app loader that defines
        // LookupInjector itself, splitting one class across two loaders and two unnamed
        // modules that don't read each other.
        LookupInjector injector = new LookupInjector();
        inst.addTransformer(injector, true);
        try {
            inst.retransformClasses(MethodHandles.Lookup.class);
        } finally {
            inst.removeTransformer(injector);
        }
        if (injector.failure != null)
            throw new IllegalStateException("lambda-cracker: failed to instrument MethodHandles.Lookup", injector.failure);
        if (injector.injectedCount == 0)
            throw new IllegalStateException("lambda-cracker: no makeHiddenClassDefiner(.., byte[], ..) methods found");

        // Only now make lambdacracker.boot.* visible to java.base: the injected invokestatic
        // in Lookup resolves against the boot-loader copy of the hook the first time a hidden
        // class is defined, which happens no earlier than this point.
        File jar = new File(LambdaCrackerAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));

        // java.base does not read unnamed modules by default; add the read edge.
        Class<?> hook = Class.forName("lambdacracker.boot.LambdaCrackerHook", false, null);
        inst.redefineModule(Object.class.getModule(),
                Set.of(hook.getModule()), Map.of(), Map.of(), Set.of(), Map.of());
    }
}
