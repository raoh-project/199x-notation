package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PatternParser} answers every line of {@code suite/pattern-read.txt}: whether a pattern is
 * read, refused, or past a limit, and which limit where the line names one.
 */
class PatternsAreReadAsEveryLineOfTheSuiteSaysTest {

    @Test
    void everyPatternIsReadRefusedOrPastALimitAsTheLineSays() {
        assertEquals(List.of(), Suite.wrong("pattern-read.txt", 3, line -> {
            String pattern = line.text(0);
            String outcome = line.oneOf(1, "READ", "REFUSED", "BEYOND");
            String limit = null;
            if (outcome.equals("BEYOND")) {
                limit = line.oneOfOrNothing(2, "REPETITION_COUNT", "NESTING_DEPTH", "MACHINE_STATES");
            } else {
                line.empty(2);
            }
            PatternRead answered = PatternParser.read(pattern);
            String expectedLimit = limit;
            boolean asSaid = switch (answered) {
                case PatternRead.Read _ -> outcome.equals("READ");
                case PatternRead.Refused _ -> outcome.equals("REFUSED");
                case PatternRead.Beyond beyond -> outcome.equals("BEYOND")
                        && (expectedLimit == null || beyond.limit().name().equals(expectedLimit));
            };
            return asSaid ? null : Suite.shown(pattern) + " is " + answered + ", not " + outcome
                    + (limit == null ? "" : " " + limit);
        }));
    }
}
