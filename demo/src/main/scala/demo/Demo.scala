package demo

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

    val cases = List(
      "single-expression closure over a local val" -> f,
      "two-arg lambda, arithmetic body" -> g,
      "boolean-returning predicate (comparison bails to call summary)" -> pred,
      "string interpolation body" -> greet,
      "zero-arg supplier" -> thunk,
      "Runnable / side-effecting lambda" -> effect,
      "instance method turned into a function value" -> h,
      "curried lambda, outer function" -> nested,
      "curried lambda, applied once (captures x=1)" -> nested(1),
      "multi-statement body (bails to call summary)" -> multiStatement,
    )

    println("-- Scala 3 --")
    for (desc, fn) <- cases do println(f"  $desc%-64s -> $fn")

    println("-- Java --")
    JavaDemo.run()