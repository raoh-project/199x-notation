package net.unit8.notation199x;

/**
 * The code points with the Unicode {@code White_Space} property, as of Unicode 18.0.0: twenty-five
 * of them.
 *
 * <p>The set is enumerated rather than read off a platform table, so a Unicode update in the JVM
 * does not change it. It is neither {@link Character#isWhitespace}, which leaves out the no-break
 * spaces and takes in the four information separators, nor what {@link String#strip} scans by.
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
        return switch (codePoint) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
                 0x0020,
                 0x0085,
                 0x00A0,
                 0x1680,
                 0x2028, 0x2029,
                 0x202F,
                 0x205F,
                 0x3000 -> true;
            default -> codePoint >= 0x2000 && codePoint <= 0x200A;
        };
    }
}
