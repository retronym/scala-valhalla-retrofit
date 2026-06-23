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
                + (opts.includes.isEmpty() ? "; include=<all>" : "; include=" + opts.includes));
        inst.addTransformer(new Transformer(opts), true);
    }

    private record Options(List<String> includes, boolean verbose) {
        static Options parse(String args) {
            List<String> includes = List.of();
            boolean verbose = false;
            if (args != null && !args.isBlank()) {
                for (String part : args.split(";")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    if (part.equals("verbose")) {
                        verbose = true;
                    } else if (part.startsWith("include=")) {
                        String v = part.substring("include=".length());
                        includes = Arrays.stream(v.split(","))
                                .map(s -> s.trim().replace('.', '/'))
                                .filter(s -> !s.isEmpty())
                                .toList();
                    }
                }
            }
            return new Options(includes, verbose);
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        private final Options opts;
        private final StrictInitTransformer engine;

        Transformer(Options opts) {
            this.opts = opts;
            this.engine = new StrictInitTransformer(opts.verbose);
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain pd, byte[] classfileBuffer) {
            try {
                if (className == null) return null;
                if (loader == null) return null;            // skip bootstrap/system classes
                if (!included(className)) return null;

                StrictInitTransformer.Result r = engine.transform(classfileBuffer);
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
