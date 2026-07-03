package demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class JavaDemo {
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

        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("single-expression closure over a local variable", add);
        cases.put("boolean predicate (comparison bails to call summary)", longish);
        cases.put("zero-arg supplier, constant body", constant);
        cases.put("closure over a local variable, string concat", label);
        cases.put("unbound instance method reference", ref);
        cases.put("static method reference", max);
        cases.put("constructor reference", ctor);
        cases.put("multi-statement Runnable (bails to call summary)", multi);

        for (Map.Entry<String, Object> e : cases.entrySet())
            System.out.printf("  %-64s -> %s%n", e.getKey(), e.getValue());
    }
}