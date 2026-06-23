package au.id.zaugg.strictinit;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * External configuration for promoting classes we cannot annotate (Scala stdlib
 * types like {@code scala.Some} / {@code scala.util.Left}). Two independent
 * mechanisms plus one mode:
 *
 * <ul>
 *   <li><b>value-class list</b> — internal names treated exactly as if they
 *       carried {@code @ValueClass}: a concrete entry is promoted (all fields
 *       strict), an abstract entry becomes a stateless value super class. List
 *       an abstract super here too so a listed leaf may legally extend it (the
 *       JVM forbids a value class from extending an identity class).</li>
 *   <li><b>finalize list</b> — internal names to mark {@code ACC_FINAL}. Use to
 *       finalize a class the author left open before promoting it (or on its own,
 *       independent of promotion).</li>
 *   <li><b>allow-non-final</b> — a blanket mode that skips the "class is not
 *       final" eligibility gate (promotion finalizes the class anyway). The
 *       "oops, the author forgot {@code final} but I know it has no subclasses"
 *       escape hatch, as an alternative to listing each class in finalize.</li>
 * </ul>
 *
 * <p>Loadable from a {@code .properties} file with keys {@code value-class},
 * {@code finalize}, {@code allow-non-final} (comma-separated values), and/or from
 * inline agent options. Class names may be written with {@code .} or {@code /}
 * (both accepted); nested classes must use {@code $}, e.g.
 * {@code scala.collection.immutable.Range$Inclusive}.
 */
public final class ValueClassConfig {

    final Set<String> valueClasses;   // internal names to treat as @ValueClass
    final Set<String> finalizeClasses; // internal names to mark ACC_FINAL
    final boolean allowNonFinal;

    public ValueClassConfig(Set<String> valueClasses, Set<String> finalizeClasses, boolean allowNonFinal) {
        this.valueClasses = Set.copyOf(valueClasses);
        this.finalizeClasses = Set.copyOf(finalizeClasses);
        this.allowNonFinal = allowNonFinal;
    }

    public static ValueClassConfig empty() {
        return new ValueClassConfig(Set.of(), Set.of(), false);
    }

    public boolean isEmpty() {
        return valueClasses.isEmpty() && finalizeClasses.isEmpty() && !allowNonFinal;
    }

    boolean isValueClass(String internalName) { return valueClasses.contains(internalName); }
    boolean shouldFinalize(String internalName) { return finalizeClasses.contains(internalName); }

    /** Parse a comma-separated list of class names (dots or slashes) into
     *  internal names. Nested classes must already use {@code $}. */
    public static Set<String> parseList(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String n = part.trim().replace('.', '/');
                if (!n.isEmpty()) out.add(n);
            }
        }
        return out;
    }

    public static ValueClassConfig fromFile(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            props.load(r);
        }
        return new ValueClassConfig(
                parseList(props.getProperty("value-class")),
                parseList(props.getProperty("finalize")),
                Boolean.parseBoolean(props.getProperty("allow-non-final", "false")));
    }

    /** Union of two configs (allow-non-final is OR'd). */
    public ValueClassConfig merge(ValueClassConfig other) {
        Set<String> vc = new LinkedHashSet<>(valueClasses);
        vc.addAll(other.valueClasses);
        Set<String> fin = new LinkedHashSet<>(finalizeClasses);
        fin.addAll(other.finalizeClasses);
        return new ValueClassConfig(vc, fin, allowNonFinal || other.allowNonFinal);
    }

    @Override
    public String toString() {
        return "value-class=" + valueClasses + " finalize=" + finalizeClasses
                + " allow-non-final=" + allowNonFinal;
    }
}
