package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Checkpoint;
import net.unit8.notation199x.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A walk that fills the sets a pattern keeps starts them again, and one that keeps filling them
 * with sets it does not meet again keeps no more for the rest of its match; and none of that is
 * held past the match. The tenth-from-the-end machines here have far more sets than a pattern keeps,
 * and a subject at random comes to a new one at most characters.
 */
class WhatAMatchDecidesGoesNoFurtherThanTheMatchTest {

    /** The seventeenth character from the end is an a. */
    private static final String SEVENTEENTH = "(?:a|b)*a(?:a|b){16}";

    /**
     * After one match that filled the sets kept, another subject is walked by the sets it comes to,
     * kept again: matched once more, it asks once a character, as a walk does that goes only by
     * steps already worked out. A pattern that went on from the full sets by moving each state,
     * and kept doing so after, asked for each state of each set.
     */
    @Test
    void aMatchAfterOneThatFilledTheSetsKeepsItsOwn() {
        StringPattern pattern = StringPattern.of(shaped(SEVENTEENTH));
        String hostile = random(20_000, 7);
        assertEquals(hostile.charAt(hostile.length() - 17) == 'a', pattern.matches(hostile));
        // The budget filled, beside the set a walk starts in and the one kept for the walk that
        // started the sets again, which are kept whatever they take and counted apart.
        assertEquals(StringPattern.Budget.DEFAULT.subsets() + 2, pattern.setsKept(), "the sets were filled");

        // The first match starts the sets again part of the way through, and the second works out
        // the steps it read before that from the first set of the new ones.
        String friendly = "ba".repeat(500_000);
        assertTrue(pattern.matches(friendly));
        assertTrue(pattern.matches(friendly));
        int kept = pattern.setsKept();
        assertTrue(kept < 100, "the sets were started again, " + kept + " kept");
        long[] asked = {0};
        Checkpoint counting = () -> ++asked[0] > 0;
        assertEquals(new Outcome.Answered<>(true), pattern.matches(friendly, counting));
        assertEquals(friendly.length(), asked[0], "once a character");
        assertEquals(kept, pattern.setsKept());
    }

    /**
     * A match that fills the sets it keeps, starts them again and fills them again, having met few
     * of them again, keeps no more: what is kept stays as it was, sets and steps, for the rest of
     * the match. Looked at as the match goes, from its checkpoint.
     */
    @Test
    void aMatchThatKeepsFillingTheSetsKeepsNoMore() {
        StringPattern pattern = StringPattern.of(shaped(SEVENTEENTH));
        List<long[]> seen = new ArrayList<>();
        long[] asked = {0};
        Checkpoint looking = () -> {
            if (++asked[0] % 200_000 == 0) {
                seen.add(new long[] {pattern.setsKept(), pattern.stepsKnown()});
            }
            return true;
        };
        String hostile = random(40_000, 11);
        assertEquals(new Outcome.Answered<>(hostile.charAt(hostile.length() - 17) == 'a'),
                pattern.matches(hostile, looking));
        long[] last = {pattern.setsKept(), pattern.stepsKnown()};
        assertEquals(StringPattern.Budget.DEFAULT.subsets() + 2, last[0]);
        int still = 0;
        for (int i = seen.size() - 1; i >= 0 && seen.get(i)[0] == last[0] && seen.get(i)[1] == last[1]; i--) {
            still++;
        }
        assertTrue(still * 2 > seen.size(), "what is kept stayed as it was for " + still + " of "
                + seen.size() + " looks");
    }

    /**
     * A walk that keeps no more sets still looks each set it comes to up among those kept, and goes
     * on by their steps from one it finds. Here a subject at random fills the sets twice, with a
     * run of a and b between, whose two sets and their steps are kept the second time; a long run of
     * a and b after comes back to them. Each character more of it asks once, as a walk by steps
     * already worked out does.
     */
    @Test
    void aMatchThatKeepsNoMoreGoesOnByTheSetsKept() {
        Automaton machine = shaped(SEVENTEENTH);
        String before = random(3_000, 5) + "ab".repeat(20) + random(3_000, 6);
        long shorter = asks(StringPattern.of(machine), before + "ab".repeat(500));
        long longer = asks(StringPattern.of(machine), before + "ab".repeat(1_000));
        assertEquals(1_000, longer - shorter);
    }

