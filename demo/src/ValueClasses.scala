// Erased `extends AnyVal` value classes for Phase-1 promotion.
// UserId/Money are eligible (integral underlying); Ratio is gated out by
// default (Double underlying -> acmp inverts NaN / signed-zero vs numeric ==).

final class UserId(val raw: Int) extends AnyVal {
  def next: UserId = new UserId(raw + 1)
  def isAdmin: Boolean = raw == 0
}

final class Money(val cents: Long) extends AnyVal {
  def + (o: Money): Money = new Money(cents + o.cents)
}

final class Ratio(val value: Double) extends AnyVal {
  def reciprocal: Ratio = new Ratio(1.0 / value)
}
