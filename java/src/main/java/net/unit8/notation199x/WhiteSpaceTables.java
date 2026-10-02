package net.unit8.notation199x;

/**
 * The {@code White_Space} set {@link WhiteSpace} reads, as of Unicode 18.0.0.
 *
 * <p>Generated from Unicode 18.0.0's {@code PropList.txt} ({@code https://www.unicode.org/Public/18.0.0/ucd/})
 * by {@code gen/Generate.java}. DO NOT EDIT — regenerate on a Unicode version bump with
 * {@code java gen/Generate.java ucd/<version>}.
 *
 * <p>SHA-256 of PropList.txt as downloaded: {@code f438f532e8737bb8a2702126cdf9c4af5e357c58c7acf9d9eb2fc7c1a1d955d6}
 */
final class WhiteSpaceTables {

    private WhiteSpaceTables() {}

    /** Decodes a "{@code <start>-<end> ...}" string of sorted, non-overlapping inclusive
     *  hex ranges into the parallel {@code [starts, ends]} arrays a lookup searches. */
    private static int[][] decodeRanges(String data) {
        String[] tokens = data.split(" ");
        int[] starts = new int[tokens.length];
        int[] ends = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            int dash = tokens[i].indexOf('-');
            starts[i] = Integer.parseInt(tokens[i].substring(0, dash), 16);
            ends[i] = Integer.parseInt(tokens[i].substring(dash + 1), 16);
        }
        return new int[][] {starts, ends};
    }

    /** {@code White_Space}, as sorted non-overlapping inclusive ranges: index 0 is starts,
     *  index 1 is ends. */
    static final int[][] WHITE_SPACE = decodeRanges("9-D 20-20 85-85 A0-A0 1680-1680 2000-200A 2028-2028 2029-2029 202F-202F 205F-205F 3000-3000");
}
