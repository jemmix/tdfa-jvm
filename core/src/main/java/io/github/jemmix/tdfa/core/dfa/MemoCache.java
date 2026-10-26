package io.github.jemmix.tdfa.core.dfa;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutable field of a shared match-time artifact as a lazily
 * computed memo whose value is a <b>pure function</b> of (artifact, key):
 * warm == cold == concurrent, byte-for-byte, forever. The finality gate
 * (see {@code docs/runner-immutability.md}) admits a non-final instance
 * field on a shared artifact only while it carries this annotation and
 * sits on the gate's transition allowlist — every entry is deleted by
 * the work item that converts the memo to the honest-cache shape or
 * precomputes it, so the end state carries <b>zero</b> annotated fields.
 *
 * <p>The javadoc of an annotated field must argue its purity: what the
 * key is, why the value is a function of (artifact, key) alone, and why
 * racing duplicate computation cannot publish a wrong or partial value.
 *
 * <p>Test/debug statics and per-call carriers ({@code Matcher},
 * {@code MatchScratch}, {@code TagTree}) do <em>not</em> carry this
 * annotation: they are not caches — the gate categorizes them
 * separately.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MemoCache {
}
