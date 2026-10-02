package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PatternParser} answers every line of {@code suite/pattern-read.txt}: whether a pattern is
 * read, refused, or past a limit, and which limit where the line names one.
 */
class PatternsAreReadAsEveryLineOfTheSuiteSaysTest {

    @Test
    void everyPatternIsReadRefusedOrPastALimitAsTheLineSays() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("pattern-read.txt", 3)) {
            String pattern = line.text(0);
            PatternRead answered = PatternParser.read(pattern);
            String outcome = switch (answered) {
                case PatternRead.Read _ -> "READ";
                case PatternRead.Refused _ -> "REFUSED";
                case PatternRead.Beyond _ -> "BEYOND";
            };
            boolean limitAsSaid = line.field(2).isEmpty()
                    || answered instanceof PatternRead.Beyond beyond && beyond.limit().name().equals(line.field(2));
            if (!outcome.equals(line.field(1)) || !limitAsSaid) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " is " + answered + ", not "
                        + line.field(1) + " " + line.field(2));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
