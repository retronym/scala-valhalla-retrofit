package au.id.zaugg.strictinit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure bytecode rewrite that retrofits {@code ACC_STRICT_INIT} (0x0800) onto the
 * fields of a single class that <em>already</em> satisfy the JEP 401 strict-field
 * invariant, then marks the classfile as a preview classfile for the running JVM.
 *
 * <p>The transform deliberately does the absolute minimum:
 * <ul>
 *   <li>OR {@code 0x0800} into the access flags of the selected fields;</li>
 *   <li>set {@code minor_version = 0xFFFF} (preview) and
 *       {@code major_version = }{@link #PREVIEW_MAJOR} (the running JVM's latest,
 *       which the preview gate requires when minor is 0xFFFF);</li>
 *   <li>leave every instruction, attribute and StackMapTable byte untouched.</li>
 * </ul>
 *
 * <p>No StackMapTable rewriting is needed for the two supported populations
 * (eager object/static fields, and straight-line pre-super param accessors),
 * so no {@code EARLY_LARVAL} (frame_type 246) emission is required — which is
 * exactly the one thing neither ASM nor the Classfile API can express today.
 * Classes whose constructors branch or guard the pre-super region are detected
 * and gated out (their instance fields are simply left unmarked).
 */
public final class StrictInitTransformer {

    /** {@code ACC_STRICT}/{@code ACC_STRICT_INIT}: bit 0x0800 on a field. */
    public static final int ACC_STRICT_INIT = 0x0800;

    /** Preview marker for the minor version. */
    public static final int PREVIEW_MINOR = 0xFFFF;

    /**
     * Major version stamped on rewritten classes. The JVM's preview gate rejects
     * any class whose minor is 0xFFFF unless its major equals the running JVM's
     * latest supported major (verified empirically: 69.65535 is rejected,
     * 71.65535 loads on the JEP 401 EA build). Derived from the running runtime
     * ({@code feature + 44}; e.g. 27 -> 71) so the agent is not pinned to one EA.
     */
    public static final int PREVIEW_MAJOR = Runtime.version().feature() + 44;

    private final boolean verbose;

    public StrictInitTransformer(boolean verbose) {
        this.verbose = verbose;
    }

    /** Result of a transform: the (possibly null) new bytes plus a human report. */
    public record Result(byte[] bytes, String report) {
        public boolean changed() { return bytes != null; }
    }

    /**
     * @return a {@link Result} whose {@code bytes} are non-null only if at least
     *         one field was marked strict.
     */
    /** The strict-init-eligible fields of a class, partitioned by kind. */
    public record Selection(Set<String> staticFields, Set<String> instanceFields) {
        public boolean isEmpty() { return staticFields.isEmpty() && instanceFields.isEmpty(); }
    }

    /** Run the (sound, conservative) selection without mutating anything. Shared
     *  with {@link ValueClassTransformer}, which needs to confirm a field is
     *  safely strict-markable before promoting its class to a value class. */
    public Selection analyze(ClassNode cn) {
        return new Selection(selectStaticFields(cn), selectInstanceFields(cn));
    }

    public Result transform(byte[] original) {
        ClassNode cn = new ClassNode();
        new ClassReader(original).accept(cn, 0); // preserve frames/code verbatim

        Selection sel = analyze(cn);
        Set<String> staticTargets = sel.staticFields();
        Set<String> instanceTargets = sel.instanceFields();

        if (sel.isEmpty()) {
            return new Result(null, null);
        }

        // Apply: OR the strict bit into the selected fields.
        for (FieldNode fn : cn.fields) {
            String key = key(fn.name, fn.desc);
            if (staticTargets.contains(key) || instanceTargets.contains(key)) {
                fn.access |= ACC_STRICT_INIT;
            }
        }

        // Stamp preview minor + required major.
        cn.version = (PREVIEW_MINOR << 16) | PREVIEW_MAJOR;

        // Write back, preserving frames/code verbatim (no COMPUTE_*).
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        byte[] out = cw.toByteArray();

        StringBuilder sb = new StringBuilder();
        sb.append(cn.name).append("  -> minor=0x").append(Integer.toHexString(PREVIEW_MINOR))
          .append(" major=").append(PREVIEW_MAJOR);
        if (!staticTargets.isEmpty()) sb.append("\n    static strict: ").append(staticTargets);
        if (!instanceTargets.isEmpty()) sb.append("\n    instance strict (pre-super): ").append(instanceTargets);
        return new Result(out, sb.toString());
    }

    // ------------------------------------------------------------------
    // Static field selection.
    //
    // A static field qualifies iff:
    //   * ACC_STATIC && ACC_FINAL
    //   * no ConstantValue attribute (FieldNode.value == null) -- those are
    //     "always set" by the JVM and carry no putstatic
    //   * it is definitely-assigned by an unconditional putstatic in <clinit>,
    //     i.e. the putstatic occurs in the entry straight-line block of <clinit>
    //     (before the first branch), so it is guaranteed to run to completion.
    //
    // Soundness note: a false positive here makes <clinit> throw at completion,
    // so we are conservative -- only the entry block counts as "unconditional".
    // The Scala module pattern (MODULE$, eager vals, lazy-val offsets) all sit
    // in that entry block, so they are caught; conditionally-set fields are not.
    // ------------------------------------------------------------------
    private Set<String> selectStaticFields(ClassNode cn) {
        MethodNode clinit = findMethod(cn, "<clinit>", "()V");
        Set<String> result = new LinkedHashSet<>();
        if (clinit == null) return result;

        Set<String> entryBlockPutstatics = entryBlockPutstatics(cn.name, clinit);

        for (FieldNode fn : cn.fields) {
            boolean isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
            boolean isFinal = (fn.access & Opcodes.ACC_FINAL) != 0;
            boolean hasConstantValue = fn.value != null;
            if (isStatic && isFinal && !hasConstantValue
                    && entryBlockPutstatics.contains(key(fn.name, fn.desc))) {
                result.add(key(fn.name, fn.desc));
            }
        }
        return result;
    }

