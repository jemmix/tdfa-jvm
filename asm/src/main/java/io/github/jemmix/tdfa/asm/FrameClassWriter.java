package io.github.jemmix.tdfa.asm;

import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter} whose {@code COMPUTE_FRAMES} hierarchy resolution is
 * explicit and total, instead of ASM's default load-everything-or-throw.
 *
 * <p>The default {@code getCommonSuperClass} resolves each merged type pair
 * with {@code Class.forName} on the ASM jar's own loader and throws
 * {@code TypeNotPresentException} when resolution fails. For generated code
 * that is a landmine: the classes being written are by construction defined
 * in loaders the backend cannot see — engines load through
 * {@code GenClassLoader} children, BYO shells through consumer loaders — and
 * a single unresolvable reference would crash emission at pattern-compile
 * time.
 *
 * <p>The override encodes the merge shapes emitted code actually has:
 * <ul>
 *   <li>every merge involving a generated/unresolvable type is between
 *       <em>identical</em> descriptors (locals keep one static type across
 *       joins), so identical types resolve to themselves and unresolvable
 *       pairs degrade to {@code java/lang/Object};</li>
 *   <li>all other merges are between JDK/core types, every one visible to
 *       the ASM module's loader, resolved with ASM's documented walk
 *       (mutual assignability, then the superclass chain);</li>
 *   <li>an explicit {@code java/lang/Object} operand short-circuits, and
 *       non-identical array descriptors (e.g. {@code [I} vs
 *       {@code [Ljava/lang/String;}) resolve to {@code java/lang/Object} —
 *       the same answer the reflective walk produces for arrays.</li>
 * </ul>
 *
 * <p>If a future emitter shape ever violates the identical-types assumption,
 * the wrong frame is caught at build time by {@code CheckClassAdapter} +
 * {@code SimpleVerifier} dataflow ({@code EmittedBytecodePolicyTest}) — not
 * as a production {@code VerifyError}.
 */
class FrameClassWriter extends ClassWriter {
    FrameClassWriter(int flags) {
        super(flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) return type1;
        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
            return "java/lang/Object";
        }
        if (type1.charAt(0) == '[' || type2.charAt(0) == '[') return "java/lang/Object";
        Class<?> c1 = probe(type1);
        Class<?> c2 = probe(type2);
        if (c1 == null || c2 == null) return "java/lang/Object";
        // ASM-documented walk: mutual assignability, then c1's superclass chain.
        if (c1.isAssignableFrom(c2)) return type1;
        if (c2.isAssignableFrom(c1)) return type2;
        for (Class<?> s = c1.getSuperclass(); s != null; s = s.getSuperclass()) {
            if (s.isAssignableFrom(c2)) return s.getName().replace('.', '/');
        }
        return "java/lang/Object";
    }

    /** Resolve without initializing; {@code null} = not visible to this loader. */
    private static Class<?> probe(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                    FrameClassWriter.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
