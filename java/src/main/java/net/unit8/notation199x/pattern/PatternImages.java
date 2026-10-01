package net.unit8.notation199x.pattern;

import org.jspecify.annotations.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a machine is written out as a {@link PatternImage}.
 *
 * <p>Private to this package, beside the interface rather than on it: a static method of an
 * interface is public whatever it is written as, and whether a machine is deterministic is a fact
 * about the machine that the caller of these knows because it built it. {@link PatternMachine} is
 * that caller; the checks that hold two images against each other are the others.
 */
final class PatternImages {

    private PatternImages() {
    }

    /** {@code machine} written out, or {@link PatternImage.MoreCharacters} where it would take
     *  more than {@link PatternImage#MOST_CHARACTERS}. */
    static PatternImage of(Automaton machine, boolean deterministic) {
        List<String> image = written(machine, deterministic);
        return image == null
                ? new PatternImage.MoreCharacters(PatternImage.MOST_CHARACTERS)
                : new PatternImage.Written(image);
    }

    /**
     * The deterministic machine's image, or null where making it is past {@code meter}.
     *
     * <p>For a check holding the two images against each other. {@link PatternMachine} picks by
     * cost, so a small pattern only ever reaches the deterministic one there, and the other would go
     * unasked.
     */
    static @Nullable List<String> deterministic(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        Automaton one = shaped == null ? null : shaped.canonical(meter);
        return one == null ? null : written(one, true);
    }

    /** The shape's machine's image, or null where it is past {@code meter}. See
     *  {@link #deterministic}. */
    static @Nullable List<String> shaped(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        return shaped == null ? null : written(shaped, false);
    }

    /**
     * {@code machine}'s image, or null where it would take more than
     * {@link PatternImage#MOST_CHARACTERS}.
     *
     * <p>Stopped where the writer says it is past its limit, which is before the next state is
     * written: what is refused here is never written out first. A label is handed to the writer once
     * however many steps share it.
     */
    private static @Nullable List<String> written(Automaton machine, boolean deterministic) {
        StringPattern.Writer out = new StringPattern.Writer(deterministic, PatternImage.MOST_CHARACTERS);
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
