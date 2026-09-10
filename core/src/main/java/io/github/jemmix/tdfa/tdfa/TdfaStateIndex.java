package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** State interning/dedup: signature probing (ProbeKey vs DfaStateKey),
 *  class-signature canonicalization, register-bijection mapping (tryMap) and
 *  addState. Extracted verbatim from TdfaCompiler (2026-09 god-file split);
 *  the {@code owner} back-reference carries the shared compiler state. */
final class TdfaStateIndex {
    final TdfaCompiler owner;

    /** Hash-consed state index: canonical signature → candidate bucket. */
    Map<DfaStateKey, StateBucket> stateIndex = new HashMap<>();

    TdfaStateIndex(TdfaCompiler owner) { this.owner = owner; }

        /** Shared per-addState "tag has history" bitsets: computed once for
         *  the incoming closure, reused across all tryMap candidates of that
         *  attempt (the per-candidate recompute zeroed and refilled every
         *  config's bits — 8% of cliff-compile time in JFR). */
        private long[][] hasHistShared;
        /** Primitive register bijection scratch for tryMap (replaces the boxed
         *  HashMap pair): mappings stamped with per-attempt epochs. */
        private int[] mapNewToOld, mapOldToNew, epochNew, epochOld;
        private int[] stampedRegs;
        private int stamp;
        /** Per-state class signatures (null for tagless), parallel to states. */
        private final List<int[]> stateClassIds = new ArrayList<>();
        /** Class signature scratch for the pending closure (copied on append). */
        private int[] classScratch;
        private int pendingCanonLen;
        /** Epoch-stamped register → class-id map for canonSignature (primitive:
         *  the boxed HashMap made each work-meter tick so expensive the fuzz
         *  watchdog fired before the budget could). */
        private int[] canonKeyStamp, canonKeyClass;
        private int canonEpoch;

        static final class AddResult { final int targetId; final int[] ops; AddResult(int t, int[] o) { targetId=t; ops=o; } }

        /** Reusable lookup key for {@code stateIndex}: sig/hash reassigned per probe.
         *  Used ONLY for {@code get()} against the immutable stored {@link DfaStateKey}s —
         *  never inserted — so single-threaded reassignment is safe. Saves the
         *  ~2 KB sig-array + key allocation on every HIT (the dominant case on
         *  large determinizations: ~1.4 M lookups on the 234 K-state bomb). */
        private final ProbeKey probe = new ProbeKey();
        /** Scratch sig storage behind {@link #probe}; grown as needed, reused across calls. */
        private int[] probeSig = new int[64];

        /** Mutable lookup twin of {@link DfaStateKey}; equals() accepts stored keys. */
        private static final class ProbeKey {
            int[] sig;
            int len;
            int hash;
            @Override public boolean equals(Object o) {
                if (!(o instanceof DfaStateKey)) return false;
                DfaStateKey k = (DfaStateKey) o;
                return k.sig.length == len && rangeEquals(sig, 0, len, k.sig, 0, len);
            }
            @Override public int hashCode() { return hash; }
        }

        /** Fill the reusable probe key with the ORDER-EXACT signature of {@code configs}.
         *  tryMap only ever merges closures with identical ordered (state, l)
         *  sequences (its first phase compares element i to element i), so the
         *  index key must discriminate by arrival order: the former canonical
         *  (state-sorted) key admitted every permutation of the same multiset,
         *  and tryMap linearly rejected them — the dominant compile cliff
         *  (nested counted repetitions produce many arrival orders of one
         *  multiset; buckets grew into the hundreds and every addState
         *  rescanned them with Arrays.equals over each l). Order-exact keys
         *  admit exactly the candidates that can pass tryMap's first phase;
         *  buckets hold only genuine register-permutation variants. */
        private void fillKeySig(List<Config> configs) {
            int n = configs.size();
            int total = 0;
            if (owner.tags == 0) {
                // dense tagless sig: (state, emptyMask[, pri]) — l is always empty
                for (int i = 0; i < n; i++) total += 2 + (owner.longest ? 1 : 0);
            } else {
                // l enters the signature as its HASH-CONSED ID — one int per
                // config instead of the full history content (interning makes
                // id equality exact content equality).
                for (int i = 0; i < n; i++) total += 3 + (owner.longest ? 1 : 0);
            }
            if (probeSig.length < total) probeSig = new int[Math.max(total, probeSig.length * 2)];
            int j = 0;
            for (int i = 0; i < n; i++) {
                Config c = configs.get(i);
                probeSig[j++] = c.state;
                if (owner.tags != 0) probeSig[j++] = c.l;
                probeSig[j++] = c.emptyMask;
                if (owner.longest) probeSig[j++] = c.pri;
            }
            probe.sig = probeSig;
            probe.len = total;
            int h = 1;
            for (int i = 0; i < total; i++) h = 31 * h + probeSig[i];
            probe.hash = h;
            // Work meter: sig fill/copy/hash is O(sum |l|) real work — the
            // dominant cost on history-bloated compiles. Ticking per 64 ints
            // keeps the tick rate proportional to that work so the work budget
            // still bounds adversarial wall time (it was calibrated when the
            // interning scans dominated; those are gone).
            owner.meter.tick(total >>> 6);
        }

