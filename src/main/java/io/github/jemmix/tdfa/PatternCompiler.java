package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.ShellEmitter;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.Budgets;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tdfa.WorkMeter;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * {@link Pattern} compilation: fold the flags into an inline-flag prefix,
 * parse to a TNFA, determinize, compile a cut-free whole-match artifact
 * when the pike cut deleted continuations, and translate each artifact to
 * an engine through one source ({@code -Dtdfa.engine=VM} interpreter, a
 * custom {@link RegexEngineFactory}, or per-pattern ASM generation).
 * Everything builds inside {@code compile()}; any budget rejection fails
 * the compile as a translated
 * {@link io.github.jemmix.tdfa.core.PatternSyntaxException}.
 */
final class PatternCompiler {

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH | Pattern.UNICODE_CHARACTER_CLASS;

    private PatternCompiler() {
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider) {
        return compile(regex, flags, factory, provider, null);
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider, CompileObserver observer) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(
                            "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
        }
        String fl = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) {
            fl = "(?i)" + fl;
        }
        if ((flags & Pattern.DOTALL) != 0) {
            fl = "(?s)" + fl;
        }
        if ((flags & Pattern.MULTILINE) != 0) {
            fl = "(?m)" + fl;
        }
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) {
            fl = "(?u)" + fl;
        }
        boolean longest = (flags & Pattern.LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        CompileObserver obs = observer != null ? observer : CompileObserver.NONE;
        try {
            // One CPU ledger for the whole compile: the front-end, the find
            // determinization and the cut-free whole determinization (when
            // needed) all debit the same pool.
            WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov, obs, ledger);
            Tdfa find = Tdfa.compile(nfa, longest, obs, ledger.fork(0));
            Tdfa whole = find;
            if (find.pikeCutMatters()) {
                obs.note("pikeCut", "cut-free whole artifact compiled");
                whole = Tdfa.compileUnpruned(nfa, longest, obs, ledger.fork(0));
            }
            boolean shared = whole == find;
            int ps = find.stateCount();
            // A pattern keeping a second (whole) engine splits the runtime
            // memo budget so the pattern's combined memos stay within one budget.
            long memoBudget = Budgets.runtimeMemoryBytes() / (shared ? 1 : 2);

            // One engine translation for both artifacts.
            long t0 = System.nanoTime();
            RegexEngine eng = engineOf(find, factory, memoBudget);
            RegexEngine wholeEng = shared ? eng : engineOf(whole, factory, memoBudget);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);

            if (vmSwitched()) {
                obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
                return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
            }

            // Shell around the engines — concrete-typed for generated ones
            // (the engine's own class names the shell's field type and
            // classes). A shell emission problem degrades to the shared
            // Pattern implementation with the same engines.
            try {
                String owner = factory == null ? eng.getClass().getName().replace('.', '/') : null;
                Pattern p = (Pattern) ShellEmitter.emit(new ShellEmitter.Spec(regex, flags, ps, eng, wholeEng, owner,
                                provider));
                obs.note("engine", factory == null ? "generated" : "byo-shell");
                return p;
            } catch (RuntimeException ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) {
                    ex.printStackTrace();
                }
                obs.note("engine", factory == null ? "shared (shell emission failed)" : "shared (byo-shell emission failed)");
                return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
            }
        } catch (RuntimeException e) {
            throw CompiledRegex.translate(e, regex);
        }
    }

    /**
     * The compile's engine source, applied to every artifact of the compile:
     * the shared interpreter under {@code -Dtdfa.engine=VM}, the custom
     * factory's engine, or a generated per-pattern class. Emission failures
     * (IllegalStateException, LinkageError from broken generated bytecode)
     * propagate — they are bugs, not shapes to route around.
     */
    private static RegexEngine engineOf(Tdfa tdfa, RegexEngineFactory factory, long memoBudget) {
        if (vmSwitched()) {
            return new TdfaRunner(tdfa, memoBudget);
        }
        if (factory != null) {
            return factory.create(tdfa);
        }
        return TdfaAsmBackend.generate(tdfa, memoBudget);
    }

    /**
     * {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile.
     */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }
}
