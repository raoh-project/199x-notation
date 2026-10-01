package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Checkpoint;
import net.unit8.notation199x.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match run with a {@link Checkpoint} answers what it answers without one, and stops wherever the
 * checkpoint says to, neither accepting nor refusing.
 *
 * <p>Over a machine that is not deterministic one character can move every state the machine has,
 * so asking once a character would let a short subject run as long as the machine is large. These
 * hold that the walk asks inside a character too.
 */
class AMatchIsStoppedWhereItIsAskedTest {

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

    @Test
    void oneCharacterOfAWideWalkIsStoppedInsideIt() {
        StringPattern wide = StringPattern.of(Automaton.of(meaning("(a?){10000}"), Held.roomy()), false);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(true), wide.matches("a", all));
        assertTrue(all.asked > 10_000, "asked " + all.asked + " times over one character");
        for (long at : new long[] {2, all.asked / 2, all.asked}) {
            assertInstanceOf(Outcome.Stopped.class, wide.matches("a", new Counting(at)),
                    "stopped at ask " + at);
        }
    }

    /** A walk over a large machine stopped at its first ask has not made the room the walk is
     *  held in, which is as large as the machine. */
    @Test
    void aStopAtTheFirstAskHasMadeNothingAsLargeAsTheMachine() {
        StringPattern large = StringPattern.of(PatternMachine.of(meaning("a{200000}")).shaped(), false);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        Outcome<Boolean> stopped = large.matches("a", new Counting(1));
        long made = threads.getCurrentThreadAllocatedBytes() - before;
        assertInstanceOf(Outcome.Stopped.class, stopped);
        assertTrue(made < 100_000, "made " + made + " bytes");
    }

    @Test
    void aLongSubjectIsStoppedPartOfTheWayThrough() {
        StringPattern run = PatternMachine.of(meaning("[a-z]*")).pattern();
        String subject = "a".repeat(1_000_000);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(true), run.matches(subject, all));
        assertEquals(subject.length(), all.asked, "once a character");
        assertInstanceOf(Outcome.Stopped.class, run.matches(subject, new Counting(1_000)));
    }

    /**
     * A walk can end in many states, none of which it may stop at, and is looked through for one
     * once the subject is read: as many states as the machine has, asked about as the rest is.
     */
    @Test
    void aWalkThatEndsInManyStatesIsStoppableAsItLooksThroughThem() {
        StringPattern wide = StringPattern.of(Automaton.of(meaning("(a?){10000}b"), Held.roomy()), false);
        assertEquals(new Outcome.Answered<>(false), wide.matches("", Counting.never()));
        assertEquals(new Outcome.Answered<>(false), wide.matches("aaa", Counting.never()));
        assertEquals(new Outcome.Answered<>(true), wide.matches("b", Counting.never()));
        assertEquals(new Outcome.Answered<>(true), wide.matches("aaab", Counting.never()));
        Counting all = Counting.never();
        wide.matches("", all);
        assertTrue(all.asked > 10_000, "asked " + all.asked + " times over no character");
        for (long at : new long[] {1, all.asked / 2, all.asked}) {
            assertInstanceOf(Outcome.Stopped.class, wide.matches("", new Counting(at)), "stopped at ask " + at);
        }
    }

    /**
     * Whichever machine is run, and with a checkpoint or without, a match answers what the shape's
     * machine accepts as {@link Automaton#accepts} walks it, which is written apart from this walk.
     * And a pattern is still the predicate it was.
     */
    @Test
    void aMatchThatGoesOnAnswersWhatTheMachineAccepts() {
        List<String> patterns = List.of("[a-z]*", "(ab|a)*b?", "\\d{3}-\\d{4}", "(a?){50}a{50}", "[^x]+x", "",
                "(a?){30}b", "(a|ab)(c|bcd)(d*)", "((a|b)*c)?");
        List<String> subjects = List.of("", "a", "b", "ab", "abab", "abcd", "abcdd", "c", "abc", "123-4567",
                "a".repeat(30), "a".repeat(30) + "b", "a".repeat(31) + "b", "a".repeat(50), "a".repeat(100),
                "a".repeat(101), "yyyx", "x", "日本語x", "\uD800");
        for (String pattern : patterns) {
            PatternMeaning meaning = meaning(pattern);
            Automaton shaped = Automaton.of(meaning, Held.roomy());
            for (StringPattern run : List.of(PatternMachine.of(meaning).pattern(), StringPattern.of(shaped, false))) {
                Predicate<String> predicate = run;
                for (String subject : subjects) {
                    Boolean accepted = shaped.accepts(subject, Held.roomy());
                    String what = pattern + " " + run + " " + subject;
                    assertEquals(accepted, predicate.test(subject), what);
                    assertEquals(new Outcome.Answered<>(accepted), run.matches(subject, Counting.never()), what);
                }
            }
        }
    }

    private static PatternMeaning meaning(String regex) {
        return ((PatternRead.Read) PatternParser.read(regex)).meaning();
    }
}