        /** Same-sequence DFA states. Tagged buckets partition members by
         *  register-slice class signature (see addState); tagless buckets keep
         *  a flat list — every member is merge-equivalent. */
        static final class StateBucket {
            int[] members;                       // tagless
            final HashMap<Long, int[]> byClass = new HashMap<>();  // tagged
        }

        /** Canonical flat signature: the register value at every history-free
         *  (config, tag) position, renumbered by first appearance (config-major,
         *  tag ascending). A bijection M with M(rn_p) = ro_p for all positions
         *  exists IFF the two closures' equality patterns over positions match
         *  (rn_p == rn_q ⟺ ro_p == ro_q) — which is exactly equality of these
         *  canonical arrays. So hash-bucketing by this form is an EXACT
         *  compatibility filter: no viable candidate is ever skipped, and any
         *  hash-matched candidate passes tryMap's bijection phase by
         *  construction (only its ops-rewrite can still fail). */
        private int[] canonSignature(List<Config> configs, long[][] hasHist) {
            int n = configs.size();
            int max = n * owner.tags;
            if (classScratch == null || classScratch.length < max) {
                // Slack growth: closure sizes creep up one config at a time, so
                // exact sizing reallocated on nearly every addState (18% of all
                // fuzzer allocation). Doubling amortizes to O(log) per compile.
                classScratch = new int[Math.max(max, (classScratch == null ? 32 : classScratch.length) * 2)];
            }
            if (canonKeyStamp == null || canonKeyStamp.length < owner.nextReg) {
                int cap = Math.max(64, Integer.highestOneBit(Math.max(1, owner.nextReg) - 1) << 1);
                canonKeyStamp = new int[cap];
                canonKeyClass = new int[cap];
                canonEpoch = 0;
            }
            int epoch = ++canonEpoch;
            int next = 0, k = 0;
            for (int i = 0; i < n; i++) {
                Config c = configs.get(i);
                long[] bits = hasHist[i];
                for (int t = 0; t < owner.tags; t++) {
                    owner.meter.tick();
                    if ((bits[t >>> 6] >>> (t & 63) & 1L) != 0) continue;
                    int r = c.regs[t];
                    if (canonKeyStamp[r] != epoch) { canonKeyStamp[r] = epoch; canonKeyClass[r] = next++; }
                    classScratch[k++] = canonKeyClass[r];
                }
            }
            pendingCanonLen = k;
            return classScratch;
        }

        /** Stored class-signatures, one per canon class (canonHash -> the
         *  detached copy). Canon-equal states share the stored array — it is
         *  strictly read-only downstream (compared via rangeEquals), and
         *  merge-heavy patterns stop paying a detach copy per state. On a
         *  64-bit fold collision (two distinct classes, same hash) the loser
         *  just copies per state — correctness is unaffected. */
        private final java.util.HashMap<Long, int[]> canonByClass = new java.util.HashMap<>();

        int[] dedupeCanon(int[] scratch, int len, long hash) {
            int[] stored = canonByClass.get(hash);
            if (stored != null && stored.length == len && rangeEquals(scratch, 0, len, stored, 0, len)) {
                return stored;
            }
            int[] copy = Arrays.copyOf(scratch, len);
            canonByClass.put(hash, copy);
            return copy;
        }

        private static long foldClass(int[] ids, int len) {
            long h = 1;
            for (int i = 0; i < len; i++) h = h * 0x100000001B3L + ids[i];
            return h;
        }

