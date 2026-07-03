package io.github.retronym.lambdacracker;

import io.github.retronym.lambdacracker.boot.LambdaCrackerRuntime;
import io.github.retronym.lambdacracker.boot.LambdaInfo;

/**
 * Library-mode entry point: add this jar as a plain dependency (no {@code -javaagent}, no
 * rewritten {@code toString}) and call {@link #describe} to introspect a lambda on demand.
 * Requires the lambda's functional interface to be {@code Serializable} — true of every
 * Scala {@code FunctionN} by default, and available for a Java lambda via an explicit
 * {@code (Foo & Serializable)} cast — since that's the only way to recover which method a
 * lambda proxy delegates to without the agent's spin-time hook.
 */
public final class LambdaCracker {
    private LambdaCracker() {}

    /**
     * Introspects a lambda. The expensive analysis (locating the impl method's classfile,
     * reconstructing its body) runs once per lambda site (its hidden class) and is cached;
     * repeat calls for lambdas from the same site only re-read captured values.
     */
    public static LambdaInfo describe(Object lambda) {
        return LambdaCrackerRuntime.describe(lambda);
    }
}
