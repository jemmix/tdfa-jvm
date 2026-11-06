package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.core.compile.MachineryCarrier;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mints the API-typed wrapper around a machinery pattern — the one
 * emission step a facade performs for itself, with its own types. Given
 * a compiled {@link TDFAPattern} (bare interpreter tier or emitted
 * shell), an API interface, and a serialization anchor, this emits into
 * the pattern's own classloader a public final class implementing the
 * interface and composition-wrapping the pattern: one field, a
 * constructor, a per-method delegation, and a {@code writeReplace} that
 * hands serialization to the anchor.
 *
 * <p>This emitter is facade-agnostic by construction: the interface and
 * the anchor arrive as opaque {@code Class} values from the calling
 * facade (which owns them), nothing here knows a facade API, and the
 * machinery below never sees either. The compatibility the emission
 * relies on — every instance method of the interface maps to a public
 * method of {@code TDFAPattern} with a return the interface accepts —
 * is <b>verified, not assumed</b>, method by method at emission time: a
 * facade whose API has drifted from the machinery level fails the
 * compile loudly rather than assuming the two still rhyme.
 *
 * <p><b>Why per-pattern.</b> The wrapper's delegations are emitted into
 * a class unique to the wrapped pattern, so each delegation site has
 * exactly one receiver type for its whole life — monomorphic by
 * construction, exactly like the emitted shell's own methods. The hot
 * chain is untouched: {@code matcher()} delegates to the emitted shell
 * (one cold hop per matcher creation), and everything from
 * {@code Matcher.find()} down is the emitted core pair's devirtualized
 * ladder. Wrappers around the bare interpreter pattern reuse one cached
 * class per (interface, anchor) pair — that tier has no per-pattern
 * classes to bind to.
 *
 * <p>The wrapper also implements {@link MachineryCarrier}, returning the
 * wrapped pattern — the machinery view tests and harnesses use to
 * inspect a facade compile's engines and provider without reflection.
 *
 * <p><b>The serialization anchor</b> is the calling tier's own class
 * bearing a {@code public static Object serialProxy(TDFAPattern)} —
 * the wrapper's {@code writeReplace} forwards to it, so the proxy (and
 * the recompile route it owns) live in the tier that owns the API, and
 * nothing reflective crosses upward through the machinery.
 *
 * <p>Public-but-internal: bytecode tier ({@code :asm}), referenced by
 * the facades only — not shipped API.
 */
public final class WrapperEmitter {

    private static final String TDFAPATTERN = "io/github/jemmix/tdfa/core/compile/TDFAPattern";

    private static final String CARRIER = "io/github/jemmix/tdfa/core/compile/MachineryCarrier";

    private static final String TDFAPATTERN_D = "L" + TDFAPATTERN + ";";

    /** Monotonic wrapper name sequence — same contract as ShellEmitter's. */
    private static final AtomicLong WRAP_SEQ = new AtomicLong();

    /** Interpreter-tier wrapper classes, one per (api, anchor) pair. */
    private static final ConcurrentHashMap<String, Constructor<?>> SHARED = new ConcurrentHashMap<>();

    private WrapperEmitter() {
    }

    /**
     * Wrap {@code core} in a generated class implementing {@code api}
     * (the calling facade's {@code Pattern} interface), delegating
     * every interface method to the machinery pattern and serializing
     * through {@code anchor.serialProxy(TDFAPattern)}. Returns the
     * wrapper as {@code Object} — the caller casts to its own
     * interface (it owns the type). Throws
     * {@link IllegalStateException} if the interface has drifted from
     * the machinery level (an unmappable method) or the anchor is
     * missing its {@code serialProxy} — loudly, never a silent
     * partial wrapper.
     */
    public static Object wrap(TDFAPattern core, Class<?> api, Class<?> anchor) {
        try {
            boolean sharedTier = core.getClass() == TDFAPattern.class;
            String key = null;
            Constructor<?> ctor = null;
            if (sharedTier) {
                // Interpreter tier: no per-pattern class to bind to — one
                // cached wrapper per (api, anchor) serves every pattern.
                key = api.getName() + '#' + anchor.getName();
                ctor = SHARED.get(key);
            }
            if (ctor == null) {
                ctor = define(core, api, anchor).getDeclaredConstructor(TDFAPattern.class);
                ctor.setAccessible(true);
                if (sharedTier) {
                    Constructor<?> prev = SHARED.putIfAbsent(key, ctor);
                    if (prev != null) {
                        ctor = prev;
                    }
                }
            }
            return ctor.newInstance(core);
        } catch (IllegalStateException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("wrapper emission failed", e);
        }
    }

    /** Emit and define the wrapper class next to the wrapped pattern. */
    private static Class<?> define(TDFAPattern core, Class<?> api, Class<?> anchor) throws ClassNotFoundException {
        String apiInternal = api.getName().replace('.', '/');
        String anchorInternal = anchor.getName().replace('.', '/');
        String owner = "io/github/jemmix/tdfa/gen/Wrap" + Long.toHexString(WRAP_SEQ.incrementAndGet());

        ClassWriter cw = new FrameClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null,
            "java/lang/Object", new String[]{apiInternal, CARRIER, "java/io/Serializable"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "p", TDFAPATTERN_D, null, null).visitEnd();

        // ctor(TDFAPattern p) { this.p = p; }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + TDFAPATTERN_D + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "p", TDFAPATTERN_D);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // The Object trio delegates like any other method (the machinery
        // base overrides all three — equality rides the lane axis).
        try {
            emitDelegation(cw, owner, Object.class.getMethod("equals", Object.class));
            emitDelegation(cw, owner, Object.class.getMethod("hashCode"));
            emitDelegation(cw, owner, Object.class.getMethod("toString"));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("JDK without Object's trio", e); // unreachable
        }
        // Every own (non-static, non-default) instance method of the API.
        for (Method m : api.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isDefault() || m.getDeclaringClass() == Object.class) {
                continue;
            }
            emitDelegation(cw, owner, m);
        }

