package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A complement turns over the states a walk stops at, and what it leaves is still the one machine.
 *
 * <p>Two things and they fail apart. A machine a walk is only ever in one state of, where every
 * symbol leads somewhere, has a complement that is the same table with the other states stopped at
 * — so nothing is made deterministic and nothing is walked, and what a caller spends is the states
 * it already had. And the table being the same table is what makes the answer canonical without
 * being made so: what settles the one machine is read off the steps and the walk that numbers them,
 * and neither of those asks where a walk may stop.
 *
 * <p>Which is why the second is asked of the machine and not of the strings it holds. A complement
 * that came back short of canonical still holds the right strings, so every question answered by
 * walking agrees. The question is about the machine the complement hands back, and it is asked
 * there.
 */
class TheComplementOfTheOneMachineIsTheOneMachineTest {

    private static Meter roomy() {
        return new Meter(100_000, 10_000_000, 1_000_000_000L);
    }

    private static Automaton canonicalMachineOf(String regex, Meter meter) {
        PatternMeaning meaning =
                assertInstanceOf(PatternRead.Read.class, PatternParser.read(regex), regex).meaning();
        Automaton one = Held.canonical(meaning, meter);
        assertNotNull(one, regex);
        return one;
    }

    /**
     * What a complement of the one machine costs is nothing.
     *
     * <p>A meter counts the states a construction makes, and this one makes none: the steps are the
     * steps that were there and so is what each is numbered, and the answer to where a walk may
     * stop is what is built. Made deterministic first, it would be the subsets and then a machine
     * written out of them — the same states discovered a second time and charged for both.
     */
    @Test
    void turningTheStatesOverCostsNothing() {
        Meter meter = roomy();
        Automaton one = canonicalMachineOf("[ab]+", meter);
        int before = meter.left();

        Automaton turned = one.not(meter);

        assertNotNull(turned);
        assertEquals(one.size(), turned.size(), "the same states");
        assertEquals(before, meter.left(), "and none of them was made, so none was charged");
    }

    /**
     * And it is made where there is nothing left to make a state with.
     *
     * <p>A construction that shares what it was handed is not one an allowance has anything to say
     * about, and a complement asked for with nothing left is answered — where charging it for the
     * states it did not make would refuse an answer that costs nothing.
     */
    @Test
    void aComplementIsMadeWithNothingLeftToMakeAStateWith() {
        // As much in all as one machine may hold, so that what is left can be taken in one ask.
        Meter meter = new Meter(100_000, 100_000, 1_000_000_000L);
        Automaton one = canonicalMachineOf("[ab]+", meter);
        // Everything the meter had, so that a state asked for now is refused.
        assertTrue(meter.making().states(meter.left()), "the allowance is spent down to nothing");
        assertEquals(0, meter.left());

        Automaton turned = one.not(meter);

        assertNotNull(turned, "the complement is still answered");
        assertEquals(one.size(), turned.size());
    }

    /**
     * And what it hands back is the one machine for those strings already.
     *
     * <p>Asked of the complement itself and not of anything built out of it. Made canonical, it
     * comes to itself — same states, same steps, same numbering — which is what lets a caller that
     * knows its machine is canonical keep the answer without paying for that walk.
     */
    @Test
    void aComplementOfTheOneMachineNeedsNoMaking() {
        Meter meter = roomy();
        Automaton one = canonicalMachineOf("[ab]+", meter);

        Automaton turned = one.not(meter);
        Automaton again = turned == null ? null : turned.canonical(meter);

        assertNotNull(turned);
        assertNotNull(again);
        assertTrue(turned.sameAs(again), "the complement is the one machine for what it accepts");
    }

    /**
     * And the complement is the machine the same strings come to by another road.
     *
     * <p>The two are built without a step in common past the words: one turns the one machine for
     * them over, the other meets every string with the complement of a machine written out of the
     * words themselves, which is not deterministic to begin with. Equal means the tables are equal,
     * state for state, which is what the one machine for a set of strings says.
     */
    @Test
    void aComplementIsTheSameMachineAsTheOneTheStringsComeToOtherwise() {
        Meter meter = roomy();
        List<String> words = List.of("a");

        Automaton made = Automaton.ofWords(words, meter);
        Automaton one = made == null ? null : made.canonical(meter);
        Automaton turned = one == null ? null : one.not(meter);

        Automaton everything = canonicalMachineOf("[\\s\\S]*", meter);
        Automaton written = Automaton.ofWords(words, meter);
        Automaton leftOut = written == null ? null : written.not(meter);
        Automaton both = leftOut == null ? null : everything.and(leftOut, meter);
        Automaton met = both == null ? null : both.canonical(meter);

        assertNotNull(turned);
        assertNotNull(met);
        assertTrue(met.sameAs(turned), "one set of strings is one machine, however it was reached");
        assertEquals(met.shape(), turned.shape(), "which is what a map looking one up asks");
    }

    /** Turned over twice, a machine is itself — table for table, not merely string for string. */
    @Test
    void aMachineTurnedOverTwiceIsTheOneItWas() {
        Meter meter = roomy();
        Automaton one = canonicalMachineOf("[ab]+", meter);

        Automaton once = one.not(meter);
        Automaton back = once == null ? null : once.not(meter);

        assertNotNull(back);
        assertTrue(one.sameAs(back), "the same machine and not only the same strings");
    }
}
