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
 */
class AMeterKeepsTheFirstRefusalTest {

    @Test
    void aMeterKeepsTheFirstRefusalAndNotTheLast() {
        Meter meter = new Meter(10, 30, 1_000_000_000L);
        meter.starting();

        assertFalse(meter.making().states(20), "more than one machine may be");
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy());

        // Now spend the allowance down, so that the next refusal is the other limit: small enough
        // to be a machine and larger than what is left.
        for (int each = 0; each < 3; each++) {
            assertTrue(meter.making().states(10), "ten at a time, three times, is the whole of it");
        }
        assertFalse(meter.making().states(5), "and there is nothing left for five more");

        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy(),
                "the first refusal still stands, and it is the one about a pattern");
    }
}
