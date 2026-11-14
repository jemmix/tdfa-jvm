package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.core.compile.ShellSpec;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits the per-pattern core shell — a machinery {@code Pattern}/
 * {@code PatternMatcher} pair into the engine's child classloader whose
 * hot methods ({@code find}/{@code matches}/{@code lookingAt}) inline the
 * bookkeeping and call the engine directly with a statically-known final
 * receiver type, so the whole {@code Matcher.find() → engine ladder →
 * walk leaf} chain devirtualizes and inlines with no megamorphic hop
 * anywhere. Cold machinery (group accessors, replacement, split) is
 * inherited from the shared machinery base classes — one brain, thin
 * generated hot paths.
 *
 * <p>This class compiles against the CORE module only, and knows nothing
 * about the facades: no facade type appears in its API, its emission, or
 * the classes it emits. The machinery types it references
 * ({@code core.compile.TDFAPattern}, {@code core.compile.PatternMatcher})
 * appear as internal-name / descriptor strings — resolved at class-load
 * time in whatever runtime loaded the compile's classes — except the
 * reflection tail, which links {@code core.compile} types directly (the
 * {@code :asm → :core} edge). The emitted pattern implements no API
 * interface; each facade drives its own codegen on top — it mints a
 * per-pattern subclass implementing its own {@code Pattern}, in its own
 * module, with no help from (and no knowledge in) this one.
 *
 * <pre>
 * final class GenNNNPattern extends core.compile.TDFAPattern {
 *     public final GenNNN eng;              // concrete engine class (per-pattern), or
 *                                            // RegexEngine-typed for bring-your-own engines
 *     GenNNNPattern(String pattern, int flags, int ps, RegexEngine e, WholeEngine w,
 *                   UnicodeDataProvider prov, Semantics lane, GenNNN eng) { ... }
 *     {@literal @Override} public PatternMatcher matcher(CharSequence in) { return new GenNNNMatcher(this, in); }
 * }
 * final class GenNNNMatcher extends core.compile.PatternMatcher {
 *     private final GenNNNPattern p;
 *     {@literal @Override} public boolean find()      { ... p.eng.match(input, start, scratch) ... }
 *     {@literal @Override} public boolean matches()   { ... p.wholeEngine().matchWhole(input, scratch) ... }
 *     {@literal @Override} public boolean lookingAt() { ... p.eng.match(input, 0, scratch) ... }
 * }
 * </pre>
 */
public final class ShellEmitter {

    /** Monotonic BYO-shell name sequence: deterministic within a JVM,
     *  collision-free while any shell is live, and stable across runs for
     *  dumps/debuggers (identityHashCode is none of these). */
    private static final AtomicLong SHELL_SEQ = new AtomicLong();

    // Machinery-tier types, by descriptor only (no compile-time dependency
    // for the emitted references).
    private static final String TDFAPATTERN = "io/github/jemmix/tdfa/core/compile/TDFAPattern";
    private static final String PATMAT = "io/github/jemmix/tdfa/core/compile/PatternMatcher";
    // Core-tier types.
    private static final String ENGINE_ITF = "io/github/jemmix/tdfa/core/engine/RegexEngine";
    private static final String WHOLE_ITF = "io/github/jemmix/tdfa/core/engine/WholeEngine";
    private static final String CORE_MATCHER = "io/github/jemmix/tdfa/core/engine/Matcher";
    private static final String RESULT = "io/github/jemmix/tdfa/core/engine/MatchResult";
    private static final String SCRATCH = "io/github/jemmix/tdfa/core/engine/MatchScratch";
    private static final String SCRATCH_D = "L" + SCRATCH + ";";
    private static final String CS = "Ljava/lang/CharSequence;";

    /**
     * Emit and instantiate the core shell described by {@code spec}: a
     * {@link TDFAPattern} carrying the concrete engine field, no API
     * interface — the calling facade wraps it into its own type
     * afterwards. Throws {@link IllegalStateException} on emission
     * problems; failures propagate to the caller (they are bugs, not
     * shapes to route around).
     */
    private ShellEmitter() {
    }

