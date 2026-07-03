# lambda-cracker — design

A tool that gives JVM lambdas a useful `toString`: where the lambda was born (source file, line, enclosing method) and what it does (a compact rendering of its implementation method, recovered from the classfile), plus its captured state. Two deployment modes share one analysis engine: a `-javaagent` that rewrites every lambda's `toString` in place, and a plain library call (`LambdaCracker.describe(lambda)`) that returns the same information as a structured, introspectable object — no agent, no bytecode rewriting.

## Problem

Lambdas are opaque at runtime:

```
scala.collection.IterableOps$$Lambda/0x00000008012ab440@4f3f5b24
```

This string appears everywhere debuggability matters: debugger variable views (IntelliJ/jdb render values via `toString`), log statements, test failure messages, collection dumps, actor/callback registries. When a `Map[String, () => Response]` misbehaves, you can see *that* it holds five thunks but not which ones. Scala makes this worse than Java: heavy use of first-class functions, compiler-mangled names (`$anonfun$validate$3`), and specialized variants.

The information to fix this already exists on disk. The lambda proxy class knows exactly which implementation method it delegates to; that method's classfile carries a `LineNumberTable`, a `SourceFile` attribute, and the full bytecode of the body. Nobody joins these up at runtime. This agent does.

Target rendering (format details below):

```
PriceEngine.scala:42 PriceEngine.discounted { p => p.amount * (1.0 - this.rate) } [this=PriceEngine(retail)]
```

Because `toString` is the universal rendering hook, this improves debuggers, logs, and assertions in one move — no IDE plugin, no source changes, no recompile.

## Goals / non-goals

**Goals**
- Every LambdaMetafactory-produced lambda (Java, Scala 2.12+, Scala 3, Kotlin) gets an informative `toString`.
- Origin (file:line, enclosing class/method) is always available; body rendering and captured state are best-effort with graceful fallback.
- Near-zero steady-state overhead: all cost is at lambda class spin time (one injected method + one string field) and lazily on first `toString` call, cached per class.
- Never break the app: rendering failures fall back to something at least as good as the default string.
- Usable without an agent: the same analysis, returned as data, for callers who want to introspect a specific lambda rather than instrument every `toString` in the JVM.

**Non-goals**
- Perfect decompilation. The body rendering is a debugging aid, not source recovery.
- IDE-specific renderers, JDWP integration, method-reference receivers, composed-function unfolding (`f andThen g`) — future work.
- Pre-2.12 Scala anonymous-class lambdas — phase 4 option, different mechanism.

## Background: how lambdas materialize

`invokedynamic` → `LambdaMetafactory` → `InnerClassLambdaMetafactory` spins the proxy class bytes and defines them as a **hidden class**. Two consequences drive the whole design:

1. Hidden classes never pass through `ClassFileTransformer` — we cannot instrument the lambda class after the fact.
2. The spinner *does* have everything we want at generation time: the `implMethod` handle (owner, name, descriptor), the functional interface, and the captured-arg field layout.

So the one viable, cheap interception point is the spinner itself.

## Key decisions

### 1. Intercept at `InnerClassLambdaMetafactory`, by retransforming that one JDK class

The agent retransforms `java.lang.invoke.InnerClassLambdaMetafactory` so that, just before the proxy bytes are defined, they pass through a hook: `LambdaCrackerHook.augment(byte[] proxyBytes, MethodHandleInfo implInfo): byte[]`. The hook (agent jar is appended to the bootstrap classloader search, so it's visible from `java.lang.invoke`) uses the Classfile API to add two things to the proxy class:

- a private static final `String $lambdaImpl` field holding the impl method coordinates (`pkg/Owner#name(desc)`), and
- a `toString()` override that calls `LambdaCrackerRuntime.render(this)` — unless the functional interface itself declares `toString` semantics we'd be trampling (it can't; `toString` is an `Object` method, so the proxy never had a real override to conflict with).

