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

/**
 * Lowers a compiled {@link Tdfa} to JVM bytecode via the ASM library.
 *
 * <p>Each compiled regex gets its own {@link ClassLoader} so that when the
 * {@link Regex.Engine} instance becomes unreachable, the generated class
 * and its classloader are garbage-collected — no Metaspace leak.
 *
 * <p>The generated class implements {@link Regex.Engine} directly. DFA
 * transitions are baked into a {@code TABLESWITCH} on state, with per-range
 * {@code IF} cascades and inline register ops ({@code IASTORE}).
 */
public final class TdfaAsmBackend {

    private static final AtomicLong COUNTER = new AtomicLong();

    private static final String ENGINE_INT    = "io/github/jemmix/tdfa/Regex$Engine";
    private static final String MATCH_HOLDER  = "io/github/jemmix/tdfa/tdfa/TdfaRunner$MatchHolder";
    private static final String MATCH_RESULT  = "io/github/jemmix/tdfa/vm/MatchResult";
    private static final String CHAR_SEQ      = "java/lang/CharSequence";
    private static final String ARRAYS        = "java/util/Arrays";
    private static final String CS_DESC       = "Ljava/lang/CharSequence;";
    private static final String RUN_DESC      = "(" + CS_DESC + "IIZ)L" + MATCH_HOLDER + ";";

