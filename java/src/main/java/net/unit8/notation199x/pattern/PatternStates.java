package net.unit8.notation199x.pattern;

/**
 * The states a pattern comes to with its repetitions written out, counted from what is written and
 * without building anything.
 *
 * <p>This is the measure {@link PatternRead.Limit#MACHINE_STATES} is stated in, and it is the
 * specifications' and not this implementation's: every implementation counts the same number from
 * the same text, whatever machine it then runs. Counted on the pattern as written:
 *
 * <ul>
 *   <li>a character, an escape, {@code .}, a shorthand and a class are one each; so are {@code ^}
 *       and {@code $};</li>
 *   <li>an empty pattern, group or alternative is none;</li>
 *   <li>a sequence is the sum of its parts, and a group is what is inside it;</li>
 *   <li>a choice of {@code n} alternatives, written with {@code n - 1} bars, is one more than the
 *       sum of one more than each alternative;</li>
 *   <li>{@code A{n,m}} is {@code m} times {@code A}, plus one; {@code A{n}} is {@code A{n,n}} and
 *       {@code A?} is {@code A{0,1}};</li>
 *   <li>{@code A{n,}} is {@code n + 1} times {@code A}, plus one; {@code A*} is {@code A{0,}} and
 *       {@code A+} is {@code A{1,}};</li>
 *   <li>the pattern is one more than what it is written as.</li>
 * </ul>
 *
 * <p>For a pattern without anchors this is exactly the states of the machine its shape builds
 * ({@link Automaton#of}): a state to start in, one after each set of symbols, one where a choice
 * ends and one where each alternative begins, and one where a repetition ends. An anchor counts one
 * and comes to at most one state, and a repetition of exactly one around an anchor is built as its
 * body, so the machine is never larger than the count. So a pattern within the limit always has a
 * machine, and no machine is built to find out whether it has one.
 *
 * <p>Counted up to one past the limit and no further, so a count written as large as the reader
 * reads is multiplied without overflowing and without being built.
 */
final class PatternStates {

    /** One past the limit: every count above the limit is this. */
    static final long PAST = PatternRead.Limit.MACHINE_STATES.most() + 1L;

    private PatternStates() {
    }

    /** The states {@code written} comes to as a whole pattern, or {@link #PAST}. */
    static long of(WrittenPattern written) {
        return plus(1, in(written));
    }

    /** The states {@code meaning} comes to as a whole pattern, or {@link #PAST}. A meaning has no
     *  anchor, so this is the states the machine its shape builds has. */
    static long of(PatternMeaning meaning) {
        return plus(1, in(meaning));
    }

    private static long in(WrittenPattern written) {
        return switch (written) {
            case WrittenPattern.Meant it -> in(it.meaning());
            case WrittenPattern.Anchor _ -> 1;
            case WrittenPattern.InTurn it -> {
                long sum = 0;
                for (WrittenPattern each : it.parts()) {
                    sum = plus(sum, in(each));
                }
                yield sum;
            }
            case WrittenPattern.EitherOf it -> {
                long sum = 1;
                for (WrittenPattern each : it.arms()) {
                    sum = plus(sum, plus(1, in(each)));
                }
                yield sum;
            }
            case WrittenPattern.Repeated it -> repeated(in(it.what()), it.least(), it.most());
        };
    }

    private static long in(PatternMeaning meaning) {
        return switch (meaning) {
            case PatternMeaning.Nothing _ -> 0;
            case PatternMeaning.Never _, PatternMeaning.Symbols _ -> 1;
            case PatternMeaning.InTurn it -> {
                long sum = 0;
                for (PatternMeaning each : it.parts()) {
                    sum = plus(sum, in(each));
                }
                yield sum;
            }
            case PatternMeaning.EitherOf it -> {
                long sum = 1;
                for (PatternMeaning each : it.arms()) {
                    sum = plus(sum, plus(1, in(each)));
                }
                yield sum;
            }
            case PatternMeaning.Repeated it -> repeated(in(it.what()), it.least(), it.most());
        };
    }

    /** A repetition of a body of {@code body} states: its copies, and the state it ends in. */
    private static long repeated(long body, int least, int most) {
        long copies = most == PatternMeaning.Repeated.NO_CEILING ? least + 1L : most;
        return plus(times(copies, body), 1);
    }

    private static long plus(long one, long other) {
        return Math.min(PAST, one + other);
    }

    private static long times(long copies, long body) {
        if (copies == 0 || body == 0) {
            return 0;
        }
        return copies > PAST / body ? PAST : Math.min(PAST, copies * body);
    }
}
