# scala-valhalla-retrofit

> [!WARNING]
> **🧪 Experimental.** A proof-of-concept that depends on a preview JEP 401
> early-access JDK whose classfile encoding may change without notice. Not for
> production use — APIs, flags, and on-disk classfile shapes can change at any
> time. Pin a specific EA build and expect breakage on upgrades.

> Retrofit Project Valhalla's JEP 401 — **strict field initialization** *and*
> **value classes** — onto ordinary Scala bytecode, so a preview JVM enforces
> Valhalla semantics natively. No source changes, no runtime helpers: the
> transform only flips access-flag bits on classes/fields that already satisfy
> the invariant and stamps the preview classfile version.

## Executive summary

Scala already emits bytecode that *happens* to satisfy several Valhalla
invariants — module/`val` statics are assigned in `<clinit>`, constructor param
accessors are written before `super()`, and `AnyVal` wrappers (plus many case
classes) are effectively final with only `val` fields. This project ships a
small ASM-based agent (usable load-time **or** build-time) plus a tiny
dependency-free opt-in annotation library that turn that latent compliance into
the real thing, in three layered phases. Where the invariant does *not* hold —
a `var` param, a `var` introduced in the body, or mutable inherited state — the
eligibility filter detects it (no non-`final` field, `Object` superclass, every
field set before `super()`) and leaves the class alone:

- **Phase 0 — strict fields.** Set `ACC_STRICT_INIT` (`0x0800`) on static and
  pre-`super()` instance fields that are provably assigned, so the JVM verifies
  and enforces them. Zero StackMapTable work for the supported shapes.
- **Phase 1 — `AnyVal` → value class.** Detect an *erased* `extends AnyVal`
  class (structurally — `AnyVal` is not a real superclass after erasure) and
  clear `ACC_IDENTITY` to make it a real `value class`.
- **Phase 2 — `@ValueClass` case classes → value class.** An opt-in annotation
  promotes a final case class of final fields, marking every field strict.

Everything is verified end-to-end on the JEP 401 EA JDK under `-Xverify:all`:
strict fields are enforced (a field set after `super()` fails with `VerifyError`),
and promoted value classes compare `==` **by state**. The transform is
deliberately minimal and **sound** — a wrong mark fails loudly at load, never
silently — and floating-point fields are gated by default because value-class
`==` (acmp) inverts `NaN`/signed-zero relative to numeric `==`. See
[docs/value-class-mapping.md](docs/value-class-mapping.md) for the full mapping,
the `equals`/`hashCode`/`toString` gap analysis, and the detection design.

## Prerequisites

