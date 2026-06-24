package au.id.zaugg.strictinit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

/**
 * Load-time agent that retrofits {@code ACC_STRICT_INIT} onto eligible Scala
 * classes as they are defined, so a JEP 401 preview JVM enforces strict field
 * initialization natively.
 *
 * <p>Run with:
 * <pre>
 *   java --enable-preview \
 *        -javaagent:strict-init-retrofit.jar=include=Repro,Obj,A,B;verbose \
 *        ...
 * </pre>
 *
 * <p>Agent options (semicolon-separated {@code key=value} / flags):
 * <ul>
 *   <li>{@code include=pkg1/Foo,pkg2} — only transform internal names starting
 *       with one of these comma-separated prefixes ({@code /} or {@code .} both
 *       accepted). If omitted, every non-bootstrap class is considered.</li>
 *   <li>{@code verbose} — print each rewritten class and its strict fields.</li>
 * </ul>
 */
public final class StrictInitAgent {

    public static void premain(String args, Instrumentation inst) {
        install(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst);
    }

    private static void install(String args, Instrumentation inst) {
        Options opts = Options.parse(args);
        System.err.println("[strict-init] agent active; preview minor=0x"
                + Integer.toHexString(StrictInitTransformer.PREVIEW_MINOR)
                + " major=" + StrictInitTransformer.PREVIEW_MAJOR
                + (opts.valueClasses ? "; value-class promotion ON"
                        + (opts.allowFloating ? " (incl. floating)" : " (float/double gated)") : "")
                + (opts.config.isEmpty() ? "" : "; config[" + opts.config + "]")
                + (opts.includes.isEmpty() ? "; include=<all>" : "; include=" + opts.includes));
        inst.addTransformer(new Transformer(opts), true);
    }

    private record Options(List<String> includes, boolean verbose,
                           boolean valueClasses, boolean allowFloating,
                           ValueClassConfig config) {
        static Options parse(String args) {
            List<String> includes = List.of();
            boolean verbose = false, valueClasses = false, allowFloating = false, allowNonFinal = false;
            java.util.Set<String> vcList = new java.util.LinkedHashSet<>();
            java.util.Set<String> finList = new java.util.LinkedHashSet<>();
            ValueClassConfig fileConfig = ValueClassConfig.empty();
            if (args != null && !args.isBlank()) {
                for (String part : args.split(";")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    switch (part) {
                        case "verbose" -> verbose = true;
                        case "valueclass", "valueclasses" -> valueClasses = true;
                        case "allow-floating" -> allowFloating = true;
                        case "allow-non-final" -> allowNonFinal = true;
                        default -> {
                            if (part.startsWith("include=")) {
                                String v = part.substring("include=".length());
                                includes = Arrays.stream(v.split(","))
                                        .map(s -> s.trim().replace('.', '/'))
                                        .filter(s -> !s.isEmpty())
                                        .toList();
                            } else if (part.startsWith("value-class-list=")) {
                                vcList.addAll(ValueClassConfig.parseList(part.substring("value-class-list=".length())));
                            } else if (part.startsWith("finalize=")) {
                                finList.addAll(ValueClassConfig.parseList(part.substring("finalize=".length())));
                            } else if (part.startsWith("config=")) {
                                try {
                                    fileConfig = ValueClassConfig.fromFile(java.nio.file.Path.of(part.substring("config=".length())));
                                } catch (Exception e) {
                                    System.err.println("[strict-init] could not load config: " + e);
                                }
                            }
                        }
                    }
                }
            }
            ValueClassConfig config = fileConfig.merge(new ValueClassConfig(vcList, finList, allowNonFinal));
            if (!config.isEmpty()) valueClasses = true; // external config implies promotion
            return new Options(includes, verbose, valueClasses, allowFloating, config);
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        private final Options opts;
        private final StrictInitTransformer strict;
        private final ValueClassTransformer valueClass;

        Transformer(Options opts) {
            this.opts = opts;
            this.strict = new StrictInitTransformer(opts.verbose);
            this.valueClass = opts.valueClasses ? new ValueClassTransformer(opts.allowFloating, opts.config) : null;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain pd, byte[] classfileBuffer) {
            try {
                if (className == null) return null;
                if (loader == null) return null;            // skip bootstrap/system classes
                if (!included(className)) return null;

                // Value-class promotion (when enabled) takes precedence: it is a
                // strict superset rewrite for the AnyVal classes it touches.
                if (valueClass != null) {
                    // Resolve a superclass's bytes from the same loader so the
                    // agent can verify an abstract @ValueClass value super class.
                    ValueClassTransformer.SuperResolver resolver = name -> {
                        try (var in = loader.getResourceAsStream(name + ".class")) {
                            return in == null ? null : in.readAllBytes();
                        } catch (Exception e) {
                            return null;
                        }
                    };
                    ValueClassTransformer.Result vc = valueClass.transform(classfileBuffer, resolver);
                    if (vc.changed()) {
                        if (opts.verbose) System.err.println("[value-class] " + vc.report());
                        return vc.bytes();
                    } else if (opts.verbose && vc.report() != null) {
                        System.err.println("[value-class] " + vc.report());
                    }
                }

                StrictInitTransformer.Result r = strict.transform(classfileBuffer);
                if (!r.changed()) return null;
                if (opts.verbose && r.report() != null) {
                    System.err.println("[strict-init] " + r.report());
                }
                return r.bytes();
            } catch (Throwable t) {
                // Never break class loading because of the retrofit.
                System.err.println("[strict-init] skipped " + className + ": " + t);
                return null;
            }
        }

        private boolean included(String internalName) {
            if (opts.includes.isEmpty()) return true;
            for (String p : opts.includes) {
                if (internalName.startsWith(p)) return true;
            }
            return false;
        }
    }

    private StrictInitAgent() {}
}
