package net.unit8.notation199x;

/**
 * What a rule asks, as it works, whether to go on: handed in by a caller that may have to stop the
 * rule part of the way through.
 *
 * <p>The rule holds no allowance of its own. How much a caller lets a rule spend, and what that is
 * counted in — steps, a deadline, whether whoever the caller works for has given up — is the
 * caller's, and the rule asks only whether to go on. Where the answer is no, the rule stops there
 * and answers {@link Outcome.Stopped}, which is neither of its own answers.
 *
 * <p>Each rule that takes one says how often it asks, in what it looks at: the characters of the
 * text, the states of a walk, the marks of a combining run. Every loop of the rule whose count turns
 * on what the caller handed in asks once a time round, so between two asks the rule looks at one of
 * those and no more. How much asking costs is the caller's too: a checkpoint that is costly to
 * answer can answer most asks at once and look only every so often.
 *
 * <p>What the JVM does in one operation is not asked inside: making an array or a string, growing
 * one, copying one. A rule asks before it begins one, and makes none ahead of the work it is for.
 * Room for an answer starts small and grows with what is written, so a stop at the first ask has
 * made nothing as long as the text, and the answer is copied out into a string only after one more
 * ask. That copy, as long as the answer, is the one the rule cannot ask inside.
 *
 * <p>A checkpoint that throws stops the rule as well, and what it throws comes out of the rule as
 * it is.
 */
@FunctionalInterface
public interface Checkpoint {

    /**
     * Whether the rule goes on.
     *
     * @return true to go on, and false to stop the rule where it is
     */
    boolean proceed();
}
