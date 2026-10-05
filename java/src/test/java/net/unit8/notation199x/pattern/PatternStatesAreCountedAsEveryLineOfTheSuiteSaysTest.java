package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The states of every pattern in {@code suite/pattern-states.txt} are counted as the line says,
 * held where the count decides something: beside a filler that brings the whole to exactly the
 * limit, which is read, and to one past it, which is not.
 */
class PatternStatesAreCountedAsEveryLineOfTheSuiteSaysTest {

    /** The limit the specifications state, which the file's rule is written against: not the one
     *  this implementation holds, which is what is tested. */
    private static final long MOST = 250_000;

    @Test
    void eachPatternIsReadAtTheLimitAndIsPastItOneStateOn() {
        assertEquals(List.of(), Suite.wrong("pattern-states.txt", 2, line -> {
            String pattern = line.text(0);
            long states = line.number(1);
            PatternRead at = PatternParser.read("(?:" + pattern + ")|a{0," + (MOST - 5 - states) + "}");
            PatternRead past = PatternParser.read("(?:" + pattern + ")|a{0," + (MOST - 4 - states) + "}");
            if (!(at instanceof PatternRead.Read)) {
                return Suite.shown(pattern) + " at the limit is " + at;
            }
            if (!(past instanceof PatternRead.Beyond beyond) || beyond.limit() != PatternRead.Limit.MACHINE_STATES) {
                return Suite.shown(pattern) + " past the limit is " + past;
            }
            return null;
        }));
    }
}