    /** Names of this-class static fields written by a putstatic in the entry
     *  straight-line block of {@code clinit} (everything before the first branch
     *  / switch / explicit control-flow merge). */
    private Set<String> entryBlockPutstatics(String owner, MethodNode clinit) {
        Set<String> set = new HashSet<>();
        for (AbstractInsnNode insn : clinit.instructions) {
            int op = insn.getOpcode();
            if (insn instanceof JumpInsnNode
                    || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode
                    || op == Opcodes.ATHROW
                    || op == Opcodes.RETURN) {
                break; // leave the entry block; nothing past here is unconditional
            }
            if (insn instanceof FieldInsnNode f && op == Opcodes.PUTSTATIC
                    && f.owner.equals(owner)) {
                set.add(key(f.name, f.desc));
            }
        }
        return set;
    }

    // ------------------------------------------------------------------
    // Instance (pre-super) field selection.
    //
    // A field qualifies iff, across EVERY <init> of this class:
    //   * the pre-super region (instructions before the receiver's super/ this()
    //     invokespecial <init>) is straight-line: no branch, no switch, and no
    //     try/catch covering it -- otherwise an EARLY_LARVAL frame would be
    //     required, which we cannot emit, so we gate the whole class's instance
    //     fields out;
    //   * the field is putfield-set in that pre-super region in every <init>
    //     (intersection), and
    //   * the field is never putfield-set after super in any <init> (a field set
    //     post-super is unset at the super call and would fail verification).
    //
    // A constructor that delegates via this(...) also gates instance fields out
    // (the delegate, not this ctor, establishes the fields).
    // ------------------------------------------------------------------
    private Set<String> selectInstanceFields(ClassNode cn) {
        Set<String> intersection = null;
        Set<String> setAfterSuper = new HashSet<>();

        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>")) continue;

            int superIdx = indexOfSuperInit(cn, m);
            if (superIdx < 0) {
                // No identifiable super/this init -> cannot reason; gate out.
                return new LinkedHashSet<>();
            }
            if (isThisDelegation(cn, m, superIdx)) {
                return new LinkedHashSet<>(); // delegating ctor: gate out
            }
            if (preSuperRegionUnsafe(m, superIdx)) {
                return new LinkedHashSet<>(); // branch/handler before super: gate out
            }

            Set<String> preSuper = new LinkedHashSet<>();
            collectPutfields(cn.name, m, 0, superIdx, preSuper);

            Set<String> postSuper = new HashSet<>();
            collectPutfields(cn.name, m, superIdx + 1, m.instructions.size(), postSuper);
            setAfterSuper.addAll(postSuper);

            if (intersection == null) {
                intersection = preSuper;
            } else {
                intersection.retainAll(preSuper);
            }
        }

        if (intersection == null) return new LinkedHashSet<>(); // no <init>
        intersection.removeAll(setAfterSuper);

        // Keep only fields actually declared in this class as instance fields.
        Set<String> declared = new HashSet<>();
        for (FieldNode fn : cn.fields) {
            if ((fn.access & Opcodes.ACC_STATIC) == 0) declared.add(key(fn.name, fn.desc));
        }
        intersection.retainAll(declared);
        return intersection;
    }

    /** Index of the invokespecial &lt;init&gt; that initialises {@code this}
     *  (super-ctor or this-delegation). The first such call in a constructor is
     *  always the receiver's own initialisation in well-formed bytecode. */
    private int indexOfSuperInit(ClassNode cn, MethodNode m) {
        InsnList insns = m.instructions;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL
                    && insn instanceof org.objectweb.asm.tree.MethodInsnNode mi
                    && mi.name.equals("<init>")
                    && (mi.owner.equals(cn.superName) || mi.owner.equals(cn.name))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isThisDelegation(ClassNode cn, MethodNode m, int superIdx) {
        AbstractInsnNode insn = m.instructions.get(superIdx);
        return insn instanceof org.objectweb.asm.tree.MethodInsnNode mi
                && mi.owner.equals(cn.name);
    }

    /** True if the pre-super region contains a branch/switch, or any try/catch
     *  block overlaps it. */
    private boolean preSuperRegionUnsafe(MethodNode m, int superIdx) {
        InsnList insns = m.instructions;
        for (int i = 0; i < superIdx; i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof JumpInsnNode
                    || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                return true;
            }
        }
        if (m.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : m.tryCatchBlocks) {
                int start = insns.indexOf(tcb.start);
                if (start >= 0 && start < superIdx) return true;
            }
        }
        return false;
    }

    /** Collect this-class instance fields written by putfield whose owner is the
     *  class, in the instruction index range [from, to). */
    private void collectPutfields(String owner, MethodNode m, int from, int to, Set<String> out) {
        InsnList insns = m.instructions;
        for (int i = from; i < to && i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn.getOpcode() == Opcodes.PUTFIELD
                    && insn instanceof FieldInsnNode f
                    && f.owner.equals(owner)) {
                out.add(key(f.name, f.desc));
            }
        }
    }

    // ------------------------------------------------------------------

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return m;
        }
        return null;
    }

    static String key(String name, String desc) { return name + ":" + desc; }
}
