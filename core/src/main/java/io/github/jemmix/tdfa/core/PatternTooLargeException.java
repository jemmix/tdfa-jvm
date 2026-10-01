package io.github.jemmix.tdfa.core;

/**
 * Thrown when a pattern is syntactically valid but its compilation
 * exceeds one of the resource budgets: compile RAM
 * ({@code tdfa.budget.compile.memory}), compile CPU
 * ({@code tdfa.budget.compile.compute}), or retained match-time RAM
 * ({@code tdfa.budget.runtime.memory}). Message family:
 * {@code pattern too large: ...}, each naming the {@code -D} property to
 * raise.
 *
 * <p>Deliberately NOT a {@link PatternSyntaxException}: a pattern that is
 * too large is not malformed, and code that catches the syntax exception
 * must not accidentally swallow budget rejections (or vice versa). Raised
 * at the exact site that trips the budget — determinization caps, the
 * work meter, packing limits, the end-of-compile RAM check — and
 * propagates unwrapped to the caller.
 */
public class PatternTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PatternTooLargeException(String message) {
        super(message);
    }
}
