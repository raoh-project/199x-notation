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
 * hold that the walk asks inside a character too, both where it works out a set of states it has
 * not been in and where it moves each state for every character, and after the subject is read.
 *
 * <p>How often it asks is what {@link StringPattern} says, worked out here over sets of states of
 * the machine apart from the walk ({@link #asksOf}, {@link #asksRemembering}), and each walk asks
 * that often, neither less nor more. Every place the walk asks is counted in them, so a walk that
 * stopped asking at any one of them would ask fewer times than it says, and a place it asks that
 * they leave out, and so nothing holds it to, would ask more. A walk that keeps its sets asks a
 * number in a span and not one number, since how many slots it looks in to find a set turns on
 * where the sets' hashes fall, which is not worked out here; the span is no wider than that.
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
        for (boolean remembering : new boolean[] {true, false}) {
            Counting all = Counting.never();
            assertEquals(new Outcome.Answered<>(false), wide(machine, remembering).matches("a", all));
            if (remembering) {
                asksRemembering(machine, "a").hold(all.asked, "a");
            } else {
                assertEquals(asksOf(machine, "a"), all.asked);
            }
            for (long at : new long[] {2, all.asked / 2, all.asked}) {
                assertInstanceOf(Outcome.Stopped.class,
                        wide(machine, remembering).matches("a", new Counting(at)),
                        "stopped at ask " + at);
            }
        }
    }

    /** The machine a walk is run over: one that keeps the sets of states it is in, or one that keeps
     *  none and moves each state for every character. */
    private static StringPattern wide(Automaton machine, boolean remembering) {
        return remembering ? StringPattern.of(machine) : StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(0));
    }

    /** A walk over a large machine stopped at its first ask has not made the room the walk is
     *  held in, which is as large as the machine. */
    @Test
    void aStopAtTheFirstAskHasMadeNothingAsLargeAsTheMachine() {
        StringPattern large = StringPattern.of(PatternMachine.of(meaning("a{200000}")).shaped());
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        Outcome<Boolean> stopped = large.matches("a", new Counting(1));
        long made = threads.getCurrentThreadAllocatedBytes() - before;
        assertInstanceOf(Outcome.Stopped.class, stopped);
        assertTrue(made < 100_000, "made " + made + " bytes");
    }

    /** A walk over a deterministic machine asks once a character, and is stopped where it is asked
     *  to be. */
    @Test
    void aLongSubjectIsStoppedPartOfTheWayThrough() {
        StringPattern run = StringPattern.of(ClassRows.of(Held.canonical(meaning("[a-z]*"), Held.roomy())));
        String subject = "a".repeat(1_000_000);
        Counting all = Counting.never();
        assertEquals(new Outcome.Answered<>(true), run.matches(subject, all));
        assertEquals(subject.length(), all.asked, "once a character");
        assertInstanceOf(Outcome.Stopped.class, run.matches(subject, new Counting(1_000)));
    }

    /**
     * A walk asks as often as the class says, over no character, one, or several. A walk that moves
     * each state for every character asks before it makes its room, for each state and step for no
     * character it takes in, for each character, state moved and step looked at, and, once the
     * subject is read, for each state it ended in as it looks through them for one it may stop at.
     */
    @Test
    void aWalkAsksAtEveryPlaceItSaysItDoes() {
        Automaton machine = Automaton.of(meaning("(a?){1000}b"), Held.roomy());
        StringPattern wide = StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(0));
        assertEquals(StringPattern.Way.EVERY_STATE, wide.way());
        for (String subject : List.of("", "a", "aaa", "ba")) {
            Counting all = Counting.never();
            assertEquals(new Outcome.Answered<>(false), wide.matches(subject, all), subject);
            assertEquals(asksOf(machine, subject), all.asked, subject);
        }
    }

    /**
     * A walk that keeps the sets of states it is in asks for each character, and where the set the
     * character leads to is not yet known, before it makes its room, for each state moved, step
     * looked at, and state and step for no character it takes in, for each state of the set as it
     * sums its hash, for each place it looks for the set among those kept and each state of a kept
     * one it holds against it, and, where it keeps the set, for each state it looks at for one it
     * may stop at.
     */
    @Test
    void aWalkKeepingItsSetsAsksAtEveryPlaceItSaysItDoes() {
        Automaton machine = Automaton.of(meaning("(a?){1000}b"), Held.roomy());
        assertWalkKeepingItsSetsAsksAsItSays(machine, List.of("", "a", "aaa", "ba", "aab"));
        // Two characters of one class, from one set the walk stays in: the second is looked up as
        // the first was.
        Automaton classed = Automaton.of(meaning("(a?){1000}[bc]*d"), Held.roomy());
        assertWalkKeepingItsSetsAsksAsItSays(classed, List.of("abcb", "acbc", "aabcbc", "abcbd"));
    }

    private static void assertWalkKeepingItsSetsAsksAsItSays(Automaton machine, List<String> subjects) {
        for (String subject : subjects) {
            Counting all = Counting.never();
            boolean accepted = machine.accepts(subject, Held.roomy());
            assertEquals(new Outcome.Answered<>(accepted),
                    StringPattern.of(machine).matches(subject, all), subject);
            asksRemembering(machine, subject).hold(all.asked, subject);
        }
    }

    /**
     * Where a character leads from a set is worked out once and kept with the set, so a walk that
     * comes to it again asks once a character, as over a deterministic machine.
     */
    @Test
    void aSetWorkedOutOnceIsLookedUpAfter() {
        Automaton machine = Automaton.of(meaning("(a|b)*a(a|b){3}"), Held.roomy());
        StringPattern run = StringPattern.of(machine);
        String subject = "abba".repeat(50) + "abab";
        assertEquals(new Outcome.Answered<>(true), run.matches(subject, Counting.never()));
        Counting again = Counting.never();
        assertEquals(new Outcome.Answered<>(true), run.matches(subject, again));
        assertEquals(subject.length(), again.asked, "once a character");
    }

    /**
     * A walk that would need a set past those a pattern keeps goes on from the one it is in, moving
     * each state for every character, and answers the same: with a checkpoint or without, and
     * stopped wherever it is asked to stop.
     */
    @Test
    void aWalkPastTheSetsKeptGoesOnByMovingEachState() {
        Automaton machine = Automaton.of(meaning("(a?){30}b(a|b)*"), Held.roomy());
        List<String> subjects = List.of("", "b", "ab", "a".repeat(29) + "b", "a".repeat(30) + "b",
                "a".repeat(31) + "b", "a".repeat(10) + "bab", "aaaa", "\uD800", "aa\uD800b");
        for (int most : new int[] {1, 2, 3, 5, 40}) {
            StringPattern run = StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(most));
            assertEquals(StringPattern.Way.SETS_KEPT, run.way());
            for (String subject : subjects) {
                boolean accepted = machine.accepts(subject, Held.roomy());
                assertEquals(accepted, run.matches(subject), most + " " + subject);
                assertEquals(new Outcome.Answered<>(accepted), run.matches(subject, Counting.never()),
                        most + " " + subject);
                // Counted on a pattern no walk has been run on, as each stopped walk is.
                Counting all = Counting.never();
                assertEquals(new Outcome.Answered<>(accepted),
                        StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(most)).matches(subject, all), most + " " + subject);
                for (long at = 1; at <= all.asked; at += Math.max(1, all.asked / 7)) {
                    assertInstanceOf(Outcome.Stopped.class,
                            StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(most)).matches(subject, new Counting(at)),
                            most + " " + subject + " stopped at ask " + at);
                }
            }
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
            for (StringPattern run : List.of(PatternMachine.of(meaning).pattern(), StringPattern.of(shaped),
                    StringPattern.of(shaped, StringPattern.Budget.DEFAULT.keeping(0)),
                    StringPattern.of(shaped, StringPattern.Budget.DEFAULT.keeping(2)))) {
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

    /** The fewest and the most times a walk says it asks. */
    record Asks(long fewest, long most) {

        Asks plus(long fewer, long more) {
            return new Asks(fewest + fewer, most + more);
        }

        void hold(long asked, String subject) {
            assertTrue(fewest <= asked && asked <= most,
                    "\"" + subject + "\" asked " + asked + " times, and says " + fewest + " to " + most);
        }
    }

    /**
     * How many times a walk that keeps the sets of states it is in says it asks over {@code subject},
     * on a pattern no walk has been run on yet, worked out over sets of states and apart from the
     * walk: once for each character; and where the set that character's class leads to from the one
     * the walk is in is not yet known, once before the first such room is made, once for each state
     * moved and each step looked at from one, as {@link #close} counts for each state taken in. Where
     * that set has states, once for each as its hash is summed, and once for each slot looked in for
     * it among those kept. Where it is one of them, once more for each of its states as it is held
     * against them. Where it is not, it is kept, and its states are looked through for one the walk
     * may stop at, once for each until one is found.
     *
     * <p>The slots looked in are the first, and past it one for each kept set in the way, which turns
     * on where the hashes fall: at most every set kept but the one looked for. A kept set in the way
     * with as many states may have the same hash, and is then held against the set too, once for each
     * of its states at most. Where in the set the state it may stop at is turns on the order the walk
     * put the states in, so finding it is once at fewest and once for each state at most. The walk
     * starts in the set its first state is in, made with the pattern, and where that is empty it asks
     * nothing.
     *
     * <p>A class is worked out here apart from the walk's: two symbols are in one where every set a
     * step of the machine is over holds both or neither ({@link #classOf}).
     */
    static Asks asksRemembering(Automaton machine, String subject) {
        boolean[] live = live(machine);
        Asks asks = new Asks(0, 0);
        boolean room = false;
        Set<Set<Integer>> kept = new java.util.HashSet<>();
        java.util.Map<List<Object>, Set<Integer>> known = new java.util.HashMap<>();
        Set<Integer> here = new LinkedHashSet<>();
        close(machine, live, Automaton.START, here);
        if (here.isEmpty()) {
            return asks;
        }
        kept.add(here);
        for (int at = 0; at < subject.length(); ) {
            int symbol = subject.codePointAt(at);
            at += Character.charCount(symbol);
            asks = asks.plus(1, 1);
            List<Object> key = List.of(here, classOf(machine, symbol));
            Set<Integer> there = known.get(key);
            if (there == null) {
                if (!room) {
                    asks = asks.plus(1, 1);
                    room = true;
                }
                there = new LinkedHashSet<>();
                long moved = 0;
                for (int state : here) {
                    moved++;
                    for (Automaton.Step step : machine.stepsFrom(state)) {
                        moved++;
                        if (step.over().has(symbol)) {
                            moved += close(machine, live, step.to(), there);
                        }
                    }
                }
                asks = asks.plus(moved, moved);
                if (!there.isEmpty()) {
                    int size = there.size();
                    boolean held = kept.contains(there);
                    // Its hash, and the first slot looked in.
                    asks = asks.plus(size + 1, size + 1);
                    long inTheWay = 0;
                    for (Set<Integer> other : kept) {
                        if (!other.equals(there)) {
                            inTheWay += 1 + (other.size() == size ? size : 0);
                        }
                    }
                    asks = asks.plus(0, inTheWay);
                    if (held) {
                        asks = asks.plus(size, size);
                    } else {
                        kept.add(there);
                        boolean stops = false;
                        for (int state : there) {
                            stops |= machine.stopsAt(state);
                        }
                        asks = asks.plus(stops ? 1 : size, size);
                    }
                }
                known.put(key, there);
            }
            if (there.isEmpty()) {
                return asks;
            }
            here = there;
        }
        return asks;
    }

    /** The class of {@code symbol}, as which of the sets the machine's steps are over hold it. */
    private static List<Boolean> classOf(Automaton machine, int symbol) {
        Set<CodePoints> sets = new LinkedHashSet<>();
        for (int state = 0; state < machine.size(); state++) {
            for (Automaton.Step step : machine.stepsFrom(state)) {
                sets.add(step.over());
            }
        }
        List<Boolean> held = new ArrayList<>();
        for (CodePoints set : sets) {
            held.add(set.has(symbol));
        }
        return held;
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
