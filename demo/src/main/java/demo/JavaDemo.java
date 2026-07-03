package demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lambdacracker.LambdaCracker;
import lambdacracker.boot.LambdaInfo;

public class JavaDemo {
    // Plain java.util.function.Function isn't Serializable; library mode needs writeReplace()
    // to recover impl-method coordinates without the agent, so it needs this explicit cast.
    interface SerializableFunction<A, B> extends Function<A, B>, Serializable {}

    public static void run() {
        int offset = 7;
        String tag = "prod";
        Function<Integer, Integer> add = x -> x + offset;
        Predicate<String> longish = s -> s.length() > 3;
        Supplier<String> constant = () -> "constant";
        Function<String, String> label = s -> tag + ":" + s;
        Function<String, Integer> ref = String::length;
        BiFunction<Integer, Integer, Integer> max = Math::max;
        Supplier<ArrayList<String>> ctor = ArrayList::new;
        Runnable multi = () -> { int a = offset * 2; System.out.println(a); };
        Function<Integer, Integer> factorial = n -> {
            int r = 1;
            for (int i = 2; i <= n; i++) r *= i;
            return r;
        };

        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("single-expression closure over a local variable", add);
        cases.put("boolean predicate (comparison bails to bytecode)", longish);
        cases.put("zero-arg supplier, constant body", constant);
        cases.put("closure over a local variable, string concat", label);
        cases.put("unbound instance method reference", ref);
        cases.put("static method reference", max);
        cases.put("constructor reference", ctor);
        cases.put("multi-statement Runnable (bails to bytecode)", multi);
        cases.put("loop body — hard case (bails to bytecode)", factorial);

        for (Map.Entry<String, Object> e : cases.entrySet())
            System.out.printf("  %-64s -> %s%n", e.getKey(), e.getValue());

        System.out.println("-- Library mode (no agent, no rewritten toString) --");
        SerializableFunction<Integer, Integer> serializableAdd = x -> x + offset;
        LambdaInfo info = LambdaCracker.describe(serializableAdd);
        System.out.printf("  %-64s -> %s%n", "LambdaCracker.describe on an explicitly Serializable lambda", info);

        LambdaInfo unresolved = LambdaCracker.describe(add); // 'add' above is a plain, non-Serializable Function
        System.out.printf("  %-64s -> %s (resolved=%s)%n",
                "non-Serializable lambda degrades gracefully, no crash", unresolved, unresolved.resolved());
    }
}