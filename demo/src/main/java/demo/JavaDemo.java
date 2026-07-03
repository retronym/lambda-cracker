package demo;

import java.util.ArrayList;
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

        for (Object fn : new Object[]{add, longish, constant, label, ref, max, ctor, multi})
            System.out.println("  " + fn);
    }
}
