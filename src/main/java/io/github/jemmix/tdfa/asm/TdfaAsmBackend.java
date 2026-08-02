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

    public static Regex.Engine compile(Tdfa tdfa) {
        byte[] bc;
        try {
            long id = COUNTER.incrementAndGet();
            String cn = "io.github.jemmix.tdfa.gen.Gen" + id;
            String owner = cn.replace('.', '/');
            bc = generate(tdfa, owner);
            final byte[] bytes = bc;
            final String className = cn;
            ClassLoader cl = new ClassLoader(TdfaAsmBackend.class.getClassLoader()) {
                @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                    if (n.equals(className)) return defineClass(className, bytes, 0, bytes.length);
                    return super.findClass(n);
                }
            };
            return (Regex.Engine) Class.forName(cn, true, cl).getDeclaredConstructor().newInstance();
        } catch (org.objectweb.asm.MethodTooLargeException e) {
            return new io.github.jemmix.tdfa.tdfa.TdfaRunner(tdfa);
        } catch (Exception e) {
            throw new IllegalStateException("ASM backend failed", e);
        }
    }

    private static byte[] generate(Tdfa tdfa, String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", new String[]{ENGINE});
        genClinit(cw, tdfa, owner);
        genInit(cw);
        genMatches(cw, owner);
        genFind(cw, owner);
        genMatch(cw, tdfa, owner);
        genToCharArray(cw);
        genRunBoolean(cw, tdfa, owner);
        genRunExtract(cw, tdfa, owner);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ===== <clinit> =====

    private static void genClinit(ClassWriter cw, Tdfa tdfa, String owner) {
        for (String f : new String[]{"ENTRY_MASK", "ACCEPT_MASK", "STOP_MASK", "IS_ACCEPT"})
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, f, "[I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        int n = tdfa.stateCount;

        newIntArr(mv, n);
        for (int s = 0; s < n; s++) if (tdfa.stateEntryMask[s] != 0) { mv.visitInsn(Opcodes.DUP); ic(mv, s); ic(mv, tdfa.stateEntryMask[s]); mv.visitInsn(Opcodes.IASTORE); }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ENTRY_MASK", "[I");

        newIntArr(mv, n);
        for (int s = 0; s < n; s++) if (tdfa.stateAcceptMask[s] != 0) { mv.visitInsn(Opcodes.DUP); ic(mv, s); ic(mv, tdfa.stateAcceptMask[s]); mv.visitInsn(Opcodes.IASTORE); }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ACCEPT_MASK", "[I");

        newIntArr(mv, n);
        for (int s = 0; s < n; s++) if ((tdfa.stateMeta[s] & 1) != 0) { mv.visitInsn(Opcodes.DUP); ic(mv, s); mv.visitInsn(Opcodes.ICONST_1); mv.visitInsn(Opcodes.IASTORE); }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "IS_ACCEPT", "[I");

        newIntArr(mv, n * 16);
        mv.visitInsn(Opcodes.DUP); ic(mv, Tdfa.NEVER_STOP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);
        for (int i = 0; i < tdfa.stopOnAcceptMask.length; i++) if (tdfa.stopOnAcceptMask[i] != Tdfa.NEVER_STOP) { mv.visitInsn(Opcodes.DUP); ic(mv, i); ic(mv, tdfa.stopOnAcceptMask[i]); mv.visitInsn(Opcodes.IASTORE); }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STOP_MASK", "[I");

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== <init> =====

    private static void genInit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    // ===== interface methods =====

    private static void genMatches(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toCharArray", "(" + CS_D + ")[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "runBoolean", "([CIIZ)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genFind(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find", "(" + CS_D + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toCharArray", "(" + CS_D + ")[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "runBoolean", "([CIIZ)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0); mv.visitEnd();
    }

    private static void genMatch(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "match", "(" + CS_D + "I)L" + RESULT + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "toCharArray", "(" + CS_D + ")[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "runExtract", "([CII)L" + HOLDER + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        Label ret = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, ret);
        mv.visitTypeInsn(Opcodes.NEW, RESULT);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOLDER, "regs", "[I");
        ic(mv, tdfa.tagCount);
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

    // ===== runBoolean — zero-alloc DFA walk =====

    private static void genRunBoolean(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "runBoolean", "([CIIZ)Z", null, null);
        mv.visitCode();
        emitRunCore(mv, tdfa, owner, false);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== runExtract — DFA walk with register tracking =====

    private static void genRunExtract(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "runExtract", "([CII)L" + HOLDER + ";", null, null);
        mv.visitCode();
        emitRunCore(mv, tdfa, owner, true);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== shared core: DFA walk + search loop =====

    private static void emitRunCore(MethodVisitor mv, Tdfa tdfa, String owner, boolean extract) {
        final boolean perl = tdfa.perlMode;
        final boolean startReqBT = tdfa.startRequiresBeginText();
        final int nStates = tdfa.stateCount;
        final int[] sm = tdfa.stateMeta, rg = tdfa.ranges, op = tdfa.ops, sfo = tdfa.stateFinalOpsOff;

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

        // Entry check for start state at ST
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T1);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        Label initEntryOk = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, initEntryOk);
        emitPFInline(mv, owner, IN, ST, LEN, PF2, T2, T3, T4);
        mv.visitVarInsn(Opcodes.ILOAD, PF2);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, searchNext);
        mv.visitLabel(initEntryOk);

        // state=0, pos=start, haveAccept=false, lastAcceptPos=-1
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, STATE);
        mv.visitVarInsn(Opcodes.ILOAD, ST); mv.visitVarInsn(Opcodes.ISTORE, POS);
        mv.visitInsn(Opcodes.ICONST_0); mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitInsn(Opcodes.ICONST_M1); mv.visitVarInsn(Opcodes.ISTORE, LAP);

        // ===== DFA LOOP =====
        Label dfaLoop = new Label(), dfaEnd = new Label();
        mv.visitLabel(dfaLoop);

        // pf = positionFlags(pos, len, input)
        emitPFInline(mv, owner, IN, POS, LEN, PF, T1, T2, T3);

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
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
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

        // c = input[pos] (CALOAD)
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, C_LV);

        // ===== DFA DISPATCH (TABLESWITCH) =====
        emitDfaDispatch(mv, tdfa, owner, extract,
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
            emitFinalOps(mv, tdfa, owner, R, LAS, LAP, T1, op, sfo, sm);

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
                                        boolean extract,
                                        int IN, int STATE, int POS, int LEN, int PF, int C_LV,
                                        int REGS, int T1, int T2, int PF2, int T3, int T4,
                                        Label dfaLoop, Label dfaEnd, int[] op) {
        int nStates = tdfa.stateCount;
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;

        Label[] sl = new Label[nStates];
        Label def = new Label();
        for (int s = 0; s < nStates; s++) sl[s] = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, STATE);
        mv.visitTableSwitchInsn(0, nStates - 1, def, sl);

        for (int s = 0; s < nStates; s++) {
            mv.visitLabel(sl[s]);
            int meta = sm[s];
            int base = meta >>> 9, cnt = (meta >>> 1) & 0xFF;

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

                // surrogate pair advance: if c in [0xD800,0xDBFF] && pos+1<len && input[pos+1] in [0xDC00,0xDFFF] → pos++
                emitSurrogateAdvance(mv, IN, C_LV, POS, LEN, T1);

                // entry check for target at pos+1
                mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
                mv.visitVarInsn(Opcodes.ILOAD, STATE);
                mv.visitInsn(Opcodes.IALOAD);
                mv.visitVarInsn(Opcodes.ISTORE, T1);
                mv.visitVarInsn(Opcodes.ILOAD, T1);
                Label entryOk = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, entryOk);
                // compute PF at pos+1
                mv.visitVarInsn(Opcodes.ILOAD, POS);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, T2);
                emitPFInline(mv, owner, IN, T2, LEN, PF2, T3, T4, T1);
                mv.visitVarInsn(Opcodes.ILOAD, PF2);
                mv.visitVarInsn(Opcodes.ILOAD, T1);
                mv.visitInsn(Opcodes.IAND);
                mv.visitVarInsn(Opcodes.ILOAD, T1);
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, dfaEnd);
                mv.visitLabel(entryOk);

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
                                     int R, int LAS, int LAP, int T1,
                                     int[] op, int[] sfo, int[] sm) {
        int n = tdfa.stateCount;
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
            emitOpsInlineFinal(mv, op, finals.get(k)[1], R, LAP);
            mv.visitJumpInsn(Opcodes.GOTO, fAfter);
        }
        mv.visitLabel(fDef);
        mv.visitLabel(fAfter);
    }

    // ===== position flags (inline) =====

    private static void emitPFInline(MethodVisitor mv, String owner,
                                     int IN, int POS, int LEN, int RESULT, int T1, int T2, int T3) {
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

        // prevWord = pos > 0 && isWord(input[pos-1])
        Label prevFalse = new Label(), prevDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitJumpInsn(Opcodes.IFLE, prevFalse);
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T1);
        emitIsWordBranch(mv, T1, prevFalse);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, prevDone);
        mv.visitLabel(prevFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(prevDone);
        mv.visitVarInsn(Opcodes.ISTORE, T1); // prevWord

        // currWord = pos < len && isWord(input[pos])
        Label currFalse = new Label(), currDone = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, currFalse);
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T2);
        emitIsWordBranch(mv, T2, currFalse);
        mv.visitInsn(Opcodes.ICONST_1);
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

    private static void emitIsWordBranch(MethodVisitor mv, int lvChar, Label notWord) {
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

    // ===== surrogate pair advance (inline) =====

    private static void emitSurrogateAdvance(MethodVisitor mv, int IN, int C_LV, int POS, int LEN, int T1) {
        Label noSur = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0xD800);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, noSur);
        mv.visitVarInsn(Opcodes.ILOAD, C_LV);
        mv.visitLdcInsn(0xDBFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, noSur);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, LEN);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, noSur);
        mv.visitVarInsn(Opcodes.ALOAD, IN);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, T1);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitLdcInsn(0xDC00);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, noSur);
        mv.visitVarInsn(Opcodes.ILOAD, T1);
        mv.visitLdcInsn(0xDFFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, noSur);
        mv.visitIincInsn(POS, 1);
        mv.visitLabel(noSur);
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
}
