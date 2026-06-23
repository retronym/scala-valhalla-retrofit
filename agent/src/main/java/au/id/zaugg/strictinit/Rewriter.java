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
        List<String> positional = new ArrayList<>();
        boolean valueClasses = false, allowFloating = false, allowNonFinal = false;
        java.util.Set<String> vcList = new java.util.LinkedHashSet<>();
        java.util.Set<String> finList = new java.util.LinkedHashSet<>();
        java.util.Set<String> staticList = new java.util.LinkedHashSet<>();
        ValueClassConfig fileConfig = ValueClassConfig.empty();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--value-classes" -> valueClasses = true;
                case "--allow-floating" -> { valueClasses = true; allowFloating = true; }
                case "--allow-non-final" -> { valueClasses = true; allowNonFinal = true; }
                case "--value-classes-list" -> { valueClasses = true; vcList.addAll(ValueClassConfig.parseList(args[++i])); }
                case "--finalize" -> { valueClasses = true; finList.addAll(ValueClassConfig.parseList(args[++i])); }
                case "--staticize-inner" -> { valueClasses = true; staticList.addAll(ValueClassConfig.parseList(args[++i])); }
                case "--config" -> { valueClasses = true; fileConfig = ValueClassConfig.fromFile(Path.of(args[++i])); }
                default -> positional.add(a);
            }
        }
        if (positional.isEmpty()) {
            System.err.println("usage: Rewriter [--value-classes] [--allow-floating] [--allow-non-final]"
                    + " [--value-classes-list a,b] [--finalize a,b] [--staticize-inner a,b] [--config file] <classesDir> [outDir]");
            System.exit(2);
        }
        Path in = Path.of(positional.get(0));
        Path out = positional.size() > 1 ? Path.of(positional.get(1)) : in;
        ValueClassConfig config = fileConfig.merge(new ValueClassConfig(vcList, finList, staticList, allowNonFinal));
        StrictInitTransformer strict = new StrictInitTransformer(true);
        ValueClassTransformer valueClass = valueClasses ? new ValueClassTransformer(allowFloating, config) : null;

        List<Path> classes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(in)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(classes::add);
        }

        // Resolve a superclass's bytes from the input dir so the promoter can
        // verify an abstract @ValueClass value super class.
        ValueClassTransformer.SuperResolver resolver = name -> {
            Path sp = in.resolve(name + ".class");
            try {
                return Files.exists(sp) ? Files.readAllBytes(sp) : null;
            } catch (Exception e) {
                return null;
            }
        };

        int strictCount = 0, valueCount = 0;
        for (Path p : classes) {
            byte[] original = Files.readAllBytes(p);
            byte[] result = null;
            // Value-class promotion takes precedence (strict superset rewrite).
            if (valueClass != null) {
                ValueClassTransformer.Result vc = valueClass.transform(original, resolver);
                if (vc.changed()) {
                    valueCount++;
                    System.out.println("[value-class] " + vc.report());
                    result = vc.bytes();
                } else if (vc.report() != null) {
                    System.out.println("[value-class] " + vc.report());
                }
            }
            if (result == null) {
                StrictInitTransformer.Result r = strict.transform(original);
                if (r.changed()) {
                    strictCount++;
                    System.out.println("[rewrite] " + r.report());
                    result = r.bytes();
                }
            }
            Path target = out.resolve(in.relativize(p));
            if (result != null) {
                Files.createDirectories(target.getParent());
                Files.write(target, result);
            } else if (!out.equals(in)) {
                Files.createDirectories(target.getParent());
                Files.write(target, original);
            }
        }
        System.out.println("[rewrite] done: " + valueCount + " value classes, "
                + strictCount + " strict-only, of " + classes.size() + " classes");
    }

    private Rewriter() {}
}
