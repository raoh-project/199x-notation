package net.unit8.notation199x;

/**
 * The code points with the Unicode {@code White_Space} property, as of Unicode 18.0.0: twenty-five
 * of them.
 *
 * <p>The set is generated from the database's {@code PropList.txt} rather than read off a platform
 * table, so a Unicode update in the JVM does not change it. It is neither
 * {@link Character#isWhitespace}, which leaves out the no-break spaces and takes in the four
 * information separators, nor what {@link String#strip} scans by.
 */
public final class WhiteSpace {

    private WhiteSpace() {}

    /**
     * Whether {@code codePoint} has the {@code White_Space} property.
     *
     * @param codePoint a code point
     * @return whether it has the property
     */
    public static boolean contains(int codePoint) {
        int[] starts = WhiteSpaceTables.WHITE_SPACE[0];
        int[] ends = WhiteSpaceTables.WHITE_SPACE[1];
        // A handful of ranges, so a scan in order is as quick as a search.
        for (int i = 0; i < starts.length && starts[i] <= codePoint; i++) {
            if (codePoint <= ends[i]) {
                return true;
            }
        }
        return false;
    }
}
