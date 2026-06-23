# valhalla-benchmarks

JMH benchmarks comparing a Scala `case class` promoted to a JEP 401 `value class`
(`@ValueClass final case class VPoint(x: Int, y: Int)`) against an identical
reference `case class` (`RPoint`). The retrofit agent is wired into the **forked
JVM at load time** (`run.sh` passes `-javaagent:…=valueclass` via JMH's
`-jvmArgsAppend`), so `VPoint` is promoted while `RPoint` stays a reference
class — both run in the same fork. Confirmed in-fork: `VPoint(1,2) == VPoint(1,2)`
is `true` (state equality), `RPoint(1,2) == RPoint(1,2)` is `false`.

Run with the built-in allocation profiler:

```
mvn -DskipTests package
./benchmarks/run.sh                 # all benchmarks, -prof gc
./benchmarks/run.sh param -f 2 -wi 5 -i 10
```

The headline metric is `gc.alloc.rate.norm` — **bytes allocated per op** (each op
constructs `n = 1000` points).

## Scenarios

- **locals** — construct and use without escaping. C2 escape analysis already
  scalarizes a non-escaping *reference* object, so this is the parity baseline.
- **param** — pass to a non-inlined method (scalarize-into-params). Needs a
  scalarized calling convention for value objects; without one the value is
  buffered on the heap at the call.
- **array** — store N into an array and sum (flatten-into-fields/arrays). JEP 401
  alone does not flatten arrays (that needs the later null-restricted types).

## Results (one run; JEP 401 EA `27-jep401ea3`, Apple M-series, `-f 1 -wi 3 -i 5`)

| scenario | `RPoint` (ref) | `VPoint` (value) | outcome |
|---|---:|---:|---|
| locals (no escape) | ~0 B/op | ~0 B/op | **parity** — both scalarized by EA |
| param (non-inlined call) | 24000 B/op | 32000 B/op | value **buffered**, and larger |
| array (flatten) | 28016 B/op | 36016 B/op | **no flattening**; value buffered, larger |

## Reading the result honestly

On this EA build, promotion does **not** reduce allocation for these patterns:

- where escape analysis already wins (no escape), the value class **matches** it
  (≈0 B/op) — promotion is allocation-neutral, not a regression;
- where the object escapes — across a non-inlined call, or into an array — the
  value object is **buffered** on the heap, and here it is *larger* than the
  reference object (32 vs 24 B/op for two `int`s), so it allocates **more**.

This is exactly the limitation called out in
[`../docs/value-class-mapping.md`](../docs/value-class-mapping.md): **JEP 401
delivers identity removal and on-stack scalarization, but not a scalarized value
calling convention or heap/array flattening** — those depend on the follow-on
null-restricted / implicitly-constructible value-type JEPs. The benchmark is set
up correctly (promotion verified active in-fork); the numbers reflect the current
EA's optimiser, not a harness artifact. Expect the `param`/`array` columns to
flip in favour of value classes once a build lands those optimisations — re-run
then.

As always with JMH: these are one machine / one build; re-run with more forks
(`-f 5`) and on your target hardware before drawing conclusions.
