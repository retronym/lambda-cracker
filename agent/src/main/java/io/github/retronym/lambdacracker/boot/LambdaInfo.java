package io.github.retronym.lambdacracker.boot;

import java.util.Map;

/**
 * A structured, introspectable rendering of a lambda: everything the agent's injected
 * {@code toString()} prints, exposed as data instead of a pre-formatted string. Returned by
 * {@link LambdaCrackerRuntime#describe(Object)} for library-mode use (no agent, no injected
 * {@code toString}); the agent path renders the identical format directly to a {@code String}
 * without allocating one of these.
 *
 * <p>When {@link #resolved} is {@code false}, none of the other fields carry information —
 * {@link #body} holds a plain {@code Class@identityHash} fallback string instead, matching
 * the JDK default {@code toString} so nothing is worse than the uninstrumented case. This
 * happens when the lambda's functional interface isn't {@code Serializable} (library mode's
 * only way to recover impl-method coordinates without the agent) or analysis otherwise fails.
 *
 * <p>When {@link #methodReference} is {@code true}, this describes a method or constructor
 * reference rather than a lambda body: {@link #enclosingClass} and {@link #enclosingMethod}
 * identify the target ({@code "new"} for a constructor reference), and {@link #sourceFile},
 * {@link #line}, {@link #params}, and {@link #body} are not populated.
 */
public record LambdaInfo(
        boolean resolved,
        boolean methodReference,
        String sourceFile,
        int line,
        String enclosingClass,
        String enclosingMethod,
        String params,
        String body,
        Map<String, String> captures
) {
    @Override
    public String toString() {
        if (!resolved) return body;

        StringBuilder sb = new StringBuilder();
        if (methodReference) {
            sb.append(enclosingClass).append("::").append(enclosingMethod);
        } else {
            if (sourceFile != null) {
                sb.append(sourceFile);
                if (line > 0) sb.append(':').append(line);
                sb.append(' ');
            }
            sb.append(enclosingClass);
            if (!enclosingMethod.isEmpty()) sb.append('.').append(enclosingMethod);
            sb.append(" { ").append(params).append(" => ").append(body).append(" }");
        }

        if (!captures.isEmpty()) {
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<String, String> e : captures.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
