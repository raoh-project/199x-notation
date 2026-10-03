package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.unit8.notation199x.Normalization.Form;

/**
 * A bound at either end of {@code long} is a bound like any other: the greatest holds every answer
 * and the least none, the empty text among them. The texts go in and out of the algorithm more than
 * once, and a case conversion looks past a sigma, so what is left of the bound is worked out again
 * after something has been written.
 */
class ABoundAtEitherEndOfItsTypeIsABoundTest {

    private static final List<String> TEXTS = List.of(
            "", "a", "áb́c", "́́a", "AΣ́ bΣ", "straßé");

    @Test
    void theGreatestHoldsEveryAnswerAndTheLeastNone() {
        for (String text : TEXTS) {
            for (Form form : Form.values()) {
                assertEquals(Normalization.normalize(form, text),
                        Normalization.normalizeWithin(form, text, Long.MAX_VALUE), form + " " + text);
                for (long least : new long[] {-1, Long.MIN_VALUE}) {
                    assertNull(Normalization.normalizeWithin(form, text, least), form + " " + text + " " + least);
                }
            }
            assertEquals(CaseConversion.lowercase(text), CaseConversion.lowercaseWithin(text, Long.MAX_VALUE));
            assertEquals(CaseConversion.uppercase(text), CaseConversion.uppercaseWithin(text, Long.MAX_VALUE));
            for (long least : new long[] {-1, Long.MIN_VALUE}) {
                assertNull(CaseConversion.lowercaseWithin(text, least), text + " " + least);
                assertNull(CaseConversion.uppercaseWithin(text, least), text + " " + least);
            }
        }
    }
}
