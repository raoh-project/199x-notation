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
 * <p>Where each limit falls, and what is answered on either side of it, is in
 * {@code suite/pattern-read.txt} and {@code suite/pattern-states.txt}. What is here is what a line of
 * vectors does not hold: text too deep to write out, a machine as large as a pattern within the
 * limits builds, the count against the machine that is built, and where what is past a limit
 * is quoted from, which the specification does not state.
 */
class APatternIsHeldToTheLimitsEveryImplementationHoldsToTest {

    /**
     * What is past a limit is quoted from where it begins: a count as written, every digit of it,
     * and the whole pattern for the states, which are a fact about all of it. The specification
     * does not state where a pattern past a limit points, so this is Java's answer and not a vector
     * in {@code suite/}.
     */
    @Test
    void whatIsPastALimitIsQuotedFromWhereItBegins() {
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "a{249999}"),
                PatternParser.read("a{249999}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "(a{500}){500}"),
                PatternParser.read("(a{500}){500}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.MACHINE_STATES, 0, "a{134217727}"),
                PatternParser.read("a{134217727}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 2, "134217728"),
                PatternParser.read("a{134217728}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 4, "99999999999"),
                PatternParser.read("a{0,99999999999}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 2, "000134217728"),
                PatternParser.read("a{000134217728}"));
    }

    /**
     * Text nested as deeply as it is long is read to its end, and never runs the stack out: the
     * reading and the placing of anchors keep stacks of their own.
     */
    @Test
    void textNestedAMillionDeepIsReadToItsEnd() {
        int deep = 1_000_000;
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            assertEquals(PatternRead.Limit.NESTING_DEPTH, ((PatternRead.Beyond) PatternParser.read(
                    "(?:".repeat(deep) + "a" + ")".repeat(deep))).limit());
            assertEquals(PatternRead.Refusal.AN_ANCHOR_THIS_CANNOT_PLACE, refusal(
                    "(?:".repeat(deep) + "a*^b" + ")".repeat(deep)));
            assertEquals(PatternRead.Refusal.SOMETHING_UNCLOSED, refusal(
                    "(?:".repeat(deep) + "a" + ")".repeat(deep - 1)));
        });
    }

    private static PatternRead.Refusal refusal(String regex) {
        return assertInstanceOf(PatternRead.Refused.class, PatternParser.read(regex), regex).why();
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
