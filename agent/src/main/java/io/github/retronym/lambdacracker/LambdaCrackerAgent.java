package io.github.retronym.lambdacracker;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Premain entry point. This class runs on the system classloader; the classes reachable
 * from code injected into java.base live in {@code io.github.retronym.lambdacracker.boot} and are loaded a
 * second time by the bootstrap loader — no statics are shared between the two copies, so
 * this class must only refer to them by name.
 */
public final class LambdaCrackerAgent {
    private LambdaCrackerAgent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        // Resolve our own jar before touching Lookup at all: findAgentJar() calls ordinary
        // JDK APIs (ManagementFactory) that may themselves spin a lambda internally. Once
        // Lookup is retransformed below, spinning ANY lambda routes through our hook, which
        // needs io.github.retronym.lambdacracker.boot.* on the bootstrap search path — not
        // appended until after the retransform. Doing this lookup first avoids the ordering
        // hazard entirely instead of trying to dodge lambda use in whatever API we call.
        File agentJar = findAgentJar();

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

        // Only now make io.github.retronym.lambdacracker.boot.* visible to java.base: the injected invokestatic
        // in Lookup resolves against the boot-loader copy of the hook the first time a hidden
        // class is defined, which happens no earlier than this point.
        inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));

        // java.base does not read unnamed modules by default; add the read edge.
        Class<?> hook = Class.forName("io.github.retronym.lambdacracker.boot.LambdaCrackerHook", false, null);
        inst.redefineModule(Object.class.getModule(),
                Set.of(hook.getModule()), Map.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * {@code getProtectionDomain().getCodeSource().getLocation()} isn't reliable here: it
     * reports whichever classpath entry actually satisfied loading this class, which is this
     * agent's jar only as long as nothing else on the classpath shadows it. Library mode
     * (this jar as a plain compile dependency, e.g. via sbt's {@code .dependsOn(agent)})
     * means an IDE-derived run configuration can list the agent's output *directory* ahead of the
     * jar the {@code -javaagent} flag names, resolving this class from there instead — and
     * {@code new JarFile(aDirectory)} throws. Read the actual {@code -javaagent:<path>} JVM
     * argument instead and confirm it's us via the manifest, which is unaffected by classpath
     * order.
     */
    private static File findAgentJar() throws Exception {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : args) {
            if (!arg.startsWith("-javaagent:")) continue;
            String spec = arg.substring("-javaagent:".length());
            int eq = spec.indexOf('=');
            File candidate = new File(eq < 0 ? spec : spec.substring(0, eq));
            if (!candidate.isFile()) continue;
            try (JarFile jf = new JarFile(candidate)) {
                String premainClass = jf.getManifest().getMainAttributes().getValue("Premain-Class");
                if (LambdaCrackerAgent.class.getName().equals(premainClass)) return candidate;
            } catch (Exception ignored) {
                // not a jar, or unreadable manifest: not us, keep looking
            }
        }
        throw new IllegalStateException("lambda-cracker: could not find this agent's own jar among -javaagent arguments: " + args);
    }
}
