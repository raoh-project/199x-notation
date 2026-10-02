package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PatternParser} answers every line of {@code suite/pattern-read.txt}.
 *
 * <p>The suite counts where a construct begins in scalar values and {@link PatternRead} in the
 * chars of a Java string, so the place is turned into chars here.
 */
class PatternsAreReadAsEveryLineOfTheSuiteSaysTest {

    @Test
    void everyPatternIsReadRefusedOrPastALimitAsTheLineSays() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("pattern-read.txt", 5)) {
            String pattern = line.text(0);
            PatternRead expected = switch (line.field(1)) {
                case "READ" -> null;
                case "REFUSED" -> new PatternRead.Refused(PatternRead.Refusal.valueOf(line.field(2)),
                        pattern.offsetByCodePoints(0, Integer.parseInt(line.field(3))), line.text(4));
                case "BEYOND" -> new PatternRead.Beyond(PatternRead.Limit.valueOf(line.field(2)),
                        pattern.offsetByCodePoints(0, Integer.parseInt(line.field(3))), line.text(4));
                default -> throw new IllegalStateException(line.where() + ": no outcome " + line.field(1));
            };
            PatternRead answered = PatternParser.read(pattern);
            if (expected == null ? !(answered instanceof PatternRead.Read) : !expected.equals(answered)) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " is " + answered + ", not "
                        + (expected == null ? "read" : expected));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
