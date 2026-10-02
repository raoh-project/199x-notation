package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CaseConversion} answers every line of {@code suite/case.txt}. */
class CaseConversionAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyLineIsAnswered() {
        List<String> wrong = new ArrayList<>();
        for (Suite.Line line : Suite.read("case.txt", 5)) {
            String text = line.text(0);
            boolean lower = switch (line.field(1)) {
                case "LOWER" -> true;
                case "UPPER" -> false;
                default -> throw new IllegalStateException(line.where() + ": no direction " + line.field(1));
            };
            String expected = switch (line.field(3)) {
                case "MAPPED" -> line.text(4);
                case "PAST" -> null;
                default -> throw new IllegalStateException(line.where() + ": no outcome " + line.field(3));
            };
            String answered;
            if (line.field(2).isEmpty()) {
                answered = lower ? CaseConversion.lowercase(text) : CaseConversion.uppercase(text);
            } else {
                long bound = Long.parseLong(line.field(2));
                answered = lower ? CaseConversion.lowercaseWithin(text, bound)
                        : CaseConversion.uppercaseWithin(text, bound);
            }
            if (expected == null ? answered != null : !expected.equals(answered)) {
                wrong.add(line.where() + ": " + line.field(1) + " " + Suite.shown(text) + " within "
                        + line.field(2) + " is " + (answered == null ? "past" : Suite.shown(answered))
                        + ", not " + (expected == null ? "past" : Suite.shown(expected)));
            }
        }
        assertEquals(List.of(), wrong);
    }
}
