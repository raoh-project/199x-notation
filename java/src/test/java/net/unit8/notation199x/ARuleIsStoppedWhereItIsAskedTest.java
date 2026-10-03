package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.unit8.notation199x.Normalization.Form;

/**
 * A normalization and a case conversion run with a {@link Checkpoint} answer what they answer
 * without one, and stop wherever the checkpoint says to, with an answer that says so.
 *
 * <p>The texts that matter are the ones where one code point of the text makes the rule look at as
 * many others as the text has: a combining run held until the starter after it, and the marks after
 * a sigma that decide whether it is final. Asking once a code point read would let either run as
 * long as the text without asking, so these hold that the rule asks while it looks, too.
 *
 * <p>How often each asks is what its class says, worked out here from the shape of the text, and each
 * asks that often, neither less nor more. Every place a rule asks is counted in it and is a large
 * part of it, so a rule that stopped asking at any one of them would ask fewer times than it says,
 * and a place it asks that the count leaves out, and so nothing holds it to, would ask more.
 */
class ARuleIsStoppedWhereItIsAskedTest {

    /** A checkpoint that counts the asks and says not to go on at the {@code stopAt}th. */
    private static final class Counting implements Checkpoint {

        private final long stopAt;
        long asked;

        Counting(long stopAt) {
            this.stopAt = stopAt;
        }

        static Counting never() {
            return new Counting(Long.MAX_VALUE);
        }

        @Override
        public boolean proceed() {
            return ++asked < stopAt;
        }
    }

    private static final int MANY = 100_000;

    /** A starter, a long run of marks of two classes out of their canonical order, and the starter
     *  that ends the run. */
    private static final String LONG_RUN = "a" + "\u0301\u0316".repeat(MANY / 2) + "b";

    /** A sigma with as many {@code Case_Ignorable} marks before it, back to the cased letter that
     *  might make it final, as after it, up to the cased letter that decides it is not. */
    private static final String LONG_LOOK = "A" + "\u0301".repeat(MANY) + "Σ" + "\u0301".repeat(MANY) + "A";

    @Test
    void aNormalizationAsksWhileItSettlesARunAndNotOnlyAsItReads() {
        for (Form form : Form.values()) {
            Counting all = Counting.never();
            Outcome<String> whole = Normalization.normalizeWithin(form, LONG_RUN, Long.MAX_VALUE, all);
            assertEquals(new Outcome.Answered<>(Normalization.normalize(form, LONG_RUN)), whole);
            long read = LONG_RUN.codePointCount(0, LONG_RUN.length());
            String answer = Normalization.normalize(form, LONG_RUN);
            boolean composes = form == Form.NFC || form == Form.NFKC;
            // Each code point is read once: the starter and the first mark, which sends the text to
            // the algorithm, before it, the rest of the marks by the algorithm, and the starter that
            // ends the run after it. The run is put in order by counting, which goes over the marks
            // twice; composed, in a composing form; and the marks left, which is the answer without
            // its two starters, written. Then the answer is made a string.
            long says = read + 2L * MANY + (composes ? MANY : 0)
                    + (answer.codePointCount(0, answer.length()) - 2) + 1;
            assertEquals(says, all.asked, form + " asked " + all.asked + " times, and says " + says);
            // The last code point is asked about before it is read, and the run is settled after.
            for (long at : new long[] {1, read / 2, read, read + 1, all.asked}) {
                assertInstanceOf(Outcome.Stopped.class,
                        Normalization.normalizeWithin(form, LONG_RUN, Long.MAX_VALUE, new Counting(at)),
                        form + " stopped at ask " + at);
            }
        }
    }

    @Test
    void aLowercaseAsksWhileItLooksPastASigma() {
        Counting all = Counting.never();
        Outcome<String> whole = CaseConversion.lowercaseWithin(LONG_LOOK, Long.MAX_VALUE, all);
        assertEquals(new Outcome.Answered<>(CaseConversion.lowercase(LONG_LOOK)), whole);
        long read = LONG_LOOK.codePointCount(0, LONG_LOOK.length());
        // Each code point mapped; for the sigma, the marks before it and the letter past them, and
        // the marks after it and the letter past those; then the answer made a string.
        long says = read + (MANY + 1) + (MANY + 1) + 1;
        assertEquals(says, all.asked, "asked " + all.asked + " times, and says " + says);
        for (long at : new long[] {1, 3, MANY / 2, all.asked}) {
            assertInstanceOf(Outcome.Stopped.class,
                    CaseConversion.lowercaseWithin(LONG_LOOK, Long.MAX_VALUE, new Counting(at)),
                    "stopped at ask " + at);
        }
    }

