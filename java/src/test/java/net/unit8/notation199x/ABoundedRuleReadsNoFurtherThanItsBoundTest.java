package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.unit8.notation199x.Normalization.Form;

/**
 * A bounded normalization or case conversion of a text whose answer is past the bound reads no more
 * of it than the bound and a number the Unicode tables decide, however long the text is.
 *
 * <p>Each code point read is asked about once, as the classes say, so the asks of a checkpoint that
 * always says to go on count what was read. Each count is worked out here from the shape of the text
 * and the bound, and the texts are far longer than the bound, so a rule that read on to the end of a
 * run would ask as many times as the text is long.
 */
class ABoundedRuleReadsNoFurtherThanItsBoundTest {

    private static final int MANY = 100_000;

    private static final long BOUND = 10;

    /** A checkpoint that counts the asks and always says to go on. */
    private static final class Counting implements Checkpoint {

        long asked;

        @Override
        public boolean proceed() {
            asked++;
            return true;
        }
    }

    /** Every code point of the text is a stable starter, and each is one of the answer, so the text
     *  is read to one past the bound. */
    @Test
    void stableStartersAreReadToOnePastTheBound() {
        String text = "a".repeat(MANY);
        for (Form form : Form.values()) {
            Counting counting = new Counting();
            assertEquals(new Outcome.Answered<String>(null),
                    Normalization.normalizeWithin(form, text, BOUND, counting));
            assertEquals(BOUND + 1, counting.asked, form.toString());
        }
    }

    /**
     * A starter and a combining run as long as the text. Each mark held is one of the answer, but
     * in a composing form as many as {@link NormalizationTables#MOST_MARKS_COMPOSED} may compose into
     * the starter, so the run is read up to the mark that puts the starter and the marks past the
     * bound with that many taken off.
     */
    @Test
    void aCombiningRunIsReadNoFurtherThanTheMarksThatMayComposeAndTheBound() {
        String text = "a" + "́".repeat(MANY);
        for (Form form : Form.values()) {
            boolean composes = form == Form.NFC || form == Form.NFKC;
            Counting counting = new Counting();
            assertEquals(new Outcome.Answered<String>(null),
                    Normalization.normalizeWithin(form, text, BOUND, counting));
            long says = 1 + BOUND + (composes ? NormalizationTables.MOST_MARKS_COMPOSED : 0);
            assertEquals(says, counting.asked, form.toString());
        }
    }

    /** Text the mapping leaves as it is, each code point one of the answer, is read to one past the
     *  bound. */
    @Test
    void whatTheMappingLeavesIsReadToOnePastTheBound() {
        Counting lower = new Counting();
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.lowercaseWithin("a".repeat(MANY), BOUND, lower));
        assertEquals(BOUND + 1, lower.asked);
        Counting upper = new Counting();
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.uppercaseWithin("A".repeat(MANY), BOUND, upper));
        assertEquals(BOUND + 1, upper.asked);
    }

    /**
     * After a sigma, as many Case_Ignorable marks as the text has. The letter is read and the sigma
     * after it, and the letter is read again looking back from the sigma; looking forward, the marks
     * are read up to one more than the answer has room for after the letter and the sigma.
     */
    @Test
    void aSigmaLooksNoFurtherForwardThanTheBound() {
        Counting counting = new Counting();
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.lowercaseWithin("AΣ" + "́".repeat(MANY), BOUND, counting));
        assertEquals(3 + (BOUND - 2 + 1), counting.asked);
    }
}
