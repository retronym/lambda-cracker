package demo

import lambdacracker.LambdaCracker

class PriceEngine(val label: String, rate: Double):
  def discounted: Double => Double = p => p * (1.0 - rate)
  override def toString = s"PriceEngine($label)"

object Demo:
  def main(args: Array[String]): Unit =
    val rate = 0.15
    val offset = 7

    val f: Double => Double = p => p * (1.0 - rate)
    val g: (Int, Int) => Int = (x, y) => x + y * 2
    val pred: String => Boolean = s => s.length > 3
    val greet: String => String = s => s"hello, $s!"
    val thunk: () => Int = () => 42
    val effect: Runnable = () => println("side effect")
    val engine = new PriceEngine("retail", rate)
    val h = engine.discounted
    val nested: Int => Int => Int = x => y => x + y
    val multiStatement: Int => Int = x =>
      val doubled = x * 2
      doubled + offset
    val classify: String => String = s =>
      if s.isEmpty then "empty" else "non-empty"

    val cases = List(
      "single-expression closure over a local val" -> f,
      "two-arg lambda, arithmetic body" -> g,
      "boolean-returning predicate (comparison bails to bytecode)" -> pred,
      "string interpolation body" -> greet,
      "zero-arg supplier" -> thunk,
      "Runnable / side-effecting lambda" -> effect,
      "instance method turned into a function value" -> h,
      "curried lambda, outer function" -> nested,
      "curried lambda, applied once (captures x=1)" -> nested(1),
      "multi-statement body (bails to bytecode)" -> multiStatement,
      "if/else body — hard case (bails to bytecode)" -> classify,
    )

    def row(desc: String, value: Any): Unit = println(f"  $desc%-64s -> $value")

    println("-- Scala 3 --")
    for (desc, fn) <- cases do row(desc, fn)

    println("-- Java --")
    JavaDemo.run()

    println("-- Library mode (no agent, no rewritten toString) --")
    // Every Scala FunctionN is Serializable by default, so LambdaCracker.describe works on
    // plain lambdas with no cast needed — unlike Java, see JavaDemo.
    val info = LambdaCracker.describe(f)
    row("LambdaCracker.describe(f), matches the agent's own rendering", info)
    row("...as a structured object, not just a string",
      s"enclosingClass=${info.enclosingClass} enclosingMethod=${info.enclosingMethod} " +
        s"line=${info.line} params=${info.params}")

    def adder(n: Int): Int => Int = x => x + n
    val add1 = adder(1)
    val add2 = adder(2)
    row("same lambda site, first instance (captures n=1)", LambdaCracker.describe(add1))
    row("same lambda site, second instance (captures n=2)", LambdaCracker.describe(add2))