package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization round-trips of the facade Pattern (review Phase C / P1 #17):
 * the SerialProxy carries pattern + flags + pinned-provider identity, and
 * recompiles on read — generated per-pattern classes live in child loaders
 * that cannot cross processes. The pinned provider must SURVIVE the
 * round-trip (previously it silently recompiled against the reader's
 * default tables — the reproducibility bug the pinned-provider API exists
 * to prevent).
 */
class PatternSerializationTest {

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T obj) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(obj);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void generatedShellRoundTripsAndMatches() throws Exception {
        // Default compile = ASM tier: a generated shell (writeReplace is
        // inherited from TDFAPattern — the proxy replaces the shell).
        Pattern p = Pattern.compile("(?<word>\\w+)-(\\d+)");
        Pattern copy = roundTrip(p);
        assertThat(copy).isEqualTo(p);   // re2j semantics: pattern + flags
        PatternMatcher m = copy.matcher("order-77 and id-42");
        assertThat(m.find()).isTrue();
        assertThat(m.group("word")).isEqualTo("order");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isEqualTo("42");
    }

    @Test
    void interpreterRoundTrips() throws Exception {
        Pattern p = Pattern.compile("a(b+)c", 0, TdfaRunner::new);
        Pattern copy = roundTrip(p);
        PatternMatcher m = copy.matcher("xabbbcz");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("bbb");
    }

    @Test
    void pinnedProviderSurvivesRoundTrip() throws Exception {
        Pattern p = Pattern.compile("\\p{L}+", 0, null, Unicode6_0.provider());
        assertThat(((TDFAPattern) p).unicodeProvider()).isNotNull();

        Pattern copy = roundTrip(p);

        // The pin survived: same provider class, resolvable by the proxy's
        // convention (static provider()), and semantics follow the pinned
        // tables.
        assertThat(((TDFAPattern) copy).unicodeProvider())
                .isEqualTo(Unicode6_0.provider());
        assertThat(copy.matcher("Gr\u00fc\u00dfe").matches()).isTrue();
        assertThat(copy.matcher("abc1").matches()).isFalse();
    }

    @Test
    void defaultProviderPatternReadsAgainstReadersDefault() throws Exception {
        // No pin on the wire: a default-compiled pattern recompiles against
        // the reading process's default provider (historical behavior).
        Pattern p = Pattern.compile("[a-z]+");
        Pattern copy = roundTrip(p);
        assertThat(((TDFAPattern) copy).unicodeProvider()).isNull();
        assertThat(copy.matcher("abc").matches()).isTrue();
    }
}
