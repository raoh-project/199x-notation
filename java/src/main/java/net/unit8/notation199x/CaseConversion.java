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
 *
 * <p>A bounded conversion can also be run with a {@link Checkpoint}, for a caller that may have to
 * stop it part of the way through. It asks before each code point of the text it maps, before
 * each code point it looks at around a sigma to tell whether the sigma is final, which may be as
 * many as the text has, and once before the answer is made a string.
 */
public final class CaseConversion {

    private CaseConversion() {}

    /**
     * {@code s} in lowercase. One code point can map to several, and no locale narrows it: a Greek
     * capital sigma becomes the context-dependent final form only at the end of a cased run —
     * {@code lowercase("ΟΣ")} is {@code "ος"} but {@code lowercase("ΟΣΑ")} is {@code "οσα"} — which
     * is the one condition Unicode's default algorithm carries that is context rather than locale.
     *
     * @param s the text, a sequence of scalar values
     * @return {@code s} in lowercase
     */
    public static String lowercase(String s) {
        return unbounded(mapped(s, true, Long.MAX_VALUE, null));
    }

    /**
     * {@code s} in uppercase: the same untailored full mapping, so one code point can widen to
     * several ({@code uppercase("straße")} is {@code "STRASSE"}), and no locale narrows it back —
     * Turkish {@code i} still becomes {@code I}, never {@code İ}.
     *
     * @param s the text, a sequence of scalar values
     * @return {@code s} in uppercase
     */
    public static String uppercase(String s) {
        return unbounded(mapped(s, false, Long.MAX_VALUE, null));
    }

    /**
     * {@link #lowercase}, or null where it is longer than {@code longest} scalar values, which is
     * found out before more than that is written.
     *
     * @param s       the text, a sequence of scalar values
     * @param longest the most scalar values the answer may hold
     * @return {@code s} in lowercase, or null where that is longer than {@code longest}
     */
    public static @Nullable String lowercaseWithin(String s, long longest) {
        return mapped(s, true, longest, null);
    }

    /**
     * {@link #uppercase}, or null where it is longer than {@code longest} scalar values, which is
     * found out before more than that is written.
     *
     * @param s       the text, a sequence of scalar values
     * @param longest the most scalar values the answer may hold
     * @return {@code s} in uppercase, or null where that is longer than {@code longest}
     */
    public static @Nullable String uppercaseWithin(String s, long longest) {
        return mapped(s, false, longest, null);
    }

    /**
     * {@link #lowercaseWithin(String, long)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * @param s          the text, a sequence of scalar values
     * @param longest    the most scalar values the answer may hold
     * @param checkpoint asked before each code point the conversion looks at, as the class says
     * @return what {@link #lowercaseWithin(String, long)} answers, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before it was found
     */
    public static Outcome<@Nullable String> lowercaseWithin(String s, long longest, Checkpoint checkpoint) {
        return checked(s, true, longest, checkpoint);
    }

    /**
     * {@link #uppercaseWithin(String, long)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * @param s          the text, a sequence of scalar values
     * @param longest    the most scalar values the answer may hold
     * @param checkpoint asked before each code point the conversion looks at, as the class says
     * @return what {@link #uppercaseWithin(String, long)} answers, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before it was found
     */
    public static Outcome<@Nullable String> uppercaseWithin(String s, long longest, Checkpoint checkpoint) {
        return checked(s, false, longest, checkpoint);
    }

    private static Outcome<@Nullable String> checked(String s, boolean lower, long longest,
                                                     Checkpoint checkpoint) {
        return Checkpoints.answer(() -> mapped(s, lower, longest, checkpoint));
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
     * <p>The text is read where it is, and not taken out first: {@code Final_Sigma} looks either side
     * of a sigma for as many {@code Case_Ignorable} code points as there are, and looks at them in
     * the text. What is bounded is what is written: each code point's mapping is measured before any
     * of it is, so the answer never holds more than {@code longest}, nor part of a mapping that would
     * take it past.
     *
     * <p>With a checkpoint the answer is written into room that grows with it, and is made a string
     * only once the checkpoint has been asked again ({@link Checkpoints}).
     */
    private static @Nullable String mapped(String s, boolean lower, long longest,
                                            @Nullable Checkpoint checkpoint) {
        StringBuilder out = new StringBuilder(Checkpoints.room(checkpoint, Math.min(s.length(), longest)));
        if (!mapCase(s, lower, longest, out::appendCodePoint, checkpoint)) {
            return null;
        }
        Checkpoints.ask(checkpoint);
        return out.toString();
    }

    /**
     * Hands each code point of the mapped text to {@code out}, and answers whether all of it was:
     * false where it is longer than {@code longest}, and then {@code out} has been handed only what
     * was within it.
     */
    static boolean mapCase(String s, boolean lower, long longest, IntConsumer out) {
        return mapCase(s, lower, longest, out, null);
    }

    private static boolean mapCase(String s, boolean lower, long longest, IntConsumer out,
                                   @Nullable Checkpoint checkpoint) {
        // Even the empty text is longer than a negative bound.
        if (longest < 0) {
            return false;
        }
        long written = 0;
        for (int at = 0; at < s.length(); ) {
            Checkpoints.ask(checkpoint);
            int cp = s.codePointAt(at);
            int after = at + Character.charCount(cp);
            int[] mapped = null;
            if (lower) {
                int[] finalSigmaMapped = lookup(CaseTables.FINAL_SIGMA, cp);
                if (finalSigmaMapped != null && isFinalSigmaContext(s, at, after, checkpoint)) {
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
            at = after;
        }
        return true;
    }

    /** Unicode's {@code Final_Sigma} condition of the code point between {@code at} and
     *  {@code after}: immediately preceded, skipping {@code Case_Ignorable} code points, by a
     *  {@code Cased} one, and NOT immediately followed, skipping the same way, by another
     *  {@code Cased} one. Scanned as far as the text goes rather than over a window, since what
     *  "immediately" skips over is itself defined by the property, not by a fixed count. */
    private static boolean isFinalSigmaContext(String s, int at, int after,
                                               @Nullable Checkpoint checkpoint) {
        boolean precededByCased = false;
        for (int j = at; j > 0; ) {
            Checkpoints.ask(checkpoint);
            int cp = s.codePointBefore(j);
            j -= Character.charCount(cp);
            if (isCaseIgnorable(cp)) {
                continue;
            }
            precededByCased = isCased(cp);
            break;
        }
        if (!precededByCased) {
            return false;
        }
        for (int j = after; j < s.length(); ) {
            Checkpoints.ask(checkpoint);
            int cp = s.codePointAt(j);
            j += Character.charCount(cp);
            if (isCaseIgnorable(cp)) {
                continue;
            }
            return !isCased(cp);
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
