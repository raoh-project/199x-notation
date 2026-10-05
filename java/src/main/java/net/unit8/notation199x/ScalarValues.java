package net.unit8.notation199x;

import org.jspecify.annotations.Nullable;

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
 * {@link #compare} take the text to be one and do not ask.
 *
 * <p>Each can also be run with a {@link Checkpoint}, for a caller that may have to stop it part of
 * the way through: each goes over as much of the text as it is long, and a text can be as long as a
 * string holds. {@link #halfAPairAt} and {@link #count} ask before each code point they read, a pair
 * being one, and {@link #compare} before each unit of the two texts it compares, up to the first
 * that differs.
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
        return firstHalfAPair(text, null);
    }

    /**
     * {@link #halfAPairAt(String)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * @param text       the text
     * @param checkpoint asked before each code point read, as the class says
     * @return what {@link #halfAPairAt(String)} answers, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before it was found
     */
    public static Outcome<Integer> halfAPairAt(String text, Checkpoint checkpoint) {
        return Checkpoints.answer(() -> firstHalfAPair(text, checkpoint));
    }

    private static int firstHalfAPair(String text, @Nullable Checkpoint checkpoint) {
        for (int at = 0; at < text.length(); at++) {
            Checkpoints.ask(checkpoint);
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
     * {@link #count(String)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * <p>Counted a code point at a time, as {@link String#codePointCount} counts: a pair is one, and
     * half of one is one as well.
     *
     * @param text       the text, a sequence of scalar values
     * @param checkpoint asked before each code point read, as the class says
     * @return what {@link #count(String)} answers, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before it was found
     */
    public static Outcome<Long> count(String text, Checkpoint checkpoint) {
        return Checkpoints.answer(() -> {
            long counted = 0;
            for (int at = 0; at < text.length(); at++) {
                Checkpoints.ask(checkpoint);
                if (Character.isHighSurrogate(text.charAt(at)) && at + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(at + 1))) {
                    at++;
                }
                counted++;
            }
            return counted;
        });
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
        return ordered(a, b, null);
    }

    /**
     * {@link #compare(String, String)}, asking {@code checkpoint} as it goes whether to go on.
     *
     * @param a          one text, a sequence of scalar values
     * @param b          another
     * @param checkpoint asked before each unit compared, as the class says
     * @return what {@link #compare(String, String)} answers, or {@link Outcome.Stopped} where
     *         {@code checkpoint} said not to go on before it was found
     */
    public static Outcome<Integer> compare(String a, String b, Checkpoint checkpoint) {
        return Checkpoints.answer(() -> ordered(a, b, checkpoint));
    }

    private static int ordered(String a, String b, @Nullable Checkpoint checkpoint) {
        int shared = Math.min(a.length(), b.length());
        for (int at = 0; at < shared; at++) {
            Checkpoints.ask(checkpoint);
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
