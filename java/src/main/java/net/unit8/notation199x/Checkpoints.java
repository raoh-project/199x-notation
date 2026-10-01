package net.unit8.notation199x;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * How a rule of this package runs with a {@link Checkpoint}: every entry that takes one runs the
 * rule through {@link #answer}, and the rule asks through {@link #ask} and sizes what it writes into
 * by {@link #room}.
 *
 * <p>What {@link Checkpoint} promises a caller is held here, in two parts. A loop whose count turns
 * on the text, as against one bounded by a table or a fixed most, asks once a time round, and so
 * does every phase of the rule: before the text, while it is read, and after it, where what has
 * been held is settled. And no room is made from the text's length ahead of the work: a buffer
 * starts small and grows with what is written, and the answer is copied out of it only after one
 * more ask.
 */
final class Checkpoints {

    private Checkpoints() {}

    /** The rule's checkpoint said not to go on: thrown where it was asked and caught by
     *  {@link #answer}, so that the helpers a rule works through carry no answer for it. */
    private static final class Stop extends RuntimeException {

        private static final Stop STOP = new Stop();

        private Stop() {
            super(null, null, false, false);
        }
    }

    /**
     * What {@code rule} answers, or {@link Outcome.Stopped} where it asked a checkpoint that said not
     * to go on. What the checkpoint throws comes out as it is; only a stop is caught, and a rule run
     * inside another's checkpoint catches its own.
     */
    static <T extends @Nullable Object> Outcome<T> answer(Supplier<T> rule) {
        try {
            return new Outcome.Answered<>(rule.get());
        } catch (Stop stop) {
            return new Outcome.Stopped<>();
        }
    }

    /** Asks {@code checkpoint}, where there is one, whether to go on, and unwinds to {@link #answer}
     *  where not. */
    static void ask(@Nullable Checkpoint checkpoint) {
        if (checkpoint != null && !checkpoint.proceed()) {
            throw Stop.STOP;
        }
    }

    /**
     * The room to make at first for an answer {@code expected} long at most: all of it where nothing
     * asks, which saves growing it, and the default where something does, since making it all at once
     * is work the length of the text done before anything has been asked.
     */
    static int room(@Nullable Checkpoint checkpoint, long expected) {
        return checkpoint == null ? (int) Math.max(0, Math.min(expected, Integer.MAX_VALUE - 8)) : 16;
    }
}
