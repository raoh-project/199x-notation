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
 * <p>The states every implementation counts, and what it answers on either side of each limit, are
 * in {@code suite/pattern-states.txt} and {@code suite/pattern-read.txt}. What is here is Java's own
 * answer where the specifications decide less: which limit is answered where a pattern is past more
 * than one, which refusal text is refused for, and where each points and what it quotes. And what a
 * line of vectors cannot hold: text too deep to write out, and a machine as large as a pattern within
 * the limits builds.
 */
class APatternIsHeldToTheLimitsEveryImplementationHoldsToTest {

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

    /**
     * A limit is about a pattern, so text that is no pattern is refused as that whatever limit it
     * also went past, and wherever in the text the two are: the reading goes on past a count or a
     * depth to the end, and places the anchors, before it says a limit was the answer.
     */
    @Test
    void textThatIsNoPatternIsRefusedWhateverLimitItWentPast() {
        assertEquals(PatternRead.Refusal.AN_ESCAPE_THIS_DOES_NOT_READ, refusal("a{249999}\\q"));
        assertEquals(PatternRead.Refusal.SOMETHING_UNCLOSED, refusal("a{134217728x}"));
        assertEquals(PatternRead.Refusal.A_CHARACTER_PROPERTY, refusal("a{134217728}\\p{L}"));
        assertEquals(PatternRead.Refusal.SOMETHING_UNCLOSED, refusal("a{134217728}("));
        int past = PatternRead.Limit.NESTING_DEPTH.most() + 1;
        assertEquals(PatternRead.Refusal.SOMETHING_UNCLOSED, refusal("(?:".repeat(past) + "a"));
        assertEquals(PatternRead.Refusal.AN_ANCHOR_THIS_CANNOT_PLACE,
                refusal("(?:".repeat(past) + "(a|)^b" + ")".repeat(past)));
        assertEquals(PatternRead.Refusal.A_GROUP_THE_GRAMMAR_DOES_NOT_HAVE,
                refusal("(?:".repeat(past) + "(?=a)" + ")".repeat(past)));
    }

    /** A floor and a ceiling are compared as written, even where both are past the limit. */
    @Test
    void aCeilingBelowItsFloorIsRefusedHoweverLargeTheyAre() {
        assertEquals(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ, refusal("a{200000000,150000000}"));
        assertEquals(PatternRead.Refusal.A_COUNT_THIS_CANNOT_READ,
                refusal("a{99999999999999999999,99999999999999999998}"));
        assertEquals(new PatternRead.Beyond(PatternRead.Limit.REPETITION_COUNT, 2, "000134217728"),
                PatternParser.read("a{000134217728}"));
        assertInstanceOf(PatternRead.Read.class, PatternParser.read("a{0003,0005}"));
    }

    /** Of two limits met, the first in the text is the answer. */
    @Test
    void theFirstLimitInTheTextIsTheAnswer() {
        int past = PatternRead.Limit.NESTING_DEPTH.most() + 1;
        String deep = "(?:".repeat(past) + "a" + ")".repeat(past);
        assertEquals(PatternRead.Limit.REPETITION_COUNT,
                ((PatternRead.Beyond) PatternParser.read("a{134217728}" + deep)).limit());
        assertEquals(PatternRead.Limit.NESTING_DEPTH,
                ((PatternRead.Beyond) PatternParser.read(deep + "a{134217728}")).limit());
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
