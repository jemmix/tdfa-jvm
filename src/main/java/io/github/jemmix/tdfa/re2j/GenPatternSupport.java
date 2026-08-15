package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend.Generated;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend.GeneratedRegex;
import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Supplier;

/**
 * Per-pattern generation for the re2j-compat tier, under an engine factory
 * that {@linkplain io.github.jemmix.tdfa.EngineFactory#generatesPerPattern()
 * generates per-pattern classes}: emits a Pattern/Matcher pair into the
 * engine's classloader whose hot methods call the generated engine class
 * directly — the whole {@code Matcher.find() → engine ladder → walk leaf}
 * chain devirtualizes and inlines, with no megamorphic hop anywhere.
 *
 * <pre>
 * final class GenNNNPattern extends VmPattern {
 *     private final GenNNN eng;
 *     GenNNNPattern(String pattern, int flags, Regex engine, Supplier&lt;Regex&gt; whole, GenNNN eng) { ... }
 *     @Override public Matcher matcher(CharSequence in) { return new GenNNNMatcher(this, in); }
 *     @Override public Matcher matcher(byte[] in)       { return new GenNNNMatcher(this, Utf8.decode(in)); }
 * }
 * final class GenNNNMatcher extends VmMatcher {
 *     private final GenNNNPattern p;
 *     @Override public boolean find()      { ... p.eng.match(input, start) ... }
 *     @Override public boolean matches()   { ... p.wholeEngine().find(input, 0) ... }
 *     @Override public boolean lookingAt() { ... p.eng.match(input, 0) ... }
 * }
 * </pre>
 *
 * All cold machinery (group accessors, replacement, split) is inherited from
 * the shared {@link VmPattern}/{@link VmMatcher} — one brain, thin generated
 * hot paths.
 */
final class GenPatternSupport {

    private static final String VP = "io/github/jemmix/tdfa/re2j/VmPattern";
    private static final String VM = "io/github/jemmix/tdfa/re2j/VmMatcher";
    private static final String MATCHER = "io/github/jemmix/tdfa/re2j/Matcher";
    private static final String PATTERN = "io/github/jemmix/tdfa/re2j/Pattern";
    private static final String RESULT = "io/github/jemmix/tdfa/vm/MatchResult";
    private static final String RESULT_D = "L" + RESULT + ";";
    private static final String CS = "Ljava/lang/CharSequence;";
    private static final String REGEX = "io/github/jemmix/tdfa/Regex";

