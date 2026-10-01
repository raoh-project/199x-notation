package net.unit8.notation199x;

import org.jspecify.annotations.Nullable;

/**
 * How a rule run with a {@link Checkpoint} came out: it answered, or it was stopped first.
 *
 * <p>Whether the rule finished and what it answered are apart. A bounded normalization that finds
 * its answer past the bound has answered, with null; one that was stopped has no answer at all, and
 * a match that was stopped is neither accepted nor refused.
 *
 * @param <T> what the rule answers
 */
public sealed interface Outcome<T extends @Nullable Object> {

    /**
     * The rule ran to its end.
     *
     * @param answer what it answered, as the same rule run without a checkpoint answers
     * @param <T>    what the rule answers
     */
    record Answered<T extends @Nullable Object>(T answer) implements Outcome<T> {}

    /**
     * The checkpoint said not to go on, and the rule stopped there without an answer.
     *
     * @param <T> what the rule would have answered
     */
    record Stopped<T extends @Nullable Object>() implements Outcome<T> {}
}
