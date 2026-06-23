# Mapping Scala value classes onto JVM value classes (JEP 401)

Status: design sketch. Companion to the `ACC_STRICT_INIT` retrofit in this repo — which turns out to be a *prerequisite* for this mapping, not a separate exercise (every JEP 401 value class mandates strict fields).

## Why

Scala already ships two zero-cost-abstraction mechanisms that exist *only* because the JVM had no value types, and each pays a tax:

- **`AnyVal` value classes** (`class Meters(val u: Double) extends AnyVal`) erase to their underlying field in the common case, but **box unpredictably** — as a generic type argument, an array element, an `Any`/`AnyVal` upcast, a pattern-match scrutinee. The box is a real heap object with identity, and the boxing is silent, so it's a latent performance cliff.
- **`opaque type`s** never box, but go the other way: they vanish entirely at runtime (literally the underlying type), so there is no distinct runtime type at all — no reflection, no pattern match, no overload resolution on the abstraction.

JEP 401 finally gives the JVM an **identity-free class the JIT can scalarize**. Mapping Scala's constructs onto it lets the boxed form of an `AnyVal` value class be flattened away in hot code instead of allocated, and gives `opaque type`'s "free" abstraction a real runtime identity when one is wanted — without reintroducing the cliff. It also future-proofs Scala for heap/array flattening once the null-restricted-type JEPs land on top of 401.

## What JEP 401 actually gives us (verified on the EA build, `27-jep401ea3`)

A concrete `value class` is **implicitly `final`**; its instance fields are `final` **and `ACC_STRICT_INIT`** (must be assigned before `super()`). Value objects have **no identity**:

- `==` / `acmp` compares **by field state** (substitutability): two distinct allocations with equal fields are `==`. Verified: `new V(1,2) == new V(1,2)` is `true`.
- `synchronized`, `wait`/`notify`, and identity-hash are **rejected** — javac: *"required: a type with identity, found: V"*.
- Value objects are still **nullable `L`-references**; `null` is assignable. Scalarization is a JIT optimization. **Heap/array flattening is *not* in JEP 401** — it needs the later null-restricted / implicitly-constructible types. So v1's payoff is stack/JIT scalarization, not flat layout in `Array[V]`. Set expectations accordingly.

This is the crux of the semantic mapping: Scala must only emit `value class` for types where **loss of identity is sound** and **state equality is the intended `==`**.

## Bytecode gap analysis: `equals` / `hashCode` / `toString`

This is where the mapping stops being a flag flip. Compiling a bare `value class Pt { int x; int y; ... }` with the EA javac and inspecting the result:

- **No `equals`/`hashCode`/`toString` are synthesized.** Unlike `record`, the classfile contains only the fields and the constructor. (Note in passing: `record` fields are *also* `ACC_STRICT_INIT` now under JEP 401 — javap even chokes printing `0x800` at FIELD location — so records are migrated to strict fields too.)
- **`equals` and `hashCode` are nevertheless state-based, supplied by the VM.** `new Pt(1,2).equals(new Pt(1,2))` is `true` and their `hashCode`s are equal; `System.identityHashCode` does *not* throw — it folds onto the same state hash. So a promoted type gets correct value `equals`/`hashCode` *for free*.
- **`toString` is NOT structural.** It stays the inherited `Pt@1dda4358` form; the VM synthesizes nothing record-like.
- **`==` / acmp always compares by field state and ignores any `equals` override.** A `value class` with a deliberately-lying `equals` returns `true` from `.equals(...)` yet `false` from `==` for differing state. `==` *is* substitutability and cannot be redirected to a user `equals`.
- **Floating-point substitutability is bitwise, which inverts numeric `==`.** For a `double` field: acmp says `NaN == NaN` is **true** (numeric `==` says false) and `+0.0 == -0.0` is **false** (numeric `==` says true). Both edges flip relative to Scala/Java `==`.

Against what Scala emits today — case-class `equals` is `instanceof` + per-field compare + `canEqual`; `hashCode` is MurmurHash via `Statics.mix`/`productHash`; `toString` is `ScalaRunTime._toString` → `B(99)`; an `AnyVal` class delegates all three to its single underlying field:

