package io.github.jemmix.tdfa.inlining;

import java.util.List;

/**
 * Hot-loop driver executed inside the forked, PrintInlining-enabled JVM of
 * {@link InliningGuardTest}. Compiles one pattern per emitted-ladder shape
 * and hammers find() far past every JIT threshold, so C2 compiles the
 * generated class's methods with real type profiles. The guard parses the
 * parent-side inlining log; this class only makes the code hot.
 */
public final class InliningDriver {

    record Shape(String regex, String haystack) {}

    static final List<Shape> SHAPES = List.of(
            new Shape("needle42hash", "noise noise needle42hash noise needle42hash x"),
            new Shape("[a-z]+ing", "the quick brown fox matching things doing something running"),
            new Shape("(\\d{3})-(\\d{4})", "call 555-1234 or 212-5555 or 999-0000 for more"),
            new Shape("\\w+@(\\w+)\\.[a-z]{2,4}", "mail bob@example.com or alice@test.org now"));

    public static void main(String[] argv) {
        int iters = argv.length > 0 ? Integer.parseInt(argv[0]) : 200_000;
        long sink = 0;
        for (Shape s : SHAPES) {
            var p = io.github.jemmix.tdfa.Pattern.compile(s.regex());
            for (int i = 0; i < iters; i++) {
                var m = p.matcher(s.haystack());
                sink += m.find() ? m.end() : 0;
            }
        }
        System.out.println("(sink " + sink + ")");
    }

    private InliningDriver() {}
}
