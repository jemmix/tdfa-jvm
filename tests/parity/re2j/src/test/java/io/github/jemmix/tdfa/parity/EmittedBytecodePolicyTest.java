package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Modifier;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.re2j.Re2jUnicodeProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Devirtualization policy of the ASM-generated engine classes, enforced on
 * the emitted bytes themselves (dumped via {@code -Dtdfa.asm.dump}).
 *
 * <p>The generated tier is only fast because HotSpot can turn every call in
 * its hot methods into a direct/inlined one. That rests on a structural
 * property the emitter must never lose:
 * <ul>
 *   <li>no {@code INVOKEINTERFACE} and no {@code INVOKEDYNAMIC} anywhere;</li>
 *   <li>every {@code INVOKEVIRTUAL} receiver is a <b>final</b> class, so the
 *       call site is provably monomorphic ({@code String}, {@code [I},
 *       {@code TdfaRunner}, {@code Tdfa}, ...). Receiver finality is checked
 *       reflectively, not by allowlist — a new emitter call to a non-final
 *       owner fails here at construction time, not as a silent dispatch.</li>
 * </ul>
 * {@code INVOKESPECIAL} (constructors, private, super) is always direct and
 * {@code INVOKESTATIC} needs no receiver; both are unconstrained.
 */
class EmittedBytecodePolicyTest {

    /** Shapes that trigger every emitted-ladder path: literal needle,
     *  candidate scan with extract kernel, bare kernel. All fastPath/INLINED
     *  tier (no zero-width masks), per the emitter's pickMode. */
    private static final String[] PATTERNS = {
            "needle42hash",          // pure literal -> LITERAL short-circuit
            "[a-z]+ing",             // candidate scan -> extractOne kernel
            "(\\d{3})-(\\d{4})",     // tagged kernel + register ops
            "\\w+@(\\w+)\\.[a-z]{2,4}",
    };

    private static final class Calls {
        final String file, method;
        final int opcode;
        final String owner, name, desc;
        Calls(String file, String method, int opcode, String owner, String name, String desc) {
            this.file = file; this.method = method; this.opcode = opcode;
            this.owner = owner; this.name = name; this.desc = desc;
        }
        @Override public String toString() {
            // OPCODES is indexed relative to INVOKEVIRTUAL (182): storing the
            // names at absolute opcode indices would need a 187-slot array.
            return file + "." + method + ": " + OPCODES[opcode - Opcodes.INVOKEVIRTUAL] + " " + owner + "." + name + desc;
        }
    }

    private static final String[] OPCODES = {"", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "INVOKEVIRTUAL", "INVOKESPECIAL", "INVOKESTATIC", "INVOKEINTERFACE", "INVOKEDYNAMIC"};

    @Test
    void generatedEngineClassesAreDevirtualizable() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> dumped = compileAndDump(tmp);
        assertThat(dumped.size()).as("at least one Gen engine class dumped").isGreaterThan(0);

        List<Calls> violations = new ArrayList<>();
        int[] counts = new int[3];  // virtual, static, special
        for (Path classFile : dumped) {
            byte[] bytes = Files.readAllBytes(classFile);
            String fileName = classFile.getFileName().toString();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                String method = "<clinit>";
                @Override public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                    method = name + d;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            switch (opcode) {
                                case Opcodes.INVOKEVIRTUAL -> {
                                    counts[0]++;
                                    if (!receiverIsFinal(owner))
                                        violations.add(new Calls(fileName, method, opcode, owner, name, desc));
                                }
                                case Opcodes.INVOKESTATIC -> counts[1]++;
                                case Opcodes.INVOKESPECIAL -> counts[2]++;
                                case Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC ->
                                        violations.add(new Calls(fileName, method, opcode, owner, name, desc));
                                default -> {}
                            }
                        }
                    };
                }
            }, 0);
        }
        // There must be real code under test, not empty shells.
        assertThat(counts[0] + counts[1] + counts[2]).as("call sites in generated classes").isGreaterThan(20);
        assertThat(violations)
                .as("every call in the generated tier must be direct or monomorphic:\n%s",
                        violations.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n")))
                .isEmpty();
    }

    /** Compile the shapes with dumping enabled; return the dumped class files. */
    private static List<Path> compileAndDump(Path tmp) throws Exception {
        String prefix = "io.github.jemmix.tdfa.gen.Gen";
        List<Path> before = listGenClasses(tmp, prefix);
        for (Path p : before) Files.deleteIfExists(p);

        String prev = System.getProperty("tdfa.asm.dump");
        System.setProperty("tdfa.asm.dump", "true");
        try {
            for (String re : PATTERNS)
                io.github.jemmix.tdfa.Pattern.compile(re, 0,
                        (io.github.jemmix.tdfa.core.RegexEngineFactory) null, Re2jUnicodeProvider.INSTANCE);
        } finally {
            if (prev == null) System.clearProperty("tdfa.asm.dump");
            else System.setProperty("tdfa.asm.dump", prev);
        }
        List<Path> after = listGenClasses(tmp, prefix);
        // shells (Gen*Pattern/Gen*Matcher) are dumped to /tmp/shells with a
        // different name shape; only engine classes land here.
        return after;
    }

    private static List<Path> listGenClasses(Path dir, String prefix) throws Exception {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*.class")) {
            for (Path p : ds) out.add(p);
        }
        return out;
    }

    /** Reflective receiver-finality: the load-bearing check. Arrays are final
     *  per JLS; everything else must declare (or be) final. */
    private static boolean receiverIsFinal(String owner) {
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false,
                    EmittedBytecodePolicyTest.class.getClassLoader());
            return c.isArray() || Modifier.isFinal(c.getModifiers());
        } catch (ClassNotFoundException e) {
            return false;  // unloadable receiver: definitely not verifiable
        }
    }
}
