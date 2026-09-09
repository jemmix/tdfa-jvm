package io.github.jemmix.tdfa.tdfa;

    /** int[] wrapper for HashMap keys with value equality (avoids storing Strings). */
    final class SigKey {
        final int[] sig;
        final int hash;
        SigKey(int[] sig) { this.sig = sig; this.hash = java.util.Arrays.hashCode(sig); }
        @Override public boolean equals(Object o) {
            return o instanceof SigKey && java.util.Arrays.equals(sig, ((SigKey) o).sig);
        }
        @Override public int hashCode() { return hash; }
    }
