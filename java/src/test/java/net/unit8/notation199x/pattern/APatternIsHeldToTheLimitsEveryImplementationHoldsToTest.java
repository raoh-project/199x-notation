package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pattern is held to three limits, each the same number in every implementation and each decided
 * from the text: a count, a depth, and the states the pattern comes to with its repetitions written
 * out.
 *
 * <p>The states are counted from what is written ({@link PatternStates}), and the vectors here are
 * the ones the specification's verifier is held to as well: each is put beside a filler that brings
 * the whole to exactly the limit, which is read, and to one past it, which is not. So what is pinned
 * is the count itself and not only that some large pattern is refused.
 */
class APatternIsHeldToTheLimitsEveryImplementationHoldsToTest {

    private static final int MOST = PatternRead.Limit.MACHINE_STATES.most();

    /** A pattern, and the states it is written as, before the one a whole pattern adds. */
    private record Counted(String pattern, long states) {}

    private static final List<Counted> COUNTED = List.of(
            new Counted("", 0),
            new Counted("a", 1),
            new Counted("abc", 3),
            new Counted(".", 1),
            new Counted("[a-z]", 1),
            new Counted("[^a]", 1),
            new Counted("\\d", 1),
            new Counted("\\x{1F600}", 1),
            new Counted("\\uD83D\\uDE00", 1),
            new Counted("😀", 1),
            new Counted("()", 0),
            new Counted("(?:a)", 1),
            new Counted("a|b", 5),
            new Counted("a|b|c", 7),
            new Counted("(?:a|b)|c", 9),
            new Counted("a|", 4),
            new Counted("a?", 2),
            new Counted("a??", 2),
            new Counted("a*", 2),
            new Counted("a+", 3),
            new Counted("a{3}", 4),
            new Counted("a{2,5}", 6),
            new Counted("a{2,}", 4),
            new Counted("a{0,0}", 1),
            new Counted("(?:ab){3}", 7),
            new Counted("(?:a{2}){3}", 10),
            new Counted("(?:a|b)*", 6),
            new Counted("(?:){134217727}", 1),
            new Counted("^a", 2),
            new Counted("a^b", 3),
            new Counted("a$", 2),
            new Counted("^a$", 3));

    /**
     * Each vector is one arm of a choice whose other arm is a filler, so an anchor in it stands at
     * both ends of the string whatever the filler is. The choice is one, each arm one more than
     * itself, and the pattern one more: {@code 4 + pattern + filler}.
     */
    @Test
    void theStatesAreCountedFromWhatIsWritten() {
        List<String> wrong = new ArrayList<>();
        for (Counted each : COUNTED) {
            long filler = MOST - 4 - each.states();
            String at = "(?:" + each.pattern() + ")|" + filler(filler);
            String past = "(?:" + each.pattern() + ")|" + filler(filler + 1);
            if (!(PatternParser.read(at) instanceof PatternRead.Read)) {
                wrong.add(each.pattern() + " at the limit is " + PatternParser.read(at));
            }
            if (!(PatternParser.read(past) instanceof PatternRead.Beyond beyond)
                    || beyond.limit() != PatternRead.Limit.MACHINE_STATES) {
                wrong.add(each.pattern() + " past the limit is " + PatternParser.read(past));
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** A filler of exactly {@code states} states: {@code a{0,n}} is {@code n + 1}. */
    private static String filler(long states) {
        return "a{0," + (states - 1) + "}";
    }

    @Test
    void theStatesAtTheLimitAreReadAndOnePastAreNot() {
        assertInstanceOf(PatternRead.Read.class, PatternParser.read("a{249998}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "a{249999}"),
                PatternParser.read("a{249999}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "(a{500}){500}"),
                PatternParser.read("(a{500}){500}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "a{134217727}"),
                PatternParser.read("a{134217727}"));
    }

    /** A count past its limit is quoted as written, every digit of it. */
    @Test
    void aCountPastItsLimitIsQuotedAsWritten() {
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 2, "134217728"),
                PatternParser.read("a{134217728}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 4, "99999999999"),
                PatternParser.read("a{0,99999999999}"));
    }

    /** Text that is no pattern is refused as that, even where it is also large. */
    @Test
    void textThatIsNoPatternIsRefusedBeforeItIsCounted() {
        assertInstanceOf(PatternRead.Refused.class, PatternParser.read("a{249999}\\p{L}"));
    }

    /**
     * Every pattern that is read has a machine, the largest included, and it answers what the
     * pattern means.
     */
    @Test
    void theLargestPatternThatIsReadHasAMachine() {
        PatternMeaning meaning = ((PatternRead.Read) PatternParser.read("a{0,249998}")).meaning();
        StringPattern run = PatternMachine.of(meaning).pattern();
        assertTrue(run.matches("a".repeat(249_998)));
        assertFalse(run.matches("a".repeat(249_999)));
        assertTrue(run.matches(""));
    }

    /**
     * A repetition of a body that builds no state is one state, and building it costs that and not
     * the count: a count as large as the reader reads, repeated as often as the states allow.
     */
    @Test
    void aRepetitionOfNothingCostsItsStateAndNotItsCount() {
        PatternMeaning meaning =
                ((PatternRead.Read) PatternParser.read("(?:(?:){134217727}){249998}")).meaning();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertTrue(PatternMachine.of(meaning).pattern().matches("")));
    }

    /**
     * The count is never below the states of the machine a pattern builds, and is those states
     * exactly where the pattern has no anchor. An anchor counts one and builds one or none.
     */
    @Test
    void theCountIsTheStatesTheShapeBuilds() {
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        for (String regex : WhatARunWalksIsWhatThePatternMeansTest.written()) {
            if (!(PatternParser.read(regex) instanceof PatternRead.Read read)) {
                continue;
            }
            asked++;
            WrittenPattern written = PatternParser.writtenOf(regex);
            long counted = PatternStates.of(written);
            int built = Automaton.of(read.meaning(), PatternMachine.run()).size();
            if (holdsAnAnchor(written) ? built > counted : built != counted) {
                wrong.add(regex + ": counted " + counted + ", built " + built);
            }
        }
        assertEquals(List.of(), wrong);
        assertTrue(asked > 1000, "the generator reached the reader: " + asked);
    }

    private static boolean holdsAnAnchor(WrittenPattern written) {
        return switch (written) {
            case WrittenPattern.Meant _ -> false;
            case WrittenPattern.Anchor _ -> true;
            case WrittenPattern.InTurn it -> it.parts().stream().anyMatch(
                    APatternIsHeldToTheLimitsEveryImplementationHoldsToTest::holdsAnAnchor);
            case WrittenPattern.EitherOf it -> it.arms().stream().anyMatch(
                    APatternIsHeldToTheLimitsEveryImplementationHoldsToTest::holdsAnAnchor);
            case WrittenPattern.Repeated it -> holdsAnAnchor(it.what());
        };
    }
}
