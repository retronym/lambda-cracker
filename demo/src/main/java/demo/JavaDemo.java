package demo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import io.github.retronym.lambdacracker.LambdaCracker;
import io.github.retronym.lambdacracker.boot.LambdaInfo;

public class JavaDemo {
    // Plain java.util.function.Function isn't Serializable; library mode needs writeReplace()
    // to recover impl-method coordinates without the agent, so it needs this explicit cast.
    interface SerializableFunction<A, B> extends Function<A, B>, Serializable {}

    static class Greeter {
        private final String name;
        Greeter(String name) { this.name = name; }
        String greet(String salutation) { return salutation + ", " + name + "!"; }
        @Override public String toString() { return "Greeter(" + name + ")"; }
    }

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

    /** All four JLS 15.13 method-reference kinds, plus an array constructor reference. */
    public static void methodReferences() {
        Greeter ada = new Greeter("Ada");

        Function<Integer, Integer> staticRef = Math::abs;                          // static method
        Function<String, Integer> unboundOneArg = String::length;                 // unbound instance, receiver is the sole arg
        BiFunction<String, String, Boolean> unboundTwoArg = String::startsWith;    // unbound instance, receiver + explicit arg
        Supplier<Integer> boundOnLiteral = "hello"::length;                       // bound instance, literal receiver captured
        Function<String, String> boundOnObject = ada::greet;                      // bound instance, captures `ada`
        Supplier<ArrayList<String>> ctorRef = ArrayList::new;                     // constructor
        IntFunction<int[]> arrayCtorRef = int[]::new;                             // array constructor

        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("static method reference (Math::abs)", staticRef);
        cases.put("unbound instance ref, 1-arg (String::length)", unboundOneArg);
        cases.put("unbound instance ref, 2-arg (String::startsWith)", unboundTwoArg);
        cases.put("bound instance ref, literal receiver (\"hello\"::length)", boundOnLiteral);
        cases.put("bound instance ref, captured receiver (ada::greet)", boundOnObject);
        cases.put("constructor reference (ArrayList::new)", ctorRef);
        cases.put("array constructor reference (int[]::new)", arrayCtorRef);

        for (Map.Entry<String, Object> e : cases.entrySet())
            System.out.printf("  %-58s -> %s%n", e.getKey(), e.getValue());
    }
}