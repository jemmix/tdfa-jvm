package io.github.jemmix.tdfa.cfg;

import java.util.ArrayList;
import java.util.List;

/**
 * Control flow graph over a TDFA's register operations (BT22 §6.3).
 *
 * <p>The CFG models the dataflow of register values through the DFA. Nodes are
 * basic blocks (per-transition op lists), final blocks (per-accepting-state op lists),
 * and — once M3 lands — fallback blocks. Arcs follow DFA reachability skipping
 * zero-op transitions.
 *
 * <p>Each {@link Op} is one of:
 * <ul>
 *   <li>{@link #KIND_SET} with {@link #value} = {@link #VAL_POS} or {@link #VAL_NIL}
 *       — modeled uniformly; both are "set dst to a value" for dataflow purposes;</li>
 *   <li>{@link #KIND_COPY} ({@code dst <- src});</li>
 *   <li>{@link #KIND_APPEND} ({@code dst <- dst · src}) — multi-valued tags, not yet
 *       supported but stubbed for algorithmic completeness.</li>
 * </ul>
 *
 * <p>The CFG is a pure data structure. Construction from {@code Tdfa.Compiler}'s
 * builder list, and writeBack of optimized ops, live inline in {@code Tdfa.Compiler}
 * (which has direct access to its nested {@code DfaStateBuilder}/{@code Range}
 * types). The optimization passes ({@link Optimize}) operate on this data model.
 *
 * <p>Register layout invariant: working registers occupy {@code [0..W-1]}, final
 * registers occupy {@code [W..W+T-1]} where {@code T = tagCount} and {@code W = finalRegBase}.
 * This lets {@code MatchResult.tag(t)} read {@code regs[finalRegBase + t - 1]}
 * regardless of how aggressive the allocation was.
 */
public final class Cfg {
    // ---- Op kinds ----
    public static final int KIND_SET    = 1;
    public static final int KIND_COPY   = 2;
    public static final int KIND_APPEND = 3;  // reserved for multi-valued tags (not yet supported)

    // ---- Set values (for KIND_SET only) ----
    public static final int VAL_POS = 1;  // set to current cursor position
    public static final int VAL_NIL = 2;  // set to NIL (-1)

    // ---- Block kinds ----
    public static final int BLOCK_BASIC    = 1;
    public static final int BLOCK_FINAL    = 2;
    public static final int BLOCK_FALLBACK = 3;  // reserved for M3

    /** A single register operation. Ops are mutated in place by the optimization passes. */
    public static final class Op {
        public int kind;     // KIND_*
        public int dst;
        public int src;      // for KIND_COPY / KIND_APPEND
        public int value;    // for KIND_SET: VAL_POS or VAL_NIL

        public Op(int kind, int dst, int src, int value) {
            this.kind = kind; this.dst = dst; this.src = src; this.value = value;
        }
        public static Op setPos(int dst) { return new Op(KIND_SET, dst, 0, VAL_POS); }
        public static Op setNil(int dst) { return new Op(KIND_SET, dst, 0, VAL_NIL); }
        public static Op copy(int dst, int src) { return new Op(KIND_COPY, dst, src, 0); }

        @Override public String toString() {
            switch (kind) {
                case KIND_SET:  return "r" + dst + "=" + (value == VAL_POS ? "pos" : "nil");
                case KIND_COPY: return "r" + dst + "=r" + src;
                default:        return "r" + dst + "=r" + dst + "·r" + src;
            }
        }
    }

    /** A basic / final / fallback block: an op list plus successor block indices. */
    public static final class Block {
        public int kind;
        /** DFA state this block belongs to. For BASIC: source state of the transition.
         *  For FINAL: the accepting state. For FALLBACK: reserved. */
        public int stateId;
        public final List<Op> ops = new ArrayList<>();
        /** Successor block indices in {@link Cfg#blocks}. */
        public final List<Integer> successors = new ArrayList<>();
        /** Back-link so writeBack can find the right slot. For BASIC blocks: range index
         *  within the state's builder. For FINAL blocks: -1 (the state's finalOpsArr). */
        public int rangeIndex;
    }

    public final List<Block> blocks = new ArrayList<>();
    public final int tagCount;
    public final int groupCount;
    /** Initial register count (nextReg at end of determinization). */
    public final int initialRegCount;
    /** Current register count (after optimization passes; starts at initialRegCount). */
    public int regCount;
    /** Index of the first final register. Working registers are [0..finalRegBase-1];
     *  final registers are [finalRegBase..finalRegBase+tagCount-1]. */
    public int finalRegBase;

    public Cfg(int tagCount, int groupCount, int initialRegCount) {
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.initialRegCount = initialRegCount;
        this.regCount = initialRegCount;
        this.finalRegBase = tagCount;  // pre-optimimization layout: working [0..T-1], final [T..2T-1], extras [2T..]
    }

    public Block newBlock(int kind, int stateId, int rangeIndex) {
        Block b = new Block();
        b.kind = kind;
        b.stateId = stateId;
        b.rangeIndex = rangeIndex;
        blocks.add(b);
        return b;
    }

    /** Max register index + 1 across all ops (or {@code finalRegBase + tagCount} if no ops). */
    public int computeMaxReg() {
        int max = finalRegBase + tagCount;  // final registers always present
        for (Block b : blocks) {
            for (Op op : b.ops) {
                max = Math.max(max, op.dst + 1);
                if (op.kind == KIND_COPY || op.kind == KIND_APPEND) max = Math.max(max, op.src + 1);
            }
        }
        return max;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CFG: ").append(blocks.size()).append(" blocks, ")
          .append(regCount).append(" regs (final base ").append(finalRegBase).append(")\n");
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
