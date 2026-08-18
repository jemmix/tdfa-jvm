package io.github.jemmix.tdfa.core;

import java.util.function.Supplier;

/**
 * Stateful matcher over one input: find-iteration, group access, and the
 * full replacement machinery ({@code replaceAll}/{@code replaceFirst}/
 * {@code appendReplacement}/{@code appendTail}), mirroring the re2j /
 * {@code java.util.regex.Matcher} surface on top of a {@link RegexEngine}.
 *
 * <p><b>Shell contract.</b> This class is also the base for per-pattern
 * generated matchers (ASM tier): the bookkeeping state below is
 * {@code protected}, and the three hot entry points — {@link #find()},
 * {@link #matches()}, {@link #lookingAt()} — are overridable. A generated
 * subclass inlines the bookkeeping and calls its engine directly with a
 * statically-known receiver type, so the call chain devirtualizes
 * end-to-end; all cold machinery is inherited unchanged (one brain, thin
 * generated hot paths).
 */
public class Matcher {

    private final RegexEngine engine;
    private final Supplier<RegexEngine> wholeEngine;

    protected CharSequence input;
    protected int inputLength;

    protected MatchResult match;
    protected boolean hasMatch;

    protected int lastMatchStart;
    protected int lastMatchEnd;
    protected int appendPos;

    public Matcher(RegexEngine engine, Supplier<RegexEngine> wholeEngine, CharSequence input) {
        this.engine = engine;
        this.wholeEngine = wholeEngine;
        this.input = input;
        this.inputLength = input.length();
    }

    public Matcher reset() {
        hasMatch = false;
        match = null;
        appendPos = 0;
        return this;
    }

    public Matcher reset(CharSequence input) {
        this.input = input;
        this.inputLength = input.length();
        return reset();
    }

    /** Reset with UTF-8-decoded bytes as the new input (re2j's {@code MatcherInput.utf8}). */
    public Matcher reset(byte[] bytes) {
        return reset(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- match operations ----

    public boolean matches() {
        MatchResult m = wholeEngine.get().match(input, 0);
        hasMatch = m != null;
        if (hasMatch) {
            match = m;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
        }
        return hasMatch;
    }

    public boolean lookingAt() {
        MatchResult m = engine.match(input, 0);
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
        MatchResult m = engine.match(input, start);
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

    public boolean find(int start) {
        if (start < 0 || start > inputLength)
            throw new IndexOutOfBoundsException("start index out of bounds: " + start);
        reset();
        appendPos = start;
        return find();
    }

    // ---- match results ----

    public int start() { return start(0); }

    public int end() { return end(0); }

    public int start(int group) {
        ensureMatch();
        return match.start(group);
    }

    public int end(int group) {
        ensureMatch();
        return match.end(group);
    }

    public String group() { return group(0); }

    public String group(int group) {
        int s = start(group);
        int e = end(group);
        if (s < 0 || e < 0) return null;
        return input.subSequence(s, e).toString();
    }

    public String group(String name) {
        return group(groupIndex(name));
    }

    public int start(String name) {
        return start(groupIndex(name));
    }

    public int end(String name) {
        return end(groupIndex(name));
    }

    public int groupCount() {
        return engine.groupCount();
    }

    public int programSize() {
        return engine.programSize();
    }

    // ---- replacement ----

    public String replaceAll(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        while (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    public String replaceFirst(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        if (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    public Matcher appendReplacement(StringBuilder sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        appendReplacementInternal(sb, replacement);
        return this;
    }

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

    public StringBuilder appendTail(StringBuilder sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

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
        Integer idx = engine.namedGroups().get(name);
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
}
