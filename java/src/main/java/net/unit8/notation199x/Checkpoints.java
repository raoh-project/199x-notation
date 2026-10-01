package net.unit8.notation199x;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * How a rule of this package runs with a {@link Checkpoint}: every entry that takes one runs the
 * rule through {@link #answer}, and the rule asks through {@link #ask} and sizes what it writes into
 * by {@link #room}.
 *
 * <p>What {@link Checkpoint} promises a caller is held here, in two parts. Every loop the rule runs
 * whose count turns on what the caller handed in asks once a time round. And no work the JVM does in
 * one operation — making an array or a string, growing one, copying one — is begun before the
 * rule has asked, nor is any made larger than what has been asked about so far calls for: a buffer
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
