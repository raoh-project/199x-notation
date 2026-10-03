package net.unit8.notation199x;

/**
 * What a scan over a run of a string learns, held in one {@code long} so that a scan a conversion
 * makes for every run allocates nothing: where the run ends, a UTF-16 index, in the low 32 bits,
 * and how many of its code points are past the basic plane, two units each, in the high 32. The
 * caller then knows how many code points the run holds without reading it again.
 */
final class Scan {

    private Scan() {}

    /** The scan of a run that ends at {@code end} and holds {@code pairs} code points past the basic
     *  plane. */
    static long of(int end, int pairs) {
        return (long) pairs << 32 | end;
    }

    /** Where the run ends. */
    static int end(long scan) {
        return (int) scan;
    }

    /** How many code points the run from {@code start} holds. */
    static int codePoints(long scan, int start) {
        return end(scan) - start - (int) (scan >>> 32);
    }
}
