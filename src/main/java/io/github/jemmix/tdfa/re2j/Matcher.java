package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.vm.MatchResult;

/**
 * Drop-in replacement for {@code com.google.re2j.Matcher}, mimicking the
 * {@code java.util.regex.Matcher} API surface.
 *
 * <p>Stateful iterator: each call to {@link #find()}, {@link #matches()}, or
 * {@link #lookingAt()} updates the match state accessible via {@link #start()},
 * {@link #end()}, and {@link #group(int)}.
 */
public final class Matcher {

    private final Pattern pattern;
    private CharSequence input;
    private int inputLength;

    private MatchResult match;
    private boolean hasMatch;

    private int lastMatchStart;
    private int lastMatchEnd;
    private int appendPos;

    Matcher(Pattern pattern, CharSequence input) {
        this.pattern = pattern;
        this.input = input;
        this.inputLength = input.length();
    }

    /** Returns this matcher's {@link Pattern}. */
    public Pattern pattern() { return pattern; }

    /** Reset match state (keeps the same input). */
    public Matcher reset() {
        hasMatch = false;
        match = null;
        appendPos = 0;
        return this;
    }

    /** Reset with a new input. */
    public Matcher reset(CharSequence input) {
        this.input = input;
        this.inputLength = input.length();
        return reset();
    }

    /** Reset with UTF-8-decoded bytes as the new input (matches re2j's {@code MatcherInput.utf8}). */
    public Matcher reset(byte[] bytes) {
        return reset(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- match operations ----

    /** Match the entire input (anchored both ends). */
    public boolean matches() {
        Regex whole = pattern.wholeEngine();
        MatchResult m = whole.find(input, 0);
        hasMatch = m != null;
        if (hasMatch) {
            match = m;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
        }
        return hasMatch;
    }

    /** Match from the beginning of input (anchored start only). */
    public boolean lookingAt() {
        Regex engine = pattern.engine();
        MatchResult m = engine.find(input, 0);
        if (m != null && m.start(0) == 0) {
            match = m;
            hasMatch = true;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
            return true;
        }
        hasMatch = false;
        return false;
    }

    /** Find the next match, searching from the end of the last match (or start). */
    public boolean find() {
        int start;
        if (hasMatch) {
            start = lastMatchEnd;
            if (lastMatchStart == lastMatchEnd) start++;
        } else {
            start = appendPos;
        }
        if (start > inputLength) {
            hasMatch = false;
            return false;
        }
        Regex engine = pattern.engine();
        MatchResult m = engine.find(input, start);
        if (m == null) {
            hasMatch = false;
            return false;
        }
        match = m;
        hasMatch = true;
        lastMatchStart = m.start(0);
        lastMatchEnd = m.end(0);
        return true;
    }

    /** Reset and find from the given {@code start} position. */
    public boolean find(int start) {
        if (start < 0 || start > inputLength)
            throw new IndexOutOfBoundsException("start index out of bounds: " + start);
        reset();
        appendPos = start;
        return find();
    }

    // ---- match results ----

    /** Start of the overall match. */
    public int start() { return start(0); }

    /** End of the overall match. */
    public int end() { return end(0); }

    /** Start of group {@code g} (0 = overall match). */
    public int start(int group) {
        ensureMatch();
        return match.start(group);
    }

    /** End of group {@code g} (0 = overall match). */
    public int end(int group) {
        ensureMatch();
        return match.end(group);
    }

    /** Substring of the overall match. */
    public String group() { return group(0); }

    /** Substring of group {@code g}, or {@code null} if the group didn't participate. */
    public String group(int group) {
        int s = start(group);
        int e = end(group);
        if (s < 0 || e < 0) return null;
        return input.subSequence(s, e).toString();
    }

    /** Substring of the named group, or {@code null} if the group didn't participate. */
    public String group(String name) {
        return group(groupIndex(name));
    }

    /** Start of the named group. */
    public int start(String name) {
        return start(groupIndex(name));
    }

    /** End of the named group. */
    public int end(String name) {
        return end(groupIndex(name));
    }

    /** Number of capturing groups (excluding group 0). */
    public int groupCount() {
        return pattern.groupCount();
    }

    /** Pending parity — DFA complexity metric not yet exposed. */
    public int programSize() {
        throw new UnsupportedOperationException("programSize() pending parity implementation");
    }

    // ---- replacement ----

    /** Replace all matches with {@code replacement} (supports {@code $N} backreferences). */
    public String replaceAll(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        while (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    /** Replace the first match with {@code replacement}. */
    public String replaceFirst(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        if (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    /**
     * Append the text between the append position and the start of the current match,
     * then append {@code replacement} with {@code $N} group backreferences substituted.
     */
    public Matcher appendReplacement(StringBuilder sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        appendReplacementInternal(sb, replacement);
        return this;
    }

    /** {@link StringBuffer} overload — delegates to {@link StringBuilder} variant. */
    public Matcher appendReplacement(StringBuffer sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        StringBuilder tmp = new StringBuilder();
        appendReplacementInternal(tmp, replacement);
        sb.append(tmp);
        return this;
    }

    /** Append the remaining unmatched tail. */
    public StringBuilder appendTail(StringBuilder sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

    /** {@link StringBuffer} overload. */
    public StringBuffer appendTail(StringBuffer sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

    /** Quote {@code \} and {@code $} in {@code s} for use as a literal replacement. */
    public static String quoteReplacement(String s) {
        if (s.indexOf('\\') < 0 && s.indexOf('$') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- internal ----

    private void ensureMatch() {
        if (!hasMatch) throw new IllegalStateException("perhaps no match attempted");
    }

    private int groupIndex(String name) {
        Integer idx = pattern.namedGroups().get(name);
        if (idx == null) throw new IllegalArgumentException("group '" + name + "' not found");
        return idx;
    }

    private void appendReplacementInternal(StringBuilder sb, String replacement) {
        int gc = groupCount();
        int last = 0;
        int i = 0;
        int m = replacement.length();
        for (; i < m - 1; i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (last < i) sb.append(replacement, last, i);
                i++;
                last = i;
                continue;
            }
            if (c == '$') {
                char c2 = replacement.charAt(i + 1);
                if (c2 >= '0' && c2 <= '9') {
                    int n = c2 - '0';
                    if (last < i) sb.append(replacement, last, i);
                    for (i += 2; i < m; i++) {
                        c2 = replacement.charAt(i);
                        if (c2 < '0' || c2 > '9' || n * 10 + c2 - '0' > gc) break;
                        n = n * 10 + c2 - '0';
                    }
                    if (n > gc)
                        throw new IndexOutOfBoundsException("n > number of groups: " + n);
                    String g = group(n);
                    if (g != null) sb.append(g);
                    last = i;
                    i--;
                    continue;
                } else if (c2 == '{') {
                    if (last < i) sb.append(replacement, last, i);
                    i += 2;
                    int j = i;
                    while (j < m && replacement.charAt(j) != '}' && replacement.charAt(j) != ' ') j++;
                    if (j >= m || replacement.charAt(j) != '}')
                        throw new IllegalArgumentException("named capture group is missing trailing '}'");
                    String gName = replacement.substring(i, j);
                    String gVal = group(gName);
                    if (gVal != null) sb.append(gVal);
                    last = j + 1;
                    i = j;
                    continue;
                }
            }
        }
        if (last < m) sb.append(replacement, last, m);
    }

    int inputLength() { return inputLength; }

    String substring(int start, int end) {
        return input.subSequence(start, end).toString();
    }
}
