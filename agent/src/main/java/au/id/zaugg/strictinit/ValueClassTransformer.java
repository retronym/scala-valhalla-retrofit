package au.id.zaugg.strictinit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 *       SIP-15 {@code $extension} fingerprint.</li>
 *   <li><b>Phase 2 — {@code @au.id.zaugg.valhalla.ValueClass}.</b> An explicit
 *       opt-in marker on a class. On a <em>concrete</em> final class of final
 *       fields (the canonical case being a {@code case class}) all instance
 *       fields are promoted; on an <em>abstract</em> class it marks a
 *       <em>value super class</em>, which JEP 401 requires to be stateless
 *       (zero instance fields).</li>
 * </ul>
 *
 * <p>Superclass rule (JEP 401): a value class may only extend {@code Object} or
 * an <em>abstract</em> {@code @ValueClass} class that is itself stateless. That
 * check requires resolving the superclass bytes, supplied by a
 * {@link SuperResolver} (the classloader at load time, the classes dir offline);
 * without one, a non-{@code Object} superclass is conservatively gated out. The
 * chain is validated all the way to {@code Object}.
 *
 * <p>Detection is necessary but not sufficient: a class must also pass
 * eligibility (final-and-≥1-field if concrete / stateless if abstract, all
 * fields final and set before {@code super()}, no {@code float}/{@code double}
 * unless opted in, valid superclass). See {@code docs/value-class-mapping.md}.
 */
public final class ValueClassTransformer {

    /** {@code ACC_IDENTITY} / legacy {@code ACC_SUPER}; its <em>absence</em>
     *  marks a value class. */
    public static final int ACC_IDENTITY = 0x0020;

    /** Descriptor of the opt-in annotation, read by string to avoid coupling the
     *  agent jar to {@code valhalla-annotations}. */
    static final String VALUE_CLASS_DESC = "Lau/id/zaugg/valhalla/ValueClass;";

    /** Supplies raw classfile bytes for an internal name, used to validate a
     *  non-{@code Object} superclass. Returns {@code null} if unavailable. */
    @FunctionalInterface
    public interface SuperResolver {
        byte[] bytes(String internalName);
    }

    private final boolean allowFloating;
    private final ValueClassConfig config;
    private final StrictInitTransformer strict = new StrictInitTransformer(false);

    public ValueClassTransformer(boolean allowFloating) {
        this(allowFloating, ValueClassConfig.empty());
    }

    public ValueClassTransformer(boolean allowFloating, ValueClassConfig config) {
        this.allowFloating = allowFloating;
        this.config = config;
    }

    public record Result(byte[] bytes, String report) {
        public boolean changed() { return bytes != null; }
    }

    /** A class detected as promotable, plus the fields to make strict. */
    private record Candidate(List<FieldNode> fields, String kind,
                             boolean allowFloatingOverride, boolean isAbstract) {}

    /** Convenience overload with no superclass resolution (non-Object supers gated out). */
    public Result transform(byte[] original) {
        return transform(original, null);
    }

    public Result transform(byte[] original, SuperResolver resolver) {
        ClassNode cn = read(original);

        // Staticize configured inner classes first, so a value class no longer
        // "encloses" them (and the eligibility check sees them as static).
        boolean staticized = staticizeInnerEntries(cn);

        Candidate c = detect(cn);
        boolean finalizeOnly = c == null && config.shouldFinalize(cn.name);
        if (c == null && !finalizeOnly && !staticized) return new Result(null, null);

        if (c == null) {
            // No promotion: just finalize and/or staticize at the original version.
            StringBuilder ops = new StringBuilder();
            if (finalizeOnly && (cn.access & Opcodes.ACC_FINAL) == 0) {
                cn.access |= Opcodes.ACC_FINAL;
                ops.append("finalized");
            }
            if (staticized) ops.append(ops.length() > 0 ? ", " : "").append("staticized inner(s)");
            if (ops.length() == 0) return new Result(null, null);
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            return new Result(cw.toByteArray(), cn.name + "  -> " + ops);
        }

        // Finalize a forgotten-final class before the eligibility check, so a
        // listed leaf passes the "must be final" gate.
        boolean finalized = false;
        if (!c.isAbstract && (cn.access & Opcodes.ACC_FINAL) == 0 && config.shouldFinalize(cn.name)) {
            cn.access |= Opcodes.ACC_FINAL;
            finalized = true;
        }

        String why = ineligibilityReason(cn, c, resolver);
        if (why != null) {
            return new Result(null, "skip value-class " + cn.name + " [" + c.kind + "] (" + why + ")");
        }

        for (FieldNode f : c.fields) {
            f.access |= ACC_STRICT_INIT | Opcodes.ACC_FINAL;
        }
        cn.access &= ~ACC_IDENTITY;            // <- the value-class marker
        if (!c.isAbstract) {
            cn.access |= Opcodes.ACC_FINAL;    // concrete value classes are final; abstract ones stay abstract
        }
        markInnerClassIdentity(cn);            // identity nested classes need ACC_IDENTITY inside a value class
        cn.version = (PREVIEW_MINOR << 16) | PREVIEW_MAJOR;

        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);

