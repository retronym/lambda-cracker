package demo;

/**
 * Pure-Java entry point: no Scala class on the call path, proving the agent needs nothing
 * beyond java.base. Run via {@code sbt "demo/runMain demo.JavaApp"} — {@code demo/run}
 * stays pinned to {@code demo.Demo} (see build.sbt) so the two don't collide.
 */
public class JavaApp {
    public static void main(String[] args) {
        System.out.println("-- Pure-Java app, no Scala on the call path --");
        JavaDemo.run();

        System.out.println("-- Method references, every JLS 15.13 kind --");
        JavaDemo.methodReferences();
    }
}
