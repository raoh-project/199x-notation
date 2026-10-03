package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Meter#refusing} refuses every request for more than none, of every kind a meter counts.
 *
 * <p>What an operation answered with it asked of a meter is then nothing, which is what a test of
 * that operation's cost reads off it: the complement of the one machine is held to asking nothing by
 * being answered with this meter. A refusing meter that let work through would let that test pass
 * over a complement that looked at work, so the refusal is held here kind by kind.
 */
class AMeterThatRefusesRefusesEveryKindTest {

    @Test
    void aStateIsRefused() {
        assertFalse(Meter.refusing().making().state());
        assertFalse(Meter.refusing().making().states(1));
    }

    @Test
    void workIsRefused() {
        assertFalse(Meter.refusing().making().work(1));
    }

    @Test
    void nothingAskedIsGranted() {
        Meter.Making making = Meter.refusing().making();
        assertTrue(making.states(0));
        assertTrue(making.work(0));
    }
}
