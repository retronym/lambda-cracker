# lambda-cracker

[Releases](https://github.com/retronym/lambda-cracker/releases) · [Apache License 2.0](LICENSE)

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

## Try it

```
sbt demo/run
```

builds the agent, attaches it to a small demo app via `-javaagent`, and prints a zoo
of Java and Scala lambdas — closures, method/constructor references, curried and
multi-statement bodies — labeled with what each one is demonstrating.

## Example output

Regenerate this section after touching the agent or the demo with:

```
./scripts/update-readme.sh
```

<!-- DEMO_OUTPUT:START -->
```
-- Scala 3 --
  single-expression closure over a local val                       -> Demo.scala:12 Demo { p => p * (1.0 - rate$1) } [rate$1=0.15]
  two-arg lambda, arithmetic body                                  -> Demo.scala:13 Demo { (x, y) => x + y * 2 }
  boolean-returning predicate (comparison bails to bytecode)       -> Demo.scala:14 Demo { s => «bytecode» L0:; aload_0 s; invokevirtual String.length; ldc 3; if_icmple L1; ldc 1; goto L2; L1:; ldc 0; L2:; ireturn; L3: }
  string interpolation body                                        -> Demo.scala:15 Demo { s => new StringBuilder(8).append("hello, ").append(s).append("!").toString }
  zero-arg supplier                                                -> Demo.scala:16 Demo { () => 42 }
  Runnable / side-effecting lambda                                 -> Demo.scala:17 Demo { () => println("side effect") }
  instance method turned into a function value                     -> Demo.scala:4 PriceEngine.discounted { p => p * (1.0 - this.rate) } [this=PriceEngine(retail)]
  curried lambda, outer function                                   -> Demo.scala:20 Demo.adapted { x => Demo.$anonfun$7(x) }
  curried lambda, applied once (captures x=1)                      -> Demo.scala:20 Demo.7$$anonfun { y => x$1 + y } [x$1=1]
  multi-statement body (bails to bytecode)                         -> Demo.scala:22 Demo { x => «bytecode» L0:; iload_1 x; ldc 2; imul; istore_2 doubled; L1:; iload_2 doubled; iload_0 offset$1; iadd; ireturn; L2: } [offset$1=7]
  if/else body — hard case (bails to bytecode)                     -> Demo.scala:25 Demo { s => «bytecode» L0:; aload_0 s; invokevirtual String.isEmpty; ifeq L1; ldc "empty"; areturn; L1:; ldc "non-empty"; areturn; L2: }
-- Java --
  single-expression closure over a local variable                  -> JavaDemo.java:15 JavaDemo.run { x => x + offset } [offset=7]
  boolean predicate (comparison bails to bytecode)                 -> JavaDemo.java:16 JavaDemo.run { s => «bytecode» L0:; aload_0 s; invokevirtual String.length; ldc 3; if_icmple L1; ldc 1; goto L2; L1:; ldc 0; L2:; ireturn; L3: }
  zero-arg supplier, constant body                                 -> JavaDemo.java:17 JavaDemo.run { () => "constant" }
  closure over a local variable, string concat                     -> JavaDemo.java:18 JavaDemo.run { s => tag + ":" + s } [tag=prod]
  unbound instance method reference                                -> String::length
  static method reference                                          -> Number::intValue
  constructor reference                                            -> ArrayList::new
  multi-statement Runnable (bails to bytecode)                     -> JavaDemo.java:22 JavaDemo.run { () => «bytecode» L0:; iload_0 offset; ldc 2; imul; istore_1 a; L1:; getstatic System.out; iload_1 a; invokevirtual PrintStream.println; return; L2: } [offset=7]
  loop body — hard case (bails to bytecode)                        -> JavaDemo.java:24 JavaDemo.run { n => «bytecode» L0:; ldc 1; istore_1 r; L1:; ldc 2; istore_2 i; L2:; iload_2 i; aload_0 n; invokevirtual Integer.intValue; if_icmpgt L3; iload_1 r; iload_2 i; imul; istore_1 r… }
```
<!-- DEMO_OUTPUT:END -->

## License

[Apache License 2.0](LICENSE).
