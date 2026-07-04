# lambda-cracker

[Releases](https://github.com/retrtheonym/lambda-cracker/releases) · [Apache License 2.0](LICENSE)

A `-javaagent` that gives JVM lambdas a useful `toString`: where the lambda was born
(source file, line, enclosing method), what it does (a compact rendering of its
implementation method, recovered from the classfile), and its captured state — turning

```
demo.Demo$$Lambda/0x00000008012ab440@4f3f5b24
```

into

```
PriceEngine.scala:4 PriceEngine.discounted { p => p * (1.0 - this.rate) } [this=PriceEngine(retail)]
```

everywhere `toString` gets called: debugger variable views, log statements, test
failure messages, collection dumps. See [DESIGN.md](DESIGN.md) for the problem
statement, the interception approach (hooking `MethodHandles.Lookup`'s hidden-class
definer, since lambda proxies are hidden classes and never reach a
`ClassFileTransformer`), the rendering tiers, and the phase plan. This repo is at
Phase 1: origin + best-effort body reconstruction, no decompiler yet.

## ⚠️ Dev/debug use only — do not run this in production

- **It leaks source.** The rendered body is reconstructed from your actual bytecode —
  literals, string concatenations, field/method names, and (for the agent path) captured
  values all show up in the `toString`. Anything a lambda closes over — a password, a
  token, a customer record — can end up in a log line, a crash report, or a debugger
  screenshot that leaves your control. Don't attach the agent to a process handling
  sensitive data.
- **It's slower than the default `toString`.** Body reconstruction and classfile parsing
  are cached per lambda class, but the first call per site pays real cost (parsing the
  owner's classfile, walking the impl method's bytecode), and every call still does
  reflection to read captured values. Fine for a debugging session; not something to leave
  wired into a hot logging path.
- **It changes lambda `toString()` output for every lambda in the JVM, with no opt-out
  yet.** Code (yours or a dependency's) that pattern-matches, truncates, or otherwise
  depends on the default `Object@hash` shape — including test assertions and golden files
  — can break. DESIGN.md's "Configuration" section plans package filters and a kill switch
  for this; neither is implemented yet, so today the only mitigation is not attaching the
  agent to that process.
- **Retransforming `java.lang.invoke.MethodHandles$Lookup` at startup** touches JDK
  internals via `Instrumentation`. It's designed to degrade safely (any failure falls back
  to the default string, never crashes the app), but it's still reaching into machinery
  every lambda in the process depends on.

None of this applies to normal `LambdaCracker.describe(lambda)` [library mode](#library-mode)
calls beyond the source-leaking and cost concerns above — it doesn't touch JVM-wide
`toString` behavior at all, since it only inspects the one lambda you hand it.

## Try it

```
sbt demo/run
```

builds the agent, attaches it to a small demo app via `-javaagent`, and prints a zoo
of Java and Scala lambdas — closures, method/constructor references, curried and
multi-statement bodies — labeled with what each one is demonstrating.

```
sbt "demo/runMain demo.JavaApp"
```

runs a pure-Java entry point (no Scala class on the call path) that exercises the same
Java lambda zoo plus a dedicated tour of every JLS 15.13 method-reference kind — static,
unbound instance, bound instance, constructor, array constructor.

## Library mode

No agent, no rewritten `toString`: add the agent jar as a plain dependency and call
`LambdaCracker.describe(lambda)` to introspect one lambda on demand. It returns a
`LambdaInfo` record (source file, line, enclosing class/method, params, body, captures)
whose `toString()` matches the agent's own rendering exactly, since both share the same
engine. The catch: without the agent's spin-time hook, the only supported way to recover
which method a lambda delegates to is `SerializedLambda`, which requires the lambda's
functional interface to be `Serializable` — true of every Scala `FunctionN` by default,
and available in Java via an explicit `(Foo & Serializable)` cast. Anything else degrades
to `LambdaInfo.resolved() == false` with a JDK-default-shaped fallback string, never a
crash. See [DESIGN.md](DESIGN.md#6-library-mode-same-engine-a-different-way-to-find-the-impl-method)
for why.

```java
LambdaInfo info = LambdaCracker.describe(someLambda);
System.out.println(info.enclosingClass() + "." + info.enclosingMethod() + ":" + info.line());
```

The demo's "Library mode" section below exercises both the Serializable-lambda path and
the graceful-degradation path, and shows two instances from the same lambda site sharing
cached site info while their captured values render independently.

## Example output

Regenerate this section after touching the agent or the demo with:

```
./scripts/update-readme.sh
```

<!-- DEMO_OUTPUT:START -->
```
-- Scala 3 --
  single-expression closure over a local val                       -> Demo.scala:14 Demo { p => p * (1.0 - rate$1) } [rate$1=0.15]
  two-arg lambda, arithmetic body                                  -> Demo.scala:15 Demo { (x, y) => x + y * 2 }
  boolean-returning predicate (comparison bails to bytecode)       -> Demo.scala:16 Demo { s => «bytecode» L0:; aload_0 s; invokevirtual String.length; ldc 3; if_icmple L1; ldc 1; goto L2; L1:; ldc 0; L2:; ireturn; L3: }
  string interpolation body                                        -> Demo.scala:17 Demo { s => new StringBuilder(8).append("hello, ").append(s).append("!").toString }
  zero-arg supplier                                                -> Demo.scala:18 Demo { () => 42 }
  Runnable / side-effecting lambda                                 -> Demo.scala:19 Demo { () => println("side effect") }
  instance method turned into a function value                     -> Demo.scala:6 PriceEngine.discounted { p => p * (1.0 - this.rate) } [this=PriceEngine(retail)]
  curried lambda, outer function                                   -> Demo.scala:22 Demo.adapted { x => Demo.$anonfun$7(x) }
  curried lambda, applied once (captures x=1)                      -> Demo.scala:22 Demo.7$$anonfun { y => x$1 + y } [x$1=1]
  multi-statement body (bails to bytecode)                         -> Demo.scala:24 Demo { x => «bytecode» L0:; iload_1 x; ldc 2; imul; istore_2 doubled; L1:; iload_2 doubled; iload_0 offset$1; iadd; ireturn; L2: } [offset$1=7]
  if/else body — hard case (bails to bytecode)                     -> Demo.scala:27 Demo { s => «bytecode» L0:; aload_0 s; invokevirtual String.isEmpty; ifeq L1; ldc "empty"; areturn; L1:; ldc "non-empty"; areturn; L2: }
-- Java --
  single-expression closure over a local variable                  -> JavaDemo.java:30 JavaDemo.run { x => x + offset } [offset=7]
  boolean predicate (comparison bails to bytecode)                 -> JavaDemo.java:31 JavaDemo.run { s => «bytecode» L0:; aload_0 s; invokevirtual String.length; ldc 3; if_icmple L1; ldc 1; goto L2; L1:; ldc 0; L2:; ireturn; L3: }
  zero-arg supplier, constant body                                 -> JavaDemo.java:32 JavaDemo.run { () => "constant" }
  closure over a local variable, string concat                     -> JavaDemo.java:33 JavaDemo.run { s => tag + ":" + s } [tag=prod]
  unbound instance method reference                                -> String::length
  static method reference                                          -> Math::max
  constructor reference                                            -> ArrayList::new
  multi-statement Runnable (bails to bytecode)                     -> JavaDemo.java:37 JavaDemo.run { () => «bytecode» L0:; iload_0 offset; ldc 2; imul; istore_1 a; L1:; getstatic System.out; iload_1 a; invokevirtual PrintStream.println; return; L2: } [offset=7]
  loop body — hard case (bails to bytecode)                        -> JavaDemo.java:39 JavaDemo.run { n => «bytecode» L0:; ldc 1; istore_1 r; L1:; ldc 2; istore_2 i; L2:; iload_2 i; aload_0 n; invokevirtual Integer.intValue; if_icmpgt L3; iload_1 r; iload_2 i; imul; istore_1 r… }
-- Library mode (no agent, no rewritten toString) --
  LambdaCracker.describe on an explicitly Serializable lambda      -> JavaDemo.java:59 JavaDemo.run { x => x + offset } [offset=7]
  non-Serializable lambda degrades gracefully, no crash            -> demo.JavaDemo$$Lambda/0x000001e0010bda80@1efed156 (resolved=false)
-- Library mode (no agent, no rewritten toString) --
  LambdaCracker.describe(f), matches the agent's own rendering     -> Demo.scala:14 Demo { p => p * (1.0 - rate$1) } [rate$1=0.15]
  ...as a structured object, not just a string                     -> enclosingClass=Demo enclosingMethod= line=14 params=p
  same lambda site, first instance (captures n=1)                  -> Demo.scala:60 Demo.adder$1 { x => x + n$1 } [n$1=1]
  same lambda site, second instance (captures n=2)                 -> Demo.scala:60 Demo.adder$1 { x => x + n$1 } [n$1=2]
-- Method references, every JLS 15.13 kind --
  static method reference (Math::abs)                        -> Math::abs
  unbound instance ref, 1-arg (String::length)               -> String::length
  unbound instance ref, 2-arg (String::startsWith)           -> String::startsWith
  bound instance ref, literal receiver ("hello"::length)     -> String::length [arg$1=hello]
  bound instance ref, captured receiver (ada::greet)         -> JavaDemo.Greeter::greet [arg$1=Greeter(Ada)]
  constructor reference (ArrayList::new)                     -> ArrayList::new
  array constructor reference (int[]::new)                   -> JavaDemo.java:78 JavaDemo.methodReferences { x$0 => «bytecode» L0:; iload_0 x$0; newarray; areturn; L1: }
```
<!-- DEMO_OUTPUT:END -->

## License

[Apache License 2.0](LICENSE).
