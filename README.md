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

## What it does

Scala already emits bytecode that *happens* to satisfy several Valhalla
invariants — module/`val` statics are assigned in `<clinit>`, constructor param
accessors are written before `super()`, and `AnyVal` wrappers and many case
classes are effectively final with only `val` fields. A small ASM-based agent
(usable **load-time** via `-javaagent` or **build-time** as an offline rewriter)
turns that latent compliance into the real thing. It does three things:

1. **Enforces strict fields.** Sets `ACC_STRICT_INIT` on fields that are provably
   assigned in time, so the JVM verifies and enforces it natively.
2. **Promotes value classes.** Turns an erased `AnyVal` wrapper, or a case class
   you opt in with `@ValueClass`, into a real JEP 401 `value class` (identity
   removed; `==` compares by state).
3. **Retrofits library types you can't edit** — `scala.util.Left`/`Right`,
   `scala.Some`, … — driven by an external config instead of an annotation.

Everything is verified end-to-end on the JEP 401 EA JDK under `-Xverify:all`. The
transform is deliberately minimal and **sound**: it only marks what genuinely
qualifies and gates the rest with a precise reason, so a mistake fails loudly at
load rather than silently changing behaviour. Every instruction, attribute and
StackMapTable byte is left untouched. See
[docs/value-class-mapping.md](docs/value-class-mapping.md) for the full design,
the `equals`/`hashCode`/`toString` gap analysis, and the detection rules.

## Prerequisites

