package io.github.jemmix.tdfa.tdfa;

import java.util.Arrays;

/**
 * int[] wrapper for HashMap keys with value equality (avoids storing Strings).
 */
final class SigKey {
    final int[] sig;
    final int hash;

    SigKey(int[] sig) {
        this.sig = sig;
        this.hash = Arrays.hashCode(sig);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SigKey && Arrays.equals(sig, ((SigKey) o).sig);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
