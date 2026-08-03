package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Disambiguation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Drop-in replacement for {@code com.google.re2j.Pattern}, mimicking the
 * {@code java.util.regex.Pattern} API surface.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)}.
 * Obtain a {@link Matcher} via {@link #matcher(CharSequence)}.
 *
 * <p>Supported flags: {@link #CASE_INSENSITIVE}, {@link #DOTALL}, {@link #MULTILINE}, {@link #LONGEST_MATCH}.
 * {@link #DISABLE_UNICODE_GROUPS} is accepted but enforcement is pending.
 */
public final class Pattern {

    /** Flag: case insensitive matching. */
    public static final int CASE_INSENSITIVE = 1;

    /** Flag: dot ({@code .}) matches all characters, including newline. */
    public static final int DOTALL = 2;

    /** Flag: multiline matching ({@code ^}/{@code $} at line boundaries). */
    public static final int MULTILINE = 4;

    /** Flag: matches longest possible string (POSIX leftmost-longest). */
    public static final int LONGEST_MATCH = 16;

    /** Flag: disable Unicode groups. Pending parity — flag is accepted but Unicode groups remain enabled. */
    public static final int DISABLE_UNICODE_GROUPS = 8;

    private final String pattern;
    private final int flags;
    private final Regex engine;

    Pattern(String pattern, int flags, Regex engine) {
        this.pattern = pattern;
        this.flags = flags;
        this.engine = engine;
    }

    /** Compile {@code regex} with default flags (Perl leftmost-first semantics). */
    public static Pattern compile(String regex) {
        return compile(regex, 0);
    }

    /** Compile {@code regex} with the given {@code flags} (bitwise OR of the flag constants). */
    public static Pattern compile(String regex, int flags) {
        String flregex = regex;
        if ((flags & CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & MULTILINE) != 0)       flregex = "(?m)" + flregex;
        Disambiguation disamb = (flags & LONGEST_MATCH) != 0
                ? Disambiguation.POSIX : Disambiguation.PERL;
        try {
            Regex engine = Regex.compile(flregex, Boolean.getBoolean("tdfa.asm"), disamb);
            return new Pattern(regex, flags, engine);
        } catch (RuntimeException e) {
            throw RE2.translate(e, regex);
        }
    }

    /** Convenience: compile and match the entire input. */
    public static boolean matches(String regex, CharSequence input) {
        return compile(regex).matcher(input).matches();
    }

    /** Match the entire input against this pattern. */
    public boolean matches(String input) {
        return matcher(input).matches();
    }

    /** Create a {@link Matcher} for this pattern against {@code input}. */
    public Matcher matcher(CharSequence input) {
        return new Matcher(this, input);
    }

    /** Split {@code input} around matches of this pattern. Trailing empty strings are omitted. */
    public String[] split(String input) {
        return split(input, 0);
    }

    /** Split {@code input} with a limit on the number of result strings. */
    public String[] split(String input, int limit) {
        Matcher m = matcher(input);
        List<String> result = new ArrayList<>();
        int last = 0;
        boolean emptiesSkipped = false;

        while (m.find()) {
            if (last == 0 && m.end() == 0) {
                last = m.end();
                continue;
            }
            if (limit > 0 && result.size() == limit - 1) break;
            if (last == m.start()) {
                if (limit == 0) {
                    emptiesSkipped = true;
                    last = m.end();
                    continue;
                }
            } else if (emptiesSkipped) {
                result.add("");
                emptiesSkipped = false;
            }
            result.add(input.substring(last, m.start()));
            last = m.end();
        }
        if (limit == 0 && last != input.length()) {
            if (emptiesSkipped) result.add("");
            result.add(input.substring(last));
        }
        if (limit != 0 || result.isEmpty()) {
            result.add(input.substring(last));
        }
        return result.toArray(new String[0]);
    }

    /** Quote regexp metacharacters in {@code s}. */
    public static String quote(String s) {
        return RE2.quoteMeta(s);
    }

    /** Returns the pattern string. */
    public String pattern() { return pattern; }

    /** Returns the flags. */
    public int flags() { return flags; }

    /** Number of capturing groups (excluding group 0). */
    public int groupCount() { return engine.groupCount(); }

    /** Unmodifiable name→index map for named capturing groups. */
    public Map<String, Integer> namedGroups() { return Collections.unmodifiableMap(engine.namedGroups()); }

    @Override public String toString() { return pattern; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pattern p)) return false;
        return flags == p.flags && pattern.equals(p.pattern);
    }

    @Override public int hashCode() {
        return 31 * pattern.hashCode() + flags;
    }

    Regex engine() { return engine; }
}
