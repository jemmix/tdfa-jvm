package io.github.jemmix.tdfa.regopt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * rename() fail-fast contract: every op-referenced register must be mapped by
 * compaction — an unmapped or out-of-range reference means a corrupted program
 * and must throw here, not surface as a runtime AIOOBE or silent capture
 * corruption (review P2: the old code silently skipped negative mappings while
 * its comment claimed detection).
 */
class OptimizeRenameTest {

    private static Cfg cfgWithOp(Cfg.Op op) {
        Cfg cfg = new Cfg(1, 1, 3);
        Cfg.Block b = new Cfg.Block();
        b.kind = Cfg.BLOCK_BASIC;
        b.ops.add(op);
        cfg.blocks.add(b);
        return cfg;
    }

    @Test
    void failsFastOnOutOfRangeRegister() {
        Cfg cfg = cfgWithOp(Cfg.Op.copy(0, 2)); // vmap below covers 0..1 only
        assertThatThrownBy(() -> Optimize.rename(cfg, new int[]{5, -1}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("unmapped register 2");
    }

    @Test
    void failsFastOnUnusedRegister() {
        Cfg cfg = cfgWithOp(Cfg.Op.setPos(1)); // vmap[1] = -1 (unused sentinel)
        assertThatThrownBy(() -> Optimize.rename(cfg, new int[]{0, -1}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("unmapped register 1");
    }

    @Test
    void renamesMappedRegistersBothSides() {
        Cfg cfg = cfgWithOp(Cfg.Op.copy(0, 1));
        Optimize.rename(cfg, new int[]{5, 7, -1});
        Cfg.Op op = cfg.blocks.get(0).ops.get(0);
        assertThat(op.dst).isEqualTo(5);
        assertThat(op.src).isEqualTo(7);
    }

    @Test
    void setOpsLeaveSrcUntouched() {
        Cfg cfg = cfgWithOp(Cfg.Op.setNil(0));
        Optimize.rename(cfg, new int[]{4, -1, -1});
        assertThat(cfg.blocks.get(0).ops.get(0).dst).isEqualTo(4);
    }
}
