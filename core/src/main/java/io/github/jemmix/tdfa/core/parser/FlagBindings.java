package io.github.jemmix.tdfa.core.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The inline-flag letter bindings: which {@link FlagAction} each
 * inline-flag letter activates — the facade-to-core seam for the one
 * piece of dialect surface that is pure spelling. The vocabulary is
 * closed ({@link FlagAction}, the parser's named behaviors); only the
 * binding is data, so a surface is a value: printable, comparable,
 * and honest about its alphabet. The two shipped readings are the
 * stock lanes below; a custom surface composes a subset or a renamed
 * alphabet without core changes (bound through the {@code Tnfa}
 * compile seam), and a letter it does <b>not</b> bind is an error —
 * the parser rejects it with the dialect's own {@code "invalid or
 * unsupported Perl syntax"} message, so a subset surface shrinks the
 * accepted syntax exactly as much as it intends.
 *
 * <p>The engine's {@code u}/{@code U} letters are crossed vs the
 * JDK's: engine {@code (?u)} is JDK {@code (?U)}
 * ({@link FlagAction#UNICODE_CHARACTER_CLASS}), and engine
 * {@code (?U)} is JDK {@code (?u)} — {@link FlagAction#UNICODE_CASE}
 * under the JUR reading, {@link FlagAction#UNGREEDY} under the RE2
 * reading (the only letter the two stock lanes disagree on).
 *
 * <p>Immutable value class — field equality over the binding map (a
 * lane's identity for the campaign harness and the parity tables);
 * lookups are array-indexed and allocation-free on the parse path.
 */
public final class FlagBindings {

    /** The java.util.regex alphabet: {@code i s m u} plus
     *  {@code U} = scoped Unicode case (the jur facade's reading). */
    public static final FlagBindings JUR_LANE = builder().bind('i', FlagAction.CASE_INSENSITIVE)
        .bind('s', FlagAction.DOTALL)
        .bind('m', FlagAction.MULTILINE)
        .bind('u', FlagAction.UNICODE_CHARACTER_CLASS)
        .bind('U', FlagAction.UNICODE_CASE)
        .build();

    /** The RE2/re2j alphabet: {@code i s m u} plus
     *  {@code U} = ungreedy (the re2j facade's reading). */
    public static final FlagBindings RE2_LANE = builder().bind('i', FlagAction.CASE_INSENSITIVE)
        .bind('s', FlagAction.DOTALL)
        .bind('m', FlagAction.MULTILINE)
        .bind('u', FlagAction.UNICODE_CHARACTER_CLASS)
        .bind('U', FlagAction.UNGREEDY)
        .build();

    private final FlagAction[] byLetter = new FlagAction[128];

    private final Map<Character, FlagAction> view;

    private FlagBindings(Map<Character, FlagAction> map) {
        for (Map.Entry<Character, FlagAction> e : map.entrySet()) {
            byLetter[e.getKey()] = e.getValue();
        }
        view = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The behavior {@code letter} activates, or {@code null} when the
     * surface does not bind the letter (the parser rejects it — never
     * a silent no-op).
     */
    public FlagAction actionFor(char letter) {
        return letter < 128 ? byLetter[letter] : null;
    }

    /** The bindings as an immutable map, letters in binding order. */
    public Map<Character, FlagAction> asMap() {
        return view;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlagBindings)) {
            return false;
        }
        return view.equals(((FlagBindings) o).view);
    }

    @Override
    public int hashCode() {
        return view.hashCode() * 31 + FlagBindings.class.hashCode();
    }

    @Override
    public String toString() {
        return "FlagBindings" + view;
    }

    /**
     * Binds ASCII letters only (the parser's flag runs are ASCII;
     * any other letter is unbindable and therefore always an error),
     * and rejects rebinding a letter already bound — a surface with
     * two opinions about one letter is a construction bug, not a
     * last-wins policy.
     */
    public static final class Builder {

        private final LinkedHashMap<Character, FlagAction> map = new LinkedHashMap<>();

        /** Bind {@code letter} to {@code action}; returns this. */
        public Builder bind(char letter, FlagAction action) {
            if (letter < 'A' || letter > 'z' || (letter > 'Z' && letter < 'a')) {
                throw new IllegalArgumentException("inline-flag letters are ASCII letters: '" + letter + "'");
            }
            Objects.requireNonNull(action, "action");
            if (map.putIfAbsent(letter, action) != null) {
                throw new IllegalArgumentException("letter already bound: '" + letter + "'");
            }
            return this;
        }

        public FlagBindings build() {
            return new FlagBindings(map);
        }

        private Builder() {
        }
    }
}
