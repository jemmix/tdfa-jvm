package io.github.jemmix.tdfa.tdfa;

    /** Wrapper around a slice of an int[] for use as a HashMap key with value equality. */
    final class OpSeq {
        final int[] arr;
        final int off;
        final int end;  // exclusive
        final int hash;
        OpSeq(int[] arr, int off, int end) {
            this.arr = arr; this.off = off; this.end = end;
            int h = 1;
            for (int i = off; i < end; i++) h = h * 31 + arr[i];
            this.hash = h;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof OpSeq)) return false;
            OpSeq that = (OpSeq) o;
            int len = end - off;
            if (len != that.end - that.off) return false;
            for (int i = 0; i < len; i++) if (arr[off + i] != that.arr[that.off + i]) return false;
            return true;
        }
        @Override public int hashCode() { return hash; }
    }
