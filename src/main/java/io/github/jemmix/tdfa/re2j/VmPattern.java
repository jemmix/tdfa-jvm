package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.EngineFactory;
import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Disambiguation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared {@link Pattern} implementation (interpreter-agnostic shape): holds the
 * compiled {@link Regex} engines and the pattern/flags state. Used directly for
 * VM engines; the ASM backend with per-pattern generation subclasses its
 * structure into generated classes.
 */
final class VmPattern implements PatternSpi {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int flags;
    private transient Regex engine;
    // Lazily-compiled engine for matches() (anchored both ends) — half of
    // Pattern.compile's DFA work, paid only by callers that actually call
    // matches(). Racy single-check: Regex.compile is deterministic, so under a
    // race either compiled instance is correct; the volatile write publishes
    // the (effectively immutable) Regex safely.
    private transient volatile Regex wholeEngine;
    private transient java.util.function.Supplier<Regex> wholeSupplier;

    VmPattern(String pattern, int flags, Regex engine, java.util.function.Supplier<Regex> wholeSupplier) {
        this.pattern = pattern;
        this.flags = flags;
        this.engine = engine;
        this.wholeSupplier = wholeSupplier;
    }

    /** Shared compile pipeline (flag prefixes, disambiguation, lazy whole-engine). */
    static Pattern compile(String regex, int flags, EngineFactory factory,
                           io.github.jemmix.tdfa.unicode.UnicodeDataProvider unicodeProvider) {
        String flregex = regex;
        if ((flags & CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & MULTILINE) != 0)       flregex = "(?m)" + flregex;
        if ((flags & UNICODE_CHARACTER_CLASS) != 0) flregex = "(?u)" + flregex;
        Disambiguation disamb = (flags & LONGEST_MATCH) != 0
                ? Disambiguation.POSIX : Disambiguation.PERL;
        boolean disableUnicodeGroups = (flags & DISABLE_UNICODE_GROUPS) != 0;
        try {
            Regex engine = Regex.compile(flregex, factory, disamb, disableUnicodeGroups, false, unicodeProvider);
            // A second engine for matches() (anchored both ends), compiled lazily.
            // anchorBoth injects start/end anchors at the AST level (not text — safe
            // against \Q..\E), and the trailing anchor supplies context that prevents
            // the Perl leftmost-first DFA from pruning a longer alternative's
            // continuation once a shorter branch reaches accept
            // (e.g. (a|ab) against "ab" must retain the `ab` path). Same parse input
            // and a subset of the determinization work, so if the eager engine
            // compiles, this one cannot fail — deferring it can't move a compile
            // error past Pattern.compile().
            final String fl = flregex;
            java.util.function.Supplier<Regex> wholeSupplier =
                    () -> Regex.compile(fl, factory, disamb, disableUnicodeGroups, true, unicodeProvider);
            return new VmPattern(regex, flags, engine, wholeSupplier);
        } catch (RuntimeException e) {
            throw RE2.translate(e, regex);
        }
    }

    @Override public boolean matches(String input) {
        return matcher(input).matches();
    }

    @Override public boolean matches(byte[] input) {
        return matches(Pattern.Utf8.decode(input));
    }

    @Override public Matcher matcher(CharSequence input) {
        return new VmMatcher(this, input);
    }

    @Override public Matcher matcher(byte[] input) {
        return new VmMatcher(this, Pattern.Utf8.decode(input));
    }

    @Override public String[] split(String input) {
        return split(input, 0);
    }

    @Override public String[] split(String input, int limit) {
        Matcher m = matcher(input);
        List<String> result = new ArrayList<>();
        int emptiesSkipped = 0;
        int last = 0;

        while (m.find()) {
            if (last == 0 && m.end() == 0) {
                // Zero-width match at the beginning, skip (JDK8+ behavior).
                last = m.end();
                continue;
            }
            if (limit > 0 && result.size() == limit - 1) break;
            if (last == m.start()) {
                if (limit == 0) {
                    // Empty match, may or may not be trailing.
                    emptiesSkipped++;
                    last = m.end();
                    continue;
                }
            } else {
                // If emptiesSkipped > 0 then limit == 0 and we have non-trailing empty
                // matches to add before this non-empty match.
                while (emptiesSkipped > 0) {
                    result.add("");
                    emptiesSkipped--;
                }
            }
            result.add(input.substring(last, m.start()));
            last = m.end();
        }
        if (limit == 0 && last != input.length()) {
            while (emptiesSkipped > 0) {
                result.add("");
                emptiesSkipped--;
            }
            result.add(input.substring(last));
        }
        if (limit != 0 || result.isEmpty()) {
            result.add(input.substring(last));
        }
        return result.toArray(new String[0]);
    }

    @Override public void reset() { }

    /** Recompile the (transient) engines after deserialization, from {@code pattern}+{@code flags}. */
    private void readObject(java.io.ObjectInputStream in)
            throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        VmPattern tmp = (VmPattern) Pattern.compile(pattern, flags);
        this.engine = tmp.engine;
        this.wholeEngine = null;
        this.wholeSupplier = tmp.wholeSupplier;
    }

    @Override public int programSize() {
        return engine.programSize();
    }

    @Override public String pattern() { return pattern; }

    @Override public int flags() { return flags; }

    @Override public int groupCount() { return engine.groupCount(); }

    @Override public Map<String, Integer> namedGroups() { return Collections.unmodifiableMap(engine.namedGroups()); }

    @Override public String toString() { return pattern; }

    @Override public boolean equals(Object o) {
        // State-based equality across implementations (shared and generated):
        // re2j semantics — same pattern string + same flags.
        if (this == o) return true;
        if (!(o instanceof Pattern p)) return false;
        return flags == p.flags() && pattern.equals(p.pattern());
    }

    @Override public int hashCode() {
        return 31 * pattern.hashCode() + flags;
    }

    @Override public Regex engine() { return engine; }

    /** Engine for {@code matches()}: pattern wrapped in {@code \A(?:...)\z}, compiled on first use. */
    @Override public Regex wholeEngine() {
        Regex w = wholeEngine;
        if (w == null) {
            w = wholeSupplier.get();
            wholeEngine = w;
        }
        return w;
    }
}
