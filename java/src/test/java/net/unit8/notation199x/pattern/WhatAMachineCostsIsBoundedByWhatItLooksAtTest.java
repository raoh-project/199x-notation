package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
            assertTrue(PatternMachine.of(meaning).rows() != null,
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
     * A set is held once in a machine however many steps are over it, so a deterministic machine
     * every state of which steps over one wide class holds that class once: making it, running it
     * and writing it out each read the class once and not once a state.
     */
    @Test
    void aWideClassEveryStateStepsOverIsHeldOnce() {
        PatternMeaning meaning = meaning(wideClass(3000) + "{2000}");
        Automaton deterministic = Objects.requireNonNull(Held.canonical(meaning, Held.roomy()));
        Set<CodePoints> sets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int state = 0; state < deterministic.size(); state++) {
            for (Automaton.Step each : deterministic.stepsFrom(state)) {
                sets.add(each.over());
            }
        }
        assertEquals(3, sets.size(), "the class, the rest, and every symbol out of where a walk is done");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 20; i++) {
                assertEquals(StringPattern.Way.TABLE, StringPattern.of(deterministic, true).way());
                assertInstanceOf(PatternImage.Written.class, PatternImages.p2(deterministic));
                assertTrue(deterministic.shortest() != null);
            }
        });
    }

    /**
     * An image is held to what reading it looks at however many states step over one set: a set
     * of two hundred thousand runs that every state steps over is read as its classes, and not as
     * its runs once a state.
     */
    @Test
    void anImageWhoseStatesAllStepOverOneWideSetIsReadAsItsClasses() {
        int runs = 200_000;
        int states = 20_000;
        StringBuilder image = new StringBuilder("P1,1,1,").append(runs);
        for (int i = 0; i < runs; i++) {
            image.append(',').append(0x10000 + 2 * i).append(',').append(0x10000 + 2 * i);
        }
        image.append(',').append(states);
        for (int state = 0; state < states; state++) {
            image.append(",1,1,0,").append((state + 1) % states).append(",0");
        }
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            StringPattern run = StringPattern.of(List.of(image.toString()));
            assertEquals(StringPattern.Way.TABLE, run.way());
            assertTrue(run.matches(new StringBuilder().appendCodePoint(0x10000).appendCodePoint(0x10002)
                    .toString()));
            assertFalse(run.matches(new StringBuilder().appendCodePoint(0x10001).toString()));
        });
    }

    /**
     * An image of P2 is read in one pass, however many states step over the same wide classes: the
     * machine of issue #27, two classes of a hundred thousand runs each that every state steps over
     * to states of its own, which an image of P1 that says it is deterministic makes a reader hold
     * against each other at every state.
     */
    @Test
    void anImageOfP2IsReadInOnePassHoweverManyStatesStepOverWideClasses() {
        int runs = 100_000;
        int states = 100_000;
        int dead = states - 1;
        StringBuilder image = new StringBuilder("P2");
        for (int i = 0; i < runs; i++) {
            int at = 0x10000 + 4 * i;
            image.append(',').append(at - 1).append(",0,").append(at).append(",1,")
                    .append(at + 1).append(",0,").append(at + 2).append(",2");
        }
        image.append(",1114111,0,").append(states);
        for (int state = 0; state < dead; state++) {
            image.append(",1,0,").append(dead).append(",1,").append((state + 1) % dead)
                    .append(",2,").append((state + 2) % dead);
        }
        image.append(",0,2,").append(dead);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            StringPattern run = StringPattern.of(List.of(image.toString()));
            assertTrue(run.matches(new StringBuilder().appendCodePoint(0x10000).appendCodePoint(0x10006)
                    .toString()));
            assertFalse(run.matches(new StringBuilder().appendCodePoint(0x10001).toString()));
            assertTrue(run.matches(""));
        });
    }

    /**
     * And where the classes are too many for a table and the runs every state would hold over again
     * are past what is allowed, a deterministic image is walked as the sets of states its steps lead
     * to, which answers the same, rather than its runs being made whatever they come to.
     */
    @Test
    void anImageWhoseRunsArePastWhatIsAllowedIsWalkedAsItsSteps() {
        String image = manyClassesImage(0x10001);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            StringPattern run = StringPattern.of(List.of(image));
            assertEquals(StringPattern.Way.SETS_KEPT, run.way());
            assertTrue(run.matches(new StringBuilder().appendCodePoint(0x10001).appendCodePoint(0x10000)
                    .toString()));
            assertFalse(run.matches(new StringBuilder().appendCodePoint(0x10000).appendCodePoint(0x10001)
                    .toString()));
        });
    }

    /**
     * Whether an image is one that is read is never a matter of what a walk over it is given to walk
     * faster on: one said to be deterministic that steps two ways is refused however large it is,
     * and past every table and run it could be walked by.
     */
    @Test
    void anImageSteppingTwoWaysIsRefusedHoweverLarge() {
        assertThrows(IllegalArgumentException.class, () -> StringPattern.of(List.of(manyClassesImage(0x10000))));
        StringPattern.of(List.of(manyClassesImage(0x10001)));
    }

    /**
     * An image of a wide set every state steps over and as many characters of their own as are too
     * many for a table, the first state stepping over each; {@code firstSingle} is where those
     * characters begin, beside or inside the wide set.
     */
    private static String manyClassesImage(int firstSingle) {
        int runs = 200_000;
        int singles = 600;
        int states = 1_000;
        StringBuilder image = new StringBuilder("P1,1,").append(1 + singles).append(',').append(runs);
        for (int i = 0; i < runs; i++) {
            image.append(',').append(0x10000 + 2 * i).append(',').append(0x10000 + 2 * i);
        }
        for (int i = 0; i < singles; i++) {
            image.append(",1,").append(firstSingle + 2 * i).append(',').append(firstSingle + 2 * i);
        }
        image.append(',').append(states);
        image.append(",1,").append(1 + singles).append(",0,1");
        for (int i = 0; i < singles; i++) {
            image.append(',').append(1 + i).append(",0");
        }
        image.append(",0");
        for (int state = 1; state < states; state++) {
            image.append(",1,1,0,").append((state + 1) % states).append(",0");
        }
        return image.toString();
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
        StringPattern.P1Writer out = new StringPattern.P1Writer(40);
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
