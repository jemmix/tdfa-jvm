package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend.GenClassLoader;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend.Generated;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Emits the per-pattern {@link Regex} subclass: thin overrides whose bodies
 * call the generated engine class with a statically-known final receiver type,
 * so the whole chain — user call site → Regex.find → engine ladder → walk
 * leaf — devirtualizes and inlines without a megamorphic hop.
 *
 * <pre>
 * final class GenNNNRegex extends Regex {
 *     private final GenNNN eng;               // final engine class, per-pattern
 *     GenNNNRegex(GenNNN e, int gc, int ps, Map&lt;String,Integer&gt; ng) { super(e, gc, ps, ng); this.eng = e; }
 *     @Override public boolean matches(CharSequence in)  { return eng.matches(in); }
 *     @Override public boolean find(CharSequence in)     { return eng.find(in); }
 *     @Override public MatchResult find(CharSequence in, int from) { return eng.match(in, from); }
 * }
 * </pre>
 */
final class GenRegexEmitter {

    static Regex emit(Generated g, int groupCount, int programSize, Map<String, Integer> namedGroups) {
        String engInternal = g.owner();                       // io/.../GenNNN (slashed)
        String engCn = engInternal.replace('/', '.');          // dotted, for reflection
        String regexCn = engCn + "Regex";
        String regexOwner = regexCn.replace('.', '/');
        String engOwner = engInternal;                             // final engine class (slashed)
        String engDesc = "L" + engOwner + ";";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                regexOwner, null, "io/github/jemmix/tdfa/Regex", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "eng", engDesc, null, null).visitEnd();

        // ctor: (GenNNN, int, int, Map) → super(e, gc, ps, ng); this.eng = e;
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + engDesc + "II" + "Ljava/util/Map;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "io/github/jemmix/tdfa/Regex", "<init>",
                "(Lio/github/jemmix/tdfa/core/RegexEngine;II" + "Ljava/util/Map;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, regexOwner, "eng", engDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean matches(CharSequence)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(Ljava/lang/CharSequence;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, regexOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, engOwner, "matches", "(Ljava/lang/CharSequence;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public boolean find(CharSequence)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "(Ljava/lang/CharSequence;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, regexOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, engOwner, "find", "(Ljava/lang/CharSequence;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public MatchResult find(CharSequence, int)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "(Ljava/lang/CharSequence;I)Lio/github/jemmix/tdfa/core/MatchResult;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, regexOwner, "eng", engDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, engOwner, "match", "(Ljava/lang/CharSequence;I)Lio/github/jemmix/tdfa/core/MatchResult;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ((GenClassLoader) g.loader()).register(regexCn, bytes);
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Regex> cls = (Class<? extends Regex>) Class.forName(regexCn, true, g.loader());
            return cls.getDeclaredConstructor(
                    Class.forName(engCn, true, g.loader()), int.class, int.class, Map.class
            ).newInstance(g.engine(), groupCount, programSize, namedGroups);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GenRegex emission failed", e);
        }
    }

    private GenRegexEmitter() { }
}
