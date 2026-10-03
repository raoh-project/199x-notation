package net.unit8.notation199x.pattern;

import net.unit8.notation199x.Checkpoint;
import net.unit8.notation199x.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(StringPattern.Budget.DEFAULT.subsets(), pattern.setsKept(), "the sets were filled");

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
        assertEquals(StringPattern.Budget.DEFAULT.subsets(), last[0]);
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