    @Test
    void anUppercaseIsStoppedPartOfTheWayThroughALongText() {
        String text = "straße".repeat(MANY);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(CaseConversion.uppercase(text)),
                CaseConversion.uppercaseWithin(text, Long.MAX_VALUE, all));
        assertEquals(text.length() + 1, all.asked, "once a code point, and before the answer is made a string");
        assertInstanceOf(Outcome.Stopped.class,
                CaseConversion.uppercaseWithin(text, Long.MAX_VALUE, new Counting(all.asked / 2)));
    }

    /**
     * A rule stopped at its first ask has made nothing as long as the text: room for the answer is
     * made as the answer is written, and not all at once from the length of what was handed in.
     */
    @Test
    void aStopAtTheFirstAskHasMadeNothingAsLongAsTheText() {
        String text = "a".repeat(10_000_000);
        // A mark first sends the text to the algorithm at once: the first ask reads the mark, and the
        // second reads the starter after it, once the algorithm has gone over the mark.
        String marked = "\u0300" + text;
        for (Form form : Form.values()) {
            long made = allocatedBy(() -> assertInstanceOf(Outcome.Stopped.class,
                    Normalization.normalizeWithin(form, text, Long.MAX_VALUE, new Counting(1))));
            assertTrue(made < 1_000_000, form + " made " + made + " bytes");
            long madeMarked = allocatedBy(() -> assertInstanceOf(Outcome.Stopped.class,
                    Normalization.normalizeWithin(form, marked, Long.MAX_VALUE, new Counting(2))));
            assertTrue(madeMarked < 1_000_000, form + " made " + madeMarked + " bytes for a marked text");
        }
        long lower = allocatedBy(() -> assertInstanceOf(Outcome.Stopped.class,
                CaseConversion.lowercaseWithin(text, Long.MAX_VALUE, new Counting(1))));
        assertTrue(lower < 1_000_000, "lowercase made " + lower + " bytes");
        long upper = allocatedBy(() -> assertInstanceOf(Outcome.Stopped.class,
                CaseConversion.uppercaseWithin(text, Long.MAX_VALUE, new Counting(1))));
        assertTrue(upper < 1_000_000, "uppercase made " + upper + " bytes");
    }

    /** The bytes the current thread allocates while {@code work} runs. */
    static long allocatedBy(Runnable work) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        work.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    /** An answer past the bound is an answer, and is not what a stop answers. */
    @Test
    void anAnswerPastTheBoundIsNotAStop() {
        assertEquals(new Outcome.Answered<String>(null),
                Normalization.normalizeWithin(Form.NFD, "é", 1, Counting.never()));
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.uppercaseWithin("ß", 1, Counting.never()));
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.lowercaseWithin("", -1, Counting.never()));
        // A text past the bound is found to be before the end of it, and asked no further.
        Counting some = Counting.never();
        assertEquals(new Outcome.Answered<String>(null),
                CaseConversion.uppercaseWithin("ß".repeat(MANY), 10, some));
        assertEquals(6, some.asked);
    }

    @Test
    void whatACheckpointThrowsComesOutAsItIs() {
        RuntimeException thrown = new IllegalStateException("the caller's");
        Checkpoint throwing = () -> {
            throw thrown;
        };
        assertSame(thrown, assertThrows(IllegalStateException.class,
                () -> Normalization.normalizeWithin(Form.NFC, "abc", 10, throwing)));
        assertSame(thrown, assertThrows(IllegalStateException.class,
                () -> CaseConversion.lowercaseWithin("abc", 10, throwing)));
    }

    /** A rule run inside another's checkpoint, and stopped, stops only itself. */
    @Test
    void aRuleStoppedInsideAnothersCheckpointStopsOnlyItself() {
        Checkpoint asking = () -> {
            assertInstanceOf(Outcome.Stopped.class,
                    CaseConversion.lowercaseWithin("ABC", 10, new Counting(1)));
            return true;
        };
        assertEquals(new Outcome.Answered<>("abc"), CaseConversion.lowercaseWithin("ABC", 10, asking));
    }

    /** Every line of the conformance test, each form of each column, answers as it does without a
     *  checkpoint. */
    @Test
    void aNormalizationThatGoesOnAnswersAsWithoutACheckpoint() throws IOException {
        List<String> failed = new ArrayList<>();
        int checked = 0;
        for (String line : Files.readAllLines(Ucd.file("NormalizationTest.txt"), StandardCharsets.UTF_8)) {
            String data = line.replaceFirst("#.*", "").trim();
            if (data.isEmpty() || data.startsWith("@")) {
                continue;
            }
            String[] columns = data.split(";", -1);
            for (int i = 0; i < 5; i++) {
                String text = decode(columns[i]);
                for (Form form : Form.values()) {
                    for (long longest : new long[] {Long.MAX_VALUE, 1}) {
                        checked++;
                        Outcome<String> answered = Normalization.normalizeWithin(form, text, longest, Counting.never());
                        Outcome<String> expected = new Outcome.Answered<>(Normalization.normalizeWithin(form, text, longest));
                        if (!expected.equals(answered)) {
                            failed.add(form + " " + columns[i] + " within " + longest);
                        }
                    }
                }
            }
        }
        assertTrue(checked > 100_000, "the file's data lines were read: " + checked);
        assertEquals(List.of(), failed.subList(0, Math.min(20, failed.size())));
    }

    /** Every scalar value, and texts whose mapping turns on what is around it, answer as they do
     *  without a checkpoint. */
    @Test
    void aCaseConversionThatGoesOnAnswersAsWithoutACheckpoint() {
        StringBuilder every = new StringBuilder();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp < Character.MIN_SURROGATE || cp > Character.MAX_SURROGATE) {
                every.appendCodePoint(cp);
            }
        }
        List<String> texts = List.of(every.toString(),
                "", "abc", "straße", "İstanbul", "ΟΣ ΟΣΑ Ο'Σ", "ΟΣ'Α", "ﬃ", "𐐀𐐨", "ŉ", "ΐ", "Σ",
                "\uD800", "Σ\uDC00", "A\uD800Σ");
        for (String text : texts) {
            for (long longest : new long[] {Long.MAX_VALUE, 2}) {
                assertEquals(new Outcome.Answered<>(CaseConversion.lowercaseWithin(text, longest)),
                        CaseConversion.lowercaseWithin(text, longest, Counting.never()), text);
                assertEquals(new Outcome.Answered<>(CaseConversion.uppercaseWithin(text, longest)),
                        CaseConversion.uppercaseWithin(text, longest, Counting.never()), text);
            }
        }
    }

    private static String decode(String column) {
        StringBuilder out = new StringBuilder();
        for (String hex : column.trim().split(" +")) {
            out.appendCodePoint(Integer.parseInt(hex, 16));
        }
        return out.toString();
    }
}