    /**
     * A generation another walk started counts against a match that goes into it as one it started
     * would. Here, as soon as the sets kept are full, they are started again from the match's own
     * checkpoint, as a walk on another thread that filled them would; the match, which meets few of
     * its sets again, goes into the first new one, fills it and keeps no more, so the sets are
     * started again twice however long the subject. A match that went into each new generation it
     * found, uncounted, kept sets in one after another to the end of the subject.
     */
    @Test
    void aMatchCountsTheGenerationsOtherWalksStart() {
        StringPattern pattern = StringPattern.of(shaped(SEVENTEENTH));
        int[] started = {0};
        Checkpoint another = () -> {
            if (pattern.setsKept() >= StringPattern.Budget.DEFAULT.subsets()) {
                started[0]++;
                pattern.startSetsAgain();
            }
            return true;
        };
        String hostile = random(200_000, 13);
        assertEquals(new Outcome.Answered<>(hostile.charAt(hostile.length() - 17) == 'a'),
                pattern.matches(hostile, another));
        assertEquals(2, started[0], "the sets were started again " + started[0] + " times");
        assertEquals(1, pattern.setsKept(), "nothing kept in the last generation but where walks start");
    }

    /**
     * Walks on many threads at once fill the sets, start them again in each other's place and keep
     * no more, each on its own, and each answers what the subject's seventeenth character from the
     * end says.
     */
    @Test
    void walksOnManyThreadsStartingTheSetsAgainAnswerTheSame() throws Exception {
        StringPattern pattern = StringPattern.of(shaped(SEVENTEENTH));
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            int seed = 100 + t;
            Thread thread = new Thread(() -> {
                for (int round = 0; round < 6; round++) {
                    String subject = round % 2 == 0 ? random(5_000, seed + round) : "ba".repeat(2_000 + round);
                    boolean expected = subject.charAt(subject.length() - 17) == 'a';
                    if (pattern.matches(subject) != expected) {
                        failed.add(new AssertionError(subject.length() + " characters, round " + round));
                    }
                }
            });
            thread.setUncaughtExceptionHandler((th, e) -> failed.add(e));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(List.of(), failed);
    }