    public static TDFAPattern emit(ShellSpec spec) {
        String engName = spec.engineInternalName();
        boolean concrete = engName != null;
        String engOwner = concrete ? engName : ENGINE_ITF;
        String engDesc = "L" + engOwner + ";";
        RegexEngine engInstance = spec.engine();

        // Per-instance naming: derive from the engine's class when concrete,
        // else from the monotonic shell sequence.
        String base;
        if (concrete) {
            base = engOwner;
        } else {
            base = "io/github/jemmix/tdfa/gen/Shell" + Long.toHexString(SHELL_SEQ.incrementAndGet());
        }
        String patOwner = base + "Pattern";
        String matOwner = base + "Matcher";
        String patDesc = "L" + patOwner + ";";

        // ---- GenNNNMatcher extends core.compile.PatternMatcher ----
        FrameClassWriter cw = new FrameClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, matOwner, null, PATMAT,
            null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "p", patDesc, null, null).visitEnd();

        // ctor(GenNNNPattern, CharSequence)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + patDesc + CS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PATMAT, "<init>", "(L" + TDFAPATTERN + ";" + CS + ")V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, matOwner, "p", patDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean find()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "()Z", null, null);
        mv.visitCode();
        // Op-mode bookkeeping for the cold hitEnd/requireEnd probe: an
        // unanchored find, and the search start it scans from.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastOpAnchored", "Z");
        // Region routing: an active region leaves the inlined fast path —
        // the machinery find() owns the bounds family (one predictable
        // never-taken branch on the hot path).
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "regionTo", "I");
        Label noRegion = new Label();
        mv.visitJumpInsn(Opcodes.IFLT, noRegion); // -1 = no region
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CORE_MATCHER, "find", "()Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(noRegion);
        // locals: 1 = start, 2 = m
        Label elseStart = new Label(), startDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, elseStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "lastMatchEnd", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "lastMatchEnd", "I");
        Label noBump = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, noBump);
        mv.visitIincInsn(1, 1);
        mv.visitLabel(noBump);
        mv.visitJumpInsn(Opcodes.GOTO, startDone);
        mv.visitLabel(elseStart);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "appendPos", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitLabel(startDone);
        // lastSearchFrom = start (the failed-search probe's scan origin)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastSearchFrom", "I");
        // if (start > inputLength) { hasMatch = false; return false; }
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "inputLength", "I");
        Label notPast = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, notPast);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        // searchSkipped = true — the early return keeps the last search's flags
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "searchSkipped", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notPast);
        // searchSkipped = false (this find executes a search)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "searchSkipped", "Z");
        // m = p.eng.match(input, start, this.scratch)  — carrier-aware entry:
        // the matcher's buffers pool across find() iterations.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "scratch", SCRATCH_D);
        mv.visitMethodInsn(concrete ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKEINTERFACE, engOwner, "match",
            "(" + CS + "I" + SCRATCH_D + ")L" + RESULT + ";", !concrete);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        emitAcceptTail(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean matches()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "()Z", null, null);
        mv.visitCode();
        // Op-mode bookkeeping for the cold hitEnd/requireEnd probe: an
        // anchored op (the machinery path on the region route stores it
        // again — harmless, same value), and this op executes — the
        // flags become its own.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastOpAnchored", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "searchSkipped", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "regionTo", "I");
        Label mNoRegion = new Label();
        mv.visitJumpInsn(Opcodes.IFLT, mNoRegion);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CORE_MATCHER, "matches", "()Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(mNoRegion);
        // locals: 1 = m
        // RegexEngine w = p.wholeEngine(); m = w.matchWhole(input, this.scratch)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFAPATTERN, "wholeEngine", "()L" + WHOLE_ITF + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "scratch", SCRATCH_D);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, WHOLE_ITF, "matchWhole", "(" + CS + SCRATCH_D + ")L" + RESULT + ";",
            true);
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
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label mNull2 = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, mNull2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "match", "L" + RESULT + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchEnd", "I");
        mv.visitLabel(mNull2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean lookingAt()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "lookingAt", "()Z", null, null);
        mv.visitCode();
        // Op-mode bookkeeping (see matches()).
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastOpAnchored", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "searchSkipped", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "regionTo", "I");
        Label lNoRegion = new Label();
        mv.visitJumpInsn(Opcodes.IFLT, lNoRegion);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CORE_MATCHER, "lookingAt", "()Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(lNoRegion);
        // m = p.eng.match(input, 0, this.scratch); if (m != null && m.start(0) == 0) accept else false
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "scratch", SCRATCH_D);
        mv.visitMethodInsn(concrete ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKEINTERFACE, engOwner, "match",
            "(" + CS + "I" + SCRATCH_D + ")L" + RESULT + ";", !concrete);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label laNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, laNull);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        Label laFail = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, laFail); // start != 0 → fail
        // accept
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "match", "L" + RESULT + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchEnd", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(laNull);
        mv.visitLabel(laFail);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] matBytes = cw.toByteArray();

        // ---- GenNNNPattern extends core.compile.TDFAPattern (machinery only) ----
        cw = new FrameClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        // Not final: the calling facade mints its API-typed subclass of this
        // class (its own codegen); the matcher below stays final.
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, patOwner, null, TDFAPATTERN, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "eng", engDesc, null, null).visitEnd();
        // (String pattern, int flags, int ps, RegexEngine e, RegexEngine w, UnicodeDataProvider prov,
        //  Semantics lane, Eng eng)
        String provDesc = "Lio/github/jemmix/tdfa/core/unicode/UnicodeDataProvider;";
        String semDesc = "Lio/github/jemmix/tdfa/core/tnfa/Semantics;";
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
            "(Ljava/lang/String;IIL" + ENGINE_ITF + ";L" + WHOLE_ITF + ";" + provDesc + semDesc + engDesc + ")V", null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TDFAPATTERN, "<init>",
            "(Ljava/lang/String;IIL" + ENGINE_ITF + ";L" + WHOLE_ITF + ";" + provDesc + semDesc + ")V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, patOwner, "eng", engDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // public PatternMatcher matcher(CharSequence)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matcher", "(" + CS + ")L" + PATMAT + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, matOwner);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, matOwner, "<init>", "(" + patDesc + CS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // public PatternMatcher matcher(byte[])
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matcher", "([B)L" + PATMAT + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, matOwner);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TDFAPATTERN, "decodeUtf8", "([B)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, matOwner, "<init>", "(" + patDesc + CS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] patBytes = cw.toByteArray();
        if (Boolean.getBoolean("tdfa.asm.dump")) {
            dumpShell(matOwner, matBytes, patOwner, patBytes);
        }

        try {
            ClassLoader cl = engInstance.getClass().getClassLoader();
            TdfaAsmBackend.GenClassLoader gcl = cl instanceof TdfaAsmBackend.GenClassLoader
                ? (TdfaAsmBackend.GenClassLoader) cl : new TdfaAsmBackend.GenClassLoader(cl);
            gcl.register(patOwner.replace('/', '.'), patBytes);
            gcl.register(matOwner.replace('/', '.'), matBytes);
            Class<?> patCls = Class.forName(patOwner.replace('/', '.'), true, gcl);
            Class<?> engCls = concrete ? Class.forName(engOwner.replace('/', '.'), true, gcl) : RegexEngine.class;
            return (TDFAPattern) patCls.getDeclaredConstructor(String.class, int.class, int.class, RegexEngine.class,
                WholeEngine.class, UnicodeDataProvider.class, Semantics.class, engCls).newInstance(spec.pattern(),
                    spec.flags(), spec.programSize(), engInstance, spec.wholeEngine(), spec.provider(),
                    spec.semantics(), engInstance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("shell emission failed", e);
        }
    }

    /** if (m == null) { hasMatch = false; return false; } accept; return true; */
    private static void emitAcceptTail(MethodVisitor mv) {
        // local 2 = m
        Label notNull = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "match", "L" + RESULT + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "start", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "end", "(I)I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "lastMatchEnd", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /** Best-effort debug dump of the emitted shell pair; a failed dump must
     *  never fail compilation (tdfa.asm.dump is a diagnostic switch). */
    @SuppressWarnings("EmptyCatch")
    private static void dumpShell(String matOwner, byte[] matBytes, String patOwner, byte[] patBytes) {
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "shells");
            Files.createDirectories(dir);
            Files.write(dir.resolve(matOwner.substring(matOwner.lastIndexOf('/') + 1) + ".class"), matBytes);
            Files.write(dir.resolve(patOwner.substring(patOwner.lastIndexOf('/') + 1) + ".class"), patBytes);
        } catch (IOException ignored) {
        }
    }
}
