package io.github.jemmix.tdfa.core.determinize;

import io.github.jemmix.tdfa.core.dfa.DfaStateBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_APPEND_POS;
import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_COPY;
import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_SET_NIL;
import static io.github.jemmix.tdfa.core.dfa.Tdfa.OP_SET_POS;

/**
 * Final-ops (φ-function) variant solving + transition-op register allocation.
 * The {@code owner} back-reference carries the shared determinization
 * context (registers, tag histories, meter).
 *
 * <p><b>Multi-valued compiles</b> (BT22 §3.1, {@code owner.multi}): every
 * POSITIVE history occurrence of a tag contributes an
 * {@link OP_APPEND_POS} op (the paper's {@code i ← j·h} append — negative
 * tags never enter histories; the engine's re2j capture contract keeps a
 * value once set, so multi-valued lists record only real participations)
 * instead of the last-sign reduction, and φ REPLACES the final register
 * (COPY-then-append from the working register — idempotent under the
 * eager re-application at every accept, which plain appending would not
 * be). Append chains dedup as whole chains — the leading
 * {@code (dst, src)} triple identifies the chain (dst is vmap-keyed by
 * exactly the RHS), and repeated triples WITHIN a chain are genuine
 * (consecutive same-sign occurrences), so per-triple dedup would drop
 * real appends.
 */
final class TdfaFinalVariants {
    final TdfaCompiler owner;
    /**
     * Per-source-state (tag, sign) → register, flat int[2*tags]; null until first use.
     */
    final List<int[]> sourceVmaps = new ArrayList<>();
    /**
     * Multi-valued twin of {@link #sourceVmaps}: per-source-state
     * (history, src register, tag) → register. The RHS of a multi-valued
     * op is the pair (current register, the tag's history projection), so
     * the vmap key must cover both — a flat int[2*tags] cannot.
     */
    final List<MultiVmap> sourceMultiVmaps = new ArrayList<>();

    TdfaFinalVariants(TdfaCompiler owner) {
        this.owner = owner;
    }

