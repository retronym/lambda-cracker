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
        File jar = new File(LambdaCrackerAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));

        // The injected invokestatic in MethodHandles.Lookup resolves against the boot-loader
        // copy of the hook, which lives in the boot loader's unnamed module. java.base does
        // not read unnamed modules by default; add the read edge.
        Class<?> hook = Class.forName("lambdacracker.boot.LambdaCrackerHook", false, null);
        inst.redefineModule(Object.class.getModule(),
                Set.of(hook.getModule()), Map.of(), Map.of(), Set.of(), Map.of());

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
    }
}
