# Value classes in the internals of Scala collections

The JEP 401 JDK PR ships a benchmark suite with three motifs — immutable cursors,
array flattening via a hand-specialized `ArrayList<PrimitiveInt>`, and HashMap
node redesigns (immutable nodes with side bookkeeping, or open addressing). The
striking thing, reading them as a Scala engineer, is that **Scala already ships a
hand-built approximation of all three**, precisely because the JVM has lacked
value types and flattening for fifteen years. Valhalla is the feature those
approximations were faking. This note maps the PR's motifs onto the standard
library and asks what could actually change.

The honest framing first (learned from building the retrofit in this repo): JEP
401 alone delivers **identity removal and on-stack scalarization**, not a
scalarized value calling convention or heap/array flattening. The array and
HashMap benchmarks are *hand-specialized* exactly because generic specialization
and null-restricted flat value arrays are still to come. So the items below split
into "works today (scalarization)" and "needs the follow-on JEPs (flattening)".

## 1. Immutable cursors → Scala's mutable `Iterator` / `Stepper`

Scala iteration is mutable to the core: `Iterator` has `next()`/`hasNext`, and the
`Stepper` API (for Java `Stream` interop) is mutable too. An immutable value
cursor — `cursor.exists` / `cursor.get` / `cursor = cursor.advance()` — is the
allocation-free dual, and (as the benchmark in this repo shows) a value cursor
scalarizes across the loop back-edge where escape analysis cannot save a
reference cursor.

Where it pays off in Scala is **internal traversal of immutable structures**:

- `List` already has a near-ideal cursor — the cons cell itself — so little to win.
- `Vector` (radix-balanced trie) iterates via a *mutable* `VectorIterator` holding
  array references and indices; a value cursor carrying the trie path could
  scalarize the per-step bookkeeping.
- `immutable.HashMap` (CHAMP) iterates with mutable node-stack iterators; a value
  cursor over `(node, cursorInNode)` is a natural fit.

The catch is the public API is `Iterator`-shaped, so this is an *internal* engine
change (a value cursor feeding the existing `Iterator` facade), not a new surface.

## 2. Array flattening → the `ArraySeq.of*` zoo and trie/buffer backings

This is where Scala's hand-specialization is most visible. `immutable.ArraySeq`
has **ten** hand-written subclasses — `ofInt`, `ofLong`, `ofDouble`, … `ofRef`,
`ofUnit` — each wrapping a typed primitive array so the *backing store* doesn't
box. `mutable.ArraySeq` mirrors them. That is exactly the PR's manual
`ArrayList<PrimitiveInt>`: a hand specialization standing in for the generic
flattening that isn't here yet.

Two distinct wins, only one of which is available today:

- **Backing-array flattening (needs the follow-on JEPs).** A generic `Vector[T]`
  or `ArrayBuffer[T]` stores `Array[AnyRef]`, so `Vector[Complex]` is an array of
  *references* to heap `Complex` objects — a pointer-chase per element and an
  allocation per element. With null-restricted flat value arrays, the same
  `Array[Complex]` becomes `[re,im,re,im,…]`: no per-element object, contiguous,
  cache-friendly. Combined with generic specialization, the entire `ofInt`/`ofLong`
  hand-specialized family — and the parallel boxing in `Vector`/`ArrayBuffer` —
  collapses into one generic flattened type. This is the headline collection win,
  and it is gated on the post-401 work.
- **Boundary scalarization (works today, partially).** Even without flat arrays,
  promoting wrapper types means transient values created while iterating/folding
  can scalarize instead of allocating, as long as they don't escape into the
  `Array[AnyRef]`. Useful for `map`/`fold`/`view` pipelines kept monomorphic.

## 3. HashMap nodes → mutable chaining vs CHAMP, and the two redesigns

Scala's two hash maps bracket the PR's discussion exactly.

`mutable.HashMap` is `Array[Node]` with
`final class Node(_key, _hash, var _value, var _next)` — **mutable** nodes,
collision chains threaded through `_next`. Those `var`s make it a non-candidate
for a value class as-is; this is the "mutable nodes" baseline the PR contrasts
against. The two PR-style redesigns map cleanly:

- **Immutable value nodes + side bookkeeping.** Make the entry a value
  `(_hash, key, value)` with no `_next`, store entries flat in the table, and keep
  collision linkage in a side structure (a parallel `nextIndex: Array[Int]`, or a
  small overflow region). The hot entry then scalarizes/flattens; the rare-collision
  linkage is demoted to a cold side array.
- **Open addressing.** Drop chaining entirely; probe within a flat table of value
  entries. This is the most flatten-friendly shape — a contiguous `Entry[]`
  flattening to `[hash,k,v,hash,k,v,…]`, zero node objects, excellent locality.
  There is strong prior art (fastutil, koloboke, agrona, hopscotch/Robin-Hood
  hashing); Valhalla makes a *generic* version of it viable rather than a
  primitive-only hand-roll.

