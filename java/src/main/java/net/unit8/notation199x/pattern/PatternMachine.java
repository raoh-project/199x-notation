package net.unit8.notation199x.pattern;

import org.jspecify.annotations.Nullable;

/**
 * The machine a pattern is run as.
 *
 * <p>The one place a pattern becomes what a run executes, so which machine is run is decided once.
 * What is done with it then is the caller's: a caller that runs the pattern where it reads it takes
 * the matcher ({@link #pattern}), and one that carries the machine somewhere else, as a compiler
 * writing it into a class does, takes it written out ({@link #image}).
 *
 * <p>Every pattern that is read has one. {@link PatternParser} holds a pattern to
 * {@link PatternRead.Limit#MACHINE_STATES} before it is read, counting the states from the text
 * ({@link PatternStates}), and the machine is built with room for exactly that many. So building
 * one never refuses, and a limit of how a machine is carried, such as the characters of an image,
 * never decides whether a pattern is one a caller takes.
 *
 * <p>The deterministic machine where making it stays within {@link #deterministicRun}; otherwise
 * the machine the pattern's shape builds, steps for nothing and all. Both accept the same strings,
 * so which one is held decides how fast a run is and nothing about its answer. The deterministic
 * machine is run and written as its classes and rows ({@link ClassRows}), which are what making it
 * proved: they are taken from it once, and neither a run nor an image works them out again.
 */
public final class PatternMachine {

    private final Automaton shaped;
    private final @Nullable Automaton deterministic;
    private final @Nullable ClassRows rows;

    private PatternMachine(Automaton shaped, @Nullable Automaton deterministic) {
        this.shaped = shaped;
        this.deterministic = deterministic;
        this.rows = deterministic == null ? null : ClassRows.of(deterministic);
    }

    /**
     * What building the machine a pattern is run as may make: the states
     * {@link PatternRead.Limit#MACHINE_STATES} allows, and nothing thrown away.
     *
     * <p>Building the shape's machine looks at nothing beside the states it makes, so the work
     * allowed is a bound on nothing this builds.
     */
    static Meter run() {
        int most = PatternRead.Limit.MACHINE_STATES.most();
        return new Meter(most, most, 50_000_000);
    }

    /**
     * What making the machine that is run deterministic is allowed to cost.
     *
     * <p>Apart from {@link #run}, because running out of it refuses nothing: the machine the shape
     * built is run instead, which answers the same and walks more states a character. So this is
     * what a faster run is worth, and a pattern whose deterministic machine is large is not larger
     * for it than one whose machine is small.
     */
    static Meter deterministicRun() {
        return new Meter(10_000, 20_000, 5_000_000);
    }

    /**
     * The machine {@code meaning} is run as.
     *
     * @param meaning what a pattern means, as {@link PatternRead.Read} gives it or as a caller put
     *                it together
     * @return the machine
     * @throws IllegalArgumentException where {@code meaning} comes to more states than
     *                                  {@link PatternRead.Limit#MACHINE_STATES}, which a meaning
     *                                  the reader gave never does
     */
    public static PatternMachine of(PatternMeaning meaning) {
        if (meaning == null) {
            throw new IllegalArgumentException("a machine is made for some meaning");
        }
        if (PatternStates.of(meaning) > PatternRead.Limit.MACHINE_STATES.most()) {
            throw new IllegalArgumentException("a meaning of more than "
                    + PatternRead.Limit.MACHINE_STATES.most() + " states has no machine");
        }
        Automaton shaped = Automaton.of(meaning, run());
        if (shaped == null) {
            throw new IllegalStateException("a meaning within the states it was counted at was"
                    + " refused a machine of that many: the count and the building disagree");
        }
        // Trying costs what the meter counts — the rows, as wide as the symbols the shape tells
        // apart and as deep as the subsets they are worked out from — and stops where that runs
        // out, so a shape nothing deterministic is worth making is given up on early.
        return new PatternMachine(shaped, shaped.canonical(deterministicRun()));
    }

    /**
     * What text is matched against: the machine, run where it is.
     *
     * @return the matcher
     */
    public StringPattern pattern() {
        return rows != null ? StringPattern.of(rows) : StringPattern.of(shaped, false);
    }

    /**
     * The machine written out, for a caller that carries it somewhere to be read back
     * ({@link StringPattern#of(java.util.List)}), or that it takes more characters than one image
     * is given.
     *
     * <p>The deterministic machine as P2 where there is one and its image fits, and otherwise the
     * shape's as P1. No machine is written as a deterministic image of P1, which a reader would have
     * to hold to leading one way at a cost the image's length does not bound.
     * Either accepts what the pattern does, and which is written is this implementation's choice:
     * the shape's image is often the larger, since the deterministic machine is the smallest there
     * is, and a pattern may have an image here that another implementation, writing another machine,
     * finds too large. Past {@link PatternImage#MOST_CHARACTERS} for the one tried last, there is no
     * image, and that is a limit of carrying a machine and not of the pattern: {@link #pattern} runs
     * it all the same.
     *
     * @return the image, or {@link PatternImage.MoreCharacters} where it is too large
     */
    public PatternImage image() {
        if (rows != null) {
            PatternImage one = PatternImages.p2(rows);
            if (one instanceof PatternImage.Written) {
                return one;
            }
        }
        return PatternImages.p1(shaped);
    }

    /** The machine the shape builds, for a check holding it against the deterministic one. */
    Automaton shaped() {
        return shaped;
    }

    /** The deterministic machine, or null where making it was past {@link #deterministicRun}. */
    @Nullable Automaton deterministic() {
        return deterministic;
    }

    @Override
    public String toString() {
        return deterministic != null
                ? "a deterministic machine of " + deterministic.size() + " states"
                : "a machine of " + shaped.size() + " states";
    }
}
