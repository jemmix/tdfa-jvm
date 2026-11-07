package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The jur facade's own codegen — the only emission step this module
 * performs, authored here, with this facade's types hardcoded. For every
 * pattern {@link Pattern#compile} hands out it mints a per-pattern
 * subclass of the machinery shell ({@code Gen*Pattern extends
 * TDFAPattern}) that implements this facade's {@link Pattern}: a pure
 * type tag — every method is inherited from the machinery base, so the
 * whole {@code matcher() → find() → engine} chain is the emitted core
 * pair's devirtualized ladder, untouched — plus a copy-constructor
 * (through the machinery getters) and a {@code writeReplace} that hands
 * serialization to this facade's {@link SerializationProxy}.
 *
 * <p>The mint knows two things: its own interface (a hardcoded name —
 * this module owns it) and the machinery's public surface
 * ({@code TDFAPattern}'s getters and the emitted shell's constructor
 * shape, discovered reflectively from the class itself, all core
 * types). Nothing flows down into the machinery and nothing in the
 * machinery knows this exists. The interpreter tier ({@code
 * -Dtdfa.engine=VM}) mints the same shape around the bare machinery
 * base once and reuses the class — that tier has no per-pattern
 * classes to bind to.
 *
 * <p>Package-private: this facade's own plumbing — not shipped API.
 */
final class Shells {

    private static final String TDFAPATTERN = "io/github/jemmix/tdfa/core/compile/TDFAPattern";

    private static final String ENGINE = "io/github/jemmix/tdfa/core/engine/RegexEngine";

    private static final String WHOLE = "io/github/jemmix/tdfa/core/engine/WholeEngine";

    private static final String PROV = "io/github/jemmix/tdfa/core/unicode/UnicodeDataProvider";

    private static final String SEM = "io/github/jemmix/tdfa/core/tnfa/Semantics";

    /** This facade's own API interface — hardcoded: this module owns the name. */
    private static final String API = "io/github/jemmix/tdfa/Pattern";

    /** This facade's serialization anchor — hardcoded likewise. */
    private static final String PROXY = "io/github/jemmix/tdfa/SerializationProxy";

    private static final AtomicLong SEQ = new AtomicLong();

    /** The interpreter-tier carrier constructor, minted once per process. */
    private static volatile Constructor<?> vmCarrier;

    private Shells() {
    }

    /**
     * Mint the API-typed pattern for a machinery compile: an emitted
     * subclass of {@code core}'s class. Throws
     * {@link IllegalStateException} if the machinery shell's shape is
     * not as this emitter expects (a bug, not a shape to route around).
     */
    static Pattern mint(TDFAPattern core) {
        try {
            if (core.getClass() == TDFAPattern.class) {
                Constructor<?> ctor = vmCarrier;
                if (ctor == null) {
                    ctor = mintClass(TDFAPattern.class, null).getDeclaredConstructor(TDFAPattern.class);
                    ctor.setAccessible(true);
                    vmCarrier = ctor;
                }
                return (Pattern) ctor.newInstance(core);
            }
            // The emitted shell's own engine field names the concrete type
            // its constructor's last parameter takes — read from the class
            // and verified against the declared constructor (the
            // mint↔machinery shape contract, checked loudly here in the
            // facade's own code).
            Class<?> base = core.getClass();
            Class<?> engType = engField(base);
            try {
                // Shape check — the copy-ctor emission below relies on this
                // exact super-constructor existing on the machinery shell.
                Constructor<?> superCtor = base.getDeclaredConstructor(String.class, int.class, int.class,
                    RegexEngine.class, WholeEngine.class, UnicodeDataProvider.class, Semantics.class, engType);
                superCtor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                    "machinery shell " + base.getName() + " does not declare the expected copy-constructor shape", e);
            }
            Constructor<?> ctor = mintClass(base, engType).getDeclaredConstructor(TDFAPattern.class);
            ctor.setAccessible(true);
            return (Pattern) ctor.newInstance(core);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("facade shell mint failed", e);
        }
    }

    /** The emitted shell's concrete {@code eng} field type, or {@code null} if absent. */
    private static Class<?> engField(Class<?> base) {
        try {
            return base.getDeclaredField("eng").getType();
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * Emit {@code WrapNNN extends <base> implements <jur Pattern>} with
     * the copy-constructor and the serialization hook.
     */
    private static Class<?> mintClass(Class<?> base, Class<?> engType) {
        String baseInternal = base.getName().replace('.', '/');
        String engDesc = engType == null ? "" : "L" + engType.getName().replace('.', '/') + ";";
        // The base's copy-constructor super call: the machinery's public
        // 7-getter run, plus the concrete engine (the emitted shell's own
        // constructor re-initializes its eng field from the last arg).
        String superDesc =
            "(Ljava/lang/String;IIL" + ENGINE + ";L" + WHOLE + ";L" + PROV + ";L" + SEM + ";" + engDesc + ")V";
        String owner = "io/github/jemmix/tdfa/gen/Wrap" + Long.toHexString(SEQ.incrementAndGet());

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, baseInternal,
            new String[]{API});
        // public WrapNNN(TDFAPattern src) { super(src.pattern(), src.flags(),
        //     src.programSize(), src.engine(), src.wholeEngine(),
        //     src.unicodeProvider(), src.semantics(), (Eng) src.engine()); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + TDFAPATTERN + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        copy(mv, "pattern", "()Ljava/lang/String;");
        copy(mv, "flags", "()I");
        copy(mv, "programSize", "()I");
        copy(mv, "engine", "()L" + ENGINE + ";");
        copy(mv, "wholeEngine", "()L" + WHOLE + ";");
        copy(mv, "unicodeProvider", "()L" + PROV + ";");
        copy(mv, "semantics", "()L" + SEM + ";");
        if (engType != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFAPATTERN, "engine", "()L" + ENGINE + ";", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, engType.getName().replace('.', '/'));
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseInternal, "<init>", superDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // public Object writeReplace() { return SerializationProxy.serialProxy(this); }
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "writeReplace", "()Ljava/lang/Object;", null,
            new String[]{"java/io/ObjectStreamException"});
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROXY, "serialProxy", "(L" + TDFAPATTERN + ";)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        // Define in a child of the base's loader: the subclass must link
        // against the base (an emitted shell lives in its own child
        // loader) and, through delegation, this facade's types.
        return new Definer(base.getClassLoader()).define(owner.replace('/', '.'), cw.toByteArray());
    }

    /** load src; call the named machinery getter. */
    private static void copy(MethodVisitor mv, String name, String desc) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFAPATTERN, name, desc, false);
    }

    /** Minimal defineClass exposure for the minted classes. */
    private static final class Definer extends ClassLoader {

        Definer(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