- **A JEP 401 early-access "Valhalla" JDK — required to run anything here.** The
  retrofit stamps preview classfiles (value classes / strict fields) that only
  such a build will load. Download one from **<https://jdk.java.net/valhalla/>**,
  then either extract it to `./jdk/` (the demos' default lookup path) or set
  `JAVA_HOME_PREVIEW` to its home directory. The demo scripts and the Maven build
  fail fast with this link if a suitable JDK is missing.
- **JDK 21+ to build** the agent (any vendor; enforced by maven-enforcer).
- **Scala 3 (`scalac`) and coursier (`cs`)** on `PATH` for the demos.

## Strict field initialization

JEP 401 lets a field be flagged `ACC_STRICT_INIT` (`0x0800`): it must be assigned
before it can leak — for static fields, by the time `<clinit>` completes; for
instance fields, before the super-constructor call. Scala already emits bytecode
that obeys this for two populations, so the retrofit is just a flag flip:

| Population | Enforcement | StackMapTable work |
|---|---|---|
| Object/static fields (`MODULE$`, eager `val`) | dynamic, at `<clinit>` completion | none |
| Pre-super instance fields (param accessors) | verifier, before `super()` | none — a straight-line ctor has no frame in the pre-super region |

The selection is conservative, because a wrong mark fails at load/clinit:

- **Static:** `ACC_STATIC && ACC_FINAL`, no `ConstantValue` attribute, and
  definitely-assigned by a `putstatic` in the **entry straight-line block** of
  `<clinit>` (before the first branch). Catches `MODULE$` / eager vals / lazy-val
  offsets; excludes `var`s and lazy backing fields for free.
- **Instance:** across **every** `<init>`, the field is `putfield`-set in the
  pre-super region, never set after super, the region is straight-line (no
  branch/switch/try-catch), and the ctor does not delegate via `this(...)`.

A constructor that **branches or guards the pre-super region** would need the new
`EARLY_LARVAL` StackMapTable frame (`frame_type 246`), which neither ASM nor the
Classfile API can emit today — such classes are detected and gated out. Param
accessors never hit it.

**It really enforces.** A negative test (`demo/neg`) builds the *same* class
twice, each writing its field **after** `super()`:

```
BadPlain  (no strict bit)  -> loads + constructs fine
BadStrict (0x0800 set)     -> VerifyError: All strict final fields must be
                              initialized before super(): 1 field(s), x:I
```

**One non-obvious detail:** the preview gate is stricter than "set the minor to
`0xFFFF`" — the class's `major` must *also* equal the running JVM's latest, or it
is rejected (`69.65535` fails; `71.65535` loads). Scala emits major 52, so the
transform bumps major to the JVM's latest (`feature + 44`, e.g. 27 → 71) as well.

## Value classes

A value class is just a class with the `ACC_IDENTITY` bit (`0x0020`) cleared
(`final value class` = `0x0010`, vs a normal class's `0x0021`). Promotion clears
that bit, marks the instance fields strict + final, and stamps the preview
version. Value objects have no identity, so `==`/acmp compares **by state**.

**Erased `AnyVal` wrappers** are detected structurally — `AnyVal` is not a real
superclass after erasure (the backend rewrites it to `java/lang/Object` and the
name survives nowhere), so the tell is the SIP-15 `$extension` fingerprint: a
`public static name$extension(<underlying>, …)` whose first parameter is the
single field's type.

```
UserId(5) == UserId(5)   -> true    (Int-backed, promoted: == is by state)
Money(100) == Money(100) -> true    (Long-backed, promoted)
Ratio(1.5) == Ratio(1.5) -> false   (Double-backed: gated, still an identity class)
```

**Case classes opt in with `@ValueClass`** — multi-field promotion is opt-in
because dropping identity on an arbitrary class is not always sound. The
`valhalla-annotations` module is a tiny, dependency-free runtime jar; user code
depends only on it, while the agent reads the annotation by descriptor (the two
are decoupled).

```scala
import au.id.zaugg.valhalla.ValueClass

@ValueClass final case class Complex(re: Int, im: Int)
@ValueClass(allowFloating = true) final case class Vec2(x: Double, y: Double)

// A value class may extend an abstract value super class, which JEP 401
// requires to be stateless (zero instance fields):
@ValueClass sealed abstract class Shape
@ValueClass final case class Circle(r: Int) extends Shape
@ValueClass final case class Rect(w: Int, h: Int) extends Shape
```

```
Complex(1,2) == Complex(1,2)   -> true     (== by state; toString/equals retained)
Circle(5) == Circle(5)         -> true     (value subtype of the abstract value class Shape)
NotAnnotated(1,2) == (1,2)     -> false    (no @ValueClass: stays an identity class)
Derived extends PlainParent    -> skipped  (superclass is not an abstract value super)
```

**What gets gated, and why.** The eligibility filter is what keeps this sound; it
leaves a class alone (clear reason logged) when promotion would be wrong or fail
to load:

- a `var` param, a body `var`, or mutable inherited state → not all fields are
  `final` and set before `super()`;
- a `float`/`double` field → value `==`/acmp is *bitwise*, which inverts `NaN`
  (acmp says `NaN == NaN`) and signed-zero (acmp says `+0.0 != -0.0`) vs Scala
  numeric `==`; off by default, opt in per type or globally;
- a non-`Object` superclass that isn't an abstract **stateless** value super
  (checked recursively up the whole chain) — a value class may not extend an
  identity class;
- enclosing a **non-static inner member class** — an enclosing value instance has
  no identity to capture.

When promoting, the agent also fixes `InnerClasses` flags so identity nested
classes carry `ACC_IDENTITY`, exactly as javac does.

## Retrofitting library classes you can't annotate

For types you can't edit, the agent takes an external config (a `.properties`
file or inline options) instead of the annotation:

- **value-class list** — names treated exactly as `@ValueClass`. A concrete entry
  is promoted; an **abstract** entry becomes a stateless value super class (list a
  leaf's abstract super here too).
- **finalize list** — add `ACC_FINAL` to named classes: "the author forgot
  `final` but I know it has no subclasses" — finalize, then promote.
- **allow-non-final** — a blanket version of the above that skips the not-final
  gate (promotion finalizes anyway).
- **staticize-inner** — convert a named non-static inner class to static (flip
  `ACC_STATIC` on its `InnerClasses` entries), unblocking its enclosing class.

```
-javaagent:strict-init-retrofit.jar=valueclass;config=config/scala-stdlib.conf
# offline:
Rewriter --config config/scala-stdlib.conf <classes> <out>
Rewriter --value-classes-list a,b --finalize a,b --allow-non-final <classes> <out>
```

Pointed at the real `scala-library`, it promotes what is genuinely sound and
gives a precise reason for the rest:

```
scala.util.Either -> abstract value super;  Left/Right -> value classes   (Left(e)==Left(e) is true)
Range / Range.Inclusive / .Exclusive        -> skipped   (Range is stateful; a value super must be stateless)
scala.Option / scala.Some                   -> skipped   (Option encloses the non-static inner WithFilter)
```

That last one is fixable: `Option$WithFilter` already takes its outer `Option` as
an explicit constructor argument, so converting it to a static inner needs no code
change — just `staticize-inner=scala.Option$WithFilter`. With that, `Option`
becomes an abstract value super and `Some` a value class, and a `for`-comprehension
guard (which compiles to `Option.withFilter`) still works:

```
value-class-list=scala.Option,scala.Some;staticize-inner=scala.Option$WithFilter
-> Some(1) == Some(1) is true;  `for (x <- Some(21) if x > 0) yield x*2` -> Some(42)
```

## Usage

Load-time agent:

```
java --enable-preview \
     -javaagent:agent/target/strict-init-retrofit.jar="valueclass;include=Obj,A,B;verbose" \
     -cp <app> MainClass
```

Agent options (`;`-separated): `include=<prefix,…>` (internal names; `.` or `/`,
default = all non-bootstrap classes), `verbose`, `valueclass` (enable value-class
promotion), `allow-floating`, `allow-non-final`, `value-class-list=…`,
`finalize=…`, `staticize-inner=…`, `config=<file>`.

Offline post-processor (rewrite a classes dir, then inspect with `javap` /
re-verify with `-Xverify:all`):

```
java -cp agent/target/strict-init-retrofit.jar au.id.zaugg.strictinit.Rewriter \
     [--value-classes] [--allow-floating] [--allow-non-final] \
     [--value-classes-list a,b] [--finalize a,b] [--staticize-inner a,b] \
     [--config file] <classesDir> [outDir]
```

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
./demo/run.sh                     # strict fields: scalac -> agent -> preview JVM, + negative test
./demo/run-value-classes.sh       # AnyVal wrappers -> value classes, offline + agent
./demo/run-case-classes.sh        # @ValueClass case classes (incl. an abstract value-super hierarchy)
./demo/run-config-classes.sh      # config-driven: stdlib Either/Left/Right and Option/Some
./benchmarks/run.sh               # JMH: value vs reference, allocation profiler
```

## Modules

- `annotations/` — `au.id.zaugg:valhalla-annotations`, the dependency-free opt-in
  annotation library for user code.
- `agent/` — `au.id.zaugg:strict-init-retrofit`, the shaded load-time / build-time
  agent (also runnable offline via `Rewriter`).
- `benchmarks/` — `au.id.zaugg:valhalla-benchmarks`, JMH allocation benchmarks
  (value class vs reference case class).

## Notes / limitations

- Stock ASM (9.10.1) suffices — `STRICT_INIT` and the value-class marker are just
  access bits and the preview minor packs into the version int; ASM never has to
  understand them. No Valhalla-preview build of ASM is required.
- Constructors that branch / guard the pre-super region are gated out pending
  `EARLY_LARVAL` frame emission support.
- JEP 401 delivers identity removal and on-stack scalarization, not a scalarized
  value calling convention or array/heap flattening (those need the follow-on
  null-restricted JEPs) — so promotion helps the scalarizable cases and is
  neutral-to-negative where the value escapes. See the benchmarks.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
