public class RunStrict { public static void main(String[] a) throws Exception {
  Class.forName("BadStrict").getDeclaredConstructor().newInstance();
  System.out.println("BadStrict: UNEXPECTED success"); } }
