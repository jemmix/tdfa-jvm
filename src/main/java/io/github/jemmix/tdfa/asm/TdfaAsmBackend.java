package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class TdfaAsmBackend {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final String ENGINE = "io/github/jemmix/tdfa/Regex$Engine";
    private static final String HOLDER = "io/github/jemmix/tdfa/tdfa/TdfaRunner$MatchHolder";
    private static final String RESULT = "io/github/jemmix/tdfa/vm/MatchResult";
    private static final String CS = "java/lang/CharSequence";
    private static final String STR = "java/lang/String";
    private static final String CS_D = "Ljava/lang/CharSequence;";
    private static final String ARRAYS = "java/util/Arrays";
    private static final String RUNNER = "io/github/jemmix/tdfa/tdfa/TdfaRunner";
    private static final String RUNNER_D = "L" + RUNNER + ";";
    private static final String TDFA = "io/github/jemmix/tdfa/tdfa/Tdfa";
    private static final String TDFA_D = "L" + TDFA + ";";

    public static Regex.Engine compile(Tdfa tdfa) {
        byte[] bc;
        try {
            long id = COUNTER.incrementAndGet();
            String cn = "io.github.jemmix.tdfa.gen.Gen" + id;
            String owner = cn.replace('.', '/');
            bc = generate(tdfa, owner);
            if (Boolean.getBoolean("tdfa.asm.dump")) {
                String dp = "/tmp/" + owner.replace('/', '.') + ".class";
                try { java.nio.file.Files.write(java.nio.file.Paths.get(dp), bc); } catch (Exception ignored) {}
            }
            final byte[] bytes = bc;
            final String className = cn;
            ClassLoader cl = new ClassLoader(TdfaAsmBackend.class.getClassLoader()) {
                @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                    if (n.equals(className)) return defineClass(className, bytes, 0, bytes.length);
                    return super.findClass(n);
                }
            };
            return (Regex.Engine) Class.forName(cn, true, cl)
                    .getDeclaredConstructor(Tdfa.class).newInstance(tdfa);
        } catch (Exception e) {
            throw new IllegalStateException("ASM backend failed", e);
        }
    }

    /**
     * Maximum total live range count for inlined ASM dispatch. Above this,
     * {@code runExtract}'s inlined range checks would exceed the JVM 65 KB
     * method-size limit (each inlined range is ~25 bytes of bytecode). The
     * ASM engine falls back to TABLE_SCAN dispatch (one compact runtime
     * helper call per state) instead.
     */
    private static final int MAX_INLINED_RANGES = 600;

    /**
     * Maximum DFA state count for TABLE_SCAN dispatch. Each table-scan state
     * emits ~100 bytes of bytecode; above ~600 states the per-state snippets
     * alone blow the 65 KB method limit on {@code runExtract}. Above this
     * threshold the engine uses DELEGATE mode (every hot method just forwards
     * to the embedded {@link TdfaRunner}); the class is a thin wrapper, with
     * no inlined dispatch at all.
     */
    private static final int MAX_TABLESCAN_STATES = 600;

    /** Dispatch mode picked at class-emit time, see {@link #pickMode}. */
    enum DispatchMode { INLINED, TABLE_SCAN, DELEGATE }

    private static byte[] generate(Tdfa tdfa, String owner) {
        DispatchMode mode = pickMode(tdfa);
        boolean dispatchTooLarge = mode != DispatchMode.INLINED;
        boolean delegate = mode == DispatchMode.DELEGATE;
        // Disable the fast path (ASCII_TARGET table) outside INLINED mode.
        // In DELEGATE mode matches()/runExtract() forward to the runner, so
        // the table would never be consulted anyway.
        boolean fastPath = mode == DispatchMode.INLINED && computeFastPath(tdfa);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", new String[]{ENGINE});
        if (delegate) {
            // Minimal class: just an init storing the runner, and forwarding stubs
            // for the Regex.Engine interface. No static tables, no <clinit>.
            genDelegateInit(cw, owner);
            genDelegateMatches(cw, owner);
            genDelegateFind(cw, owner);
            genDelegateMatch(cw, owner);
        } else {
            genClinit(cw, tdfa, owner, fastPath);
            genInit(cw, owner, tdfa, fastPath);
            genMatches(cw, owner, fastPath);
            genFind(cw, owner);
            genMatch(cw, tdfa, owner, dispatchTooLarge);
            genToCharArray(cw);
            genRunExtract(cw, tdfa, owner, dispatchTooLarge);
            if (dispatchTooLarge) {
                genScanRanges(cw, owner);
                genApplyOpsRuntime(cw, owner);
            }
            genEntryOkC(cw, owner);
            genPositionFlagsC(cw, owner, tdfa.multiline, tdfa.unicodeWordBoundary);
            if (tdfa.unicodeWordBoundary) {
                genIsUnicodeWordChar(cw, owner);
                genIsWordBefore(cw, owner);
                genIsWordAt(cw, owner);
            }
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
        boolean hasFixed = tdfa.fixedBase != null;
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
        // Instance field holding a TdfaRunner for the multi-state no-match
        // pre-check (avoids the O(n²) outer-loop scan in runBoolean/runExtract
        // on non-matching haystacks). The ASM inlined transitions are still used
        // for the actual extraction; the runner is only consulted for the pre-check.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "runner", RUNNER_D, null, null).visitEnd();
        // Per-instance CharSequence → char[] cache for match(). Without this,
        // each find() call re-copies the entire haystack via toCharArray,
        // producing G1 humongous allocations and O(n²) wall time on long
        // inputs (e.g. [a-zA-Z]+ing on 16 MB leipzig). Keyed by reference
        // identity; safe because Matcher (and therefore the Pattern's engine)
        // is single-thread per owner. See docs/REBAR-SPEEDUP-PLAN.md §Tier-2 #3.
        cw.visitField(Opcodes.ACC_PRIVATE, "cachedInput", CS_D, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "cachedChars", "[C", null, null).visitEnd();
        // Static fields for the table-scan dispatch fallback (wide Unicode classes
        // like \p{L}{N} that would blow the 65 KB method limit if inlined). Set
        // from <init> because the arrays come from the Tdfa param and are too large
        // to populate via per-element IASTORE in <clinit>. One instance per class.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "RANGES_TABLE", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "OPS_TABLE", "[I", null, null).visitEnd();
        if (tdfa.unicodeWordBoundary) {
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
        // RANGES_TABLE = tdfa.ranges
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "ranges", "[I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "RANGES_TABLE", "[I");
        // OPS_TABLE = tdfa.ops
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "ops", "[I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "OPS_TABLE", "[I");
        if (tdfa.unicodeWordBoundary) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "wordRanges", "[I");
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "WORD_RANGES", "[I");
        }
        // ENTRY_MASK = tdfa.stateEntryMask (reference copy — no per-element bytecode)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stateEntryMask", "[I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ENTRY_MASK", "[I");
        // ACCEPT_MASK = tdfa.stateAcceptMask
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stateAcceptMask", "[I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ACCEPT_MASK", "[I");
        // STOP_MASK = tdfa.stopOnAcceptMask
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stopOnAcceptMask", "[I");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STOP_MASK", "[I");
        // IS_ACCEPT = new int[n]; for each state s, IS_ACCEPT[s] = (stateMeta[s] & 1)
        // Computed in a fixed-size runtime loop — bytecode is ~30 bytes regardless
        // of state count (matters for dictionary-scale DFAs with 20 K+ states).
        int n = tdfa.stateCount;
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
        mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stateMeta", "[I");
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
        if (tdfa.fixedBase != null) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "fixedBase", "[I");
            mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "FIXED_BASE", "[I");
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "fixedOffset", "[I");
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
            int tableSize = tdfa.stateCount * 128;
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
            mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stateMeta", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IUSHR);
            mv.visitIntInsn(Opcodes.SIPUSH, 0xFFFF);
            mv.visitInsn(Opcodes.IAND);
            mv.visitVarInsn(Opcodes.ISTORE, 6);
            // base = stateBase[s]
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, TDFA, "stateBase", "[I");
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
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
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
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
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
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
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

    private static void genMatches(ClassWriter cw, String owner, boolean fastPath) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(" + CS_D + ")Z", null, null);
        mv.visitCode();

        if (fastPath) {
            // locals: 0=this, 1=input, 2=s(String), 3=len, 4=state, 5=pos
            Label slowPath = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, STR);
            mv.visitJumpInsn(Opcodes.IFEQ, slowPath);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, STR);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "length", "()I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 4); // state = 0 (startState)
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 5); // pos = 0

            Label loop = new Label(), done = new Label(), deadFail = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "charAt", "(I)C", false);
            mv.visitVarInsn(Opcodes.ISTORE, 6); // c (use local 6 temporarily, no conflict)
            mv.visitVarInsn(Opcodes.ILOAD, 6);
            mv.visitIntInsn(Opcodes.SIPUSH, 128);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, slowPath); // non-ASCII → fallback
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ASCII_TARGET", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            ic(mv, 128);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 6);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, 4); // state = ASCII_TARGET[state * 128 + c]
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitJumpInsn(Opcodes.IFLT, deadFail);
            mv.visitIincInsn(5, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loop);

            mv.visitLabel(deadFail);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);

            mv.visitLabel(done);
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "IS_ACCEPT", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitInsn(Opcodes.IRETURN);

            mv.visitLabel(slowPath);
            // Fall through to generic path
        }

        // Generic path: delegate to the runner (anchored match — single DFA
        // walk from position 0, no O(n²) concern; the multi-state fix is
        // only needed for unanchored find/extract).
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

    private static void genMatch(ClassWriter cw, Tdfa tdfa, String owner, boolean dispatchTooLarge) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "match", "(" + CS_D + "I)L" + RESULT + ";", null, null);
        mv.visitCode();
        // Leftmost-start via the runner's origin-tracking multi-state simulation
        // (fast-path DFAs only; mask-bearing DFAs degrade to the boolean
        // over-approximation, same as the old anyMatch precheck). O(n × |states|)
        // single pass. No match → null without entering runExtract's outer-loop
        // scan; match → runExtract starts AT the leftmost match position instead
        // of rescanning every failed start (the O(n²) dense-match shape).
        // locals: 0=this, 1=input, 2=from, 3=chars, 4=holder, 5=leftmost
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "runner", RUNNER_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RUNNER, "leftmostStart", "(" + CS_D + "I)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        Label extract = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitJumpInsn(Opcodes.IFGE, extract);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(extract);
        // cached chars lookup: if (input == cachedInput) use cachedChars,
        // else convert via toCharArray and update cache. Avoids re-copying
        // the entire haystack on every find() call (G1 humongous allocations).
        // locals: 0=this, 1=input, 2=from, 3=chars
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "cachedInput", CS_D);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label cacheMiss = new Label();
        // ACMPNE on references: jump if cachedInput != input
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, cacheMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "cachedChars", "[C");
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        Label runExtract = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, runExtract);
        mv.visitLabel(cacheMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toCharArray", "(" + CS_D + ")[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        // this.cachedInput = input; this.cachedChars = chars;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "cachedInput", CS_D);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "cachedChars", "[C");
        mv.visitLabel(runExtract);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "runExtract", "([CII)L" + HOLDER + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        Label ret = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, ret);
        if (tdfa.fixedBase != null) {
            // BT22 §6.4 fixed-tag reconstruction: rewrite fixed-tag slots in the
            // holder's regs from their base tag values, before constructing MatchResult.
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "regs", "[I");
            ic(mv, tdfa.finalRegBase);
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "FIXED_BASE", "[I");
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "FIXED_OFFSET", "[I");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RESULT, "reconstructFixed", "([II[I[I)V", false);
        }
        mv.visitTypeInsn(Opcodes.NEW, RESULT);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "regs", "[I");
        ic(mv, tdfa.finalRegBase);
        ic(mv, tdfa.groupCount);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "matchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "matchEnd", "I");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT, "<init>", "([IIIII)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(ret);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== toCharArray =====

    private static void genToCharArray(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "toCharArray", "(" + CS_D + ")[C", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, STR);
        Label notStr = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, notStr);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, STR);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STR, "toCharArray", "()[C", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notStr);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CS, "length", "()I", true);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CS, "charAt", "(I)C", true);
        mv.visitInsn(Opcodes.CASTORE);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== runExtract — DFA walk with register tracking =====

    private static void genRunExtract(ClassWriter cw, Tdfa tdfa, String owner, boolean tableScan) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "runExtract", "([CII)L" + HOLDER + ";", null, null);
        mv.visitCode();
        emitRunCore(mv, tdfa, owner, true, tableScan);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== entryOkC — entry mask check using char[] input (helper for DFA dispatch) =====

    private static void genEntryOkC(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "entryOkC", "(III[C)Z", null, null);
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
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlagsC", "(II[C)I", false);
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

    // ===== positionFlagsC — position flags for char[] input (helper) =====

    private static void genPositionFlagsC(ClassWriter cw, String owner, boolean multiline, boolean unicodeWord) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "positionFlagsC", "(II[C)I", null, null);
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
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordBefore", "(II[C)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordAt", "(II[C)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.CALOAD);
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

    private static void emitRunCore(MethodVisitor mv, Tdfa tdfa, String owner, boolean extract, boolean tableScan) {
        final boolean perl = tdfa.perlMode;
        final boolean startReqBT = !tdfa.multiline && tdfa.startRequiresBeginText();
        final int nStates = tdfa.stateCount;
        final int[] sm = tdfa.stateMeta, rg = tdfa.ranges, op = tdfa.ops, sfo = tdfa.stateFinalOpsOff;

        // Compile-time check: is positionFlags ever needed?
        // PF is needed if any accepting state has ACCEPT_MASK != 0,
        // or any live transition range has reqMask != 0,
        // or PERL mode has accepting states (STOP_MASK indexed by PF).
        boolean pfNeeded = false;
        for (int s = 0; s < nStates && !pfNeeded; s++) {
            if ((sm[s] & 1) != 0) {
                if (perl || tdfa.stateAcceptMask[s] != 0) pfNeeded = true;
            }
        }
        if (!pfNeeded) {
            for (int i = 0; i < rg.length; i += 5) {
                if (rg[i + 2] >= 0 && rg[i + 4] != 0) { pfNeeded = true; break; }
            }
        }

        // Locals (extract adds: regs, lastAcceptState, r)
        // 0=input, 1=from, 2=len, 3=anchored(runBoolean only)
        final int IN=0, FROM=1, LEN=2, ANC=3;
        final int MS=4, ST=5, STATE=6, POS=7, HA=8, LAP=9;
        final int PF, C_LV, T1, T2, PF2, T3, T4, R, REGS, LAS;
        if (extract) {
            REGS = 10; LAS = 11; PF = 12; C_LV = 13;
            T1 = 14; T2 = 15; PF2 = 16; T3 = 17; T4 = 18; R = 19;
        } else {
            REGS = -1; LAS = -1;
            PF = 10; C_LV = 11; T1 = 12; T2 = 13; PF2 = 14; T3 = 15; T4 = 16; R = -1;
        }

        // maxStart = anchored ? from : (startReqBT ? 0 : len)
        // For extract (no anchored param): maxStart = startReqBT ? 0 : len
        if (extract) {
            if (startReqBT) mv.visitInsn(Opcodes.ICONST_0);
            else mv.visitVarInsn(Opcodes.ILOAD, LEN);
            mv.visitVarInsn(Opcodes.ISTORE, MS);
        } else {
            mv.visitVarInsn(Opcodes.ILOAD, ANC);
            Label aPath = new Label();
            mv.visitJumpInsn(Opcodes.IFNE, aPath);
            if (startReqBT) mv.visitInsn(Opcodes.ICONST_0);
            else mv.visitVarInsn(Opcodes.ILOAD, LEN);
            mv.visitVarInsn(Opcodes.ISTORE, MS);
            Label msDone = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, msDone);
            mv.visitLabel(aPath);
            mv.visitVarInsn(Opcodes.ILOAD, FROM);
            mv.visitVarInsn(Opcodes.ISTORE, MS);
            mv.visitLabel(msDone);
        }

        // start = from
        mv.visitVarInsn(Opcodes.ILOAD, FROM);
        mv.visitVarInsn(Opcodes.ISTORE, ST);

        // Pre-initialize locals to satisfy verifier at search-loop merge point
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, STATE);
        mv.visitVarInsn(Opcodes.ILOAD, FROM); mv.visitVarInsn(Opcodes.ISTORE, POS);
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAP);
        if (extract) {
            mv.visitInsn(Opcodes.ACONST_NULL); mv.visitVarInsn(Opcodes.ASTORE, REGS);
            mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAS);
        }

        // ===== SEARCH LOOP =====
        Label searchLoop = new Label(), searchEnd = new Label(), searchNext = new Label();
        mv.visitLabel(searchLoop);
        mv.visitVarInsn(Opcodes.ILOAD, ST);
        mv.visitVarInsn(Opcodes.ILOAD, MS);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, searchEnd);

        // Per-start: allocate regs (extract only)
        if (extract) {
            if (tdfa.registerCount == 0) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitVarInsn(Opcodes.ASTORE, REGS);
            } else {
                ic(mv, tdfa.registerCount);
                mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
                mv.visitVarInsn(Opcodes.ASTORE, REGS);
                mv.visitVarInsn(Opcodes.ALOAD, REGS);
                mv.visitInsn(Opcodes.ICONST_M1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);
            }
        }

        // Entry check for start state at ST — skip if ENTRY_MASK[0] == 0
        if (tdfa.stateEntryMask[0] != 0) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, T1);
            mv.visitVarInsn(Opcodes.ILOAD, T1);
            Label initEntryOk = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, initEntryOk);
            emitPFInline(mv, owner, IN, ST, LEN, PF2, T2, T3, T4, tdfa.multiline, tdfa.unicodeWordBoundary);
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
            emitPFInline(mv, owner, IN, POS, LEN, PF, T1, T2, T3, tdfa.multiline, tdfa.unicodeWordBoundary);
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
        if (extract) { mv.visitVarInsn(Opcodes.ILOAD, STATE); mv.visitVarInsn(Opcodes.ISTORE, LAS); }
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

        // c = input[pos] (CALOAD), then decode codepoint from surrogate pair
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, C_LV);
        emitCodePointDecode(mv, IN, C_LV, POS, LEN, T1);

        // ===== DFA DISPATCH (TABLESWITCH) =====
        emitDfaDispatch(mv, tdfa, owner, extract, tableScan,
                IN, STATE, POS, LEN, PF, C_LV, REGS, T1, T2, PF2, T3, T4,
                dfaLoop, dfaEnd, op);

        mv.visitLabel(dfaEnd);

        // ===== POST-DFA: check accept =====
        if (extract) {
            // if (haveAccept) { ... return MatchHolder }
            Label noResult = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, HA);
            mv.visitJumpInsn(Opcodes.IFEQ, noResult);

            // anchored check (only in runBoolean, not here)
            // For extract: just build result

            // r = regs == null ? new int[0] : regs.clone()
            if (tdfa.registerCount == 0) {
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
        } else {
            // runBoolean post-DFA
            mv.visitVarInsn(Opcodes.ILOAD, ANC);
            Label unanchored = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, unanchored);
            // anchored: return haveAccept && lastAcceptPos == len
            mv.visitVarInsn(Opcodes.ILOAD, HA);
            Label retFalse1 = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, retFalse1);
            mv.visitVarInsn(Opcodes.ILOAD, LAP);
            mv.visitVarInsn(Opcodes.ILOAD, LEN);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, retFalse1);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(retFalse1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(unanchored);
            // unanchored: if haveAccept → return true; else fall through to searchNext
            mv.visitVarInsn(Opcodes.ILOAD, HA);
            mv.visitJumpInsn(Opcodes.IFEQ, searchNext);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
        }

        // searchNext: start++; goto searchLoop
        mv.visitLabel(searchNext);
        mv.visitIincInsn(ST, 1);
        mv.visitJumpInsn(Opcodes.GOTO, searchLoop);

        // searchEnd: return false / null
        mv.visitLabel(searchEnd);
        if (extract) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        }
    }

    // ===== DFA TABLESWITCH + range checks =====

    private static void emitDfaDispatch(MethodVisitor mv, Tdfa tdfa, String owner,
                                        boolean extract, boolean tableScan,
                                        int IN, int STATE, int POS, int LEN, int PF, int C_LV,
                                        int REGS, int T1, int T2, int PF2, int T3, int T4,
                                        Label dfaLoop, Label dfaEnd, int[] op) {
        int nStates = tdfa.stateCount;
        int[] sm = tdfa.stateMeta, sb = tdfa.stateBase, rg = tdfa.ranges;

        Label[] sl = new Label[nStates];
        Label def = new Label();
        for (int s = 0; s < nStates; s++) sl[s] = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitTableSwitchInsn(0, nStates - 1, def, sl);

        for (int s = 0; s < nStates; s++) {
            mv.visitLabel(sl[s]);
            int meta = sm[s];
            int base = sb[s], cnt = (meta >>> 1) & 0xFFFF;

            if (tableScan) {
                emitTableScanState(mv, owner, extract, base, cnt,
                        IN, STATE, POS, LEN, PF, C_LV, REGS, T1, T2, T3, T4, dfaLoop, dfaEnd);
                continue;
            }

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
                if (extract && opsOff != 0 && REGS >= 0)
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
                if (tdfa.stateEntryMask[target] != 0) {
                    mv.visitVarInsn(Opcodes.ILOAD, STATE);
                    mv.visitVarInsn(Opcodes.ILOAD, POS);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitInsn(Opcodes.IADD);
                    mv.visitVarInsn(Opcodes.ILOAD, LEN);
                    mv.visitVarInsn(Opcodes.ALOAD, IN);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "entryOkC",
                            "(III[C)Z", false);
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

    // ===== table-scan dispatch (one state) =====

    /**
     * Emit the DFA dispatch for a single state using a runtime binary search
     * over the {@code RANGES_TABLE} static array, instead of inlining range
     * checks. Compact (fixed-size per state) — used for wide character classes
     * like {@code \p{L}} (1369 ranges/state) that would exceed the 65 KB method
     * limit if inlined.
     */
    private static void emitTableScanState(MethodVisitor mv, String owner,
                                           boolean extract, int base, int cnt,
                                           int IN, int STATE, int POS, int LEN, int PF, int C_LV,
                                           int REGS, int T1, int T2, int T3, int T4,
                                           Label dfaLoop, Label dfaEnd) {
        Label stateDead = new Label();

        // ri = scanRanges(RANGES_TABLE, base, cnt, c)
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
        ic(mv, base);
        ic(mv, cnt);
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "scanRanges", "([IIII)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, T1);

        // if (ri < 0) goto dead
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitJumpInsn(Opcodes.IFLT, stateDead);

        // target = RANGES_TABLE[ri + 2]
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T2);

        // Dead range (target < 0, inserted by fillGaps) — inline path filters
        // these out; table-scan must do the same.
        mv.visitVarInsn(Opcodes.ILOAD, T2);
        mv.visitJumpInsn(Opcodes.IFLT, stateDead);

        // reqMask = RANGES_TABLE[ri + 4]
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T4);

        // if (reqMask != 0 && (PF & reqMask) != reqMask) goto dead
        Label maskOk = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, T4);
        mv.visitJumpInsn(Opcodes.IFEQ, maskOk);
        mv.visitVarInsn(Opcodes.ILOAD, PF);
        mv.visitVarInsn(Opcodes.ILOAD, T4);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, T4);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, stateDead);
        mv.visitLabel(maskOk);

        // Apply ops (extract only): opsOff = RANGES_TABLE[ri + 3]
        if (extract && REGS >= 0) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, T1);
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            mv.visitVarInsn(Opcodes.ISTORE, T3);
            Label opsDone = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, T3);
            mv.visitJumpInsn(Opcodes.IFEQ, opsDone);
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "OPS_TABLE", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, T3);
            mv.visitVarInsn(Opcodes.ALOAD, REGS);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "applyOpsRuntime", "([II[II)V", false);
            mv.visitLabel(opsDone);
        }

        // state = target
        mv.visitVarInsn(Opcodes.ILOAD, T2);
        mv.visitVarInsn(Opcodes.ISTORE, STATE);

        // Codepoint advance: hi = RANGES_TABLE[ri + 1]; if (hi > 0x10000 && c >= 0x10000) pos++
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RANGES_TABLE", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitLdcInsn(0x10000);
        Label noAdv = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, noAdv);
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0x10000);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, noAdv);
        mv.visitIincInsn(POS, 1);
        mv.visitLabel(noAdv);

        // Entry check for target (runtime: ENTRY_MASK[STATE])
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitInsn(Opcodes.IALOAD);
        Label entryOk = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, entryOk);
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "entryOkC", "(III[C)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, entryOk);
        mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
        mv.visitLabel(entryOk);

        mv.visitIincInsn(POS, 1);
        mv.visitJumpInsn(Opcodes.GOTO, dfaLoop);

        mv.visitLabel(stateDead);
        mv.visitJumpInsn(Opcodes.GOTO, dfaEnd);
    }

    // ===== shared binary-search helper (table-scan dispatch) =====

    /**
     * Generate {@code static int scanRanges(int[] ranges, int base, int count, int c)}.
     * Linear scan {@code ranges} for the entry matching {@code c}. Returns the
     * flat offset into {@code ranges} (i.e. {@code (base + i) * 5}) of the first
     * matching entry, or {@code -1} if no range contains {@code c}.
     *
     * <p>Linear scan (not binary search) because the DFA builder's
     * {@code sortByMaskSpecificity} may reorder ranges by mask priority, breaking
     * the sort-by-lo invariant that binary search requires. For wide classes
     * like {@code \p{L}} (~1369 ranges), this is still fast on typical inputs.
     */
    private static void genScanRanges(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "scanRanges", "([IIII)I", null, null);
        // 0=ranges, 1=base, 2=count, 3=c; local 4=i, 5=o
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        Label loop = new Label(), nf = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, nf);
        // o = (base + i) * 5
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IADD);
        mv.visitIntInsn(Opcodes.BIPUSH, 5);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        // if (c >= ranges[o]) — c < ranges[o] → skip
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IALOAD);
        Label skip = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, skip);
        // if (c <= ranges[o+1]) — c > ranges[o+1] → skip
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, skip);
        // return o
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(skip);
        // i++
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(nf);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== shared runtime ops applier (table-scan dispatch) =====

    /**
     * Generate {@code static void applyOpsRuntime(int[] ops, int opsOff, int[] regs, int pos)}.
     * Interprets the ops array at runtime — same semantics as {@code TdfaRunner.applyOps}
     * but emitted as ASM so the table-scan dispatch stays in generated code.
     */
    private static void genApplyOpsRuntime(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "applyOpsRuntime", "([II[II)V", null, null);
        // 0=ops, 1=opsOff, 2=regs, 3=pos; local 4=j, 5=op, 6=dst
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        Label loop = new Label(), done = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label checkCopy = new Label(), setNil = new Label(), next = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkCopy);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitJumpInsn(Opcodes.GOTO, next);
        mv.visitLabel(checkCopy);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, setNil);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitJumpInsn(Opcodes.GOTO, next);
        mv.visitLabel(setNil);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitLabel(next);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== final ops (LOOKUPSWITCH) =====

    private static void emitFinalOps(MethodVisitor mv, Tdfa tdfa, String owner,
                                     int R, int LAS, int LAP, int POS,
                                     int[] op, int[] sfo, int[] sm) {
        int n = tdfa.stateCount;
        boolean[] isFallback = tdfa.stateIsFallback;
        int[] fallbackOff = tdfa.stateFallbackOpsOff;
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
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordBefore", "(II[C)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitInsn(Opcodes.CALOAD);
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
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWordAt", "(II[C)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, IN);
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitInsn(Opcodes.CALOAD);
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
        mv.visitInsn(Opcodes.CALOAD);
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
     * Emits {@code private static boolean isWordBefore(int pos, int len, char[] input)}:
     * whether the character immediately before {@code pos} is a word character. A low
     * surrogate at {@code pos-1} paired with a high surrogate at {@code pos-2} is decoded
     * to the full supplementary codepoint before the {@code isUnicodeWordChar} search, so
     * {@code \b} adjacent to supplementary word chars (e.g. U+1D504) is computed correctly.
     */
    private static void genIsWordBefore(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isWordBefore", "(II[C)Z", null, null);
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
        mv.visitInsn(Opcodes.CALOAD);
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
        mv.visitInsn(Opcodes.CALOAD);
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
     * Emits {@code private static boolean isWordAt(int pos, int len, char[] input)}:
     * whether the character at {@code pos} is a word character; a high surrogate at
     * {@code pos} paired with a low surrogate at {@code pos+1} is decoded first.
     */
    private static void genIsWordAt(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isWordAt", "(II[C)Z", null, null);
        mv.visitCode();
        // locals: 0=pos, 1=len, 2=input, 3=c, 4=l
        Label notPos = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notPos);
        // c = input[pos]
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.CALOAD);
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
        mv.visitInsn(Opcodes.CALOAD);
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

    // ===== fast-path eligibility =====

    /**
     * Pick the ASM dispatch mode based on DFA shape. Three modes, in order of
     * decreasing speed and decreasing per-class bytecode cost:
     *
     * <ol>
     *   <li><b>INLINED</b> — per-state inlined range checks in {@code runExtract}.
     *       Fastest dispatch (~25 bytes per live range, plus ~7 bytes per op
     *       for SET/COPY register instructions; O(1) per-char). Fits when
     *       the size estimate is below {@link #INLINE_BUDGET_BYTES}.</li>
     *   <li><b>TABLE_SCAN</b> — per-state compact snippet that binary-searches
     *       {@code RANGES_TABLE} at runtime. ~100 bytes per state; O(log R)
     *       per-char. Fits when {@code stateCount ≤ MAX_TABLESCAN_STATES}.</li>
     *   <li><b>DELEGATE</b> — every hot method forwards to the embedded
     *       {@link TdfaRunner}. Bytecode is trivially small; runtime is
     *       VM-equivalent. Used when neither of the above fits (e.g. the
     *       21 K-state dictionary alternation DFA, or 268-state / 352-tag
     *       lexer DFAs whose inlined ops alone would blow 65 KB).</li>
     * </ol>
     *
     * <p>The prior design had only INLINED + TABLE_SCAN (selected by a single
     * 1 500-range threshold); DFAs too big for TABLE_SCAN (notably dictionary)
     * threw {@code MethodTooLargeException} from {@code runExtract}, which the
     * parity harness then retried by re-compiling the regex on the VM factory
     * (doubling wall time). DELEGATE keeps the class emittable in all cases.
     */
    private static DispatchMode pickMode(Tdfa tdfa) {
        // INLINED — fastest dispatch (~25 B per range; ~7 B per transition op;
        // ~7 B per final-op). Used when the runExtract bytecode estimate fits
        // the 30 KB soft budget (the JVM hard cap is 65 KB; the estimate is a
        // lower bound, so leave headroom).
        if (estimateInlinedBytes(tdfa) <= INLINE_BUDGET_BYTES) return DispatchMode.INLINED;
        // TABLE_SCAN mode is preserved as a code path (per-state compact binary-
        // search dispatch via RANGES_TABLE) but isn't currently selected by
        // pickMode: on the workloads where INLINED doesn't fit, the embedded
        // TdfaRunner (DELEGATE mode) is faster thanks to its asciiRangeFlat
        // per-(state, ASCII-char) cache, which beats binary search on the
        // ASCII-heavy inputs that dominate the rebar suite. TABLE_SCAN remains
        // useful for non-ASCII workloads with few tags; flip the policy here to
        // re-enable it.
        return DispatchMode.DELEGATE;
    }

    /** Bytecode cost of the inlined final-ops block alone (~7 B/op). */
    private static int estimateFinalOpsBytes(Tdfa tdfa) {
        int[] op = tdfa.ops, sfo = tdfa.stateFinalOpsOff;
        int total = 0;
        for (int s = 0; s < tdfa.stateCount; s++) {
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
        int[] sm = tdfa.stateMeta, sb = tdfa.stateBase, rg = tdfa.ranges, op = tdfa.ops;
        int[] sfo = tdfa.stateFinalOpsOff;
        int total = 0;
        for (int s = 0; s < tdfa.stateCount; s++) {
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
        int[] sm = tdfa.stateMeta, sb = tdfa.stateBase, rg = tdfa.ranges;
        int total = 0;
        for (int s = 0; s < tdfa.stateCount; s++) {
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
        if (tdfa.multiline) return false;
        if (!checkRangesDisjoint(tdfa)) return false;
        for (int mask : tdfa.stateEntryMask) if (mask != 0) return false;
        for (int mask : tdfa.stateAcceptMask) if (mask != 0) return false;
        for (int i = 4; i < tdfa.ranges.length; i += 5) if (tdfa.ranges[i] != 0) return false;
        return true;
    }

    private static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, sb = tdfa.stateBase, rg = tdfa.ranges;
        long[] sortBuf = null;
        for (int s = 0; s < tdfa.stateCount; s++) {
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
