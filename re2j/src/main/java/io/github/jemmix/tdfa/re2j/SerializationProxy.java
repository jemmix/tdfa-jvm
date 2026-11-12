package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.core.compile.SerialProxy;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.ObjectStreamException;
import java.io.Serializable;

/**
 * The re2j facade's serialization proxy — the minted shells' anchor.
 * The minted shells ({@code Shells}) live in child classloaders that
 * will not exist in the reading process, so each serializes as this
 * proxy: pattern + flags + lane + provider identity. Read back through
 * this facade's own compile funnel — a direct call, no reflection — so
 * the roster validation, the RE2-lane default and the provider default
 * all apply from one place. The lane travels as data ({@link Semantics}
 * bits): a pattern compiled through a per-axis probe keeps its probe
 * lane across the wire, and a serialized re2j-facade pattern stays re2j
 * on read.
 *
 * <p>Provider pinning round-trips: a pattern compiled against a pinned
 * {@link UnicodeDataProvider} keeps that provider (resolved per the
 * UnicodeDataProvider serialization convention, machinery-resolved by
 * {@link SerialProxy#resolveProvider}); a pattern compiled against the
 * process default serializes without a provider and recompiles against
 * the READER's default. Failures throw {@link java.io.InvalidObjectException}
 * — loud, never a silent fallback to different tables or a different
 * lane.
 *
 * <p>Public-but-internal: referenced by name from this facade's minted
 * shells (which live in the gen package, another runtime package) —
 * not shipped API.
 */
public final class SerializationProxy implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String pattern;

    private final int flags;

    private final int semanticsBits;

    /**
     * FQCN of the pinned UnicodeDataProvider, or {@code null} = process default.
     */
    private final String providerClass;

    /** The policy's component payloads ({@link SerialProxy.Components}),
     *  or {@code null} = a v1 stream (stock components derived from the
     *  bits). */
    private final SerialProxy.Components components;

    private SerializationProxy(String pattern, int flags, int semanticsBits, String providerClass,
        SerialProxy.Components components) {
        this.pattern = pattern;
        this.flags = flags;
        this.semanticsBits = semanticsBits;
        this.providerClass = providerClass;
        this.components = components;
    }

    /** The wrapper-emitter hook: the proxy for a wrapped machinery pattern. */
    public static Object serialProxy(TDFAPattern p) {
        UnicodeDataProvider provider = p.unicodeProvider();
        return new SerializationProxy(p.pattern(), p.flags(), p.semantics().bits(),
            provider == null ? null : provider.getClass().getName(), SerialProxy.componentsOf(p.semantics()));
    }

    private Object readResolve() throws ObjectStreamException {
        UnicodeDataProvider provider = providerClass == null ? null : SerialProxy.resolveProvider(providerClass);
        return Lane.compile(pattern, flags, null, provider, null, SerialProxy.semantics(semanticsBits, components));
    }
}
