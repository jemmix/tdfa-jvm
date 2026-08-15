package io.github.jemmix.tdfa.re2j;

/**
 * Drop-in replacement for {@code com.google.re2j.Matcher}, mimicking the
 * {@code java.util.regex.Matcher} API surface.
 *
 * <p>Interface since the kernel refactor: {@link Pattern#matcher} returns the
 * shared implementation, or — under a per-pattern-generating engine factory —
 * a generated Matcher whose {@link #find()}/{@link #matches()} calls devirtualize
 * and inline straight into the pattern's machinery.
 *
 * <p>Stateful iterator: each call to {@link #find()}, {@link #matches()}, or
 * {@link #lookingAt()} updates the match state accessible via {@link #start()},
 * {@link #end()}, and {@link #group(int)}.
 */
public interface Matcher {

    /** Returns this matcher's {@link Pattern}. */
    Pattern pattern();

    /** Reset match state (keeps the same input). */
    Matcher reset();

    /** Reset with a new input. */
    Matcher reset(CharSequence input);

    /** Reset with UTF-8-decoded bytes as the new input (matches re2j's {@code MatcherInput.utf8}). */
    Matcher reset(byte[] bytes);

    // ---- match operations ----

    /** Match the entire input (anchored both ends). */
    boolean matches();

    /** Match from the beginning of input (anchored start only). */
    boolean lookingAt();

    /** Find the next match, searching from the end of the last match (or start). */
    boolean find();

    /** Reset and find from the given {@code start} position. */
    boolean find(int start);

    // ---- match results ----

    /** Start of the overall match. */
    int start();

    /** End of the overall match. */
    int end();

    /** Start of group {@code g} (0 = overall match). */
    int start(int group);

    /** End of group {@code g} (0 = overall match). */
    int end(int group);

    /** Substring of the overall match. */
    String group();

    /** Substring of group {@code g}, or {@code null} if the group didn't participate. */
    String group(int group);

    /** Substring of the named group, or {@code null} if the group didn't participate. */
    String group(String name);

    /** Start of the named group. */
    int start(String name);

    /** End of the named group. */
    int end(String name);

    /** Number of capturing groups (excluding group 0). */
    int groupCount();

    /** Cost estimate for this matcher's pattern — see {@link Pattern#programSize()}. */
    int programSize();

    // ---- replacement ----

    /** Replace all matches with {@code replacement} (supports {@code $N} backreferences). */
    String replaceAll(String replacement);

    /** Replace the first match with {@code replacement} (supports {@code $N} backreferences). */
    String replaceFirst(String replacement);

    /**
     * Append the text between the append position and the start of the current match,
     * then append {@code replacement} with {@code $N} group backreferences substituted.
     */
    Matcher appendReplacement(StringBuilder sb, String replacement);

    /** {@code StringBuffer} overload — delegates to {@link StringBuilder} variant. */
    Matcher appendReplacement(StringBuffer sb, String replacement);

    /** Append the remaining unmatched tail. */
    StringBuilder appendTail(StringBuilder sb);

    /** {@code StringBuffer} overload. */
    StringBuffer appendTail(StringBuffer sb);

    /** Quote {@code \} and {@code $} in {@code s} for use as a literal replacement. */
    static String quoteReplacement(String s) {
        if (s.indexOf('\\') < 0 && s.indexOf('$') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