        AddResult addState(List<Config> configs, int[] ops, List<Config> seed) {
            owner.meter.tick();
            fillKeySig(configs);
             // ProbeKey vs DfaStateKey is the deliberate asymmetric probe
             // pattern: get() invokes probe.equals(storedKey), which accepts
             // stored keys; ProbeKey is never stored. See ProbeKey.
             @SuppressWarnings("CollectionIncompatibleType")
             StateBucket candidates = stateIndex.get(probe);

            // Canonical class signature for TAGGED closures — computed for
            // EVERY state, not just probe hits. The prior code filled
            // pendingClass/pendingClassHash only when a same-shape candidate
            // existed, so a first-of-shape state stored the PREVIOUS closure's
            // class (or null/0L for the very first state): a later identical
            // closure then missed the byClass probe entirely — permanent
            // missed merges, state-count/cap pressure [review P1 #1].
            int[] canon = null;
            long canonHash = 0;
            if (owner.tags > 0) {
                // Shared has-history bitsets for this closure: consumed by
                // canonSignature below AND by every tryMap of this attempt.
                int words = (owner.tags + 63) >>> 6;
                if (hasHistShared == null || hasHistShared.length < configs.size()) {
                    // Ragged: every row is replaced by a hist-cache ref right
                    // below, so new long[rows][words] allocated words*rows junk
                    // longs per growth. words is constant for this compile.
                    hasHistShared = new long[Math.max(configs.size(),
                            (hasHistShared == null ? 16 : hasHistShared.length) * 2)][];
                }
                for (int i = 0; i < configs.size(); i++) {
                    // Per-history-id cached bitsets (HistTable.bits): no fill,
                    // no content rescan.
                    hasHistShared[i] = owner.hist.bits(configs.get(i).l, words);
                }
                int[] scratch = canonSignature(configs, hasHistShared);
                int canonLen = pendingCanonLen;
                canonHash = TdfaCompiler.mix(foldClass(scratch, canonLen));
                canon = dedupeCanon(scratch, canonLen, canonHash);
            }

            if (candidates != null) {
                // Order-exact signature: candidates have the identical ordered
                // (state, l) sequence; only their register assignment can differ.
                // Tagged buckets are further partitioned by CLASS SIGNATURE:
                // each config's regs slice over history-free tags, canonically
                // numbered by first appearance. A bijection can only exist when
                // slice-equality aligns (slice_i == slice_j ⟺ oslice_i ==
                // oslice_j for all i,j — otherwise the pair map is ill-defined
                // or non-injective), so the attempt visits only class-compatible
                // members instead of rescanning the whole bucket — that rescan
                // was the dominant compile cliff on permutation-heavy patterns
                // (78% of wall time in JFR).
                if (owner.tags == 0) {
                    for (int cand : candidates.members) {
                        int[] mapped = tryMap(configs, owner.states.get(cand), owner.packedKernels.get(cand), ops);
                        if (mapped != null) return new AddResult(cand, mapped);
                    }
                } else {
                    int[] compatibles = candidates.byClass.get(canonHash);
                    if (compatibles != null && compatibles.length > 0) {
                        // Canon-equal members are interchangeable: the bijection
                        // succeeds by construction, and ops-rewrite coverage
                        // depends only on the ATTEMPT's registers — so success
                        // or failure (and the merged-into choice) is identical
                        // for every member. One probe suffices; scanning all
                        // canon-equal members was the residual quadratic.
                        int cand = compatibles[0];
                        int[] stored = stateClassIds.get(cand);
                        if (stored != null && stored.length == canon.length
                                && rangeEquals(canon, 0, canon.length, stored, 0, stored.length)) {
                            int[] mapped = tryMap(configs, owner.states.get(cand), owner.packedKernels.get(cand), ops);
                            if (mapped != null) return new AddResult(cand, mapped);
                            // ops-rewrite failed: outcome is member-independent,
                            // fall through to append a new state.
                        }
                    }
                }
                // All same-sequence states failed the register bijection: this
                // closure genuinely needs a new DFA state. Fall through.
            }
            int id = owner.states.size();
            owner.states.add(configs);
            owner.packedKernels.add(null);
            // Seeds are consumed ONLY by the Perl-mode stopOnAccept computation,
            // and only for ACCEPTING states (compile() line ~663 gates on
            // anyAccept). Retaining them for all states cost ~22 M extra Config
            // objects on the 234 K-state bounded-repeat determinization — a
            // third of all live Configs — for zero readers. Null for the rest.
            boolean isAccept = false;
            for (Config c : configs) {
                if (c.state == owner.nfa.accept) { isAccept = true; break; }
            }
            if (!owner.longest && isAccept) {
                if (owner.tags == 0) {
                    // tagless: consumers only read the seed STATES — pack them
                    int[] seedStates = new int[seed.size()];
                    for (int i = 0; i < seed.size(); i++) seedStates[i] = seed.get(i).state;
                    owner.stateSeeds.add(seedStates);
                } else {
                    owner.stateSeeds.add(seed);
                }
            } else {
                owner.stateSeeds.add(null);
            }
            // Own class for tagged states, null for tagless (never read there).
            stateClassIds.add(canon);
            if (candidates == null) {
                StateBucket fresh = new StateBucket();
                if (owner.tags == 0) fresh.members = new int[]{id};
                else fresh.byClass.put(canonHash, new int[]{id});
                stateIndex.put(new DfaStateKey(Arrays.copyOf(probe.sig, probe.len)), fresh);
            } else if (owner.tags == 0) {
                candidates.members = appendInt(candidates.members, id);
            } else {
                StateBucket b = candidates;
                // Only the first member of a canon-equal class is ever probed
                // (see above) — don't grow the list.
                b.byClass.putIfAbsent(canonHash, new int[]{id});
            }
            owner.builders.add(new DfaStateBuilder(id));
            if (isAccept) owner.accept.set(id);
            owner.kernelsTotal += configs.size();
            if (owner.states.size() > owner.maxStates || owner.kernelsTotal > owner.maxKernelsTotal) {
                throw new IllegalStateException("pattern too large: TDFA determinization budget exceeded ("
                        + owner.states.size() + " states, kernel total " + owner.kernelsTotal + ", ticks " + owner.meter.spent()
                        + "; caps " + owner.maxStates + " states / " + owner.maxKernelsTotal
                        + " — raise -Dtdfa.max.states / -Dtdfa.max.kernels)");
            }
            return new AddResult(id, ops);
        }