| Aspect | Scala (case class) | value class as compiled | Gap / action on promotion |
|---|---|---|---|
| `equals` | structural + `instanceof` + `canEqual` | VM, state-based, exact-class | **Drop** Scala's `equals` — VM covers it. Loses the `canEqual` subclass hook, but value classes are `final` so it's moot. |
| `hashCode` | MurmurHash (`Statics.mix`) | VM state hash, different algorithm | **Drop.** Contract still holds; only the *values* change — breaks anything persisting hashes or assuming cross-version stability. |
| `toString` | `B(99)` (structural) | `B@1a2b` (non-structural) | **Keep** Scala's `toString`; the VM synthesizes none. |
| `==` vs custom `equals` | `==` calls `equals` | acmp ignores `equals` entirely | Promote only when `equals` is structural-over-fields (or absent); a custom `equals` silently diverges from `==`. |
| `double`/`float` field `==` | numeric (`NaN≠NaN`, `±0.0` equal) | acmp bitwise (`NaN=NaN`, `±0.0` differ) | **Semantics flip** — see eligibility filter; gate out or require opt-in. |
| identity (`eq`, `synchronized`) | reference / monitor | none (folded / rejected) | Already handled by the identity rule. |

**Net:** `equals`/`hashCode` are a *simplification* (delete them, inherit correct value semantics) — except for floating-point fields, where acmp's bitwise substitutability is a genuine behavioural change. `toString` must be retained. These three results drive the eligibility filter below.

## Source constructs and how each maps

| Scala construct | Target | Notes |
|---|---|---|
| `class M(val u: T) extends AnyVal` | `value class` (1 strict field) | The 1:1 case. The *boxed* form becomes a value object; the JIT scalarizes the boxes Scala can't currently elide. |
| `case class C(a: A, b: B)` — all-`val`, identity-free | `value class` (N strict fields) | The multi-field target. `AnyVal` allows only one field; case classes are where flattening of several fields pays off. Not *every* case class qualifies — a `var` param, a body `var`, or mutable inherited state is excluded by the eligibility filter. |
| `opaque type O = U` | **leave as `U`** by default | Already optimal. Only promote to a `value class` when the program needs a *runtime* type (reflection / pattern match / overloading) — rare and opt-in. |
| plain `final` class, all-`val`, no identity use | opportunistic `value class` | Same eligibility test as case classes; needs intent confirmation. |

`AnyVal` is the headline because it is the construct literally called "value class" in Scala and the one whose cliff users actually hit.

## The semantic decisions (where the design lives)

