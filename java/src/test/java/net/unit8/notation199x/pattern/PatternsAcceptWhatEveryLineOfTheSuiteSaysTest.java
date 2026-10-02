package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Suite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A pattern that is read, run as the matcher its machine makes, accepts what every line of
 * {@code suite/pattern-match.txt} says.
 */
class PatternsAcceptWhatEveryLineOfTheSuiteSaysTest {

    @Test
    void everySubjectIsAcceptedOrNotAsTheLineSays() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("pattern-match.txt", 3)) {
            String pattern = line.text(0);
            String subject = line.text(1);
            boolean expected = Boolean.parseBoolean(line.field(2));
            if (!(PatternParser.read(pattern) instanceof PatternRead.Read read)) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + " is not read");
                continue;
            }
            if (PatternMachine.of(read.meaning()).pattern().matches(subject) != expected) {
                wrong.add(line.where() + ": " + Suite.shown(pattern) + (expected ? " refuses " : " accepts ")
                        + Suite.shown(subject));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
