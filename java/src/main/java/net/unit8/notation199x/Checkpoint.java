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
 * text, the states of a walk, the marks of a combining run. Between two asks a rule goes over none
 * of what the caller handed in, the text or the machine a pattern is run as, further than one of
 * those. What it does between them is a lookup in a table, a search of a sorted one, which grows
 * only as the logarithm of what it searches, or a loop with a fixed most. How much asking costs is
 * the caller's too: a checkpoint that is costly to answer can answer most asks at once and look only
 * every so often.
 *
 * <p>Nor does a rule make room ahead of the work from the size of what it was handed. Room for an
 * answer starts small and grows with what is written, and a walk makes the room it is held in only
 * once it has asked, so a stop at the first ask has made nothing as large as the text or the
 * machine. What the platform does in one operation, such as growing that room or copying the answer
 * out into a string at the end, is not asked inside.
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
