package au.id.zaugg.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Compares a promoted value class ({@code VPoint}, {@code @ValueClass}) against an
 * identical reference class ({@code RPoint}) in two situations where Valhalla
 * helps. Run with the GC profiler ({@code -prof gc}); the headline number is
 * {@code gc.alloc.rate.norm} — <b>bytes allocated per benchmark op</b>.
 *
 * <ul>
 *   <li><b>locals_*</b> — construct and use without letting the object escape.
 *       C2 escape analysis already scalarizes a non-escaping <i>reference</i>
 *       object here, so this is the parity baseline: a promoted value class
 *       should match (≈0 B/op), not regress.</li>
 *   <li><b>param_*</b> — pass the object to a non-inlined method (the
 *       "scalarize into params" case). Realising it for a value class needs a
 *       scalarized calling convention; if the JVM lacks one the value object is
 *       buffered on the heap at the call, so this is where the EA build's
 *       current limits show.</li>
 *   <li><b>array_*</b> — store N instances into an array and sum (the
 *       "flatten into fields/arrays" case). JEP 401 alone does not flatten
 *       arrays (that needs the later null-restricted types), so little or no
 *       difference is expected — the profiler reports the truth.</li>
 * </ul>
 *
 * The agent that performs the promotion is supplied to the forked JVM by
 * {@code run.sh} via {@code -jvmArgsAppend}; without it both variants are plain
 * reference classes and read identically. Interpret with {@code -prof gc}: the
 * point is the allocation comparison, not raw ns/op.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1) // JVM args (--enable-preview + -javaagent) are supplied by run.sh
public class PointBench {

    @Param({"1000"})
    public int n;

    // --- Scalarization into locals: object does not escape (EA parity) ---

    @Benchmark
    public long locals_value() {
        long s = 0;
        for (int i = 0; i < n; i++) { VPoint p = new VPoint(i, i + 1); s += (long) p.x() * p.y(); }
        return s;
    }

    @Benchmark
    public long locals_ref() {
        long s = 0;
        for (int i = 0; i < n; i++) { RPoint p = new RPoint(i, i + 1); s += (long) p.x() * p.y(); }
        return s;
    }

    // --- Scalarization into params: pass across a non-inlined call boundary ---

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static long dot(VPoint p) { return (long) p.x() * p.y(); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static long dot(RPoint p) { return (long) p.x() * p.y(); }

    @Benchmark
    public long param_value() {
        long s = 0;
        for (int i = 0; i < n; i++) s += dot(new VPoint(i, i + 1));
        return s;
    }

    @Benchmark
    public long param_ref() {
        long s = 0;
        for (int i = 0; i < n; i++) s += dot(new RPoint(i, i + 1));
        return s;
    }

    // --- Flattening: fill an array, then sum it ---

    @Benchmark
    public long array_value() {
        VPoint[] a = new VPoint[n];
        for (int i = 0; i < n; i++) a[i] = new VPoint(i, i + 1);
        long s = 0;
        for (VPoint p : a) s += p.x() + p.y();
        return s;
    }

    @Benchmark
    public long array_ref() {
        RPoint[] a = new RPoint[n];
        for (int i = 0; i < n; i++) a[i] = new RPoint(i, i + 1);
        long s = 0;
        for (RPoint p : a) s += p.x() + p.y();
        return s;
    }
}