    static PatternSpi compile(String regex, int flags, io.github.jemmix.tdfa.asm.AsmEngineFactory factory,
                              io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider) {
        String flregex = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & Pattern.DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & Pattern.MULTILINE) != 0)       flregex = "(?m)" + flregex;
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) flregex = "(?u)" + flregex;
        Disambiguation disamb = (flags & Pattern.LONGEST_MATCH) != 0
                ? Disambiguation.POSIX : Disambiguation.PERL;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        try {
            // Parse + determinize first so syntax errors surface identically.
            Tnfa nfa = Tnfa.compile(flregex, disableUnicodeGroups, false, provider);
            Tdfa tdfa = Tdfa.compile(nfa, disamb);
            try {
                GeneratedRegex gr = TdfaAsmBackend.generateRegexWithHandle(
                        tdfa, nfa.groupCount, tdfa.stateCount, nfa.namedGroups);
                final String fl = flregex;
                Supplier<Regex> wholeSupplier =
                        () -> Regex.compile(fl, factory, disamb, disableUnicodeGroups, true, provider);
                return emit(gr.generated(), regex, flags, gr.regex(), wholeSupplier);
            } catch (RuntimeException genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                // Generation failed (rare): fall back to the shared implementation
                // (recompiles — the parse already succeeded, so this cannot throw
                // a syntax error).
                return (PatternSpi) VmPattern.compile(regex, flags, factory, provider);
            }
        } catch (RuntimeException e) {
            throw RE2.translate(e, regex);
        }
    }

    static PatternSpi emit(Generated g, String pattern, int flags, Regex engine, Supplier<Regex> wholeSupplier) {
        String engOwner = g.owner();
        String engDesc = "L" + engOwner + ";";
        String patOwner = engOwner + "Pattern";
        String matOwner = engOwner + "Matcher";
        String patDesc = "L" + patOwner + ";";

        // ---- GenNNNMatcher extends VmMatcher ----
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                matOwner, null, VM, null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "p", patDesc, null, null).visitEnd();

        // ctor(GenNNNPattern, CharSequence)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + patDesc + CS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VM, "<init>",
                "(Lio/github/jemmix/tdfa/re2j/PatternSpi;" + CS + ")V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, matOwner, "p", patDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean find()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "()Z", null, null);
        mv.visitCode();
        // locals: 1 = start, 2 = m
        Label elseStart = new Label(), startDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "hasMatch", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, elseStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "lastMatchEnd", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "lastMatchEnd", "I");
        Label noBump = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, noBump);
        mv.visitIincInsn(1, 1);
        mv.visitLabel(noBump);
        mv.visitJumpInsn(Opcodes.GOTO, startDone);
        mv.visitLabel(elseStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "appendPos", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitLabel(startDone);
        // if (start > inputLength) { hasMatch = false; return false; }
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "inputLength", "I");
        Label notPast = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, notPast);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notPast);
        // m = p.eng.match(input, start)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "input", CS);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, engOwner, "match", "(" + CS + "I)" + RESULT_D, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        emitAcceptTail(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean matches()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "()Z", null, null);
        mv.visitCode();
        // locals: 1 = m
        // Regex w = p.wholeEngine(); m = w.find(input, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VP, "wholeEngine", "()L" + REGEX + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "input", CS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGEX, "find", "(" + CS + "I)" + RESULT_D, false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        // hasMatch = m != null; if (m != null) { match=m; lms=m.start(0); lme=m.end(0); } return hasMatch;
        Label mNull = new Label(), hasMatchSet = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, mNull);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, hasMatchSet);
        mv.visitLabel(mNull);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(hasMatchSet);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label mNull2 = new Label(), tailDone = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, mNull2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "match", RESULT_D);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchEnd", "I");
        mv.visitLabel(mNull2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "hasMatch", "Z");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean lookingAt()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "lookingAt", "()Z", null, null);
        mv.visitCode();
        // m = p.eng.match(input, 0); if (m != null && m.start(0) == 0) accept else false
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, VM, "input", CS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, engOwner, "match", "(" + CS + "I)" + RESULT_D, false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label laNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, laNull);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        Label laFail = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, laFail);   // start != 0 → fail
        // accept
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "match", RESULT_D);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchEnd", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(laNull);
        mv.visitLabel(laFail);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] matBytes = cw.toByteArray();

        // ---- GenNNNPattern extends VmPattern ----
        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                patOwner, null, VP, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "eng", engDesc, null, null).visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;IL" + REGEX + ";Ljava/util/function/Supplier;" + engDesc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, VP, "<init>",
                "(Ljava/lang/String;IL" + REGEX + ";Ljava/util/function/Supplier;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitFieldInsn(Opcodes.PUTFIELD, patOwner, "eng", engDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // public Matcher matcher(CharSequence)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matcher", "(" + CS + ")L" + MATCHER + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, matOwner);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, matOwner, "<init>", "(" + patDesc + CS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // public Matcher matcher(byte[])
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matcher", "([B)L" + MATCHER + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, matOwner);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PATTERN + "$Utf8", "decode", "([B)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, matOwner, "<init>", "(" + patDesc + CS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] patBytes = cw.toByteArray();

        try {
            TdfaAsmBackend.GenClassLoader cl = (TdfaAsmBackend.GenClassLoader) g.loader();
            cl.register(patOwner.replace('/', '.'), patBytes);
            cl.register(matOwner.replace('/', '.'), matBytes);
            Class<?> engCls = Class.forName(engOwner.replace('/', '.'), true, cl);
            Class<?> patCls = Class.forName(patOwner.replace('/', '.'), true, cl);
            return (PatternSpi) patCls.getDeclaredConstructor(
                    String.class, int.class, Regex.class, Supplier.class, engCls
            ).newInstance(pattern, flags, engine, wholeSupplier, g.engine());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GenPattern emission failed", e);
        }
    }

    /** if (m == null) { hasMatch = false; return false; } accept; return true; */
    private static void emitAcceptTail(MethodVisitor mv) {
        // local 2 = m (find path); local 1 = m (other callers do not use this)
        Label notNull = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "match", RESULT_D);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, VM, "lastMatchEnd", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
    }

    private GenPatternSupport() { }
}