- **A JEP 401 early-access "Valhalla" JDK — required to run anything here.** The
  retrofit stamps preview classfiles (value classes / strict fields) that only
  such a build will load. Download one from **<https://jdk.java.net/valhalla/>**,
  then either extract it to `./jdk/` (the demos' default lookup path) or set
  `JAVA_HOME_PREVIEW` to its home directory. The demo scripts and the Maven build
  fail fast with this link if a suitable JDK is missing.
- **JDK 21+ to build** the agent (any vendor; enforced by maven-enforcer).
- **Scala 3 (`scalac`) and coursier (`cs`)** on `PATH` for the demos.

## Why

JEP 401 adds strict field initialization: a field flagged `ACC_STRICT_INIT`
(`0x0800`) must be assigned before it can leak — for static fields, by the time
`<clinit>` completes; for instance fields, before the super-constructor call.
Scala already emits bytecode that *happens* to obey this for two populations:

- **eager object/static fields** — `MODULE$`, eager `val`s, lazy-val offsets are
  `static final` and unconditionally `putstatic`'d at the top of `<clinit>`;
- **constructor param accessors** — a `case class B(_1)` writes `_1` with a
  `putfield` on `this` *before* the super `invokespecial`, on a straight line.

So no helpers, no codegen — just set `0x0800` on the fields that already qualify
and let the JVM verify/enforce it.

## The two populations, and why they're each easy

| Population | Enforcement | StackMapTable work |
|---|---|---|
| Object/static fields (`MODULE$`, eager `val`) | dynamic, at `<clinit>` completion | none |
| Pre-super instance fields (param accessors) | verifier, before `super()` | none — straight-line ctor has no frame in the pre-super region |

The one thing neither ASM nor the Classfile API can emit today is the new
`EARLY_LARVAL` StackMapTable frame (`frame_type 246`), needed only when a
constructor **branches or guards the pre-super region**. Param-accessor ctors
never hit it; such classes are detected and gated out (their instance fields are
left unmarked).

## Selection filters (kept sound — a wrong mark fails at load/clinit, not silently)

**Static:** `ACC_STATIC && ACC_FINAL`, no `ConstantValue` attribute, and
definitely-assigned by a `putstatic` in the **entry straight-line block** of
`<clinit>` (before the first branch — guaranteed to run). Catches `MODULE$` /
eager vals / lazy-val offsets; excludes `var`s and lazy backing fields for free.

**Instance:** across **every** `<init>`, the field is `putfield`-set in the
pre-super region (intersection), never set after super, the pre-super region is
straight-line (no branch/switch/try-catch), and the ctor does not delegate via
`this(...)`. Anything else gates the class's instance fields out.

## Key empirical finding (beyond the original sketch)

The preview gate is stricter than "bump the minor to `0xFFFF`": a class with
`minor == 0xFFFF` is **rejected** unless its `major` also equals the running
JVM's latest. Verified on the JEP 401 EA build:

```
69.65535  -> UnsupportedClassVersionError: ... only recognizes preview features
             for class file version 71.65535
71.65535  -> loads
```

Scala emits major 52 (Java 8) by default, so the transform must bump
`major` to the JVM's latest (derived at runtime as `feature + 44`, e.g. 27 → 71)
in addition to setting `minor = 0xFFFF` and OR-ing `0x0800`. That's the whole
rewrite; every instruction, attribute and StackMapTable byte is untouched.

## Enforcement is real, not silently ignored

A negative test (`demo/neg`) generates the *same* class twice — once with the
strict bit, once without — each writing its field **after** `super()`:

```
BadPlain  (no strict bit)  -> loads + constructs fine
BadStrict (0x0800 set)     -> VerifyError: All strict final fields must be
                              initialized before super(): 1 field(s), x:I
```

## Usage

Load-time agent:

```
java --enable-preview \
     -javaagent:agent/target/strict-init-retrofit.jar="include=Obj,A,B;verbose" \
     -cp <app> MainClass
```

Agent options (`;`-separated): `include=<prefix,prefix>` (internal names; `.` or
`/` accepted; default = all non-bootstrap classes), `verbose`,
`valueclass` (enable Phase-1 value-class promotion, below), `allow-floating`.

Offline post-processor (rewrite a classes dir, then inspect with `javap` /
re-verify with `-Xverify:all`):

```
java -cp agent/target/strict-init-retrofit.jar \
     au.id.zaugg.strictinit.Rewriter [--value-classes] [--allow-floating] <classesDir> [outDir]
```

## Phase 1: promoting `extends AnyVal` to real value classes

With `valueclass` / `--value-classes`, the agent also promotes an *erased* Scala
`extends AnyVal` value class to a JEP 401 `value class`. The rewrite clears
`ACC_IDENTITY` (0x0020) on the class — a value class is exactly a class *without*
that bit (`final value class` = `0x0010`, vs a normal class's `0x0021`) — marks
the single underlying field strict+final, and stamps the preview version.

`AnyVal` is not a real superclass after erasure (the backend rewrites it to
`java/lang/Object` and the name survives nowhere), so detection is structural:
the SIP-15 `$extension` fingerprint — a `public static name$extension(<underlying>,
…)` method whose first parameter is the underlying field's type. See
[docs/value-class-mapping.md](docs/value-class-mapping.md).

**Floating-point underlyings are gated out by default.** Value `==`/acmp is
bitwise substitutability, which *inverts* `NaN` (acmp says `NaN == NaN`) and
signed-zero (acmp says `+0.0 != -0.0`) relative to Scala numeric `==`. Pass
`allow-floating` / `--allow-floating` to override, accepting the changed
semantics. Result, verified on the EA JVM (two distinct allocations):

```
UserId(5) == UserId(5)   -> true    (Int-backed, promoted: == is by state)
Money(100) == Money(100) -> true    (Long-backed, promoted)
Ratio(1.5) == Ratio(1.5) -> false   (Double-backed: gated, still an identity class)
```

## Phase 2: opting case classes in with `@ValueClass`

Multi-field promotion is **opt-in**, because dropping identity on an arbitrary
class is not always sound. The `valhalla-annotations` module (a tiny,
dependency-free runtime jar) provides the marker:

```scala
import au.id.zaugg.valhalla.ValueClass

@ValueClass final case class Complex(re: Int, im: Int)
@ValueClass(allowFloating = true) final case class Vec2(x: Double, y: Double)

// A value class may extend an abstract @ValueClass "value super class",
// which JEP 401 requires to be stateless (zero instance fields):
@ValueClass sealed abstract class Shape
@ValueClass final case class Circle(r: Int) extends Shape
@ValueClass final case class Rect(w: Int, h: Int) extends Shape
```

The agent (in `valueclass` mode) promotes an annotated **final class of final
fields** whose fields are all set before `super()`, marking *every* field
strict. `@ValueClass` is `RUNTIME`-retained so the agent reads it from
`RuntimeVisibleAnnotations`; the per-type `allowFloating` element lifts the
`float`/`double` gate for that class only.

**Superclass rule (JEP 401).** A value class may only extend `Object` or an
**abstract `@ValueClass`** class that is itself stateless. On an abstract class
the annotation marks a *value super class*: it must have zero instance fields
(the agent verifies this, and resolves a non-`Object` superclass's bytes to
validate the whole chain up to `Object`). A case class extending a non-annotated
or stateful parent is therefore excluded — promoting it would make a value class
extend an identity class. Ineligible or un-annotated classes are left as
identity classes (still strict-init'd). Verified on the EA JVM:

```
Complex(1,2) == Complex(1,2)   -> true     (annotated, promoted: == by state; toString/equals retained)
Mixed(7,true) == Mixed(7,true) -> true     (Long + Boolean, promoted)
Circle(5) == Circle(5)         -> true     (value subtype of the abstract value class Shape)
NotAnnotated(1,2) == (1,2)     -> false    (no @ValueClass: stays an identity class)
Derived extends PlainParent    -> skipped  (superclass is not an abstract @ValueClass)
```

User code depends only on `valhalla-annotations`; the agent jar pulls in ASM
but reads the annotation by descriptor, so the two are decoupled.

## Retrofitting classes you cannot annotate (config)

For library types you can't edit — `scala.util.Left`/`Right`, etc. — the agent
takes an external config instead of the annotation. Three mechanisms:

- **value-class list** — internal names treated exactly as `@ValueClass`. A
  concrete entry is promoted; an **abstract** entry becomes a stateless *value
  super class*. List a leaf's abstract super here too (the JVM forbids a value
  class from extending an identity class).
- **finalize list** — add `ACC_FINAL` to named classes. The "the author forgot
  `final` but I know it has no subclasses" case: finalize, then promote.
- **allow-non-final** — a blanket mode that skips the not-final gate (promotion
  finalizes anyway), as an alternative to listing each class.
- **staticize-inner** — convert a non-static inner class to static (flip
  `ACC_STATIC` on its `InnerClasses` entries everywhere), so its enclosing class
  is no longer blocked from becoming a value class.

Via a `.properties` file ([config/scala-stdlib.conf](config/scala-stdlib.conf))
or inline agent options:

```
-javaagent:strict-init-retrofit.jar=valueclass;config=config/scala-stdlib.conf
-javaagent:strict-init-retrofit.jar=valueclass;value-class-list=scala.util.Either,scala.util.Left,scala.util.Right
# offline:
Rewriter --config config/scala-stdlib.conf <classes> <out>
Rewriter --value-classes-list a,b --finalize a,b --allow-non-final <classes> <out>
```

Running this against the real `scala-library` is instructive — the agent only
promotes what is genuinely sound and **gates the rest with a precise reason**:

```
scala.util.Either -> abstract value class;  Left/Right -> value classes   (Left(e)==Left(e) is true)
scala.Option / scala.Some -> skipped   (Option encloses the non-static inner WithFilter)
Range / Range.Inclusive / .Exclusive  -> skipped   (Range has instance fields; a value super must be stateless)
```

`scala.Option`/`Some` *become* promotable once `Option$WithFilter` is converted
to a static inner with **staticize-inner** — `WithFilter` already takes its outer
`Option` as an explicit constructor argument, so flipping `ACC_STATIC` on its
`InnerClasses` entries needs no code change. With that, `Option` becomes an
abstract value super and `Some` a value class; a `for`-comprehension guard (which
compiles to `Option.withFilter`) still works:

```
-javaagent:strict-init-retrofit.jar=valueclass;value-class-list=scala.Option,scala.Some;staticize-inner=scala.Option$WithFilter
-> Some(1) == Some(1) is true;  `for (x <- Some(21) if x > 0) yield x*2` -> Some(42)
```

Two JEP 401 constraints the validator enforces (both verified against the EA
JVM, then gated rather than producing an unloadable class): a value class may
only extend `Object` or an abstract **stateless** value super class (checked up
the whole chain), and a value class may **not enclose a non-static inner member
class**. When promoting, the agent also fixes `InnerClasses` flags so identity
nested classes carry `ACC_IDENTITY`, as javac does.

## Benchmarks

JMH benchmarks ([benchmarks/](benchmarks/README.md)) compare promoted value
classes against identical reference `case class`es, wiring the agent into the
forked JVM at load time and reading `gc.alloc.rate.norm` from the built-in
allocation profiler:

```
./benchmarks/run.sh               # -prof gc, value vs reference
./benchmarks/run.sh CursorBench   # the immutable-cursor idiom (the headline win)
```

The headline is the **immutable-cursor idiom** from the JEP 401 JDK PR — iterate
by producing a *new* cursor each step (`c = c.advance()`) instead of mutating an
iterator. As a value class the cursor scalarizes across the loop, so a full
traversal allocates **nothing**, matching a mutable iterator while staying
immutable; the identical *reference* cursor allocates one object per step
(escape analysis cannot eliminate it across the loop back-edge):

```
cursor_value     -> ~0 B/op     (immutable value class: scalarized, zero-alloc)
cursor_ref       -> 24024 B/op  (immutable reference class: a cursor per step)
iterator_mutable -> ~0 B/op     (classic mutable iterator, EA-eliminated)
```

The companion `PointBench` shows the limits: where the value *escapes* (passed
across a non-inlined call, or stored in an array) JEP 401 has no scalarized
calling convention or array/heap flattening yet, so the value object is buffered
— and here larger — and allocates *more*. JEP 401 delivers identity removal and
on-stack scalarization; flattening awaits the follow-on null-restricted JEPs. See
the [benchmarks README](benchmarks/README.md) for the full tables.

## Build & demo

```
mvn -DskipTests package          # builds annotations + agent + benchmarks
./demo/run.sh                     # phase 0: scalac -> agent -> preview JVM, + negative test
./demo/run-value-classes.sh       # phase 1: promote AnyVal -> value class, offline + agent
./demo/run-case-classes.sh        # phase 2: @ValueClass case classes -> value class
./demo/run-config-classes.sh      # config: promote stdlib Either/Left/Right (no annotation)
./benchmarks/run.sh               # JMH: value vs reference, allocation profiler
```

## Modules

- `annotations/` — `au.id.zaugg:valhalla-annotations`, the dependency-free opt-in
  annotation library for user code.
- `agent/` — `au.id.zaugg:strict-init-retrofit`, the shaded load-time / build-time
  agent (also runnable offline via `Rewriter`).
- `benchmarks/` — `au.id.zaugg:valhalla-benchmarks`, JMH allocation benchmarks
  (value class vs reference case class).

The demo expects the JEP 401 EA JDK at `jdk/jdk-27.jdk/Contents/Home` (override
with `JAVA_HOME_PREVIEW`) and `cs` (coursier) on `PATH` for the Scala library.

## Notes / limitations (v1)

- Stock ASM (9.10.1) suffices — `STRICT_INIT` is just an access bit and the
  preview minor packs into the version int; ASM never has to understand the flag.
  No Valhalla-preview build of ASM is required.
- Constructors that branch / guard the pre-super region are gated out pending
  `EARLY_LARVAL` frame emission support.
- The agent only rewrites classes that gain ≥1 strict field; everything else is
  passed through untouched (mixed preview / non-preview classpaths are fine).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
