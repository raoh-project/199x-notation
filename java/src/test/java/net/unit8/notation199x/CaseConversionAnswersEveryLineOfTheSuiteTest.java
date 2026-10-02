package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CaseConversion} answers every line of {@code suite/case.txt}. */
class CaseConversionAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyLineIsAnswered() {
        assertEquals(List.of(), Suite.wrong("case.txt", 5, line -> {
            String text = line.text(0);
            boolean lower = line.oneOf(1, "LOWER", "UPPER").equals("LOWER");
            Long bound = line.numberOrNothing(2);
            String expected;
            if (line.oneOf(3, "MAPPED", "PAST").equals("MAPPED")) {
                expected = line.text(4);
            } else {
                line.empty(4);
                if (bound == null) {
                    throw new IllegalStateException(line.where() + ": a conversion with no bound is never past it");
                }
                expected = null;
            }
            String answered = bound == null
                    ? (lower ? CaseConversion.lowercase(text) : CaseConversion.uppercase(text))
                    : (lower ? CaseConversion.lowercaseWithin(text, bound) : CaseConversion.uppercaseWithin(text, bound));
            if (expected == null ? answered == null : expected.equals(answered)) {
                return null;
            }
            return (lower ? "LOWER " : "UPPER ") + Suite.shown(text) + (bound == null ? "" : " within " + bound)
                    + " is " + (answered == null ? "past" : Suite.shown(answered))
                    + ", not " + (expected == null ? "past" : Suite.shown(expected));
        }));
    }
}
