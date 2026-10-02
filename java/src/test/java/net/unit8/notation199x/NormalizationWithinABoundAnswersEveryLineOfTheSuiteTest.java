package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Normalization#normalizeWithin} answers every line of {@code suite/normalization-bound.txt}. */
class NormalizationWithinABoundAnswersEveryLineOfTheSuiteTest {

    @Test
    void everyLineIsAnswered() {
        assertEquals(List.of(), Suite.wrong("normalization-bound.txt", 5, line -> {
            String text = line.text(0);
            Normalization.Form form = Normalization.Form.valueOf(line.oneOf(1, "NFC", "NFD", "NFKC", "NFKD"));
            long bound = line.number(2);
            String expected;
            if (line.oneOf(3, "NORMALIZED", "PAST").equals("NORMALIZED")) {
                expected = line.text(4);
            } else {
                line.empty(4);
                expected = null;
            }
            String answered = Normalization.normalizeWithin(form, text, bound);
            if (expected == null ? answered == null : expected.equals(answered)) {
                return null;
            }
            return form + " " + Suite.shown(text) + " within " + bound + " is "
                    + (answered == null ? "past" : Suite.shown(answered))
                    + ", not " + (expected == null ? "past" : Suite.shown(expected));
        }));
    }
}
