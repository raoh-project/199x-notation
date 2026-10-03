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
     * <p>Text that maps to itself is answered with itself. Otherwise what maps to itself is copied a
     * run at a time, from {@code kept}, and the answer is made only once a code point that changes
     * is met. Whether one does is read off {@link CaseTables#LOWER_PAGES} or
     * {@link CaseTables#UPPER_PAGES}, which answer where its mapping is as well.
     *
     * <p>With a checkpoint the answer is written into room that grows with it, and is made a string
     * only once the checkpoint has been asked again ({@link Checkpoints}).
     */
    private static @Nullable String mapped(String s, boolean lower, long longest,
                                            @Nullable Checkpoint checkpoint) {
        // Even the empty text is longer than a negative bound.
        if (longest < 0) {
            return null;
        }
        byte[] blocks = lower ? CaseTables.LOWER_BLOCKS : CaseTables.UPPER_BLOCKS;
        char[] pages = lower ? CaseTables.LOWER_PAGES : CaseTables.UPPER_PAGES;
        int[][] mappings = (lower ? CaseTables.LOWER : CaseTables.UPPER).mapped();
        StringBuilder out = null;
        int kept = 0;
        long written = 0;
        byte[] ascii = lower ? LOWER_ASCII : UPPER_ASCII;
        for (int at = 0; at < s.length(); ) {
            long scanned = sameUpTo(s, at, ascii, blocks, pages, checkpoint);
            int same = Scan.end(scanned);
            if (same > at) {
                written += Scan.codePoints(scanned, at);
                if (written > longest) {
                    return null;
                }
                at = same;
                if (at == s.length()) {
                    break;
                }
            }
            int cp = s.codePointAt(at);
            int after = at + Character.charCount(cp);
            if (cp < 0x80 && ascii[cp] >= 0) {
                if (out == null) {
                    out = new StringBuilder(Checkpoints.room(checkpoint, Math.min(s.length(), longest)));
                }
                out.append(s, kept, at);
                // The ASCII from here that changes is written as it is mapped, a character at a time.
                while (true) {
                    if (written == longest) {
                        return null;
                    }
                    written++;
                    out.append((char) ascii[s.charAt(at)]);
                    at++;
                    if (at == s.length()) {
                        break;
                    }
                    char next = s.charAt(at);
                    if (next >= 0x80 || ascii[next] < 0 || ascii[next] == next) {
                        break;
                    }
                    Checkpoints.ask(checkpoint);
                }
                kept = at;
                continue;
            }
            int position = pages[(blocks[cp >>> 8] & 0xFF) << 8 | cp & 0xFF];
            int[] mapped = mappings[position - 1];
            if (lower) {
                int[] finalSigmaMapped = lookup(CaseTables.FINAL_SIGMA, cp);
                if (finalSigmaMapped != null && isFinalSigmaContext(s, at, after, checkpoint)) {
                    mapped = finalSigmaMapped;
                }
            }
            if (mapped.length > longest - written) {
                return null;
            }
            written += mapped.length;
            if (out == null) {
                out = new StringBuilder(Checkpoints.room(checkpoint, Math.min(s.length(), longest)));
            }
            out.append(s, kept, at);
            for (int m : mapped) {
                out.appendCodePoint(m);
            }
            kept = after;
            at = after;
        }
        if (out == null) {
            return s;
        }
        out.append(s, kept, s.length());
        Checkpoints.ask(checkpoint);
        return out.toString();
    }

    /** Where the code points {@code s} has from {@code at} that the mapping leaves as they are end,
     *  the first one from there that it changes or the end of the text, and how many of them are
     *  past the basic plane, as a {@link Scan}. Asks {@code checkpoint} before each code point it
     *  reads, the one it ends at too. */
    private static long sameUpTo(String s, int at, byte[] ascii, byte[] blocks, char[] pages,
                                @Nullable Checkpoint checkpoint) {
        int pairs = 0;
        while (at < s.length()) {
            Checkpoints.ask(checkpoint);
            char c = s.charAt(at);
            if (c < 0x80) {
                if (ascii[c] != c) {
                    break;
                }
                at++;
                continue;
            }
            int cp = s.codePointAt(at);
            if (pages[(blocks[cp >>> 8] & 0xFF) << 8 | cp & 0xFF] != 0) {
                break;
            }
            if (cp > Character.MAX_VALUE) {
                pairs++;
            }
            at += Character.charCount(cp);
        }
        return Scan.of(at, pairs);
    }

    /** For the lowercase mapping, what each ASCII character maps to where the mapping makes it one
     *  ASCII character and no {@code Final_Sigma} entry names it, and -1 where the tables are asked.
     *  Read off the tables, so that most text is mapped a unit at a time without a rule of its own
     *  about ASCII. */
    private static final byte[] LOWER_ASCII = ascii(CaseTables.LOWER, CaseTables.FINAL_SIGMA);

    /** {@link #LOWER_ASCII} for the uppercase mapping. */
    private static final byte[] UPPER_ASCII = ascii(CaseTables.UPPER, null);

    private static byte[] ascii(CaseTables.Mapping mapping, CaseTables.@Nullable Mapping finalSigma) {
        byte[] ascii = new byte[0x80];
        for (int c = 0; c < 0x80; c++) {
            int[] mapped = lookup(mapping, c);
            ascii[c] = finalSigma != null && lookup(finalSigma, c) != null ? -1
                    : mapped == null ? (byte) c
                    : mapped.length == 1 && mapped[0] < 0x80 ? (byte) mapped[0]
                    : -1;
        }
        return ascii;
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
