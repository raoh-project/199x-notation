package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Checkpoint;
import net.unit8.notation199x.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match run with a {@link Checkpoint} answers what it answers without one, and stops wherever the
 * checkpoint says to, neither accepting nor refusing.
 *
 * <p>Over a machine that is not deterministic one character can move every state the machine has,
 * so asking once a character would let a short subject run as long as the machine is large. These
 * hold that the walk asks inside a character too, and after the subject is read.
 *
 * <p>How often it asks is what {@link StringPattern} says, worked out here over sets of states of
 * the machine apart from the walk ({@link #asksOf}), and each walk asks at least that often. Every
 * place the walk asks is counted in it, so a walk that stopped asking at any one of them would ask
 * fewer times than it says.
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
        Automaton machine = Automaton.of(meaning("(a?){10000}b"), Held.roomy());
        StringPattern wide = StringPattern.of(machine, false);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(false), wide.matches("a", all));
        long says = asksOf(machine, "a");
        assertTrue(all.asked >= says, "asked " + all.asked + " times, and says " + says);
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
     * A walk asks as often as the class says, over no character, one, or several: before it makes
     * its room, for each state and step for no character it takes in, for each character, state
     * moved and step looked at, and, once the subject is read, for each state it ended in as it
     * looks through them for one it may stop at.
     */
    @Test
    void aWalkAsksAtEveryPlaceItSaysItDoes() {
        Automaton machine = Automaton.of(meaning("(a?){1000}b"), Held.roomy());
        StringPattern wide = StringPattern.of(machine, false);
        for (String subject : List.of("", "a", "aaa", "ba")) {
            Counting all = Counting.never();
            assertEquals(new Outcome.Answered<>(false), wide.matches(subject, all), subject);
            long says = asksOf(machine, subject);
            assertTrue(all.asked >= says, "\"" + subject + "\" asked " + all.asked + " times, and says " + says);
        }
    }

    /**
     * A walk ends in many states, none of which it may stop at, and looks through them for one once
     * the subject is read: as many states as the machine has, and it is stopped there as anywhere.
     */
    @Test
    void aWalkLookingThroughWhereItEndedIsStoppedThere() {
        Automaton machine = Automaton.of(meaning("(a?){10000}b"), Held.roomy());
        StringPattern wide = StringPattern.of(machine, false);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(false), wide.matches("", all));
        // The states it ended in are looked through last, so the ask before the last of them is
        // one of those.
        assertInstanceOf(Outcome.Stopped.class, wide.matches("", new Counting(all.asked)));
        assertEquals(new Outcome.Answered<>(true), wide.matches("b", Counting.never()));
        assertEquals(new Outcome.Answered<>(true), wide.matches("aaab", Counting.never()));
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

    /**
     * How many times a walk over {@code machine} says it asks over {@code subject}, worked out over
     * sets of states and apart from the walk: once before it makes its room; for each state put in
     * the walk, once, and once for each step for no character from it; for each character, once,
     * and once for each state moved and each step looked at from one; and, where the walk ends in
     * states, once for each as it looks through them. Of a subject the machine does not accept, so
     * that every state it ended in is looked through.
     */
    static long asksOf(Automaton machine, String subject) {
        boolean[] live = live(machine);
        long asks = 1;
        Set<Integer> here = new LinkedHashSet<>();
        asks += close(machine, live, Automaton.START, here);
        for (int at = 0; at < subject.length(); ) {
            int symbol = subject.codePointAt(at);
            at += Character.charCount(symbol);
            asks++;
            if (here.isEmpty()) {
                return asks;
            }
            Set<Integer> there = new LinkedHashSet<>();
            for (int state : here) {
                asks++;
                for (Automaton.Step step : machine.stepsFrom(state)) {
                    asks++;
                    if (step.over().has(symbol)) {
                        asks += close(machine, live, step.to(), there);
                    }
                }
            }
            here = there;
        }
        for (int state : here) {
            assertFalse(machine.stopsAt(state), "a subject the machine does not accept");
        }
        return asks + here.size();
    }

    /** Puts {@code from} and the live states it reaches for no character into {@code into}, where
     *  it is not there already; answers the asks that takes. */
    private static long close(Automaton machine, boolean[] live, int from, Set<Integer> into) {
        if (into.contains(from) || !live[from]) {
            return 0;
        }
        long asks = 0;
        Deque<Integer> pending = new ArrayDeque<>();
        into.add(from);
        pending.push(from);
        while (!pending.isEmpty()) {
            int state = pending.pop();
            asks++;
            for (int to : machine.freeFrom(state)) {
                asks++;
                if (!into.contains(to) && live[to]) {
                    into.add(to);
                    pending.push(to);
                }
            }
        }
        return asks;
    }

    /** The states from which a state the machine stops at can be reached. */
    private static boolean[] live(Automaton machine) {
        int states = machine.size();
        List<List<Integer>> back = new ArrayList<>();
        for (int state = 0; state < states; state++) {
            back.add(new ArrayList<>());
        }
        for (int state = 0; state < states; state++) {
            for (Automaton.Step step : machine.stepsFrom(state)) {
                back.get(step.to()).add(state);
            }
            for (int to : machine.freeFrom(state)) {
                back.get(to).add(state);
            }
        }
        boolean[] live = new boolean[states];
        Deque<Integer> pending = new ArrayDeque<>();
        for (int state = 0; state < states; state++) {
            if (machine.stopsAt(state)) {
                live[state] = true;
                pending.push(state);
            }
        }
        while (!pending.isEmpty()) {
            for (int from : back.get(pending.pop())) {
                if (!live[from]) {
                    live[from] = true;
                    pending.push(from);
                }
            }
        }
        return live;
    }

    private static PatternMeaning meaning(String regex) {
        return ((PatternRead.Read) PatternParser.read(regex)).meaning();
    }
}