        // public TDFAPattern machinery() { return p; } — the machinery view
        // (tests and harnesses unwrap the wrapper without reflection).
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "machinery", "()" + TDFAPATTERN_D, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "p", TDFAPATTERN_D);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public Object writeReplace() { return Anchor.serialProxy(p); }
        Method hook = anchorHook(anchor);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "writeReplace", "()Ljava/lang/Object;", null,
            new String[]{"java/io/ObjectStreamException"});
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "p", TDFAPATTERN_D);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, anchorInternal, hook.getName(),
            "(" + TDFAPATTERN_D + ")Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        // Define next to the wrapped pattern: a child of its loader sees
        // the pattern class (linkage), the machinery (superstructure), and
        // — through normal delegation — the API interface and the anchor.
        ClassLoader cl = core.getClass().getClassLoader();
        TdfaAsmBackend.GenClassLoader gcl;
        if (cl instanceof TdfaAsmBackend.GenClassLoader) {
            gcl = (TdfaAsmBackend.GenClassLoader) cl;
        } else {
            gcl = new TdfaAsmBackend.GenClassLoader(cl);
        }
        gcl.register(owner.replace('/', '.'), cw.toByteArray());
        return Class.forName(owner.replace('/', '.'), true, gcl);
    }

    /**
     * Emit one delegation: {@code return this.p.<m>(args);} with the
     * interface-declared return type. The machinery counterpart is
     * looked up by name and parameter types and its return checked
     * against the declared one — an interface method the machinery
     * level cannot serve fails the whole emission, loudly (the drift
     * guard: nothing about the two APIs rhyming is assumed).
     */
    private static void emitDelegation(ClassWriter cw, String owner, Method m) {
        Method target = null;
        for (Method t : TDFAPattern.class.getMethods()) {
            if (t.getName().equals(m.getName()) && Arrays.equals(t.getParameterTypes(), m.getParameterTypes())) {
                target = t;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("API method " + m.getDeclaringClass().getName() + "." + m.getName()
                + Arrays.toString(m.getParameterTypes())
                + " has no machinery counterpart on TDFAPattern — the API has drifted from the machinery level;"
                + " the facade must adapt it itself");
        }
        if (!m.getReturnType().isAssignableFrom(target.getReturnType())) {
            throw new IllegalStateException("API method " + m.getDeclaringClass().getName() + "." + m.getName()
                + " returns " + m.getReturnType().getName() + " but the machinery counterpart returns "
                + target.getReturnType().getName() + " — not assignable; the facade must adapt it itself");
        }

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), descriptor(m), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "p", TDFAPATTERN_D);
        int slot = 1;
        for (Class<?> pt : m.getParameterTypes()) {
            mv.visitVarInsn(loadOp(pt), slot);
            slot += width(pt);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFAPATTERN, target.getName(), descriptor(target), false);
        if (!m.getReturnType().equals(target.getReturnType()) && !m.getReturnType().isPrimitive()) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, internal(m.getReturnType()));
        }
        mv.visitInsn(returnOp(m.getReturnType()));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The anchor's serialProxy hook, verified once per emission. */
    private static Method anchorHook(Class<?> anchor) {
        try {
            Method h = anchor.getDeclaredMethod("serialProxy", TDFAPattern.class);
            if (!Modifier.isStatic(h.getModifiers()) || !Modifier.isPublic(h.getModifiers())
                || !Object.class.equals(h.getReturnType())) {
                throw new IllegalStateException("serialization anchor " + anchor.getName()
                    + ".serialProxy(TDFAPattern) must be a public static Object");
            }
            return h;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("serialization anchor " + anchor.getName()
                + " is missing its public static Object serialProxy(TDFAPattern)", e);
        }
    }

    // ---- reflection-to-bytecode helpers ----

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> pt : m.getParameterTypes()) {
            sb.append(desc(pt));
        }
        return sb.append(')').append(desc(m.getReturnType())).toString();
    }

    private static String desc(Class<?> c) {
        if (c == void.class) {
            return "V";
        }
        if (c.isArray()) {
            // Class#getName() is already the descriptor form: [B, [Ljava/lang/String; ...
            return c.getName().replace('.', '/');
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == short.class) {
            return "S";
        }
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == double.class) {
            return "D";
        }
        return "L" + internal(c) + ";";
    }

    private static String internal(Class<?> c) {
        return c.getName().replace('.', '/');
    }

    private static int width(Class<?> c) {
        return c == long.class || c == double.class ? 2 : 1;
    }

    private static int loadOp(Class<?> c) {
        if (c == long.class) {
            return Opcodes.LLOAD;
        }
        if (c == float.class) {
            return Opcodes.FLOAD;
        }
        if (c == double.class) {
            return Opcodes.DLOAD;
        }
        if (c.isPrimitive()) {
            return Opcodes.ILOAD;
        }
        return Opcodes.ALOAD;
    }

    private static int returnOp(Class<?> c) {
        if (c == void.class) {
            return Opcodes.RETURN;
        }
        if (c == long.class) {
            return Opcodes.LRETURN;
        }
        if (c == float.class) {
            return Opcodes.FRETURN;
        }
        if (c == double.class) {
            return Opcodes.DRETURN;
        }
        if (c.isPrimitive()) {
            return Opcodes.IRETURN;
        }
        return Opcodes.ARETURN;
    }
}