    /**
     * Allocate registers and emit ops for the transition. {@code vmaps} is per-source-state
     * to allow sharing registers across transitions out of the same state with identical RHS.
     * {@code nextReg} is bumped globally so registers are unique across states.
     */
    int[] transitionRegops(List<Config> configs, int sourceStateId) {
        owner.meter.tick();
        // Tagless patterns (count-model usage, the giant bounded-repeat DFAs):
        // no registers exist, so transitions carry no ops — nothing to do.
        if (owner.tags == 0) {
            return TdfaCompiler.EMPTY;
        }
        if (owner.multi) {
            return transitionRegopsMulti(configs, sourceStateId);
        }
        // vmap is keyed (tag, sign) — a flat int[2*tags] per source state,
        // shared across that state's symbol transitions (register
        // assignments are stable per source). A boxed
        // HashMap<Long,Integer> per state would become a top profile
        // entry.
        while (sourceVmaps.size() <= sourceStateId) {
            sourceVmaps.add(null);
        }
        int[] vmap = sourceVmaps.get(sourceStateId);
        if (vmap == null) {
            vmap = new int[2 * owner.tags];
            sourceVmaps.set(sourceStateId, vmap);
        }
        List<int[]> opList = new ArrayList<>();
        // Per-tag LAST history sign: cached per hash-consed history id
        // (HistTable.lastSign) — a rescan of each config's sequence
        // content would be the transition-regop hot spot.
        for (int ci = 0; ci < configs.size(); ci++) {
            Config c = configs.get(ci);
            if (c.h == HistTable.EMPTY_ID) {
                continue;
            }
            int[] last = owner.hist.lastSign(c.h, owner.tags);
            int[] newRegs = c.regs.clone();
            for (int t = 1; t <= owner.tags; t++) {
                int l = last[t - 1];
                if (l == 0) {
                    continue;
                } // tag has no history entry
                int slot = 2 * (t - 1) + (l == TdfaCompiler.TAG_POS ? 0 : 1);
                int reg = vmap[slot];
                if (reg == 0) {
                    reg = vmap[slot] = owner.nextReg++;
                }
                // Per-transition dedup (paper "if op not in O"): opList is
                // bounded by 2*tags distinct (reg, sign) ops — a linear
                // scan beats a boxed HashSet.
                boolean dup = false;
                for (int[] o : opList) {
                    if (o[1] == reg && o[0] == (l == TdfaCompiler.TAG_POS ? OP_SET_POS : OP_SET_NIL)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    if (l == TdfaCompiler.TAG_POS) {
                        opList.add(new int[]{OP_SET_POS, reg, 0});
                    } else {
                        opList.add(new int[]{OP_SET_NIL, reg, 0});
                    }
                }
                newRegs[t - 1] = reg;
            }
            configs.set(ci, new Config(c.state, newRegs, c.h, c.l, c.emptyMask, c.pri));
        }
        return flatten(opList);
    }

    /**
     * Multi-valued transition regops: one append chain per (config, tag)
     * whose INHERITED history {@code h} mentions the tag — every
     * occurrence appends its sign, so a repeated group keeps its whole
     * offset sequence, in order, in one register. The chain's destination
     * is keyed by the full RHS {@code (h, r[t], t)} — equal RHS share a
     * register and (deduped) one chain.
     */
    private int[] transitionRegopsMulti(List<Config> configs, int sourceStateId) {
        while (sourceMultiVmaps.size() <= sourceStateId) {
            sourceMultiVmaps.add(null);
        }
        MultiVmap vmap = sourceMultiVmaps.get(sourceStateId);
        if (vmap == null) {
            vmap = new MultiVmap();
            sourceMultiVmaps.set(sourceStateId, vmap);
        }
        List<int[]> opList = new ArrayList<>();
        for (int ci = 0; ci < configs.size(); ci++) {
            Config c = configs.get(ci);
            if (c.h == HistTable.EMPTY_ID) {
                continue;
            }
            int[] h = owner.hist.content(c.h);
            int[] newRegs = c.regs.clone();
            for (int t = 1; t <= owner.tags; t++) {
                if (!mentions(h, t)) {
                    continue;
                }
                int src = c.regs[t - 1];
                int dst = vmap.get(c.h, src, t, owner);
                if (!chainPresent(opList, dst, src)) {
                    int prev = src;
                    for (int v : h) {
                        if (v != t) {
                            continue;
                        }
                        opList.add(new int[]{OP_APPEND_POS, dst, prev});
                        prev = dst;
                    }
                }
                newRegs[t - 1] = dst;
            }
            configs.set(ci, new Config(c.state, newRegs, c.h, c.l, c.emptyMask, c.pri));
        }
        return flatten(opList);
    }

    /** Whether {@code seq} mentions tag {@code t} (histories are positive-only). */
    private static boolean mentions(int[] seq, int t) {
        for (int v : seq) {
            if (v == t) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole-chain dedup: the chain writing {@code dst} from {@code src}
     * was already emitted iff its leading triple is present (dst is keyed
     * by exactly that RHS, so no other chain can produce it).
     */
    private static boolean chainPresent(List<int[]> opList, int dst, int src) {
        for (int[] o : opList) {
            if (o[0] == OP_APPEND_POS && o[1] == dst && o[2] == src) {
                return true;
            }
        }
        return false;
    }

    int[] finalRegops(List<Config> configs) {
        if (owner.tags == 0) {
            return TdfaCompiler.EMPTY;
        }
        for (Config c : configs) {
            if (c.state == owner.nfa.accept) {
                return finalRegopsOf(c);
            }
        }
        return TdfaCompiler.EMPTY;
    }

    /**
     * Position-aware φ variants for one accepting state. A DFA state may
     * merge several accept configs of different priority whose zero-width
     * assertions differ; the tag-value winner is the highest-priority
     * accept config ALIVE under the runtime posFlags. When the winner is
     * the same config for all masks the state is uniform (the common
     * case — the first accept config is unconditional) and nothing is
     * stored. Otherwise the per-mask winners' op lists (deduped) land in
     * {@code finalOpsVariants} with {@code finalMaskVariant} as the
     * [posFlagCells] selector; materialization turns them into
     * {@code stateFinalOpsByMask}.
     */
    void computeFinalVariants(DfaStateBuilder sb, List<Config> cfgs) {
        int n = cfgs.size();
        int[] st = new int[n], mk = new int[n];
        for (int i = 0; i < n; i++) {
            st[i] = cfgs.get(i).state;
            mk[i] = cfgs.get(i).emptyMask;
        }
        computeFinalVariants(sb, st, mk, cfgs::get);
    }

    /**
     * Packed (tagless) form: boxed closures are released, only
     * (state, emptyMask) pairs remain — all the aliveness computation
     * needs (finalRegopsOf returns empty ops when tags==0).
     */
    void computeFinalVariantsPacked(DfaStateBuilder sb, int[] pk) {
        int n = pk.length >> 1;
        int[] st = new int[n], mk = new int[n];
        for (int i = 0; i < n; i++) {
            st[i] = pk[i * 2];
            mk[i] = pk[i * 2 + 1];
        }
        computeFinalVariants(sb, st, mk, i -> null);
    }

    void computeFinalVariants(DfaStateBuilder sb, int[] st, int[] mk, IntFunction<Config> at) {
        int cells = owner.posFlagCells;
        owner.meter.tick((long) cells * st.length); // cells masks × n aliveness scan — budget-visible
        int[] winner = new int[cells];
        boolean uniform = true;
        for (int M = 0; M < cells; M++) {
            int w = -1;
            for (int i = 0; i < st.length; i++) {
                if (st[i] != owner.nfa.accept) {
                    continue;
                }
                if ((mk[i] & ~M) == 0) {
                    w = i;
                    break;
                }
            }
            winner[M] = w;
            if (M > 0 && w != winner[0]) {
                uniform = false;
            }
        }
        if (Boolean.getBoolean("tdfa.debug.finals") && owner.tags > 0) {
            for (int i = 0; i < st.length; i++) {
                if (st[i] != owner.nfa.accept) {
                    continue;
                }
                Config c = at.apply(i);
                if (c == null) {
                    continue;
                } // packed (tagless) kernel
                StringBuilder h = new StringBuilder("cfg[" + i + "] mask=" + c.emptyMask + " l:");
                int[] last = owner.hist.lastSign(c.l, owner.tags);
                for (int t = 1; t <= owner.tags; t++) {
                    h.append(" t").append(t)
                        .append(last[t - 1] == 0 ? "Ø" : (last[t - 1] == TdfaCompiler.TAG_POS ? "P" : "N"));
                }
                System.err.println("  [finals] " + h + "  winner(M63)=" + winner[63] + " winner(M0)=" + winner[0]);
            }
        }
        if (uniform) {
            return;
        }
        List<int[]> variants = new ArrayList<>();
        int[] maskVariant = new int[cells];
        for (int M = 0; M < cells; M++) {
            int w = winner[M];
            if (w < 0) {
                maskVariant[M] = -1;
                continue;
            }
            int[] opsArr = finalRegopsOf(at.apply(w));
            int v = -1;
            for (int k = 0; k < variants.size(); k++) {
                if (Arrays.equals(variants.get(k), opsArr)) {
                    v = k;
                    break;
                }
            }
            if (v < 0) {
                variants.add(opsArr);
                v = variants.size() - 1;
            }
            maskVariant[M] = v;
        }
        sb.finalOpsVariants = variants.toArray(new int[0][]);
        sb.finalMaskVariant = maskVariant;
    }

    /**
     * φ ops for ONE accept config: per tag, COPY its working register, or
     * SET_POS/SET_NIL from its tag history. Multi-valued compiles REPLACE
     * the final register with {@code r[t]·l_t}: COPY first, then one append
     * per history occurrence — re-running the block at a later accept
     * recomputes the same sequence instead of growing it (eager φ
     * idempotence).
     */
    int[] finalRegopsOf(Config c) {
        if (owner.tags == 0) {
            return TdfaCompiler.EMPTY;
        }
        List<int[]> opList = new ArrayList<>();
        if (owner.multi) {
            int[] l = owner.hist.content(c.l);
            for (int t = 1; t <= owner.tags; t++) {
                int dst = owner.finalRegisters[t - 1];
                opList.add(new int[]{OP_COPY, dst, c.regs[t - 1]});
                int prev = dst;
                for (int v : l) {
                    if (v != t) {
                        continue;
                    }
                    opList.add(new int[]{OP_APPEND_POS, dst, prev});
                    prev = dst;
                }
            }
            return flatten(opList);
        }
        int[] lastSign = owner.hist.lastSign(c.l, owner.tags);
        for (int t = 1; t <= owner.tags; t++) {
            int dst = owner.finalRegisters[t - 1];
            if (lastSign[t - 1] == 0) {
                opList.add(new int[]{OP_COPY, dst, c.regs[t - 1]});
            } else {
                int last = lastSign[t - 1];
                if (last == TdfaCompiler.TAG_POS) {
                    opList.add(new int[]{OP_SET_POS, dst, 0});
                } else {
                    opList.add(new int[]{OP_SET_NIL, dst, 0});
                }
            }
        }
        return flatten(opList);
    }

    int[] flatten(List<int[]> opList) {
        int[] flat = new int[opList.size() * 3];
        for (int i = 0; i < opList.size(); i++) {
            int[] op = opList.get(i);
            flat[i * 3] = op[0];
            flat[i * 3 + 1] = op[1];
            flat[i * 3 + 2] = op[2];
        }
        return flat;
    }

    /**
     * Primitive open-addressing map (history id, src register, tag) →
     * destination register for multi-valued transition regops. Parallel
     * int arrays with exact triple comparison — the key space cannot pack
     * into one long (history ids and register ids both grow past 2^20 on
     * real compiles), and a boxed key would allocate per lookup on the
     * determinizer's per-transition path.
     */
    private static final class MultiVmap {
        private int[] hKey = new int[16];
        private int[] sKey = new int[16];
        private int[] tKey = new int[16];
        private int[] val = new int[16];
        private int mask = 15;
        private int size;

        int get(int h, int s, int t, TdfaCompiler owner) {
            int i = spread(h, s, t) & mask;
            while (val[i] != 0) {
                if (hKey[i] == h && sKey[i] == s && tKey[i] == t) {
                    return val[i];
                }
                i = (i + 1) & mask;
            }
            int reg = owner.nextReg++;
            hKey[i] = h;
            sKey[i] = s;
            tKey[i] = t;
            val[i] = reg;
            if (++size * 4 > (mask + 1) * 3) {
                grow();
            }
            return reg;
        }

        private void grow() {
            int n = (mask + 1) * 2;
            int[] nh = new int[n], ns = new int[n], nt = new int[n], nv = new int[n];
            int nMask = n - 1;
            for (int i = 0; i <= mask; i++) {
                if (val[i] == 0) {
                    continue;
                }
                int j = spread(hKey[i], sKey[i], tKey[i]) & nMask;
                while (nv[j] != 0) {
                    j = (j + 1) & nMask;
                }
                nh[j] = hKey[i];
                ns[j] = sKey[i];
                nt[j] = tKey[i];
                nv[j] = val[i];
            }
            hKey = nh;
            sKey = ns;
            tKey = nt;
            val = nv;
            mask = nMask;
        }

        private static int spread(int h, int s, int t) {
            int k = h * 1000003 + s * 7919 + t;
            k ^= k >>> 13;
            k *= 0x5BD1E995;
            return k ^ (k >>> 15);
        }
    }
}
