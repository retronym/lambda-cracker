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
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Target of the injected {@code toString()}. Everything expensive — locating and parsing
 * the classfile that hosts the lambda's implementation method — happens on first render
 * and is cached per lambda class. Captured values are read per call. Never throws.
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
        if (depth[0] >= 3) return fallback(lambda); // captured lambdas rendering captured lambdas
        depth[0]++;
        try {
            return CACHE.computeIfAbsent(lambda.getClass(), c -> Description.compute(c, meta)).render(lambda);
        } catch (Throwable t) {
            return fallback(lambda);
        } finally {
            depth[0]--;
        }
    }

    private static String fallback(Object o) {
        return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    private static final class Description {
        final String prefix;          // everything except the per-instance captured values
        final Field[] captureFields;  // arg$1..arg$N, accessible where possible
        final String[] captureNames;

        private Description(String prefix, Field[] captureFields, String[] captureNames) {
            this.prefix = prefix;
            this.captureFields = captureFields;
            this.captureNames = captureNames;
        }

        String render(Object lambda) {
            if (captureFields.length == 0) return prefix;
            StringBuilder sb = new StringBuilder(prefix).append(" [");
            for (int i = 0; i < captureFields.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(captureNames[i]).append('=').append(valueString(captureFields[i], lambda));
            }
            return sb.append(']').toString();
        }

        private static String valueString(Field f, Object lambda) {
            try {
                String s = String.valueOf(f.get(lambda));
                return s.length() > MAX_VALUE_LEN ? s.substring(0, MAX_VALUE_LEN - 1) + "…" : s;
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
                String ref = ownerSimple + "::" + (kind == 'N' ? "new" : implName);
                return new Description(ref, captures, capNames);
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

            StringBuilder p = new StringBuilder();
            if (sourceFile != null) {
                p.append(sourceFile);
                if (line > 0) p.append(':').append(line);
                p.append(' ');
            }
            p.append(ownerSimple);
            if (!encl.isEmpty()) p.append('.').append(encl);
            p.append(" { ").append(paramList(lambdaParams)).append(" => ").append(body).append(" }");
            return new Description(p.toString(), captures, capNames);
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
