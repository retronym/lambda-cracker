package lambdacracker.boot;

import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two entry points into the same analysis engine, sharing one cache keyed by lambda class
 * (the "lambda site" — every instance from the same call site shares a class; only captured
 * values differ):
 *
 * <ul>
 *   <li>{@link #render}: target of the agent's injected {@code toString()}. The impl-method
 *       coordinates ({@code meta}) were extracted from the proxy's classfile at spin time,
 *       before it became a hidden class no reflection could reach again.
 *   <li>{@link #describe}: library-mode entry point, no agent required. Impl-method
 *       coordinates come from {@link SerializedLambda} instead, via the lambda's own
 *       {@code writeReplace()} — which only exists if its functional interface is
 *       {@code Serializable}. Every Scala {@code FunctionN} is, by language design; a Java
 *       lambda needs an explicit {@code (Foo & Serializable)} cast.
 * </ul>
 *
 * Everything expensive — locating and parsing the classfile that hosts the lambda's
 * implementation method, reconstructing its body — happens once per lambda class and is
 * cached. Captured values are read fresh on every call. Never throws.
 */
public final class LambdaCrackerRuntime {
    private LambdaCrackerRuntime() {}

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    // PoC: strong keys pin lambda classes against unloading; see PLAN.md follow-ups.
    private static final Map<Class<?>, Description> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_VALUE_LEN = 60;
    private static final int MAX_BYTECODE_LEN = 160;

    public static String render(Object lambda, String meta) {
        int[] depth = DEPTH.get();
        if (depth[0] >= 3) return fallbackText(lambda); // captured lambdas rendering captured lambdas
        depth[0]++;
        try {
            return CACHE.computeIfAbsent(lambda.getClass(), c -> Description.compute(c, meta)).render(lambda);
        } catch (Throwable t) {
            return fallbackText(lambda);
        } finally {
            depth[0]--;
        }
    }

    /**
     * Library-mode introspection: no agent, no rewritten {@code toString}. Works for any
     * lambda whose functional interface is {@code Serializable} (every Scala lambda; a Java
     * lambda cast to {@code (Foo & Serializable)}); anything else degrades to
     * {@link LambdaInfo#resolved} {@code == false}, matching the JDK-default-shaped fallback.
     */
    public static LambdaInfo describe(Object lambda) {
        int[] depth = DEPTH.get();
        if (depth[0] >= 3) return unresolved(lambda);
        depth[0]++;
        try {
            SerializedLambda sl = serialize(lambda);
            if (sl == null) return unresolved(lambda);
            Description d = CACHE.computeIfAbsent(lambda.getClass(), c -> Description.compute(c, metaOf(sl)));
            return d.describe(capturesFromSerialized(d, sl));
        } catch (Throwable t) {
            return unresolved(lambda);
        } finally {
            depth[0]--;
        }
    }

    private static SerializedLambda serialize(Object lambda) {
        try {
            Method m = lambda.getClass().getDeclaredMethod("writeReplace");
            m.setAccessible(true);
            Object replacement = m.invoke(lambda);
            return replacement instanceof SerializedLambda sl ? sl : null;
        } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
            return null;
        }
    }

    private static String metaOf(SerializedLambda sl) {
        char kind = switch (sl.getImplMethodKind()) {
            case MethodHandleInfo.REF_invokeStatic -> 'S';
            case MethodHandleInfo.REF_invokeInterface -> 'I';
            case MethodHandleInfo.REF_newInvokeSpecial -> 'N';
            default -> 'V'; // REF_invokeVirtual, REF_invokeSpecial
        };
        return kind + "|" + sl.getImplClass() + "|" + sl.getImplMethodName() + "|" + sl.getImplMethodSignature();
    }

    private static Map<String, String> capturesFromSerialized(Description d, SerializedLambda sl) {
        int n = Math.min(sl.getCapturedArgCount(), d.captureNames.length);
        if (n == 0) return Map.of();
        Map<String, String> captures = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) captures.put(d.captureNames[i], valueString(sl.getCapturedArg(i)));
        return captures;
    }

    private static String fallbackText(Object o) {
        return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    private static LambdaInfo unresolved(Object lambda) {
        return new LambdaInfo(false, false, null, -1, null, "", "", fallbackText(lambda), Map.of());
    }

    private static String valueString(Object value) {
        try {
            String s = String.valueOf(value);
            return s.length() > MAX_VALUE_LEN ? s.substring(0, MAX_VALUE_LEN - 1) + "…" : s;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static final class Description {
        final boolean methodReference;
        final String sourceFile;      // null unless a lambda body with known source
        final int line;               // -1 if unknown
        final String enclosingClass;
        final String enclosingMethod; // "" for a top-level lambda; "new"/name for a reference
        final String params;
        final String body;
        final Field[] captureFields;  // arg$1..arg$N, accessible where possible (agent path)
        final String[] captureNames;

        private Description(boolean methodReference, String sourceFile, int line, String enclosingClass,
                             String enclosingMethod, String params, String body,
                             Field[] captureFields, String[] captureNames) {
            this.methodReference = methodReference;
            this.sourceFile = sourceFile;
            this.line = line;
            this.enclosingClass = enclosingClass;
            this.enclosingMethod = enclosingMethod;
            this.params = params;
            this.body = body;
            this.captureFields = captureFields;
            this.captureNames = captureNames;
        }

        LambdaInfo describe(Map<String, String> captures) {
            return new LambdaInfo(true, methodReference, sourceFile, line, enclosingClass, enclosingMethod,
                    params, body, captures);
        }

        /** Agent path: capture values come from the proxy's own {@code arg$N} fields. */
        String render(Object lambda) {
            return describe(captureValues(lambda)).toString();
        }

        private Map<String, String> captureValues(Object lambda) {
            if (captureFields.length == 0) return Map.of();
            Map<String, String> captures = new LinkedHashMap<>();
            for (int i = 0; i < captureFields.length; i++)
                captures.put(captureNames[i], valueString(captureFields[i], lambda));
            return captures;
        }

        private static String valueString(Field f, Object lambda) {
            try {
                return LambdaCrackerRuntime.valueString(f.get(lambda));
            } catch (Throwable t) {
                return "?";
            }
        }

        static Description compute(Class<?> lambdaClass, String meta) {
            String[] parts = meta.split("\\|", 4);
            char kind = parts[0].charAt(0);
            String owner = parts[1];
            String implName = parts[2];
            String implDesc = parts[3];

            Field[] captures = captureFields(lambdaClass);
            String[] capNames = new String[captures.length];
            for (int i = 0; i < captures.length; i++) capNames[i] = captures[i].getName();
            String ownerSimple = simpleName(owner);

            String encl = Demangler.enclosingMethod(implName);
            if (encl == null) { // a method (or constructor) reference, not a lambda body
                String refName = kind == 'N' ? "new" : implName;
                return new Description(true, null, -1, ownerSimple, refName, "", null, captures, capNames);
            }

            String sourceFile = null;
            int line = -1;
            String body = "…";
            List<String> lambdaParams = new ArrayList<>();

            ClassModel cm = parseOwner(lambdaClass, owner);
            MethodModel impl = cm == null ? null : findMethod(cm, implName, implDesc);
            if (impl != null && implName.endsWith("$adapted")) {
                // Scala 2.13 boxing adapter; the real body lives in the unsuffixed method.
                MethodModel real = findMethodByName(cm, implName.substring(0, implName.length() - "$adapted".length()));
                if (real != null) impl = real;
            }
            CodeModel code = impl == null ? null : impl.code().orElse(null);
            if (code != null) {
                sourceFile = cm.findAttribute(Attributes.sourceFile())
                        .map(a -> a.sourceFile().stringValue()).orElse(null);
                for (CodeElement e : code.elementList())
                    if (e instanceof LineNumber ln) { line = ln.line(); break; }

                boolean implStatic = impl.flags().has(AccessFlag.STATIC);
                MethodTypeDesc mtd = impl.methodTypeSymbol();
                int[] slots = paramSlots(mtd, implStatic);
                Map<Integer, String> names = slotNames(code, mtd, slots, implStatic);

                // Leading impl parameters are the captures; the rest are the lambda's own.
                int leadingCaptures = implStatic ? captures.length : captures.length - 1;
                if (leadingCaptures >= 0 && leadingCaptures <= mtd.parameterCount()) {
                    if (!implStatic && captures.length > 0) capNames[0] = "this";
                    for (int i = 0; i < leadingCaptures; i++)
                        capNames[i + (implStatic ? 0 : 1)] = names.get(slots[i]);
                    for (int i = leadingCaptures; i < mtd.parameterCount(); i++)
                        lambdaParams.add(names.get(slots[i]));
                }

                String rendered = BodyRenderer.render(code, names);
                if (rendered != null) {
                    body = rendered;
                } else {
                    String bytecode = BodyRenderer.textify(code, names);
                    body = bytecode != null ? "«bytecode» " + truncate(bytecode, MAX_BYTECODE_LEN) : "…";
                }
            }

            return new Description(false, sourceFile, line, ownerSimple, encl, paramList(lambdaParams), body,
                    captures, capNames);
        }

        private static ClassModel parseOwner(Class<?> lambdaClass, String owner) {
            try {
                ClassLoader cl = lambdaClass.getClassLoader(); // hidden class: the capturing class's loader
                String res = owner + ".class";
                try (InputStream in = cl != null ? cl.getResourceAsStream(res)
                        : ClassLoader.getSystemResourceAsStream(res)) {
                    return in == null ? null : ClassFile.of().parse(in.readAllBytes());
                }
            } catch (Throwable t) {
                return null;
            }
        }

        private static Field[] captureFields(Class<?> lambdaClass) {
            List<Field> fields = new ArrayList<>();
            for (Field f : lambdaClass.getDeclaredFields())
                if (f.getName().startsWith("arg$")) {
                    f.trySetAccessible();
                    fields.add(f);
                }
            fields.sort(Comparator.comparingInt(f -> Integer.parseInt(f.getName().substring(4))));
            return fields.toArray(new Field[0]);
        }

        private static MethodModel findMethod(ClassModel cm, String name, String desc) {
            for (MethodModel mm : cm.methods())
                if (mm.methodName().equalsString(name) && mm.methodType().equalsString(desc)) return mm;
            return null;
        }

        private static MethodModel findMethodByName(ClassModel cm, String name) {
            for (MethodModel mm : cm.methods())
                if (mm.methodName().equalsString(name)) return mm;
            return null;
        }

        private static int[] paramSlots(MethodTypeDesc mtd, boolean isStatic) {
            int[] slots = new int[mtd.parameterCount()];
            int s = isStatic ? 0 : 1;
            for (int i = 0; i < slots.length; i++) {
                slots[i] = s;
                String d = mtd.parameterType(i).descriptorString();
                s += d.equals("J") || d.equals("D") ? 2 : 1;
            }
            return slots;
        }

        /** Positional p1..pN defaults, overridden by LocalVariableTable names when compiled with -g. */
        private static Map<Integer, String> slotNames(CodeModel code, MethodTypeDesc mtd, int[] slots, boolean isStatic) {
            Map<Integer, String> lvt = new HashMap<>();
            for (CodeElement e : code.elementList())
                if (e instanceof LocalVariable lv) {
                    String n = lv.name().stringValue();
                    lvt.putIfAbsent(lv.slot(), n.equals("$this") ? "this" : n);
                }
            Map<Integer, String> names = new HashMap<>();
            if (!isStatic) names.put(0, "this");
            for (int i = 0; i < slots.length; i++) names.put(slots[i], "p" + (i + 1));
            names.putAll(lvt);
            return names;
        }

        private static String truncate(String s, int max) {
            return s.length() > max ? s.substring(0, max - 1) + "…" : s;
        }

        private static String paramList(List<String> params) {
            return switch (params.size()) {
                case 0 -> "()";
                case 1 -> params.get(0);
                default -> "(" + String.join(", ", params) + ")";
            };
        }

        private static String simpleName(String internalName) {
            String s = internalName.substring(internalName.lastIndexOf('/') + 1);
            if (s.endsWith("$")) s = s.substring(0, s.length() - 1);
            return s.replace('$', '.');
        }
    }
}
