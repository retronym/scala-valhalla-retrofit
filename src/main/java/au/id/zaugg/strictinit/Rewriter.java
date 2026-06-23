package au.id.zaugg.strictinit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Offline post-processor counterpart to {@link StrictInitAgent}. Rewrites
 * {@code .class} files in place (or into a mirror dir) so the result can be
 * inspected with {@code javap} and re-verified offline with
 * {@code java -Xverify:all --enable-preview}.
 *
 * <pre>
 *   java -cp strict-init-retrofit.jar au.id.zaugg.strictinit.Rewriter &lt;classesDir&gt; [outDir]
 * </pre>
 */
public final class Rewriter {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Rewriter <classesDir> [outDir]");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = args.length > 1 ? Path.of(args[1]) : in;
        StrictInitTransformer engine = new StrictInitTransformer(true);

        List<Path> classes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(in)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(classes::add);
        }

        int changed = 0;
        for (Path p : classes) {
            byte[] original = Files.readAllBytes(p);
            StrictInitTransformer.Result r = engine.transform(original);
            Path target = out.resolve(in.relativize(p));
            if (r.changed()) {
                changed++;
                System.out.println("[rewrite] " + r.report());
                Files.createDirectories(target.getParent());
                Files.write(target, r.bytes());
            } else if (!out.equals(in)) {
                Files.createDirectories(target.getParent());
                Files.write(target, original);
            }
        }
        System.out.println("[rewrite] done: " + changed + "/" + classes.size() + " classes marked strict");
    }

    private Rewriter() {}
}
