package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.core.MatchResult;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class TdfaAsmBackend {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String ENGINE = "io/github/jemmix/tdfa/core/RegexEngine";
    private static final String HOLDER = "io/github/jemmix/tdfa/tdfa/TdfaRunner$MatchHolder";
    private static final String RESULT = "io/github/jemmix/tdfa/core/MatchResult";
    private static final String CS = "java/lang/CharSequence";
    private static final String STR = "java/lang/String";
    private static final String CS_D = "Ljava/lang/CharSequence;";
    private static final String ARRAYS = "java/util/Arrays";
    private static final String RUNNER = "io/github/jemmix/tdfa/tdfa/TdfaRunner";
    private static final String RUNNER_D = "L" + RUNNER + ";";
    private static final String TDFA = "io/github/jemmix/tdfa/tdfa/Tdfa";
    private static final String TDFA_D = "L" + TDFA + ";";

    /** A per-pattern generation result: the engine instance plus the classloader
     *  that defines its class (and any additionally generated per-pattern classes,
     *  e.g. the facade Pattern/Matcher shell tier) plus the Tdfa backing it. The loader
     *  is unreferenced once the pattern is garbage → all its classes unload together. */
    public record Generated(RegexEngine engine, java.lang.ClassLoader loader, String owner, Tdfa tdfa) { }

    /** Child loader that can define any number of registered classes for one pattern. */
    public static final class GenClassLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        GenClassLoader(ClassLoader parent) { super(parent); }
        /** Register another class to be defined by this loader (same pattern). */
        public void register(String name, byte[] bytes) { classes.put(name, bytes); }
        @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
            byte[] b = classes.remove(n);
            if (b != null) return defineClass(n, b, 0, b.length);
            return super.findClass(n);
        }
    }

    public static Generated generate(Tdfa tdfa) {
        try {
            long id = COUNTER.incrementAndGet();
            String cn = "io.github.jemmix.tdfa.gen.Gen" + id;
            String owner = cn.replace('.', '/');
            byte[] bc = generateBytes(tdfa, owner);
            if (Boolean.getBoolean("tdfa.asm.dump")) {
                String dp = System.getProperty("java.io.tmpdir") + "/" + owner.replace('/', '.') + ".class";
                try { java.nio.file.Files.write(java.nio.file.Paths.get(dp), bc); } catch (Exception ignored) {}
            }
            GenClassLoader cl = new GenClassLoader(TdfaAsmBackend.class.getClassLoader());
            cl.register(cn, bc);
            RegexEngine engine = (RegexEngine) Class.forName(cn, true, cl)
                    .getDeclaredConstructor(Tdfa.class).newInstance(tdfa);
            return new Generated(engine, cl, owner, tdfa);
        } catch (Exception e) {
            throw new IllegalStateException("ASM backend failed", e);
        }
    }

    /** Dispatch mode picked at class-emit time, see {@link #pickMode}. */
    enum DispatchMode { INLINED, DELEGATE }

    private static byte[] generateBytes(Tdfa tdfa, String owner) {
        // One brain, one ladder: the search strategy (literal / candidate
        // scan / origin sim / trigger / walk ordering) lives in TdfaRunner;
        // generated code CALLS it (monomorphic hooks) and owns only the leaf
        // loops (inlined-dispatch walk with inlined ops, flat-table anchored
        // loop). Non-fastPath DFAs and literal needles delegate entirely —
        // the runner's ladder is already optimal there. The emitted ladder in
        // genMatch is a transcription of runStringExtractFast; the
        // strategy-conformance test asserts trace equality with the VM.
        DispatchMode mode = pickMode(tdfa);
        boolean delegate = mode == DispatchMode.DELEGATE
                || TdfaRunner.detectLiteralNeedle(tdfa) != null;
        boolean fastPath = mode == DispatchMode.INLINED;   // pickMode only INLINES fastPath-eligible DFAs
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", new String[]{ENGINE});
        if (delegate) {
            // Minimal class: just an init storing the runner, and forwarding stubs
            // for the RegexEngine interface. No static tables, no <clinit>.
            genDelegateInit(cw, owner);
            genDelegateMatches(cw, owner);
            genDelegateFind(cw, owner);
            genDelegateMatch(cw, owner);
            genMetadataMethods(cw, owner);
        } else {
            genClinit(cw, tdfa, owner, fastPath);
            genInit(cw, owner, tdfa, fastPath);
            genMatches(cw, owner, fastPath);
            genFind(cw, owner);
            genMatch(cw, tdfa, owner);
            genExtractOne(cw, tdfa, owner);
            genToResult(cw, tdfa, owner);
            genEntryOkC(cw, owner);
            genPositionFlagsC(cw, owner, tdfa.multiline(), tdfa.unicodeWordBoundary());
            if (tdfa.unicodeWordBoundary()) {
                genIsUnicodeWordChar(cw, owner);
                genIsWordBefore(cw, owner);
                genIsWordAt(cw, owner);
            }
            genMetadataMethods(cw, owner);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ===== <clinit> =====

    private static void genClinit(ClassWriter cw, Tdfa tdfa, String owner, boolean fastPath) {
        // Field declarations only; all data flows from the Tdfa arg through <init>.
        // (Prior design populated ENTRY_MASK/ACCEPT_MASK/IS_ACCEPT/STOP_MASK/ASCII_TARGET/
        // FIXED_* via per-element IASTORE in <clinit>, which exceeded the JVM 65 KB
        // method-size limit on DFAs with many states (e.g. dictionary alternation,
        // 21 K states × 64 STOP_MASK slots = 1.36 M entries; or fastPath-eligible
        // wide-ASCII-class patterns like [^u-z]{80}x with 16 K ASCII_TARGET IASTOREs).
        for (String f : new String[]{"ENTRY_MASK", "ACCEPT_MASK", "STOP_MASK", "IS_ACCEPT"})
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, f, "[I", null, null).visitEnd();
        if (fastPath)
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "ASCII_TARGET", "[I", null, null).visitEnd();
        boolean hasFixed = tdfa.fixedBase() != null;
        if (hasFixed) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FIXED_BASE", "[I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FIXED_OFFSET", "[I", null, null).visitEnd();
        }
        // Empty <clinit>. Required by the JVM if any static initializer is implied,
        // and harmless. The actual array population lives in <init> as reference
        // copies from the Tdfa constructor arg.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== <init> =====

    private static void genInit(ClassWriter cw, String owner, Tdfa tdfa, boolean fastPath) {
        // Instance field holding the TdfaRunner: the shared strategy brain
        // (ladder hooks are monomorphic final-class calls) and the full
        // delegate path for everything the generated class doesn't own.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "runner", RUNNER_D, null, null).visitEnd();
        if (tdfa.unicodeWordBoundary()) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "WORD_RANGES", "[I", null, null).visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + TDFA_D + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, RUNNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RUNNER, "<init>", "(" + TDFA_D + ")V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "runner", RUNNER_D);
        if (tdfa.unicodeWordBoundary()) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "wordRanges", "()[I", false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "WORD_RANGES", "[I");
        }
        // ENTRY_MASK = tdfa.stateEntryMask() (reference copy — no per-element bytecode)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stateEntryMask", "()[I", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ENTRY_MASK", "[I");
        // ACCEPT_MASK = tdfa.stateAcceptMask()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stateAcceptMask", "()[I", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ACCEPT_MASK", "[I");
        // STOP_MASK = tdfa.stopOnAcceptMask()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stopOnAcceptMask", "()[I", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STOP_MASK", "[I");
        // IS_ACCEPT = new int[n]; for each state s, IS_ACCEPT[s] = (stateMeta[s] & 1)
        // Computed in a fixed-size runtime loop — bytecode is ~30 bytes regardless
        // of state count (matters for dictionary-scale DFAs with 20 K+ states).
        int n = tdfa.stateCount();
        ic(mv, n);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        mv.visitVarInsn(Opcodes.ASTORE, 2);  // local 2 = IS_ACCEPT temp
        // local 3 = loop counter
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        Label loop = new Label(), done = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        ic(mv, n);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        // IS_ACCEPT[s] = stateMeta[s] & 1
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stateMeta", "()[I", false);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IAND);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "IS_ACCEPT", "[I");
        // FIXED_BASE / FIXED_OFFSET (if fixed-tag optimization applied)
        if (tdfa.fixedBase() != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "fixedBase", "()[I", false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "FIXED_BASE", "[I");
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "fixedOffset", "()[I", false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "FIXED_OFFSET", "[I");
        }
        // ASCII_TARGET (fastPath only): populate via a runtime loop over RANGES_TABLE.
        // The prior design emitted one IASTORE per ASCII char per state range in
        // <clinit>, which for wide-ASCII-class patterns like [^u-z]{80}x produced
        // ~16 K IASTOREs (~160 KB bytecode) and tripped the 65 KB method limit.
        // Loop body is fixed-size; bytecode is ~100 bytes regardless of state count.
        //
        // Pseudo: for s in 0..n-1: meta=stateMeta[s]; base=stateBase[s]; cnt=(meta>>>1)&0xFFFF;
        //         for i in 0..cnt-1: o=(base+i)*5; lo=max(ranges[o],0); hi=min(ranges[o+1],127);
        //                            target=ranges[o+2]; if (target<0) continue;
        //                            for c in lo..hi: ASCII_TARGET[s*128+c] = target;
        if (fastPath) {
            // ranges from the Tdfa param (local 14) — RANGES_TABLE static is gone
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "ranges", "()[I", false);
            mv.visitVarInsn(Opcodes.ASTORE, 14);
            int tableSize = tdfa.stateCount() * 128;
            ic(mv, tableSize);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            mv.visitVarInsn(Opcodes.ASTORE, 4);  // local 4 = ASCII_TARGET
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);
            // locals: 5=s, 6=cnt, 7=base, 8=i, 9=o, 10=lo, 11=hi, 12=target, 13=c
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 5);
            Label sLoop = new Label(), sDone = new Label();
            mv.visitLabel(sLoop);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            ic(mv, n);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, sDone);
            // cnt = (stateMeta[s] >>> 1) & 0xFFFF
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stateMeta", "()[I", false);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IUSHR);
            mv.visitIntInsn(Opcodes.SIPUSH, 0xFFFF);
            mv.visitInsn(Opcodes.IAND);
            mv.visitVarInsn(Opcodes.ISTORE, 6);
            // base = stateBase[s]
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TDFA, "stateBase", "()[I", false);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, 7);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 8);  // i = 0
            Label iLoop = new Label(), iDone = new Label();
            mv.visitLabel(iLoop);
            mv.visitVarInsn(Opcodes.ILOAD, 8);
            mv.visitVarInsn(Opcodes.ILOAD, 6);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, iDone);
            // o = (base + i) * 5
            mv.visitVarInsn(Opcodes.ILOAD, 7);
            mv.visitVarInsn(Opcodes.ILOAD, 8);
            mv.visitInsn(Opcodes.IADD);
            mv.visitIntInsn(Opcodes.BIPUSH, 5);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ISTORE, 9);
            // target = RANGES_TABLE[o+2]
            mv.visitVarInsn(Opcodes.ALOAD, 14);
            mv.visitVarInsn(Opcodes.ILOAD, 9);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, 12);
            // if (target < 0) goto iNext
            Label iNext = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 12);
            mv.visitJumpInsn(Opcodes.IFLT, iNext);
            // lo = max(RANGES_TABLE[o], 0)
            mv.visitVarInsn(Opcodes.ALOAD, 14);
            mv.visitVarInsn(Opcodes.ILOAD, 9);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitInsn(Opcodes.DUP);
            Label loSet = new Label();
            mv.visitJumpInsn(Opcodes.IFGE, loSet);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(loSet);
            mv.visitVarInsn(Opcodes.ISTORE, 10);
            // hi = min(RANGES_TABLE[o+1], 127)
            mv.visitVarInsn(Opcodes.ALOAD, 14);
            mv.visitVarInsn(Opcodes.ILOAD, 9);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitIntInsn(Opcodes.SIPUSH, 127);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 11);
            // for (c = lo; c <= hi; c++) ASCII_TARGET[s*128 + c] = target
            mv.visitVarInsn(Opcodes.ILOAD, 10);
            mv.visitVarInsn(Opcodes.ISTORE, 13);
            Label cLoop = new Label(), cDone = new Label();
            mv.visitLabel(cLoop);
            mv.visitVarInsn(Opcodes.ILOAD, 13);
            mv.visitVarInsn(Opcodes.ILOAD, 11);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, cDone);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitIntInsn(Opcodes.SIPUSH, 128);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 13);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ILOAD, 12);
            mv.visitInsn(Opcodes.IASTORE);
            mv.visitIincInsn(13, 1);
            mv.visitJumpInsn(Opcodes.GOTO, cLoop);
            mv.visitLabel(cDone);
            mv.visitLabel(iNext);
            mv.visitIincInsn(8, 1);
            mv.visitJumpInsn(Opcodes.GOTO, iLoop);
            mv.visitLabel(iDone);
            mv.visitIincInsn(5, 1);
            mv.visitJumpInsn(Opcodes.GOTO, sLoop);
            mv.visitLabel(sDone);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ASCII_TARGET", "[I");
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== interface methods =====

    /**
     * Anchored matches: full delegate to the runner. The flat-table anchored
     * loop is identical work in both backends (measured equal), so emitting it
     * buys nothing — and the runner owns the ANCHORED_FAST/ANCHORED/GENERIC
     * strategy recording, keeping the traces trivially conformant.
     */
    private static void genMatches(ClassWriter cw, String owner, boolean fastPath) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "matches", "(" + CS_D + ")Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genFind(ClassWriter cw, String owner) {
        // Delegate to the runner's find(), which uses the multi-state parallel
        // simulation — O(n × |states|) instead of the O(n²) outer-loop restart
        // that runBoolean used. The boolean result is identical; only the path
        // differs. The ASM inlined transitions don't help here because the O(n²)
        // restart dominates for non-matching haystacks.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "find", "(" + CS_D + ")Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    /**
     * The emitted strategy ladder — a bytecode transcription of
     * {@code TdfaRunner.runStringExtractFast}. Strategy pieces (literal
     * needle, candidate bounds, origin sim, trigger scan) are calls into the
     * embedded runner (monomorphic: TdfaRunner is final); the walk itself is
     * the generated {@code extractOne} leaf (inlined dispatch + inlined ops).
     * Emits {@code TdfaRunner.trace} calls at the same decision points the
     * runner records, so the strategy-conformance test can assert the two
     * backends pick identical sequences.
     *
     * <p>Only emitted for fastPath INLINED classes (pickMode guarantees
     * fastPath: no masks, disjoint ranges) — non-fastPath shapes never see
     * this method because they compile to DELEGATE classes.
     */
    private static void genMatch(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "match", "(" + CS_D + "I)L" + RESULT + ";", null, null);
        mv.visitCode();
        // locals: 0=this, 1=input, 2=from, 3=s, 4=len, 5=holder, 6=leftmost/idx,
        //         7=p, 8=fails, 9=c, 10=bits, 11=needle
        Label isStr = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, STR);
        mv.visitJumpInsn(Opcodes.IFNE, isStr);
        // non-String: full delegate (runner records GENERIC)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "match", "(" + CS_D + "I)L" + RESULT + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(isStr);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, STR);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "length", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // --- literal needle: indexOf short-circuit (runner-identical) ---
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "literalNeedle", "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 11);
        Label noNeedle = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitJumpInsn(Opcodes.IFNULL, noNeedle);
        emitTrace(mv, "LITERAL");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "indexOf", "(Ljava/lang/String;I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label litMiss = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, litMiss);
        // hit: toResult(new MatchHolder(idx, idx + needle.length(), new int[0]))
        mv.visitTypeInsn(Opcodes.NEW, HOLDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "length", "()I", false);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HOLDER, "<init>", "(II[I)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toResult", "(L" + HOLDER + ";)L" + RESULT + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(litMiss);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(noNeedle);

        // --- 1) one exact walk from `from` ---
        emitTrace(mv, "EXACT_FROM");
        emitExtractOne(mv, owner, 3, 2, 4, 5);
        emitReturnToResult(mv, owner, 5);

        // --- 1b) short-input candidate scan ---
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "startBits", "()[J", false);
        mv.visitVarInsn(Opcodes.ASTORE, 10);
        Label skipCand = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitJumpInsn(Opcodes.IFNULL, skipCand);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "candScanMax", "()I", false);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, skipCand);
        emitTrace(mv, "CAND_SCAN");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);                       // fails = 0
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 7);                       // p = from + 1
        Label candLoop = new Label(), candDone = new Label(), candNext = new Label(), candWalk = new Label();
        mv.visitLabel(candLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, candDone);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, 9);
        // bit test: (bits[c >>> 6] >>> (c & 63) & 1L) != 0L
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitIntInsn(Opcodes.BIPUSH, 6);
        mv.visitInsn(Opcodes.ISHR);
        mv.visitInsn(Opcodes.LALOAD);
        mv.visitVarInsn(Opcodes.ILOAD, 9);
        mv.visitIntInsn(Opcodes.BIPUSH, 63);
        mv.visitInsn(Opcodes.IAND);
        mv.visitInsn(Opcodes.LSHR);
        mv.visitInsn(Opcodes.LCONST_1);
        mv.visitInsn(Opcodes.LAND);
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitInsn(Opcodes.LCMP);
        mv.visitJumpInsn(Opcodes.IFEQ, candNext);
        // adaptive boolean pre-filter after 3 failed extract walks
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, candWalk);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "booleanMatchFrom", "(Ljava/lang/String;II)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, candWalk);
        mv.visitJumpInsn(Opcodes.GOTO, candNext);
        mv.visitLabel(candWalk);
        emitExtractOne(mv, owner, 3, 7, 4, 5);
        emitReturnToResult(mv, owner, 5);
        mv.visitIincInsn(8, 1);                                   // fails++
        mv.visitLabel(candNext);
        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, candLoop);
        mv.visitLabel(candDone);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(skipCand);

        // --- 2) budgeted origin sim, trigger fallback ---
        emitTrace(mv, "ORIGIN_SIM");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "originSimBudget", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "originSimLeftmost", "(" + CS_D + "III)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label noBudget = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.GETSTATIC, RUNNER, "LSS_BUDGET", "I");
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, noBudget);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "triggerScanTop", "(Ljava/lang/String;II)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label noMatch1 = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, noMatch1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "originSimLeftmost", "(" + CS_D + "III)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        mv.visitLabel(noBudget);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, noMatch1);
        // exact walk from leftmost
        emitExtractOne(mv, owner, 3, 6, 4, 5);
        emitReturnToResult(mv, owner, 5);
        // --- 3) defensive restart ---
        emitTrace(mv, "WALK_RESTART");
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label rstLoop = new Label(), rstDone = new Label();
        mv.visitLabel(rstLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, rstDone);
        emitExtractOne(mv, owner, 3, 7, 4, 5);
        emitReturnToResult(mv, owner, 5);
        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, rstLoop);
        mv.visitLabel(rstDone);
        mv.visitLabel(noMatch1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    /** extractOne(s, fromLocal, toLocal) → holderLocal. */
    private static void emitExtractOne(MethodVisitor mv, String owner, int sL, int fromL, int toL, int holderL) {
        mv.visitVarInsn(Opcodes.ALOAD, sL);
        mv.visitVarInsn(Opcodes.ILOAD, fromL);
        mv.visitVarInsn(Opcodes.ILOAD, toL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "extractOne", "(Ljava/lang/String;II)L" + HOLDER + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, holderL);
    }

    /** if (holderL != null) return toResult(holderL); */
    private static void emitReturnToResult(MethodVisitor mv, String owner, int holderL) {
        Label cont = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, holderL);
        mv.visitJumpInsn(Opcodes.IFNULL, cont);
        mv.visitVarInsn(Opcodes.ALOAD, holderL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toResult", "(L" + HOLDER + ";)L" + RESULT + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(cont);
    }

    /** TdfaRunner.trace(TdfaRunner.Strategy.X) — strategy-conformance point. */
    private static void emitTrace(MethodVisitor mv, String strategy) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, RUNNER + "$Strategy", strategy, "L" + RUNNER + "$Strategy;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNNER, "trace", "(L" + RUNNER + "$Strategy;)V", false);
    }

    // ===== extractOne — single-start DFA walk with register tracking =====

    /**
     * The generated walk leaf: one exact walk from {@code from} over the
     * String (no char[] copy, no restart loop — the emitted ladder in
     * genMatch positions every call). Inlined per-state dispatch + inlined
     * register ops; reads via String.charAt.
     */
    private static void genExtractOne(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "extractOne", "(Ljava/lang/String;II)L" + HOLDER + ";", null, null);
        mv.visitCode();
        emitRunCore(mv, tdfa, owner);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Shared result epilogue: holder → MatchResult (fixed-tag rewrite + ctor). */
    private static void genToResult(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "toResult", "(L" + HOLDER + ";)L" + RESULT + ";", null, null);
        mv.visitCode();
        // local 0 = h
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label ret = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, ret);
        if (tdfa.fixedBase() != null) {
            // BT22 §6.4 fixed-tag reconstruction: rewrite fixed-tag slots in the
            // holder's regs from their base tag values, before constructing MatchResult.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "regs", "[I");
            ic(mv, tdfa.finalRegBase());
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "FIXED_BASE", "[I");
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "FIXED_OFFSET", "[I");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RESULT, "reconstructFixed", "([II[I[I)V", false);
        }
        mv.visitTypeInsn(Opcodes.NEW, RESULT);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "regs", "[I");
        ic(mv, tdfa.finalRegBase());
        ic(mv, tdfa.groupCount());
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "matchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "matchEnd", "I");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT, "<init>", "([IIIII)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(ret);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== entryOkC — entry mask check over String (helper for DFA dispatch) =====

    private static void genEntryOkC(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "entryOkC", "(IIILjava/lang/String;)Z", null, null);
        mv.visitCode();
        // locals: 0=state, 1=pos, 2=len, 3=input, 4=required
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label ok = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, ok);
        // (positionFlagsC(pos, len, input) & required) == required
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlagsC", "(IILjava/lang/String;)I", false);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label yes = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, yes);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(ok);
        mv.visitLabel(yes);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== positionFlagsC — position flags over String (helper) =====

    private static void genPositionFlagsC(ClassWriter cw, String owner, boolean multiline, boolean unicodeWord) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "positionFlagsC", "(IILjava/lang/String;)I", null, null);
        mv.visitCode();
        // locals: 0=pos, 1=len, 2=input, 3=flags, 4=t1, 5=t2
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // BEGIN_TEXT: pos == 0
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        Label l1 = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, l1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(l1);

        if (multiline) {
            // || (pos > 0 && input[pos-1] == '\n')
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            Label l1b = new Label();
            mv.visitJumpInsn(Opcodes.IFLE, l1b);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitIntInsn(Opcodes.BIPUSH, '\n');
            Label l1c = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, l1c);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.IOR);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitLabel(l1c);
            mv.visitLabel(l1b);
        }

        // END_TEXT: pos == len
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        Label l2 = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l2);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(l2);

        if (multiline) {
            // || (pos < len && input[pos] == '\n')
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            Label l2b = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, l2b);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitIntInsn(Opcodes.BIPUSH, '\n');
            Label l2c = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, l2c);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.IOR);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitLabel(l2c);
            mv.visitLabel(l2b);
        }

        // ABS_BEGIN (\A): pos == 0, always (never affected by (?m))
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        Label lab = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, lab);
        mv.visitIntInsn(Opcodes.BIPUSH, 16);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(lab);

        // ABS_END (\z): pos == len, always
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        Label lae = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, lae);
        mv.visitIntInsn(Opcodes.BIPUSH, 32);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(lae);

        // prevWord = pos > 0 && isWordBefore(pos, len, input)   [unicode: decodes surrogate pairs]
        // Under unicodeWord, isWordBefore decodes a supplementary codepoint ending at pos-1
        // with its high surrogate, so \b adjacent to supplementary word chars is computed on
        // the full codepoint. ASCII mode keeps the inline char check (surrogate halves are
        // simply non-word there).
        Label pf = new Label(), pd = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, pf);
        if (unicodeWord) {
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordBefore", "(IILjava/lang/String;)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitVarInsn(Opcodes.ISTORE, 4);
            emitIsWordBranch(mv, 4, pf, false, owner);
            mv.visitInsn(Opcodes.ICONST_1);
        }
        mv.visitJumpInsn(Opcodes.GOTO, pd);
        mv.visitLabel(pf);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(pd);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // currWord = pos < len && isWordAt(pos, len, input)
        Label cf = new Label(), cd = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, cf);
        if (unicodeWord) {
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordAt", "(IILjava/lang/String;)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitVarInsn(Opcodes.ISTORE, 5);
            emitIsWordBranch(mv, 5, cf, false, owner);
            mv.visitInsn(Opcodes.ICONST_1);
        }
        mv.visitJumpInsn(Opcodes.GOTO, cd);
        mv.visitLabel(cf);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(cd);

        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label nb = new Label(), done = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, nb);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(nb);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(done);

        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== shared core: DFA walk + search loop =====

    private static void emitRunCore(MethodVisitor mv, Tdfa tdfa, String owner) {
        final boolean perl = !tdfa.longestMatch();
        final int nStates = tdfa.stateCount();
        final int[] sm = tdfa.stateMeta(), rg = tdfa.ranges(), op = tdfa.ops(), sfo = tdfa.stateFinalOpsOff();

        // Compile-time check: is positionFlags ever needed?
        // PF is needed if any accepting state has ACCEPT_MASK != 0,
        // or any live transition range has reqMask != 0,
        // or PERL mode has accepting states (STOP_MASK indexed by PF).
        boolean pfNeeded = false;
        for (int s = 0; s < nStates && !pfNeeded; s++) {
            if ((sm[s] & 1) != 0) {
                if (perl || tdfa.stateAcceptMask()[s] != 0) pfNeeded = true;
            }
        }
        if (!pfNeeded) {
            for (int i = 0; i < rg.length; i += 5) {
                if (rg[i + 2] >= 0 && rg[i + 4] != 0) { pfNeeded = true; break; }
            }
        }

        // Locals: 0=input(String), 1=from, 2=len; 4..9 search/accept state
        final int IN=0, FROM=1, LEN=2;
        final int MS=4, ST=5, STATE=6, POS=7, HA=8, LAP=9;
        final int REGS = 10, LAS = 11, PF = 12, C_LV = 13;
        final int T1 = 14, T2 = 15, PF2 = 16, T3 = 17, T4 = 18, R = 19;

        // Single start: the emitted ladder in genMatch positions every call;
        // the walk itself never restarts (MS = ST = from).
        mv.visitVarInsn(Opcodes.ILOAD, FROM);
        mv.visitVarInsn(Opcodes.ISTORE, MS);

        // start = from
        mv.visitVarInsn(Opcodes.ILOAD, FROM);
        mv.visitVarInsn(Opcodes.ISTORE, ST);

        // Pre-initialize locals to satisfy verifier at search-loop merge point
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, STATE);
        mv.visitVarInsn(Opcodes.ILOAD, FROM); mv.visitVarInsn(Opcodes.ISTORE, POS);
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAP);
        mv.visitInsn(Opcodes.ACONST_NULL); mv.visitVarInsn(Opcodes.ASTORE, REGS);
        mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAS);

        // ===== SEARCH LOOP (single pass: MS == from) =====
        Label searchLoop = new Label(), searchEnd = new Label(), searchNext = new Label();
        mv.visitLabel(searchLoop);
        mv.visitVarInsn(Opcodes.ILOAD, ST);
        mv.visitVarInsn(Opcodes.ILOAD, MS);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, searchEnd);

        // Per-start: allocate regs
        {
            if (tdfa.registerCount() == 0) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, REGS);
            } else {
                ic(mv, tdfa.registerCount());
                mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
                mv.visitVarInsn(Opcodes.ASTORE, REGS);
                mv.visitVarInsn(Opcodes.ALOAD, REGS);
                mv.visitInsn(Opcodes.ICONST_M1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);
            }
        }

        // Entry check for start state at ST — skip if ENTRY_MASK[0] == 0
        if (tdfa.stateEntryMask()[0] != 0) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, T1);
            mv.visitVarInsn(Opcodes.ILOAD, T1);
            Label initEntryOk = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, initEntryOk);
            emitPFInline(mv, owner, IN, ST, LEN, PF2, T2, T3, T4, tdfa.multiline(), tdfa.unicodeWordBoundary());
            mv.visitVarInsn(Opcodes.ILOAD, PF2);
            mv.visitVarInsn(Opcodes.ILOAD, T1);
            mv.visitInsn(Opcodes.IAND);
            mv.visitVarInsn(Opcodes.ILOAD, T1);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, searchNext);
            mv.visitLabel(initEntryOk);
        }

        // state=0, pos=start, haveAccept=false, lastAcceptPos=-1
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, STATE);
        mv.visitVarInsn(Opcodes.ILOAD, ST); mv.visitVarInsn(Opcodes.ISTORE, POS);
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAP);

        // ===== DFA LOOP =====
        Label dfaLoop = new Label(), dfaEnd = new Label();
        mv.visitLabel(dfaLoop);

        // pf = positionFlags(pos, len, input) — skip when never needed
        if (pfNeeded)
            emitPFInline(mv, owner, IN, POS, LEN, PF, T1, T2, T3, tdfa.multiline(), tdfa.unicodeWordBoundary());
        else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, PF);
        }

        // Accept check (inline)
        Label skipAccept = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "IS_ACCEPT", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitJumpInsn(Opcodes.IFEQ, skipAccept);
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ACCEPT_MASK", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T1);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        Label doAccept = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, doAccept);
        mv.visitVarInsn(Opcodes.ILOAD, PF);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, skipAccept);
        mv.visitLabel(doAccept);
        mv.visitInsn(Opcodes.ICONST_1); mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitVarInsn(Opcodes.ILOAD, POS); mv.visitVarInsn(Opcodes.ISTORE, LAP);
        mv.visitVarInsn(Opcodes.ILOAD, STATE); mv.visitVarInsn(Opcodes.ISTORE, LAS);
        if (perl) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "STOP_MASK", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, STATE);
            mv.visitIntInsn(Opcodes.BIPUSH, 64);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, PF);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            ic(mv, Tdfa.NEVER_STOP);
            Label noStop = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, noStop);
            mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
            mv.visitLabel(noStop);
        }
        mv.visitLabel(skipAccept);

        // if (pos >= len) break
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, dfaEnd);

        // c = input.charAt(pos), then decode codepoint from surrogate pair
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, C_LV);
        emitCodePointDecode(mv, IN, C_LV, POS, LEN, T1);

        // ===== DFA DISPATCH (TABLESWITCH) =====
        emitDfaDispatch(mv, tdfa, owner,
                IN, STATE, POS, LEN, PF, C_LV, REGS, T1, T2, PF2, T3, T4,
                dfaLoop, dfaEnd, op);

        mv.visitLabel(dfaEnd);

        // ===== POST-DFA: check accept =====
        {
            // if (haveAccept) { ... return MatchHolder }
            Label noResult = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, HA);
            mv.visitJumpInsn(Opcodes.IFEQ, noResult);

            // r = regs == null ? new int[0] : regs.clone()
            if (tdfa.registerCount() == 0) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, REGS);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "[I");
            }
            mv.visitVarInsn(Opcodes.ASTORE, R);

            // final ops switch
            emitFinalOps(mv, tdfa, owner, R, LAS, LAP, POS, op, sfo, sm);

            // return new MatchHolder(start, lastAcceptPos, r)
            mv.visitTypeInsn(Opcodes.NEW, HOLDER);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ILOAD, ST);
            mv.visitVarInsn(Opcodes.ILOAD, LAP);
            mv.visitVarInsn(Opcodes.ALOAD, R);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HOLDER, "<init>", "(II[I)V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(noResult);
        }

        // searchNext: start++; goto searchLoop
        mv.visitLabel(searchNext);
        mv.visitIincInsn(ST, 1);
        mv.visitJumpInsn(Opcodes.GOTO, searchLoop);

        // searchEnd: no match at any start (single-start leaf: the one start)
        mv.visitLabel(searchEnd);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
    }

    // ===== DFA TABLESWITCH + range checks =====

    private static void emitDfaDispatch(MethodVisitor mv, Tdfa tdfa, String owner,
                                        int IN, int STATE, int POS, int LEN, int PF, int C_LV,
                                        int REGS, int T1, int T2, int PF2, int T3, int T4,
                                        Label dfaLoop, Label dfaEnd, int[] op) {
        int nStates = tdfa.stateCount();
        int[] sm = tdfa.stateMeta(), sb = tdfa.stateBase(), rg = tdfa.ranges();

        Label[] sl = new Label[nStates];
        Label def = new Label();
        for (int s = 0; s < nStates; s++) sl[s] = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitTableSwitchInsn(0, nStates - 1, def, sl);

        for (int s = 0; s < nStates; s++) {
            mv.visitLabel(sl[s]);
            int meta = sm[s];
            int base = sb[s], cnt = (meta >>> 1) & 0xFFFF;

            List<int[]> live = new ArrayList<>();
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o + 2] >= 0) live.add(new int[]{rg[o], rg[o+1], rg[o+2], rg[o+3], rg[o+4]});
            }
            Label stateDead = new Label();
            int nLive = live.size();
            for (int ri = 0; ri < nLive; ri++) {
                int[] range = live.get(ri);
                int lo = range[0], hi = range[1], target = range[2], opsOff = range[3], reqMask = range[4];
                boolean isLast = ri == nLive - 1;
                Label nextRange = isLast ? stateDead : new Label();

                // c >= lo && c <= hi
                mv.visitVarInsn(Opcodes.ILOAD, C_LV);
                ic(mv, lo);
                mv.visitJumpInsn(Opcodes.IF_ICMPLT, nextRange);
                mv.visitVarInsn(Opcodes.ILOAD, C_LV);
                ic(mv, hi);
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, nextRange);

                // mask check
                if (reqMask != 0) {
                    mv.visitVarInsn(Opcodes.ILOAD, PF);
                    ic(mv, reqMask);
                    mv.visitInsn(Opcodes.IAND);
                    ic(mv, reqMask);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextRange);
                }

                // register ops (extract only)
                if (opsOff != 0)
                    emitOpsInline(mv, op, opsOff, REGS, POS);

                // state = target
                ic(mv, target);
                mv.visitVarInsn(Opcodes.ISTORE, STATE);

                // codepoint pair advance — if decoded a non-BMP codepoint
                if (hi > 0xFFFF || (lo <= 0xDBFF && hi >= 0xD800)) {
                    Label noAdv = new Label();
                    mv.visitVarInsn(Opcodes.ILOAD, C_LV);
                    mv.visitLdcInsn(0x10000);
                    mv.visitJumpInsn(Opcodes.IF_ICMPLT, noAdv);
                    mv.visitIincInsn(POS, 1);
                    mv.visitLabel(noAdv);
                }

                // entry check for target at pos+1 — skip call if ENTRY_MASK[target] == 0
                if (tdfa.stateEntryMask()[target] != 0) {
                    mv.visitVarInsn(Opcodes.ILOAD, STATE);
                    mv.visitVarInsn(Opcodes.ILOAD, POS);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitInsn(Opcodes.IADD);
                    mv.visitVarInsn(Opcodes.ILOAD, LEN);
                    mv.visitVarInsn(Opcodes.ALOAD, IN);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "entryOkC",
                            "(IIILjava/lang/String;)Z", false);
                    Label entryOk = new Label();
                    mv.visitJumpInsn(Opcodes.IFNE, entryOk);
                    mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
                    mv.visitLabel(entryOk);
                }

                // pos++
                mv.visitIincInsn(POS, 1);
                mv.visitJumpInsn(Opcodes.GOTO, dfaLoop);

                if (!isLast) mv.visitLabel(nextRange);
            }
            mv.visitLabel(stateDead);
            mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
        }
        mv.visitLabel(def);
        mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
    }

    // ===== final ops (LOOKUPSWITCH) =====

    private static void emitFinalOps(MethodVisitor mv, Tdfa tdfa, String owner,
                                     int R, int LAS, int LAP, int POS,
                                     int[] op, int[] sfo, int[] sm) {
        int n = tdfa.stateCount();
        boolean[] isFallback = tdfa.stateIsFallback();
        int[] fallbackOff = tdfa.stateFallbackOpsOff();
        List<int[]> finals = new ArrayList<>();
        for (int s = 0; s < n; s++)
            if ((sm[s] & 1) != 0 && sfo[s] != 0) finals.add(new int[]{s, sfo[s]});
        if (finals.isEmpty()) return;
        int nf = finals.size();
        int[] keys = new int[nf];
        Label[] fl = new Label[nf];
        Label fDef = new Label(), fAfter = new Label();
        for (int k = 0; k < nf; k++) { keys[k] = finals.get(k)[0]; fl[k] = new Label(); }
        mv.visitVarInsn(Opcodes.ILOAD, LAS);
        mv.visitLookupSwitchInsn(fDef, keys, fl);
        for (int k = 0; k < nf; k++) {
            mv.visitLabel(fl[k]);
            int state = finals.get(k)[0];
            int phiOff = finals.get(k)[1];
            boolean hasPsi = isFallback != null && state < isFallback.length
                    && isFallback[state] && fallbackOff[state] != 0;
            if (hasPsi) {
                // if (POS > LAP) applyOps(ψ); else applyOps(φ).
                Label usePhi = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, POS);
                mv.visitVarInsn(Opcodes.ILOAD, LAP);
                mv.visitJumpInsn(Opcodes.IF_ICMPLE, usePhi);
                emitOpsInlineFinal(mv, op, fallbackOff[state], R, LAP);
                mv.visitJumpInsn(Opcodes.GOTO, fAfter);
                mv.visitLabel(usePhi);
                emitOpsInlineFinal(mv, op, phiOff, R, LAP);
            } else {
                emitOpsInlineFinal(mv, op, phiOff, R, LAP);
            }
            mv.visitJumpInsn(Opcodes.GOTO, fAfter);
        }
        mv.visitLabel(fDef);
        mv.visitLabel(fAfter);
    }

    // ===== position flags (inline) =====

    private static void emitPFInline(MethodVisitor mv, String owner,
                                     int IN, int POS, int LEN, int RESULT, int T1, int T2, int T3,
                                     boolean multiline, boolean unicodeWord) {
        // pf = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);

        // if (pos == 0) pf |= 1
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        Label l1 = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, l1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitLabel(l1);

        if (multiline) {
            // || (pos > 0 && input[pos-1] == '\n')
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            Label l1b = new Label();
            mv.visitJumpInsn(Opcodes.IFLE, l1b);
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitIntInsn(Opcodes.BIPUSH, '\n');
            Label l1c = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, l1c);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ILOAD, RESULT);
            mv.visitInsn(Opcodes.IOR);
            mv.visitVarInsn(Opcodes.ISTORE, RESULT);
            mv.visitLabel(l1c);
            mv.visitLabel(l1b);
        }

        // if (pos == len) pf |= 2
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        Label l2 = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l2);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitLabel(l2);

        if (multiline) {
            // || (pos < len && input[pos] == '\n')
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitVarInsn(Opcodes.ILOAD, LEN);
            Label l2b = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, l2b);
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitIntInsn(Opcodes.BIPUSH, '\n');
            Label l2c = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, l2c);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitVarInsn(Opcodes.ILOAD, RESULT);
            mv.visitInsn(Opcodes.IOR);
            mv.visitVarInsn(Opcodes.ISTORE, RESULT);
            mv.visitLabel(l2c);
            mv.visitLabel(l2b);
        }

        // if (pos == 0) pf |= 16  (ABS_BEGIN, \A — always, not multiline-gated)
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        Label lab = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, lab);
        mv.visitIntInsn(Opcodes.BIPUSH, 16);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitLabel(lab);

        // if (pos == len) pf |= 32  (ABS_END, \z — always)
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        Label lae = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, lae);
        mv.visitIntInsn(Opcodes.BIPUSH, 32);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitLabel(lae);

        // prevWord = pos > 0 && isWordBefore(pos, len, input)   [unicode: decodes surrogate pairs]
        Label prevFalse = new Label(), prevDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitJumpInsn(Opcodes.IFLE, prevFalse);
        if (unicodeWord) {
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitVarInsn(Opcodes.ILOAD, LEN);
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordBefore", "(IILjava/lang/String;)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitVarInsn(Opcodes.ISTORE, T1);
            emitIsWordBranch(mv, T1, prevFalse, false, owner);
            mv.visitInsn(Opcodes.ICONST_1);
        }
        mv.visitJumpInsn(Opcodes.GOTO, prevDone);
        mv.visitLabel(prevFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(prevDone);
        mv.visitVarInsn(Opcodes.ISTORE, T1); // prevWord

        // currWord = pos < len && isWordAt(pos, len, input)
        Label currFalse = new Label(), currDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, currFalse);
        if (unicodeWord) {
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitVarInsn(Opcodes.ILOAD, LEN);
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordAt", "(IILjava/lang/String;)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitVarInsn(Opcodes.ISTORE, T2);
            emitIsWordBranch(mv, T2, currFalse, false, owner);
            mv.visitInsn(Opcodes.ICONST_1);
        }
        mv.visitJumpInsn(Opcodes.GOTO, currDone);
        mv.visitLabel(currFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(currDone);
        // stack: currWord (0 or 1)

        // if (prevWord != currWord) pf |= 4; else pf |= 8
        mv.visitVarInsn(Opcodes.ILOAD, T1); // prevWord (below currWord on stack)
        Label noWB = new Label(), pfDone = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, noWB);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitJumpInsn(Opcodes.GOTO, pfDone);
        mv.visitLabel(noWB);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitVarInsn(Opcodes.ILOAD, RESULT);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, RESULT);
        mv.visitLabel(pfDone);
    }

    // ===== isWord (branch to notWord if false) =====

    private static void emitIsWordBranch(MethodVisitor mv, int lvChar, Label notWord,
                                         boolean unicodeWord, String owner) {
        if (unicodeWord) {
            mv.visitVarInsn(Opcodes.ILOAD, lvChar);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isUnicodeWordChar", "(I)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, notWord);
            return;
        }
        Label isWord = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitLdcInsn(95); // '_'
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isWord);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 48); // '0'
        Label cl = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, cl);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 57); // '9'
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, isWord);
        mv.visitLabel(cl);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 97); // 'a'
        Label cu = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, cu);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 122); // 'z'
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, isWord);
        mv.visitLabel(cu);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 65); // 'A'
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notWord);
        mv.visitVarInsn(Opcodes.ILOAD, lvChar);
        mv.visitIntInsn(Opcodes.BIPUSH, 90); // 'Z'
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notWord);
        mv.visitLabel(isWord);
        // fall through = is word
    }

    // ===== codepoint decode from surrogate pair (inline) =====

    private static void emitCodePointDecode(MethodVisitor mv, int IN, int C_LV, int POS, int LEN, int T1) {
        Label notSur = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0xD800);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notSur);
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0xDBFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notSur);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notSur);
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, T1);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitLdcInsn(0xDC00);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notSur);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitLdcInsn(0xDFFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notSur);
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0xD800);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, 10);
        mv.visitInsn(Opcodes.ISHL);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitLdcInsn(0xDC00);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.IADD);
        mv.visitLdcInsn(0x10000);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, C_LV);
        mv.visitLabel(notSur);
    }

    // ===== isUnicodeWordChar: binary-search WORD_RANGES for \b under (?u) =====

    private static void genIsUnicodeWordChar(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isUnicodeWordChar", "(I)Z", null, null);
        mv.visitCode();
        // locals: 0 = c, 1 = lo, 2 = hi
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "WORD_RANGES", "[I");
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IDIV);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        // while (lo <= hi)
        Label loop = new Label(), notFound = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notFound);
        // mid = (lo + hi) >>> 1
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitVarInsn(Opcodes.ISTORE, 3); // mid (local 3)
        // rLo = WORD_RANGES[mid * 2]
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "WORD_RANGES", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 4); // rLo (local 4)
        // rHi = WORD_RANGES[mid * 2 + 1]
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "WORD_RANGES", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 5); // rHi (local 5)
        // if (c < rLo) { hi = mid - 1; goto loop }
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label geLo = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, geLo);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(geLo);
        // if (c > rHi) { lo = mid + 1; goto loop }
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        Label inRange = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, inRange);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(inRange);
        // return true
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notFound);
        // return false
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== isWordBefore / isWordAt: word-char checks that decode surrogate pairs =====

    /**
     * Emits {@code private static boolean isWordBefore(int pos, int len, String input)}:
     * whether the character immediately before {@code pos} is a word character. A low
     * surrogate at {@code pos-1} paired with a high surrogate at {@code pos-2} is decoded
     * to the full supplementary codepoint before the {@code isUnicodeWordChar} search, so
     * {@code \b} adjacent to supplementary word chars (e.g. U+1D504) is computed correctly.
     */
    private static void genIsWordBefore(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isWordBefore", "(IILjava/lang/String;)Z", null, null);
        mv.visitCode();
        // locals: 0=pos, 1=len, 2=input, 3=c, 4=h
        Label notPos = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, notPos);
        // c = input[pos - 1]
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        // if (c >= 0xDC00 && c <= 0xDFFF && pos >= 2)
        Label notLow = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xDC00);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notLow);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xDFFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notLow);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 2);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notLow);
        // h = input[pos - 2]; if (h >= 0xD800 && h <= 0xDBFF)
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        Label notHigh = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xD800);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notHigh);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDBFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notHigh);
        // return isUnicodeWordChar(((h - 0xD800) << 10) + (c - 0xDC00) + 0x10000)
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xD800);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, 10);
        mv.visitInsn(Opcodes.ISHL);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xDC00);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.IADD);
        mv.visitLdcInsn(0x10000);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isUnicodeWordChar", "(I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        // return isUnicodeWordChar(c)
        mv.visitLabel(notHigh);
        mv.visitLabel(notLow);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isUnicodeWordChar", "(I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        // return false
        mv.visitLabel(notPos);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits {@code private static boolean isWordAt(int pos, int len, String input)}:
     * whether the character at {@code pos} is a word character; a high surrogate at
     * {@code pos} paired with a low surrogate at {@code pos+1} is decoded first.
     */
    private static void genIsWordAt(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isWordAt", "(IILjava/lang/String;)Z", null, null);
        mv.visitCode();
        // locals: 0=pos, 1=len, 2=input, 3=c, 4=l
        Label notPos = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notPos);
        // c = input[pos]
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        // if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < len)
        Label notHigh = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xD800);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notHigh);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xDBFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notHigh);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notHigh);
        // l = input[pos + 1]; if (l >= 0xDC00 && l <= 0xDFFF)
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        Label notLow = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDC00);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, notLow);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDFFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, notLow);
        // return isUnicodeWordChar(((c - 0xD800) << 10) + (l - 0xDC00) + 0x10000)
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitLdcInsn(0xD800);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitIntInsn(Opcodes.BIPUSH, 10);
        mv.visitInsn(Opcodes.ISHL);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDC00);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.IADD);
        mv.visitLdcInsn(0x10000);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isUnicodeWordChar", "(I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        // return isUnicodeWordChar(c)
        mv.visitLabel(notLow);
        mv.visitLabel(notHigh);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isUnicodeWordChar", "(I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        // return false
        mv.visitLabel(notPos);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== register ops (inline, transition) =====

    private static void emitOpsInline(MethodVisitor mv, int[] op, int off, int REGS, int POS) {
        int j = off;
        while (op[j] != Tdfa.OP_END) {
            int opc = op[j], dst = op[j + 1], src = op[j + 2];
            if (opc == Tdfa.OP_SET_POS) {
                mv.visitVarInsn(Opcodes.ALOAD, REGS); ic(mv, dst);
                mv.visitVarInsn(Opcodes.ILOAD, POS); mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_SET_NIL) {
                mv.visitVarInsn(Opcodes.ALOAD, REGS); ic(mv, dst);
                mv.visitInsn(Opcodes.ICONST_M1); mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_COPY) {
                mv.visitVarInsn(Opcodes.ALOAD, REGS); ic(mv, dst);
                mv.visitVarInsn(Opcodes.ALOAD, REGS); ic(mv, src);
                mv.visitInsn(Opcodes.IALOAD); mv.visitInsn(Opcodes.IASTORE);
            }
            j += 3;
        }
    }

    // ===== register ops (inline, final — uses lastAcceptPos) =====

    private static void emitOpsInlineFinal(MethodVisitor mv, int[] op, int off, int R, int LAP) {
        int j = off;
        while (op[j] != Tdfa.OP_END) {
            int opc = op[j], dst = op[j + 1], src = op[j + 2];
            if (opc == Tdfa.OP_SET_POS) {
                mv.visitVarInsn(Opcodes.ALOAD, R); ic(mv, dst);
                mv.visitVarInsn(Opcodes.ILOAD, LAP); mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_SET_NIL) {
                mv.visitVarInsn(Opcodes.ALOAD, R); ic(mv, dst);
                mv.visitInsn(Opcodes.ICONST_M1); mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_COPY) {
                mv.visitVarInsn(Opcodes.ALOAD, R); ic(mv, dst);
                mv.visitVarInsn(Opcodes.ALOAD, R); ic(mv, src);
                mv.visitInsn(Opcodes.IALOAD); mv.visitInsn(Opcodes.IASTORE);
            }
            j += 3;
        }
    }

    // ===== small helpers =====

    private static void newIntArr(MethodVisitor mv, int size) { ic(mv, size); mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT); }

    private static void ic(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
        else mv.visitLdcInsn(v);
    }

    // ===== DELEGATE-mode emitters (forward every hot method to the embedded runner) =====

    /**
     * Emit a minimal {@code <init>(Tdfa)} that stores a fresh {@link TdfaRunner}
     * in the {@code runner} instance field. No static fields, no caches, no
     * {@code <clinit>} — the generated class is a thin wrapper for DFAs too
     * large to inline (e.g. dictionary alternations with 20 K+ states).
     */
    private static void genDelegateInit(ClassWriter cw, String owner) {
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "runner", RUNNER_D, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + TDFA_D + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, RUNNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RUNNER, "<init>", "(" + TDFA_D + ")V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "runner", RUNNER_D);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genDelegateMatches(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "matches", "(" + CS_D + ")Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genDelegateFind(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "find", "(" + CS_D + ")Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genDelegateMatch(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "match", "(" + CS_D + "I)L" + RESULT + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "match", "(" + CS_D + "I)L" + RESULT + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }
    /** RegexEngine metadata (groupCount/namedGroups/programSize), delegating to
     *  the final {@code runner} field — monomorphic. Needed by both dispatch
     *  modes since the generated class implements RegexEngine directly. */
    private static void genMetadataMethods(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "groupCount", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "groupCount", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "namedGroups", "()Ljava/util/Map;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "namedGroups", "()Ljava/util/Map;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "programSize", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "programSize", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== fast-path eligibility =====

    /**
     * Pick the ASM dispatch mode. Two modes:
     *
     * <ol>
     *   <li><b>INLINED</b> — fastPath-eligible DFAs only (no masks, disjoint
     *       ranges): per-state inlined range checks + inlined register ops in
     *       the emitted extract leaf, flat-table anchored matches loop, and an
     *       emitted strategy ladder mirroring {@code runStringExtractFast}.
     *       Fits when the size estimate is below {@link #INLINE_BUDGET_BYTES}.</li>
     *   <li><b>DELEGATE</b> — every hot method forwards to the embedded
     *       {@link TdfaRunner}. Bytecode trivially small; runtime
     *       VM-equivalent (it IS the VM ladder). Used for big DFAs (the 21
     *       K-state dictionary alternation, 268-state/352-tag lexer DFAs),
     *       non-fastPath DFAs (masks: word boundaries, anchors — the runner's
     *       generic walk is already optimal there), and literal needles
     *       (indexOf short-circuit).</li>
     * </ol>
     */
    private static DispatchMode pickMode(Tdfa tdfa) {
        // INLINED only pays for fastPath DFAs (the emitted leaf + ladder are
        // fastPath-shaped); everything else delegates to the runner ladder.
        if (!computeFastPath(tdfa)) return DispatchMode.DELEGATE;
        if (estimateInlinedBytes(tdfa) <= INLINE_BUDGET_BYTES) return DispatchMode.INLINED;
        return DispatchMode.DELEGATE;
    }

    /** Bytecode cost of the inlined final-ops block alone (~7 B/op). */
    private static int estimateFinalOpsBytes(Tdfa tdfa) {
        int[] op = tdfa.ops(), sfo = tdfa.stateFinalOpsOff();
        int total = 0;
        for (int s = 0; s < tdfa.stateCount(); s++) {
            if (sfo[s] == 0) continue;
            int j = sfo[s];
            while (j < op.length && op[j] != Tdfa.OP_END) { total += 7; j += 3; }
        }
        return total;
    }

    /** Soft budget for {@code runExtract} bytecode under INLINED mode. 65 KB
     *  is the JVM hard cap; the per-range cost estimate (~25 B/range) is a
     *  lower bound — Unicode-word-boundary patterns like {@code \b\w+\b} add
     *  ~15 B for codepoint-advance checks on surrogate-crossing ranges, and
     *  per-state mask/entry checks add fixed overhead. Budget of 30 KB
     *  leaves comfortable headroom for the under-estimate. */
    private static final int INLINE_BUDGET_BYTES = 30_000;

    /**
     * Rough byte-code cost estimate for INLINED {@code runExtract}. Counts
     * range-check code (~25 B/range), per-op register code (~7 B/op for
     * SET/COPY in transition ops), and final-ops code (~7 B/op for SET/COPY
     * in each accept state's final-ops block). The final-ops term dominates
     * for high-tag DFAs like lexer-veryl (268 states, 101 accept states,
     * ~352 final-ops per accept → 250 KB of bytecode, which blows the 65 KB
     * method limit if inlined).
     */
    private static int estimateInlinedBytes(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta(), sb = tdfa.stateBase(), rg = tdfa.ranges(), op = tdfa.ops();
        int[] sfo = tdfa.stateFinalOpsOff();
        int total = 0;
        for (int s = 0; s < tdfa.stateCount(); s++) {
            int meta = sm[s];
            int base = sb[s], cnt = (meta >>> 1) & 0xFFFF;
            total += 60;  // per-state fixed overhead (label, dead-state, mask check, etc.)
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o + 2] < 0) continue;
                total += 25;  // range check + transition setup
                int opsOff = rg[o + 3];
                if (opsOff != 0) {
                    int j = opsOff;
                    while (j < op.length && op[j] != Tdfa.OP_END) { total += 7; j += 3; }
                }
            }
            // Final-ops per accept state (emitFinalOps inlines each)
            if (sfo[s] != 0) {
                int j = sfo[s];
                while (j < op.length && op[j] != Tdfa.OP_END) { total += 7; j += 3; }
            }
        }
        return total;
    }

    /** Count total live (non-dead-target) ranges across all states. */
    private static int estimateLiveRangeCount(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta(), sb = tdfa.stateBase(), rg = tdfa.ranges();
        int total = 0;
        for (int s = 0; s < tdfa.stateCount(); s++) {
            int meta = sm[s];
            int base = sb[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o + 2] >= 0) total++;
            }
        }
        return total;
    }

    private static boolean computeFastPath(Tdfa tdfa) {
        if (tdfa.multiline()) return false;
        if (!checkRangesDisjoint(tdfa)) return false;
        for (int mask : tdfa.stateEntryMask()) if (mask != 0) return false;
        for (int mask : tdfa.stateAcceptMask()) if (mask != 0) return false;
        for (int i = 4; i < tdfa.ranges().length; i += 5) if (tdfa.ranges()[i] != 0) return false;
        return true;
    }

    private static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta(), sb = tdfa.stateBase(), rg = tdfa.ranges();
        long[] sortBuf = null;
        for (int s = 0; s < tdfa.stateCount(); s++) {
            int meta = sm[s];
            int base = sb[s], cnt = (meta >>> 1) & 0xFFFF;
            if (cnt < 2) continue;
            // Fast path (sorted by lo, the materialization default): O(cnt) scan
            // with running max-hi. Reordered states (sortByMaskSpecificity) take
            // the pack-and-sort path — O(cnt log cnt) vs the old O(cnt²) pairwise.
            boolean sortedByLo = true;
            for (int i = 1; i < cnt; i++) {
                if (rg[(base + i) * 5] < rg[(base + i - 1) * 5]) { sortedByLo = false; break; }
            }
            if (!sortedByLo) {
                if (sortBuf == null || sortBuf.length < cnt) sortBuf = new long[Math.max(cnt, 64)];
                for (int i = 0; i < cnt; i++) {
                    int o = (base + i) * 5;
                    sortBuf[i] = ((long) rg[o] << 32) | (rg[o + 1] & 0xFFFFFFFFL);
                }
                java.util.Arrays.sort(sortBuf, 0, cnt);
                int maxHi = (int) sortBuf[0];
                for (int i = 1; i < cnt; i++) {
                    if ((int) (sortBuf[i] >>> 32) <= maxHi) return false;
                    int hi = (int) sortBuf[i];
                    if (hi > maxHi) maxHi = hi;
                }
                continue;
            }
            int maxHi = rg[base * 5 + 1];
            for (int i = 1; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o] <= maxHi) return false;
                if (rg[o + 1] > maxHi) maxHi = rg[o + 1];
            }
        }
        return true;
    }
}
