# scala-valhalla-retrofit

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
```

The agent (in `valueclass` mode) promotes an annotated **final class of final
fields** whose superclass is `Object` and whose fields are all set before
`super()`, marking *every* field strict. `@ValueClass` is `RUNTIME`-retained so
the agent reads it from `RuntimeVisibleAnnotations`; the per-type
`allowFloating` element lifts the `float`/`double` gate for that class only.
Ineligible or un-annotated classes are left as identity classes (still
strict-init'd). Verified on the EA JVM:

```
Complex(1,2) == Complex(1,2)   -> true    (annotated, promoted: == by state; toString/equals retained)
Mixed(7,true) == Mixed(7,true) -> true    (Long + Boolean, promoted)
NotAnnotated(1,2) == (1,2)     -> false   (no @ValueClass: stays an identity class)
```

User code depends only on `valhalla-annotations`; the agent jar pulls in ASM
but reads the annotation by descriptor, so the two are decoupled.

## Build & demo

```
mvn -DskipTests package          # builds valhalla-annotations + the shaded agent jar
./demo/run.sh                     # phase 0: scalac -> agent -> preview JVM, + negative test
./demo/run-value-classes.sh       # phase 1: promote AnyVal -> value class, offline + agent
./demo/run-case-classes.sh        # phase 2: @ValueClass case classes -> value class
```

## Modules

- `annotations/` — `au.id.zaugg:valhalla-annotations`, the dependency-free opt-in
  annotation library for user code.
- `agent/` — `au.id.zaugg:strict-init-retrofit`, the shaded load-time / build-time
  agent (also runnable offline via `Rewriter`).

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
