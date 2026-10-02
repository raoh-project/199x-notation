package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The states of every pattern in {@code suite/pattern-states.txt} are counted as the line says,
 * held where the count decides something: beside a filler that brings the whole to exactly the
 * limit, which is read, and to one past it, which is not.
 */
class PatternStatesAreCountedAsEveryLineOfTheSuiteSaysTest {

    private static final int MOST = PatternRead.Limit.MACHINE_STATES.most();

    @Test
    void eachPatternIsReadAtTheLimitAndIsPastItOneStateOn() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("pattern-states.txt", 2)) {
            String pattern = line.text(0);
            long states = Long.parseLong(line.field(1));
            String at = "(?:" + pattern + ")|a{0," + (MOST - 5 - states) + "}";
            String past = "(?:" + pattern + ")|a{0," + (MOST - 4 - states) + "}";
            if (!(PatternParser.read(at) instanceof PatternRead.Read)) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " at the limit is " + PatternParser.read(at));
            }
            if (!(PatternParser.read(past) instanceof PatternRead.Beyond beyond)
                    || beyond.limit() != PatternRead.Limit.MACHINE_STATES) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " past the limit is "
                        + PatternParser.read(past));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
