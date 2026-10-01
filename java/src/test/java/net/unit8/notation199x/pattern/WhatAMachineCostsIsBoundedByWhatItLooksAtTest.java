package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What making, reading and writing a machine costs is bounded by what it looks at, and not by its
 * states alone.
 *
 * <p>A state of a deterministic machine is a row as wide as the symbols it tells apart; a state of
 * a meet is the steps of one side against the other's; a walk over a machine that may be in many
 * states at once looks at all of them per symbol. Each of those is counted where it is looked at
 * ({@link Meter}), and the cases here are the shapes that looked at far more than their states said:
 * a machine within every state limit that nothing stopped.
 *
 * <p>And the limits that answer different questions are held apart where the answer depends on
 * their order: what a reading of the rules gives up on has to be something a class still runs.
 */
class WhatAMachineCostsIsBoundedByWhatItLooksAtTest {


    /**
     * And the characters a class is given for one image never refuse a machine the state limit let
     * through for its size alone: a repetition written out, as long as the limit allows.
     */
    @Test
    void theLongestRepetitionAClassRunsFitsTheImageItIsGiven() {
        int most = PatternRead.Limit.MACHINE_STATES.most();
        PatternMeaning meaning = meaning("a{" + (most - 10) + "}");
        PatternImage.Written image =
                assertInstanceOf(PatternImage.Written.class, PatternMachine.of(meaning).image());
        StringPattern run = StringPattern.of(image.strings());
        assertTrue(run.matches("a".repeat(most - 10)));
        assertFalse(run.matches("a".repeat(most - 11)));
    }

    /**
     * A class written wide and repeated is two classes however many runs it cuts the symbols into:
     * the characters in it and the rest. Its rows are as wide as that, so making it deterministic
     * costs its states, and the runs are read once.
     */
    @Test
    void aWideClassRepeatedIsMadeDeterministicOverTheTwoClassesItTellsApart() {
        PatternMeaning meaning = meaning(wideClass(3000) + "{2000}");
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Meter meter = Held.roomy();
            assertTrue(Held.canonical(meaning, meter) != null);
            assertNull(meter.stoppedBy());
            assertTrue(PatternMachine.of(meaning).deterministic() != null,
                    "within what a faster run is worth");
        });
    }

    /**
     * A chain of characters each of its own is as many classes as it is long, and its rows are as
     * wide as that: the work of making it deterministic is the square of its length. It is refused
     * on that work, as a machine larger than one may be, and refused early.
     */
    @Test
    void aChainOfCharactersEachOfItsOwnIsRefusedOnTheWorkItsRowsTake() {
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            chain.appendCodePoint(0x20000 + i);
        }
        PatternMeaning meaning = meaning(chain.toString());
        Meter meter = Held.roomy();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertNull(Held.canonical(meaning, meter)));
        assertEquals(Meter.Stopped.ONE_MACHINE, meter.stoppedBy());
        assertTrue(Automaton.of(meaning, Held.roomy()) != null,
                "its shape is within the state limit, so what refused it is the work");
    }

    /**
     * A long chain made smallest costs the chain and not its square: telling its states apart used
     * to take a round per state, each round over every state.
     */
    @Test
    void aLongChainIsMadeSmallestInTimeItsLengthSets() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertTrue(Held.canonical(meaning("a{49000}"), Held.roomy()) != null));
    }

    /** A class is read in time its length sets, and not the square of it. */
    @Test
    void aLongClassIsReadInTimeItsLengthSets() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertInstanceOf(PatternRead.Read.class, PatternParser.read(wideClass(100_000))));
    }

    /**
     * A walk over the shape's machine is in every state the machine may be in at once, and a long
     * subject over a wide machine is refused rather than walked: that match is for the machine that
     * is run, where it is linear in the subject.
     */
    @Test
    void aWalkOfTheShapeOverALongSubjectIsRefusedRatherThanWalked() {
        Automaton machine = Automaton.of(meaning("(a?){10000}"), Held.roomy());
        assertNull(machine.accepts("a".repeat(200_000), Held.roomy()));
        assertEquals(true, machine.accepts("a".repeat(10), Held.roomy()));
    }

    /** A writer says it is past its limit as it goes, and writes nothing out once it is. */
    @Test
    void aWriterPastItsLimitSaysSoAndWritesNothing() {
        StringPattern.Writer out = new StringPattern.Writer(false, 40);
        int at = out.state(false);
        assertTrue(out.holds());
        for (int i = 0; i < 20 && out.holds(); i++) {
            out.step(at, out.set(new int[] {'a', 'a'}), out.state(false));
        }
        assertFalse(out.holds());
        assertThrows(IllegalStateException.class, out::image);
    }

    private static PatternMeaning meaning(String regex) {
        return ((PatternRead.Read) PatternParser.read(regex)).meaning();
    }

    /** A class of {@code many} characters none of which is beside another, all past the basic
     *  plane so that none is half of a pair. */
    private static String wideClass(int many) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < many; i++) {
            out.appendCodePoint(0x20000 + 2 * i);
        }
        return out.append(']').toString();
    }
}
