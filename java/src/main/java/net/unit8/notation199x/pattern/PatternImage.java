package net.unit8.notation199x.pattern;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The machine a pattern is run as, written out as the image a {@link StringPattern} is read back
 * from, or why it was not.
 *
 * <p>For a caller that carries a machine somewhere else before it is run, as a compiler writing it
 * into a class does. A caller that runs the pattern where it reads it has no use for this, and takes
 * {@link PatternMachine#pattern} instead.
 *
 * <p>An image is bounded by the characters it is given, which is a limit of carrying a machine and
 * not of the pattern: every pattern that is read has a machine ({@link PatternMachine}), and a
 * caller that runs it where it is runs it whatever this answers.
 */
public sealed interface PatternImage {

    /**
     * The most characters one pattern's image may take.
     *
     * <p>Above what the largest machine {@link PatternMachine#run} builds takes where its steps are
     * over sets a pattern writes once — a repetition written out, which is what makes a shape
     * large. So what this refuses is a pattern whose sets are themselves large.
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

    /** The machine is written in more characters than {@code most}. */
    record MoreCharacters(int most) implements PatternImage {}

    /** {@code machine} written out, or {@link MoreCharacters} where it would take more than
     *  {@link #MOST_CHARACTERS}. */
    static PatternImage of(Automaton machine, boolean deterministic) {
        List<String> image = written(machine, deterministic);
        return image == null ? new MoreCharacters(MOST_CHARACTERS) : new Written(image);
    }

    /**
     * The deterministic machine's image, or null where making it is past {@code meter}.
     *
     * <p>For a check holding the two images against each other. {@link PatternMachine} picks by
     * cost, so a small pattern only ever reaches the deterministic one there, and the other would go
     * unasked.
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
