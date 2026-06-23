// Compiled against the ORIGINAL (identity) classes, run against the PROMOTED
// (value class) ones. `==` is acmp: for a value class it compares field state,
// so two distinct allocations with equal fields are ==; for an identity class
// (gated Ratio) they are not.
public class VCDriver {
  public static void main(String[] a) {
    UserId p = new UserId(5), q = new UserId(5), r = new UserId(6);
    System.out.println("UserId(5) == UserId(5) [acmp] : " + (p == q) + "   (value => state equality)");
    System.out.println("UserId(5) == UserId(6) [acmp] : " + (p == r));
    System.out.println("p.raw()                       : " + p.raw());

    Money m1 = new Money(100L), m2 = new Money(100L);
    System.out.println("Money(100) == Money(100)      : " + (m1 == m2));

    Ratio g1 = new Ratio(1.5), g2 = new Ratio(1.5);
    System.out.println("Ratio(1.5) == Ratio(1.5) [id] : " + (g1 == g2) + "   (gated: still identity)");
  }
}
