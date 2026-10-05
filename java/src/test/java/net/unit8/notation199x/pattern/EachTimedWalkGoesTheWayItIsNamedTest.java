package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each path {@link WalkPaths} times goes the way it is named, so that what a time says is of that
 * way: a match with no kept sets keeps none; one with nothing kept keeps a new set at most
 * characters; one over steps already worked out works none out; and one whose steps are forgotten
 * works out a step at most characters and finds where each leads among the sets kept, keeping no
 * new one.
 */
class EachTimedWalkGoesTheWayItIsNamedTest {

    @Test
    void aMatchOfALargeMachineKeepsNoSets() {
        StringPattern large = WalkPaths.large();
        assertEquals(StringPattern.Way.EVERY_STATE, large.way());
        large.matches(WalkPaths.as());
        assertEquals(0, large.setsKept());
    }

    @Test
    void eachMatchOfTheTenthFromTheEndGoesTheWayItIsNamed() {
        StringPattern tenth = StringPattern.of(WalkPaths.tenth());
        String subject = WalkPaths.random();
        assertEquals(StringPattern.Way.SETS_KEPT, tenth.way());
        int before = tenth.setsKept();
        tenth.matches(subject);
        int kept = tenth.setsKept();
        assertTrue((kept - before) * 2 > subject.length(),
                "a new set at " + (kept - before) + " of " + subject.length() + " characters");

        int steps = tenth.stepsKnown();
        tenth.matches(subject);
        assertEquals(kept, tenth.setsKept(), "no set made over steps worked out");
        assertEquals(steps, tenth.stepsKnown(), "no step worked out again");

        tenth.forgetSteps();
        assertEquals(0, tenth.stepsKnown());
        tenth.matches(subject);
        assertEquals(kept, tenth.setsKept(), "no set made where each is kept");
        assertTrue(tenth.stepsKnown() * 2 > subject.length(),
                "a kept set found at " + tenth.stepsKnown() + " of " + subject.length() + " characters");
    }
}
