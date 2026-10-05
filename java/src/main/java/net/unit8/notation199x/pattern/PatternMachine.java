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
 * <p>What is run is the machine the pattern's shape builds, steps for nothing and all, and nothing
 * else: a run keeps the sets of states it comes to ({@link StringPattern}), which are the states a
 * deterministic machine would have, made only as a walk needs them. So building a pattern's machine
 * makes no deterministic one.
 *
 * <p>The deterministic machine is made only for an image, the first time one is asked for and
 * within {@link #canonicalImage}, and is written as its classes and rows ({@link ClassRows}). What
 * that gives is kept for the image and for nothing else: asking for an image does not change what
 * {@link #pattern} runs.
 */
public final class PatternMachine {

    private final Automaton shaped;
    /** Whether the deterministic machine an image is written from has been tried. */
    private boolean imageRowsTried;
    /** The deterministic machine an image is written from as its classes and rows, or null where it
     *  has not been tried or making it was past {@link #canonicalImage}. The machine they were taken
     *  from is not kept: its rows, as wide as its classes, are what these hold in spans. */
    private @Nullable ClassRows imageRows;

    private PatternMachine(Automaton shaped) {
        this.shaped = shaped;
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
     * What making the deterministic machine an image of P2 is written from is allowed to cost.
     *
     * <p>Apart from {@link #run}, because running out of it refuses nothing: the shape's machine is
     * written as P1 instead, which accepts the same. So this is what a smaller image is worth, and a
     * pattern whose deterministic machine is large is not larger for it than one whose machine is
     * small.
     */
    static Meter canonicalImage() {
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
        return new PatternMachine(shaped);
    }

    /**
     * What text is matched against: the machine, run where it is.
     *
     * @return the matcher
     */
    public StringPattern pattern() {
        return StringPattern.of(shaped);
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
     * <p>The deterministic machine is made the first time this is asked, and what that gave, rows or
     * none, is what every later asking writes from.
     *
     * @return the image, or {@link PatternImage.MoreCharacters} where it is too large
     */
    public PatternImage image() {
        ClassRows rows = imageRows();
        if (rows != null) {
            PatternImage one = PatternImages.p2(rows);
            if (one instanceof PatternImage.Written) {
                return one;
            }
        }
        return PatternImages.p1(shaped);
    }

    /**
     * The rows an image of P2 is written from, made the first time they are asked for, or null where
     * making them was past {@link #canonicalImage}. That they were tried is kept with what trying
     * gave, so a machine past the budget is not tried again, and one asked on many threads at once is
     * tried once.
     */
    private synchronized @Nullable ClassRows imageRows() {
        if (!imageRowsTried) {
            // Trying costs what the meter counts — the rows, as wide as the symbols the shape tells
            // apart and as deep as the subsets they are worked out from — and stops where that runs
            // out, so a shape nothing deterministic is worth making is given up on early.
            Automaton deterministic = shaped.canonical(canonicalImage());
            imageRows = deterministic == null ? null : ClassRows.of(deterministic);
            imageRowsTried = true;
        }
        return imageRows;
    }

    /** The machine the shape builds, for a check holding it against the deterministic one. */
    Automaton shaped() {
        return shaped;
    }

    @Override
    public String toString() {
        return "a machine of " + shaped.size() + " states";
    }
}
