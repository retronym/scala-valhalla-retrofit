package au.id.zaugg.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The immutable-cursor idiom from the JEP 401 JDK PR: iterate a collection by
 * producing a <em>new</em> cursor each step ({@code c = c.advance()}) instead of
 * mutating an iterator. As a value class the per-advance cursor scalarizes, so a
 * full traversal allocates nothing; as a reference class the same idiom allocates
 * a fresh cursor on every element.
 *
 * <p>Used via {@code var} (as in the PR) so the loop stays monomorphic and the
 * cursor is a candidate for scalarization rather than being boxed to its
 * interface type. Run with {@code -prof gc}; the headline is
 * {@code gc.alloc.rate.norm} (bytes/op, one op = one full traversal of {@code n}
 * elements).
 *
 * <ul>
 *   <li><b>cursor_value</b> — immutable {@code @ValueClass} cursor (promoted).</li>
 *   <li><b>cursor_ref</b> — the identical cursor as a reference class.</li>
 *   <li><b>iterator_mutable</b> — classic mutable iterator: one allocation per
 *       traversal, mutated in place (the status-quo baseline).</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1) // JVM args (--enable-preview + -javaagent) supplied by run.sh
public class CursorBench {

    @Param({"1000"})
    public int n;

    private int[] data;

    @Setup
    public void setup() {
        data = new int[n];
        for (int i = 0; i < n; i++) data[i] = i;
    }

    @Benchmark
    public long cursor_value() {
        long s = 0;
        for (var c = new VCursor(data, 0); c.exists(); c = c.advance()) s += c.get();
        return s;
    }

    @Benchmark
    public long cursor_ref() {
        long s = 0;
        for (var c = new RCursor(data, 0); c.exists(); c = c.advance()) s += c.get();
        return s;
    }

    @Benchmark
    public long iterator_mutable() {
        long s = 0;
        for (var it = new MutIntIterator(data); it.hasNext(); ) s += it.next();
        return s;
    }
}
