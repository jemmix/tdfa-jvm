package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_COPY;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_NIL;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_POS;

/** Final-ops (φ-function) variant solving + transition-op register allocation.
 *  Extracted verbatim from TdfaCompiler (2026-09 god-file split); the
 *  {@code owner} back-reference carries the shared compiler state. */
final class TdfaFinalVariants {
    final TdfaCompiler owner;

    TdfaFinalVariants(TdfaCompiler owner) { this.owner = owner; }

        /**
         * Allocate registers and emit ops for the transition. {@code vmaps} is per-source-state
         * to allow sharing registers across transitions out of the same state with identical RHS.
         * {@code nextReg} is bumped globally so registers are unique across states.
         */
        int[] transitionRegops(List<Config> configs, int sourceStateId) {
            owner.meter.tick();
            // Tagless patterns (count-model usage, the giant bounded-repeat DFAs):
            // no registers exist, so transitions carry no ops — nothing to do.
            if (owner.tags == 0) return TdfaCompiler.EMPTY;
            // vmap is keyed (tag, sign) — a flat int[2*tags] per source state,
            // shared across that state's symbol transitions (register
            // assignments are stable per source). The former boxed
            // HashMap<Long,Integer> was a top profile entry after the
            // interning rework.
            while (sourceVmaps.size() <= sourceStateId) sourceVmaps.add(null);
            int[] vmap = sourceVmaps.get(sourceStateId);
            if (vmap == null) { vmap = new int[2 * owner.tags]; sourceVmaps.set(sourceStateId, vmap); }
            List<int[]> opList = new ArrayList<>();
            // Per-tag LAST history sign: cached per hash-consed history id
            // (HistTable.lastSign) — formerly a rescan of each config's
            // sequence content, the transition-regop hot spot.
            for (int ci = 0; ci < configs.size(); ci++) {
                Config c = configs.get(ci);
                if (c.h == HistTable.EMPTY_ID) continue;
                int[] last = owner.hist.lastSign(c.h, owner.tags);
                int[] newRegs = c.regs.clone();
                for (int t = 1; t <= owner.tags; t++) {
                    int l = last[t - 1];
                    if (l == 0) continue;   // tag has no history entry
                    int slot = 2 * (t - 1) + (l == TdfaCompiler.TAG_POS ? 0 : 1);
                    int reg = vmap[slot];
                    if (reg == 0) reg = vmap[slot] = owner.nextReg++;
                    // Per-transition dedup (paper "if op not in O"): opList is
                    // bounded by 2*tags distinct (reg, sign) ops — linear scan
                    // beats the former boxed HashSet.
                    boolean dup = false;
                    for (int[] o : opList) {
                        if (o[1] == reg && o[0] == (l == TdfaCompiler.TAG_POS ? OP_SET_POS : OP_SET_NIL)) { dup = true; break; }
                    }
                    if (!dup) {
                        if (l == TdfaCompiler.TAG_POS) opList.add(new int[]{OP_SET_POS, reg, 0});
                        else opList.add(new int[]{OP_SET_NIL, reg, 0});
                    }
                    newRegs[t - 1] = reg;
                }
                configs.set(ci, new Config(c.state, newRegs, c.h, c.l, c.emptyMask, c.pri));
            }
            return flatten(opList);
        }

        /** Per-source-state (tag, sign) → register, flat int[2*tags]; null until first use. */
        final List<int[]> sourceVmaps = new ArrayList<>();

        int[] finalRegops(List<Config> configs) {
            if (owner.tags == 0) return TdfaCompiler.EMPTY;
            for (Config c : configs) {
                if (c.state == owner.nfa.accept) return finalRegopsOf(c);
            }
            return TdfaCompiler.EMPTY;
        }

        /**
         * Position-aware φ variants for one accepting state. A DFA state may
         * merge several accept configs of different priority whose zero-width
         * assertions differ; the tag-value winner is the highest-priority
         * accept config ALIVE under the runtime posFlags. When the winner is
         * the same config for all 64 masks the state is uniform (the common
         * case — the first accept config is unconditional) and nothing is
         * stored. Otherwise the per-mask winners' op lists (deduped) land in
         * {@code finalOpsVariants} with {@code finalMaskVariant} as the
         * [64] selector; materialization turns them into
         * {@code stateFinalOpsByMask}.
         */
        void computeFinalVariants(DfaStateBuilder sb, List<Config> cfgs) {
            int n = cfgs.size();
            int[] st = new int[n], mk = new int[n];
            for (int i = 0; i < n; i++) { st[i] = cfgs.get(i).state; mk[i] = cfgs.get(i).emptyMask; }
            computeFinalVariants(sb, st, mk, cfgs::get);
        }

