package au.id.zaugg.bench

import au.id.zaugg.valhalla.ValueClass

// Two identically-shaped case classes. Under the load-time agent (valueclass
// mode) only the annotated one is promoted to a JEP 401 value class; the other
// stays an ordinary identity/reference class. The benchmarks below construct and
// use them in the same forked JVM, so the comparison is apples-to-apples.

@ValueClass final case class VPoint(x: Int, y: Int) // -> value class (promoted)
final case class RPoint(x: Int, y: Int)             // -> reference class (unchanged)

// --- Immutable-cursor idiom (from the JEP 401 JDK PR) ---------------------
//
// A cursor is an immutable reference to a position in a collection. Iteration
// advances by producing a *new* cursor (`cursor = cursor.advance()`) rather than
// mutating one in place. That is only practical when the cursor is a value class:
// the per-advance "allocation" then scalarizes away. As an ordinary reference
// class the same idiom allocates a fresh cursor on every step.
//
//   for (var c = list.cursor(); c.exists(); c = c.advance()) sum += c.get();

trait IntCursor {
  def exists: Boolean
  def get: Int
  def advance: IntCursor
}

// `advance` is overridden with a covariant concrete return type so that, used via
// `var` (the PR's idiom), the hot loop stays monomorphic and the cursor can be
// scalarized instead of boxed to the interface type.
@ValueClass final case class VCursor(a: Array[Int], i: Int) extends IntCursor {
  def exists: Boolean = i < a.length
  def get: Int = a(i)
  override def advance: VCursor = VCursor(a, i + 1) // -> value class (promoted)
}

final case class RCursor(a: Array[Int], i: Int) extends IntCursor {
  def exists: Boolean = i < a.length
  def get: Int = a(i)
  override def advance: RCursor = RCursor(a, i + 1) // -> reference class (unchanged)
}

// Classic mutable iterator baseline: one allocation per traversal, mutated in
// place. Not a value-class candidate (has a `var`).
final class MutIntIterator(a: Array[Int]) {
  private var i = 0
  def hasNext: Boolean = i < a.length
  def next(): Int = { val v = a(i); i += 1; v }
}
