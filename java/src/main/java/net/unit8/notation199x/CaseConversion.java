package net.unit8.notation199x;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Unicode 18.0.0's default case conversion: the full mapping of {@code UnicodeData.txt} and
 * {@code SpecialCasing.txt}, with no locale or language tailoring.
 *
 * <p>Not {@link String#toLowerCase} and {@link String#toUpperCase}, which answer for the Unicode
 * version of the JDK they run on, and which decide a final sigma by word boundaries rather than by
 * Unicode's {@code Final_Sigma} condition.
 *
 * <p>The answer is the mapped text as the mapping writes it. A case mapping is not closed under
 * any normalization form, so a caller that holds its text in one normalizes the answer.
 */
public final class CaseConversion {

    private CaseConversion() {}

    /** {@code s} in lowercase. One code point can map to several, and no locale narrows it: a Greek
     *  capital sigma becomes the context-dependent final form only at the end of a cased run —
     *  {@code lowercase("ΟΣ")} is {@code "ος"} but {@code lowercase("ΟΣΑ")} is {@code "οσα"} — which
     *  is the one condition Unicode's default algorithm carries that is context rather than locale. */
    public static String lowercase(String s) {
        return mapCase(s, true);
    }

    /** {@code s} in uppercase: the same untailored full mapping, so one code point can widen to
     *  several ({@code uppercase("straße")} is {@code "STRASSE"}), and no locale narrows it back —
     *  Turkish {@code i} still becomes {@code I}, never {@code İ}. */
    public static String uppercase(String s) {
        return mapCase(s, false);
    }

    private static String mapCase(String s, boolean lower) {
        int[] cps = s.codePoints().toArray();
        StringBuilder out = new StringBuilder(cps.length);
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            int[] mapped = null;
            if (lower) {
                int[] finalSigmaMapped = lookup(CaseTables.FINAL_SIGMA, cp);
                if (finalSigmaMapped != null && isFinalSigmaContext(cps, i)) {
                    mapped = finalSigmaMapped;
                }
            }
            if (mapped == null) {
                mapped = lookup(lower ? CaseTables.LOWER : CaseTables.UPPER, cp);
            }
            if (mapped == null) {
                out.appendCodePoint(cp);
            } else {
                for (int m : mapped) {
                    out.appendCodePoint(m);
                }
            }
        }
        return out.toString();
    }

    /** Unicode's {@code Final_Sigma} condition: immediately preceded, skipping {@code Case_Ignorable}
     *  code points, by a {@code Cased} one, and NOT immediately followed, skipping the same way, by
     *  another {@code Cased} one. Scanned over the whole string's code points rather than a window,
     *  since what "immediately" skips over is itself defined by the property, not by a fixed count. */
    private static boolean isFinalSigmaContext(int[] cps, int at) {
        boolean precededByCased = false;
        for (int j = at - 1; j >= 0; j--) {
            if (isCaseIgnorable(cps[j])) {
                continue;
            }
            precededByCased = isCased(cps[j]);
            break;
        }
        if (!precededByCased) {
            return false;
        }
        for (int j = at + 1; j < cps.length; j++) {
            if (isCaseIgnorable(cps[j])) {
                continue;
            }
            return !isCased(cps[j]);
        }
        return true;
    }

    private static int @Nullable [] lookup(CaseTables.Mapping table, int cp) {
        int index = Arrays.binarySearch(table.codePoints(), cp);
        return index >= 0 ? table.mapped()[index] : null;
    }

    private static boolean isCased(int cp) {
        return inRanges(CaseTables.CASED, cp);
    }

    private static boolean isCaseIgnorable(int cp) {
        return inRanges(CaseTables.CASE_IGNORABLE, cp);
    }

    private static boolean inRanges(int[][] startsAndEnds, int cp) {
        int[] starts = startsAndEnds[0];
        int[] ends = startsAndEnds[1];
        int index = Arrays.binarySearch(starts, cp);
        if (index < 0) {
            index = -index - 2;
        }
        return index >= 0 && cp <= ends[index];
    }
}