        /** Packed (tagless) form: boxed closures are released, only
         *  (state, emptyMask) pairs remain — all the aliveness computation
         *  needs (finalRegopsOf returns empty ops when tags==0). */
        void computeFinalVariantsPacked(DfaStateBuilder sb, int[] pk) {
            int n = pk.length >> 1;
            int[] st = new int[n], mk = new int[n];
            for (int i = 0; i < n; i++) { st[i] = pk[i * 2]; mk[i] = pk[i * 2 + 1]; }
            computeFinalVariants(sb, st, mk, i -> null);
        }

        void computeFinalVariants(DfaStateBuilder sb, int[] st, int[] mk, java.util.function.IntFunction<Config> at) {
            int[] winner = new int[64];
            boolean uniform = true;
            for (int M = 0; M < 64; M++) {
                int w = -1;
                for (int i = 0; i < st.length; i++) {
                    if (st[i] != owner.nfa.accept) continue;
                    if ((mk[i] & ~M) == 0) { w = i; break; }
                }
                winner[M] = w;
                if (M > 0 && w != winner[0]) uniform = false;
            }
            if (Boolean.getBoolean("tdfa.debug.finals") && owner.tags > 0) {
                for (int i = 0; i < st.length; i++) {
                    if (st[i] != owner.nfa.accept) continue;
                    Config c = at.apply(i);
                    if (c == null) continue;   // packed (tagless) kernel
                    StringBuilder h = new StringBuilder("cfg[" + i + "] mask=" + c.emptyMask + " l:");
                    int[] last = owner.hist.lastSign(c.l, owner.tags);
                    for (int t = 1; t <= owner.tags; t++) {
                        h.append(" t").append(t).append(last[t - 1] == 0 ? "Ø" : (last[t - 1] == TdfaCompiler.TAG_POS ? "P" : "N"));
                    }
                    System.err.println("  [finals] " + h + "  winner(M63)=" + winner[63] + " winner(M0)=" + winner[0]);
                }
            }
            if (uniform) return;
            List<int[]> variants = new ArrayList<>();
            int[] maskVariant = new int[64];
            for (int M = 0; M < 64; M++) {
                int w = winner[M];
                if (w < 0) { maskVariant[M] = -1; continue; }
                int[] opsArr = finalRegopsOf(at.apply(w));
                int v = -1;
                for (int k = 0; k < variants.size(); k++)
                    if (Arrays.equals(variants.get(k), opsArr)) { v = k; break; }
                if (v < 0) { variants.add(opsArr); v = variants.size() - 1; }
                maskVariant[M] = v;
            }
            sb.finalOpsVariants = variants.toArray(new int[0][]);
            sb.finalMaskVariant = maskVariant;
        }

        /** φ ops for ONE accept config: per tag, COPY its working register, or
         *  SET_POS/SET_NIL from its tag history. */
        int[] finalRegopsOf(Config c) {
            if (owner.tags == 0) return TdfaCompiler.EMPTY;
            List<int[]> opList = new ArrayList<>();
            int[] lastSign = owner.hist.lastSign(c.l, owner.tags);
            for (int t = 1; t <= owner.tags; t++) {
                int dst = owner.finalRegisters[t - 1];
                if (lastSign[t - 1] == 0) {
                    opList.add(new int[]{OP_COPY, dst, c.regs[t - 1]});
                } else {
                    int last = lastSign[t - 1];
                    if (last == TdfaCompiler.TAG_POS) opList.add(new int[]{OP_SET_POS, dst, 0});
                    else opList.add(new int[]{OP_SET_NIL, dst, 0});
                }
            }
            return flatten(opList);
        }

        int[] flatten(List<int[]> opList) {
            int[] flat = new int[opList.size() * 3];
            for (int i = 0; i < opList.size(); i++) {
                int[] op = opList.get(i);
                flat[i * 3] = op[0]; flat[i * 3 + 1] = op[1]; flat[i * 3 + 2] = op[2];
            }
            return flat;
        }
}
