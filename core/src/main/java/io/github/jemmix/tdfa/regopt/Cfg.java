package io.github.jemmix.tdfa.regopt;

import java.util.ArrayList;
import java.util.List;

/**
 * Control flow graph over a TDFA's register operations (BT22 §6.3).
 *
 * <p>The CFG models the dataflow of register values through the DFA. Nodes are
 * basic blocks (per-transition op lists) and final blocks (per-accepting-state
 * op lists). Arcs follow DFA reachability skipping zero-op transitions.
 *
 * <p>Each {@link Op} is one of:
 * <ul>
 *   <li>{@link #KIND_SET} with {@link Op#value} = {@link #VAL_POS} or {@link #VAL_NIL}
 *       — modeled uniformly; both are "set dst to a value" for dataflow purposes;</li>
 *   <li>{@link #KIND_COPY} ({@code dst <- src}).</li>
 * </ul>
 *
 * <p>The CFG is a pure data structure. Construction from {@code TdfaMaterializer}'s
 * builder list, and writeBack of optimized ops, live inline in {@code TdfaMaterializer}
 * (which has direct access to the package-private {@code DfaStateBuilder}/{@code Range}
 * types). The optimization passes ({@link Optimize}) operate on this data model.
 *
 * <p>Register layout invariant: working registers occupy {@code [0..W-1]}, final
 * registers occupy {@code [W..W+T-1]} where {@code T = tagCount} and {@code W = finalRegBase}.
 * This lets {@code MatchResult.tag(t)} read {@code regs[finalRegBase + t - 1]}
 * regardless of how aggressive the allocation was.
 */
public final class Cfg {
    // ---- Op kinds ----
    public static final int KIND_SET = 1;
    public static final int KIND_COPY = 2;

    // ---- Set values (for KIND_SET only) ----
    /**
     * Set dst to the current cursor position. <b>Invariant (soundness-critical
     * for {@link Optimize#interferenceAnalysis}):</b> within any single block,
     * every SET-pos op observes the <em>same</em> cursor position. The
     * interference analyzer conflates all SET-pos values into one sentinel
     * ({@code POS_VALUE}) and lets same-value registers share a slot — that
     * is only sound because a block's op list executes atomically at one DFA
     * transition/final boundary, where the cursor cannot move. A future op
     * source that mixes SET-pos ops at different positions inside one block
     * MUST split them into separate blocks (or the conflation will alias
     * registers holding different positions and silently corrupt captures).
     */
    public static final int VAL_POS = 1;

    public static final int VAL_NIL = 2; // set to NIL (-1)

    // ---- Block kinds ----
    public static final int BLOCK_BASIC = 1;
    public static final int BLOCK_FINAL = 2;

    /** A single register operation. Ops are mutated in place by the optimization passes. */
    public static final class Op {
        public int kind; // KIND_*
        public int dst;
        public int src; // for KIND_COPY
        public int value; // for KIND_SET: VAL_POS or VAL_NIL

        public Op(int kind, int dst, int src, int value) {
            this.kind = kind;
            this.dst = dst;
            this.src = src;
            this.value = value;
        }

        public static Op setPos(int dst) {
            return new Op(KIND_SET, dst, 0, VAL_POS);
        }

        public static Op setNil(int dst) {
            return new Op(KIND_SET, dst, 0, VAL_NIL);
        }

        public static Op copy(int dst, int src) {
            return new Op(KIND_COPY, dst, src, 0);
        }

        @Override
        public String toString() {
            switch (kind) {
                case KIND_SET :
                    return "r" + dst + "=" + (value == VAL_POS ? "pos" : "nil");
                case KIND_COPY :
                    return "r" + dst + "=r" + src;
                default :
                    return "r" + dst + "=?<" + kind + ">";
            }
        }
    }

    /** A basic / final block: an op list plus successor block indices. */
    public static final class Block {
        public int kind;
        /** DFA state this block belongs to. For BASIC: source state of the transition.
         *  For FINAL: the accepting state. */
        public int stateId;

        public final List<Op> ops = new ArrayList<>();
        /** Successor block indices in {@link Cfg#blocks}. */
        public final List<Integer> successors = new ArrayList<>();
        /** Back-link so writeBack can find the right slot. For BASIC blocks: range index
         *  within the state's builder. For FINAL blocks: -1 (the state's finalOpsArr). */
        public int rangeIndex;
        /**
         * BASIC blocks of a partial-whole side-table entry: rangeIndex
         * indexes the state builder's wholeRanges list, and write-back
         * targets that list (the whole walk's op block, not the pruned
         * one).
         */
        public boolean whole;
    }

    public final List<Block> blocks = new ArrayList<>();
    public final int tagCount;
    /** Initial register count (nextReg at end of determinization). */
    public final int initialRegCount;
    /** Current register count (after optimization passes; starts at initialRegCount). */
    public int regCount;
    /** Index of the first final register. Working registers are [0..finalRegBase-1];
     *  final registers are [finalRegBase..finalRegBase+tagCount-1]. */
    public int finalRegBase;
    /** Diagnostic: ops removed by DCE (0 if none). */
    public int dceRemovedOps;

    public Cfg(int tagCount, int initialRegCount) {
        this.tagCount = tagCount;
        this.initialRegCount = initialRegCount;
        this.regCount = initialRegCount;
        this.finalRegBase = tagCount; // pre-optimimization layout: working [0..T-1], final [T..2T-1], extras [2T..]
    }

    public Block newBlock(int kind, int stateId, int rangeIndex) {
        Block b = new Block();
        b.kind = kind;
        b.stateId = stateId;
        b.rangeIndex = rangeIndex;
        blocks.add(b);
        return b;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CFG: ").append(blocks.size()).append(" blocks, ").append(regCount).append(" regs (final base ")
            .append(finalRegBase).append(")\n");
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            sb.append("  B").append(i).append(" [")
                .append(b.kind == BLOCK_BASIC ? "basic" : b.kind == BLOCK_FINAL ? "final" : "fallback")
                .append(" state=").append(b.stateId).append("]: ");
            sb.append(b.ops).append(" -> succ ").append(b.successors).append("\n");
        }
        return sb.toString();
    }
}
