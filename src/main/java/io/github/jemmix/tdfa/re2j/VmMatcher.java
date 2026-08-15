package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.vm.MatchResult;

/**
 * Shared {@link Matcher} implementation: stateful iteration over a
 * {@link VmPattern}'s engines via the {@link Regex} API.
 */
final class VmMatcher implements Matcher {

    private final PatternSpi pattern;
    private CharSequence input;
    private int inputLength;

    private MatchResult match;
    private boolean hasMatch;

    private int lastMatchStart;
    private int lastMatchEnd;
    private int appendPos;

    VmMatcher(PatternSpi pattern, CharSequence input) {
        this.pattern = pattern;
        this.input = input;
        this.inputLength = input.length();
    }

    @Override public Pattern pattern() { return pattern; }

    private PatternSpi spi() { return pattern; }

    @Override public Matcher reset() {
        hasMatch = false;
        match = null;
        appendPos = 0;
        return this;
    }

    @Override public Matcher reset(CharSequence input) {
        this.input = input;
        this.inputLength = input.length();
        return reset();
    }

    @Override public Matcher reset(byte[] bytes) {
        return reset(Pattern.Utf8.decode(bytes));
    }

    // ---- match operations ----

    @Override public boolean matches() {
        Regex whole = spi().wholeEngine();
        MatchResult m = whole.find(input, 0);
        hasMatch = m != null;
        if (hasMatch) {
            match = m;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
        }
        return hasMatch;
    }

    @Override public boolean lookingAt() {
        Regex engine = spi().engine();
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

    @Override public boolean find() {
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
        Regex engine = spi().engine();
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

    @Override public boolean find(int start) {
        if (start < 0 || start > inputLength)
            throw new IndexOutOfBoundsException("start index out of bounds: " + start);
        reset();
        appendPos = start;
        return find();
    }

    // ---- match results ----

    @Override public int start() { return start(0); }

    @Override public int end() { return end(0); }

    @Override public int start(int group) {
        ensureMatch();
        return match.start(group);
    }

    @Override public int end(int group) {
        ensureMatch();
        return match.end(group);
    }

    @Override public String group() { return group(0); }

    @Override public String group(int group) {
        int s = start(group);
        int e = end(group);
        if (s < 0 || e < 0) return null;
        return input.subSequence(s, e).toString();
    }

    @Override public String group(String name) {
        return group(groupIndex(name));
    }

    @Override public int start(String name) {
        return start(groupIndex(name));
    }

    @Override public int end(String name) {
        return end(groupIndex(name));
    }

    @Override public int groupCount() {
        return pattern.groupCount();
    }

    @Override public int programSize() {
        return pattern.programSize();
    }

    // ---- replacement ----

    @Override public String replaceAll(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        while (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    @Override public String replaceFirst(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        if (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    @Override public Matcher appendReplacement(StringBuilder sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        appendReplacementInternal(sb, replacement);
        return this;
    }

    @Override public Matcher appendReplacement(StringBuffer sb, String replacement) {
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

    @Override public StringBuilder appendTail(StringBuilder sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

    @Override public StringBuffer appendTail(StringBuffer sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
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
