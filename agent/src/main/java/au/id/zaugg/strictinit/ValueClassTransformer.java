package au.id.zaugg.strictinit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
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
 * Promote a Scala class to a JEP 401 {@code value class} by clearing
 * {@code ACC_IDENTITY} (0x0020) — a value class is exactly a class without that
 * bit (verified: a javac value class is {@code 0x0010 ACC_FINAL}, a normal class
 * {@code 0x0021 ACC_SUPER}) — marking its instance fields strict+final, and
 * stamping the preview classfile version. Instructions, attributes and
 * StackMapTables are preserved verbatim.
 *
 * <p>Two detection paths feed one rewrite:
 * <ul>
 *   <li><b>Phase 1 — erased {@code extends AnyVal}.</b> {@code AnyVal} is not a
 *       real superclass after erasure, so detection is structural via the
 *       SIP-15 {@code $extension} fingerprint (a {@code public static
 *       name$extension(<underlying>, ...)} whose first parameter is the single
 *       field's type).</li>
 *   <li><b>Phase 2 — {@code @au.id.zaugg.valhalla.ValueClass}.</b> An explicit
 *       opt-in marker for a final class of final fields (the canonical case
 *       being a {@code case class}), promoting <em>all</em> its instance
 *       fields. Opt-in because promotion drops identity and is not always
 *       sound to infer.</li>
 * </ul>
 *
 * <p>Detection is necessary but not sufficient: a class must also pass
 * eligibility (final, {@code Object} superclass, all fields final and provably
 * set before {@code super()}, no {@code float}/{@code double} unless opted in).
 * See {@code docs/value-class-mapping.md}.
 */
public final class ValueClassTransformer {

    /** {@code ACC_IDENTITY} / legacy {@code ACC_SUPER}; its <em>absence</em>
     *  marks a value class. */
    public static final int ACC_IDENTITY = 0x0020;

    /** Descriptor of the opt-in annotation, read by string to avoid coupling the
     *  agent jar to {@code valhalla-annotations}. */
    static final String VALUE_CLASS_DESC = "Lau/id/zaugg/valhalla/ValueClass;";

    private final boolean allowFloating;
    private final StrictInitTransformer strict = new StrictInitTransformer(false);

    public ValueClassTransformer(boolean allowFloating) {
        this.allowFloating = allowFloating;
    }

    public record Result(byte[] bytes, String report) {
        public boolean changed() { return bytes != null; }
    }

    /** A class detected as promotable, plus the fields to make strict. */
    private record Candidate(List<FieldNode> fields, String kind, boolean allowFloatingOverride) {}

    public Result transform(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0);

        Candidate c = detect(cn);
        if (c == null) return new Result(null, null);

        String why = ineligibilityReason(cn, c);
        if (why != null) {
            return new Result(null, "skip value-class " + cn.name + " [" + c.kind + "] (" + why + ")");
        }

        for (FieldNode f : c.fields) {
            f.access |= ACC_STRICT_INIT | Opcodes.ACC_FINAL;
        }
        cn.access &= ~ACC_IDENTITY;     // <- the value-class marker
        cn.access |= Opcodes.ACC_FINAL; // concrete value classes are final
        cn.version = (PREVIEW_MINOR << 16) | PREVIEW_MAJOR;

        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);

        List<String> names = new ArrayList<>();
        for (FieldNode f : c.fields) names.add(f.name + ":" + f.desc);
        String report = cn.name + "  -> value class [" + c.kind + "] (cleared ACC_IDENTITY); "
                + "strict fields " + names
                + "; minor=0x" + Integer.toHexString(PREVIEW_MINOR) + " major=" + PREVIEW_MAJOR;
        return new Result(cw.toByteArray(), report);
    }

    // ------------------------------------------------------------------
    // Detection: the @ValueClass opt-in (multi-field) takes precedence over the
    // AnyVal $extension fingerprint (single-field).
    // ------------------------------------------------------------------
    private Candidate detect(ClassNode cn) {
        AnnotationNode ann = findValueClassAnnotation(cn);
        if (ann != null) {
            return new Candidate(instanceFields(cn), "@ValueClass", readAllowFloating(ann));
        }
        FieldNode underlying = detectAnyVal(cn);
        if (underlying != null) {
            return new Candidate(List.of(underlying), "AnyVal", false);
        }
        return null;
    }

    private AnnotationNode findValueClassAnnotation(ClassNode cn) {
        List<AnnotationNode> anns = cn.visibleAnnotations;
        if (anns == null) return null;
        for (AnnotationNode a : anns) {
            if (VALUE_CLASS_DESC.equals(a.desc)) return a;
        }
        return null;
    }

    private boolean readAllowFloating(AnnotationNode ann) {
        if (ann.values == null) return false;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if ("allowFloating".equals(ann.values.get(i))) {
                Object v = ann.values.get(i + 1);
                return v instanceof Boolean b && b;
            }
        }
        return false;
    }

    private static List<FieldNode> instanceFields(ClassNode cn) {
        List<FieldNode> out = new ArrayList<>();
        for (FieldNode f : cn.fields) {
            if ((f.access & Opcodes.ACC_STATIC) == 0) out.add(f);
        }
        return out;
    }

    /** The SIP-15 {@code $extension} fingerprint of an erased AnyVal class;
     *  returns the single underlying instance field or null. */
    FieldNode detectAnyVal(ClassNode cn) {
        if (!"java/lang/Object".equals(cn.superName)) return null;
        if ((cn.access & Opcodes.ACC_FINAL) == 0) return null;

        List<FieldNode> fields = instanceFields(cn);
        if (fields.size() != 1) return null;
        FieldNode underlying = fields.get(0);
        if ((underlying.access & Opcodes.ACC_FINAL) == 0) return null;

        String firstParam = "(" + underlying.desc;
        for (MethodNode m : cn.methods) {
            if (m.name.endsWith("$extension")
                    && (m.access & Opcodes.ACC_STATIC) != 0
                    && m.desc.startsWith(firstParam)) {
                return underlying;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Eligibility: returns a human reason if NOT eligible, else null. Separate
    // from detection because a mis-promotion silently imposes value semantics.
    // ------------------------------------------------------------------
    String ineligibilityReason(ClassNode cn, Candidate c) {
        if ((cn.access & Opcodes.ACC_FINAL) == 0) {
            return "class is not final";
        }
        if (!"java/lang/Object".equals(cn.superName)) {
            return "non-Object superclass " + cn.superName
                    + " (a value class cannot extend a stateful identity class)";
        }
        if (c.fields.isEmpty()) {
            return "no instance fields to make strict";
        }
        boolean floatingOk = allowFloating || c.allowFloatingOverride;
        for (FieldNode f : c.fields) {
            if ((f.access & Opcodes.ACC_FINAL) == 0) {
                return "non-final field " + f.name + " (value-class fields must be final)";
            }
            if (!floatingOk && (f.desc.equals("D") || f.desc.equals("F"))) {
                return "floating-point field " + f.name + ":" + f.desc
                        + " (acmp flips NaN/signed-zero; opt in with allowFloating)";
            }
        }
        // Every field must be provably set before super() -- reuse the sound
        // strict-init instance analysis. (A body val set after super fails here,
        // correctly: such a class cannot be an all-strict value class.)
        StrictInitTransformer.Selection sel = strict.analyze(cn);
        for (FieldNode f : c.fields) {
            if (!sel.instanceFields().contains(key(f.name, f.desc))) {
                return "field " + f.name + " not provably set before super()";
            }
        }
        return null;
    }

    public boolean allowFloating() { return allowFloating; }
}
