# valhalla-benchmarks

JMH benchmarks comparing Scala `case class`es promoted to JEP 401 `value class`es
against identical reference `case class`es. The retrofit agent is wired into the
**forked JVM at load time** (`run.sh` passes `-javaagent:…=valueclass` via JMH's
`-jvmArgsAppend`), so the annotated types are promoted while the plain ones stay
reference classes — both run in the same fork. Confirmed in-fork:
`VPoint(1,2) == VPoint(1,2)` is `true` (state equality),
`RPoint(1,2) == RPoint(1,2)` is `false`.

Run with the built-in allocation profiler:

```
mvn -DskipTests package
./benchmarks/run.sh                 # all benchmarks, -prof gc
./benchmarks/run.sh CursorBench     # just the immutable-cursor idiom
./benchmarks/run.sh param -f 2 -wi 5 -i 10
```

The headline metric is `gc.alloc.rate.norm` — **bytes allocated per op** (one op
is a full traversal / construction of `n = 1000` elements).

There are two benchmark classes. `CursorBench` is the idiom from the JEP 401 JDK
PR and the headline win; `PointBench` probes where JEP 401 does *not* yet help.

## `CursorBench` — the immutable-cursor idiom (value classes win)

Iterate by producing a *new* cursor each step (`c = c.advance()`) instead of
mutating an iterator. Used via `var` (as in the PR) so the loop stays monomorphic
and the cursor is a scalarization candidate rather than being boxed to its
interface type.

- **cursor_value** — immutable `@ValueClass` cursor (promoted).
- **cursor_ref** — the identical cursor as a reference class.
- **iterator_mutable** — classic mutable iterator: one allocation, mutated in place.

| benchmark | B/op | outcome |
|---|---:|---|
| `cursor_value` | **~0** | the value cursor scalarizes across the loop back-edge |
| `cursor_ref` | 24024 | a fresh cursor heap-allocated on every step |
| `iterator_mutable` | ~0 | one object, eliminated by escape analysis |

The value cursor matches the mutable iterator's **zero allocation while staying
immutable** — exactly the PR's motivation. Note escape analysis does *not* save
the reference cursor: its allocation flows across the loop back-edge through a
phi, which EA cannot scalarize, whereas the identity-free value object can be
kept in registers throughout. This is the scenario where promotion pays off on
the current EA build.

## `PointBench` — where JEP 401 does not (yet) help

- **locals** — construct and use without escaping (EA parity baseline).
- **param** — pass to a non-inlined method (needs a scalarized value calling
  convention; without one the value is buffered at the call).
- **array** — store N into an array and sum (needs heap/array flattening).

| scenario | `RPoint` (ref) | `VPoint` (value) | outcome |
|---|---:|---:|---|
| locals (no escape) | ~0 B/op | ~0 B/op | **parity** — both scalarized by EA |
| param (non-inlined call) | 24000 B/op | 32000 B/op | value **buffered**, and larger |
| array (flatten) | 28016 B/op | 36016 B/op | **no flattening**; value buffered, larger |

Where the object escapes — across a non-inlined call, or into an array — the
value object is **buffered** on the heap, and here it is *larger* than the
reference object (32 vs 24 B/op for two `int`s), so it allocates **more**.

## Reading both results together

The contrast is the point. **JEP 401 delivers identity removal and on-stack
scalarization, but not (yet) a scalarized value calling convention or heap/array
flattening** — those depend on the follow-on null-restricted /
implicitly-constructible value-type JEPs (see
[`../docs/value-class-mapping.md`](../docs/value-class-mapping.md)). So promotion
is a clear win for the *scalarizable* idiom (immutable cursors, fluent
value-returning chains kept monomorphic) and is neutral-to-negative where the
value escapes (passed across opaque calls, stored in arrays/fields). Promotion
was verified active in-fork (`VPoint`/`VCursor -> value class`, and
`VCursor(a,i) == VCursor(a,i)` is `true`), so the numbers reflect the EA's
optimiser, not a harness artifact. Expect the `param`/`array` columns to flip
once a build lands those optimisations — re-run then.

Results above: one run, JEP 401 EA `27-jep401ea3`, Apple M-series, `-f 1 -wi 3
-i 5`. As always with JMH, re-run with more forks (`-f 5`) on your target
hardware before drawing conclusions.