        String report = cn.name + "  -> " + (c.isAbstract ? "abstract " : "") + "value class ["
                + c.kind + "] (cleared ACC_IDENTITY"
                + (finalized ? ", finalized" : "") + ")"
                + (c.fields.isEmpty() ? "" : "; strict fields " + names(c.fields))
                + "; minor=0x" + Integer.toHexString(PREVIEW_MINOR) + " major=" + PREVIEW_MAJOR;
        return new Result(cw.toByteArray(), report);
    }

    // ------------------------------------------------------------------
    // Detection: the @ValueClass opt-in takes precedence over the AnyVal
    // $extension fingerprint. @ValueClass works on abstract classes too.
    // ------------------------------------------------------------------
    private Candidate detect(ClassNode cn) {
        boolean isAbstract = (cn.access & Opcodes.ACC_ABSTRACT) != 0;
        AnnotationNode ann = findValueClassAnnotation(cn);
        if (ann != null) {
            String kind = isAbstract ? "@ValueClass abstract" : "@ValueClass";
            return new Candidate(instanceFields(cn), kind, readAllowFloating(ann), isAbstract);
        }
        // Externally configured (classes we cannot annotate, e.g. stdlib types).
        if (config.isValueClass(cn.name)) {
            String kind = isAbstract ? "config abstract" : "config";
            return new Candidate(instanceFields(cn), kind, allowFloating, isAbstract);
        }
        FieldNode underlying = detectAnyVal(cn); // always concrete + final
        if (underlying != null) {
            return new Candidate(List.of(underlying), "AnyVal", false, false);
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
    String ineligibilityReason(ClassNode cn, Candidate c, SuperResolver resolver) {
        return ineligibilityReason(cn, c, resolver, new HashSet<>());
    }

    private String ineligibilityReason(ClassNode cn, Candidate c, SuperResolver resolver, Set<String> visited) {
        // A value class can neither enclose nor be a non-static inner member class
        // (an enclosing value instance has no identity to capture). Verified:
        // ClassFormatError otherwise (e.g. scala.Option's WithFilter).
        String innerWhy = innerClassIneligibility(cn);
        if (innerWhy != null) return innerWhy;

        if (c.isAbstract) {
            // A value super class must be stateless (verified: javac rejects an
            // instance field on an abstract value class). It stays abstract.
            if (!c.fields.isEmpty()) {
                return "abstract value class must have zero instance fields "
                        + "(a value super class is stateless), found " + names(c.fields);
            }
            return superIneligibility(cn, resolver, visited);
        }

        if ((cn.access & Opcodes.ACC_FINAL) == 0 && !config.allowNonFinal) {
            return "class is not final (assert no subclasses via the finalize list or allow-non-final)";
        }
        // Super chain before the no-fields check, so a leaf with a stateful parent
        // (e.g. Range.Inclusive) reports the real reason, not "no instance fields".
        String superWhy = superIneligibility(cn, resolver, visited);
        if (superWhy != null) return superWhy;
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

    /** Mark configured inner classes {@code ACC_STATIC} in this class's
     *  {@code InnerClasses} attribute (e.g. {@code scala.Option$WithFilter}), so a
     *  value class no longer "encloses" a non-static inner. The inner already
     *  takes its outer as an explicit constructor argument and holds it in a plain
     *  field, so no code change is needed. Returns whether anything changed. */
    private boolean staticizeInnerEntries(ClassNode cn) {
        if (cn.innerClasses == null || config.staticizeInners.isEmpty()) return false;
        boolean changed = false;
        for (org.objectweb.asm.tree.InnerClassNode ic : cn.innerClasses) {
            if (config.shouldStaticize(ic.name) && (ic.access & Opcodes.ACC_STATIC) == 0) {
                ic.access |= Opcodes.ACC_STATIC;
                changed = true;
            }
        }
        return changed;
    }

    /** In a value class's {@code InnerClasses} attribute, an identity nested
     *  class must carry {@code ACC_IDENTITY} (verified: javac emits {@code 0x28}
     *  STATIC|IDENTITY for an identity class nested in a value class, and the JVM
     *  rejects the entry otherwise). The class itself, and any nested class also
     *  being promoted, stay value (no identity bit). */
    private void markInnerClassIdentity(ClassNode cn) {
        if (cn.innerClasses == null) return;
        for (org.objectweb.asm.tree.InnerClassNode ic : cn.innerClasses) {
            boolean willBeValue = ic.name.equals(cn.name) || config.isValueClass(ic.name);
            if (willBeValue) {
                ic.access &= ~ACC_IDENTITY;
            } else {
                ic.access |= ACC_IDENTITY;
            }
        }
    }

    /** A value class may not enclose, or be, a non-static inner member class. */
    private String innerClassIneligibility(ClassNode cn) {
        if (cn.innerClasses == null) return null;
        for (org.objectweb.asm.tree.InnerClassNode ic : cn.innerClasses) {
            boolean member = ic.innerName != null && ic.outerName != null;
            boolean nonStatic = (ic.access & Opcodes.ACC_STATIC) == 0;
            if (!member || !nonStatic) continue;
            if (cn.name.equals(ic.outerName)) {
                return "encloses non-static inner member class " + ic.name
                        + " (a value class cannot capture an enclosing value instance)";
            }
            if (cn.name.equals(ic.name)) {
                return "is a non-static inner member class of " + ic.outerName;
            }
        }
        return null;
    }

    /** Null if {@code cn}'s superclass chain is value-compatible: every level is
     *  {@code Object} or an abstract value super class that itself fully passes
     *  eligibility (resolved via {@code resolver}). */
    private String superIneligibility(ClassNode cn, SuperResolver resolver, Set<String> visited) {
        String sup = cn.superName;
        if (sup == null || "java/lang/Object".equals(sup)) return null;
        if (!visited.add(sup)) return null; // cycle guard (cannot occur in valid bytecode)
        if (resolver == null) {
            return "non-Object superclass " + sup
                    + " (no resolver to verify it is an abstract value super class)";
        }
        byte[] b = resolver.bytes(sup);
        if (b == null) {
            return "superclass " + sup + " not resolvable to verify it is a value super class";
        }
        ClassNode sn = read(b);
        staticizeInnerEntries(sn); // mirror the staticize the super will receive when promoted
        // The super must itself be a value super: @ValueClass or in the list.
        if (findValueClassAnnotation(sn) == null && !config.isValueClass(sup)) {
            return "superclass " + sup + " is not a value super class"
                    + " (annotate it @ValueClass or add it to the value-class list)";
        }
        if ((sn.access & Opcodes.ACC_ABSTRACT) == 0) {
            return "superclass " + sup + " is not abstract (a value super class must be abstract)";
        }
        // Recursively validate the super exactly as if we were promoting it (its
        // own inner classes, statelessness and super chain) -- so a leaf is only
        // promoted when its whole ancestry will actually be promoted too.
        Candidate superC = new Candidate(instanceFields(sn), "super", false, true);
        return ineligibilityReason(sn, superC, resolver, visited);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static List<String> names(List<FieldNode> fields) {
        List<String> out = new ArrayList<>();
        for (FieldNode f : fields) out.add(f.name + ":" + f.desc);
        return out;
    }

    public boolean allowFloating() { return allowFloating; }
}
