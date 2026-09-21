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
 * when the pike cut deleted continuations, and resolve the engine source
 * ({@code -Dtdfa.engine=VM}, a custom {@link RegexEngineFactory}, or
 * per-pattern ASM generation). Everything builds inside {@code compile()};
 * any budget rejection fails the compile as a translated
 * {@link io.github.jemmix.tdfa.core.PatternSyntaxException}.
 */
final class PatternCompiler {

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH
        | Pattern.UNICODE_CHARACTER_CLASS;

    private PatternCompiler() {
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           UnicodeDataProvider provider) {
        return compile(regex, flags, factory, provider, null);
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           UnicodeDataProvider provider,
                           CompileObserver observer) {
        if (regex == null) throw new NullPointerException("pattern is null");
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(
                "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
        }
        String fl = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) fl = "(?i)" + fl;
        if ((flags & Pattern.DOTALL) != 0) fl = "(?s)" + fl;
        if ((flags & Pattern.MULTILINE) != 0) fl = "(?m)" + fl;
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) fl = "(?u)" + fl;
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
            int ps = find.stateCount();
            // A pattern keeping a second (whole) engine splits the runtime
            // memo budget so the pattern's combined memos stay within one budget.
            long memoBudget = Budgets.runtimeMemoryBytes() / (whole == find ? 1 : 2);

            if (vmSwitched()) {
                obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
                RegexEngine eng = new TdfaRunner(find, memoBudget);
                return new TDFAPattern(regex, flags, ps, eng,
                    whole == find ? eng : new TdfaRunner(whole, memoBudget), provider);
            }

            if (factory != null) {
                // One factory call (the find engine). Whole matching runs
                // the facade's own engine over the whole artifact; when the
                // artifacts are shared that is the custom engine itself
                // (its own matchWhole contract applies).
                long t0 = System.nanoTime();
                RegexEngine eng = factory.create(find);
                RegexEngine wholeEng = whole == find ? eng : new TdfaRunner(whole, memoBudget);
                obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
                try {
                    Pattern p = (Pattern) ShellEmitter.emit(
                        new ShellEmitter.Spec(regex, flags, ps, eng, wholeEng, null, provider));
                    obs.note("engine", "byo-shell");
                    return p;
                } catch (RuntimeException ex) {
                    if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                    obs.note("engine", "shared (byo-shell emission failed)");
                    return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
                }
            }

            // Default: ASM per-pattern engine + concrete-typed shell.
            // LinkageError is caught alongside RuntimeException: generated
            // bytecode failures (VerifyError from defineClass, lazy shell
            // linkage) degrade to the interpreter instead of escaping
            // Pattern.compile as raw Errors.
            TdfaAsmBackend.Generated gen;
            long t1 = System.nanoTime();
            try {
                gen = TdfaAsmBackend.generate(find, memoBudget);
            } catch (RuntimeException | LinkageError genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                obs.note("engine", "shared-interpreter (engine emission failed)");
                RegexEngine eng = new TdfaRunner(find, memoBudget);
                return new TDFAPattern(regex, flags, ps, eng,
                    whole == find ? eng : new TdfaRunner(whole, memoBudget), provider);
            }
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t1, 0);
            try {
                Pattern p = (Pattern) ShellEmitter.emit(
                    new ShellEmitter.Spec(regex, flags, ps, gen.engine(),
                        whole == find ? gen.engine() : new TdfaRunner(whole, memoBudget),
                        gen.owner(), provider));
                obs.note("engine", "generated");
                return p;
            } catch (RuntimeException | LinkageError ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                obs.note("engine", "shared-interpreter (shell emission failed)");
                RegexEngine eng = new TdfaRunner(find, memoBudget);
                return new TDFAPattern(regex, flags, ps, eng,
                    whole == find ? eng : new TdfaRunner(whole, memoBudget), provider);
            }
        } catch (RuntimeException e) {
            throw CompiledRegex.translate(e, regex);
        }
    }

    /**
     * {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile.
     */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }
}
