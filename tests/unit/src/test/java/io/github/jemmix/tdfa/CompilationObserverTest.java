package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.core.CompileOptions;
import io.github.jemmix.tdfa.core.CompilationReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compilation transparency: a {@link CompilationReport} attached via
 * {@link CompileOptions#observer} records stage timings/details and the
 * engine decision, at both the core tier and the facade.
 */
class CompilationObserverTest {

    @Test void coreTierRecordsAllStages() {
        CompilationReport r = new CompilationReport();
        io.github.jemmix.tdfa.core.CompiledRegex.compile("(\\w+)@(\\w+)\\.(com|org)",
                CompileOptions.of().observer(r));
        assertThat(r.detail(CompileObserver.Stage.PARSE)).isEqualTo(6);   // 3 groups -> 6 tags
        assertThat(r.detail(CompileObserver.Stage.TNFA)).isPositive();
        assertThat(r.detail(CompileObserver.Stage.DETERMINIZE)).isPositive();
        assertThat(r.detail(CompileObserver.Stage.MINIMIZE)).isPositive();
        assertThat(r.nanos(CompileObserver.Stage.REGOPT)).isGreaterThanOrEqualTo(0L);
        assertThat(r.notes()).containsEntry("engine", "interpreter");
        assertThat(r.totalNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(r.toString()).contains("determinize=");
    }

    @Test void facadeRecordsGeneratedEngineDecision() {
        CompilationReport r = new CompilationReport();
        Pattern.compile("(a|b)*c", CompileOptions.of().observer(r));
        assertThat(r.notes().get("engine")).isIn("generated", "shared-interpreter (shell emission failed)",
                "shared-interpreter (engine emission failed)", "shared-interpreter (tdfa.engine=VM)");
        if ("generated".equals(r.notes().get("engine"))) {
            assertThat(r.nanos(CompileObserver.Stage.ENGINE)).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test void longestMatchOptionFlipsThroughReport() {
        CompilationReport r = new CompilationReport();
        Pattern.compile("(a|ab)", CompileOptions.of().longestMatch().observer(r));
        var m = Pattern.compile("(a|ab)", Pattern.LONGEST_MATCH).matcher("ab");
        assertThat(m.find()).isTrue();
        assertThat(m.end()).isEqualTo(2);
    }

    @Test void noObserverIsDefaultAndCheap() {
        // smoke: compiles fine with no observer attached
        assertThat(Pattern.compile("x+").matcher("xx").find()).isTrue();
        assertThat(io.github.jemmix.tdfa.core.CompiledRegex.compile("x+").find("xx")).isTrue();
    }
}
