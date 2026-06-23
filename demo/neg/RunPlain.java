public class RunPlain { public static void main(String[] a) throws Exception {
  Class.forName("BadPlain").getDeclaredConstructor().newInstance();
  System.out.println("BadPlain: loaded + constructed OK (field set after super is legal when NOT strict)"); } }
