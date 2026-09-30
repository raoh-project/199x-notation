package net.unit8.notation199x.pattern;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The machine that is run for a pattern, as the image a {@link StringPattern} is read from, or why
 * there is none.
 *
 * <p>The one place a pattern becomes what a run executes, so which machine is run and how it is
 * written are decided once.
 *
 * <p>The deterministic machine where making it stays within {@link #deterministicRun} — its states
 * and the work of making them — and its image within {@link #MOST_CHARACTERS}; otherwise the
 * machine the pattern's shape builds, steps for nothing and all. Both accept the same strings, so
 * which one is held decides how fast a run is and nothing about its answer. Only the shape's
 * machine can refuse a pattern: past {@link #run}, or past the characters one image is given,
 * there is no machine written.
 *
 * <p>The limits here are of what is run and of nothing else. What a caller is willing to spend
 * answering questions about patterns before any text arrives is the caller's, and is a
 * {@link Meter} of its own.
 */
public sealed interface PatternImage {

    /** The most states the machine a pattern is run as may have. */
    int MOST_STATES = 250_000;

    /**
     * What the machine that is run for a pattern may be, built from the pattern's shape.
     *
     * <p>Past it the pattern is refused as larger than is written. One machine and nothing thrown
     * away, so what it may have and what may be built are the same number.
     */
    static Meter run() {
        return new Meter(MOST_STATES, MOST_STATES, 50_000_000);
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
     * The most characters one pattern's image may take.
     *
     * <p>Above what the largest machine {@link #run} lets the shape build takes where its steps are
     * over sets a pattern writes once — a repetition written out, which is what makes a shape
     * large. So what this refuses is a pattern whose sets are themselves large, and never one the
     * state limit already let through for its size alone.
     */
    int MOST_CHARACTERS = 1 << 23;

    /** The image, as the strings it is cut into ({@link StringPattern#CHUNK}). */
    record Written(List<String> strings) implements PatternImage {

        public Written {
            strings = List.copyOf(strings);
        }

        /** The pattern the image writes, as what text is matched against. */
        public StringPattern pattern() {
            return StringPattern.of(strings);
        }
    }

    /** The machine the shape builds has more states than {@code most}. */
    record MoreStates(int most) implements PatternImage {}

    /** The machine the shape builds is written in more characters than {@code most}. */
    record MoreCharacters(int most) implements PatternImage {}

    /** What is run for {@code meaning}. */
    static PatternImage of(PatternMeaning meaning) {
        Automaton shaped = Automaton.of(meaning, run());
        if (shaped == null) {
            return new MoreStates(MOST_STATES);
        }
        // Trying costs what the meter counts — the rows, as wide as the symbols the shape tells
        // apart and as deep as the subsets they are worked out from — and stops where that runs
        // out, so a shape nothing deterministic is worth making is given up on early.
        Automaton one = shaped.canonical(deterministicRun());
        List<String> image = one == null ? null : written(one, true);
        if (image == null) {
            image = written(shaped, false);
        }
        return image == null ? new MoreCharacters(MOST_CHARACTERS) : new Written(image);
    }

    /**
     * The deterministic machine's image, or null where making it is past {@code meter}.
     *
     * <p>For a check holding the two images against each other. {@link #of} picks by size, so a
     * small pattern only ever reaches the deterministic one there, and the other would go unasked.
     */
    static List<String> deterministic(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        Automaton one = shaped == null ? null : shaped.canonical(meter);
        return one == null ? null : written(one, true);
    }

    /** The shape's machine's image, or null where it is past {@code meter}. See
     *  {@link #deterministic}. */
    static List<String> shaped(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        return shaped == null ? null : written(shaped, false);
    }

    /**
     * {@code machine}'s image, or null where it would take more than {@link #MOST_CHARACTERS}.
     *
     * <p>Stopped where the writer says it is past its limit, which is before the next state is
     * written: what is refused here is never written out first. A label is handed to the writer once
     * however many steps share it.
     */
    private static List<String> written(Automaton machine, boolean deterministic) {
        StringPattern.Writer out = new StringPattern.Writer(deterministic, MOST_CHARACTERS);
        for (int state = 0; state < machine.size() && out.holds(); state++) {
            out.state(machine.stopsAt(state));
        }
        Map<CodePoints, Integer> sets = new IdentityHashMap<>();
        for (int state = 0; state < machine.size() && out.holds(); state++) {
            for (Automaton.Step each : machine.stepsFrom(state)) {
                Integer set = sets.get(each.over());
                if (set == null) {
                    set = out.set(pairs(each.over()));
                    sets.put(each.over(), set);
                }
                out.step(state, set, each.to());
            }
            for (int to : machine.freeFrom(state)) {
                out.free(state, to);
            }
        }
        return out.holds() ? out.image() : null;
    }

    private static int[] pairs(CodePoints over) {
        List<CodePoints.Range> ranges = over.ranges();
        int[] out = new int[ranges.size() * 2];
        for (int at = 0; at < ranges.size(); at++) {
            out[at * 2] = ranges.get(at).from();
            out[at * 2 + 1] = ranges.get(at).to();
        }
        return out;
    }
}
