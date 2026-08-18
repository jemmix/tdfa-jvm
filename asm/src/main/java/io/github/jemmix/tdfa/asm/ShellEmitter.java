package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.core.RegexEngine;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Supplier;

/**
 * Emits the per-pattern facade shell — a Pattern/PatternMatcher pair into the
 * engine's child classloader whose hot methods ({@code find}/{@code matches}/
 * {@code lookingAt}) inline the bookkeeping and call the engine directly with
 * a statically-known final receiver type, so the whole
 * {@code Matcher.find() → engine ladder → walk leaf} chain devirtualizes and
 * inlines with no megamorphic hop anywhere. Cold machinery (group accessors,
 * replacement, split) is inherited from the shared facade base classes —
 * one brain, thin generated hot paths.
 *
 * <p>This class compiles against the CORE module only. The facade types it
 * emits references to ({@code io.github.jemmix.tdfa.TDFAPattern},
 * {@code PatternMatcher}, {@code Pattern}) appear exclusively as internal-name
 * / descriptor strings — resolved at class-load time in whatever runtime
 * loaded the facade (the facade is what triggered emission, so its classes
 * are linkable by construction). This keeps the module graph acyclic:
 * facade → asm → core.
 *
 * <pre>
 * final class GenNNNPattern extends TDFAPattern {
 *     public final GenNNN eng;              // concrete engine class (per-pattern), or
 *                                            // RegexEngine-typed for bring-your-own engines
 *     GenNNNPattern(String pattern, int flags, int ps, RegexEngine e, Supplier w, GenNNN eng) { ... }
 *     @Override public PatternMatcher matcher(CharSequence in) { return new GenNNNMatcher(this, in); }
 * }
 * final class GenNNNMatcher extends PatternMatcher {
 *     private final GenNNNPattern p;
 *     @Override public boolean find()      { ... p.eng.match(input, start) ... }
 *     @Override public boolean matches()   { ... p.wholeEngine().match(input, 0) ... }
 *     @Override public boolean lookingAt() { ... p.eng.match(input, 0) ... }
 * }
 * </pre>
 */
public final class ShellEmitter {

    // Facade-tier types, by descriptor only (no compile-time dependency).
    private static final String TDFAPATTERN = "io/github/jemmix/tdfa/TDFAPattern";
    private static final String PATMAT = "io/github/jemmix/tdfa/PatternMatcher";
    private static final String PATTERN = "io/github/jemmix/tdfa/Pattern";
    // Core-tier types.
    private static final String ENGINE_ITF = "io/github/jemmix/tdfa/core/RegexEngine";
    private static final String CORE_MATCHER = "io/github/jemmix/tdfa/core/Matcher";
    private static final String RESULT = "io/github/jemmix/tdfa/core/MatchResult";
    private static final String CS = "Ljava/lang/CharSequence;";

    /** Shell emission spec. All core/reflection types — no facade linkage. */
    public record Spec(String pattern, int flags, int programSize,
                       RegexEngine engine, Supplier<RegexEngine> wholeSupplier,
                       /** Slashed internal name of a per-pattern generated engine
                        *  class for concrete-type wiring, or {@code null} to type
                        *  the shell's engine field as {@link RegexEngine}. */
                       String engineInternalName) { }

    /**
     * Emit and instantiate the shell. Returns the generated {@code Pattern}
     * instance as {@link Object} — the facade casts it (it owns the type).
     * Throws {@link IllegalStateException} on emission problems; the caller
     * falls back to the shared implementation.
     */
    public static Object emit(Spec spec) {
        String engOwner = spec.engineInternalName() != null
                ? spec.engineInternalName()
                : ENGINE_ITF;
        String engDesc = "L" + engOwner + ";";
        boolean concrete = spec.engineInternalName() != null;
        RegexEngine engInstance = spec.engine();

        // Deterministic per-instance naming: derive from the engine's class
        // when concrete, else from identity hash.
        String base;
        if (concrete) {
            base = engOwner;
        } else {
            base = "io/github/jemmix/tdfa/gen/Shell" + Integer.toHexString(System.identityHashCode(spec));
        }
        String patOwner = base + "Pattern";
        String matOwner = base + "Matcher";
        String patDesc = "L" + patOwner + ";";

        // ---- GenNNNMatcher extends PatternMatcher ----
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                matOwner, null, PATMAT, null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "p", patDesc, null, null).visitEnd();

        // ctor(GenNNNPattern, CharSequence)
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + patDesc + CS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PATMAT, "<init>",
                "(L" + TDFAPATTERN + ";" + CS + ")V", false);
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
        // if (start > inputLength) { hasMatch = false; return false; }
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "inputLength", "I");
        Label notPast = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, notPast);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CORE_MATCHER, "hasMatch", "Z");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notPast);
        // m = p.eng.match(input, start)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(concrete ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKEINTERFACE, engOwner, "match", "(" + CS + "I)L" + RESULT + ";", !concrete);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        emitAcceptTail(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean matches()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "()Z", null, null);
        mv.visitCode();
        // locals: 1 = m
        // RegexEngine w = p.wholeEngine(); m = w.match(input, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFAPATTERN, "wholeEngine", "()L" + ENGINE_ITF + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ENGINE_ITF, "match", "(" + CS + "I)L" + RESULT + ";", true);
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
        Label mNull2 = new Label(), tailDone = new Label();
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
        // m = p.eng.match(input, 0); if (m != null && m.start(0) == 0) accept else false
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, matOwner, "p", patDesc);
        mv.visitFieldInsn(Opcodes.GETFIELD, patOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CORE_MATCHER, "input", CS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(concrete ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKEINTERFACE, engOwner, "match", "(" + CS + "I)L" + RESULT + ";", !concrete);
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

        // ---- GenNNNPattern extends TDFAPattern ----
        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                patOwner, null, TDFAPATTERN, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "eng", engDesc, null, null).visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;IIL" + ENGINE_ITF + ";Ljava/util/function/Supplier;" + engDesc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TDFAPATTERN, "<init>",
                "(Ljava/lang/String;IIL" + ENGINE_ITF + ";Ljava/util/function/Supplier;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
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
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PATTERN + "$Utf8", "decode", "([B)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, matOwner, "<init>", "(" + patDesc + CS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] patBytes = cw.toByteArray();
        if (Boolean.getBoolean("tdfa.asm.dump")) {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Paths.get("/tmp/shells"));
                java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/shells/" + matOwner.substring(matOwner.lastIndexOf('/') + 1) + ".class"), matBytes);
                java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/shells/" + patOwner.substring(patOwner.lastIndexOf('/') + 1) + ".class"), patBytes);
            } catch (Exception ignored) { }
        }

        try {
            ClassLoader cl = engInstance.getClass().getClassLoader();
            TdfaAsmBackend.GenClassLoader gcl =
                    cl instanceof TdfaAsmBackend.GenClassLoader g ? g : new TdfaAsmBackend.GenClassLoader(cl);
            gcl.register(patOwner.replace('/', '.'), patBytes);
            gcl.register(matOwner.replace('/', '.'), matBytes);
            Class<?> patCls = Class.forName(patOwner.replace('/', '.'), true, gcl);
            Class<?> engCls = concrete
                    ? Class.forName(engOwner.replace('/', '.'), true, gcl)
                    : RegexEngine.class;
            return patCls.getDeclaredConstructor(
                    String.class, int.class, int.class, RegexEngine.class, Supplier.class, engCls
            ).newInstance(spec.pattern(), spec.flags(), spec.programSize(),
                    engInstance, spec.wholeSupplier(), engInstance);
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

    private ShellEmitter() { }
}