        static int[] appendInt(int[] arr, int v) {
            int[] out = new int[arr.length + 1];
            System.arraycopy(arr, 0, out, 0, arr.length);
            out[arr.length] = v;
            return out;
        }

        /** Java 8 floor: Arrays.equals range overload is Java 9+. */
        static boolean rangeEquals(int[] a, int aFrom, int aTo, int[] b, int bFrom, int bTo) {
            if (aTo - aFrom != bTo - bFrom) return false;
            for (int i = aFrom, j = bFrom; i < aTo; i++, j++) {
                if (a[i] != b[j]) return false;
            }
            return true;
        }

        /**
         * Attempt to map a candidate closure to an existing state's closure by registering
         * a bijection on their register vectors. Returns rewritten ops if mapping succeeds,
         * null otherwise. Implements paper §3 {@code map} function.
         *
         * <p>Callers index closures by the ORDER-EXACT (state, l, emptyMask)
         * signature, so every candidate here already has the identical ordered
         * sequence — the former element-wise state/Arrays.equals(l) phase is
         * implied by DfaStateKey.equals and has been deleted (it cost a full
         * l-comparison sweep per candidate on permutation-heavy patterns).
         * The tagless branch still checks element-wise states only because
         * its callers may pass closures from unindexed paths.
         */
        int[] tryMap(List<Config> newConfigs, List<Config> oldConfigs, int[] oldPacked, int[] ops) {
            int size = newConfigs.size();
            if (oldPacked != null ? oldPacked.length != size * 2 : oldConfigs.size() != size) return null;
            // Tagless patterns: no registers exist, so the (empty) bijection is
            // the identity and ops rewrite is a no-op — but the element-wise
            // ORDER check below is still semantically load-bearing: two closures
            // with the same shape-key (canonical state-sorted multiset) can have
            // DIFFERENT DFS arrival orders, and in Perl mode arrival order IS
            // priority (stepOnSymbol suppression). Refusing to merge those is
            // what the pre-fast-path code did; keep it.
            if (owner.tags == 0) {
                for (int i = 0; i < size; i++) {
                    int oldState = oldPacked != null ? oldPacked[i * 2] : oldConfigs.get(i).state;
                    if (newConfigs.get(i).state != oldState) return null;
                }
                return ops;
            }
            // Build register bijection M: newReg -> oldReg, M': oldReg -> newReg.
            // Primitive arrays with epoch stamps replace the former boxed
            // HashMaps — this loop was the dominant compile cost on
            // permutation-heavy patterns (32% getNode + 7% putVal in JFR).
            // Register values come from the GLOBAL register allocator (nextReg
            // grows during compilation; configs' regs arrays only cover the
            // tags they carry) — size scratch by the current universe.
            int numRegs = owner.nextReg;
            if (mapNewToOld == null || mapNewToOld.length < numRegs) {
                int cap = Math.max(64, Integer.highestOneBit(Math.max(1, numRegs) - 1) << 1);
                mapNewToOld = new int[cap];
                mapOldToNew = new int[cap];
                epochNew = new int[cap];
                epochOld = new int[cap];
                stamp = 0;
            }
            stamp++;      // fresh epoch for this attempt
            int[] m = mapNewToOld, mp = mapOldToNew;
            int[] eN = epochNew, eO = epochOld;
            // Stamped new-side registers, for the O(pairs) remaining-pairs scan
            // below — the register universe is global and grows with the DFA
            // (tens of thousands), so scanning it per attempt was a cliff.
            // Each (config, tag) pair contributes at most one register.
            int maxPairs = size * owner.tags;
            if (stampedRegs == null || stampedRegs.length < maxPairs) {
                stampedRegs = new int[Math.max(maxPairs, (stampedRegs == null ? 8 : stampedRegs.length) * 2)];
            }
            int stamped = 0;
            // "Tag has transition-op history" bitsets: one pass over each
            // config's history sequence replaces the former tags × full-
            // sequence history() rescans per (config, tag) — the top
            // compile-time hot spot (410 of 678 overnight hang records were
            // this loop; the full history ARRAY was built to test only its
            // existence). Computed by the CALLER once per addState — shared
            // across all candidates of this attempt.
            long[][] hasHist = hasHistShared;
            for (int i = 0; i < size; i++) {
                Config cn = newConfigs.get(i), co = oldConfigs.get(i);
                long[] bits = hasHist[i];
                for (int t = 0; t < owner.tags; t++) {
                    if (owner.meter != null) owner.meter.tick();   // per (config, tag): the bijection's real unit
                    if ((bits[t >>> 6] >>> (t & 63) & 1L) != 0) continue; // tag is set by transition op
                    int rn = cn.regs[t], ro = co.regs[t];
                    // A register may be new-side of one tag and old-side of
                    // another, so the two sides carry separate epoch arrays.
                    boolean mn = eN[rn] == stamp, mo = eO[ro] == stamp;
                    if (!mn && !mo) {
                        m[rn] = ro; eN[rn] = stamp;
                        mp[ro] = rn; eO[ro] = stamp;
                        stampedRegs[stamped++] = rn;
                    } else if (!mn || !mo || m[rn] != ro || mp[ro] != rn) {
                        return null;
                    }
                }
            }
            // Rewrite ops: replace each op's dst with M[dst]. Each consumed
            // pair is unstamped (the HashMap remove), so a second op hitting
            // the same dst fails — bijection violations, as before.
            List<int[]> rewritten = new ArrayList<>();
            for (int i = 0; i < ops.length; i += 3) {
                owner.meter.tick();
                int op = ops[i], dst = ops[i + 1], src = ops[i + 2];
                if (eN[dst] != stamp) return null;
                int mapped = m[dst];
                if (eO[mapped] != stamp || mp[mapped] != dst) return null;
                rewritten.add(new int[]{op, mapped, src});
                eN[dst] = 0;
                eO[mapped] = 0;
            }
            // Prepend copy ops for remaining bijection pairs (stamped order —
            // first-stamp ascending; the pairs are mutually commutative, order
            // only affects the emitted op sequence deterministically).
            // M maps newReg -> oldReg. Existing state expects tag values in its oldReg slots;
            // the new state's transition just wrote them into newReg slots. Copy oldReg <- newReg.
            for (int p = 0; p < stamped; p++) {
                int newReg = stampedRegs[p];
                if (eN[newReg] != stamp) continue;
                int oldReg = m[newReg];
                if (eO[oldReg] != stamp) continue;
                if (newReg != oldReg) rewritten.add(0, new int[]{Tdfa.OP_COPY, oldReg, newReg});
            }
            // Topological sort: copy ops must come before any op that reads their src.
            owner.topologicalSort(rewritten);
            return owner.variants.flatten(rewritten);
        }
}
