package net.unit8.notation199x.pattern;

import org.jspecify.annotations.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a machine is written out as a {@link PatternImage}.
 *
 * <p>Private to this package, beside the interface rather than on it: a static method of an
 * interface is public whatever it is written as. A deterministic machine is written as P2 and any
 * other as P1, and a machine is never written as a deterministic image of P1. {@link PatternMachine}
 * is the caller; the checks that hold two images against each other are the others.
 */
final class PatternImages {

    private PatternImages() {
    }

    /** {@code machine}, a deterministic one walked one state at a time, written out as P2; or
     *  {@link PatternImage.MoreCharacters} where it would take more than
     *  {@link PatternImage#MOST_CHARACTERS}. */
    static PatternImage p2(Automaton machine) {
        return image(writtenAsP2(machine));
    }

    /** {@code machine} written out as P1, as one that is not deterministic whatever it is; or
     *  {@link PatternImage.MoreCharacters} where it would take more than
     *  {@link PatternImage#MOST_CHARACTERS}. */
    static PatternImage p1(Automaton machine) {
        return image(writtenAsP1(machine));
    }

    private static PatternImage image(@Nullable List<String> written) {
        return written == null
                ? new PatternImage.MoreCharacters(PatternImage.MOST_CHARACTERS)
                : new PatternImage.Written(written);
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
        return one == null ? null : writtenAsP2(one);
    }

    /** The shape's machine's image, or null where it is past {@code meter}. See
     *  {@link #deterministic}. */
    static @Nullable List<String> shaped(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        return shaped == null ? null : writtenAsP1(shaped);
    }

    /**
     * {@code machine}'s image of P2, or null where it would take more than
     * {@link PatternImage#MOST_CHARACTERS}.
     *
     * <p>Asked of a machine made deterministic, which holds where each of its classes leads from
     * each state ({@link Automaton#classesOfWalk}); those are written as they are, and nothing is
     * worked out again from its steps. Stopped where the writer says it is past its limit, which is
     * before the next state is written.
     */
    private static @Nullable List<String> writtenAsP2(Automaton machine) {
        SymbolPartition classes = machine.classesOfWalk();
        if (classes == null) {
            throw new IllegalArgumentException("an image of P2 is written of a machine made"
                    + " deterministic");
        }
        StringPattern.P2Writer out = new StringPattern.P2Writer(classes, machine.size(),
                PatternImage.MOST_CHARACTERS);
        for (int state = 0; state < machine.size() && out.holds(); state++) {
            int from = state;
            out.state(machine.stopsAt(state), each -> machine.leadsTo(from, each));
        }
        return out.holds() ? out.image() : null;
    }

    /**
     * {@code machine}'s image of P1, or null where it would take more than
     * {@link PatternImage#MOST_CHARACTERS}.
     *
     * <p>Stopped where the writer says it is past its limit, which is before the next state is
     * written: what is refused here is never written out first. A label is handed to the writer once
     * however many steps share it.
     */
    private static @Nullable List<String> writtenAsP1(Automaton machine) {
        StringPattern.P1Writer out = new StringPattern.P1Writer(PatternImage.MOST_CHARACTERS);
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
