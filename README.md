# strict-init-retrofit

A load-time Java agent that retrofits **`ACC_STRICT_INIT`** (JEP 401 strict field
initialization) onto Scala bytecode that is *already* in the correct shape, so a
preview JVM enforces the invariant natively. No runtime helpers — it only flips
flags on fields that already satisfy the invariant.

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
     -javaagent:target/strict-init-retrofit.jar="include=Obj,A,B;verbose" \
     -cp <app> MainClass
```

Agent options (`;`-separated): `include=<prefix,prefix>` (internal names; `.` or
`/` accepted; default = all non-bootstrap classes), `verbose`,
`valueclass` (enable Phase-1 value-class promotion, below), `allow-floating`.

Offline post-processor (rewrite a classes dir, then inspect with `javap` /
re-verify with `-Xverify:all`):

```
java -cp target/strict-init-retrofit.jar \
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

## Build & demo

```
mvn -DskipTests package          # builds the shaded agent jar
./demo/run.sh                     # phase 0: scalac -> agent -> preview JVM, + negative test
./demo/run-value-classes.sh       # phase 1: promote AnyVal -> value class, offline + agent
```

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
