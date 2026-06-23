package au.id.zaugg.strictinit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static au.id.zaugg.strictinit.StrictInitTransformer.ACC_STRICT_INIT;
import static au.id.zaugg.strictinit.StrictInitTransformer.PREVIEW_MAJOR;
import static au.id.zaugg.strictinit.StrictInitTransformer.PREVIEW_MINOR;
import static au.id.zaugg.strictinit.StrictInitTransformer.key;

/**
 * Phase 1: promote an <em>erased</em> Scala {@code extends AnyVal} value class to
 * a real JEP 401 {@code value class}.
 *
 * <p>The rewrite, on a class that passes both detection and eligibility:
 * <ul>
 *   <li>clear {@code ACC_IDENTITY} (0x0020) on the class — that bit's absence is
 *       what marks a class a value class (verified: a javac value class is
 *       {@code 0x0010 ACC_FINAL}, a normal class {@code 0x0021 ACC_SUPER});</li>
 *   <li>ensure the class is {@code final} (concrete value classes are);</li>
 *   <li>mark the single underlying field {@code ACC_STRICT_INIT} + {@code final}
 *       (reusing {@link StrictInitTransformer}'s sound pre-super analysis to
 *       confirm it is set before {@code super()});</li>
 *   <li>stamp the preview minor (0xFFFF) and required major.</li>
 * </ul>
 *
 * <p>Every instruction, attribute and StackMapTable byte is preserved — the
 * {@code AnyVal} constructor already writes the field before {@code super()},
 * which is exactly the value-class invariant.
 *
 * <h2>Detection (AnyVal is not a real superclass after erasure)</h2>
 * A nominal {@code superName == "scala/AnyVal"} check never fires; the backend
 * erases {@code AnyVal} away (super becomes {@code java/lang/Object} and the name
 * survives nowhere). Detection is structural, keyed on the SIP-15 {@code
 * $extension} scheme: every operation of an {@code AnyVal} class becomes a
 * {@code public static name$extension(<underlying>, ...)} whose first parameter
 * is the underlying field's type. See {@code docs/value-class-mapping.md}.
 *
 * <h2>Eligibility (non-problematic underlying types)</h2>
 * Floating-point underlyings ({@code float}/{@code double}) are gated out by
 * default: value {@code ==}/acmp is bitwise substitutability, which inverts
 * {@code NaN} and signed-zero equality relative to Scala numeric {@code ==}.
 * They can be opted in explicitly.
 */
public final class ValueClassTransformer {

    /** {@code ACC_IDENTITY} / legacy {@code ACC_SUPER}; its <em>absence</em>
     *  marks a value class. */
    public static final int ACC_IDENTITY = 0x0020;

    private final boolean allowFloating;
    private final StrictInitTransformer strict = new StrictInitTransformer(false);

    public ValueClassTransformer(boolean allowFloating) {
        this.allowFloating = allowFloating;
    }

    public record Result(byte[] bytes, String report) {
        public boolean changed() { return bytes != null; }
    }

    public Result transform(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);

        FieldNode underlying = detectAnyVal(cn);
        if (underlying == null) return new Result(null, null);

        String why = ineligibilityReason(cn, underlying);
        if (why != null) {
            return new Result(null, "skip value-class " + cn.name + " (" + why + ")");
        }

        // Mark the field strict+final and clear identity.
        underlying.access |= ACC_STRICT_INIT | Opcodes.ACC_FINAL;
        cn.access &= ~ACC_IDENTITY;     // <- the value-class marker
        cn.access |= Opcodes.ACC_FINAL; // concrete value classes are final
        cn.version = (PREVIEW_MINOR << 16) | PREVIEW_MAJOR;

        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);

        String report = cn.name + "  -> value class (cleared ACC_IDENTITY); "
                + "strict underlying " + underlying.name + ":" + underlying.desc
                + "; minor=0x" + Integer.toHexString(PREVIEW_MINOR) + " major=" + PREVIEW_MAJOR;
        return new Result(cw.toByteArray(), report);
    }

    // ------------------------------------------------------------------
    // Detection: the SIP-15 $extension fingerprint of an erased AnyVal class.
    // Returns the single underlying instance field, or null if not an AnyVal.
    // ------------------------------------------------------------------
    FieldNode detectAnyVal(ClassNode cn) {
        // Must erase to Object and be final.
        if (!"java/lang/Object".equals(cn.superName)) return null;
        if ((cn.access & Opcodes.ACC_FINAL) == 0) return null;

        // Exactly one instance field.
        List<FieldNode> instanceFields = new ArrayList<>();
        for (FieldNode f : cn.fields) {
            if ((f.access & Opcodes.ACC_STATIC) == 0) instanceFields.add(f);
        }
        if (instanceFields.size() != 1) return null;
        FieldNode underlying = instanceFields.get(0);
        if ((underlying.access & Opcodes.ACC_FINAL) == 0) return null;

        // At least one `name$extension` static whose first param == underlying type.
        String firstParam = "(" + underlying.desc;
        boolean hasExtension = false;
        for (MethodNode m : cn.methods) {
            if (m.name.endsWith("$extension")
                    && (m.access & Opcodes.ACC_STATIC) != 0
                    && m.desc.startsWith(firstParam)) {
                hasExtension = true;
                break;
            }
        }
        if (!hasExtension) return null;

        return underlying;
    }

    // ------------------------------------------------------------------
    // Eligibility: returns a human reason string if NOT eligible, else null.
    // Detection is necessary but not sufficient (a mis-promotion silently
    // imposes value semantics), so these are separate gates.
    // ------------------------------------------------------------------
    String ineligibilityReason(ClassNode cn, FieldNode underlying) {
        // Floating-point underlying flips NaN / signed-zero equality under acmp.
        if (!allowFloating && (underlying.desc.equals("D") || underlying.desc.equals("F"))) {
            return "floating-point underlying " + underlying.desc + "; pass allow-floating to override";
        }
        // The underlying must be provably set before super() so the value-class
        // verifier accepts it -- reuse the strict-init instance analysis.
        StrictInitTransformer.Selection sel = strict.analyze(cn);
        if (!sel.instanceFields().contains(key(underlying.name, underlying.desc))) {
            return "underlying not provably set before super()";
        }
        return null;
    }

    public boolean allowFloating() { return allowFloating; }
}
