package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What stopped a construction is the first limit that said no, whatever is asked of the meter
 * afterwards.
 *
 * <p>A builder that stops on the first refusal never asks again, so this is what holds when one
 * forgets: the attribution stays the construction's rather than the last thing that happened to be
 * tried. The two limits answer different questions — one machine larger than a machine may be is
 * about the pattern somebody wrote, and an allowance run out is about everything asked of it — so
 * which of them a caller is told decides what it says to a person.
 *
 * <p>A meter counts states and work apart, and either can be what refuses, so the later refusal is
 * asked on both. Each is one of the other kind than the first, which the controls show: on a meter
 * nothing refused before, the same asks come to the allowance.
 */
class AMeterKeepsTheFirstRefusalTest {

    /** As much work as one machine may look at, and as much as the answer may in all. */
    private static final long WORK = 100;

    private static Meter meter() {
        return new Meter(10, 30, WORK);
    }

    @Test
    void aLaterRefusalOfStatesDoesNotReplaceTheFirst() {
        Meter meter = meter();
        meter.starting();
        assertFalse(meter.making().states(20), "more than one machine may be");
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy());

        spendTheStates(meter);
        assertFalse(meter.making().states(5), "and there is nothing left for five more");
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy(),
                "the first refusal still stands, and it is the one about a pattern");
    }

    @Test
    void aLaterRefusalOfWorkDoesNotReplaceTheFirst() {
        Meter meter = meter();
        meter.starting();
        assertFalse(meter.making().states(20), "more than one machine may be");
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy());

        spendMostOfTheWork(meter);
        assertFalse(meter.making().work(WORK / 2), "within one machine and past what is left");
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy(),
                "a refusal of work after it does not replace it either");
    }

    @Test
    void theLaterAsksAreRefusedForTheAllowanceWhereNothingWasRefusedBefore() {
        Meter states = meter();
        states.starting();
        spendTheStates(states);
        assertFalse(states.making().states(5));
        assertEquals(Meter.Stopped.THE_ANSWER, states.stoppedBy());

        Meter work = meter();
        work.starting();
        spendMostOfTheWork(work);
        assertFalse(work.making().work(WORK / 2));
        assertEquals(Meter.Stopped.THE_ANSWER, work.stoppedBy());
    }

    /** Ten at a time, three times, which is the whole of what the meter allows to be made. */
    private static void spendTheStates(Meter meter) {
        for (int each = 0; each < 3; each++) {
            assertTrue(meter.making().states(10));
        }
    }

    /** More than half the work, by one machine, so that half again is within a machine and not
     *  within what is left. */
    private static void spendMostOfTheWork(Meter meter) {
        assertTrue(meter.making().work(WORK / 2 + 10));
    }
}
