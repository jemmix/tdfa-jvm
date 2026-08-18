package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

import java.util.function.Supplier;

/**
 * {@link Pattern} compilation orchestration: flags &rarr; inline-flag prefix,
 * pipeline (parse &rarr; TNFA &rarr; TDFA), engine-source resolution, and
 * shell-or-shared implementation selection.
 *
 * <p>Engine source resolution (provenance-based, no capability negotiation):
 * <ul>
 *   <li>{@code -Dtdfa.engine=VM} &rarr; shared implementation over the
 *       interpreter — no code generation anywhere;</li>
 *   <li>explicit {@link RegexEngineFactory} &rarr; shell emitted around the
 *       factory's engines ({@code RegexEngine}-typed field — monomorphic
 *       per pattern), shared implementation as emission-failure fallback;</li>
 *   <li>default &rarr; ASM per-pattern engine generation with a
 *       concrete-typed shell, shared implementation as fallback.</li>
 * </ul>
 */
final class PatternCompiler {

    private PatternCompiler() { }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           UnicodeDataProvider provider) {
        if (regex == null) throw new NullPointerException("pattern is null");
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
        }
        String flregex = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & Pattern.DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & Pattern.MULTILINE) != 0)       flregex = "(?m)" + flregex;
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) flregex = "(?u)" + flregex;
        boolean longest = (flags & Pattern.LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        final UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        final String fl = flregex;
        try {
            Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov);
            Tdfa tdfa = Tdfa.compile(nfa, longest);
            int ps = tdfa.stateCount();

            if (vmSwitched()) {
                return new TDFAPattern(regex, flags, ps,
                        new TdfaRunner(tdfa), anchoredVm(fl, disableUnicodeGroups, longest, prov));
            }

            if (factory != null) {
                RegexEngine eng = factory.create(tdfa);
                Supplier<RegexEngine> whole =
                        () -> factory.create(anchorTdfa(fl, disableUnicodeGroups, longest, prov));
                try {
                    return (Pattern) io.github.jemmix.tdfa.asm.ShellEmitter.emit(
                            new io.github.jemmix.tdfa.asm.ShellEmitter.Spec(
                                    regex, flags, ps, eng, whole, null));
                } catch (RuntimeException ex) {
                    if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                    return new TDFAPattern(regex, flags, ps, eng, whole);
                }
            }

            // Default: ASM per-pattern engine + concrete-typed shell.
            io.github.jemmix.tdfa.asm.TdfaAsmBackend.Generated gen;
            try {
                gen = io.github.jemmix.tdfa.asm.TdfaAsmBackend.generate(tdfa);
            } catch (RuntimeException genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                return new TDFAPattern(regex, flags, ps,
                        new TdfaRunner(tdfa), anchoredVm(fl, disableUnicodeGroups, longest, prov));
            }
            try {
                return (Pattern) io.github.jemmix.tdfa.asm.ShellEmitter.emit(
                        new io.github.jemmix.tdfa.asm.ShellEmitter.Spec(
                                regex, flags, ps, gen.engine(),
                                anchoredAsm(fl, disableUnicodeGroups, longest, prov),
                                gen.owner()));
            } catch (RuntimeException ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                return new TDFAPattern(regex, flags, ps,
                        new TdfaRunner(tdfa), anchoredVm(fl, disableUnicodeGroups, longest, prov));
            }
        } catch (RuntimeException e) {
            throw io.github.jemmix.tdfa.core.CompiledRegex.translate(e, regex);
        }
    }

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH
            | Pattern.UNICODE_CHARACTER_CLASS;

    /** {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile. */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }

    private static Tdfa anchorTdfa(String flregex, boolean disableUnicodeGroups,
                                   boolean longest, UnicodeDataProvider prov) {
        Tnfa an = Tnfa.compile(flregex, disableUnicodeGroups, true, prov);
        return Tdfa.compile(an, longest);
    }

    private static Supplier<RegexEngine> anchoredVm(String flregex, boolean disableUnicodeGroups,
                                                    boolean longest, UnicodeDataProvider prov) {
        return () -> new TdfaRunner(anchorTdfa(flregex, disableUnicodeGroups, longest, prov));
    }

    private static Supplier<RegexEngine> anchoredAsm(String flregex, boolean disableUnicodeGroups,
                                                     boolean longest, UnicodeDataProvider prov) {
        return () -> {
            Tdfa at = anchorTdfa(flregex, disableUnicodeGroups, longest, prov);
            try {
                return io.github.jemmix.tdfa.asm.TdfaAsmBackend.generate(at).engine();
            } catch (RuntimeException genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                return new TdfaRunner(at);
            }
        };
    }
}
