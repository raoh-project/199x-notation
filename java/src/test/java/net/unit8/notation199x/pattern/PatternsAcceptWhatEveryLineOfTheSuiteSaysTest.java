package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A pattern that is read, run as the matcher its machine makes, accepts what every line of
 * {@code suite/pattern-match.txt} says.
 */
class PatternsAcceptWhatEveryLineOfTheSuiteSaysTest {

    @Test
    void everySubjectIsAcceptedOrNotAsTheLineSays() {
        assertEquals(List.of(), Suite.wrong("pattern-match.txt", 3, line -> {
            String pattern = line.text(0);
            String subject = line.text(1);
            boolean expected = line.yesOrNo(2);
            if (!(PatternParser.read(pattern) instanceof PatternRead.Read read)) {
                return Suite.shown(pattern) + " is not read";
            }
            return PatternMachine.of(read.meaning()).pattern().matches(subject) == expected ? null
                    : Suite.shown(pattern) + (expected ? " refuses " : " accepts ") + Suite.shown(subject);
        }));
    }
}
