// Compiled against the ORIGINAL (identity) classes, run against the PROMOTED
// (value class) ones. For a promoted multi-field value class, `==` (acmp)
// compares field state, so distinct equal allocations are ==; the un-annotated
// class stays an identity class and they are not.
public class CCDriver {
  public static void main(String[] a) {
    Complex p = new Complex(1, 2), q = new Complex(1, 2), r = new Complex(3, 4);
    System.out.println("Complex(1,2) == Complex(1,2) [acmp]   : " + (p == q) + "   (value => state)");
    System.out.println("Complex(1,2) == Complex(3,4) [acmp]   : " + (p == r));
    System.out.println("Complex(1,2).equals(Complex(1,2))     : " + p.equals(q));
    System.out.println("re=" + p.re() + " im=" + p.im() + "  toString=" + p);

    Mixed m1 = new Mixed(7L, true), m2 = new Mixed(7L, true);
    System.out.println("Mixed(7,true) == Mixed(7,true) [acmp] : " + (m1 == m2));

    NotAnnotated n1 = new NotAnnotated(1, 2), n2 = new NotAnnotated(1, 2);
    System.out.println("NotAnnotated(1,2) == (1,2) [identity] : " + (n1 == n2) + "   (no opt-in => identity)");

    // value subclasses of an abstract @ValueClass value super class
    Shape s = new Circle(5);
    System.out.println("Circle(5) == Circle(5) [acmp]         : " + (new Circle(5) == new Circle(5))
        + "   (value subtype of abstract value Shape)");
    System.out.println("s instanceof Circle                   : " + (s instanceof Circle));
  }
}