    public static Regex.Engine compile(Tdfa tdfa) {
        long id = COUNTER.incrementAndGet();
        String className  = "io.github.jemmix.tdfa.gen.Gen" + id;
        String internal   = className.replace('.', '/');
        byte[] bytecode   = generateBytecode(tdfa, internal);

        String cn = className;
        byte[] bc = bytecode;
        ClassLoader loader = new ClassLoader(TdfaAsmBackend.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(cn)) {
                    return defineClass(cn, bc, 0, bc.length);
                }
                return super.findClass(name);
            }
        };

        try {
            Class<?> cls = Class.forName(className, true, loader);
            return (Regex.Engine) cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("ASM bytecode backend failed", e);
        }
    }

    // ======================================================================

    private static byte[] generateBytecode(Tdfa tdfa, String internal) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internal, null, "java/lang/Object", new String[]{ENGINE_INT});

        generateClinit(cw, tdfa, internal);
        generateConstructor(cw);
        generateMatches(cw, tdfa, internal);
        generateFind(cw, tdfa, internal);
        generateMatch(cw, tdfa, internal);
        generateRun(cw, tdfa, internal);
        generateIsAccept(cw, tdfa, internal);
        generateEntryOk(cw, internal);
        generateAcceptOk(cw, internal);
        generatePositionFlags(cw, internal);
        generateIsWord(cw);
        generatePairAdvance(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ===== <clinit> =======================================================

    private static void generateClinit(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        int n = tdfa.stateCount;

        // ENTRY_MASK
        emitNewIntArray(mv, n);
        for (int s = 0; s < n; s++) {
            if (tdfa.stateEntryMask[s] != 0) {
                mv.visitInsn(Opcodes.DUP);
                iconst(mv, s);
                iconst(mv, tdfa.stateEntryMask[s]);
                mv.visitInsn(Opcodes.IASTORE);
            }
        }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ENTRY_MASK", "[I");

        // ACCEPT_MASK
        emitNewIntArray(mv, n);
        for (int s = 0; s < n; s++) {
            if (tdfa.stateAcceptMask[s] != 0) {
                mv.visitInsn(Opcodes.DUP);
                iconst(mv, s);
                iconst(mv, tdfa.stateAcceptMask[s]);
                mv.visitInsn(Opcodes.IASTORE);
            }
        }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "ACCEPT_MASK", "[I");

        // STOP_MASK = new int[n*16]; Arrays.fill(STOP_MASK, NEVER_STOP); set exceptions
        int stopLen = n * 16;
        emitNewIntArray(mv, stopLen);
        mv.visitInsn(Opcodes.DUP);
        iconst(mv, Tdfa.NEVER_STOP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);
        int[] som = tdfa.stopOnAcceptMask;
        for (int i = 0; i < som.length; i++) {
            if (som[i] != Tdfa.NEVER_STOP) {
                mv.visitInsn(Opcodes.DUP);
                iconst(mv, i);
                iconst(mv, som[i]);
                mv.visitInsn(Opcodes.IASTORE);
            }
        }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STOP_MASK", "[I");

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // declare the static fields
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "ENTRY_MASK", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "ACCEPT_MASK", "[I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STOP_MASK", "[I", null, null).visitEnd();
    }

    // ===== <init> =========================================================

    private static void generateConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== matches(CharSequence) ==========================================

    private static void generateMatches(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "matches",
                "(" + CS_DESC + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "length", "()I", true);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "run", RUN_DESC, false);
        Label ok = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, ok);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(ok);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== find(CharSequence) =============================================

    private static void generateFind(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "find",
                "(" + CS_DESC + ")Z", null, null);
        mv.visitCode();

        // int len = i.length();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "length", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        if (tdfa.startRequiresBeginText()) {
            emitRunCall(mv, owner, 0, 1, 0, 2, 0);
            Label ok = new Label();
            mv.visitJumpInsn(Opcodes.IFNONNULL, ok);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(ok);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
        } else {
            // for (int from = 0; from <= len; from++)
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            Label loop = new Label(), end = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitJumpInsn(Opcodes.IF_ICMPGT, end);
            emitRunCall(mv, owner, 0, 1, 3, 2, 0);
            Label found = new Label();
            mv.visitJumpInsn(Opcodes.IFNONNULL, found);
            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loop);
            mv.visitLabel(found);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(end);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== match(CharSequence, int) =======================================

    private static void generateMatch(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "match",
                "(" + CS_DESC + "I)L" + MATCH_RESULT + ";", null, null);
        mv.visitCode();

        // int len = i.length();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "length", "()I", true);
        int LV_LEN = 3, LV_MAX = 4, LV_F = 5, LV_H = 6;
        mv.visitVarInsn(Opcodes.ISTORE, LV_LEN);

        // int maxStart = startReqBT ? 0 : len;
        if (tdfa.startRequiresBeginText()) mv.visitInsn(Opcodes.ICONST_0);
        else mv.visitVarInsn(Opcodes.ILOAD, LV_LEN);
        mv.visitVarInsn(Opcodes.ISTORE, LV_MAX);

        // for (int f = from; f <= maxStart; f++)
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ISTORE, LV_F);

        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, LV_F);
        mv.visitVarInsn(Opcodes.ILOAD, LV_MAX);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, end);

        emitRunCall(mv, owner, 0, 1, LV_F, LV_LEN, 0);
        mv.visitVarInsn(Opcodes.ASTORE, LV_H);

        mv.visitVarInsn(Opcodes.ALOAD, LV_H);
        Label next = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, next);

        // return new MatchResult(h.regs, tagCount, groupCount, h.matchStart, h.matchEnd);
        mv.visitTypeInsn(Opcodes.NEW, MATCH_RESULT);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, LV_H);
        mv.visitFieldInsn(Opcodes.GETFIELD, MATCH_HOLDER, "regs", "[I");
        iconst(mv, tdfa.tagCount);
        iconst(mv, tdfa.groupCount);
        mv.visitVarInsn(Opcodes.ALOAD, LV_H);
        mv.visitFieldInsn(Opcodes.GETFIELD, MATCH_HOLDER, "matchStart", "I");
        mv.visitVarInsn(Opcodes.ALOAD, LV_H);
        mv.visitFieldInsn(Opcodes.GETFIELD, MATCH_HOLDER, "matchEnd", "I");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MATCH_RESULT, "<init>", "([IIIII)V", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(next);
        mv.visitIincInsn(LV_F, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== run — core DFA walk ============================================

    private static void generateRun(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "run", RUN_DESC, null, null);
        mv.visitCode();

        final int nStates = tdfa.stateCount;
        final int[] sm = tdfa.stateMeta;
        final int[] sfo = tdfa.stateFinalOpsOff;
        final int[] rg = tdfa.ranges;
        final int[] op = tdfa.ops;
        final boolean perl = tdfa.perlMode;

        final int I = 1, FROM = 2, TO = 3, ANC = 4;
        final int REGS = 5, ST = 6, POS = 7, LAP = 8, LAS = 9, MS = 10;
        final int HA = 11, DEAD = 12, C = 13, PF = 14, R = 15;

        // int[] regs = new int[registerCount]; Arrays.fill(regs, -1);
        iconst(mv, tdfa.registerCount);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        mv.visitVarInsn(Opcodes.ASTORE, REGS);
        mv.visitVarInsn(Opcodes.ALOAD, REGS);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "fill", "([II)V", false);

        iconst(mv, tdfa.startState); mv.visitVarInsn(Opcodes.ISTORE, ST);
        mv.visitVarInsn(Opcodes.ILOAD, FROM);    mv.visitVarInsn(Opcodes.ISTORE, POS);
        mv.visitInsn(Opcodes.ICONST_M1);         mv.visitVarInsn(Opcodes.ISTORE, LAP);
        mv.visitInsn(Opcodes.ICONST_M1);         mv.visitVarInsn(Opcodes.ISTORE, LAS);
        mv.visitVarInsn(Opcodes.ILOAD, FROM);    mv.visitVarInsn(Opcodes.ISTORE, MS);
        mv.visitInsn(Opcodes.ICONST_0);          mv.visitVarInsn(Opcodes.ISTORE, HA);
        mv.visitInsn(Opcodes.ICONST_0);          mv.visitVarInsn(Opcodes.ISTORE, DEAD);

        // if (!entryOk(state, pos, to, input)) return null;
        emitEntryOk(mv, owner, ST, POS, TO, I);
        Label initOk = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, initOk);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(initOk);

        Label loopStart = new Label();
        Label loopEnd   = new Label();
        Label afterSw   = new Label();

        // ---- LOOP ----
        mv.visitLabel(loopStart);

        // Accept check
        mv.visitVarInsn(Opcodes.ILOAD, ST);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isAccept", "(I)Z", false);
        Label skipAcc = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, skipAcc);

        emitAcceptOk(mv, owner, ST, POS, TO, I);
        mv.visitJumpInsn(Opcodes.IFEQ, skipAcc);

        mv.visitVarInsn(Opcodes.ILOAD, POS);  mv.visitVarInsn(Opcodes.ISTORE, LAP);
        mv.visitVarInsn(Opcodes.ILOAD, ST);   mv.visitVarInsn(Opcodes.ISTORE, LAS);
        mv.visitInsn(Opcodes.ICONST_1);       mv.visitVarInsn(Opcodes.ISTORE, HA);

        if (perl) {
            // pf = positionFlags(pos, to, input)
            mv.visitVarInsn(Opcodes.ILOAD, POS);
            mv.visitVarInsn(Opcodes.ILOAD, TO);
            mv.visitVarInsn(Opcodes.ALOAD, I);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlags",
                    "(II" + CS_DESC + ")I", false);
            mv.visitVarInsn(Opcodes.ISTORE, PF);
            // int stopMask = STOP_MASK[state * 16 + pf]
            mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "STOP_MASK", "[I");
            mv.visitVarInsn(Opcodes.ILOAD, ST);
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ILOAD, PF);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IALOAD);
            iconst(mv, Tdfa.NEVER_STOP);
            Label noStop = new Label();
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, noStop);
            mv.visitJumpInsn(Opcodes.GOTO, loopEnd);
            mv.visitLabel(noStop);
        }
        mv.visitLabel(skipAcc);

        // if (pos == to) break;
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, TO);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, loopEnd);

        // c = input.charAt(pos)
        mv.visitVarInsn(Opcodes.ALOAD, I);
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "charAt", "(I)C", true);
        mv.visitVarInsn(Opcodes.ISTORE, C);

        // posFlags = positionFlags(pos, to, input)
        mv.visitVarInsn(Opcodes.ILOAD, POS);
        mv.visitVarInsn(Opcodes.ILOAD, TO);
        mv.visitVarInsn(Opcodes.ALOAD, I);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlags",
                "(II" + CS_DESC + ")I", false);
        mv.visitVarInsn(Opcodes.ISTORE, PF);

        // switch (state)
        Label[] sl = new Label[nStates];
        Label defL = new Label();
        for (int s = 0; s < nStates; s++) sl[s] = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, ST);
        mv.visitTableSwitchInsn(0, nStates - 1, defL, sl);

        for (int s = 0; s < nStates; s++) {
            mv.visitLabel(sl[s]);
            int meta = sm[s];
            int base = meta >>> 9;
            int cnt  = (meta >>> 1) & 0xFF;

            // Collect live (non-dead) ranges
            List<int[]> live = new ArrayList<>();
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o + 2] >= 0) {
                    live.add(new int[]{rg[o], rg[o + 1], rg[o + 2], rg[o + 3], rg[o + 4]});
                }
            }

            Label stateDead = new Label();
            int nLive = live.size();

            for (int ri = 0; ri < nLive; ri++) {
                int[] range = live.get(ri);
                int lo = range[0], hi = range[1], target = range[2];
                int opsOff = range[3], reqMask = range[4];
                boolean isLast = (ri == nLive - 1);
                Label nextRange = isLast ? stateDead : new Label();

                // if (c >= lo && c <= hi)
                mv.visitVarInsn(Opcodes.ILOAD, C);
                iconst(mv, lo);
                mv.visitJumpInsn(Opcodes.IF_ICMPLT, nextRange);
                mv.visitVarInsn(Opcodes.ILOAD, C);
                iconst(mv, hi);
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, nextRange);

                if (reqMask != 0) {
                    mv.visitVarInsn(Opcodes.ILOAD, PF);
                    iconst(mv, reqMask);
                    mv.visitInsn(Opcodes.IAND);
                    iconst(mv, reqMask);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, nextRange);
                }

                // inline register ops
                if (opsOff != 0) {
                    emitOpsInline(mv, op, opsOff, REGS, POS, false, -1);
                }

                // state = target
                iconst(mv, target);
                mv.visitVarInsn(Opcodes.ISTORE, ST);

                // pos += pairAdvance(c, pos, to, input)
                mv.visitVarInsn(Opcodes.ILOAD, C);
                mv.visitVarInsn(Opcodes.ILOAD, POS);
                mv.visitVarInsn(Opcodes.ILOAD, TO);
                mv.visitVarInsn(Opcodes.ALOAD, I);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "pairAdvance",
                        "(CII" + CS_DESC + ")I", false);
                mv.visitVarInsn(Opcodes.ILOAD, POS);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ISTORE, POS);

                // entryOk(state, pos+1, to, input)
                mv.visitVarInsn(Opcodes.ILOAD, ST);
                mv.visitVarInsn(Opcodes.ILOAD, POS);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ILOAD, TO);
                mv.visitVarInsn(Opcodes.ALOAD, I);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "entryOk",
                        "(III" + CS_DESC + ")Z", false);
                Label entryOk = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, entryOk);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitVarInsn(Opcodes.ISTORE, DEAD);
                mv.visitJumpInsn(Opcodes.GOTO, afterSw);
                mv.visitLabel(entryOk);

                mv.visitIincInsn(POS, 1);
                mv.visitJumpInsn(Opcodes.GOTO, loopStart);

                if (!isLast) mv.visitLabel(nextRange);
            }

            mv.visitLabel(stateDead);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ISTORE, DEAD);
            mv.visitJumpInsn(Opcodes.GOTO, afterSw);
        }

        mv.visitLabel(defL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, DEAD);

        mv.visitLabel(afterSw);
        mv.visitVarInsn(Opcodes.ILOAD, DEAD);
        mv.visitJumpInsn(Opcodes.IFEQ, loopStart);

        // ---- POST-LOOP ----
        mv.visitLabel(loopEnd);

        Label retNull = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, HA);
        mv.visitJumpInsn(Opcodes.IFEQ, retNull);

        // if (anchored && lastAcceptPos != to) return null
        mv.visitVarInsn(Opcodes.ILOAD, ANC);
        Label doClone = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, doClone);
        mv.visitVarInsn(Opcodes.ILOAD, LAP);
        mv.visitVarInsn(Opcodes.ILOAD, TO);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, doClone);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(doClone);

        // r = regs.clone()
        mv.visitVarInsn(Opcodes.ALOAD, REGS);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[I");
        mv.visitVarInsn(Opcodes.ASTORE, R);

        // switch(lastAcceptState) { apply final ops to r }
        List<int[]> finals = new ArrayList<>();
        for (int s = 0; s < nStates; s++) {
            if ((sm[s] & 1) != 0 && sfo[s] != 0) finals.add(new int[]{s, sfo[s]});
        }
        if (!finals.isEmpty()) {
            int nf = finals.size();
            int[] keys = new int[nf];
            Label[] fl = new Label[nf];
            Label fDef = new Label(), fAfter = new Label();
            for (int k = 0; k < nf; k++) { keys[k] = finals.get(k)[0]; fl[k] = new Label(); }

            mv.visitVarInsn(Opcodes.ILOAD, LAS);
            mv.visitLookupSwitchInsn(fDef, keys, fl);

            for (int k = 0; k < nf; k++) {
                mv.visitLabel(fl[k]);
                emitOpsInline(mv, op, finals.get(k)[1], R, LAP, true, -1);
                mv.visitJumpInsn(Opcodes.GOTO, fAfter);
            }
            mv.visitLabel(fDef);
            mv.visitLabel(fAfter);
        }

        // return new MatchHolder(matchStart, lastAcceptPos, r)
        mv.visitTypeInsn(Opcodes.NEW, MATCH_HOLDER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ILOAD, MS);
        mv.visitVarInsn(Opcodes.ILOAD, LAP);
        mv.visitVarInsn(Opcodes.ALOAD, R);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MATCH_HOLDER, "<init>", "(II[I)V", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(retNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== isAccept(int) ==================================================

    private static void generateIsAccept(ClassWriter cw, Tdfa tdfa, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isAccept", "(I)Z", null, null);
        mv.visitCode();

        int n = tdfa.stateCount;
        int[] sm = tdfa.stateMeta;
        Label[] labels = new Label[n];
        Label falseL = new Label();
        for (int s = 0; s < n; s++) labels[s] = new Label();

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitTableSwitchInsn(0, n - 1, falseL, labels);

        for (int s = 0; s < n; s++) {
            mv.visitLabel(labels[s]);
            if ((sm[s] & 1) != 0) {
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
            } else {
                mv.visitJumpInsn(Opcodes.GOTO, falseL);
            }
        }
        mv.visitLabel(falseL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== entryOk(int, int, int, CharSequence) ===========================

    private static void generateEntryOk(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "entryOk", "(III" + CS_DESC + ")Z", null, null);
        mv.visitCode();
        // locals: 0=state, 1=pos, 2=to, 3=input, 4=required
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ENTRY_MASK", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label cont = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, cont);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cont);

        // (positionFlags(pos, to, input) & required) == required
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlags",
                "(II" + CS_DESC + ")I", false);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label yes = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, yes);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(yes);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== acceptOk(int, int, int, CharSequence) ==========================

    private static void generateAcceptOk(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "acceptOk", "(III" + CS_DESC + ")Z", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "ACCEPT_MASK", "[I");
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IALOAD);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label cont = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, cont);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cont);

        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "positionFlags",
                "(II" + CS_DESC + ")I", false);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        Label yes = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, yes);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(yes);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== positionFlags(int, int, CharSequence) ==========================

    private static void generatePositionFlags(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "positionFlags", "(II" + CS_DESC + ")I", null, null);
        mv.visitCode();
        // locals: 0=pos, 1=to, 2=input, 3=flags, 4=prevWord, 5=currWord
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        // if (pos == 0) flags |= BEGIN_TEXT
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        Label l1 = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, l1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(l1);

        // if (pos == to) flags |= END_TEXT
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        Label l2 = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, l2);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IOR);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(l2);

        // boolean prevWord = pos > 0 && isWord(input.charAt(pos-1))
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        Label pf = new Label(), pd = new Label();
        mv.visitJumpInsn(Opcodes.IFLE, pf);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "charAt", "(I)C", true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWord", "(C)Z", false);
        mv.visitJumpInsn(Opcodes.GOTO, pd);
        mv.visitLabel(pf);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(pd);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // boolean currWord = pos < to && isWord(input.charAt(pos))
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        Label cf = new Label(), cd = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, cf);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, cd);
        mv.visitLabel(cf);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "charAt", "(I)C", true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "isWord", "(C)Z", false);
        mv.visitLabel(cd);
        mv.visitVarInsn(Opcodes.ISTORE, 5);

        // if (prevWord != currWord) flags |= WORD_BOUNDARY; else flags |= NO_WORD_BOUNDARY
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
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

    // ===== isWord(char) ===================================================

    private static void generateIsWord(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "isWord", "(C)Z", null, null);
        mv.visitCode();

        Label t = new Label(), f = new Label();

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitLdcInsn((int) '_');
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, t);

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, '0');
        Label cl = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, cl);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, '9');
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, t);
        mv.visitLabel(cl);

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 'a');
        Label cu = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, cu);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 'z');
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, t);
        mv.visitLabel(cu);

        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 'A');
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, f);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitIntInsn(Opcodes.BIPUSH, 'Z');
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, f);

        mv.visitLabel(t);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(f);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== pairAdvance(char, int, int, CharSequence) ======================

    private static void generatePairAdvance(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "pairAdvance", "(CII" + CS_DESC + ")I", null, null);
        mv.visitCode();

        Label zero = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitLdcInsn(0xD800);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, zero);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitLdcInsn(0xDBFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, zero);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zero);

        // c2 = input.charAt(pos+1) → store in local 4
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHAR_SEQ, "charAt", "(I)C", true);
        mv.visitVarInsn(Opcodes.ISTORE, 4);

        // return (c2 >= 0xDC00 && c2 <= 0xDFFF) ? 1 : 0
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDC00);
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, zero);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitLdcInsn(0xDFFF);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, zero);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(zero);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ===== Shared emit helpers ============================================

    /**
     * Emits inline register ops from the flat ops array.
     * @param regsLv  local var index of the regs array
     * @param posLv   local var index of pos (for SET_POS); -1 to use posVal instead
     * @param isFinal true if these are final ops (value is posLv; SET_POS uses it directly)
     * @param posVal  literal pos value when posLv == -1
     */
    private static void emitOpsInline(MethodVisitor mv, int[] op, int opsOff,
                                      int regsLv, int posLv, boolean isFinal, int posVal) {
        int j = opsOff;
        while (op[j] != Tdfa.OP_END) {
            int opc = op[j], dst = op[j + 1], src = op[j + 2];
            if (opc == Tdfa.OP_SET_POS) {
                mv.visitVarInsn(Opcodes.ALOAD, regsLv);
                iconst(mv, dst);
                if (posLv >= 0) mv.visitVarInsn(Opcodes.ILOAD, posLv);
                else            iconst(mv, posVal);
                mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_SET_NIL) {
                mv.visitVarInsn(Opcodes.ALOAD, regsLv);
                iconst(mv, dst);
                mv.visitInsn(Opcodes.ICONST_M1);
                mv.visitInsn(Opcodes.IASTORE);
            } else if (opc == Tdfa.OP_COPY) {
                mv.visitVarInsn(Opcodes.ALOAD, regsLv);
                iconst(mv, dst);
                mv.visitVarInsn(Opcodes.ALOAD, regsLv);
                iconst(mv, src);
                mv.visitInsn(Opcodes.IALOAD);
                mv.visitInsn(Opcodes.IASTORE);
            }
            j += 3;
        }
    }

    /** Emits a run(input, from, to, anchored) call. */
    private static void emitRunCall(MethodVisitor mv, String owner,
                                    int lvThis, int lvInput, int lvFrom, int lvTo, int anchored) {
        mv.visitVarInsn(Opcodes.ALOAD, lvThis);
        mv.visitVarInsn(Opcodes.ALOAD, lvInput);
        mv.visitVarInsn(Opcodes.ILOAD, lvFrom);
        mv.visitVarInsn(Opcodes.ILOAD, lvTo);
        iconst(mv, anchored);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "run", RUN_DESC, false);
    }

    /** Emits entryOk(state, pos, to, input) as INVOKESTATIC. */
    private static void emitEntryOk(MethodVisitor mv, String owner,
                                    int lvState, int lvPos, int lvTo, int lvInput) {
        mv.visitVarInsn(Opcodes.ILOAD, lvState);
        mv.visitVarInsn(Opcodes.ILOAD, lvPos);
        mv.visitVarInsn(Opcodes.ILOAD, lvTo);
        mv.visitVarInsn(Opcodes.ALOAD, lvInput);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "entryOk",
                "(III" + CS_DESC + ")Z", false);
    }

    /** Emits acceptOk(state, pos, to, input) as INVOKESTATIC. */
    private static void emitAcceptOk(MethodVisitor mv, String owner,
                                     int lvState, int lvPos, int lvTo, int lvInput) {
        mv.visitVarInsn(Opcodes.ILOAD, lvState);
        mv.visitVarInsn(Opcodes.ILOAD, lvPos);
        mv.visitVarInsn(Opcodes.ILOAD, lvTo);
        mv.visitVarInsn(Opcodes.ALOAD, lvInput);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "acceptOk",
                "(III" + CS_DESC + ")Z", false);
    }

    private static void emitNewIntArray(MethodVisitor mv, int size) {
        iconst(mv, size);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
    }

    private static void iconst(MethodVisitor mv, int val) {
        if (val >= -1 && val <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + val);
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, val);
        } else {
            mv.visitLdcInsn(val);
        }
    }
}
