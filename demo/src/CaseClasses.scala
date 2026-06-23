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