    /**
     * Walks on many threads, each in the same full generation and each at a set of its own, go
     * into a new generation together: one makes it, with the set it has come to, and the others go
     * into it. What the new generation holds whatever it takes is the set a walk starts in and the
     * set of the walk that made it, and no more, however many walks went into it; once they end,
     * that is what it still holds. A generation that kept the set of each walk going into it
     * whatever it took held one more for each walk, past the budget, long after the walks ended.
     *
     * <p>The budget is made exactly as large as the sets the first forty characters of each
     * subject come to, and those are kept first, so every walk goes by kept steps to a set of its
     * own and finds the generation full at its forty-first character, past the sets the subjects
     * share at their start.
     */
    @Test
    void aGenerationHoldsPastTheBudgetOnlyWhatItWasMadeWith() throws Exception {
        int walks = 8;
        String[] subjects = new String[walks];
        for (int t = 0; t < walks; t++) {
            subjects[t] = random(60, 300 + t);
        }
        StringPattern roomy = StringPattern.of(shaped(SEVENTEENTH));
        for (String subject : subjects) {
            roomy.matches(subject.substring(0, 40));
        }
        // The set a walk starts in is kept beside the budget.
        int within = roomy.setsKept() - 1;
        StringPattern pattern = StringPattern.of(shaped(SEVENTEENTH),
                StringPattern.Budget.DEFAULT.keeping(within));
        for (String subject : subjects) {
            pattern.matches(subject.substring(0, 40));
        }
        assertEquals(within + 1, pattern.setsKept(), "the budget is full of the sets the walks go by");
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(walks);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        for (String subject : subjects) {
            Thread thread = new Thread(() -> {
                boolean[] waited = {false};
                // Every walk has taken up the full generation when it first asks, and waits there
                // for the others, so that none goes on before all are in it.
                Checkpoint first = () -> {
                    if (!waited[0]) {
                        waited[0] = true;
                        try {
                            together.await();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return true;
                };
                boolean expected = subject.charAt(subject.length() - 17) == 'a';
                if (!new Outcome.Answered<>(expected).equals(pattern.matches(subject, first))) {
                    failed.add(new AssertionError(subject));
                }
            });
            thread.setUncaughtExceptionHandler((th, e) -> failed.add(e));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(List.of(), failed);
        assertEquals(2, pattern.keptBeside()[0],
                "kept past the budget: the start set and the set of the walk that made the generation");
        int kept = pattern.setsKept();
        assertTrue(kept > walks && kept <= within + 2,
                "the other walks kept their sets within the budget, " + kept + " kept of " + within);
    }

    /**
     * A machine whose sets cut the symbols into tens of thousands of classes keeps its sets with a
     * row only as wide as the classes ASCII is in, and the steps past the row as they are found:
     * what the set a walk starts in holds is its states and that row, and not a place for every
     * class. Matched again, a subject of characters past ASCII goes by the steps kept, making no
     * set and no step. Before, each set held a place for every class, tens of thousands for a set
     * of a few states.
     */
    @Test
    void aSetHoldsARowAsWideAsTheClassesAsciiIsIn() {
        // Twenty thousand characters one after another, each a set of its own, so each is a class,
        // and each set of states a walk is in is one state.
        StringBuilder text = new StringBuilder();
        for (int c = 0x100; c < 0x100 + 2 * 20_000; c += 2) {
            text.appendCodePoint(c);
        }
        StringPattern pattern = StringPattern.of(shaped(text.toString()));
        assertEquals(StringPattern.Way.SETS_KEPT, pattern.way());
        long[] beside = pattern.keptBeside();
        assertEquals(1, beside[0], "the set a walk starts in");
        assertTrue(beside[1] <= 1 + 128, "the set a walk starts in holds " + beside[1]);
        String once = text.substring(0, 300);
        assertFalse(pattern.matches(once));
        int sets = pattern.setsKept();
        int steps = pattern.stepsKnown();
        assertEquals(301, sets, "a set for each character, and the one a walk starts in");
        assertEquals(300, steps, "a step for each character");
        assertFalse(pattern.matches(once));
        assertEquals(sets, pattern.setsKept(), "no set made matched again");
        assertEquals(steps, pattern.stepsKnown(), "no step made matched again");
        assertTrue(pattern.matches(text.toString()));
    }

    /**
     * Walks on many threads at once keep steps past the rows in one table, which they grow in each
     * other's place, and each answers what a walk that keeps nothing does.
     */
    @Test
    void walksOnManyThreadsKeepingStepsPastTheRowsAnswerTheSame() throws Exception {
        String text = "(?:[\\x{100}-\\x{1FF}]|\\x{300}|\\x{302}|\\x{304}|\\x{306}|b)*a(?:[\\x{100}-\\x{1FF}]|\\x{300}|b){6}";
        StringPattern pattern = StringPattern.of(shaped(text));
        StringPattern none = StringPattern.of(shaped(text), StringPattern.Budget.DEFAULT.keeping(0));
        assertEquals(StringPattern.Way.EVERY_STATE, none.way());
        int[] symbols = {0x100, 0x150, 0x300, 0x302, 0x304, 0x306, 'a', 'b'};
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            int seed = 500 + t;
            Thread thread = new Thread(() -> {
                int at = seed;
                for (int round = 0; round < 40; round++) {
                    StringBuilder subject = new StringBuilder();
                    for (int i = 0; i < 200; i++) {
                        at = at * 1_664_525 + 1_013_904_223;
                        subject.appendCodePoint(symbols[(at >>> 16) % symbols.length]);
                    }
                    String each = subject.toString();
                    if (pattern.matches(each) != none.matches(each)) {
                        failed.add(new AssertionError(each));
                    }
                }
            });
            thread.setUncaughtExceptionHandler((th, e) -> failed.add(e));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(List.of(), failed);
    }

    /**
     * A match whose new steps lead only to sets already kept, and fill the budget with steps past
     * the rows, keeps no more once it fills a generation the second time having read little by
     * steps worked out, as a match that fills it with sets does: what it worked out is counted step
     * by step, and not set by set. The y before the thousand and twenty-four characters cuts them
     * into a class each, and every one of them leads the set a walk goes round back to itself.
     * Counted in sets made, the match counted nothing it worked out and went into a new generation
     * each time it filled one, to the end of the subject. Looked at as the match goes, from its
     * checkpoint.
     */
    @Test
    void aMatchThatFillsTheBudgetWithStepsKeepsNoMore() {
        StringBuilder text = new StringBuilder("y(?:");
        for (int c = 0x100; c < 0x500; c++) {
            if (c > 0x100) {
                text.append('|');
            }
            text.appendCodePoint(c);
        }
        text.append(")|[\\x{100}-\\x{4FF}]*");
        // Room for the sets and a table of a few dozen steps past the rows.
        StringPattern pattern = StringPattern.of(shaped(text.toString()), new StringPattern.Budget(
                StringPattern.Budget.DEFAULT.classWork(), StringPattern.Budget.DEFAULT.tableEntries(),
                StringPattern.Budget.DEFAULT.asciiEntries(), StringPattern.Budget.DEFAULT.runs(),
                StringPattern.Budget.DEFAULT.subsets(), 400));
        assertEquals(StringPattern.Way.SETS_KEPT, pattern.way());
        List<long[]> seen = new ArrayList<>();
        long[] asked = {0};
        Checkpoint looking = () -> {
            if (++asked[0] % 2_000 == 0) {
                seen.add(new long[] {pattern.setsKept(), pattern.stepsKnown()});
            }
            return true;
        };
        StringBuilder subject = new StringBuilder();
        int at = 11;
        for (int i = 0; i < 40_000; i++) {
            at = at * 1_664_525 + 1_013_904_223;
            subject.appendCodePoint(0x100 + (at >>> 22));
        }
        assertEquals(new Outcome.Answered<>(true), pattern.matches(subject.toString(), looking));
        assertTrue(pattern.setsKept() <= 3, pattern.setsKept() + " sets kept");
        long[] last = seen.get(seen.size() - 1);
        int still = 0;
        for (int i = seen.size() - 1; i >= 0 && seen.get(i)[0] == last[0] && seen.get(i)[1] == last[1]; i--) {
            still++;
        }
        assertTrue(still * 2 > seen.size(), "what is kept stayed as it was for " + still + " of "
                + seen.size() + " looks");
    }

    /**
     * A walk told to stop leaves nothing counted that is not held: stopped at each place it asks,
     * one after another, what the budget counts is what the sets and the table of steps past the
     * rows take, and a match after, told nothing, keeps as many steps as one on a new pattern.
     * Before, a walk that grew the table counted the larger one before making it, and one stopped
     * while it copied the steps into it left that counted, held by nothing, for every match after.
     */
    @Test
    void aWalkToldToStopLeavesNothingCountedThatIsNotHeld() {
        // Six hundred characters one after another, each a class of its own, more than a row holds.
        StringBuilder text = new StringBuilder();
        for (int c = 0x100; c < 0x100 + 2 * 600; c += 2) {
            text.appendCodePoint(c);
        }
        Automaton shaped = shaped(text.toString());
        String once = text.substring(0, 200);
        long[] asked = {0};
        assertEquals(new Outcome.Answered<>(false),
                StringPattern.of(shaped).matches(once, () -> ++asked[0] > 0));
        StringPattern fresh = StringPattern.of(shaped);
        assertFalse(fresh.matches(once));
        for (long stop = 1; stop < asked[0]; stop++) {
            StringPattern each = StringPattern.of(shaped);
            long[] count = {0};
            long at = stop;
            assertEquals(new Outcome.Stopped<>(), each.matches(once, () -> ++count[0] < at));
            long[] counted = each.counted();
            assertEquals(counted[1], counted[0], "stopped at ask " + stop);
            if (stop % 53 == 0) {
                // Matched again, told nothing, it keeps what a new pattern keeps.
                assertFalse(each.matches(once));
                assertEquals(fresh.stepsKnown(), each.stepsKnown(), "after a stop at ask " + stop);
                assertEquals(fresh.counted()[0], each.counted()[0], "after a stop at ask " + stop);
            }
        }
    }

    /** How many times a match of {@code subject}, which ends in b and is not accepted, asks. */
    private static long asks(StringPattern pattern, String subject) {
        long[] asked = {0};
        assertEquals(new Outcome.Answered<>(false), pattern.matches(subject, () -> ++asked[0] > 0));
        return asked[0];
    }

    /** {@code length} characters, each a or b, at random but the same for the same seed. */
    private static String random(int length, int seed) {
        StringBuilder out = new StringBuilder();
        int at = seed;
        for (int i = 0; i < length; i++) {
            at = at * 1_664_525 + 1_013_904_223;
            out.append((at >>> 31) == 0 ? 'a' : 'b');
        }
        return out.toString();
    }

    private static Automaton shaped(String pattern) {
        PatternRead.Read read = (PatternRead.Read) PatternParser.read(pattern);
        return PatternMachine.of(read.meaning()).shaped();
    }
}
