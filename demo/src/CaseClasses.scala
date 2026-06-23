// Phase 2: multi-field final case classes opting in to value-class translation
// via @ValueClass (from the valhalla-annotations runtime library).
import au.id.zaugg.valhalla.ValueClass

@ValueClass final case class Complex(re: Int, im: Int)
@ValueClass final case class Mixed(id: Long, flag: Boolean)

// Double-backed: gated out by default (acmp flips NaN / signed-zero vs ==)...
@ValueClass final case class WithDouble(x: Double, y: Double)
// ...unless explicitly opted in per-type.
@ValueClass(allowFloating = true) final case class Vec2(x: Double, y: Double)

// No annotation -> not promoted (stays an identity class); only strict-init'd.
final case class NotAnnotated(a: Int, b: Int)

// A value class may extend an abstract @ValueClass "value super class" (which
// JEP 401 requires to be stateless: zero instance fields).
@ValueClass sealed abstract class Shape
@ValueClass final case class Circle(r: Int) extends Shape
@ValueClass final case class Rect(w: Int, h: Int) extends Shape

// Excluded: superclass carries state and is not @ValueClass, so promoting the
// subclass would make a value class extend an identity class.
abstract class PlainParent(val base: Int)
@ValueClass final case class Derived(x: Int) extends PlainParent(x)
