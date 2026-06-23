import org.objectweb.asm.*;
import java.nio.file.*;
import static org.objectweb.asm.Opcodes.*;

public class Gen {
  // Build class `Bad` with one int field `x`. If strict, x must be set BEFORE
  // super(); we deliberately set it AFTER super() to violate strict-init.
  static byte[] make(String name, boolean strict) {
    ClassWriter cw = new ClassWriter(0);
    int minor = strict ? 0xFFFF : 0;
    int major = 71;
    cw.visit((minor << 16) | major, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
    int facc = ACC_PUBLIC | (strict ? 0x0800 : 0);
    cw.visitField(facc, "x", "I", null, null).visitEnd();
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); // super FIRST
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ICONST_5);
    mv.visitFieldInsn(PUTFIELD, name, "x", "I"); // then assign -- illegal if strict
    mv.visitInsn(RETURN);
    mv.visitMaxs(2, 1);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }
  public static void main(String[] a) throws Exception {
    Files.write(Path.of("demo/neg/BadStrict.class"), make("BadStrict", true));
    Files.write(Path.of("demo/neg/BadPlain.class"),  make("BadPlain", false));
    System.out.println("generated BadStrict (strict, set-after-super) and BadPlain (no strict bit)");
  }
}