**Rejected alternatives:**
- *Rewrite `invokedynamic` call sites to a custom bootstrap* — touches every user class, breaks the JDK's lambda class caching, fights CDS/AOT, and has fragile ordering. All pain, same result.
- *Transform the lambda classes directly* — impossible; hidden classes bypass transformers.
- *Wrap lambdas in a decorating proxy* — breaks reference identity, adds a megamorphic indirection on the hot path, and loses marker interfaces (Serializable, Scala's specialized `JFunction1$mcII$sp` bridges).
- *`jdk.internal.lambda.dumpProxyClasses`* — dump-only, no injection hook.

### 2. Injection is dumb, rendering is lazy

The spin-time hook injects only the marker field and a trampoline `toString`. All expensive work — locating the owner classfile, parsing `LineNumberTable`/`SourceFile`, reconstructing the body — happens in `LambdaCrackerRuntime.render` on first call, memoized per lambda class via a `ClassValue<LambdaDescription>`. Lambdas that are never printed cost one string field and ~30 bytes of extra bytecode.

`render` resolves the impl method's owner classfile via `owner.getClassLoader().getResourceAsStream("Owner.class")` — the bytes the JIT saw, not stale source. If the resource isn't available (repackaged/obfuscated jars), degrade to origin-from-name only.

### 3. Three rendering tiers, each a fallback for the one above

1. **Origin** (always, needs only the marker field + name demangling): source file, line of the impl method's first `LineNumberTable` entry, enclosing class and demangled method name.
2. **Body reconstruction** (default): a small symbolic interpreter over the impl method's `CodeModel` — simulate the operand stack for straight-line code, folding loads/getfields/invokes/arithmetic/boxing into an expression tree, printed as pseudo-source. Bail out on branches, loops, stores, or other unhandled shapes and print a compact textified disassembly instead, e.g. `{ n => «bytecode» L0:; ldc 1; istore_1 r; L1:; iload_2 i; ...}` (length-capped) — never a guess at what the branch does, just the honest bytecode.
3. **Full decompilation** (opt-in flag): delegate the impl method to Vineflower for real decompiled source. Best-in-class library over hand-rolling, but too heavy a dependency to be the default — it's an optional add-on jar.

Most Scala lambdas are single expressions, so tier 2 covers the common case honestly and cheaply; the interpreter refuses to guess rather than print wrong code.

### 4. Captured state is shown, defensively

Spun proxies hold captures in fields named `arg$1..arg$N`; the impl method descriptor gives their meaning (leading parameters). Render them as `[name=value]` where the name comes from the impl method's `MethodParameters`/local-variable info when present, falling back to `arg$N`. Rendering captured values calls *their* `toString` — which may itself be a cracked lambda — so: depth limit, identity-set cycle guard, per-value try/catch, length cap. Any `Throwable` anywhere in `render` degrades to the origin tier, and failing that to the JDK-default-shaped string. This invariant — **`toString` never throws and never recurses unboundedly** — is the load-bearing safety property of the whole tool.

### 5. Scala name demangling is a first-class concern

The impl method name is the origin key and needs per-compiler demangling:

- Java: `lambda$doThing$0` → enclosing method `doThing`.
- Scala 2.12/2.13: `$anonfun$discounted$1` (static, `$this` first capture for instance context).
- Scala 3: `$anonfun$N` / plain private methods; enclosing method recovered from `LineNumberTable` correlation against the owner's methods when the name doesn't encode it.
- Specialized interfaces (`JFunction1$mcDD$sp` etc.) and `LambdaDeserialize`-spun instances go through the same spinner — covered for free, but the demangler must strip specialization suffixes when naming the functional interface.

Demangling is table-driven and tested against classfiles emitted by each compiler version, not regex-and-hope.

### 6. Library mode: same engine, a different way to find the impl method

`LambdaCracker.describe(Object lambda)` (`io.github.retronym.lambdacracker.LambdaCracker`, a plain classpath dependency, no `-javaagent`) returns a `LambdaInfo` record exposing the same fields the agent renders into a string — source file, line, enclosing class/method, params, body, captures — plus a `toString()` that matches the agent's format exactly, since both call into the same `LambdaCrackerRuntime`/`Description` engine underneath.

The two modes differ only in how they learn the impl method's coordinates (owner/name/descriptor), because that's the one piece of information the agent gets for free at spin time (from the about-to-be-hidden proxy's own bytecode) that's otherwise unrecoverable — hidden classes have no retrievable bytes once defined, and there's no supported reflective API to ask an arbitrary lambda instance what it delegates to.

The one other channel the JDK exposes is `java.lang.invoke.SerializedLambda`: if a lambda's functional interface is `Serializable`, its proxy class carries a synthetic `writeReplace()` that hands back a `SerializedLambda` with exactly the coordinates we need (`getImplClass`/`getImplMethodName`/`getImplMethodSignature`/`getImplMethodKind`), plus the captured arguments themselves — no `arg$N` field reflection required. Every Scala `FunctionN` is `Serializable` by design, so this covers Scala lambdas for free; a Java lambda needs an explicit `(Foo & Serializable)` cast, since `java.util.function.*` isn't `Serializable` by default. Lambdas that aren't serializable can't be introspected this way at all without the agent — `describe` degrades to `LambdaInfo.resolved() == false` with a JDK-default-shaped fallback string, the same invariant as the agent's own tier-0 fallback.

One consequence worth calling out: javac hardens serializable-lambda impl-method names against deserialization-gadget forgery by embedding a content hash (`lambda$run$ccaa6a4a$1` instead of `lambda$run$0`); the demangler strips that hash segment specifically for the `lambda$` naming scheme, since it's only ever present on the serializable path library mode depends on.

Both entry points share one cache keyed by lambda class (`ConcurrentHashMap<Class<?>, Description>`), so calling `describe` repeatedly for lambdas from the same call site only re-reads captured values — the classfile parsing and body reconstruction happen once per site, exactly as for the agent's injected `toString`.

## Rendering format

```
<SourceFile>:<line> <EnclosingClass>.<enclosingMethod> { <params> => <body> } [<capture>=<value>, …]
```

- Origin-only fallback: `PriceEngine.scala:42 PriceEngine.discounted [rate=0.15]`
- Opaque-body fallback: body replaced by `…` or a textified-bytecode disassembly.
- Total length capped (configurable, default ~200 chars) — debugger variable panes truncate anyway.
- The default identity hash is preserved in tier-0 fallback so nothing gets *worse* than status quo.

## Configuration

System properties, all `lambdacracker.*`: package include/exclude filters (default: exclude `jdk.*`, `java.*`, `sun.*`, `scala.collection.*` internals opt-in), rendering tier, capture rendering on/off, max length, `dump` (log every description once, for eyeballing coverage). Filtering happens at spin time — excluded lambdas get no injection at all, so the opt-out is total.

## Risks and mitigations

- **AOT/CDS-archived lambda proxies (JEP 483, JDK 24+) skip the spinner entirely.** Those instances keep the default `toString`. Detect at startup (archived-class stats) and log a one-line warning suggesting the cache be disabled for debug runs. Acceptable: this is a debugging agent, not production furniture.
- **Retransforming `java.lang.invoke` during startup.** The spinner is a normal (non-hidden) class and retransform-capable, but the patch must be minimal — insert one static call, no new dependencies reachable during `<clinit>` of the invoke machinery. Lambdas spun before `premain` completes stay plain; they're JDK-internal and filtered out anyway.
- **Tests asserting on `toString`.** Real risk in Scala codebases (`<function1>` shows up in golden files). Mitigation: package filters, and a `lambdacracker.enabled=false` kill switch; the agent is opt-in per JVM by nature.
- **Serializable lambdas** (`altMetafactory`): same spinner path, covered; the injected members are synthetic and don't perturb `writeReplace`.
- **Memory**: one interned coordinates string per lambda class, one cached description per *printed* lambda class. Bounded and small.

## Prior art

- Kotlin's IntelliJ debugger renders lambdas with origin info — proof of value, but IDE-locked and Kotlin-only.
- `jdk.internal.lambda.dumpProxyClasses` — the JDK acknowledges the observability gap but only offers offline dumping.
- Vineflower / CFR — mature decompilers; tier 3 reuses rather than reimplements.
- async3's "lambda cracking" — this project extracts that idea into a standalone, agent-first utility.

## Plan

- [ ] **Phase 1 — skeleton + origin tier.** Agent bootstrap, spinner retransform, marker-field + `toString` injection, origin rendering with Java + Scala 2.13/3 demangling, capture rendering, filters, kill switch. Manual integration test: a demo app (Java + Scala) printing a zoo of lambdas before/after.
- [ ] **Phase 2 — body reconstruction.** Classfile API symbolic interpreter, straight-line expression printing, bail-out summaries. Golden-file tests against compiler-emitted classfiles.
- [ ] **Phase 3 — Scala polish.** Specialized variants, `LambdaDeserialize`, nested/enclosing-method recovery in Scala 3, demangling table tests across 2.12/2.13/3.
- [ ] **Phase 4 (future work)** — Vineflower tier, pre-2.12 anonymous-class lambdas via ordinary `ClassFileTransformer`, composed-function unfolding (`andThen`/`compose`), method-reference receiver rendering, JFR event per spun lambda, animated demo page.

## Open questions

1. Should tier 2 attempt local variable *names* from the debug info of the impl method (present under `-g`, absent in most published jars), or always use positional `p1, p2`? Proposal: use names when present, positional otherwise.
2. Enhance lambdas inside scala-library itself (e.g. collection combinators) by default? They're noisy but occasionally the thing you're debugging. Proposal: excluded by default, one flag to include.
3. Java baseline: 25 (Classfile API stable, matches your other projects) — any need to run the *agent* on older target JVMs, which would force ASM instead?
