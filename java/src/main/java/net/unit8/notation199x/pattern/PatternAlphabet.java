package net.unit8.notation199x.pattern;

import java.util.Arrays;

/**
 * The characters a backslash before which the pattern language keeps for an escape: the letters and
 * the decimal digits, General_Category {@code L} and {@code Nd}, against the Unicode version the
 * data names.
 *
 * <p>Asked of the running JDK, the answer would move with whatever Unicode version that JDK
 * carries, and a pattern one reader read another would refuse. The version is the one every rule
 * here is pinned to, because the characters of a pattern are the scalar values of text.
 */
final class PatternAlphabet {

    private PatternAlphabet() {}

    /** Whether a backslash before {@code codePoint} is kept for an escape: a letter or a decimal
     *  digit. */
    static boolean isKeptAfterABackslash(int codePoint) {
        int[] starts = PatternAlphabetTables.LETTERS_AND_DIGITS[0];
        int[] ends = PatternAlphabetTables.LETTERS_AND_DIGITS[1];
        int index = Arrays.binarySearch(starts, codePoint);
        if (index < 0) {
            index = -index - 2;
        }
        return index >= 0 && codePoint <= ends[index];
    }

    /** The Unicode version the classification is read against. */
    static String unicodeVersion() {
        return PatternAlphabetTables.UNICODE_VERSION;
    }
}
