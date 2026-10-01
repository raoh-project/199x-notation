package net.unit8.notation199x;

/**
 * Text measured and ordered in Unicode scalar values.
 *
 * <p>A reader that measured or ordered text with {@link String#length} or {@link String#compareTo}
 * would be counting UTF-16 code units, which is what a JVM string holds and not what the text is
 * made of.
 *
 * <p>Every rule in this library is stated of text that is a sequence of scalar values. A
 * {@code java.lang.String} can hold half of a surrogate pair, which is not one, and
 * {@link #halfAPairAt} is how a caller asks before it takes text in. {@link #count} and
 * {@link #compare} do not ask.
 */
public final class ScalarValues {

    private ScalarValues() {}

    /**
     * Where {@code text} holds a surrogate that is not one half of a pair beside the other, or -1
     * where it holds none and so is a sequence of scalar values.
     *
     * @param text the text
     * @return the index, in UTF-16 units, of the first surrogate that is not half of a pair, or -1
     */
    public static int halfAPairAt(String text) {
        for (int at = 0; at < text.length(); at++) {
            char unit = text.charAt(at);
            if (Character.isHighSurrogate(unit) && at + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(at + 1))) {
                at++;
            } else if (Character.isSurrogate(unit)) {
                return at;
            }
        }
        return -1;
    }

    /**
     * How many scalar values {@code text} is made of.
     *
     * @param text the text, a sequence of scalar values
     * @return the number of scalar values
     */
    public static long count(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Where {@code a} stands against {@code b}: the first scalar value where they differ decides,
     * and where one is a prefix of the other the shorter is below.
     *
     * <p>Not {@link String#compareTo}, which orders UTF-16 code units. The two differ only where the
     * first unit apart begins a pair on one side and is in {@code U+E000..U+FFFF} on the other: a
     * pair stands for a scalar value above every unit, and its first unit is below those. So the
     * units are compared as they are, with that one range moved: a surrogate goes above every other
     * unit. Both sides share every unit before the first one apart, so where one of them is the
     * second half of a pair so is the other, and two second halves keep their order under the move.
     *
     * @param a one text, a sequence of scalar values
     * @param b another
     * @return a negative number, zero or a positive number as {@code a} stands below, with or
     *         above {@code b}
     */
    public static int compare(String a, String b) {
        int shared = Math.min(a.length(), b.length());
        for (int at = 0; at < shared; at++) {
            char x = a.charAt(at);
            char y = b.charAt(at);
            if (x != y) {
                return Integer.compare(rank(x), rank(y));
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    /** Where a unit stands among units once a surrogate is put above {@code U+E000..U+FFFF}. */
    private static int rank(char unit) {
        if (unit >= 0xE000) {
            return unit - 0x800;
        }
        return Character.isSurrogate(unit) ? unit + 0x2000 : unit;
    }
}
