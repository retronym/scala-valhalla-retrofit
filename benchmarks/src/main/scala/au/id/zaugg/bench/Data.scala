package au.id.zaugg.bench

import au.id.zaugg.valhalla.ValueClass

// Two identically-shaped case classes. Under the load-time agent (valueclass
// mode) only the annotated one is promoted to a JEP 401 value class; the other
// stays an ordinary identity/reference class. The benchmarks below construct and
// use them in the same forked JVM, so the comparison is apples-to-apples.

@ValueClass final case class VPoint(x: Int, y: Int) // -> value class (promoted)
final case class RPoint(x: Int, y: Int)             // -> reference class (unchanged)
