package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PatternParser} answers every line of {@code suite/pattern-read.txt}: whether a pattern is
 * read, refused, or past a limit, and which refusal or which limit.
 */
class PatternsAreReadAsEveryLineOfTheSuiteSaysTest {

    @Test
    void everyPatternIsReadRefusedOrPastALimitAsTheLineSays() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("pattern-read.txt", 3)) {
            String pattern = line.text(0);
            PatternRead answered = PatternParser.read(pattern);
            String outcome = switch (answered) {
                case PatternRead.Read _ -> "READ ; ";
                case PatternRead.Refused refused -> "REFUSED ; " + refused.why();
                case PatternRead.Beyond beyond -> "BEYOND ; " + beyond.limit();
            };
            String expected = line.field(1) + " ; " + line.field(2);
            if (!outcome.equals(expected)) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " is " + outcome + ", not " + expected);
            }
        }
        assertEquals(List.of(), wrong);
    }
}
