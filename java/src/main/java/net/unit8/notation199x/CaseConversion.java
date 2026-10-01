package net.unit8.notation199x;

import java.util.Arrays;
import java.util.function.IntConsumer;
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
 *
 * <p>A mapping can be longer than what it maps, so each conversion also comes bounded
 * ({@link #lowercaseWithin}, {@link #uppercaseWithin}) for a caller that holds text to a longest
 * length: past the bound the answer is null, and what would be past it is never written. The bound
 * is the caller's, in scalar values as {@link Normalization#normalizeWithin} counts it; none is held
 * here.
 */
public final class CaseConversion {

    private CaseConversion() {}

    /** {@code s} in lowercase. One code point can map to several, and no locale narrows it: a Greek
     *  capital sigma becomes the context-dependent final form only at the end of a cased run —
     *  {@code lowercase("ΟΣ")} is {@code "ος"} but {@code lowercase("ΟΣΑ")} is {@code "οσα"} — which
     *  is the one condition Unicode's default algorithm carries that is context rather than locale. */
    public static String lowercase(String s) {
        return unbounded(mapCase(s, true, Long.MAX_VALUE));
    }

    /** {@code s} in uppercase: the same untailored full mapping, so one code point can widen to
     *  several ({@code uppercase("straße")} is {@code "STRASSE"}), and no locale narrows it back —
     *  Turkish {@code i} still becomes {@code I}, never {@code İ}. */
    public static String uppercase(String s) {
        return unbounded(mapCase(s, false, Long.MAX_VALUE));
    }

    /** {@link #lowercase}, or null where it is longer than {@code longest} scalar values, which is
     *  found out before more than that is written. */
    public static @Nullable String lowercaseWithin(String s, long longest) {
        return mapCase(s, true, longest);
    }

    /** {@link #uppercase}, or null where it is longer than {@code longest} scalar values, which is
     *  found out before more than that is written. */
    public static @Nullable String uppercaseWithin(String s, long longest) {
        return mapCase(s, false, longest);
    }

    private static String unbounded(@Nullable String mapped) {
        if (mapped == null) {
            throw new IllegalStateException("no text is longer than Long.MAX_VALUE code points");
        }
        return mapped;
    }

    /**
     * The mapped text, or null where it is longer than {@code longest}.
     *
     * <p>The whole of the input is taken out first, because {@code Final_Sigma} looks past the
     * sigma for as many {@code Case_Ignorable} code points as there are. What is bounded is what is
     * written: each code point's mapping is measured before any of it is, so the answer never holds
     * more than {@code longest}, nor part of a mapping that would take it past.
     */
    private static @Nullable String mapCase(String s, boolean lower, long longest) {
        StringBuilder out = new StringBuilder((int) Math.max(0, Math.min(s.length(), longest)));
        return mapCase(s, lower, longest, out::appendCodePoint) ? out.toString() : null;
    }

    /**
     * Hands each code point of the mapped text to {@code out}, and answers whether all of it was:
     * false where it is longer than {@code longest}, and then {@code out} has been handed only what
     * was within it.
     */
    static boolean mapCase(String s, boolean lower, long longest, IntConsumer out) {
        // Even the empty text is longer than a negative bound.
        if (longest < 0) {
            return false;
        }
        int[] cps = s.codePoints().toArray();
        long written = 0;
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
            int adding = mapped == null ? 1 : mapped.length;
            if (adding > longest - written) {
                return false;
            }
            written += adding;
            if (mapped == null) {
                out.accept(cp);
            } else {
                for (int m : mapped) {
                    out.accept(m);
                }
            }
        }
        return true;
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