`immutable.HashMap` is **CHAMP** (`BitmapIndexedMapNode`), already a
flattening-conscious design: it inlines keys, values and sub-nodes into one
`Array[Any]` per node with bitmaps, specifically to avoid a per-entry node object
and to improve cache behaviour. Two observations:

- CHAMP's content array is **heterogeneous** (keys, values, sub-nodes interleaved
  in one `Array[Any]`). Flat value layout wants *homogeneous* arrays, so flattening
  CHAMP means splitting into separate keys/values/nodes arrays, or a value-union —
  a real redesign, not a flag flip. CHAMP already captured much of the locality
  win at the Scala level, so the marginal gain from value flattening is smaller
  here than for the mutable map.
- The sub-node references could become value-class handles, helping scalarization
  of traversal (ties back to §1).

## The single biggest practical win: `Option` and tuples in the API

Independent of the engine redesigns, the most pervasive allocation in everyday
Scala-collection use is **`Option` in lookups** — `map.get(k)`, `find`,
`headOption`, `collectFirst` all allocate a `Some`. A value `Option` (we promote
it in this repo) scalarizes that
`Some` whenever it doesn't escape — which is the common `map.get(k).getOrElse(d)`
shape. That is probably the highest-leverage change for real programs.

**Tuples** are the same story: `Map` entries, `zip`, `groupBy`, `unzip` all
traffic in `Tuple2`, and Scala already ships specialized `Tuple2$mcII$sp` &c. A
value `Tuple2` scalarizes the wrapper in non-escaping pipelines (the generic
element types stay boxed until species/specialized generics arrive, but removing
the tuple object itself is already meaningful).

## What the compiler gets to retire

Read together, Valhalla value classes plus eventual generic specialization let
Scala **delete a large body of hand-specialization machinery** that exists only
to dodge boxing:

- the `ArraySeq.of{Int,Long,…}` (and `mutable.ArraySeq`) subclass families;
- the `Tuple2$mc..$sp` and `Function0/1/2$mc..$sp` `@specialized` zoo (24
  `Function1` variants alone);
- `AnyVal` value classes and the SIP-15 `$extension` scheme (the very thing this
  repo detects);
- research detours like miniboxing.

`value class` + a parametric/species-based specialization is the unifying primitive
those were all approximating. That is the strategic payoff for the language, beyond
any single benchmark.

## Honest constraints (from building the retrofit)

The same gates that this repo's eligibility filter enforces are exactly the
constraints a collections redesign would hit:

- **Stateless abstract supers.** A value leaf may only extend `Object` or an
  abstract *stateless* value super. `Range` is the cautionary tale: it is value-
  shaped `(start, end, step)` yet not promotable, because it extends the stateful
  `AbstractSeq` collection hierarchy. Value-flavoured collection elements/nodes
  must sit *outside* the identity-bearing collection class hierarchy.
- **`acmp` is bitwise — and these are map keys.** Value `==` compares field state
  bitwise. The VM-provided `equals`/`hashCode` are state-based and consistent, so
  value classes are fine map keys — *except* floating-point fields, where bitwise
  acmp makes `NaN == NaN` true and `+0.0 == -0.0` false, inverting numeric `==`. A
  `Map[WrappedDouble, V]` would therefore treat `NaN` keys as equal (arguably a
  feature, but a behaviour change). A custom `equals` that disagrees with field
  state is a hard exclusion.
- **Flattening needs null-restriction.** Flat value arrays/fields need the
  null-restricted / implicitly-constructible follow-on; under 401 a `null` slot in
  an `Array[V]` still implies a reference layout. The `Option`/`None` interaction
  is delicate here.
- **No non-static inner members; InnerClasses hygiene.** We hit this with
  `Option.WithFilter`; collection nodes/iterators implemented as inner classes
  would need the same staticization.
- **Binary compatibility.** Turning a *published* collection class into a value
  class is a subtle source/binary-incompatible change (identity, `synchronized`,
  serialization, `eq`). A real migration would version it, as the JDK does for its
  value-based classes.

## Where I would prototype first

1. **Value `Option`** end-to-end through `get`/`find`/`getOrElse` — highest leverage,
   smallest surface, and already promotable here.
2. **An open-addressed `mutable.HashMap`** with immutable value entries — the
   redesign with the clearest flattening payoff once flat value arrays land, and
   measurable on scalarization alone in probe-heavy workloads.
3. **A generic flattened `ArraySeq`/`ArrayBuffer`** as the retirement target for
   the `ofInt`/`ofLong` family — the direct analogue of the PR's hand-specialized
   `ArrayList<PrimitiveInt>`, and the cleanest "delete the workaround" story.