1. **Identity removal must be sound.** Forbid promotion when instances are used with `eq`/`ne`, `synchronized`, `wait`/`notify`, or identity-hash, or stored as keys in identity maps. Scala already bans most of these for `AnyVal`; for case classes it requires analysis or an explicit opt-in.
2. **`==` must mean state equality.** JEP 401 `acmp` is structural. Promote only when the type's `equals` is structural over exactly its fields (the default case-class `equals` qualifies). A *custom* `equals` that disagrees with field state would diverge from `acmp` — exclude, or accept that `==` fast-paths to `acmp`.
3. **Nullability is preserved.** v1 keeps nullable `L`-references; no null-restricted `Array[V]` flattening. `Option[V]` stays a reference. Defer to the follow-on JEP.
4. **Immutability.** Value-class fields are `final`+strict. Exclude any type with a `var` field. `copy` is fine (constructs a fresh value object).
5. **Inheritance.** Concrete value classes are implicitly final and may only extend abstract value-compatible supers (no stateful identity superclass). Map only when the super is `Object` or interface-only (covers `AnyVal` and `sealed trait` leaves whose parents are traits).
6. **Strict init is the bridge.** The constructor must set every field before `super()`. Scala param-accessor constructors already do this (see this repo's instance-field analysis); that is exactly the invariant a value class requires, so no codegen change is needed for the common shape — only the flag flip plus value-class marking.

## Eligibility filter (sound, opt-in — same philosophy as the strict-init filters)

A type is promotable iff **all** hold:

- every instance field is `final` (no `var`), and the constructor assigns them all before `super()`;
- superclass is `Object` or the hierarchy above it is interfaces/abstract-value only; class is final or a sealed leaf;
- no use of identity on its instances (`eq`/`ne`, `synchronized`, identity-hash) — conservatively: require an opt-in marker (e.g. a `@value`/`@valhalla` annotation or a compiler flag) to assert intent, since full identity-escape analysis is whole-program;
- `equals`/`hashCode` are structural over the fields (or absent) — a *custom* `equals` would silently disagree with `==`/acmp, so exclude it;
- **no `float`/`double` fields** (v1), or opt-in acknowledging that acmp's bitwise substitutability flips `NaN` and signed-zero equality versus Scala `==` — see the gap analysis. `toString` is retained either way;
- not (yet) relied upon for null-restricted flat layout.

As with the strict-init agent, a wrong promotion fails loudly (verification / `acmp` behaviour change), so the filter is conservative by construction and opt-in at the edges.

## Detecting an erased `extends AnyVal` in the transformer

`extends AnyVal` is a *frontend* concept that the Scala backend erases away. The naive check — `superName == "scala/AnyVal"` — never fires, because `scala.AnyVal` is not a real superclass in bytecode. Verified on Scala 3.7 output (`final class Meters(val underlying: Double) extends AnyVal`): the classfile's `super_class` is `java/lang/Object`, and the string `AnyVal` appears **nowhere** — not in `super_class`, not in the class `Signature` attribute, not anywhere in the constant pool. So detection has to be structural, not nominal.

**The reliable signal is the `$extension` scheme (SIP-15).** Scala compiles every operation of an `AnyVal` class into a `public static` method `name$extension` whose **first parameter is the underlying field's type** (the unwrapped receiver), and emits an instance method `name` that forwards to it — including `equals$extension(<U>, Object)` and `hashCode$extension(<U>)`. The same `$extension` methods also appear on the companion `Meters$`. This pairing is unique to `AnyVal` erasure; a plain one-field `final` class or a `case class` has no `$extension` methods.

Detection predicate (bytecode-only, conservative):

- `super_class == java/lang/Object` and the class is `ACC_FINAL`;
- exactly **one** instance field `F` (`private final`);
- the constructor is `<init>(<F.desc>)` and sets `F` before `super()` (already what the strict-init analysis verifies);
- there is at least one `public static` method `M$extension` whose **first parameter descriptor equals `F`'s descriptor**, with a matching instance method `M` (same trailing parameters) that forwards to it;
- corroborating: the class carries the `Scala` / `TASTY` marker attributes, so the rewrite only ever touches Scala output.

The `$extension` + single-field combination is the high-precision discriminator; the rest narrows to the canonical shape. This mirrors the existing `StrictInitTransformer` selection style — structural predicates over the `ClassNode`, sound by construction (a miss leaves the class unmarked rather than breaking it).

**Rejected / deferred alternatives:**

- *Nominal super / generic `Signature`* — neither retains `AnyVal` (verified absent). Dead end.
- *Parse `TASTY`* — the `TASTY` attribute is the authoritative oracle (`extends AnyVal` survives in the pickled types), but it is an opaque, version-coupled binary blob; parsing it inside an ASM pass is heavy and brittle. Keep as a last-resort oracle, not the primary path.
- *Compiler-emitted marker* — the clean long-term signal is a dedicated marker (a `@ValueClass` `RuntimeVisibleAnnotation` or class attribute) emitted by scalac; this belongs to Phase 3 and removes the need to fingerprint. For Scala 2, the `ScalaSig`/`ScalaInlineInfo` pickle already encodes value-class-ness and could be read directly.

Because mis-detection would silently impose value semantics (identity loss, acmp-based `==`), the detector is **necessary but not sufficient**: a class must pass both this fingerprint *and* the eligibility filter above (and, for ambiguous cases, the opt-in marker) before promotion. Note that `Meters` here wraps a `Double`, so it is independently gated out by the floating-point rule from the gap analysis — a good reminder that detection and eligibility are separate gates.

## Phasing

- **Phase 0 — strict-init retrofit (this repo, done).** Mandatory groundwork: value classes require strict fields. Already retrofits `ACC_STRICT_INIT` onto the exact constructor shape value classes need.
- **Phase 1 — bytecode promotion of `AnyVal` value classes (implemented; `ValueClassTransformer`).** Same form factor as the strict-init agent: a load-time / build-time rewrite that promotes an eligible erased `AnyVal` class to a `value class` by **clearing `ACC_IDENTITY` (0x0020)** — a value class is exactly a class without that bit (verified: `final value class` = `0x0010`, identity class = `0x0021`) — plus marking the underlying field strict+final and stamping the preview version. Floating-point underlyings gated out by default (opt-in `allow-floating`). Verified on the EA JVM: promoted Int/Long wrappers compare `==` by state, the gated `Double` wrapper retains identity. No scalac change.
- **Phase 2 — eligible case classes, opt-in (implemented; `valhalla-annotations` + `ValueClassTransformer`).** A dependency-free runtime annotation `@au.id.zaugg.valhalla.ValueClass` (RUNTIME-retained, read from `RuntimeVisibleAnnotations`) marks a final class of final fields for promotion; the agent then makes *all* its fields strict and clears `ACC_IDENTITY`, gated by the eligibility filter (fields final and set before `super()`, float/double off unless `allowFloating=true` per type). Opt-in rather than inferred because dropping identity is not whole-program-sound to assume.
  - **Superclass rule.** A value class may only extend `Object` or an **abstract** *value super class*, which JEP 401 requires to be stateless. An abstract class marked for promotion is checked for **zero instance fields** (verified: javac rejects a field on an abstract value class); the agent resolves a non-`Object` superclass's bytes (via the classloader at load time, the classes dir offline) and validates the whole chain up to `Object`, running the super's *full* eligibility so a leaf is only promoted when its entire ancestry will be too. A class extending a non-promoted or stateful parent is excluded.
  - **Inner-class rule.** A value class may **not enclose a non-static inner member class** (an enclosing value instance has no identity to capture — verified: `ClassFormatError` otherwise, e.g. `scala.Option`'s `WithFilter`). And when promoting, the agent fixes the `InnerClasses` attribute so identity nested classes carry `ACC_IDENTITY`, matching javac (`0x28` STATIC|IDENTITY for an identity class nested in a value class) — otherwise the entry is rejected.
  - Verified on the EA JVM: annotated multi-field case classes compare `==` by state with `equals`/`toString` retained; subclasses of an abstract value super load and `instanceof` correctly; field-bearing abstracts and non-value-parent subclasses are gated out; un-annotated classes stay identity.

- **Phase 2 (config) — classes we cannot annotate (implemented; `ValueClassConfig`).** The same promotion driven by external config instead of the annotation, for stdlib/library types: a **value-class list** (concrete → promote, abstract → value super), a **finalize list** (add `ACC_FINAL` to a class whose author forgot it, then promote), and an **allow-non-final** mode (blanket skip of the not-final gate). Loadable from a `.properties` file or inline agent/CLI options. Honest results against `scala-library`: `scala.util.Either`/`Left`/`Right` promote and load (`Left(e) == Left(e)` is `true`); `scala.Option`/`Some` are gated (Option encloses the non-static inner `WithFilter`); `Range.Inclusive`/`Exclusive` are gated (`Range` is stateful). The mechanism exists; what is *sound* is determined per class by the same eligibility filter, with a precise skip reason for the rest.
- **Phase 3 — frontend support in scalac.** Emit value classes directly, model identity-freedom in the type system, integrate `opaque type`, and adopt null-restricted / implicitly-constructible types for real heap and `Array[V]` flattening once those JEPs land.

Each phase is independently shippable and gated by the eligibility filter; earlier phases are pure post-processing (easy to inspect and re-verify offline), later phases need real frontend work.

## Prior art

- **Kotlin `value class` / `@JvmInline`** — same erase-to-underlying-with-boxing model, explicitly aiming at Valhalla; the closest existing mapping.
- **Scala `AnyVal` erasure (SIP-15)** — defines today's boxing rules we are trying to eliminate.
- **Java `record`s** — the identity-bearing structural sibling; a natural "reference shape" and a stepping stone to *value records*.
- **C# `readonly struct`** — value semantics with enforced immutability; a model for the eligibility rules.
- **Project Valhalla L-world + null-restricted (`!`) types** — the layering that makes Phase 3 flattening real.

## Risks / open questions

- **`acmp`/`eq` silently changes meaning.** Any code that compared boxed value-class instances by reference now compares by state. Mitigation: forbid `eq` on promoted types (Scala already discourages it for `AnyVal`) and gate case-class promotion behind opt-in.
- **Floating-point `==` flips (gap analysis).** acmp's bitwise substitutability makes `NaN == NaN` true and `+0.0 == -0.0` false on promoted wrappers — both inverted from Scala numeric `==`. A `value class WrappedDouble` changes the behaviour of existing `==` sites. Mitigation: exclude `float`/`double`-bearing types from automatic promotion in v1.
- **`hashCode` values change.** Dropping Scala's MurmurHash for the VM's state hash is contract-preserving but value-changing; anything persisting or wire-encoding hashCodes, or asserting specific values, breaks.
- **Binary compatibility.** Turning a *published* class into a value class is a subtle source/binary-incompatible change (identity, synchronization, serialization). Needs a versioning / migration policy, à la JDK's value-based-class warnings.
- **Java interop.** Downstream Java code that synchronizes on, or identity-compares, these types breaks at runtime (`IdentityException`).
- **Tooling gap (shared with this repo).** Constructors that branch or guard the pre-super region need an `EARLY_LARVAL` StackMapTable frame (frame_type 246) that neither ASM nor the Classfile API emits today; such classes are gated out until that lands.
- **Managed expectations on flattening.** JEP 401 alone delivers JIT scalarization, not flat heap/array layout. The headline "no more boxing" is only fully true after the null-restricted-types follow-on.
