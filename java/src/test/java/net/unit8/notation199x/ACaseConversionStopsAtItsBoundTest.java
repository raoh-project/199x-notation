package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bounded case conversion answers what the unbounded one does where that fits the bound, and null
 * where it does not, without writing anything past the bound.
 *
 * <p>The bound is in scalar values, as {@link Normalization#normalizeWithin} counts it, and not in
 * the UTF-16 units a Java string is made of.
 */
class ACaseConversionStopsAtItsBoundTest {

    /** Texts whose mapping is longer, shorter or as long as they are, in units and in scalar values. */
    private static final List<String> TEXTS = List.of(
            "", "abc", "straße", "İstanbul", "ΟΣ ΟΣΑ Ο'Σ", "ﬃ", "𐐀𐐨", "ŉ", "ΐ", "Σ");

    @Test
    void withinTheBoundItIsTheConversionAndPastItNothing() {
        for (String text : TEXTS) {
            for (boolean lower : new boolean[] {true, false}) {
                String whole = lower ? CaseConversion.lowercase(text) : CaseConversion.uppercase(text);
                long length = whole.codePointCount(0, whole.length());
                for (long longest = -1; longest <= length + 1; longest++) {
                    String within = lower
                            ? CaseConversion.lowercaseWithin(text, longest)
                            : CaseConversion.uppercaseWithin(text, longest);
                    assertEquals(longest >= length ? whole : null, within,
                            (lower ? "lower " : "upper ") + text + " within " + longest);
                }
            }
        }
    }

    @Test
    void aMappingThatWidensIsCountedAsWhatItWrites() {
        // ß is one scalar value and SS is two.
        assertEquals("SS", CaseConversion.uppercaseWithin("ß", 2));
        assertNull(CaseConversion.uppercaseWithin("ß", 1));
        // İ lowercases to i and a combining dot above, which NFC leaves as two.
        assertEquals("i̇", CaseConversion.lowercaseWithin("İ", 2));
        assertNull(CaseConversion.lowercaseWithin("İ", 1));
    }

    @Test
    void theBoundIsInScalarValuesAndNotInUnits() {
        // Two characters past the basic plane: four units, two scalar values, and a case pair.
        String deseret = "𐐀𐐀";
        assertEquals(4, deseret.length());
        assertEquals("𐐨𐐨", CaseConversion.lowercaseWithin(deseret, 2));
        assertNull(CaseConversion.lowercaseWithin(deseret, 1));
    }

    @Test
    void nothingFitsBelowNothing() {
        assertEquals("", CaseConversion.lowercaseWithin("", 0));
        assertNull(CaseConversion.lowercaseWithin("a", 0));
        assertNull(CaseConversion.lowercaseWithin("", -1), "the empty text is longer than -1");
        assertNull(CaseConversion.uppercaseWithin("", Long.MIN_VALUE));
    }

    @Test
    void theFinalSigmaIsDecidedAsWithoutABound() {
        // Whether a sigma is final turns on what follows it, which a bound does not cut short.
        assertEquals("ος", CaseConversion.lowercaseWithin("ΟΣ", 2));
        assertEquals("οσα", CaseConversion.lowercaseWithin("ΟΣΑ", 3));
        assertEquals("ο'ς", CaseConversion.lowercaseWithin("Ο'Σ", 3));
        assertEquals("οσ'α", CaseConversion.lowercaseWithin("ΟΣ'Α", 4));
        assertNull(CaseConversion.lowercaseWithin("ΟΣΑ", 2));
    }

    @Test
    void nothingPastTheBoundIsWritten() {
        // Ten thousand of a ligature that uppercases to three letters, held to ten.
        String text = "ﬃ".repeat(10_000);
        int[] handed = {0};
        assertFalse(CaseConversion.mapCase(text, false, 10, cp -> handed[0]++));
        assertEquals(9, handed[0], "three whole mappings, and not a part of the fourth");

        handed[0] = 0;
        assertTrue(CaseConversion.mapCase("ﬃﬃﬃ", false, 9, cp -> handed[0]++));
        assertEquals(9, handed[0]);
    }
}
